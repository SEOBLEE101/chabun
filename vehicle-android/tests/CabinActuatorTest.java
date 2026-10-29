package ai.pitin.vehicle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * 차 안 맞추기 실행·되돌리기 (audit 2026-10-29): the eleven fake-SDK cases from the GPT Pro probe, turned around so
 * they pass only with the fixed behaviour, plus deadline, blocked restore, persistence and one-answer checks.
 * Virtual time, a fake car; not an Android, Pleos or real-vehicle test.
 */
public final class CabinActuatorTest {
    private static int assertions;

    private static void check(boolean condition, String label) {
        assertions++;
        if (!condition) throw new AssertionError(label);
    }

    /** Deterministic scheduler: tasks run in time order, then in posting order. */
    static final class Clock implements CabinActuator.Scheduler {
        private static final class Task { long at, seq; Runnable run; }
        private final PriorityQueue<Task> tasks = new PriorityQueue<>((a, b) -> a.at != b.at ? Long.compare(a.at, b.at) : Long.compare(a.seq, b.seq));
        private long now, seq;

        public long now() { return now; }
        public void post(Runnable run, long delayMs) {
            Task t = new Task(); t.at = now + Math.max(0, delayMs); t.seq = seq++; t.run = run; tasks.add(t);
        }
        public void cancel(Runnable run) { tasks.removeIf(t -> t.run == run); }
        void advanceTo(long time) {
            while (!tasks.isEmpty() && tasks.peek().at <= time) { Task t = tasks.poll(); now = t.at; t.run.run(); }
            now = Math.max(now, time);
        }
        void advance(long ms) { advanceTo(now + ms); }
    }

    static final class MemoryStore implements CabinActuator.Store {
        String value;
        public String load() { return value; }
        public void save(String v) { value = v; }
    }

    static final CabinControl.Step HEAT = new CabinControl.Step(CabinControl.Item.SEAT_HEAT, true, 2);
    static final CabinControl.Step VENT = new CabinControl.Step(CabinControl.Item.SEAT_VENT, true, 2);
    static final CabinControl.Step LIGHT = new CabinControl.Step(CabinControl.Item.LIGHT_SWITCH, true, 0);

    static final class FakeCar extends CabinActuator {
        final Clock clock;
        final Map<String, Integer> values;
        final List<String> writes = new ArrayList<>();
        long resolveDelay, ackDelay, physicalDelay;
        boolean parked = true, firstReadMissing, refuseWrites, readsBroken;
        int reads, unsafeWrites;

        FakeCar(Clock clock, MemoryStore store, Map<String, Integer> values) {
            super(clock, store);
            this.clock = clock; this.values = values;
            setGate(() -> parked ? null : "기어가 P일 때만 맞출 수 있어요");
        }

        boolean live() { return true; }
        void resolve(CabinControl.Step s, Answer out) { clock.post(() -> out.value(s.value), resolveDelay); }
        void read(CabinControl.Step s, Answer out) {
            reads++;
            out.value((firstReadMissing && reads == 1) || readsBroken ? null : values.getOrDefault(s.key(), 0));
        }
        void write(CabinControl.Step s, int raw, Answer out) {
            writes.add(clock.now() + ":" + s.key() + "=" + raw);
            if (!parked) unsafeWrites++;
            if (refuseWrites) { out.value(REFUSED); return; }
            clock.post(() -> values.put(s.key(), raw), physicalDelay);
            clock.post(() -> out.value(raw), ackDelay);
        }
        int get(CabinControl.Step s) { return values.getOrDefault(s.key(), 0); }
    }

    static final class Answers implements CabinActuator.Listener {
        final List<CabinActuator.Result> results = new ArrayList<>();
        final List<Long> at = new ArrayList<>();
        final Clock clock;
        Answers(Clock clock) { this.clock = clock; }
        public void onDone(CabinActuator.Result result) { results.add(result); at.add(clock.now()); }
        CabinActuator.Result last() { return results.get(results.size() - 1); }
        CabinControl.Status status(int i) { return last().items.get(i).status; }
    }

    private static Clock clock;
    private static FakeCar car(int heat) {
        clock = new Clock();
        Map<String, Integer> values = new HashMap<>();
        values.put(HEAT.key(), heat);
        return new FakeCar(clock, new MemoryStore(), values);
    }

