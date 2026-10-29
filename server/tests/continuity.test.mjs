import test from 'node:test';
import assert from 'node:assert/strict';
import { request } from 'node:http';
import { ContinuityEngine } from '../server/continuity/engine.mjs';
import { createContinuityServer } from '../server/continuity/index.mjs';

const baseTime = Date.parse('2026-10-24T18:00:00+09:00');
let sequence = 0;
function engine() { return new ContinuityEngine({ now: baseTime }); }
function cmd(e, action, payload = {}, id) { return e.execute({ action, payload, expectedRevision: e.getState().revision, requestId: id || 'request-' + (++sequence).toString().padStart(8, '0') }); }
function custom(e, name = '내 편안함') { const p = e.getState().presets[0]; return { name, comfort: p.comfort, vehicle: p.vehicle, home: p.home }; }
function arm(e, extra = {}) { return cmd(e, 'arm_arrival', { presetId: 'quiet_rest', target: 'home-ac', etaMinutes: 20, leadMinutes: 10, consent: true, ...extra }); }
function code(expected) { return error => error.code === expected; }

test('continuity begins explicitly synthetic with auto selection and arrival off', () => {
  const s = engine().getState(); assert.equal(s.demo, true); assert.equal(s.autoDefaultOnRest, false); assert.equal(s.plan, null); assert.equal(s.presets.length, 3);
  assert(s.providers.every(p => p.liveCommandAvailable === false)); assert.equal(s.devices.vehicle.simulated, true);
});
test('self-reported feeling explains recommendation but never changes selection or arms control', () => {
  const e = engine(); const s = cmd(e, 'set_feeling', { value: 'warm' }); assert.equal(s.recommendation.presetId, 'cool_down'); assert.match(s.recommendation.reason, /고르셔서/); assert.equal(s.selectedId, 'quiet_rest'); assert.equal(s.plan, null);
  assert.equal(cmd(e, 'set_feeling', { value: 'skip' }).recommendation, null);
  assert.equal(cmd(e, 'set_feeling', { value: 'tired' }).recommendation.presetId, 'quiet_rest'); assert.equal(e.getState().plan, null);
});
test('custom creation, edit and deletion preserve read-only builtins and default fallback', () => {
  const e = engine(); let s = cmd(e, 'save_preset', custom(e)); const id = s.selectedId; assert.equal(s.presets.length, 4);
  cmd(e, 'set_default', { id, autoDefaultOnRest: true }); s = cmd(e, 'save_preset', { ...custom(e, '수정한 이름'), id }); assert.equal(s.presets.find(p => p.id === id).name, '수정한 이름');
  assert.throws(() => cmd(e, 'save_preset', { ...custom(e), id: 'quiet_rest' }), code('BUILTIN_READ_ONLY'));
  assert.throws(() => cmd(e, 'delete_preset', { id: 'cool_down' }), code('BUILTIN_READ_ONLY'));
  s = cmd(e, 'delete_preset', { id }); assert.equal(s.defaultId, 'quiet_rest'); assert.equal(s.selectedId, 'quiet_rest');
});
test('invalid numeric or unsupported fields cannot mutate preferences or include health data', () => {
  const e = engine(); const baseline = e.getState();
  assert.throws(() => cmd(e, 'save_preset', { ...custom(e), home: { tempC: 16, fan: 'low', power: 'on' } }), code('INVALID_TEMPERATURE'));
  assert.throws(() => cmd(e, 'save_preset', { ...custom(e), vehicle: { tempC: 23.3, fan: 'low', seat: 'keep' } }), code('INVALID_TEMPERATURE'));
  assert.throws(() => cmd(e, 'save_preset', { ...custom(e), workout: { heartRate: 80 } }), code('INVALID_FIELDS'));
  assert.deepEqual(e.getState(), baseline);
});
test('opted-in default is selected on rest preparation without starting breathing or writing a vehicle', () => {
  const e = engine(); cmd(e, 'set_default', { id: 'cool_down', autoDefaultOnRest: true }); cmd(e, 'select_preset', { id: 'gentle_refresh' });
  const s = cmd(e, 'begin_rest', { origin: 'phone' }); assert.equal(s.selectedId, 'cool_down'); assert.equal(s.devices.vehicle.proposal.id, 'cool_down'); assert.equal(s.devices.vehicle.confirmedPresetId, null); assert.equal(s.plan, null);
});
test('vehicle confirmation needs the explicit simulated parked gate', () => {
  const e = engine(); cmd(e, 'propose_vehicle', { id: 'quiet_rest' }); cmd(e, 'set_vehicle_gate', { parked: false });
  assert.throws(() => cmd(e, 'accept_vehicle', { origin: 'vehicle' }), code('VEHICLE_NOT_PARKED'));
  cmd(e, 'set_vehicle_gate', { parked: true }); assert.equal(cmd(e, 'accept_vehicle', { origin: 'vehicle' }).devices.vehicle.confirmedPresetId, 'quiet_rest');
  assert.equal(cmd(e, 'propose_vehicle', { id: 'cool_down' }).devices.vehicle.confirmedPresetId, null);
});
const cabin = () => ({
  seatDriver: { position: 'keep', comfort: 'vent', level: 2 }, seatPassenger: { position: 'keep', comfort: 'keep', level: 1 },
  climDriver: { mode: 'custom', temp: 22.5, fan: 'low', dir: 'keep', ac: 'keep' }, climPassenger: { mode: 'keep', temp: 24, fan: 'auto', dir: 'keep', ac: 'keep' },
  light: { mode: 'custom', color: 'sage', bright: 40 }
});
const phoneRest = (extra = {}) => ({ preset: { name: '조용히 쉬기', comfort: 'quiet_rest', vehicle: { tempC: 24, fan: 'low', seat: 'keep' }, cabin: cabin(), ...extra } });
test('a phone rest reaches the car with its cabin settings, without workout data or a start', () => {
  const e = engine(); const s = cmd(e, 'propose_rest', phoneRest());
  const p = s.devices.vehicle.proposal;
  assert.match(p.id, /^phone-/); assert.equal(p.kind, 'custom'); assert.equal(p.comfort, 'quiet_rest');
  assert.deepEqual(p.cabin, cabin()); assert.deepEqual(p.home, { tempC: null, fan: 'keep', power: 'keep' });
  assert.equal(s.devices.vehicle.confirmedPresetId, null); assert.equal(s.presets.length, 3); assert.equal(s.plan, null);
});
test('a phone rest with a malformed cabin or extra data changes nothing', () => {
  const e = engine(); const baseline = e.getState();
  const broken = [
    phoneRest({ cabin: { ...cabin(), seatDriver: { position: 'keep', comfort: 'massage', level: 1 } } }),
    phoneRest({ cabin: { ...cabin(), seatDriver: { position: 'keep', comfort: 'heat', level: 4 } } }),
    phoneRest({ cabin: { ...cabin(), climDriver: { mode: 'custom', temp: 22.3, fan: 'low', dir: 'keep', ac: 'keep' } } }),
    phoneRest({ cabin: { ...cabin(), light: { mode: 'custom', color: 'red', bright: 40 } } }),
    phoneRest({ cabin: { ...cabin(), window: 'open' } }),
    phoneRest({ workout: { heartRate: 80 } })
  ];
  for (const payload of broken) assert.throws(() => cmd(e, 'propose_rest', payload), error => ['INVALID_CABIN', 'INVALID_FIELDS'].includes(error.code));
  assert.throws(() => cmd(e, 'propose_rest', { preset: { ...phoneRest().preset, cabin: undefined } }), code('INVALID_FIELDS'));
  assert.deepEqual(e.getState(), baseline);
});
test('arrival needs separate consent, known target and a future execution point', () => {
  const e = engine(); assert.throws(() => arm(e, { consent: false }), code('CONSENT_REQUIRED'));
  assert.throws(() => arm(e, { etaMinutes: 10, leadMinutes: 10 }), code('INVALID_ARRIVAL'));
  assert.throws(() => arm(e, { target: 'unknown-ac' }), code('UNSUPPORTED_TARGET'));
  assert.equal(e.getState().plan, null);
});
test('arrival snapshots survive later preset editing or deletion', () => {
  const e = engine(); const id = cmd(e, 'save_preset', custom(e)).selectedId; arm(e, { presetId: id }); const snapshot = e.getState().plan.presetSnapshot;
  cmd(e, 'save_preset', { ...custom(e, '바뀐 프리셋'), id, home: { tempC: 29, fan: 'high', power: 'on' } }); cmd(e, 'delete_preset', { id });
  assert.deepEqual(e.getState().plan.presetSnapshot, snapshot);
});
test('arrival distinguishes request accepted from observed setting confirmation', () => {
  const e = engine(); arm(e); cmd(e, 'advance_time', { minutes: 5 }); assert.equal(e.getState().plan.status, 'armed');
  let s = cmd(e, 'advance_time', { minutes: 5 }); assert.equal(s.plan.status, 'requested'); assert.equal(s.devices.home.tempC, 27);
  s = cmd(e, 'confirm_home'); assert.equal(s.plan.status, 'confirmed_demo'); assert.equal(s.devices.home.tempC, 25); assert.equal(s.devices.home.power, 'on');
  const requests = s.events.filter(x => x.label.includes('요청을 접수')).length;
  cmd(e, 'advance_time', { minutes: 5 }); assert.equal(e.getState().events.filter(x => x.label.includes('요청을 접수')).length, requests);
});
test('observed mismatch never becomes confirmed and does not automatically retry', () => {
  const e = engine(); arm(e); cmd(e, 'advance_time', { minutes: 10 }); e.physical.tempC = 29;
  const s = cmd(e, 'confirm_home'); assert.equal(s.plan.status, 'failed'); assert.equal(s.devices.home.tempC, 29); assert.equal(s.plan.enabled, false);
});
test('late catch-up expires rather than switching a device on unexpectedly', () => {
  const e = engine(); arm(e); const s = cmd(e, 'advance_time', { minutes: 12 }); assert.equal(s.plan.status, 'expired'); assert.equal(s.devices.home.power, 'off'); assert.equal(e.physical.power, 'off');
});
test('cancellation invalidates pending work and never claims rollback after request', () => {
  const e = engine(); arm(e); cmd(e, 'cancel_arrival'); cmd(e, 'advance_time', { minutes: 10 }); assert.equal(e.physical.power, 'off');
  arm(e); cmd(e, 'advance_time', { minutes: 10 }); assert.equal(e.physical.power, 'on'); const s = cmd(e, 'cancel_arrival'); assert.equal(e.physical.power, 'on'); assert.match(s.plan.message, /되돌리지/);
});
test('manual home changes stop old plans and propagate without a control feedback loop', () => {
  const e = engine(); arm(e); cmd(e, 'set_home_manual', { tempC: 28, fan: 'high' }); const s = cmd(e, 'advance_time', { minutes: 10 });
  assert.equal(s.plan.status, 'needs_review'); assert.equal(s.devices.home.tempC, 28); assert.equal(e.physical.tempC, 28); assert.equal(s.devices.home.updatedBy, 'home-manual');
});
test('offline trigger and lost readback cannot claim successful appliance preparation', () => {
  const e = engine(); arm(e); cmd(e, 'set_target_online', { online: false }); cmd(e, 'advance_time', { minutes: 10 }); assert.equal(e.getState().plan.status, 'failed'); assert.equal(e.physical.power, 'off');
  cmd(e, 'set_target_online', { online: true }); arm(e); cmd(e, 'advance_time', { minutes: 10 }); cmd(e, 'set_target_online', { online: false }); assert.equal(cmd(e, 'confirm_home').plan.status, 'failed');
});
test('arrival edits supersede the previous time and require new explicit consent', () => {
  const e = engine(); arm(e); const original = e.getState().plan; cmd(e, 'advance_time', { minutes: 5 });
  assert.throws(() => cmd(e, 'update_arrival', { etaMinutes: 30, leadMinutes: 10, consent: false }), code('CONSENT_REQUIRED'));
  cmd(e, 'update_arrival', { etaMinutes: 30, leadMinutes: 10, consent: true }); assert.equal(e.getState().plan.id, original.id); assert.equal(e.getState().plan.version, 2);
  cmd(e, 'advance_time', { minutes: 5 }); assert.equal(e.getState().plan.status, 'armed'); cmd(e, 'advance_time', { minutes: 15 }); assert.equal(e.getState().plan.status, 'requested');
});
test('an already requested plan cannot be rearmed by editing a failed or manually overridden state', () => {
  for (const route of ['offline', 'manual']) {
    const e = engine(); arm(e); cmd(e, 'advance_time', { minutes: 10 });
    if (route === 'offline') { cmd(e, 'set_target_online', { online: false }); cmd(e, 'confirm_home'); }
    else cmd(e, 'set_home_manual', { tempC: 28 });
    assert.throws(() => cmd(e, 'update_arrival', { etaMinutes: 20, leadMinutes: 10, consent: true }), code('PLAN_NOT_EDITABLE'));
  }
});
test('idempotency and revision conflicts prevent duplicate or stale multi-screen writes', () => {
  const e = engine(); const c = { action: 'set_feeling', payload: { value: 'warm' }, expectedRevision: 0, requestId: 'request-shared-001' };
  const once = e.execute(c); const twice = e.execute(c); assert.equal(twice.revision, once.revision);
  assert.throws(() => e.execute({ ...c, payload: { value: 'cold' } }), code('REQUEST_ID_REUSED'));
  assert.throws(() => e.execute({ ...c, requestId: 'request-another-001' }), code('REVISION_CONFLICT'));
  const state = e.getState(); state.presets[0].name = 'corruption'; assert.notEqual(e.getState().presets[0].name, 'corruption');
});
test('independent HTTP clients share state while foreign origins, methods and raw source paths fail', async () => {
  const server = createContinuityServer({ engine: engine() }); await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const port = server.address().port, url = 'http://127.0.0.1:' + port;
  try {
    const a = await (await fetch(url + '/api/state')).json();
    const changed = await fetch(url + '/api/commands', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ action: 'set_home_manual', payload: { tempC: 26 }, expectedRevision: a.revision, requestId: 'http-request-0001' }) }); assert.equal(changed.status, 200);
    const b = await (await fetch(url + '/api/state')).json(); assert.equal(b.devices.home.tempC, 26); assert.equal(b.revision, a.revision + 1);
    const status = await new Promise((resolve, reject) => { const req = request({ hostname: '127.0.0.1', port, path: '/api/state', headers: { Origin: 'https://example.invalid' } }, res => { res.resume(); res.on('end', () => resolve(res.statusCode)); }); req.on('error', reject); req.end(); }); assert.equal(status, 403);
    assert.equal((await fetch(url + '/api/commands', { method: 'POST', body: '{}' })).status, 415);
    assert.equal((await fetch(url + '/server/continuity/engine.mjs')).status, 404);
    assert.equal((await fetch(url + '/api/pairings')).status, 404);
  } finally { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); }
});

