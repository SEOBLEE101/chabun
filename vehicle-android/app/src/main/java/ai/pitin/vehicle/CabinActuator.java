package ai.pitin.vehicle;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Runs {@link CabinControl} steps on a car (Pleos) or a stand-in (demo), one at a time, and keeps an honest ledger
 * of what each rest changed (spec 6-2-1, audit 2026-10-29). Pure Java: Android supplies the scheduler, the store
 * and the log ({@link AndroidCabinRuntime}). Every callback runs on the scheduler's thread.
 *
 * <ul>
 * <li>The parked gate is checked right before every write (and every restore write), not when a plan is queued.</li>
 * <li>A rest changes an item only when it could read the item's current value, so it can put it back, and never
 *     takes ownership of an item that already had the asked value.</li>
 * <li>A late answer or an early read never counts as "unchanged": a sent write stays in the ledger until a read
 *     shows the original value again.</li>
 * <li>Ending a rest cancels its unsent steps and then restores newest first. An item changed by hand in the
 *     meantime is kept. Records are removed only after a read confirms them, and they outlive the process.</li>
 * </ul>
 */
abstract class CabinActuator implements AutoCloseable {
    interface Scheduler {
        long now();
        void post(Runnable task, long delayMs);
        void cancel(Runnable task);
    }
    /** The ledger of rests that still owe a restore, as one line of text. */
    interface Store {
        String load();
        void save(String value);
    }
    /** Null when the car may be changed right now, otherwise the reason in on-screen words. */
    interface Gate { String blocked(); }
    /** One SDK answer: a raw car value, {@link #UNSUPPORTED}, {@link #REFUSED}, or null when the car said nothing. */
    interface Answer { void value(Integer raw); }
    interface Listener {
        default void onProgress(int finished, int total) { }
        void onDone(Result result);
    }

    static final int UNSUPPORTED = Integer.MIN_VALUE, REFUSED = Integer.MIN_VALUE + 1;
    static final long CALL_TIMEOUT_MS = 3_000, SETTLE_MS = 250, RECHECK_MS = 1_000;
    static final int MAX_SESSIONS = 4;
    /** One line per step (item, car value, answer) so a run can be checked with logcat. No personal data. */
    static final String TAG = "PitInCabin";
    static Consumer<String> log = line -> { };

    /** What happened to one asked step. {@code target} and {@code observed} are raw car values. */
    static final class Outcome {
        final CabinControl.Step step;
        final Integer target, observed;
        final CabinControl.Status status;
        final String reason;

        Outcome(CabinControl.Step step, Integer target, Integer observed, CabinControl.Status status, String reason) {
            this.step = step; this.target = target; this.observed = observed; this.status = status; this.reason = reason;
        }

        /** The step as the car now has it: temperatures and seat levels may have been fitted into the car's range. */
        CabinControl.Step shown() {
            boolean raw = step.item == CabinControl.Item.TEMPERATURE || step.item == CabinControl.Item.SEAT_HEAT
                    || step.item == CabinControl.Item.SEAT_VENT;
            return raw && target != null ? new CabinControl.Step(step.item, step.driver, target) : step;
        }

        @Override public String toString() { return step.key() + ":" + status + (target == null ? "" : "=" + target); }
    }

    static final class Result {
        final List<Outcome> items;
        final boolean restore;

        Result(List<Outcome> items, boolean restore) { this.items = items; this.restore = restore; }

        int count(CabinControl.Status... statuses) {
            int n = 0;
            for (Outcome o : items) for (CabinControl.Status s : statuses) if (o.status == s) n++;
            return n;
        }

        /** Steps the car now really has, as it has them. */
        List<CabinControl.Step> applied() {
            List<CabinControl.Step> out = new ArrayList<>();
            for (Outcome o : items)
                if (o.status == CabinControl.Status.CONFIRMED || o.status == CabinControl.Status.UNCHANGED) out.add(o.shown());
            return out;
        }

        String summary() {
            return restore ? CabinControl.restoreOutcome(count(CabinControl.Status.CONFIRMED, CabinControl.Status.UNCHANGED),
                    count(CabinControl.Status.KEPT), count(CabinControl.Status.UNKNOWN, CabinControl.Status.SKIPPED,
                            CabinControl.Status.FAILED))
                    : CabinControl.outcome(count(CabinControl.Status.CONFIRMED, CabinControl.Status.UNCHANGED),
                    count(CabinControl.Status.FAILED), count(CabinControl.Status.UNKNOWN), count(CabinControl.Status.UNSUPPORTED),
                    count(CabinControl.Status.UNREADABLE), count(CabinControl.Status.SKIPPED));
        }

