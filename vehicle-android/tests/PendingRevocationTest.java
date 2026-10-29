package ai.pitin.vehicle;

/** Network outcomes and stale replies must not erase a still-usable deletion credential. */
public final class PendingRevocationTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        PendingRevocation pending = new PendingRevocation();
        pending.retain("test-delete-only-credential", 10_000);
        long attempt = pending.generation();
        for (int status : new int[]{-1, 200, 202, 400, 403, 409, 429, 500, 503}) {
            check(pending.resolve(attempt, status, false) == PendingRevocation.Outcome.PENDING,
                    "An unconfirmed response must remain pending: " + status);
            check("test-delete-only-credential".equals(pending.token()), "Retry must retain the same credential");
        }
        check(!pending.expire(9_999), "Valid credentials must remain retryable");
        check(pending.resolve(attempt, 200, true) == PendingRevocation.Outcome.DELETED, "Explicit deletion is confirmed");
        check(!pending.pending() && pending.token() == null, "Confirmed deletion releases the credential");

        pending.retain("new-session-credential", 20_000);
        check(pending.resolve(attempt, 200, true) == PendingRevocation.Outcome.NONE, "An old success cannot resolve a new session");
        check(pending.pending(), "Stale completion must not erase the new session");
        boolean replaced = false;
        try { pending.retain("replacement", 30_000); } catch (IllegalStateException expected) { replaced = true; }
        check(replaced && "new-session-credential".equals(pending.token()), "Unfinished deletion cannot be overwritten");
        check(pending.expire(20_000) && !pending.pending(), "Expiry ends retry without claiming deletion");

        for (int status : new int[]{401, 404, 410}) {
            pending.retain("terminal-credential", 50_000);
            PendingRevocation.Outcome result = pending.resolve(pending.generation(), status, false);
            check(result == (status == 401 ? PendingRevocation.Outcome.AUTH_ENDED : PendingRevocation.Outcome.ABSENT),
                    "Terminal status must distinguish unavailable auth from absent data");
            check(!pending.pending(), "Terminal response releases credentials");
        }
        pending.retain("unknown-expiry-credential", 0);
        check(!pending.expire(Long.MAX_VALUE), "Unknown expiry is not a deletion confirmation");
        pending.clear();
        check(!pending.pending(), "Destroying the screen clears in-memory deletion state");
        check(PendingRevocation.deadline("2026-10-28T01:00:00Z") == 1793149200000L, "Server deadline uses its ISO instant");
        check(PendingRevocation.deadline("invalid") == 0, "Invalid deadlines must not create a false expiry");
        check(PendingRevocation.message(PendingRevocation.Outcome.AUTH_ENDED).contains("확인하지 못"), "Auth failure is not described as server deletion");
        System.out.println("PASS: " + checks + " deletion retry assertions");
    }
}
