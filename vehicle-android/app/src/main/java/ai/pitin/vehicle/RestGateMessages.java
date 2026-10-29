package ai.pitin.vehicle;

/** Korean presentation of the unchanged SafetyGate decisions. */
final class RestGateMessages {
    static final String FALLBACK = "차량 상태와 시작 조건을 확인해 주세요.";
    private RestGateMessages() { }
    static String message(String reason) {
        if (reason == null) return FALLBACK;
        switch (reason) {
            case "Choose a comfort preset here; workout data is optional.":
                return "차에서 선호를 골라 주세요.\n운동 기록 없이도 쉴 수 있어요.";
            case "Choose a preset and confirm on this screen.":
                return "선호를 고르고 이 화면에서 확인해 주세요.";
            case "Vehicle state is unknown.":
            case "Vehicle state is stale or invalid.":
                return "차량 상태를 확인할 수 없어 멈췄어요.";
            case "Park state is not verified.":
            case "Zero speed is not verified.":
                return "안전하게 주차한 상태인지 확인해 주세요.";
            case "Call state is active or unknown.":
                return "통화 중이거나 통화 상태를 확인할 수 없어요.";
            case "UX restriction state is active or unknown.":
                return "차량 화면 사용 조건을 확인할 수 없어요.";
            case "Required state access is not verified.":
                return "차량 상태 접근 권한을 확인해 주세요.";
            case "This build is read only; a live rest-session adapter is not implemented.":
                return "이 앱은 차량 상태 읽기 전용이에요.\n실제 차량에서는 쉼을 시작하지 않아요.";
            default:
                return FALLBACK;
        }
    }

}