        /** The short line for screens; {@link #summary} keeps the full breakdown. */
        String brief() {
            int owed = count(CabinControl.Status.UNKNOWN, CabinControl.Status.SKIPPED, CabinControl.Status.FAILED);
            return restore ? CabinControl.briefRestore(count(CabinControl.Status.KEPT), owed)
                    : CabinControl.briefOutcome(count(CabinControl.Status.CONFIRMED, CabinControl.Status.UNCHANGED),
                    items.size(), count(CabinControl.Status.UNKNOWN));
        }

        @Override public String toString() { return items.toString(); }
    }

    /** One rest's ledger: for each item it changed, the value before the rest and the value it last sent. */
    static final class Session {
        final long id;
        final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
        boolean ended;
        long endedAt;

        Session(long id) { this.id = id; }

        boolean owesRestore() { return ended && !entries.isEmpty(); }
    }

    static final class Entry {
        final CabinControl.Item item;
        final boolean driver;
        final int baseline;
        int lastApplied;

        Entry(CabinControl.Item item, boolean driver, int baseline, int lastApplied) {
            this.item = item; this.driver = driver; this.baseline = baseline; this.lastApplied = lastApplied;
        }

        CabinControl.Step step() { return new CabinControl.Step(item, driver, baseline); }
    }

    private final class Job {
        final Session session;
        final boolean restore;
        final Listener listener;
        List<CabinControl.Step> steps;
        List<Entry> entries;
        Outcome[] outcomes;
        int index;
        boolean sent, cancelled, reported;
        String reason;
        Runnable timer;

        Job(List<CabinControl.Step> steps, Session session, boolean restore, Listener listener) {
            this.steps = steps; this.session = session; this.restore = restore; this.listener = listener;
            outcomes = new Outcome[steps.size()];
        }

        void cancel(String why) { if (!cancelled) { cancelled = true; reason = why; } }
    }

    private final Scheduler clock;
    private final Store store;
    private Gate gate = () -> null;
    private final ArrayDeque<Job> queue = new ArrayDeque<>();
    private final List<Session> sessions = new ArrayList<>();
    private Job running;
    private boolean closed;
    private long nextSession = 1;

    CabinActuator(Scheduler clock, Store store) {
        this.clock = clock; this.store = store;
        load();
    }

    /** True when this changes a real car. */
    abstract boolean live();
    /** The car value for this step (its own range), {@link #UNSUPPORTED}, or null when the car could not say. */
    abstract void resolve(CabinControl.Step step, Answer out);
    /** The car's current raw value for this step's item and zone, or null. */
    abstract void read(CabinControl.Step step, Answer out);
    /** Sends the raw value; answers the value the car accepted, {@link #REFUSED} on a refusal, or null. */
    abstract void write(CabinControl.Step step, int raw, Answer out);
    /** Raw values compare exactly, except temperatures in tenths. */
    boolean same(CabinControl.Step step, int asked, Integer reported) { return CabinControl.matches(step.item, asked, reported); }

    void setGate(Gate gate) { this.gate = gate == null ? () -> null : gate; }

    /** Scheduler time, for deadlines. */
    long now() { return clock.now(); }

    Session beginSession() { return new Session(nextSession++); }

    /**
     * Runs steps in order after anything already queued. With a session, this rest owns what it changes and
     * {@link #endSession} puts it back; without one (a direct request) nothing is recorded. A deadline (scheduler
     * time, 0 = none) answers by then: unsent steps are dropped, a write already sent is reported as unknown.
     */
    void apply(List<CabinControl.Step> steps, Session session, long deadline, Listener listener) {
        Job job = new Job(new ArrayList<>(steps), session, false, listener);
        if (deadline > 0) {
            job.timer = () -> expire(job);
            clock.post(job.timer, Math.max(0, deadline - clock.now()));
        }
        enqueue(job);
    }

    /** Ends a rest: its unsent steps are dropped, then what it changed is put back (while the gate allows). */
    void endSession(Session session, Listener listener) {
        if (session == null) return;
        if (!session.ended) { session.ended = true; session.endedAt = clock.now(); }
        cancelAppliesOf(session, "쉬기를 마쳐서 보내지 않았어요");
        enqueue(new Job(new ArrayList<>(), session, true, listener));
    }

    /** Ended rests that still owe a restore (it was blocked or could not be confirmed). */
    boolean owesRestore() {
        for (Session s : sessions) if (s.owesRestore()) return true;
        return false;
    }

