package ai.pitin.vehicle;

import java.util.Calendar;
import java.util.Locale;

/** Words on the car screens (spec 4 glossary): 쉼 · 쉬는 방식 · 분위기 · 소리 끔. */
final class CarCopy {
    private CarCopy() { }

    static final String[] REST_IDS = {"cool_down", "quiet_rest", "gentle_refresh"};

    static String restName(String id) {
        switch (id == null ? "" : id) {
            case "cool_down": return "산뜻하게 식히기";
            case "quiet_rest": return "조용히 쉬기";
            case "gentle_refresh": return "부드럽게 기분 전환";
            default: return "쉼을 골라 주세요";
        }
    }

    static String restValues(String id) {
        switch (id == null ? "" : id) {
            case "cool_down": return "차 23.5° · 바람 중간";
            case "quiet_rest": return "차 24.0° · 바람 약하게";
            case "gentle_refresh": return "차 25.0° · 시트 직접 편하게";
            default: return "바꾸기에서 오늘의 쉼을 골라 주세요";
        }
    }

    static String restTemp(String id) {
        switch (id == null ? "" : id) {
            case "cool_down": return "23.5°";
            case "gentle_refresh": return "25.0°";
            default: return "24.0°";
        }
    }

    static String restFan(String id) {
        switch (id == null ? "" : id) {
            case "cool_down": return "중간";
            case "gentle_refresh": return "그대로";
            default: return "약하게";
        }
    }

    static String restIcon(String id) {
        switch (id) {
            case "cool_down": return CarIcon.COOL;
            case "gentle_refresh": return CarIcon.SUNRISE;
            default: return CarIcon.MOON;
        }
    }

    static String mode(boolean natural) { return natural ? "자유롭게 쉬기" : "안내에 맞춰 쉬기"; }

    /** Minute precision only (spec 4: ‘6:41 확인’). */
    static String clock(long epochMs) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(epochMs);
        // ‘0:27’은 남은 시간처럼 읽혀서, 폰·시스템 표시와 같은 ‘오전 12:27’ 꼴로 쓴다
        int h = c.get(Calendar.HOUR_OF_DAY);
        return String.format(Locale.ROOT, "%s %d:%02d", h < 12 ? "오전" : "오후", h % 12 == 0 ? 12 : h % 12, c.get(Calendar.MINUTE));
    }

    static String remaining(long ms) {
        long s = Math.max(0, ms / 1000);
        long m = s / 60, r = s % 60;
        return m > 0 ? (r > 0 ? m + "분 " + r + "초" : m + "분") : r + "초";
    }

    /** Short phone-connection status for the 시작 header (spec 4 연결 상태). */
    static String phoneStatus(MainActivity a) {
        if (a.revocation.pending()) return "폰 · 해제 확인 중";
        if (a.pairingId == null) return "폰 연결";
        if ("paired".equals(a.pairingStatus)) return a.isConnectionCurrent() ? "폰 · 이어짐" : "폰 · 확인 필요";
        return "폰 · 승인 기다림";
    }
}
