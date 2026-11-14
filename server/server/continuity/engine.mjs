import { readFileSync } from 'node:fs';
import { randomUUID } from 'node:crypto';

const clone = value => structuredClone(value);
const catalog = JSON.parse(readFileSync(new URL('../../shared/presets-v0.4.json', import.meta.url), 'utf8')).presets;
const feelings = ['usual', 'warm', 'quiet', 'tired', 'cold', 'skip'];
const pending = status => ['armed', 'requested'].includes(status);
export class ContinuityError extends Error {
  constructor(code, message, status = 400) { super(message); this.code = code; this.status = status; }
}
function need(test, code, message, status) { if (!test) throw new ContinuityError(code, message, status); }
function object(value) { return value !== null && typeof value === 'object' && !Array.isArray(value); }
function keys(value, allowed, required = allowed) {
  need(object(value) && Object.keys(value).every(k => allowed.includes(k)) && required.every(k => Object.hasOwn(value, k)), 'INVALID_FIELDS', '요청 항목을 확인해 주세요.');
}
function number(value, min, max, step = 1) { return Number.isFinite(value) && value >= min && value <= max && Math.abs(value / step - Math.round(value / step)) < 1e-8; }
function temp(value, max) { return value === null || number(value, 18, max, 0.5); }
export function validatePreset(input) {
  keys(input, ['id', 'name', 'kind', 'comfort', 'vehicle', 'home'], ['name', 'comfort', 'vehicle', 'home']);
  need(typeof input.name === 'string' && input.name.trim().length >= 1 && input.name.trim().length <= 40 && !/[\u0000-\u001f]/.test(input.name), 'INVALID_NAME', '이름은 1~40자로 입력해 주세요.');
  need(['cool_down', 'quiet_rest', 'gentle_refresh'].includes(input.comfort), 'INVALID_COMFORT', '기존 취향 중 하나를 골라 주세요.');
  keys(input.vehicle, ['tempC', 'fan', 'seat']); keys(input.home, ['tempC', 'fan', 'power']);
  need(temp(input.vehicle.tempC, 28) && temp(input.home.tempC, 30), 'INVALID_TEMPERATURE', '차량은18~28℃, 집은18~30℃ 범위에서0.5℃ 단위로 입력하거나 현재 유지를 선택해 주세요.');
  need(['keep', 'low', 'medium', 'high'].includes(input.vehicle.fan) && ['keep', 'auto', 'low', 'medium', 'high'].includes(input.home.fan), 'INVALID_FAN', '시연에서 지원하는 바람 선호를 골라 주세요.');
  need(['keep', 'manual_comfort'].includes(input.vehicle.seat) && ['keep', 'on'].includes(input.home.power), 'UNSUPPORTED_ACTION', '시트는 선호 메모만, 집 전원은 유지 또는 켜기만 선택할 수 있어요.');
  if (input.id !== undefined) need(typeof input.id === 'string' && /^[a-zA-Z0-9_-]{1,80}$/.test(input.id), 'INVALID_ID', '프리셋을 다시 선택해 주세요.');
  return { name: input.name.trim(), comfort: input.comfort, vehicle: clone(input.vehicle), home: clone(input.home) };
}

/**
 * The phone's 차 안 설정 for one rest (cabin contract v1): seat heat/ventilation, climate and ambient light per group.
 * "keep" means the rest leaves that group alone. Only the vehicle app decides whether and when anything is applied.
 */
export function validateCabin(input) {
  keys(input, ['seatDriver', 'seatPassenger', 'climDriver', 'climPassenger', 'light']);
  const bad = message => need(false, 'INVALID_CABIN', message);
  for (const seat of [input.seatDriver, input.seatPassenger]) {
    keys(seat, ['position', 'comfort', 'level']);
    if (!['keep', 'manual'].includes(seat.position) || !['keep', 'off', 'heat', 'vent'].includes(seat.comfort) || !number(seat.level, 1, 3)) bad('시트 값을 확인해 주세요.');
  }
  for (const c of [input.climDriver, input.climPassenger]) {
    keys(c, ['mode', 'temp', 'fan', 'dir', 'ac']);
    if (!['keep', 'custom'].includes(c.mode) || !number(c.temp, 18, 28, 0.5) || !['auto', 'low', 'medium', 'high'].includes(c.fan)
        || !['keep', 'upper', 'lower', 'both'].includes(c.dir) || !['keep', 'on', 'off'].includes(c.ac)) bad('에어컨 값을 확인해 주세요.');
  }
  keys(input.light, ['mode', 'color', 'bright']);
  if (!['keep', 'off', 'custom'].includes(input.light.mode) || !['cream', 'neutral', 'sage', 'mist'].includes(input.light.color)
      || !number(input.light.bright, 10, 100)) bad('조명 값을 확인해 주세요.');
  return clone(input);
}

