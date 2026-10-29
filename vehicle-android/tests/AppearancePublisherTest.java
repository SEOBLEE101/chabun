package ai.pitin.vehicle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Theme reporting races, idempotent retry and strict independent appearance envelopes. */
public final class AppearancePublisherTest {
    private static int assertions;
    private static final String REQUEST_A = "00000000-0000-4000-8000-000000000001";
    private static final String REQUEST_B = "00000000-0000-4000-8000-000000000002";
    private static void check(boolean value, String reason) {
        assertions++; if (!value) throw new AssertionError(reason);
    }
    private static Map<String, Object> object(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
    private static Map<String, Object> report() {
        return object("deviceId", "vehicle-a", "themeId", "graphite", "paletteVersion", 1, "revision", 1,
                "reportedAt", "2026-10-28T00:00:00Z", "expiresAt", "2026-10-28T00:02:00Z");
    }
    private static Map<String, Object> response() {
        return object("appearance", object("schemaVersion", 1, "serverTime", "2026-10-28T00:00:02Z",
                        "vehicles", new ArrayList<>(List.of(report()))),
                "sync", object("epoch", "epoch-a", "deviceId", "vehicle-a", "role", "vehicle",
                        "expiresAt", "2026-10-29T00:00:00Z"));
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> appearance(Map<String, Object> body) {
        return (Map<String, Object>) body.get("appearance");
    }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> vehicles(Map<String, Object> body) {
        return (List<Map<String, Object>>) appearance(body).get("vehicles");
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> sync(Map<String, Object> body) {
        return (Map<String, Object>) body.get("sync");
    }
    private static boolean valid(Map<String, Object> body, String requestId) {
        try { AppearanceResponsePolicy.validate(200, body, requestId); return true; }
        catch (Exception invalid) { return false; }
    }
    private static boolean throwsIllegal(Runnable action) {
        try { action.run(); return false; } catch (IllegalArgumentException expected) { return true; }
    }
    /** The report carries the car's CURRENT theme; retries keep their payload; changes re-report. */
    private static void themes() {
        check(AppearancePublisher.PAPER.equals(AppearancePublisher.wireTheme("pearl")), "Pearl keeps wire id paper");
        check(AppearancePublisher.GRAPHITE.equals(AppearancePublisher.wireTheme("graphite")), "graphite wire id");
        check(AppearancePublisher.UMBER.equals(AppearancePublisher.wireTheme("umber")), "umber wire id");
        check(throwsIllegal(() -> AppearancePublisher.wireTheme("paper")), "wire id is not a car renderer id");
        check(throwsIllegal(() -> AppearancePublisher.wireTheme("neon")), "unknown car theme refused");
        check(throwsIllegal(() -> AppearancePublisher.wireTheme(null)), "missing car theme refused");
        check("Pearl".equals(AppearancePublisher.label("paper")) && "Umber".equals(AppearancePublisher.label("umber"))
                && "Graphite".equals(AppearancePublisher.label("graphite")), "status labels name the real theme");

        AppearancePublisher p = new AppearancePublisher();
        check(throwsIllegal(() -> p.setTheme("neon")) && throwsIllegal(() -> p.setTheme("pearl"))
                && throwsIllegal(() -> p.setTheme(null)), "only wire ids can become the reported theme");
        check(AppearancePublisher.GRAPHITE.equals(p.theme()), "rejected ids leave the theme unchanged");
        check(!p.setTheme("graphite"), "same theme is not a change");
        p.start(); p.observeEpoch("epoch-t");
        check(p.setTheme("umber"), "Umber is a change");
        AppearancePublisher.Attempt a = p.begin(0);
        AppearancePublisher.Report umber = p.prepare(a, "epoch-t", 0, REQUEST_A);
        check("umber".equals(umber.themeId), "theme in report");
        check(p.setTheme("paper"), "theme changes while the POST is in flight");
        check(p.begin(1) == null, "in-flight report is not interrupted by a theme change");
        p.failed(a, false);
        check(p.begin(29_999) == null, "unanswered POST waits for its retry interval");
        AppearancePublisher.Attempt retry = p.begin(30_000);
        AppearancePublisher.Report again = p.pending(retry);
        check(again == umber && "umber".equals(again.themeId) && REQUEST_A.equals(again.requestId),
                "retry keeps the identical payload and request id, not the newer theme");
        check(p.prepare(retry, "epoch-t", 5, REQUEST_B) == umber, "retry cannot be rebased onto the new theme");
        check(AppearancePublisher.confirms(umber, "umber", 1, REQUEST_A), "matching echo confirms the report");
        check(!AppearancePublisher.confirms(umber, "paper", 1, REQUEST_A), "echo with another theme is refused");
        check(!AppearancePublisher.confirms(umber, "neon", 1, REQUEST_A), "echo with unknown theme is refused");
        check(!AppearancePublisher.confirms(umber, "umber", 0, REQUEST_A), "echo without a newer revision is refused");
        check(!AppearancePublisher.confirms(umber, "umber", 1, REQUEST_B), "echo with another receipt is refused");
        check(!AppearancePublisher.confirms(null, "umber", 1, REQUEST_A), "no report, no confirmation");
        check(p.acknowledge(retry, REQUEST_A), "retry acknowledged");
        AppearancePublisher.Attempt changed = p.begin(30_001);
        check(changed != null && p.pending(changed) == null, "theme change after ack re-reports at once with a fresh preparation");
        AppearancePublisher.Report paper = p.prepare(changed, "epoch-t", 1, REQUEST_B);
        check("paper".equals(paper.themeId) && paper.expectedRevision == 1, "re-report carries the new theme");
        check(p.acknowledge(changed, REQUEST_B), "new theme acknowledged");
        check(p.begin(30_002) == null, "unchanged theme after ack does not loop");
        check(p.setTheme("graphite"), "idle theme change");
        AppearancePublisher.Attempt prompt = p.begin(30_003);
        check(prompt != null, "idle theme change reports promptly instead of waiting 30 seconds");
        AppearancePublisher.Report graphite = p.prepare(prompt, "epoch-t", 2, REQUEST_A);
        p.failed(prompt, false); p.setTheme("umber");
        AppearancePublisher.Attempt resend = p.begin(30_004);
        check(resend != null && p.pending(resend) == graphite, "theme change re-arms an idle unanswered report with its own payload");
        p.failed(resend, true);
        AppearancePublisher.Attempt afterReject = p.begin(60_004);
        check(afterReject != null && p.pending(afterReject) == null, "definite rejection drops the old payload");
        check("umber".equals(p.prepare(afterReject, "epoch-t", 3, REQUEST_B).themeId), "next report uses the current theme");
        p.stop(); check(p.begin(90_004) == null, "background stops reporting");
        check(p.setTheme("paper") && p.begin(90_005) == null, "background theme change does not report");
        p.start(); AppearancePublisher.Attempt back = p.begin(90_006);
        check(back != null && "umber".equals(p.pending(back).themeId), "foreground resumes the unanswered payload first");
        p.reset();
        check(p.epoch() == null && p.begin(90_007) == null && p.pending(back) == null, "reset clears epoch, attempt and pending report");
        check("paper".equals(p.theme()), "reset keeps the rendered theme");
        p.observeEpoch("epoch-u"); AppearancePublisher.Attempt fresh = p.begin(90_008);
        check(fresh != null && p.pending(fresh) == null, "after reset nothing old is resent");
        check("paper".equals(p.prepare(fresh, "epoch-u", 0, REQUEST_A).themeId), "after reset the current theme is reported");
        p.observeEpoch("epoch-v");
        check(!p.current(fresh) && "epoch-v".equals(p.epoch()), "server epoch change resets the bookkeeping");
    }
    public static void main(String[] args) {
        AppearancePublisher p = new AppearancePublisher();
        check(p.begin(0) == null, "no report without foreground or approval observation");
        p.start(); check(p.begin(0) == null, "foreground alone cannot report");
        p.observeEpoch("epoch-a");
        AppearancePublisher.Attempt first = p.begin(0);
        check(first != null && p.current(first), "approved foreground starts an attempt");
        check(p.begin(0) == null, "only one theme exchange at a time");
        check(p.pending(first) == null, "first report requires GET first");
        AppearancePublisher.Report command = p.prepare(first, "epoch-a", 7, REQUEST_A);
        check(command.expectedRevision == 7 && command.epoch.equals("epoch-a"), "uses own observed revision");
        check(AppearancePublisher.GRAPHITE.equals(command.themeId), "default rendered theme is Graphite");
        check(!p.acknowledge(first, REQUEST_B), "wrong receipt cannot confirm a report");
        p.failed(first, false);
        check(!p.current(first), "failed exchange is no longer active");
        check(p.begin(29_999) == null, "retry respects 30 second interval");
        AppearancePublisher.Attempt retry = p.begin(30_000);
        check(p.pending(retry) == command, "transport failure preserves identical report and ID");
        check(p.prepare(retry, "epoch-a", 99, REQUEST_B) == command, "uncertain report cannot be silently rebased");
        check(!p.acknowledge(first, REQUEST_A), "late callback cannot resolve a later attempt");
        p.stop(); check(!p.current(retry), "background invalidates in-flight callback");
        check(p.begin(60_000) == null, "background prevents new network work");
        p.start(); AppearancePublisher.Attempt foreground = p.begin(60_000);
        check(p.pending(foreground) == command, "background interruption retains same uncertain payload");
        check(p.acknowledge(foreground, REQUEST_A), "matching foreground receipt resolves the report");
        check(p.begin(89_999) == null, "successful report does not create a hot loop");
        AppearancePublisher.Attempt next = p.begin(90_000);
        check(p.pending(next) == null, "next interval GETs latest own revision");
        p.prepare(next, "epoch-a", 8, REQUEST_B); p.failed(next, true);
        AppearancePublisher.Attempt afterConflict = p.begin(120_000);
        check(p.pending(afterConflict) == null, "definite rejection discards stale pending report");
        boolean rejectedEpoch = false;
        try { p.prepare(afterConflict, "epoch-b", 9, REQUEST_A); }
        catch (IllegalArgumentException expected) { rejectedEpoch = true; }
        check(rejectedEpoch, "different epoch cannot authorize report");
        p.observeEpoch("epoch-b");
        check(!p.current(afterConflict), "server epoch invalidates previous attempt");
        AppearancePublisher.Attempt newEpoch = p.begin(120_001);
        check(newEpoch != null && p.pending(newEpoch) == null, "restart requires fresh report preparation");
        p.prepare(newEpoch, "epoch-b", 0, REQUEST_A); p.reset();
        check(!p.acknowledge(newEpoch, REQUEST_A) && p.begin(150_000) == null,
                "credential reset drops pending report and approval observation");
        p.observeEpoch("epoch-c"); AppearancePublisher.Attempt newCredential = p.begin(150_001);
        check(p.pending(newCredential) == null, "new credential cannot reuse previous payload");
        themes();

        Map<String, Object> body = response(); check(valid(body, null), "valid GET envelope");
        vehicles(body).clear(); check(valid(body, null), "no own report yet is valid");
        body = response(); body.put("acknowledgedRequestId", REQUEST_A);
        check(valid(body, REQUEST_A), "matching POST receipt");
        check(!valid(body, REQUEST_B), "wrong POST receipt rejected");
        check(!valid(body, null), "GET cannot smuggle receipt field");
        body = response(); check(!valid(body, REQUEST_A), "POST must acknowledge request");
        body.put("state", object("demo", true)); check(!valid(body, null), "appearance cannot smuggle preset state");
        body = response(); body.put("health", object()); check(!valid(body, null), "health data outside appearance rejected");
        body = response(); appearance(body).put("schemaVersion", 2); check(!valid(body, null), "unknown schema rejected");
        body = response(); appearance(body).put("schemaVersion", "1"); check(!valid(body, null), "schema numeric coercion rejected");
        body = response(); appearance(body).put("serverTime", "unknown"); check(!valid(body, null), "server time must parse");
        body = response(); sync(body).put("role", "phone"); check(!valid(body, null), "vehicle cannot consume other role envelope");
        body = response(); sync(body).put("epoch", ""); check(!valid(body, null), "epoch cannot be empty");
        for (String theme : new String[]{"graphite", "paper", "umber"}) {
            body = response(); vehicles(body).get(0).put("themeId", theme); check(valid(body, null), "implemented theme accepted: " + theme);
        }
        for (Object theme : new Object[]{"pearl", "neon", "Graphite", "UMBER", " paper", "", null, 1}) {
            body = response(); vehicles(body).get(0).put("themeId", theme);
            check(!valid(body, null), "unknown or car-internal theme id rejected: " + theme);
        }
        body = response(); vehicles(body).get(0).remove("themeId"); check(!valid(body, null), "theme id is required");
        body = response(); vehicles(body).get(0).put("paletteVersion", 2); check(!valid(body, null), "unknown palette rejected");
        for (Object invalidRevision : new Object[]{0, -1, 1.5, Double.NaN, "1", 9007199254740992.0}) {
            body = response(); vehicles(body).get(0).put("revision", invalidRevision);
            check(!valid(body, null), "invalid report revision rejected: " + invalidRevision);
        }
        body = response(); vehicles(body).add(report()); check(!valid(body, null), "duplicate vehicle reports rejected");
        body = response(); vehicles(body).get(0).put("temperatureC", 22); check(!valid(body, null), "appearance cannot carry HVAC setting");
        body = response(); vehicles(body).get(0).put("expiresAt", "2026-10-28T00:02:01Z");
        check(!valid(body, null), "freshness cannot exceed 120 seconds");
        body = response(); vehicles(body).get(0).put("expiresAt", "2026-10-27T23:59:59Z");
        check(!valid(body, null), "expiry cannot precede reporting");
        body = response(); appearance(body).put("serverTime", "2026-10-28T00:03:00Z");
        check(valid(body, null), "expired report remains parseable for freshness decisions");
        body = response(); vehicles(body).get(0).put("expiresAt", "2026-10-28T00:00:30Z");
        check(valid(body, null), "session expiry may shorten report lifetime");
        check(!valid(PresetSyncFixtures.response(), null), "preset envelope cannot enter appearance path");
        AppearanceResponsePolicy.validate(409, object("error", "REVISION_CONFLICT"), null);
        check(true, "rejection is not promoted to a successful observation");
        Map<String, Object> conflict = PresetSyncFixtures.response();
        conflict.remove("acknowledgedRequestId"); conflict.put("error", object("code", "APPEARANCE_CONFLICT"));
        AppearanceResponsePolicy.validate(409, conflict, REQUEST_A);
        check(true, "shared server 409 state wrapper remains a definite rejection, not an uncertain POST");
        System.out.println("PASS: " + assertions + " appearance lifecycle and envelope assertions");
    }
}
