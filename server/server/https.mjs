import { readFile } from 'node:fs/promises';
import { isAbsolute, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { createApp } from './app.mjs';

export async function createHttpsRelay(env = process.env) {
  const certPath = env.PITIN_TLS_CERT;
  const keyPath = env.PITIN_TLS_KEY;
  if (!certPath || !keyPath || !isAbsolute(certPath) || !isAbsolute(keyPath)) {
    throw new Error('PITIN_TLS_CERT and PITIN_TLS_KEY must be explicit absolute PEM file paths');
  }
  const port = Number(env.PITIN_TLS_PORT ?? 9443);
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('PITIN_TLS_PORT must be 1..65535');
  const allowedHosts = (env.ALLOWED_HOSTS ?? '').split(',').map(value => value.trim()).filter(Boolean);
  const [cert, key] = await Promise.all([readFile(certPath), readFile(keyPath)]);
  const app = createApp({ allowedHosts, tls: { cert, key } });
  // USB reverse forwarding keeps this opt-in TLS listener on the development PC.
  return { ...app, host: '127.0.0.1', port };
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    const app = await createHttpsRelay();
    app.server.on('error', error => {
      console.error(`차분 HTTPS could not start: ${error.code ?? 'UNKNOWN'}`);
      process.exitCode = 1;
    });
    app.server.listen(app.port, app.host, () => {
      console.log(`차분 local TLS relay: https://${app.host}:${app.port}`);
      console.log('Verify the certificate on every client. Explicit pairing and summary consent are required.');
    });
    for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, async () => { await app.close(); process.exit(0); });
  } catch (error) {
    console.error(`차분 HTTPS configuration failed: ${error.code ?? 'CHECK_TLS_PEM_PATHS_AND_PORT'}`);
    process.exitCode = 1;
  }
}