    int owedItems() {
        int n = 0;
        for (Session s : sessions) if (s.ended) n += s.entries.size();
        return n;
    }

    /** Tries the owed restores again, newest rest first. Skips a rest whose restore is already waiting. */
    void retryRestores(Listener listener) {
        for (int i = sessions.size() - 1; i >= 0; i--) {
            Session s = sessions.get(i);
            if (!s.owesRestore() || restoreQueued(s)) continue;
            enqueue(new Job(new ArrayList<>(), s, true, listener));
        }
    }

    /** True while steps are queued or running. */
    boolean busy() { return running != null || !queue.isEmpty(); }

    /** One read for ‘지금 차’, outside the queue; null when the car does not say. Never writes. */
    void peek(CabinControl.Step step, Answer out) { call(o -> read(step, o), out); }

    @Override public void close() {
        closed = true;
        for (Job j : queue) if (j.timer != null) clock.cancel(j.timer);
        if (running != null && running.timer != null) clock.cancel(running.timer);
        queue.clear();
        running = null;
    }

    // ── queue ─────────────────────────────────────────────────────────

    private void enqueue(Job job) {
        if (closed) return;
        queue.add(job);
        if (running == null) next();
    }

    private void next() {
        if (closed) return;
        running = queue.poll();
        if (running == null) return;
        Job job = running;
        if (job.restore) {
            job.entries = new ArrayList<>(job.session.entries.values());
            java.util.Collections.reverse(job.entries);
            job.steps = new ArrayList<>();
            for (Entry e : job.entries) job.steps.add(e.step());
            job.outcomes = new Outcome[job.steps.size()];
        }
        step(job);
    }

    private boolean restoreQueued(Session s) {
        if (running != null && running.restore && running.session == s) return true;
        for (Job j : queue) if (j.restore && j.session == s) return true;
        return false;
    }

    private void cancelAppliesOf(Session session, String why) {
        for (Iterator<Job> it = queue.iterator(); it.hasNext(); ) {
            Job j = it.next();
            if (j.restore || j.session != session) continue;
            it.remove();
            j.cancel(why);
            report(j);
        }
        if (running != null && !running.restore && running.session == session) running.cancel(why);
    }

    private void expire(Job job) {
        if (job.reported) return;
        job.cancel("시간 안에 끝내지 못했어요");
        if (queue.remove(job)) { report(job); return; }
        report(job); // a write already sent keeps being tracked; nothing more is sent for this job
    }

    private void step(Job job) {
        if (closed) return;
        if (job.cancelled || job.index >= job.steps.size()) { finish(job); return; }
        if (job.listener != null && !job.reported) job.listener.onProgress(job.index, job.steps.size());
        if (job.restore) restoreStep(job); else applyStep(job);
    }

    private void done(Job job, Integer target, Integer observed, CabinControl.Status status, String reason) {
        CabinControl.Step s = job.steps.get(job.index);
        log.accept(s.key() + (job.restore ? " restore " : " ") + status + (target == null ? "" : " target " + target)
                + (observed == null ? "" : " read " + observed) + (reason == null ? "" : " · " + reason));
        if (!job.reported) job.outcomes[job.index] = new Outcome(s, target, observed, status, reason);
        job.index++;
        job.sent = false;
        step(job);
    }

    private void report(Job job) {
        if (job.reported) return;
        job.reported = true;
        if (job.timer != null) clock.cancel(job.timer);
        List<Outcome> items = new ArrayList<>();
        for (int i = 0; i < job.steps.size(); i++) {
            Outcome o = job.outcomes[i];
            if (o == null) o = i == job.index && job.sent
                    ? new Outcome(job.steps.get(i), null, null, CabinControl.Status.UNKNOWN, "바뀌었는지 확인하지 못했어요")
                    : new Outcome(job.steps.get(i), null, null, CabinControl.Status.SKIPPED, job.reason);
            items.add(o);
        }
        if (job.listener != null) job.listener.onDone(new Result(items, job.restore));
    }

    private void finish(Job job) {
        report(job);
        if (job.restore && job.session.entries.isEmpty()) sessions.remove(job.session);
        persist();
        if (running == job) { running = null; next(); }
    }

    // ── apply ─────────────────────────────────────────────────────────

