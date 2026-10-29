import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import https from 'node:https';
import { createApp } from '../server/app.mjs';
import { createHttpsRelay } from '../server/https.mjs';
import { createTestTlsFixture } from './fixtures/ephemeral-tls.mjs';

const { certPath: testCertPath, keyPath: testKeyPath, ...testTls } = createTestTlsFixture();

function secureRequest(base, path, { method = 'GET', body, token, headers = {}, ...options } = {}) {
  return new Promise((resolve, reject) => {
    const request = https.request(base + path, {
      ca: testTls.cert, agent: false, ...options, method,
      headers: { ...(body === undefined ? {} : { 'Content-Type': 'application/json' }), ...(token ? { Authorization: `Bearer ${token}` } : {}), ...headers }
    }, response => {
      let text = ''; response.on('data', chunk => { text += chunk; });
      response.on('end', () => resolve({ status: response.statusCode, body: JSON.parse(text), headers: new Headers(response.headers), protocol: response.socket.getProtocol() }));
    });
    request.on('error', reject);
    request.end(body === undefined ? undefined : JSON.stringify(body));
  });
}

async function fixture(t, options = {}) {
  let time = Date.parse('2026-10-24T04:00:00.000Z');
  const app = createApp({ ...options, now: () => time });
  await new Promise(resolve => app.server.listen(0, '127.0.0.1', resolve));
  t.after(() => app.close());
  const base = `${options.tls ? 'https' : 'http'}://127.0.0.1:${app.server.address().port}`;
  const call = async (path, method = 'GET', body, token, headers = {}) => {
    if (options.tls) return secureRequest(base, path, { method, body, token, headers });
    const response = await fetch(base + path, {
      method, headers: { ...(body === undefined ? {} : { 'Content-Type': 'application/json' }), ...(token ? { Authorization: `Bearer ${token}` } : {}), ...headers },
      body: body === undefined ? undefined : JSON.stringify(body)
    });
    return { status: response.status, headers: response.headers, body: await response.json() };
  };
  const summary = (patch = {}) => ({ schemaVersion: 1, source: 'demo', workout: { type: 'walking', durationMinutes: 42, endedAt: new Date(time - 120000).toISOString() }, comfort: 'quiet_rest', consent: { shareWorkoutSummary: true }, ...patch });
  // Keep the request target untouched: fetch would normalize URL authorities before sending.
  const rawCall = (path, headers = {}) => new Promise((resolve, reject) => {
    const request = http.request(base, { path, headers, agent: false }, response => {
      let text = ''; response.on('data', chunk => { text += chunk; });
      response.on('end', () => resolve({ status: response.statusCode, body: JSON.parse(text) }));
    });
    request.on('error', reject); request.end();
  });
  const paired = async () => {
    const made = await call('/api/pairings', 'POST', {});
    assert.equal(made.status, 201);
    const car = made.body;
    const claimed = await call('/api/pairings/claim', 'POST', { code: car.code });
    assert.equal(claimed.status, 200);
    const phone = claimed.body;
    assert.match(phone.confirmation, /^\d{4}$/);
    const approved = await call(`/api/pairings/${car.pairingId}/confirm`, 'POST', { confirmation: phone.confirmation }, car.vehicleToken);
    assert.equal(approved.status, 200);
    return { ...car, ...phone };
  };
  const slow = async (path, token) => {
    let request;
    const arrived = new Promise(resolve => app.server.once('request', resolve));
    const result = new Promise((resolve, reject) => {
      request = http.request(base + path, { method: 'POST', headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) } }, response => {
        let text = ''; response.on('data', chunk => { text += chunk; });
        response.on('end', () => resolve({ status: response.statusCode, body: JSON.parse(text) }));
      });
      request.on('error', reject); request.write('{');
    });
    await arrived;
    return { finish: body => { request.end(JSON.stringify(body).slice(1)); return result; } };
  };
  return { call, rawCall, summary, paired, slow, advance: ms => { time += ms; }, base, server: app.server };
}

