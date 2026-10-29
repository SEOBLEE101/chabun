package com.recovery.companion;

import java.time.Instant;

/** Executable without Android or extra dependencies: scripts/test-share-policy.ps1. */
public final class SharePolicyTest {
    private static final Instant NOW = Instant.parse("2026-10-24T00:00:00Z");
    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        reject("Health Connect over emulator HTTP is blocked even in debug", () -> share("http://10.0.2.2:8787", true, "health_connect", true, NOW));
        reject("Health Connect over USB HTTP is blocked even in debug", () -> share("http://127.0.0.1:8787", true, "health_connect", true, NOW));
        allow("Health Connect HTTPS remains available", () -> share("https://relay.example", false, "health_connect", true, NOW));
        for (String host : new String[]{"10.0.2.2", "127.0.0.1", "localhost"}) {
            allow("Explicit debug demo over " + host, () -> share("http://" + host + ":8787", true, "demo", true, NOW));
        }
        reject("Demo cannot be sent by release builds even over HTTPS", () -> share("https://relay.example", false, "demo", true, NOW));
        reject("Release HTTP rejected", () -> share("http://127.0.0.1:8787", false, "demo", true, NOW));
        reject("Remote HTTP rejected", () -> share("http://relay.example", true, "demo", true, NOW));
        reject("No consent blocks demo", () -> share("http://127.0.0.1:8787", true, "demo", false, NOW));
        reject("Unknown provenance rejected", () -> share("https://relay.example", true, "unknown", true, NOW));
        allow("45m workout is fresh within 120m window", () -> share("https://relay.example", true, "demo", true, NOW.minusSeconds(45 * 60)));
        allow("119m59s workout is fresh", () -> share("https://relay.example", true, "demo", true, NOW.minusSeconds(7199)));
        reject("Exactly 120m workout expires", () -> share("https://relay.example", true, "demo", true, NOW.minusSeconds(7200)));
        reject("Future workout rejected", () -> share("https://relay.example", true, "demo", true, NOW.plusSeconds(1)));
        for (String origin : new String[]{"https://user:pass@relay.example", "https://relay.example/path", "https://relay.example?token=secret", "https://relay.example#fragment", "http://127.0.0.1.evil.example"}) {
            reject("Malformed or unsafe relay " + origin, () -> SharePolicy.validateBase(origin, true));
        }
        allow("Local HTTP pairing remains available without health data", () -> SharePolicy.validateBase("http://10.0.2.2:8787", true));
        String validPairId = "0123456789abcdef0123456789abcdef";
        String validToken = "abcdefghijklmnopqrstuvwxyz0123456789";
        allow("Valid claim response accepted", () -> SharePolicy.validateClaimResponse(validPairId, validToken, "0421", "2026-10-24T00:05:00Z"));
        reject("Claim response missing token rejected (R01)", () -> SharePolicy.validateClaimResponse(validPairId, "", "0421", "2026-10-24T00:05:00Z"));
        allow("Valid paired status response accepted", () -> {
            if (!SharePolicy.validateStatusResponse(validPairId, validPairId, "paired", null, "2026-10-24T02:00:00Z")) throw new IllegalStateException();
        });
        reject("Empty 200 status response rejected (R01)", () -> SharePolicy.validateStatusResponse(validPairId, "", "", null, ""));
        reject("Mismatched pairingId status rejected (R01)", () -> SharePolicy.validateStatusResponse(validPairId, "fedcba9876543210fedcba9876543210", "paired", null, "2026-10-24T02:00:00Z"));
        allow("Valid summary response accepted", () -> SharePolicy.validateSummaryResponse(true, "0123456789abcdef01234567", "2026-10-24T00:00:00Z", "2026-10-24T00:30:00Z"));
        reject("Summary 2xx without accepted=true rejected (R01)", () -> SharePolicy.validateSummaryResponse(false, "0123456789abcdef01234567", "2026-10-24T00:00:00Z", "2026-10-24T00:30:00Z"));
        reject("Summary expiration before receipt is not a server acceptance", () -> SharePolicy.validateSummaryResponse(true, "0123456789abcdef01234567", "2026-10-24T00:10:00Z", "2026-10-24T00:09:59Z"));
        reject("Summary with zero lifetime cannot be accepted", () -> SharePolicy.validateSummaryResponse(true, "0123456789abcdef01234567", "2026-10-24T00:10:00Z", "2026-10-24T00:10:00Z"));
        reject("Summary lifetime beyond contract is rejected", () -> SharePolicy.validateSummaryResponse(true, "0123456789abcdef01234567", "2026-10-24T00:00:00Z", "2026-10-24T00:30:01Z"));
        allow("Valid delete response accepted", () -> SharePolicy.validateDeleteResponse(true));
        reject("Delete 2xx without deleted=true rejected (R01)", () -> SharePolicy.validateDeleteResponse(false));
        reject("String accepted is not boolean approval", () -> SharePolicy.validateSummaryResponse("true", "0123456789abcdef01234567", "2026-10-24T00:00:00Z", "2026-10-24T00:30:00Z"));
        reject("Numeric accepted is not boolean approval", () -> SharePolicy.validateSummaryResponse(1, "0123456789abcdef01234567", "2026-10-24T00:00:00Z", "2026-10-24T00:30:00Z"));
        reject("String deleted does not clear retry authority", () -> SharePolicy.validateDeleteResponse("true"));
        reject("Null deleted does not clear retry authority", () -> SharePolicy.validateDeleteResponse(null));
        allow("Clipped summary lifetime is accepted", () -> SharePolicy.validateSummaryResponse(true, "0123456789abcdef01234567", "2026-10-24T00:00:00Z", "2026-10-24T00:00:01Z"));
        long started = NOW.toEpochMilli();
        allow("Retry ends with the earlier server expiry", () -> {
            long deadline = SharePolicy.revocationDeadline("2026-10-24T00:05:00Z", started);
            if (deadline != started + 300_000 || !SharePolicy.canRetryRevocation(started, deadline, started + 299_999)
                    || SharePolicy.canRetryRevocation(started, deadline, started + 300_000)) throw new IllegalStateException();
        });
        allow("Unknown legacy expiry is locally capped at two hours", () -> {
            long deadline = SharePolicy.revocationDeadline(null, started);
            if (deadline != started + 7_200_000 || SharePolicy.canRetryRevocation(started, deadline, deadline)) throw new IllegalStateException();
        });
        allow("Malformed saved expiry cannot create unbounded retention", () -> {
            if (SharePolicy.revocationDeadline("not-a-date", started) != started + 7_200_000) throw new IllegalStateException();
        });
        allow("A backwards clock change ends retry retention", () -> {
            if (SharePolicy.canRetryRevocation(started, started + 300_000, started - 1)) throw new IllegalStateException();
        });
        allow("An oversized persisted retry window is rejected", () -> {
            if (SharePolicy.canRetryRevocation(started, started + 7_200_001, started)) throw new IllegalStateException();
        });
        System.out.println("RESULT passed=" + passed + " failed=" + failed);
        if (failed != 0) throw new AssertionError("Share policy regression failures: " + failed);
    }

    private static void share(String base, boolean debug, String source, boolean consent, Instant end) {
        SharePolicy.requireShare(base, debug, source, consent, end, NOW);
    }
    private static void reject(String scenario, Runnable operation) {
        try { operation.run(); failed++; System.out.println("FAIL: " + scenario + " (unexpectedly allowed)"); }
        catch (IllegalArgumentException | IllegalStateException expected) { passed++; System.out.println("PASS: " + scenario); }
    }
    private static void allow(String scenario, Runnable operation) {
        try { operation.run(); passed++; System.out.println("PASS: " + scenario); }
        catch (RuntimeException error) { failed++; System.out.println("FAIL: " + scenario + ": " + error); }
    }
}