    private void applyStep(Job job) {
        CabinControl.Step s = job.steps.get(job.index);
        call(o -> resolve(s, o), target -> {
            if (stopped(job)) return;
            if (target == null || target == REFUSED) { done(job, null, null, CabinControl.Status.FAILED, "차가 지원 범위를 알려 주지 않았어요"); return; }
            if (target == UNSUPPORTED) { done(job, null, null, CabinControl.Status.UNSUPPORTED, "이 차에서 안 돼요"); return; }
            call(o -> read(s, o), current -> {
                if (stopped(job)) return;
                if (current != null && same(s, target, current)) { done(job, target, current, CabinControl.Status.UNCHANGED, null); return; }
                if (job.session != null && current == null) {
                    done(job, target, null, CabinControl.Status.UNREADABLE, "지금 값을 읽지 못해 바꾸지 않았어요");
                    return;
                }
                String blocked = gate.blocked();
                if (blocked != null) { job.cancel(blocked); done(job, target, current, CabinControl.Status.SKIPPED, blocked); return; }
                Entry entry = null;
                boolean created = false;
                if (job.session != null) {
                    entry = job.session.entries.get(s.key());
                    if (entry == null) {
                        entry = new Entry(s.item, s.driver, current, target);
                        job.session.entries.put(s.key(), entry);
                        if (!sessions.contains(job.session)) sessions.add(job.session);
                        trimSessions();
                        created = true;
                    } else entry.lastApplied = target;
                    persist();
                }
                job.sent = true;
                final Entry owned = entry;
                final boolean fresh = created;
                call(o -> write(s, target, o), ack -> {
                    log.accept(s.key() + " write " + target + " -> " + (ack == null ? "no answer" : ack == REFUSED ? "refused" : "accepted"));
                    verifyApply(job, s, target, current, ack, owned, fresh, false);
                });
            });
        });
    }

    private void verifyApply(Job job, CabinControl.Step s, int target, Integer before, Integer ack,
                             Entry owned, boolean fresh, boolean second) {
        clock.post(() -> call(o -> read(s, o), now -> {
            if (closed) return;
            if (now != null && same(s, target, now)) { done(job, target, now, CabinControl.Status.CONFIRMED, null); return; }
            if (ack != null && ack == REFUSED && before != null && now != null && same(s, before, now)) {
                // The car refused and still has the old value: nothing to put back for this step.
                if (fresh && owned != null) { job.session.entries.remove(s.key()); persist(); }
                done(job, target, now, CabinControl.Status.FAILED, "차가 받지 않았어요");
                return;
            }
            if (!second) { verifyApply(job, s, target, before, ack, owned, fresh, true); return; }
            done(job, target, now, CabinControl.Status.UNKNOWN, "바뀌었는지 확인하지 못했어요");
        }), second ? RECHECK_MS - SETTLE_MS : SETTLE_MS);
    }

    // ── restore ───────────────────────────────────────────────────────

    private void restoreStep(Job job) {
        Entry e = job.entries.get(job.index);
        CabinControl.Step s = e.step();
        String blocked = gate.blocked();
        if (blocked != null) { job.cancel(blocked); done(job, e.baseline, null, CabinControl.Status.SKIPPED, blocked); return; }
        call(o -> read(s, o), current -> {
            if (closed) return;
            if (current == null) { done(job, e.baseline, null, CabinControl.Status.UNKNOWN, "지금 값을 읽지 못했어요"); return; }
            if (same(s, e.baseline, current)) { forget(job, s); done(job, e.baseline, current, CabinControl.Status.UNCHANGED, null); return; }
            if (!same(s, e.lastApplied, current)) {
                forget(job, s);
                done(job, e.baseline, current, CabinControl.Status.KEPT, "손으로 바꾼 값은 그대로 뒀어요");
                return;
            }
            String again = gate.blocked();
            if (again != null) { job.cancel(again); done(job, e.baseline, current, CabinControl.Status.SKIPPED, again); return; }
            job.sent = true;
            call(o -> write(s, e.baseline, o), ack -> {
                log.accept(s.key() + " restore write " + e.baseline + " -> " + (ack == null ? "no answer" : ack == REFUSED ? "refused" : "accepted"));
                verifyRestore(job, s, e, false);
            });
        });
    }

    private void verifyRestore(Job job, CabinControl.Step s, Entry e, boolean second) {
        clock.post(() -> call(o -> read(s, o), now -> {
            if (closed) return;
            if (now != null && same(s, e.baseline, now)) { forget(job, s); done(job, e.baseline, now, CabinControl.Status.CONFIRMED, null); return; }
            if (!second) { verifyRestore(job, s, e, true); return; }
            done(job, e.baseline, now, CabinControl.Status.UNKNOWN, "쉬기 전으로 돌아갔는지 확인하지 못했어요");
        }), second ? RECHECK_MS - SETTLE_MS : SETTLE_MS);
    }