/** Korea-only coordinates rounded to 4 decimals (about 11 m); both null when the place has no point. */
export const KR_LAT = [33, 39], KR_LON = [124, 132];
export const round4 = value => Math.round(value * 1e4) / 1e4;
export const inKorea = (lat, lon) => Number.isFinite(lat) && Number.isFinite(lon) && lat >= KR_LAT[0] && lat <= KR_LAT[1] && lon >= KR_LON[0] && lon <= KR_LON[1];

/** One 쉬어 갈 곳 the phone hands to the car: name, address and an optional point. No visits, favourites or history. */
export function validatePlace(input) {
  keys(input, ['name', 'address', 'lat', 'lon']);
  need(typeof input.name === 'string' && input.name.trim().length >= 1 && input.name.trim().length <= 40 && !/[\u0000-\u001f\u007f]/.test(input.name), 'INVALID_PLACE', '장소 이름은 1~40자로 보내 주세요.');
  need(typeof input.address === 'string' && input.address.length <= 120 && !/[\u0000-\u001f\u007f]/.test(input.address), 'INVALID_PLACE', '주소는 120자 이내로 보내 주세요.');
  const none = input.lat === null && input.lon === null;
  need(none || (typeof input.lat === 'number' && typeof input.lon === 'number' && inKorea(input.lat, input.lon)), 'INVALID_PLACE', '장소 위치를 확인해 주세요.');
  return { name: input.name.trim(), address: input.address.trim(), lat: none ? null : round4(input.lat), lon: none ? null : round4(input.lon) };
}