test('explicit pair confirmation allows a minimal summary across independent HTTP clients', async t => {
  const { call, summary, paired } = await fixture(t);
  const p = await paired();
  const sent = await call(`/api/pairings/${p.pairingId}/summary`, 'POST', summary(), p.phoneToken);
  assert.equal(sent.status, 201);
  const received = await call(`/api/pairings/${p.pairingId}/summary`, 'GET', undefined, p.vehicleToken);
  assert.equal(received.status, 200);
  assert.equal(received.body.summary.workout.durationMinutes, 42);
  assert.equal(received.body.summary.source, 'demo');
  assert.equal(received.body.reason, 'available');
  assert.equal(received.headers.get('cache-control'), 'no-store');
});

test('health cannot be sent until car explicitly confirms matching code', async t => {
  const { call, summary } = await fixture(t);
  const car = (await call('/api/pairings', 'POST', {})).body;
  const phone = (await call('/api/pairings/claim', 'POST', { code: car.code })).body;
  assert.equal((await call(`/api/pairings/${car.pairingId}/summary`, 'POST', summary(), phone.phoneToken)).status, 409);
  assert.equal((await call(`/api/pairings/${car.pairingId}/confirm`, 'POST', { confirmation: 'xxxx' }, car.vehicleToken)).status, 400);
  const state = (await call(`/api/pairings/${car.pairingId}`, 'GET', undefined, car.vehicleToken)).body;
  assert.equal(state.status, 'claimed');
  assert.equal(state.confirmation, phone.confirmation);
});

test('role tokens cannot read, write or confirm on behalf of the other endpoint', async t => {
  const { call, summary, paired } = await fixture(t);
  const p = await paired();
  assert.equal((await call(`/api/pairings/${p.pairingId}/summary`, 'GET', undefined, p.phoneToken)).status, 403);
  assert.equal((await call(`/api/pairings/${p.pairingId}/summary`, 'POST', summary(), p.vehicleToken)).status, 403);
  assert.equal((await call(`/api/pairings/${p.pairingId}/confirm`, 'POST', { confirmation: p.confirmation }, p.phoneToken)).status, 403);
  assert.equal((await call(`/api/pairings/${p.pairingId}`, 'GET')).status, 401);
});

test('one pair token cannot access another pair and claim code is single-use', async t => {
  const { call, summary, paired } = await fixture(t);
  const a = await paired(); const b = await paired();
  await call(`/api/pairings/${a.pairingId}/summary`, 'POST', summary(), a.phoneToken);
  assert.equal((await call(`/api/pairings/${a.pairingId}/summary`, 'GET', undefined, b.vehicleToken)).status, 401);
  assert.equal((await call('/api/pairings/claim', 'POST', { code: a.code })).status, 404);
});

test('summary sharing requires consent and rejects unrequested health data', async t => {
  const { call, summary, paired } = await fixture(t); const p = await paired();
  const endpoint = `/api/pairings/${p.pairingId}/summary`;
  for (const body of [summary({ consent: { shareWorkoutSummary: false } }), summary({ heartRateBpm: 130 }), summary({ workout: { ...summary().workout, gps: [1, 2] } }), summary({ comfort: 'diagnose_dehydration' })]) {
    assert.equal((await call(endpoint, 'POST', body, p.phoneToken)).status, 400);
  }
  assert.equal((await call(endpoint, 'GET', undefined, p.vehicleToken)).body.reason, 'empty');
});

test('old, future, invalid-duration and fabricated timestamp records are rejected', async t => {
  const { call, summary, paired } = await fixture(t); const p = await paired();
  const normal = summary();
  for (const workout of [
    { ...normal.workout, endedAt: '2026-10-24T02:00:00.000Z' },
    { ...normal.workout, endedAt: '2026-10-24T04:30:00.000Z' },
    { ...normal.workout, endedAt: 'Saturday' },
    { ...normal.workout, endedAt: '2026-02-30T03:59:00Z' },
    { ...normal.workout, durationMinutes: -1 },
    { ...normal.workout, durationMinutes: 300 },
    { ...normal.workout, durationMinutes: '42' }
  ]) assert.equal((await call(`/api/pairings/${p.pairingId}/summary`, 'POST', summary({ workout }), p.phoneToken)).status, 400);
  const fortyFiveMinOld = summary({ workout: { ...normal.workout, endedAt: '2026-10-24T03:15:00.000Z' } });
  assert.equal((await call(`/api/pairings/${p.pairingId}/summary`, 'POST', fortyFiveMinOld, p.phoneToken)).status, 201);
});

