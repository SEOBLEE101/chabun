const fail = (code, message) => ({ ok: false, code, message });
const isRecord = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const exact = (value, fields) => isRecord(value) && Object.keys(value).length === fields.length && fields.every(field => Object.hasOwn(value, field));
export const WORKOUT_MAX_AGE_MS = 120 * 60_000;

export function validateSummary(value, now) {
  if (!exact(value, ['schemaVersion', 'source', 'workout', 'comfort', 'consent'])) return fail('UNEXPECTED_FIELDS', '필요한 운동 요약 항목만 보내 주세요.');
  if (value.schemaVersion !== 1 || !['health_connect', 'healthkit', 'manual', 'demo'].includes(value.source)) return fail('INVALID_SOURCE', '운동 기록의 형식과 출처를 확인해 주세요.');
  if (!exact(value.consent, ['shareWorkoutSummary']) || value.consent.shareWorkoutSummary !== true) return fail('CONSENT_REQUIRED', '차량에 운동 요약을 공유하는 데 동의해 주세요.');
  if (!exact(value.workout, ['type', 'durationMinutes', 'endedAt'])) return fail('UNEXPECTED_HEALTH_FIELDS', '심박·위치·원시 건강 기록은 전송하지 않습니다.');
  const { type, durationMinutes, endedAt } = value.workout;
  if (!['walking', 'running', 'cycling', 'strength', 'other'].includes(type)) return fail('INVALID_WORKOUT_TYPE', '운동 종류를 확인해 주세요.');
  if (!Number.isFinite(durationMinutes) || durationMinutes < 1 || durationMinutes > 240) return fail('INVALID_DURATION', '운동 시간은 1~240분 범위여야 합니다.');
  if (!['cool_down', 'quiet_rest', 'gentle_refresh'].includes(value.comfort)) return fail('INVALID_COMFORT', '지원하는 편안함 설정을 선택해 주세요.');
  if (typeof endedAt !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(endedAt)) return fail('INVALID_DATE', 'UTC 운동 종료 시각이 필요합니다.');
  const endedAtMs = Date.parse(endedAt);
  if (!Number.isFinite(endedAtMs) || new Date(endedAtMs).toISOString().slice(0, 19) !== endedAt.slice(0, 19)) return fail('INVALID_DATE', '실제 달력 날짜와 종료 시각을 확인해 주세요.');
  if (endedAtMs > now) return fail('FUTURE_WORKOUT', '미래 운동 기록은 공유할 수 없습니다. 휴대폰 시간을 확인해 주세요.');
  if (now - endedAtMs >= WORKOUT_MAX_AGE_MS) return fail('STALE_WORKOUT', '종료 후 2시간(120분)이 지난 기록입니다. 직접 시작할 수 있어요.');
  return { ok: true, endedAtMs, summary: { schemaVersion: 1, source: value.source, workout: { type, durationMinutes, endedAt }, comfort: value.comfort, consent: { shareWorkoutSummary: true } } };
}