/** In-memory synthetic-only coordinator. Never contacts a device, health store, or location API. */
export class ContinuityEngine {
  constructor({ now = Date.now() } = {}) {
    this.clock = now; this.seen = new Map(); this.physical = { tempC: 27, fan: 'auto', power: 'off' };
    this.state = {
      demo: true, revision: 0, now: this.iso(), presets: clone(catalog), selectedId: 'quiet_rest', defaultId: 'quiet_rest', autoDefaultOnRest: false,
      feeling: 'skip', recommendation: null, plan: null,
      devices: {
        vehicle: { parked: true, proposal: null, confirmedPresetId: null, simulated: true, place: null },
        home: { id: 'home-ac', name: '예시 집 · 거실 에어컨', ...clone(this.physical), revision: 0, online: true, updatedBy: 'fixture', observedAt: this.iso() }
      },
      events: [], providers: [
        { id: 'demo', name: '연결 경험 시연', status: 'demo', detail: '같은 서버의 폰·차·집 화면이 합성 상태를 공유해요.', liveCommandAvailable: false },
        { id: 'smartthings', name: 'Samsung SmartThings', status: 'setup_required', detail: '개인 시험용 기기 조회·지원값 진단 도구. 실제 계정·기기 확인과 명령 연결이 필요해요.', liveCommandAvailable: false },
        { id: 'apple_home', name: 'Apple Home', status: 'setup_required', detail: '아이폰의 HomeKit 연결·집/장면 조회 준비. 이 시연에서 집에 명령하지 않아요.', liveCommandAvailable: false },
        { id: 'google_home', name: 'Google Home', status: 'setup_required', detail: '별도 모바일 SDK·프로젝트·서명·기기 지원 확인이 필요해요.', liveCommandAvailable: false }
      ]
    };
    this.event('system', '예시 환경을 열었어요. 실제 기기·건강정보·위치를 사용하지 않아요.');
  }
  iso(time = this.clock) { return new Date(time).toISOString(); }
  getState() { return clone(this.state); }
  preset(id) { const p = this.state.presets.find(p => p.id === id); need(p, 'PRESET_NOT_FOUND', '프리셋을 찾지 못했어요.', 404); return p; }
  event(origin, label) { this.state.events.unshift({ id: randomUUID(), at: this.iso(), origin: this.actor?.role ?? origin, label }); this.state.events = this.state.events.slice(0, 40); }
  recommend() {
    const f = this.state.feeling;
    const choices = {
      usual: [this.state.defaultId, '기본으로 저장한 선호예요.'], warm: ['cool_down', '조금 덥다고 고르셔서 시원한 분위기를 먼저 보여드려요.'],
      quiet: ['quiet_rest', '조용히 쉬고 싶다고 고르셔서 낮은 바람을 선호하는 프리셋을 보여드려요.'], tired: ['quiet_rest', '오늘 조금 지쳤다고 직접 고르셔서 조용한 분위기를 먼저 보여드려요. 건강 상태를 판단한 추천은 아니에요.'], cold: ['gentle_refresh', '조금 서늘하다고 고르셔서 부드러운 분위기를 보여드려요.']
    };
    this.state.recommendation = choices[f] ? { presetId: choices[f][0], reason: choices[f][1] } : null;
  }
  execute(command, { actor, commit } = {}) {
    keys(command, ['action', 'expectedRevision', 'requestId', 'payload']);
    need(typeof command.action === 'string' && object(command.payload) && Number.isSafeInteger(command.expectedRevision), 'INVALID_COMMAND', '명령 형식을 확인해 주세요.');
    need(typeof command.requestId === 'string' && /^[a-zA-Z0-9_-]{8,80}$/.test(command.requestId), 'INVALID_REQUEST_ID', '요청 번호를 다시 만들어 주세요.');
    const fingerprint = JSON.stringify([command.action, command.payload, command.expectedRevision]);
    const key = actor ? actor.deviceId + ':' + command.requestId : command.requestId;
    const previous = this.seen.get(key);
    if (previous !== undefined) { need(previous === fingerprint, 'REQUEST_ID_REUSED', '다른 변경에 같은 요청 번호를 사용할 수 없어요.', 409); return this.getState(); }
    need(command.expectedRevision === this.state.revision, 'REVISION_CONFLICT', '다른 화면에서 상태가 바뀌었어요. 최신 내용을 확인하고 다시 선택해 주세요.', 409);
    const before = clone(this.state), physicalBefore = clone(this.physical), clockBefore = this.clock;
    try {
      this.actor = actor;
      this.dispatch(command.action, command.payload);
      this.state.revision++; this.state.now = this.iso(); this.recommend();
      this.seen.set(key, fingerprint);
      // Keep idempotency records for the lifetime of this small demo. Bound the session rather than evicting old IDs.
      need(this.seen.size <= 10000, 'DEMO_SESSION_LIMIT', '긴 시연 세션을 마쳤어요. 서버를 다시 열어 새로 시작해 주세요.', 429);
      commit?.(this.getState());
      return this.getState();
    } catch (error) { this.state = before; this.physical = physicalBefore; this.clock = clockBefore; this.seen.delete(key); throw error; }
    finally { this.actor = undefined; }
  }
  dispatch(action, p) {
    switch (action) {
      case 'select_preset': keys(p, ['id']); this.state.selectedId = this.preset(p.id).id; this.event('phone', '프리셋을 선택했어요. 기기에는 적용하지 않았어요.'); break;
      case 'set_feeling': keys(p, ['value']); need(feelings.includes(p.value), 'INVALID_FEELING', '지금 느끼는 취향을 골라 주세요.'); this.state.feeling = p.value; break;
      case 'set_default':
        keys(p, ['id', 'autoDefaultOnRest']); need(typeof p.autoDefaultOnRest === 'boolean', 'INVALID_ENABLE', '자동 선택 여부를 확인해 주세요.');
        this.state.defaultId = this.preset(p.id).id; this.state.autoDefaultOnRest = p.autoDefaultOnRest; this.event('phone', '기본 선호를 저장했어요. 집 예약은 별도로 켜야 해요.'); break;
      case 'save_preset': {
        const clean = validatePreset(p); let id = p.id;
        if (id) need(this.preset(id).kind === 'custom', 'BUILTIN_READ_ONLY', '기본 프리셋은 복사해서 내 프리셋으로 저장해 주세요.');
        else { need(this.state.presets.length < 53, 'PRESET_LIMIT', '내 프리셋은50개까지 저장할 수 있어요.'); id = 'custom-' + randomUUID(); }
        const value = { id, kind: 'custom', ...clean }; const index = this.state.presets.findIndex(x => x.id === id);
        if (index >= 0) this.state.presets[index] = value; else this.state.presets.push(value);
        this.state.selectedId = id; this.event('phone', '내 프리셋을 저장했어요. 이미 켠 예약의 내용은 바뀌지 않아요.'); break;
      }
      case 'delete_preset':
        keys(p, ['id']); need(this.preset(p.id).kind === 'custom', 'BUILTIN_READ_ONLY', '기본 프리셋은 삭제할 수 없어요.');
        this.state.presets = this.state.presets.filter(x => x.id !== p.id);
        if (this.state.selectedId === p.id) this.state.selectedId = 'quiet_rest'; if (this.state.defaultId === p.id) this.state.defaultId = 'quiet_rest';
        this.event('phone', '내 프리셋을 지웠어요. 이미 확인한 예약은 저장된 내용대로 남아요.'); break;
      case 'begin_rest':
        keys(p, ['origin']); need(['phone', 'vehicle'].includes(p.origin), 'INVALID_ORIGIN', '선택한 화면을 확인해 주세요.');
        need(this.state.devices.vehicle.parked, 'VEHICLE_NOT_PARKED', '주차 상태를 확인한 뒤 차량의 휴식 준비를 선택해 주세요.');
        if (this.state.autoDefaultOnRest) this.state.selectedId = this.state.defaultId;
        this.state.devices.vehicle.proposal = clone(this.preset(this.state.selectedId)); this.state.devices.vehicle.confirmedPresetId = null; this.event(p.origin, '휴식 선호를 차량에 제안했어요. 실제 쉼·차량 제어는 시작하지 않았어요.'); break;
      case 'propose_vehicle': keys(p, ['id']); this.state.devices.vehicle.proposal = clone(this.preset(p.id)); this.state.devices.vehicle.confirmedPresetId = null; this.event('phone', '차량에 선택 제안을 보냈어요. 시트는 직접 조절해 주세요.'); break;
      case 'propose_rest': {
        // The rest as the phone shows it, with its 차 안 설정; no workout data. The car still starts it on its own screen.
        keys(p, ['preset']); keys(p.preset, ['name', 'comfort', 'vehicle', 'cabin']);
        const clean = validatePreset({ name: p.preset.name, comfort: p.preset.comfort, vehicle: p.preset.vehicle, home: { tempC: null, fan: 'keep', power: 'keep' } });
        this.state.devices.vehicle.proposal = { id: 'phone-' + randomUUID(), kind: 'custom', ...clean, cabin: validateCabin(p.preset.cabin) };
        this.state.devices.vehicle.confirmedPresetId = null;
        this.event('phone', '폰에서 고른 쉼과 차 안 설정을 차량에 제안했어요. 시작과 적용은 차량 화면에서 해요.'); break;
      }
      case 'propose_place':
        // Only the place itself; the car starts any 길안내 on its own screen.
        keys(p, ['place']); this.state.devices.vehicle.place = { id: 'place-' + randomUUID(), ...validatePlace(p.place) };
        this.event('phone', '폰에서 쉬어 갈 곳을 차량에 보냈어요. 길안내는 차량 화면에서 직접 시작해요.'); break;
      case 'clear_place':
        keys(p, ['id']); need(typeof p.id === 'string', 'INVALID_ID', '장소를 다시 확인해 주세요.');
        need(this.state.devices.vehicle.place?.id === p.id, 'PLACE_CHANGED', '쉬어 갈 곳이 바뀌었어요. 최신 내용을 확인해 주세요.', 409);
        this.state.devices.vehicle.place = null; this.event('vehicle', '차량에서 쉬어 갈 곳을 확인했어요.'); break;
      case 'accept_vehicle':
        keys(p, ['origin']); need(p.origin === 'vehicle' && this.state.devices.vehicle.parked, 'VEHICLE_NOT_PARKED', '예시 차량의 주차 상태에서 확인해 주세요.');
        need(this.state.devices.vehicle.proposal, 'NO_PROPOSAL', '먼저 프리셋을 제안해 주세요.');
        this.state.devices.vehicle.confirmedPresetId = this.state.devices.vehicle.proposal.id; this.event('vehicle', '차량에서 선호를 확인했어요. 공조·시트 동작은 합성 시연이에요.'); break;
      case 'arm_arrival': this.arm(p, false); break;
      case 'update_arrival': this.arm(p, true); break;
      case 'cancel_arrival':
        keys(p, []); need(this.state.plan, 'NO_PLAN', '아직 켜 둔 예약이 없어요.'); this.state.plan.status = 'cancelled'; this.state.plan.enabled = false;
        this.state.plan.message = '남은 예약을 취소했어요. 이미 요청한 기기 설정은 자동으로 되돌리지 않아요.';
        this.event('phone', this.state.plan.message); break;
      case 'advance_time':
        keys(p, ['minutes']); need(Number.isFinite(p.minutes) && p.minutes >= 0.1 && p.minutes <= 180, 'INVALID_TIME', '예시 시간은0.1~180분만 전진할 수 있어요.');
        this.clock += Math.round(p.minutes * 60000); this.tick(); break;
      case 'confirm_home': keys(p, []); this.confirm(); break;
      case 'set_home_manual': {
        keys(p, ['tempC', 'fan', 'power'], []); need(Object.keys(p).length > 0, 'NO_CHANGES', '직접 바꿀 값을 선택해 주세요.');
        if (p.tempC !== undefined) need(number(p.tempC, 18, 30, 0.5), 'INVALID_TEMPERATURE', '예시 집의 희망 온도 범위를 확인해 주세요.');
        if (p.fan !== undefined) need(['auto', 'low', 'medium', 'high'].includes(p.fan), 'INVALID_FAN', '바람 값을 확인해 주세요.');
        if (p.power !== undefined) need(['on', 'off'].includes(p.power), 'INVALID_POWER', '전원 값을 확인해 주세요.');
        this.physical = { ...this.physical, ...clone(p) }; Object.assign(this.state.devices.home, this.physical);
        this.state.devices.home.revision++; this.state.devices.home.updatedBy = 'home-manual'; this.state.devices.home.observedAt = this.iso();
        if (this.state.plan && pending(this.state.plan.status)) { this.state.plan.status = 'needs_review'; this.state.plan.enabled = false; this.state.plan.message = '집에서 직접 바꾼 설정을 존중해 예약을 멈췄어요. 다시 확인해 주세요.'; }
        this.event('home', '다른 앱·리모컨의 수동 변경을 시연했어요. 예전 예약으로 덮어쓰지 않아요.'); break;
      }
      case 'set_target_online': keys(p, ['online']); need(typeof p.online === 'boolean', 'INVALID_ONLINE', '연결 상태를 확인해 주세요.'); this.state.devices.home.online = p.online; break;
      case 'set_vehicle_gate': keys(p, ['parked']); need(typeof p.parked === 'boolean', 'INVALID_GATE', '주차 상태를 확인해 주세요.'); this.state.devices.vehicle.parked = p.parked; if (!p.parked) this.state.devices.vehicle.confirmedPresetId = null; break;
      default: throw new ContinuityError('UNKNOWN_ACTION', '지원하지 않는 시연 동작이에요.');
    }
  }
  arm(p, update) {
    keys(p, update ? ['etaMinutes', 'leadMinutes', 'consent'] : ['presetId', 'target', 'etaMinutes', 'leadMinutes', 'consent']);
    need(p.consent === true, 'CONSENT_REQUIRED', '대상과 변경 내용을 확인한 뒤 이번 예약을 켜 주세요.');
    need(number(p.etaMinutes, 1, 180) && number(p.leadMinutes, 1, 30) && p.etaMinutes > p.leadMinutes, 'INVALID_ARRIVAL', '예상 도착은1~180분, 미리 준비는1~30분이며 도착까지 남은 시간이 더 길어야 해요.');
    const previous = this.state.plan;
    if (update) need(previous && !previous.requestedAt && ['armed', 'needs_review', 'failed', 'expired'].includes(previous.status), 'PLAN_NOT_EDITABLE', '이미 요청한 예약은 다시 실행하지 않아요. 새 예약을 확인해 주세요.');
    else { need(p.target === 'home-ac', 'UNSUPPORTED_TARGET', '지원하는 예시 집 대상을 골라 주세요.'); need(!previous || !pending(previous.status), 'ACTIVE_PLAN', '이미 켜 둔 예약을 수정하거나 먼저 취소해 주세요.'); }
    const preset = update ? previous.presetSnapshot : this.preset(p.presetId);
    this.state.plan = {
      id: update ? previous.id : randomUUID(), version: update ? previous.version + 1 : 1, presetId: preset.id, presetName: preset.name,
      presetSnapshot: clone(preset), targetId: 'home-ac', createdAt: this.iso(), arrivalAt: this.iso(this.clock + p.etaMinutes * 60000),
      executeAt: this.iso(this.clock + (p.etaMinutes - p.leadMinutes) * 60000), leadMinutes: p.leadMinutes,
      status: 'armed', enabled: true, targetRevision: this.state.devices.home.revision,
      message: '이번 귀가의 예약 시연을 켰어요. 실제 위치·집 기기는 연결하지 않았어요.'
    };
    this.event('phone', update ? '도착 준비 예약을 새 버전으로 바꿨어요. 이전 시각의 요청은 무효예요.' : '선택한 프리셋의 현재 내용으로 이번 귀가 예약을 켰어요.');
  }
  tick() {
    const plan = this.state.plan; if (!plan || !plan.enabled || plan.status !== 'armed') return;
    const due = Date.parse(plan.executeAt); if (this.clock < due) return;
    if (this.clock > due + 60000) { plan.status = 'expired'; plan.enabled = false; plan.message = '예정 시각을 지났어요. 놓친 요청을 뒤늦게 실행하지 않아요.'; this.event('system', plan.message); return; }
    const home = this.state.devices.home;
    if (home.revision !== plan.targetRevision) { plan.status = 'needs_review'; plan.enabled = false; plan.message = '집 설정이 바뀌어 다시 확인해야 해요.'; return; }
    if (!home.online) { plan.status = 'failed'; plan.enabled = false; plan.message = '집 연결을 확인하지 못했어요. 설정을 바꾸지 않았어요.'; this.event('system', plan.message); return; }
    const pref = plan.presetSnapshot.home;
    if (pref.tempC !== null) this.physical.tempC = pref.tempC;
    if (pref.fan !== 'keep') this.physical.fan = pref.fan;
    if (pref.power !== 'keep') this.physical.power = pref.power;
    plan.status = 'requested'; plan.requestedAt = this.iso(); plan.enabled = false;
    plan.message = '예시 기기에 요청을 접수했어요. 상태 확인 전에는 준비 완료로 표시하지 않아요.';
    this.event('system', plan.message);
  }
  confirm() {
    const plan = this.state.plan; need(plan?.status === 'requested', 'NOT_REQUESTED', '확인할 실행 요청이 없어요.');
    const home = this.state.devices.home;
    if (!home.online) { plan.status = 'failed'; plan.message = '요청 이후 기기 상태를 확인하지 못했어요. 자동 재전송하지 않아요.'; this.event('home', plan.message); return; }
    if (home.revision !== plan.targetRevision) { plan.status = 'needs_review'; plan.message = '다른 변경이 있어 적용 완료를 단정하지 않아요.'; return; }
    Object.assign(home, clone(this.physical)); home.revision++; home.updatedBy = 'arrival-demo'; home.observedAt = this.iso();
    const pref = plan.presetSnapshot.home;
    const matches = (pref.tempC === null || home.tempC === pref.tempC) && (pref.fan === 'keep' || home.fan === pref.fan) && (pref.power === 'keep' || home.power === pref.power);
    if (!matches) { plan.status = 'failed'; plan.message = '관측한 예시 기기 설정이 요청과 달라요. 자동으로 다시 보내지 않아요.'; this.event('home', plan.message); return; }
    plan.status = 'confirmed_demo'; plan.confirmedAt = this.iso(); plan.message = '예시 기기의 설정 일치를 확인했어요. 실제 실내 온도 도달이나 실제 집 동작을 뜻하지 않아요.';
    this.event('home', plan.message);
  }
}
