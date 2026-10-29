package ai.pitin.vehicle;

/**
 * Foreground-only reporting of this app's CURRENT rendered screen theme, never cabin controls.
 * Pure Java (no Android) so the policy checks can run on the build JVM.
 */
final class AppearancePublisher {
    /** Wire ids. Pearl keeps its stored/server id "paper" (spec decision); the car's own id is "pearl". */
    static final String GRAPHITE = "graphite", PAPER = "paper", UMBER = "umber";
    static final int SCHEMA_VERSION = 1, PALETTE_VERSION = 1;
    static final long REPORT_INTERVAL_MS = 30_000;

    /** Exactly the three renderers the car app implements; anything else is refused. */
    static boolean supportedTheme(Object id) {
        return GRAPHITE.equals(id) || PAPER.equals(id) || UMBER.equals(id);
    }
    /** Car renderer id ({@code CarTheme.id}) to wire id. Unknown ids are refused rather than guessed. */
    static String wireTheme(String carThemeId) {
        if ("graphite".equals(carThemeId)) return GRAPHITE;
        if ("pearl".equals(carThemeId)) return PAPER;
        if ("umber".equals(carThemeId)) return UMBER;
        throw new IllegalArgumentException("Unknown car theme");
    }
    /** Display name of a wire id, as the car and phone label it. */
    static String label(String wireId) {
        if (PAPER.equals(wireId)) return "Pearl";
        if (UMBER.equals(wireId)) return "Umber";
        if (GRAPHITE.equals(wireId)) return "Graphite";
        throw new IllegalArgumentException("Unknown theme");
    }

    static final class Report {
        final String epoch, requestId, themeId;
        final long expectedRevision;
        Report(String epoch, long expectedRevision, String requestId, String themeId) {
            this.epoch = epoch; this.expectedRevision = expectedRevision; this.requestId = requestId;
            this.themeId = themeId;
        }
    }
    static final class Attempt {
        final long generation;
        final String epoch;
        Attempt(long generation, String epoch) { this.generation = generation; this.epoch = epoch; }
    }
    private boolean foreground;
    private long generation, nextAttemptAtMs;
    private String epoch, theme = GRAPHITE;
    private Report pending;
    private Attempt active;

    synchronized void start() { foreground = true; generation++; active = null; nextAttemptAtMs = 0; }
    synchronized void stop() { foreground = false; generation++; active = null; }
    /** Credential, revocation or epoch change: forget the link's report bookkeeping (the theme stays). */
    synchronized void reset() { generation++; active = null; pending = null; epoch = null; nextAttemptAtMs = 0; }
    synchronized void observeEpoch(String value) {
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("Missing appearance epoch");
        if (!value.equals(epoch)) { reset(); epoch = value; }
    }
    synchronized String epoch() { return epoch; }
    synchronized String theme() { return theme; }
    /**
     * The car's rendered theme changed. Returns true when it differs. When nothing is in flight the
     * next {@link #begin} may run at once; an in-flight or unanswered report keeps its exact payload
     * and {@link #acknowledge} re-arms afterwards when the theme moved on meanwhile.
     */
    synchronized boolean setTheme(String wireId) {
        if (!supportedTheme(wireId)) throw new IllegalArgumentException("Unsupported theme");
        if (wireId.equals(theme)) return false;
        theme = wireId;
        if (active == null) nextAttemptAtMs = 0;
        return true;
    }
    synchronized Attempt begin(long nowMs) {
        if (!foreground || epoch == null || active != null || nowMs < nextAttemptAtMs) return null;
        nextAttemptAtMs = nowMs + REPORT_INTERVAL_MS;
        active = new Attempt(generation, epoch); return active;
    }
    synchronized boolean current(Attempt attempt) {
        return foreground && attempt != null && active == attempt && attempt.generation == generation
                && attempt.epoch.equals(epoch);
    }
    synchronized Report pending(Attempt attempt) { return current(attempt) ? pending : null; }
    synchronized Report prepare(Attempt attempt, String responseEpoch, long ownRevision, String requestId) {
        if (!current(attempt) || !attempt.epoch.equals(responseEpoch) || ownRevision < 0)
            throw new IllegalArgumentException("Stale appearance observation");
        if (pending == null) {
            java.util.UUID.fromString(requestId);
            pending = new Report(responseEpoch, ownRevision, requestId, theme);
        }
        return pending;
    }
    synchronized boolean acknowledge(Attempt attempt, String requestId) {
        if (!current(attempt) || pending == null || !pending.requestId.equals(requestId)) return false;
        // The theme changed while this report was unanswered: report the new one promptly.
        if (!pending.themeId.equals(theme)) nextAttemptAtMs = 0;
        pending = null; active = null; return true;
    }
    synchronized void failed(Attempt attempt, boolean definiteRejection) {
        if (!current(attempt)) return;
        // An unanswered POST keeps exactly the same request ID and payload for its next retry.
        if (definiteRejection) pending = null;
        active = null;
    }
    /** The server's echo of our own report must carry what we sent, a newer revision and our receipt. */
    static boolean confirms(Report sent, Object echoedThemeId, long echoedRevision, Object acknowledgedRequestId) {
        return sent != null && supportedTheme(echoedThemeId) && sent.themeId.equals(echoedThemeId)
                && echoedRevision > sent.expectedRevision && sent.requestId.equals(acknowledgedRequestId);
    }
}
