import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import https from 'node:https';
import { randomUUID } from 'node:crypto';
import { createPresetSyncService } from '../server/preset-sync/service.mjs';
import { createTestTlsFixture } from './fixtures/ephemeral-tls.mjs';

const { key, cert } = createTestTlsFixture();
const tls = { key, cert };
const KEY = 'test-only-kakao-key-0123456789';

/** A fetch stand-in: records every call and answers with `reply(url, init)`. Nothing leaves the machine. */
function stubFetch(reply = () => ok({ documents: [] })) {
  const calls = [];
  const fetchImpl = async (url, init) => { calls.push({ url: String(url), init }); return reply(String(url), init); };
  return { fetchImpl, calls };
}
const ok = data => new Response(JSON.stringify(data), { status: 200, headers: { 'Content-Type': 'application/json' } });
const doc = (id, extra = {}) => ({ id: String(id), place_name: '장소 ' + id, road_address_name: '서울 중구 세종대로 ' + id, address_name: '서울 중구 태평로1가 ' + id, x: '126.9780', y: '37.5665', category_group_name: '카페', ...extra });

async function fixture(t, options = {}) {
  let time = Date.now();
  const stub = stubFetch(options.reply);
  const service = createPresetSyncService({ tls, now: () => time, kakaoRestKey: KEY, fetchImpl: stub.fetchImpl, ...options.service });
  await Promise.all([service.httpServer, service.httpsServer].map(s => new Promise(r => s.listen(0, '127.0.0.1', r))));
  t.after(() => service.close());
  async function call(path, { secure = false, method = 'GET', body, cookie, token, raw } = {}) {
    const server = secure ? service.httpsServer : service.httpServer;
    return new Promise((resolve, reject) => {
      const req = (secure ? https : http).request({ hostname: '127.0.0.1', port: server.address().port, path: '/api/preset-sync/v1' + path, method, ca: tls.cert, headers: { ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}), ...(cookie ? { Cookie: cookie } : {}), ...(token ? { Authorization: 'Bearer ' + token } : {}) } }, res => {
        const chunks = []; res.on('data', c => chunks.push(c)); res.on('end', () => { const text = Buffer.concat(chunks).toString('utf8'); resolve({ status: res.statusCode, headers: res.headers, text, body: JSON.parse(text) }); });
      }); req.on('error', reject); req.end(body !== undefined ? (raw ? body : JSON.stringify(body)) : undefined);
    });
  }
  const cookie = (await call('/session')).headers['set-cookie'][0].split(';')[0];
  return { service, call, cookie, stub, advance: ms => { time += ms; } };
}

async function paired(t, options) {
  const f = await fixture(t, options), cookie = f.cookie;
  async function pair(role) {
    const p = await f.call('/pairings', { method: 'POST', cookie, body: { role } });
    const c = await f.call('/pairings/claim', { method: 'POST', secure: true, body: { role, code: p.body.code } });
    assert.equal((await f.call('/pairings/approve', { method: 'POST', cookie, body: { pairingId: c.body.pairingId, comparisonCode: c.body.comparisonCode, confirmed: true } })).status, 200);
    return c.body.token;
  }
  const phone = await pair('phone'), vehicle = await pair('vehicle');
  const search = (body, token = phone) => f.call('/places/search', { method: 'POST', secure: true, token, body });
  const state = async () => (await f.call('/state', { cookie })).body;
  async function command(action, payload, token) {
    const s = await state();
    const body = { action, payload, expectedEpoch: s.sync.epoch, expectedRevision: s.state.revision, requestId: randomUUID() };
    return token ? f.call('/commands', { method: 'POST', secure: true, token, body }) : f.call('/commands', { method: 'POST', cookie, body });
  }
  return { ...f, cookie, phone, vehicle, search, state, command };
}

/** Captures console output during `run` so a test can prove nothing sensitive was logged. */
async function quietly(run) {
  const seen = [], saved = {};
  for (const name of ['log', 'info', 'warn', 'error', 'debug']) { saved[name] = console[name]; console[name] = (...args) => seen.push(args.map(String).join(' ')); }
  try { return { result: await run(), logged: seen.join('\n') }; } finally { Object.assign(console, saved); }
}

