package ai.pitin.vehicle;

import org.json.JSONObject;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** A preference suggestion, never an executable vehicle command. */
final class RecoveryProposal {
    private static final Set<String> SOURCES = new HashSet<>(Arrays.asList("health_connect", "healthkit", "manual", "demo"));
    private static final Set<String> WORKOUTS = new HashSet<>(Arrays.asList("walking", "running", "cycling", "strength", "other"));
    private static final Set<String> COMFORTS = new HashSet<>(Arrays.asList("cool_down", "quiet_rest", "gentle_refresh"));
    final String id;
    final String description;
    final String comfort;
    final long expiresAtEpochMs;

    private RecoveryProposal(String id, String description, String comfort, long expiresAt) {
        this.id = id;
        this.description = description;
        this.comfort = comfort;
        this.expiresAtEpochMs = expiresAt;
    }

    static RecoveryProposal fromEnvelope(JSONObject envelope, long now) throws Exception {
        if (!"available".equals(envelope.optString("reason")) || envelope.isNull("summary")) return null;
        JSONObject body = envelope.getJSONObject("summary");
        if (body.getInt("schemaVersion") != 1
                || !body.getJSONObject("consent").getBoolean("shareWorkoutSummary"))
            throw new IllegalArgumentException("Unsupported or unconsented summary");
        String source = body.getString("source");
        String comfort = body.getString("comfort");
        JSONObject workout = body.getJSONObject("workout");
        String type = workout.getString("type");
        double minutes = workout.getDouble("durationMinutes");
        if (!SOURCES.contains(source) || !COMFORTS.contains(comfort)
                || !WORKOUTS.contains(type) || !Double.isFinite(minutes) || minutes < 1 || minutes > 240)
            throw new IllegalArgumentException("Invalid summary");
        long endedAt = Instant.parse(workout.getString("endedAt")).toEpochMilli();
        long expiresAt = Instant.parse(envelope.getString("expiresAt")).toEpochMilli();
        expiresAt = Math.min(expiresAt, Math.addExact(endedAt, 120 * 60_000L));
        if (endedAt > now || now >= expiresAt) return null;
        String id = envelope.getString("summaryId");
        String sourceLabel = source.equals("demo") ? "DEMO 합성 운동"
                : source.equals("manual") ? "직접 입력한 운동" : "휴대폰에서 공유한 운동";
        String duration = minutes == Math.rint(minutes) ? Integer.toString((int) minutes)
                : String.format(java.util.Locale.ROOT, "%.1f", minutes);
        return new RecoveryProposal(id, sourceLabel + " · " + workoutLabel(type) + " · " + duration
                + "분 (최소 요약만 공유됨)", comfort, expiresAt);
    }

    static RecoveryProposal manual(long now) {
        return new RecoveryProposal("local-manual-" + now,
                "운동 요약 없이 쉬기 · 아래에서 원하는 편안함 선호를 직접 골라 주세요.", "", now + 30 * 60_000L);
    }

    boolean isCurrent(long now) { return now < expiresAtEpochMs; }

    private static String workoutLabel(String type) {
        if ("walking".equals(type)) return "걷기";
        if ("running".equals(type)) return "달리기";
        if ("cycling".equals(type)) return "자전거";
        if ("strength".equals(type)) return "근력 운동";
        return "기타 운동";
    }

    static String label(String key) {
        if ("cool_down".equals(key)) return "산뜻하게 식히기 (Cool down)";
        if ("quiet_rest".equals(key)) return "조용히 쉬기 (Quiet rest)";
        if ("gentle_refresh".equals(key)) return "부드럽게 기분 전환 (Gentle refresh)";
        return "선호를 선택해 주세요";
    }
}
