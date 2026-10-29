package ai.pitin.vehicle;

import java.time.Instant;

/** A deletion-only credential, retained in memory until the remote result is known. */
final class PendingRevocation {
    enum Outcome { NONE, PENDING, DELETED, ABSENT, AUTH_ENDED, EXPIRED }

    private String token;
    private long expiresAtMs;
    private long generation;

    boolean pending() { return token != null; }
    String token() { return token; }
    long generation() { return generation; }

    void retain(String credential, long expiresAt) {
        if (pending() || credential == null || credential.isEmpty())
            throw new IllegalStateException("A deletion credential must not be replaced.");
        token = credential;
        expiresAtMs = expiresAt;
        generation++;
    }

    Outcome resolve(long attemptGeneration, int status, boolean deletionConfirmed) {
        if (!pending() || attemptGeneration != generation) return Outcome.NONE;
        Outcome outcome = status >= 200 && status < 300 && deletionConfirmed ? Outcome.DELETED
                : status == 404 || status == 410 ? Outcome.ABSENT
                : status == 401 ? Outcome.AUTH_ENDED : Outcome.PENDING;
        if (outcome != Outcome.PENDING) clear();
        return outcome;
    }

    boolean expire(long nowMs) {
        if (!pending() || expiresAtMs <= 0 || nowMs < expiresAtMs) return false;
        clear();
        return true;
    }

    void clear() {
        token = null;
        expiresAtMs = 0;
        generation++;
    }

    static long deadline(String value) {
        try { return Instant.parse(value).toEpochMilli(); }
        catch (Exception invalid) { return 0; }
    }

    static String message(Outcome outcome) {
        switch (outcome) {
            case DELETED: return "서버에서 연결 삭제를 확인했어요.";
            case ABSENT: return "서버에 이 연결이 더 이상 없음을 확인했어요.";
            case AUTH_ENDED: return "연결 자격이 종료됐어요. 서버의 삭제 완료는 확인하지 못했어요.";
            case EXPIRED: return "연결 유효 시간이 지났어요. 서버의 삭제 완료는 확인하지 못했어요.";
            default: return "이 차량의 데이터는 비웠어요. 서버 해제는 미확인 상태예요. 연결이 돌아오면 다시 확인해 주세요.";
        }
    }
}