test('place search answers 503 PLACES_UNAVAILABLE until a Kakao key is configured', async t => {
  const f = await paired(t, { service: { kakaoRestKey: null } });
  const r = await f.search({ query: '공원', kind: 'park' });
  assert.equal(r.status, 503); assert.equal(r.body.error.code, 'PLACES_UNAVAILABLE'); assert.equal(r.body.error.message, '장소 검색이 아직 준비되지 않았어요.');
  assert.equal(f.stub.calls.length, 0);
});

test('only an approved phone over HTTPS may search places', async t => {
  const f = await paired(t);
  const vehicle = await f.search({ query: '공원', kind: 'park' }, f.vehicle);
  assert.equal(vehicle.status, 403); assert.equal(vehicle.body.error.code, 'ROLE_FORBIDDEN');
  const web = await f.call('/places/search', { method: 'POST', cookie: f.cookie, body: { query: '공원', kind: 'park' } });
  assert.equal(web.status, 403);
  assert.equal((await f.call('/places/search', { method: 'POST', secure: true, body: { query: '공원', kind: 'park' } })).status, 401);
  assert.equal(f.stub.calls.length, 0);
});

test('place search accepts exactly {query, kind} and no coordinates', async t => {
  const f = await paired(t);
  for (const body of [
    { query: '공원', kind: 'park', lat: 37.5, lon: 127 }, { query: '공원' }, { query: '   ', kind: 'park' }, { query: '', kind: 'park' },
    { query: '가'.repeat(41), kind: 'park' }, { query: '공원\n', kind: 'park' }, { query: '공원', kind: 'gym' }, { query: 3, kind: 'park' }, ['공원', 'park']
  ]) {
    const r = await f.search(body);
    assert.equal(r.status, 400, JSON.stringify(body)); assert.match(r.body.error.code, /^INVALID_(FIELDS|SEARCH)$/);
  }
  assert.equal(f.stub.calls.length, 0);
});

test('happy path maps, rounds, filters and caps Kakao results; sends KakaoAK and CE7 only for cafe', async t => {
  const docs = [
    doc(1, { place_name: '  서울숲  ', x: '127.03791234', y: '37.54444449', category_group_name: '' }),
    doc(2, { road_address_name: '', address_name: '서울 성동구 성수동1가 685' }),
    doc(3, { y: '40.1' }), doc(4, { x: 'abc' }), doc(5, { x: '140' }), { id: 6, place_name: '숫자 id', x: '127', y: '37' }, doc(7, { place_name: '   ' }),
    doc(8, { place_name: '아주 '.repeat(20), road_address_name: '길'.repeat(150) }),
    ...Array.from({ length: 20 }, (_, i) => doc(100 + i))
  ];
  const f = await paired(t, { reply: () => ok({ documents: docs, meta: { total_count: docs.length } }) });
  const r = await f.search({ query: ' 서울숲 & 카페 ', kind: 'park' });
  assert.equal(r.status, 200);
  assert.deepEqual(Object.keys(r.body).sort(), ['places', 'sync']); assert.equal(r.body.sync.role, 'phone');
  const places = r.body.places;
  assert.equal(places.length, 15, 'at most 15 places');
  assert.deepEqual(places[0], { id: 'kakao:1', name: '서울숲', address: '서울 중구 세종대로 1', lat: 37.5444, lon: 127.0379, category: '' });
  assert.equal(places[1].address, '서울 성동구 성수동1가 685', 'falls back to the land-lot address');
  assert.equal(places[1].category, '카페');
  assert.equal(places[2].id, 'kakao:8'); assert.equal(places[2].name.length, 40); assert.equal(places[2].address.length, 120);
  assert.ok(!places.some(p => ['kakao:3', 'kakao:4', 'kakao:5', 'kakao:6', 'kakao:7'].includes(p.id)), 'out of range, non-finite, odd ids and empty names dropped');
  for (const p of places) assert.deepEqual(Object.keys(p), ['id', 'name', 'address', 'lat', 'lon', 'category']);

  assert.equal(f.stub.calls.length, 1);
  const sent = new URL(f.stub.calls[0].url);
  assert.equal(sent.origin + sent.pathname, 'https://dapi.kakao.com/v2/local/search/keyword.json');
  assert.deepEqual([...sent.searchParams.keys()].sort(), ['query', 'size']);
  assert.equal(sent.searchParams.get('query'), '서울숲 & 카페'); assert.equal(sent.searchParams.get('size'), '15');
  assert.equal(f.stub.calls[0].init.headers.Authorization, 'KakaoAK ' + KEY);
  assert.ok(f.stub.calls[0].init.signal instanceof AbortSignal, 'a timeout signal is attached');

  assert.equal((await f.search({ query: '카페', kind: 'cafe' })).status, 200);
  const cafe = new URL(f.stub.calls[1].url);
  assert.equal(cafe.searchParams.get('category_group_code'), 'CE7');
  assert.deepEqual([...cafe.searchParams.keys()].sort(), ['category_group_code', 'query', 'size']);
  for (const kind of ['bookstore', 'sauna', 'other']) {
    await f.search({ query: '쉼', kind });
    assert.equal(new URL(f.stub.calls.at(-1).url).searchParams.has('category_group_code'), false, kind);
  }
});