test('summary expires 30m after sharing (capped by 120m workout window) and repeat upload cannot extend retention', async t => {
  const { call, summary, paired, advance } = await fixture(t); const p = await paired();
  const body = summary();
  await call(`/api/pairings/${p.pairingId}/summary`, 'POST', body, p.phoneToken);
  advance(30 * 60 * 1000);
  const expired = await call(`/api/pairings/${p.pairingId}/summary`, 'GET', undefined, p.vehicleToken);
  assert.equal(expired.body.summary, null); assert.equal(expired.body.reason, 'expired');
  assert.equal((await call(`/api/pairings/${p.pairingId}/summary`, 'POST', body, p.phoneToken)).status, 400);
});

test('revocation deletes summary and invalidates both role tokens', async t => {
  const { call, summary, paired } = await fixture(t); const p = await paired();
  await call(`/api/pairings/${p.pairingId}/summary`, 'POST', summary(), p.phoneToken);
  assert.equal((await call(`/api/pairings/${p.pairingId}`, 'DELETE', undefined, p.phoneToken)).body.deleted, true);
  assert.equal((await call(`/api/pairings/${p.pairingId}/summary`, 'GET', undefined, p.vehicleToken)).status, 404);
  assert.equal((await call(`/api/pairings/${p.pairingId}/summary`, 'POST', summary(), p.phoneToken)).status, 404);
});

test('a 119-minute-old workout has only one minute of retention despite a new receipt', async t => {
  const {call,summary,paired,advance}=await fixture(t);const p=await paired();
  const endpoint=`/api/pairings/${p.pairingId}/summary`;
  const body=summary();body.workout.endedAt='2026-10-24T02:01:00.000Z';
  const sent=await call(endpoint,'POST',body,p.phoneToken);
  assert.equal(sent.status,201);
  assert.equal(Date.parse(sent.body.expiresAt)-Date.parse(sent.body.receivedAt),60000);
  advance(60000);
  assert.equal((await call(endpoint,'GET',undefined,p.vehicleToken)).body.summary,null);
  assert.equal((await call(endpoint,'POST',body,p.phoneToken)).status,400);
});

test('unclaimed pair and active pair have independent hard expiry', async t => {
  const { call, paired, advance } = await fixture(t);
  const pending = (await call('/api/pairings', 'POST', {})).body;
  advance(5 * 60 * 1000 + 1);
  assert.equal((await call('/api/pairings/claim', 'POST', { code: pending.code })).status, 404);
  const active = await paired();
  advance(2 * 60 * 60 * 1000 + 1);
  assert.equal((await call(`/api/pairings/${active.pairingId}`, 'GET', undefined, active.vehicleToken)).status, 404);
});

test('browser cross-origin requests cannot claim pairings or create state', async t => {
  const { call } = await fixture(t);
  assert.equal((await call('/api/pairings', 'POST', {}, undefined, { Origin: 'https://evil.example' })).status, 403);
  const healthy = await call('/api/health');
  assert.equal(healthy.status, 200);
  assert.equal(healthy.body.mode, 'local-prototype');
  assert.equal(healthy.headers.get('access-control-allow-origin'), null);
});

test('relay refuses proxy-style request targets even when their authority is allowlisted', async t => {
  const { rawCall } = await fixture(t);
  for (const path of ['http://localhost/api/health', '//localhost/api/health', '/\\localhost/api/health']) {
    await t.test(JSON.stringify(path), async () => {
      const answer = await rawCall(path, { Host: 'evil.example' });
      assert.equal(answer.status, 400);
      assert.equal(answer.body.error.code, 'INVALID_URL');
    });
  }
});

