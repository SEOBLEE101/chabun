package ai.pitin.vehicle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RestGateMessagesTest {
    private static int assertions;
    private static final Set<String> observed = new HashSet<>();
    private static void check(boolean value) { assertions++; if (!value) throw new AssertionError("message check " + assertions); }
    private static SafetyGate.Snapshot snapshot(boolean simulation, SafetyGate.Gear gear, Double speed,
            Boolean call, Boolean ux, boolean permission, long sampled) {
        return new SafetyGate.Snapshot(simulation, gear, speed, call, ux, permission, sampled);
    }
    private static void denial(SafetyGate.Snapshot state, boolean choice, boolean confirmed, String expected) {
        SafetyGate.Decision gate = SafetyGate.preview(state, 10_000, choice, confirmed);
        check(!gate.previewAllowed); observed.add(gate.reason);
        check(RestGateMessages.message(gate.reason).equals(expected));
        check(!RestGateMessages.message(gate.reason).equals(RestGateMessages.FALLBACK));
    }
    public static void main(String[] args) throws Exception {
        var parked = snapshot(true, SafetyGate.Gear.PARK, 0.0, false, false, true, 10_000);
        denial(parked, false, true, "차에서 선호를 골라 주세요.\n운동 기록 없이도 쉴 수 있어요.");
        denial(parked, true, false, "선호를 고르고 이 화면에서 확인해 주세요.");
        denial(null, true, true, "차량 상태를 확인할 수 없어 멈췄어요.");
        denial(snapshot(true, SafetyGate.Gear.PARK, 0.0, false, false, true, 0), true, true,
                "차량 상태를 확인할 수 없어 멈췄어요.");
        denial(snapshot(true, SafetyGate.Gear.DRIVE, 0.0, false, false, true, 10_000), true, true,
                "안전하게 주차한 상태인지 확인해 주세요.");
        denial(snapshot(true, SafetyGate.Gear.PARK, 1.0, false, false, true, 10_000), true, true,
                "안전하게 주차한 상태인지 확인해 주세요.");
        denial(snapshot(true, SafetyGate.Gear.PARK, 0.0, null, false, true, 10_000), true, true,
                "통화 중이거나 통화 상태를 확인할 수 없어요.");
        denial(snapshot(true, SafetyGate.Gear.PARK, 0.0, false, true, true, 10_000), true, true,
                "차량 화면 사용 조건을 확인할 수 없어요.");
        denial(snapshot(true, SafetyGate.Gear.PARK, 0.0, false, false, false, 10_000), true, true,
                "차량 상태 접근 권한을 확인해 주세요.");
        denial(snapshot(false, SafetyGate.Gear.PARK, 0.0, false, false, true, 10_000), true, true,
                "이 앱은 차량 상태 읽기 전용이에요.\n실제 차량에서는 쉼을 시작하지 않아요.");
        Set<String> declared = new HashSet<>();
        Matcher reasons = Pattern.compile("return deny\\(\"([^\"]+)\"\\)").matcher(Files.readString(Path.of(args[0])));
        while (reasons.find()) declared.add(reasons.group(1));
        check(declared.size() == 10 && declared.equals(observed));
        check(RestGateMessages.message("future unknown reason").equals(RestGateMessages.FALLBACK));
        check(RestGateMessages.message(null).equals(RestGateMessages.FALLBACK));
        System.out.println("PASS " + assertions + " Korean gate presentation assertions covering all " + declared.size() + " denial reasons");
    }
}
