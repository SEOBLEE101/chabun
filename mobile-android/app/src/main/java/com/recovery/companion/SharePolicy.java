package com.recovery.companion;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;

/** Network, freshness, and relay 2xx response policy shared by the UI and plain JVM regression checks. */
public final class SharePolicy {
    public static final long MAX_WORKOUT_AGE_MINUTES = 120;
    private SharePolicy() {}

    public static String validateBase(String raw, boolean debug) {
        URI uri = URI.create(raw.trim().replaceAll("/+$", ""));
        if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || (uri.getPath() != null && !uri.getPath().isEmpty())) {
            throw new IllegalArgumentException("Use a relay origin only, without credentials, path, query or fragment.");
        }
        boolean local = "10.0.2.2".equals(uri.getHost()) || "localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost());
        if (!"https".equals(uri.getScheme()) && !(debug && local && "http".equals(uri.getScheme()))) {
            throw new IllegalArgumentException("Use HTTPS. Debug builds also allow the local emulator relay.");
        }
        return uri.toASCIIString();
    }

    public static void requireFresh(Instant endedAt, Instant now) {
        Duration age = Duration.between(endedAt, now);
        if (age.isNegative() || age.compareTo(Duration.ofMinutes(MAX_WORKOUT_AGE_MINUTES)) >= 0) {
            throw new IllegalStateException("종료 후 2시간(120분) 이내의 운동 기록만 공유할 수 있어요. 직접 쉼을 시작할 수 있어요.");
        }
    }

    public static void requireShare(String relay, boolean debug, String source, boolean consent, Instant endedAt, Instant now) {
        if (!consent) throw new IllegalStateException("Select the separate sharing consent first.");
        validateBase(relay, debug);
        if (!"demo".equals(source) && !"health_connect".equals(source)) {
            throw new IllegalArgumentException("Unsupported workout source.");
        }
        if ("demo".equals(source) && !debug) {
            throw new IllegalStateException("Synthetic demo records are available in debug builds only.");
        }
        if ("health_connect".equals(source) && !"https".equals(URI.create(relay).getScheme())) {
            throw new IllegalStateException("실제 건강 기록은 HTTPS로만 전송할 수 있어요. 로컬 HTTP에서는 합성 데모 기록을 선택해 주세요.");
        }
        requireFresh(endedAt, now);
    }

    public static void validateClaimResponse(String pairingId, String phoneToken, String confirmation, String expiresAt) {
        if (pairingId == null || !pairingId.matches("[a-f0-9]{32}")) {
            throw new IllegalStateException("서버 연결 응답 형식이 올바르지 않아요 (pairingId).");
        }
        if (phoneToken == null || phoneToken.trim().length() < 32) {
            throw new IllegalStateException("서버 연결 인증 토큰을 확인하지 못했어요.");
        }
        if (confirmation == null || !confirmation.matches("\\d{4}")) {
            throw new IllegalStateException("4자리 비교 확인 번호를 받지 못했어요.");
        }
        if (expiresAt == null || expiresAt.isEmpty()) {
            throw new IllegalStateException("연결 만료 시각을 확인하지 못했어요.");
        }
        Instant.parse(expiresAt);
    }

    public static boolean validateStatusResponse(String expectedPairingId, String actualPairingId, String status, String confirmation, String expiresAt) {
        if (expectedPairingId == null || !expectedPairingId.equals(actualPairingId)) {
            throw new IllegalStateException("연결 식별자가 일치하지 않아요. 다시 연결해 주세요.");
        }
        if (!"waiting".equals(status) && !"claimed".equals(status) && !"paired".equals(status)) {
            throw new IllegalStateException("알 수 없는 연결 상태예요. 승인 상태를 다시 확인해 주세요.");
        }
        if ("claimed".equals(status) && (confirmation == null || !confirmation.matches("\\d{4}"))) {
            throw new IllegalStateException("비교 확인 번호가 누락됐어요.");
        }
        if (expiresAt == null || expiresAt.isEmpty()) {
            throw new IllegalStateException("연결 만료 시각 정보가 없어요.");
        }
        Instant.parse(expiresAt);
        return "paired".equals(status);
    }

    public static void validateSummaryResponse(Object accepted, String summaryId, String receivedAt, String expiresAt) {
        if (!Boolean.TRUE.equals(accepted) || summaryId == null || !summaryId.matches("[a-f0-9]{24}")) {
            throw new IllegalStateException("서버에서 요약 접수 확인(accepted)을 받지 못했어요. 전송 완료로 처리하지 않았어요.");
        }
        if (receivedAt == null || expiresAt == null) {
            throw new IllegalStateException("서버 접수 및 만료 시각을 확인하지 못했어요.");
        }
        Duration lifetime = Duration.between(Instant.parse(receivedAt), Instant.parse(expiresAt));
        if (lifetime.isNegative() || lifetime.isZero() || lifetime.compareTo(Duration.ofMinutes(30)) > 0) {
            throw new IllegalStateException("서버 요약의 유효 시간이 올바르지 않아요. 접수 완료로 처리하지 않았어요.");
        }
    }

    /** Retain only DELETE authority for at most the server session lifetime, capped at two hours. */
    public static long revocationDeadline(String serverExpiresAt, long startedAtMillis) {
        long bounded = Math.addExact(startedAtMillis, Duration.ofHours(2).toMillis());
        if (serverExpiresAt == null || serverExpiresAt.isBlank()) return bounded;
        try { return Math.min(bounded, Instant.parse(serverExpiresAt).toEpochMilli()); }
        catch (RuntimeException invalidCachedDate) { return bounded; }
    }

    /** A retry never extends its original deadline; a backwards clock change ends local retention. */
    public static boolean canRetryRevocation(long startedAtMillis, long deadlineMillis, long nowMillis) {
        return startedAtMillis > 0 && deadlineMillis > startedAtMillis
                && deadlineMillis - startedAtMillis <= Duration.ofHours(2).toMillis()
                && nowMillis >= startedAtMillis && nowMillis < deadlineMillis;
    }

    public static void validateDeleteResponse(Object deleted) {
        if (!Boolean.TRUE.equals(deleted)) {
            throw new IllegalStateException("공유를 중단하고 미리보기를 지웠어요. 서버 삭제는 확인하지 못해 직접 다시 확인해야 해요.");
        }
    }
}