    private void forget(Job job, CabinControl.Step s) {
        job.session.entries.remove(s.key());
        persist();
    }

    // ── calls and storage ─────────────────────────────────────────────

    private boolean stopped(Job job) {
        if (closed) return true;
        if (!job.cancelled) return false;
        finish(job);
        return true;
    }

    private interface Call { void run(Answer out); }

    /** Calls the car once; the answer comes back on the scheduler, or null after the timeout or an exception. */
    private void call(Call call, Answer then) {
        final boolean[] answered = {false};
        Runnable timeout = () -> { if (!answered[0] && !closed) { answered[0] = true; then.value(null); } };
        clock.post(timeout, CALL_TIMEOUT_MS);
        Answer once = v -> clock.post(() -> {
            if (answered[0] || closed) return;
            answered[0] = true;
            clock.cancel(timeout);
            then.value(v);
        }, 0);
        try { call.run(once); }
        catch (RuntimeException refused) { once.value(null); }
    }

    private void trimSessions() {
        while (sessions.size() > MAX_SESSIONS) sessions.remove(0);
    }

    /** "id,ended,item,driver,baseline,lastApplied" per entry, ';' between entries. */
    private void persist() {
        if (store == null) return;
        StringBuilder b = new StringBuilder("v1");
        for (Session s : sessions)
            for (Entry e : s.entries.values())
                b.append(';').append(s.id).append(',').append(s.ended ? 1 : 0).append(',').append(e.item.name()).append(',')
                        .append(e.driver ? 1 : 0).append(',').append(e.baseline).append(',').append(e.lastApplied);
        store.save(b.toString());
    }

    /** Rests from an earlier run come back as ended rests that owe a restore. Anything malformed is dropped. */
    private void load() {
        if (store == null) return;
        String value = store.load();
        if (value == null || !value.startsWith("v1")) return;
        Map<Long, Session> byId = new LinkedHashMap<>();
        try {
            for (String part : value.substring(2).split(";")) {
                if (part.isEmpty()) continue;
                String[] f = part.split(",");
                if (f.length != 6) return;
                long id = Long.parseLong(f[0]);
                Session s = byId.computeIfAbsent(id, Session::new);
                s.ended = true;
                s.endedAt = clock.now();
                CabinControl.Item item = CabinControl.Item.valueOf(f[2]);
                boolean driver = "1".equals(f[3]);
                Entry e = new Entry(item, driver, Integer.parseInt(f[4]), Integer.parseInt(f[5]));
                s.entries.put(new CabinControl.Step(item, driver, 0).key(), e);
                nextSession = Math.max(nextSession, id + 1);
            }
        } catch (RuntimeException malformed) { return; }
        sessions.addAll(byId.values());
        trimSessions();
    }

    /** The demo stand-in: a pretend car that accepts every value after a short pause. Nothing real changes. */
    static final class Simulated extends CabinActuator {
        private final Map<String, Integer> car = new LinkedHashMap<>();
        private final Scheduler clock;

        Simulated(Scheduler clock, Store store) { super(clock, store); this.clock = clock; }

        boolean live() { return false; }

        void resolve(CabinControl.Step s, Answer out) {
            switch (s.item) {
                case FAN: out.value(CabinControl.fanSpeed(s.value, 1, 7)); break;
                case SEAT_HEAT: case SEAT_VENT: out.value(CabinControl.seatLevel(s.value, 0, 3)); break;
                case LIGHT_BRIGHTNESS: out.value(CabinControl.brightness(s.value, 0, 100)); break;
                default: out.value(s.value);
            }
        }

        void read(CabinControl.Step s, Answer out) {
            Integer v = car.get(s.key());
            clock.post(() -> out.value(v != null ? v : initial(s.item)), 120);
        }

        void write(CabinControl.Step s, int raw, Answer out) {
            clock.post(() -> { car.put(s.key(), raw); out.value(raw); }, 180);
        }

        private static int initial(CabinControl.Item item) {
            switch (item) {
                case TEMPERATURE: return 240;
                case FAN: return 3;
                case DIRECTION: return CabinControl.DIR_BOTH;
                case LIGHT_BRIGHTNESS: return 60;
                case LIGHT_COLOR: return CabinControl.color("cream");
                case LIGHT_SWITCH: return 1;
                default: return 0;
            }
        }
    }
}
