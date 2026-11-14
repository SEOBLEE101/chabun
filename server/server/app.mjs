import http from 'node:http';
import https from 'node:https';
import { randomBytes, randomInt, timingSafeEqual } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { join, extname } from 'node:path';
import { validateSummary, WORKOUT_MAX_AGE_MS } from './validation.mjs';

const PAIR_TTL = 5 * 60_000;
const SESSION_TTL = 2 * 60 * 60_000;
const SUMMARY_TTL = 30 * 60_000;
const webRoot = fileURLToPath(new URL('../web/', import.meta.url));
const token = () => randomBytes(32).toString('base64url');
const iso = value => new Date(value).toISOString();
const sameToken = (a, b) => typeof a === 'string' && typeof b === 'string' && Buffer.byteLength(a) === Buffer.byteLength(b) && timingSafeEqual(Buffer.from(a), Buffer.from(b));

class ApiError extends Error {
  constructor(status, code, message) { super(message); Object.assign(this, { status, code }); }
}
function fail(status, code, message) { throw new ApiError(status, code, message); }
function json(res, status, body) {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify(body));
}
async function readJson(req) {
  if (!/^application\/json(?:;|$)/i.test(req.headers['content-type'] ?? '')) fail(415, 'JSON_REQUIRED', 'JSON 요청이 필요합니다.');
  let size = 0; const parts = [];
  for await (const part of req) {
    size += part.length;
    if (size > 4096) fail(413, 'BODY_TOO_LARGE', '필요한 운동 요약만 보내 주세요.');
    parts.push(part);
  }
  try {
    const body = JSON.parse(Buffer.concat(parts).toString('utf8'));
    if (!body || typeof body !== 'object' || Array.isArray(body)) throw new Error();
    return body;
  } catch { fail(400, 'INVALID_JSON', 'JSON 형식을 확인해 주세요.'); }
}
function keysExactly(object, keys) {
  return Object.keys(object).length === keys.length && keys.every(key => Object.hasOwn(object, key));
}