test('Host authority cannot hide credentials, paths, queries or fragments behind an allowlisted hostname', async t => {
  const { rawCall } = await fixture(t);
  for (const host of ['user@localhost', 'localhost/path', 'localhost?ignored', 'localhost#ignored', 'localhost\\ignored']) {
    await t.test(host, async () => {
      const answer = await rawCall('/api/health', { Host: host });
      assert.equal(answer.status, 400);
      assert.equal(answer.body.error.code, 'INVALID_HOST');
    });
  }
});

test('origin-form requests keep local and explicitly allowlisted Host and Origin behavior', async t => {
  const { rawCall } = await fixture(t, { allowedHosts: ['relay.example'] });
  for (const host of ['127.0.0.1:8787', 'localhost:8787', '10.0.2.2:8787', '[::1]:8787', 'relay.example:443']) {
    for (const scheme of ['http', 'https']) {
      const answer = await rawCall('/api/health', { Host: host, Origin: `${scheme}://${host}` });
      assert.equal(answer.status, 200, `${scheme}://${host}`);
      assert.equal(answer.body.service, 'pit-in-relay');
    }
  }
  const untrusted = await rawCall('/api/health', { Host: 'evil.example' });
  assert.equal(untrusted.status, 403);
  assert.equal(untrusted.body.error.code, 'HOST_NOT_ALLOWED');
  const mismatched = await rawCall('/api/health', { Host: 'relay.example', Origin: 'https://evil.example' });
  assert.equal(mismatched.status, 403);
  assert.equal(mismatched.body.error.code, 'ORIGIN_NOT_ALLOWED');
});

test('claim guessing is bounded and response contains no pair inventory', async t => {
  const { call } = await fixture(t);
  let last;
  for (let i = 0; i < 16; i++) last = await call('/api/pairings/claim', 'POST', { code: '000000' });
  assert.equal(last.status, 429);
  assert.equal(Object.hasOwn(last.body, 'pairings'), false);
});

test('slow claim completing after code expiry is refused', async t => {
  const { call, slow, advance } = await fixture(t);
  const car = (await call('/api/pairings', 'POST', {})).body;
  const pending = await slow('/api/pairings/claim');
  advance(5 * 60_000 + 1);
  assert.equal((await pending.finish({ code: car.code })).status, 404);
});

test('slow confirm cannot revive an expired pairing', async t => {
  const { call, slow, advance } = await fixture(t);
  const car = (await call('/api/pairings', 'POST', {})).body;
  const phone = (await call('/api/pairings/claim', 'POST', { code: car.code })).body;
  const pending = await slow(`/api/pairings/${car.pairingId}/confirm`, car.vehicleToken);
  advance(5 * 60_000 + 1);
  assert.equal((await pending.finish({ confirmation: phone.confirmation })).status, 404);
});

test('slow summary finishing after revocation cannot report accepted', async t => {
  const { call, summary, paired, slow } = await fixture(t); const p = await paired();
  const pending = await slow(`/api/pairings/${p.pairingId}/summary`, p.phoneToken);
  assert.equal((await call(`/api/pairings/${p.pairingId}`, 'DELETE', undefined, p.vehicleToken)).status, 200);
  assert.equal((await pending.finish(summary())).status, 404);
});

test('replacement keeps only the newest summary and invalid uploads preserve it', async t => {
  const { call, summary, paired } = await fixture(t); const p = await paired();
  const endpoint = `/api/pairings/${p.pairingId}/summary`;
  const first = await call(endpoint, 'POST', summary(), p.phoneToken);
  const replacement = summary({ comfort: 'cool_down', workout: { ...summary().workout, type: 'cycling', durationMinutes: 19 } });
  const second = await call(endpoint, 'POST', replacement, p.phoneToken);
  assert.equal(second.status, 201);
  assert.notEqual(second.body.summaryId, first.body.summaryId);
  assert.equal((await call(endpoint, 'POST', summary({ heartRateBpm: 130 }), p.phoneToken)).status, 400);
  const received = await call(endpoint, 'GET', undefined, p.vehicleToken);
  assert.deepEqual(received.body.summary, replacement);
  assert.equal(received.body.summaryId, second.body.summaryId);
  assert.deepEqual(Object.keys(received.body).sort(), ['expiresAt', 'reason', 'receivedAt', 'summary', 'summaryId']);
});