    public static void main(String[] args) {
        // 1. The gate is checked right before each write: leaving P stops the next write.
        FakeCar c = car(0); Answers a = new Answers(clock);
        c.apply(List.of(HEAT, new CabinControl.Step(CabinControl.Item.SEAT_VENT, true, 1)), null, 0, a);
        clock.post(() -> c.parked = false, 100);
        clock.advanceTo(6000);
        check(c.writes.size() == 1 && c.unsafeWrites == 0, "no write after P was left: " + c.writes);
        check(a.results.size() == 1 && a.status(0) == CabinControl.Status.CONFIRMED && a.status(1) == CabinControl.Status.SKIPPED,
                "first confirmed, second never sent: " + a.last());

        // 2. A rest does not change what it cannot read back to.
        FakeCar c2 = car(0); Answers a2 = new Answers(clock); c2.firstReadMissing = true;
        c2.apply(List.of(HEAT), c2.beginSession(), 0, a2); clock.advanceTo(6000);
        check(c2.writes.isEmpty() && c2.get(HEAT) == 0 && a2.status(0) == CabinControl.Status.UNREADABLE && !c2.owesRestore(),
                "unreadable baseline: no write");

        // 3. A late answer (3.1 s) is not "unchanged": the read decides, and the record stays for the restore.
        FakeCar c3 = car(0); Answers a3 = new Answers(clock); c3.ackDelay = 3100;
        CabinActuator.Session s3 = c3.beginSession();
        c3.apply(List.of(HEAT), s3, 0, a3); clock.advanceTo(8000);
        check(c3.get(HEAT) == 2 && a3.status(0) == CabinControl.Status.CONFIRMED && a3.results.size() == 1, "late ack confirmed by read");
        c3.endSession(s3, a3); clock.advanceTo(16000);
        check(c3.get(HEAT) == 0 && !c3.owesRestore(), "late-ack change restored");

        // 4. A slow car (500 ms) is caught by the second read.
        FakeCar c4 = car(0); Answers a4 = new Answers(clock); c4.physicalDelay = 500;
        c4.apply(List.of(HEAT), c4.beginSession(), 0, a4); clock.advanceTo(6000);
        check(a4.status(0) == CabinControl.Status.CONFIRMED, "slow change confirmed on recheck");

        // 5. Slower than every read: unknown, but still owned, and the restore checks the car first.
        FakeCar c5 = car(0); Answers a5 = new Answers(clock); c5.physicalDelay = 5000;
        CabinActuator.Session s5 = c5.beginSession();
        c5.apply(List.of(HEAT), s5, 0, a5); clock.advanceTo(9000);
        check(a5.status(0) == CabinControl.Status.UNKNOWN && c5.get(HEAT) == 2, "unknown result, car did change");
        c5.physicalDelay = 0;
        c5.endSession(s5, a5); clock.advanceTo(20000);
        check(c5.get(HEAT) == 0 && a5.last().restore && a5.status(0) == CabinControl.Status.CONFIRMED, "unknown change restored");

        // 6. Already the asked value: nothing sent, nothing owned, a later manual change survives the end.
        FakeCar c6 = car(2); Answers a6 = new Answers(clock);
        CabinActuator.Session s6 = c6.beginSession();
        c6.apply(List.of(HEAT), s6, 0, a6); clock.advanceTo(6000);
        check(c6.writes.isEmpty() && a6.status(0) == CabinControl.Status.UNCHANGED && !c6.owesRestore(), "no-op not owned");
        c6.values.put(HEAT.key(), 3);
        c6.endSession(s6, a6); clock.advanceTo(12000);
        check(c6.get(HEAT) == 3 && c6.writes.isEmpty(), "manual value kept after a no-op rest");

        // 7. Changed by the rest, then by hand: the hand wins.
        FakeCar c7 = car(0); Answers a7 = new Answers(clock);
        CabinActuator.Session s7 = c7.beginSession();
        c7.apply(List.of(HEAT), s7, 0, a7); clock.advanceTo(6000);
        c7.values.put(HEAT.key(), 3);
        c7.endSession(s7, a7); clock.advanceTo(12000);
        check(c7.get(HEAT) == 3 && a7.status(0) == CabinControl.Status.KEPT && !c7.owesRestore(), "manual change kept");

        // 8. Ending before the first write: the ended rest never writes afterwards.
        FakeCar c8 = car(0); Answers a8 = new Answers(clock); c8.resolveDelay = 2000;
        CabinActuator.Session s8 = c8.beginSession();
        c8.apply(List.of(HEAT), s8, 0, a8);
        clock.advanceTo(1000);
        c8.endSession(s8, a8); clock.advanceTo(10000);
        check(c8.writes.isEmpty() && c8.get(HEAT) == 0 && a8.results.size() == 2 && a8.results.get(0).items.get(0).status == CabinControl.Status.SKIPPED,
                "quick end: no late write");

        // 9. A refused restore keeps its record and succeeds when tried again.
        FakeCar c9 = car(0); Answers a9 = new Answers(clock);
        CabinActuator.Session s9 = c9.beginSession();
        c9.apply(List.of(HEAT), s9, 0, a9); clock.advanceTo(6000);
        c9.refuseWrites = true;
        c9.endSession(s9, a9); clock.advanceTo(12000);
        check(c9.get(HEAT) == 2 && c9.owesRestore() && c9.owedItems() == 1, "refused restore still owed");
        c9.refuseWrites = false;
        c9.retryRestores(a9); clock.advanceTo(18000);
        check(c9.get(HEAT) == 0 && !c9.owesRestore(), "retried restore done");

        // 10. Restarting while the old rest's restore waits: both rests are put back, oldest value last.
        FakeCar c10 = car(0); Answers a10 = new Answers(clock); c10.resolveDelay = 1000;
        CabinActuator.Session first = c10.beginSession();
        c10.apply(List.of(HEAT, VENT), first, 0, a10);
        clock.advanceTo(1100);
        c10.endSession(first, a10);
        CabinActuator.Session second = c10.beginSession();
        c10.apply(List.of(HEAT), second, 0, a10);
        clock.advanceTo(15000);
        c10.endSession(second, a10); clock.advanceTo(30000);
        check(c10.get(HEAT) == 0 && c10.get(VENT) == 0 && !c10.owesRestore(), "restart race restores the original: " + c10.writes);

        // 11. Records outlive the process: a new actuator on the same store owes and performs the restore.
        clock = new Clock();
        MemoryStore store = new MemoryStore();
        Map<String, Integer> shared = new HashMap<>();
        FakeCar before = new FakeCar(clock, store, shared);
        before.apply(List.of(HEAT), before.beginSession(), 0, null); clock.advanceTo(6000);
        before.close();
        FakeCar after = new FakeCar(clock, store, shared);
        check(after.owesRestore() && shared.get(HEAT.key()) == 2, "restore owed after restart");
        after.retryRestores(null); clock.advanceTo(12000);
        check(after.get(HEAT) == 0 && !after.owesRestore(), "restored after restart");

        // 12. Positive control: two changes in one rest restore the first value.
        FakeCar c12 = car(0);
        CabinActuator.Session s12 = c12.beginSession();
        c12.apply(List.of(HEAT), s12, 0, null); clock.advanceTo(6000);
        c12.apply(List.of(new CabinControl.Step(CabinControl.Item.SEAT_HEAT, true, 3)), s12, 0, null); clock.advanceTo(12000);
        check(c12.get(HEAT) == 3, "second change applied");
        c12.endSession(s12, null); clock.advanceTo(18000);
        check(c12.get(HEAT) == 0, "first captured value restored");

        // 13. A deadline answers once, on time, and nothing is sent after it.
        FakeCar c13 = car(0); Answers a13 = new Answers(clock); c13.resolveDelay = 2000;
        c13.apply(List.of(HEAT, VENT, new CabinControl.Step(CabinControl.Item.LIGHT_SWITCH, true, 1)), null, 6000, a13);
        clock.advanceTo(20000);
        boolean late = false;
        for (String w : c13.writes) if (Long.parseLong(w.substring(0, w.indexOf(':'))) >= 6000) late = true;
        check(a13.results.size() == 1 && a13.at.get(0) <= 6000 && !late, "one answer by the deadline: " + a13.at + " " + c13.writes);
        check(a13.status(2) == CabinControl.Status.SKIPPED, "unsent step reported as not sent");

        // 14. A restore blocked by driving keeps everything owed, and finishes once parked again.
        FakeCar c14 = car(0); Answers a14 = new Answers(clock);
        CabinActuator.Session s14 = c14.beginSession();
        c14.apply(List.of(HEAT), s14, 0, a14); clock.advanceTo(6000);
        c14.parked = false;
        c14.endSession(s14, a14); clock.advanceTo(12000);
        check(c14.get(HEAT) == 2 && c14.owesRestore() && c14.unsafeWrites == 0 && a14.status(0) == CabinControl.Status.SKIPPED,
                "no restore while driving, still owed");
        c14.parked = true;
        c14.retryRestores(a14); clock.advanceTo(18000);
        check(c14.get(HEAT) == 0 && !c14.owesRestore(), "restored once parked");

        // 15. Reads that fail after the write: unknown, still owned.
        FakeCar c15 = car(0); Answers a15 = new Answers(clock);
        CabinActuator.Session s15 = c15.beginSession();
        c15.apply(List.of(HEAT), s15, 0, a15);
        clock.advanceTo(5); c15.readsBroken = true;
        clock.advanceTo(6000);
        check(a15.status(0) == CabinControl.Status.UNKNOWN && c15.owesRestore() == false && c15.owedItems() == 0
                && s15.entries.size() == 1, "unread result owned by the active rest");

        // 16. Summaries.
        check(a.last().summary().equals("1개 맞췄어요 · 1개는 보내지 않았어요"), "apply words: " + a.last().summary());
        check(a7.last().summary().contains("손으로 바꾼"), "restore words: " + a7.last().summary());
        check(a3.last().summary().equals("차 안을 쉬기 전으로 돌렸어요."), "restore done words: " + a3.last().summary());
        check(a.last().brief().equals("차 안 2개 중 1개를 맞췄어요"), "short apply words: " + a.last().brief());
        check(a7.last().brief().equals("손으로 바꾼 1개 말고는 쉬기 전으로 돌렸어요."), "short restore words: " + a7.last().brief());
        check(CabinControl.briefOutcome(0, 3, 1).equals("차 안이 바뀌었는지 확인하지 못했어요")
                && CabinControl.briefOutcome(0, 2, 0).equals("차 안은 그대로예요")
                && CabinControl.briefRestore(0, 2).startsWith("차 안 2개는 아직 못 돌렸어요"), "short words");
        System.out.println("CabinActuatorTest: " + assertions + " assertions passed");
    }
}