for (const [name, reply] of [
  ['upstream 500', () => new Response(JSON.stringify({ errorType: 'AccessDeniedError', message: 'secret upstream detail' }), { status: 500 })],
  ['bad JSON', () => new Response('<html>not json</html>', { status: 200 })],
  ['missing documents', () => ok({ meta: {} })],
  ['network error', () => { throw new TypeError('fetch failed ' + KEY); }]
]) test(`place search maps ${name} to 502 PLACES_FAILED without leaking key, query or upstream body`, async t => {
  const f = await paired(t, { reply });
  const query = '비밀검색어' + name.length;
  const { result: r, logged } = await quietly(() => f.search({ query, kind: 'other' }));
  assert.equal(r.status, 502); assert.equal(r.body.error.code, 'PLACES_FAILED'); assert.equal(r.body.error.message, '장소를 찾지 못했어요. 잠시 뒤 다시 해 주세요.');
  for (const secret of [KEY, query, 'secret upstream detail', 'not json']) { assert.ok(!r.text.includes(secret)); assert.ok(!logged.includes(secret)); }
});

test('a Kakao call that never answers times out after 5 s with 502', async t => {
  const f = await paired(t, { reply: (url, init) => new Promise((_, reject) => init.signal.addEventListener('abort', () => reject(init.signal.reason))) });
  const started = Date.now();
  const r = await f.search({ query: '느린검색', kind: 'park' });
  const took = Date.now() - started;
  assert.equal(r.status, 502); assert.equal(r.body.error.code, 'PLACES_FAILED'); assert.ok(!r.text.includes(KEY) && !r.text.includes('느린검색'));
  assert.ok(took >= 4500 && took < 9000, 'timed out near 5 s, took ' + took);
});

test('place search allows 30 per session per rolling minute', async t => {
  const f = await paired(t);
  for (let i = 0; i < 30; i++) assert.equal((await f.search({ query: '공원' + i, kind: 'park' })).status, 200);
  const limited = await f.search({ query: '공원', kind: 'park' });
  assert.equal(limited.status, 429); assert.equal(limited.body.error.code, 'SEARCH_LIMIT');
  assert.equal(f.stub.calls.length, 30, 'the limited search never reached Kakao');
  f.advance(60001);
  assert.equal((await f.search({ query: '공원', kind: 'park' })).status, 200);
});

const place = (extra = {}) => ({ place: { name: '서울숲', address: '서울 성동구 뚝섬로 273', lat: 37.544444, lon: 127.037912, ...extra } });

test('the phone proposes one 쉬어 갈 곳 and the car reads it from shared state', async t => {
  const f = await paired(t);
  assert.equal((await f.state()).state.devices.vehicle.place, null, 'no place at start');
  const sent = await f.command('propose_place', place({ name: '  서울숲  ' }), f.phone);
  assert.equal(sent.status, 200);
  const p = sent.body.state.devices.vehicle.place;
  assert.match(p.id, /^place-[0-9a-f-]{36}$/);
  assert.deepEqual({ ...p, id: undefined }, { id: undefined, name: '서울숲', address: '서울 성동구 뚝섬로 273', lat: 37.5444, lon: 127.0379 });
  assert.deepEqual(Object.keys(p), ['id', 'name', 'address', 'lat', 'lon'], 'no visit counts, favourites or history');
  assert.equal(sent.body.state.events[0].label, '폰에서 쉬어 갈 곳을 차량에 보냈어요. 길안내는 차량 화면에서 직접 시작해요.');
  assert.equal(sent.body.state.events[0].origin, 'phone');
  const seen = (await f.call('/state', { secure: true, token: f.vehicle })).body.state.devices.vehicle.place;
  assert.deepEqual(seen, p);
  const second = await f.command('propose_place', place({ name: '북카페', address: '', lat: null, lon: null }), f.phone);
  assert.equal(second.status, 200);
  const q = second.body.state.devices.vehicle.place;
  assert.notEqual(q.id, p.id); assert.equal(q.lat, null); assert.equal(q.lon, null); assert.equal(q.address, '');
});