test('summary is available one millisecond before TTL and absent exactly at TTL', async t => {
  const { call, summary, paired, advance } = await fixture(t); const p = await paired();
  const endpoint = `/api/pairings/${p.pairingId}/summary`;
  const body = summary();
  const sent = await call(endpoint, 'POST', body, p.phoneToken);
  assert.equal(Date.parse(sent.body.expiresAt), Date.parse(sent.body.receivedAt) + 30 * 60_000);
  advance(30 * 60_000 - 1);
  assert.equal((await call(endpoint, 'GET', undefined, p.vehicleToken)).body.reason, 'available');
  advance(1);
  const expired = await call(endpoint, 'GET', undefined, p.vehicleToken);
  assert.equal(expired.status, 200);
  assert.equal(expired.body.reason, 'expired');
  assert.equal(expired.body.summary, null);
  assert.equal(expired.body.summaryId, null);
  assert.equal(expired.body.receivedAt, null);
  const replay = await call(endpoint, 'POST', body, p.phoneToken);
  assert.equal(replay.status, 400);
  assert.equal(replay.body.error.code, 'STALE_WORKOUT');
});

test('pairing and approved session expire at their exact deadlines', async t => {
  const { call, paired, advance } = await fixture(t);
  const waiting = (await call('/api/pairings', 'POST', {})).body;
  advance(5 * 60_000 - 1);
  assert.equal((await call(`/api/pairings/${waiting.pairingId}`, 'GET', undefined, waiting.vehicleToken)).status, 200);
  advance(1);
  assert.equal((await call('/api/pairings/claim', 'POST', { code: waiting.code })).status, 404);
  const p = await paired();
  advance(2 * 60 * 60_000 - 1);
  assert.equal((await call(`/api/pairings/${p.pairingId}`, 'GET', undefined, p.phoneToken)).status, 200);
  advance(1);
  for (const token of [p.phoneToken, p.vehicleToken]) {
    assert.equal((await call(`/api/pairings/${p.pairingId}`, 'GET', undefined, token)).status, 404);
  }
});

test('slow summary cannot cross the workout or session expiry boundary', async t => {
  const { call, summary, paired, slow, advance } = await fixture(t); const p = await paired();
  const endpoint = `/api/pairings/${p.pairingId}/summary`;
  const body = summary();
  const pendingWorkout = await slow(endpoint, p.phoneToken);
  advance(118 * 60_000);
  const stale = await pendingWorkout.finish(body);
  assert.equal(stale.status, 400);
  assert.equal(stale.body.error.code, 'STALE_WORKOUT');
  assert.equal((await call(endpoint, 'GET', undefined, p.vehicleToken)).body.summary, null);
  const pendingSession = await slow(endpoint, p.phoneToken);
  advance(2 * 60_000 + 1);
  assert.equal((await pendingSession.finish(summary())).status, 404);
});

test('slow confirmation cannot revive a pairing deleted by the phone', async t => {
  const { call, slow } = await fixture(t);
  const car = (await call('/api/pairings', 'POST', {})).body;
  const phone = (await call('/api/pairings/claim', 'POST', { code: car.code })).body;
  const pending = await slow(`/api/pairings/${car.pairingId}/confirm`, car.vehicleToken);
  assert.equal((await call(`/api/pairings/${car.pairingId}`, 'DELETE', undefined, phone.phoneToken)).status, 200);
  assert.equal((await pending.finish({ confirmation: phone.confirmation })).status, 404);
  assert.equal((await call(`/api/pairings/${car.pairingId}`, 'GET', undefined, car.vehicleToken)).status, 404);
});

