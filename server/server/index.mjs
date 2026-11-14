import { createApp } from './app.mjs';
const port = Number(process.env.PORT ?? 8787);
const host = process.env.BIND_HOST ?? '127.0.0.1';
const allowedHosts = (process.env.ALLOWED_HOSTS ?? '').split(',').map(value => value.trim()).filter(Boolean);
if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('PORT must be 1..65535');
const app = createApp({ allowedHosts });
app.server.on('error', error => { console.error(`차분 could not start: ${error.code ?? 'UNKNOWN'}`); process.exitCode = 1; });
app.server.listen(port, host, () => {
  console.log(`차분 local prototype: http://${host}:${port}`);
  console.log('Synthetic browser demo. No real vehicle actuation. In-memory sessions expire or delete on disconnect.');
  if (!['127.0.0.1', 'localhost', '::1'].includes(host)) console.log('Network binding enabled explicitly. Use synthetic data only until HTTPS and production access controls are configured.');
});
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, async () => { await app.close(); process.exit(0); });
