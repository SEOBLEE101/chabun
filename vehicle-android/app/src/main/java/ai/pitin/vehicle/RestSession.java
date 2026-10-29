package ai.pitin.vehicle;

/** One monotonic active clock drives the timer and shape. Pauses never consume session time. */
public final class RestSession {
    public enum State { IDLE, ACTIVE, PAUSED, COMPLETE, ENDED }
    public enum Segment { GUIDED, NATURAL_REST, COMPLETE }
    public static final long GUIDED_MS = 176_000;
    public static final long DURATION_MS = 180_000;
    private State state = State.IDLE;
    private long elapsedMs;
    private long anchorMs;
    private boolean reducedMotion;

    public boolean start(long nowMs, SafetyGate.Decision decision) {
        if (!decision.previewAllowed || state == State.ACTIVE || state == State.PAUSED) return false;
        elapsedMs = 0; anchorMs = nowMs; state = State.ACTIVE;
        return true;
    }

    public boolean pause(long nowMs) {
        if (state != State.ACTIVE) return false;
        sync(nowMs);
        if (state == State.ACTIVE) state = State.PAUSED;
        return true;
    }

    public boolean resume(long nowMs, SafetyGate.Decision decision) {
        if (state != State.PAUSED || !decision.previewAllowed) return false;
        anchorMs = nowMs; state = State.ACTIVE;
        return true;
    }

    public boolean finish(long nowMs) {
        if (state != State.ACTIVE && state != State.PAUSED) return false;
        sync(nowMs);
        if (state != State.COMPLETE) state = State.ENDED;
        return true;
    }

    public State check(long nowMs, SafetyGate.Decision decision) {
        sync(nowMs);
        if (state == State.ACTIVE && !decision.previewAllowed) state = State.PAUSED;
        return state;
    }

    private void sync(long nowMs) {
        if (state != State.ACTIVE) return;
        if (nowMs < anchorMs) { state = State.PAUSED; return; }
        elapsedMs = Math.min(DURATION_MS, elapsedMs + (nowMs - anchorMs));
        anchorMs = nowMs;
        if (elapsedMs == DURATION_MS) state = State.COMPLETE;
    }

    public long elapsedActiveMs(long nowMs) {
        sync(nowMs);
        return elapsedMs;
    }

    public long secondsRemaining(long nowMs) {
        return Math.max(0, (DURATION_MS - elapsedActiveMs(nowMs) + 999) / 1_000);
    }

    public Segment segment(long nowMs) {
        long elapsed = elapsedActiveMs(nowMs);
        return elapsed >= DURATION_MS ? Segment.COMPLETE
                : elapsed >= GUIDED_MS ? Segment.NATURAL_REST : Segment.GUIDED;
    }

    public boolean inhaling(long nowMs) { return elapsedActiveMs(nowMs) % 8_000 < 4_000; }

    public double expansion(long nowMs) {
        if (reducedMotion) return 0.5;
        if (segment(nowMs) != Segment.GUIDED) return 0;
        long phase = elapsedActiveMs(nowMs) % 8_000;
        double progress = (phase < 4_000 ? phase : phase - 4_000) / 4_000.0;
        double smooth = progress * progress * progress * (progress * (progress * 6 - 15) + 10);
        return phase < 4_000 ? smooth : 1 - smooth;
    }

    public void setReducedMotion(boolean value) { reducedMotion = value; }
    public boolean reducedMotion() { return reducedMotion; }
    public State state() { return state; }
}