test('aborted upload leaves the previous summary intact and relay usable', async t => {
  const { call, summary, paired, base, server } = await fixture(t); const p = await paired();
  const endpoint = `/api/pairings/${p.pairingId}/summary`;
  const original = summary();
  const saved = await call(endpoint, 'POST', original, p.phoneToken);
  const arrived = new Promise(resolve => server.once('request', resolve));
  let request;
  const closed = new Promise((resolve, reject) => {
    request = http.request(base + endpoint, {
      method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${p.phoneToken}` }
    });
    request.on('error', error => { if (error.code !== 'ECONNRESET') reject(error); });
    request.once('close', resolve);
    request.write('{"schemaVersion":1,');
  });
  const serverRequest = await arrived;
  const aborted = new Promise(resolve => serverRequest.once('aborted', resolve));
  request.destroy();
  await Promise.all([closed, aborted]);
  const received = await call(endpoint, 'GET', undefined, p.vehicleToken);
  assert.equal(received.status, 200);
  assert.deepEqual(received.body.summary, original);
  assert.equal(received.body.summaryId, saved.body.summaryId);
  assert.equal((await call('/api/health')).status, 200);
});

test('plain HTTP accepts demo only and forwarded TLS claims cannot change transport or store health summaries', async t => {
  const { call, summary, paired, base } = await fixture(t);
  const p = await paired();
  const endpoint = `/api/pairings/${p.pairingId}/summary`;
  const headers = { 'X-Forwarded-Proto': 'https', Forwarded: 'proto=https;host=localhost', Origin: base.replace('http:', 'https:') };
  assert.equal((await call('/api/health', 'GET', undefined, undefined, headers)).body.transport, 'plain');
  for (const source of ['health_connect', 'healthkit', 'manual']) {
    const blocked = await call(endpoint, 'POST', summary({ source }), p.phoneToken, headers);
    assert.equal(blocked.status, 403);
    assert.equal(blocked.body.error.code, 'TLS_REQUIRED');
    assert.equal((await call(endpoint, 'GET', undefined, p.vehicleToken)).body.summary, null);
  }
  const saved = await call(endpoint, 'POST', summary(), p.phoneToken);
  assert.equal(saved.status, 201);
  assert.equal((await call(endpoint, 'POST', summary({ source: 'health_connect' }), p.phoneToken, headers)).status, 403);
  const received = await call(endpoint, 'GET', undefined, p.vehicleToken);
  assert.equal(received.body.summaryId, saved.body.summaryId);
  assert.equal(received.body.summary.source, 'demo');
});

test('verified TLS preserves explicit confirmation, role authorization, consent, TTL and revocation', async t => {
  const { call, summary, advance } = await fixture(t, { tls: testTls });
  const healthy = await call('/api/health');
  assert.deepEqual(healthy.body, { service: 'pit-in-relay', mode: 'local-prototype', version: 1, storage: 'ephemeral-memory', transport: 'tls' });
  assert.ok(['TLSv1.2', 'TLSv1.3'].includes(healthy.protocol));
  const car = (await call('/api/pairings', 'POST', {})).body;
  const phone = (await call('/api/pairings/claim', 'POST', { code: car.code })).body;
  const endpoint = `/api/pairings/${car.pairingId}/summary`;
  const body = summary({ source: 'health_connect' });
  assert.equal((await call(endpoint, 'POST', body, phone.phoneToken)).status, 409);
  assert.equal((await call(`/api/pairings/${car.pairingId}/confirm`, 'POST', { confirmation: phone.confirmation }, car.vehicleToken)).status, 200);
  assert.equal((await call(endpoint, 'POST', body)).status, 401);
  assert.equal((await call(endpoint, 'POST', body, car.vehicleToken)).status, 403);
  assert.equal((await call(endpoint, 'POST', { ...body, consent: { shareWorkoutSummary: false } }, phone.phoneToken)).body.error.code, 'CONSENT_REQUIRED');
  assert.equal((await call(endpoint, 'POST', { ...body, heartRateBpm: 130 }, phone.phoneToken)).status, 400);
  const sent = await call(endpoint, 'POST', body, phone.phoneToken);
  assert.equal(sent.status, 201);
  assert.equal((await call(endpoint, 'GET', undefined, phone.phoneToken)).status, 403);
  assert.deepEqual((await call(endpoint, 'GET', undefined, car.vehicleToken)).body.summary, body);
  advance(30 * 60_000);
  const expired = await call(endpoint, 'GET', undefined, car.vehicleToken);
  assert.equal(expired.body.summary, null);
  assert.equal(expired.body.reason, 'expired');
  assert.equal((await call(endpoint, 'POST', body, phone.phoneToken)).body.error.code, 'STALE_WORKOUT');
  for (const source of ['healthkit', 'manual', 'demo']) {
    assert.equal((await call(endpoint, 'POST', summary({ source }), phone.phoneToken)).status, 201);
  }
  assert.equal((await call(`/api/pairings/${car.pairingId}`, 'DELETE', undefined, phone.phoneToken)).body.deleted, true);
  assert.equal((await call(endpoint, 'GET', undefined, car.vehicleToken)).status, 404);
  assert.equal((await call(endpoint, 'POST', summary(), phone.phoneToken)).status, 404);
});

test('HTTPS requires same origin and ignores forwarded scheme and host headers', async t => {
  const { call, base } = await fixture(t, { tls: testTls });
  assert.equal((await call('/api/health', 'GET', undefined, undefined, { Origin: base })).status, 200);
  for (const origin of [base.replace('https:', 'http:'), 'https://evil.example', 'https://127.0.0.1:1']) {
    const response = await call('/api/pairings', 'POST', {}, undefined, { Origin: origin, 'X-Forwarded-Proto': 'http', 'X-Forwarded-Host': 'evil.example' });
    assert.equal(response.status, 403);
    assert.equal(response.body.error.code, 'ORIGIN_NOT_ALLOWED');
  }
  assert.equal((await secureRequest(base, '/api/health', { servername: 'localhost', headers: { Host: 'evil.example', 'X-Forwarded-Host': 'localhost' } })).body.error.code, 'HOST_NOT_ALLOWED');
  assert.equal((await call('/api/health', 'GET', undefined, undefined, { 'X-Forwarded-Proto': 'http' })).body.transport, 'tls');
});

test('TLS clients reject an untrusted certificate and wrong hostname before HTTP reaches the relay', async t => {
  const { base, server } = await fixture(t, { tls: testTls });
  let requests = 0;
  server.on('request', () => { requests++; });
  await assert.rejects(secureRequest(base, '/api/health', { ca: [] }), { code: 'DEPTH_ZERO_SELF_SIGNED_CERT' });
  await assert.rejects(secureRequest(base, '/api/health', { servername: 'wrong-host.invalid' }), { code: 'ERR_TLS_CERT_ALTNAME_INVALID' });
  assert.equal(requests, 0);
  assert.equal((await secureRequest(base, '/api/health')).status, 200);
  assert.equal(requests, 1);
});

test('TLS listener accepts TLS 1.2 but refuses TLS 1.1', async t => {
  const { base } = await fixture(t, { tls: testTls });
  assert.equal((await secureRequest(base, '/api/health', { minVersion: 'TLSv1.2', maxVersion: 'TLSv1.2' })).protocol, 'TLSv1.2');
  await assert.rejects(secureRequest(base, '/api/health', { minVersion: 'TLSv1.1', maxVersion: 'TLSv1.1' }), error => /TLS|SSL/.test(error.code));
});

test('opt-in HTTPS entry requires explicit PEM paths, validates port and stays loopback despite BIND_HOST', async t => {
  for (const env of [{}, { PITIN_TLS_CERT: testCertPath }, { PITIN_TLS_CERT: 'relative.pem', PITIN_TLS_KEY: testKeyPath }]) {
    await assert.rejects(createHttpsRelay(env), /explicit absolute PEM file paths/);
  }
  const pem = { PITIN_TLS_CERT: testCertPath, PITIN_TLS_KEY: testKeyPath };
  for (const port of ['0', '65536', '1.5', 'not-a-port']) {
    await assert.rejects(createHttpsRelay({ ...pem, PITIN_TLS_PORT: port }), /PITIN_TLS_PORT must be/);
  }
  const app = await createHttpsRelay({ ...pem, BIND_HOST: '0.0.0.0', PORT: '8787' });
  t.after(() => app.close());
  assert.equal(app.host, '127.0.0.1');
  assert.equal(app.port, 9443);
  await new Promise(resolve => app.server.listen(0, app.host, resolve));
  assert.equal(app.server.address().address, '127.0.0.1');
  assert.equal((await secureRequest(`https://127.0.0.1:${app.server.address().port}`, '/api/health')).body.transport, 'tls');
});
