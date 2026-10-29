import { ContinuityError } from '../continuity/engine.mjs';

const keys = ['schemaVersion', 'themeId', 'paletteVersion', 'expectedEpoch', 'expectedRevision', 'requestId'];
/** Wire ids of the three renderers the vehicle app implements. Pearl keeps its stored id 'paper'. */
export const APPEARANCE_THEME_IDS = Object.freeze(['graphite', 'paper', 'umber']);
function requireValue(condition, code, message, status = 400) {
  if (!condition) throw new ContinuityError(code, message, status);
}

/** A report of the vehicle application's rendered surface, never a physical command. */
export function reportAppearance(actor, value, epoch, now) {
  requireValue(actor.role === 'vehicle', 'ROLE_FORBIDDEN', '차량 앱만 현재 화면 분위기를 알릴 수 있어요.', 403);
  requireValue(value && typeof value === 'object' && !Array.isArray(value)
    && Object.keys(value).length === keys.length && keys.every(key => Object.hasOwn(value, key)),
  'INVALID_FIELDS', '화면 분위기 보고 항목을 확인해 주세요.');
  // Only a renderer actually implemented in the vehicle app may be reported (exact, case-sensitive id).
  requireValue(value.schemaVersion === 1 && APPEARANCE_THEME_IDS.includes(value.themeId) && value.paletteVersion === 1,
    'UNSUPPORTED_APPEARANCE', '현재 빌드에서 확인할 수 없는 화면 분위기예요.');
  requireValue(Number.isSafeInteger(value.expectedRevision) && value.expectedRevision >= 0
    && typeof value.requestId === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value.requestId),
  'INVALID_REPORT', '보고 버전과 요청 번호를 확인해 주세요.');
  requireValue(value.expectedEpoch === epoch, 'EPOCH_CONFLICT', '서버 세션이 바뀌었어요. 화면 상태를 다시 확인해 주세요.', 409);
  const fingerprint = JSON.stringify(keys.map(key => value[key]));
  if (actor.appearanceRequest?.id === value.requestId) {
    requireValue(actor.appearanceRequest.fingerprint === fingerprint, 'REQUEST_CONFLICT', '같은 요청 번호의 내용이 달라졌어요.', 409);
    return; // A retry cannot make an old observation look fresh.
  }
  const revision = actor.appearance?.revision ?? 0;
  requireValue(value.expectedRevision === revision, 'APPEARANCE_CONFLICT', '화면 분위기 버전이 바뀌었어요. 다시 확인해 주세요.', 409);
  actor.appearance = {
    deviceId: actor.deviceId, themeId: value.themeId, paletteVersion: value.paletteVersion,
    revision: revision + 1, reportedAt: new Date(now).toISOString(),
    expiresAt: new Date(Math.min(actor.expiresAt, now + 120_000)).toISOString()
  };
  actor.appearanceRequest = { id: value.requestId, fingerprint };
}

/** Stale observations remain labelled by their original expiry; revocation removes them. */
export function appearanceView(sessions, now) {
  return {
    schemaVersion: 1,
    serverTime: new Date(now).toISOString(),
    vehicles: [...sessions.values()]
      .filter(actor => actor.role === 'vehicle' && actor.status === 'approved'
        && actor.expiresAt > now && actor.appearance)
      .map(actor => ({ ...actor.appearance }))
      .sort((a, b) => a.deviceId.localeCompare(b.deviceId))
  };
}