test('local API requires the exact origin and JSON media type before changing state', async () => {
  const e = engine(); const server = createContinuityServer({ engine: e });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const port = server.address().port, url = 'http://127.0.0.1:' + port;
  const command = JSON.stringify({ action: 'set_feeling', payload: { value: 'warm' }, expectedRevision: 0, requestId: 'http-origin-0001' });
  try {
    for (const headers of [
      { Origin: 'http://localhost:' + port },
      { Origin: url.replace('http:', 'https:') },
      { Origin: 'http://localhost:' + port, 'X-Forwarded-Host': 'localhost:' + port },
      { Origin: url, 'Sec-Fetch-Site': 'cross-site' }
    ]) {
      const response = await fetch(url + '/api/commands', { method: 'POST', headers: { 'Content-Type': 'application/json', ...headers }, body: command });
      assert.equal(response.status, 403); assert.equal(e.getState().revision, 0);
    }
    const malformed = await fetch(url + '/api/commands', { method: 'POST', headers: { 'Content-Type': 'application/json-invalid' }, body: command });
    assert.equal(malformed.status, 415); assert.equal(e.getState().revision, 0);
    const accepted = await fetch(url + '/api/commands', { method: 'POST', headers: { 'Content-Type': 'application/json; charset=utf-8', Origin: url }, body: command });
    assert.equal(accepted.status, 200); assert.equal(e.getState().revision, 1);
  } finally { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); }
});