export function createApp({ now = Date.now, allowedHosts = [], tls } = {}) {
  const pairings = new Map();
  const rates = new Map();
  const hosts = new Set(['localhost', '127.0.0.1', '[::1]', '::1', '10.0.2.2', ...allowedHosts]);
  function sweep() {
    const time = now();
    for (const [id, pair] of pairings) {
      if (pair.expires <= time) { pairings.delete(id); continue; }
      if (pair.summary && pair.summaryExpires <= time) {
        pair.summary = null; pair.summaryId = null; pair.receivedAt = null; pair.summaryExpired = true;
      }
    }
    for (const [key, value] of rates) if (value.until <= time) rates.delete(key);
  }
  const cleaner = setInterval(sweep, 10_000); cleaner.unref();
  function rate(req, scope, max) {
    const key = `${scope}:${req.socket.remoteAddress}`;
    let bucket = rates.get(key);
    if (!bucket || bucket.until <= now()) bucket = { count: 0, until: now() + 60_000 };
    bucket.count++; rates.set(key, bucket);
    if (bucket.count > max) fail(429, 'RATE_LIMITED', '요청이 많습니다. 잠시 뒤 다시 시도해 주세요.');
  }
  function authorize(req, pair, requiredRole) {
    const header = req.headers.authorization;
    const credential = header?.startsWith('Bearer ') ? header.slice(7) : '';
    let role = null;
    if (sameToken(credential, pair.vehicleToken)) role = 'vehicle';
    else if (pair.phoneToken && sameToken(credential, pair.phoneToken)) role = 'phone';
    if (!role) fail(401, 'UNAUTHORIZED', '연결 인증이 필요합니다.');
    if (requiredRole && role !== requiredRole) fail(403, 'WRONG_ROLE', '이 기기에서 허용되지 않는 요청입니다.');
    return role;
  }
  const describe = pair => ({ pairingId: pair.id, status: pair.status, confirmation: pair.confirmation, expiresAt: iso(pair.expires) });
  function requireCurrent(pair) {
    sweep();
    if (pairings.get(pair.id) !== pair) fail(404, 'PAIRING_NOT_FOUND', '연결이 만료되거나 해제됐습니다. 다시 연결해 주세요.');
  }

  const handleRequest = async (req, res) => {
    res.setHeader('Cache-Control', 'no-store');
    res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('Referrer-Policy', 'no-referrer');
    res.setHeader('Content-Security-Policy', "default-src 'self'; connect-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'");
    res.setHeader('Permissions-Policy', 'camera=(), microphone=(), geolocation=()');
    try {
      sweep();
      // This relay is an origin server, not a forward proxy. URL() otherwise lets
      // an absolute/network-path target replace the authority supplied by Host.
      if (!req.url?.startsWith('/') || req.url.startsWith('//') || /[\\#]/.test(req.url))
        fail(400, 'INVALID_URL', '주소 형식이 잘못됐습니다.');
      const host = req.headers.host;
      // Validate the raw authority before URL parsing can reinterpret userinfo,
      // paths or queries as a different, apparently allowlisted hostname.
      if (typeof host !== 'string' || !/^(?:\[[0-9a-fA-F:.]+\]|[A-Za-z0-9.-]+)(?::[0-9]+)?$/.test(host))
        fail(400, 'INVALID_HOST', '호스트 형식이 잘못됐습니다.');
      // Forwarded headers never establish transport security on this origin server.
      const encrypted = req.socket.encrypted === true;
      const origin = `${encrypted ? 'https' : 'http'}://${host}`;
      let authority;
      try { authority = new URL(origin); } catch { fail(400, 'INVALID_HOST', '호스트 형식이 잘못됐습니다.'); }
      if (!hosts.has(authority.hostname)) fail(403, 'HOST_NOT_ALLOWED', '허용된 로컬 호스트에서 실행해 주세요.');
      let url;
      try { url = new URL(req.url, origin); } catch { fail(400, 'INVALID_URL', '주소 형식이 잘못됐습니다.'); }
      const allowedOrigins = encrypted ? [origin] : [origin, origin.replace('http:', 'https:')];
      if (req.headers.origin && !allowedOrigins.includes(req.headers.origin)) fail(403, 'ORIGIN_NOT_ALLOWED', '같은 시제품 주소에서 연결해 주세요.');
      if (req.headers['sec-fetch-site'] === 'cross-site') fail(403, 'ORIGIN_NOT_ALLOWED', '다른 사이트에서 시작한 요청은 허용하지 않습니다.');
      const path = url.pathname;
      if (path.startsWith('/api/') && url.search) fail(400, 'NO_QUERY_DATA', '인증 정보와 운동 정보는 URL에 넣지 마세요.');
      if (req.method === 'GET' && path === '/api/health') return json(res, 200, { service: 'chabun-relay', mode: 'local-prototype', version: 1, storage: 'ephemeral-memory', transport: encrypted ? 'tls' : 'plain' });
      if (req.method === 'POST' && path === '/api/pairings') {
        rate(req, 'create', 10);
        if (!keysExactly(await readJson(req), [])) fail(400, 'UNEXPECTED_FIELDS', '차량 연결 생성에는 개인정보가 필요하지 않습니다.');
        if (pairings.size >= 100) fail(503, 'CAPACITY', '연결 한도에 도달했습니다. 잠시 뒤 다시 시도해 주세요.');
        let code;
        do { code = String(randomInt(100000, 1000000)); } while ([...pairings.values()].some(pair => pair.code === code));
        const pair = { id: randomBytes(16).toString('hex'), code, vehicleToken: token(), phoneToken: null, confirmation: null, status: 'waiting', expires: now() + PAIR_TTL, summary: null, summaryId: null, receivedAt: null, summaryExpires: null, summaryExpired: false };
        pairings.set(pair.id, pair);
        return json(res, 201, { pairingId: pair.id, code, vehicleToken: pair.vehicleToken, expiresAt: iso(pair.expires) });
      }
      if (req.method === 'POST' && path === '/api/pairings/claim') {
        rate(req, 'claim', 10);
        const body = await readJson(req);
        sweep(); // A slow request body must not keep an expired one-use code alive.
        if (!keysExactly(body, ['code']) || typeof body.code !== 'string' || !/^\d{6}$/.test(body.code)) fail(400, 'INVALID_CODE', '차량의 6자리 연결 번호를 입력해 주세요.');
        const pair = [...pairings.values()].find(item => item.status === 'waiting' && item.code === body.code);
        if (!pair) fail(404, 'PAIRING_NOT_FOUND', '연결 번호가 만료됐거나 이미 사용됐습니다.');
        pair.phoneToken = token(); pair.confirmation = String(randomInt(1000, 10000)); pair.status = 'claimed'; pair.code = null;
        return json(res, 200, { pairingId: pair.id, phoneToken: pair.phoneToken, confirmation: pair.confirmation, expiresAt: iso(pair.expires) });
      }
      const match = path.match(/^\/api\/pairings\/([a-f0-9]{32})(?:\/(confirm|summary))?$/);
      if (match) {
        rate(req, 'protected', 200);
        const [, id, action] = match; const pair = pairings.get(id);
        if (!pair) fail(404, 'PAIRING_NOT_FOUND', '연결이 만료되거나 해제됐습니다. 다시 연결해 주세요.');
        if (!action && req.method === 'GET') { authorize(req, pair); return json(res, 200, describe(pair)); }
        if (!action && req.method === 'DELETE') { authorize(req, pair); pairings.delete(id); return json(res, 200, { deleted: true }); }
        if (action === 'confirm' && req.method === 'POST') {
          authorize(req, pair, 'vehicle'); const body = await readJson(req);
          requireCurrent(pair); authorize(req, pair, 'vehicle');
          if (!keysExactly(body, ['confirmation']) || typeof body.confirmation !== 'string' || body.confirmation !== pair.confirmation) fail(400, 'CONFIRMATION_MISMATCH', '두 화면의 확인 번호가 같은지 확인해 주세요.');
          if (pair.status !== 'claimed') fail(409, 'WRONG_STATE', '승인할 휴대폰 연결 요청이 없습니다.');
          pair.status = 'paired'; pair.expires = now() + SESSION_TTL;
          return json(res, 200, describe(pair));
        }
        if (action === 'summary' && req.method === 'POST') {
          authorize(req, pair, 'phone');
          if (pair.status !== 'paired') fail(409, 'CONFIRMATION_REQUIRED', '차량에서 연결을 먼저 확인해 주세요.');
          const body = await readJson(req);
          requireCurrent(pair); authorize(req, pair, 'phone');
          if (pair.status !== 'paired') fail(409, 'CONFIRMATION_REQUIRED', '차량에서 연결을 먼저 확인해 주세요.');
          const checked = validateSummary(body, now());
          if (!checked.ok) fail(400, checked.code, checked.message);
          if (!encrypted && checked.summary.source !== 'demo') fail(403, 'TLS_REQUIRED', '실제 운동 요약은 인증서를 검증한 HTTPS 연결에서만 공유할 수 있습니다.');
          const receivedAt = now();
          const retentionCap = pair.lastWorkoutEndedAt === checked.endedAtMs && pair.summaryExpires !== null
            ? pair.summaryExpires
            : Math.min(receivedAt + SUMMARY_TTL, checked.endedAtMs + WORKOUT_MAX_AGE_MS);
          if (receivedAt >= retentionCap) fail(400, 'STALE_WORKOUT', '공유 보관 시간(30분)이 지난 운동 기록입니다. 직접 시작할 수 있어요.');
          pair.summary = checked.summary; pair.summaryId = randomBytes(12).toString('hex'); pair.receivedAt = receivedAt; pair.summaryExpires = retentionCap; pair.lastWorkoutEndedAt = checked.endedAtMs; pair.summaryExpired = false;
          return json(res, 201, { accepted: true, summaryId: pair.summaryId, receivedAt: iso(pair.receivedAt), expiresAt: iso(pair.summaryExpires) });
        }
        if (action === 'summary' && req.method === 'GET') {
          authorize(req, pair, 'vehicle');
          if (pair.status !== 'paired') fail(409, 'CONFIRMATION_REQUIRED', '연결 확인을 먼저 마쳐 주세요.');
          return json(res, 200, { summary: pair.summary, summaryId: pair.summaryId, receivedAt: pair.receivedAt === null ? null : iso(pair.receivedAt), expiresAt: pair.summaryExpires === null ? null : iso(pair.summaryExpires), reason: pair.summary ? 'available' : pair.summaryExpired ? 'expired' : 'empty' });
        }
        fail(405, 'METHOD_NOT_ALLOWED', '허용되지 않는 동작입니다.');
      }
      if (path.startsWith('/api/')) fail(404, 'NOT_FOUND', '요청한 기능을 찾을 수 없습니다.');
      if (!['GET', 'HEAD'].includes(req.method)) fail(405, 'METHOD_NOT_ALLOWED', '허용되지 않는 동작입니다.');
      const name = ['/', '/phone', '/vehicle'].includes(path) ? 'index.html' : path.slice(1);
      if (!['index.html', 'styles.css', 'app.mjs', 'session.mjs', 'favicon.svg', 'fonts/NotoSansKR.ttf', 'fonts/OFL.txt'].includes(name)) fail(404, 'NOT_FOUND', '화면을 찾을 수 없습니다.');
      let content;
      try { content = await readFile(join(webRoot, name)); } catch { fail(404, 'NOT_FOUND', '화면 파일을 찾을 수 없습니다.'); }
      const types = { '.html': 'text/html', '.css': 'text/css', '.mjs': 'text/javascript', '.svg': 'image/svg+xml', '.ttf': 'font/ttf', '.txt': 'text/plain' };
      res.writeHead(200, { 'Content-Type': `${types[extname(name)]}; charset=utf-8` }); res.end(req.method === 'HEAD' ? undefined : content);
    } catch (error) {
      const known = error instanceof ApiError;
      if (!res.headersSent) json(res, known ? error.status : 500, { error: { code: known ? error.code : 'INTERNAL_ERROR', message: known ? error.message : '요청을 처리하지 못했습니다. 다시 시도해 주세요.' } });
      else res.end();
      // Never log request bodies, health information or credentials, even on errors.
    }
  };
  const server = tls
    ? https.createServer({ ...tls, minVersion: 'TLSv1.2' }, handleRequest)
    : http.createServer(handleRequest);
  server.requestTimeout = 10_000; server.headersTimeout = 10_000;
  return { server, close: () => { clearInterval(cleaner); pairings.clear(); rates.clear(); return new Promise(resolve => server.close(resolve)); } };
}