test('only the phone may propose a place', async t => {
  const f = await paired(t);
  assert.equal((await f.command('propose_place', place(), f.vehicle)).status, 403, 'car refused');
  assert.equal((await f.command('propose_place', place())).status, 403, 'local web page refused');
  assert.equal((await f.state()).state.devices.vehicle.place, null);
});

test('invalid place fields are rejected and leave state unchanged', async t => {
  const f = await paired(t);
  const before = (await f.state()).state.revision;
  for (const payload of [
    place({ name: '가'.repeat(41) }), place({ name: '   ' }), place({ name: 7 }), place({ name: '줄\n바꿈' }), place({ address: '길'.repeat(121) }), place({ address: null }),
    place({ lat: 37.5, lon: null }), place({ lat: null, lon: 127 }), place({ lat: 40, lon: 127 }), place({ lat: 37.5, lon: 123.9 }), place({ lat: '37.5', lon: '127' }),
    place({ lat: Number.NaN, lon: 127 }), place({ visits: 3 }), { place: { name: '서울숲', address: '' } }, { ...place(), favourite: true }, { place: null }
  ]) {
    const r = await f.command('propose_place', payload, f.phone);
    assert.equal(r.status, 400, JSON.stringify(payload)); assert.match(r.body.error.code, /^INVALID_/);
  }
  const s = (await f.state()).state;
  assert.equal(s.revision, before); assert.equal(s.devices.vehicle.place, null);
});

test('place coordinates are rounded to 4 decimals at the edges of the Korean range', async t => {
  const f = await paired(t);
  const r = await f.command('propose_place', place({ lat: 33.00004, lon: 131.99996 }), f.phone);
  assert.equal(r.status, 200);
  assert.equal(r.body.state.devices.vehicle.place.lat, 33); assert.equal(r.body.state.devices.vehicle.place.lon, 132);
  const s = await f.command('propose_place', place({ lat: 37.123456, lon: 126.987654 }), f.phone);
  assert.equal(s.body.state.devices.vehicle.place.lat, 37.1235); assert.equal(s.body.state.devices.vehicle.place.lon, 126.9877);
});

test('the car clears the place it saw; a stale id answers 409 PLACE_CHANGED', async t => {
  const f = await paired(t);
  const first = (await f.command('propose_place', place(), f.phone)).body.state.devices.vehicle.place;
  const second = (await f.command('propose_place', place({ name: '북카페' }), f.phone)).body.state.devices.vehicle.place;
  assert.equal((await f.command('clear_place', { id: second.id }, f.phone)).status, 403, 'the phone cannot clear');
  assert.equal((await f.command('clear_place', { id: 'place-x' }, f.vehicle)).status, 400, 'malformed id is a bad request');
  assert.deepEqual((await f.state()).state.devices.vehicle.place, { sent: true }, 'the web page does not see the place name or position');
  const webStale = await f.call('/commands', { method: 'POST', cookie: f.cookie, body: { action: 'select_preset', payload: { id: 'quiet_rest' }, expectedEpoch: (await f.state()).sync.epoch, expectedRevision: 0, requestId: randomUUID() } });
  assert.equal(webStale.status, 409);
  assert.deepEqual(webStale.body.state.devices.vehicle.place, { sent: true }, 'a 409 to the web page is masked too');
  const stale = await f.command('clear_place', { id: first.id }, f.vehicle);
  assert.equal(stale.status, 409); assert.equal(stale.body.error.code, 'PLACE_CHANGED');
  assert.deepEqual(stale.body.state.devices.vehicle.place, second, 'conflict carries the latest state');
  assert.equal((await f.command('clear_place', { id: second.id, extra: 1 }, f.vehicle)).status, 400);
  const cleared = await f.command('clear_place', { id: second.id }, f.vehicle);
  assert.equal(cleared.status, 200);
  assert.equal(cleared.body.state.devices.vehicle.place, null);
  assert.equal(cleared.body.state.events[0].label, '차량에서 쉬어 갈 곳을 확인했어요.'); assert.equal(cleared.body.state.events[0].origin, 'vehicle');
  assert.equal((await f.command('clear_place', { id: second.id }, f.vehicle)).status, 409, 'nothing left to clear');
});
