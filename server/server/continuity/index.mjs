import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { ContinuityEngine, ContinuityError } from './engine.mjs';

const root = fileURLToPath(new URL('../../', import.meta.url));
const staticFiles = new Map([
  ['/', ['web/continuity/index.html', 'text/html; charset=utf-8']],
  ['/index.html', ['web/continuity/index.html', 'text/html; charset=utf-8']],
  ['/styles.css', ['web/continuity/styles.css', 'text/css; charset=utf-8']],
  ['/app.mjs', ['web/continuity/app.mjs', 'text/javascript; charset=utf-8']],
  ['/fonts/NotoSansKR.ttf', ['web/fonts/NotoSansKR.ttf', 'font/ttf']],
  ['/fonts/OFL.txt', ['web/fonts/OFL.txt', 'text/plain; charset=utf-8']]
]);
const headers = {
  'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'no-referrer',
  'Content-Security-Policy': "default-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'; style-src 'self' 'unsafe-inline'; img-src 'self' data:"
};
function json(res, status, body) { res.writeHead(status, { ...headers, 'Content-Type': 'application/json; charset=utf-8' }); res.end(JSON.stringify(body)); }
async function body(req) {
  let bytes = 0, chunks = [];
  for await (const chunk of req) { bytes += chunk.length; if (bytes > 16384) throw new ContinuityError('BODY_LIMIT', '요청이 너무 커요.', 413); chunks.push(chunk); }
  try { return JSON.parse(Buffer.concat(chunks).toString('utf8')); } catch { throw new ContinuityError('INVALID_JSON', '요청 형식을 확인해 주세요.'); }
}
export function createContinuityServer({ engine = new ContinuityEngine() } = {}) {
  const server = createServer(async (req, res) => {
    const port = res.socket.localPort, allowed = ['127.0.0.1:' + port, 'localhost:' + port];
    if (!allowed.includes(req.headers.host) || (req.headers.origin && req.headers.origin !== 'http://' + req.headers.host) || req.headers['sec-fetch-site'] === 'cross-site') return json(res, 403, { error: { code: 'LOCAL_ONLY', message: '이 시연 서버는 같은 로컬 출처에서만 열 수 있어요.' } });
    if (!req.url.startsWith('/') || req.url.startsWith('//') || req.url.includes('\\')) return json(res, 400, { error: { code: 'INVALID_PATH', message: '요청 경로가 올바르지 않아요.' } });
    try {
      const path = new URL(req.url, 'http://127.0.0.1').pathname;
      if (path === '/api/state' && req.method === 'GET') return json(res, 200, engine.getState());
      if (path === '/api/commands' && req.method === 'POST') {
        if (!/^application\/json(?:\s*;|$)/i.test(req.headers['content-type'] ?? '')) throw new ContinuityError('CONTENT_TYPE', 'JSON 요청만 받을 수 있어요.', 415);
        return json(res, 200, { state: engine.execute(await body(req)) });
      }
      if (!['GET', 'HEAD'].includes(req.method)) return json(res, 405, { error: { code: 'METHOD', message: '지원하지 않는 요청이에요.' } });
      if (path === '/favicon.ico') { res.writeHead(204, headers); return res.end(); }
      const file = staticFiles.get(path); if (!file) return json(res, 404, { error: { code: 'NOT_FOUND', message: '페이지를 찾지 못했어요.' } });
      const data = await readFile(resolve(root, file[0])); res.writeHead(200, { ...headers, 'Content-Type': file[1], 'Content-Length': data.length }); res.end(req.method === 'HEAD' ? undefined : data);
    } catch (error) {
      const known = error instanceof ContinuityError;
      json(res, known ? error.status : 500, { error: { code: known ? error.code : 'UNAVAILABLE', message: known ? error.message : '시연 파일이나 서버 상태를 확인해 주세요.' }, state: engine.getState() });
    }
  });
  server.requestTimeout = 10000; server.headersTimeout = 10000;
  return server;
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const raw = process.env.CONTINUITY_PORT ?? '8792', port = Number(raw);
  if (!/^\d{1,5}$/.test(raw) || port < 0 || port > 65535) { console.error('CONTINUITY_PORT must be0–65535.'); process.exitCode = 1; }
  else {
    const server = createContinuityServer();
    server.on('error', error => { console.error('Cannot open local demo: ' + error.code); process.exitCode = 1; });
    server.listen(port, '127.0.0.1', () => { console.log('차분 presets & arrival demo: http://127.0.0.1:' + server.address().port); console.log('Synthetic-only: no external devices, health records, or location. Ctrl+C stops the server.'); });
    for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, () => { server.closeAllConnections(); server.close(); });
  }
}
