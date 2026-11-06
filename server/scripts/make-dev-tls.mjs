// Makes a self-signed development certificate for the local servers (2026-11-06).
// Node crypto only: no OpenSSL, no network, no system-trust change. The key stays in server/.dev-tls/ (git-ignored).
// SANs: localhost, 127.0.0.1 and 10.0.2.2 (the Android emulator's address for this PC). Valid for 365 days.
import { generateKeyPairSync, randomBytes, sign, X509Certificate } from 'node:crypto';
import { mkdirSync, writeFileSync, existsSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

function der(tag, ...parts) {
  const value = Buffer.concat(parts);
  let length = Buffer.from([value.length]);
  if (value.length >= 128) {
    const hex = value.length.toString(16).padStart(Math.ceil(value.length.toString(16).length / 2) * 2, '0');
    const bytes = Buffer.from(hex, 'hex');
    length = Buffer.concat([Buffer.from([0x80 | bytes.length]), bytes]);
  }
  return Buffer.concat([Buffer.from([tag]), length, value]);
}
const seq = (...parts) => der(0x30, ...parts);
const hex = value => Buffer.from(value, 'hex');
const pem = (type, bytes) => `-----BEGIN ${type}-----\n${bytes.toString('base64').match(/.{1,64}/g).join('\n')}\n-----END ${type}-----\n`;
const utc = date => Buffer.from(date.toISOString().replace(/[-:T]/g, '').slice(2, 14) + 'Z'); // YYMMDDHHMMSSZ

const out = resolve(dirname(fileURLToPath(import.meta.url)), '..', '.dev-tls');
const certPath = resolve(out, 'dev-cert.pem'), keyPath = resolve(out, 'dev-key.pem');
if (existsSync(certPath) && !process.argv.includes('--replace')) {
  console.error(`${certPath} already exists. Run again with --replace to make a new one.`);
  process.exit(1);
}

const { publicKey, privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
const serial = randomBytes(16); serial[0] |= 0x80;
const algorithm = seq(der(6, hex('2a864886f70d01010b')), der(5));
const name = seq(der(0x31, seq(der(6, hex('550403')), der(12, Buffer.from('Chabun local development')))));
const now = Date.now();
const validity = seq(der(23, utc(new Date(now - 86_400_000))), der(23, utc(new Date(now + 365 * 86_400_000))));
const san = seq(der(0x82, Buffer.from('localhost')), der(0x87, Buffer.from([127, 0, 0, 1])), der(0x87, Buffer.from([10, 0, 2, 2])));
const extensions = der(0xa3, seq(
  seq(der(6, hex('551d13')), der(1, hex('ff')), der(4, seq(der(1, hex('ff'))))),
  seq(der(6, hex('551d11')), der(4, san))
));
const tbs = seq(der(0xa0, der(2, hex('02'))), der(2, Buffer.concat([hex('00'), serial])),
  algorithm, name, validity, name, publicKey.export({ type: 'spki', format: 'der' }), extensions);
const cert = pem('CERTIFICATE', seq(tbs, algorithm, der(3, hex('00'), sign('sha256', tbs, privateKey))));
const parsed = new X509Certificate(cert);
if (!parsed.verify(publicKey) || !parsed.checkIP('127.0.0.1') || !parsed.checkIP('10.0.2.2') || !parsed.checkHost('localhost')) {
  throw new Error('The new certificate did not verify');
}
mkdirSync(out, { recursive: true, mode: 0o700 });
writeFileSync(keyPath, privateKey.export({ type: 'pkcs8', format: 'pem' }), { mode: 0o600 });
writeFileSync(certPath, cert);
console.log(`Certificate: ${certPath}\nPrivate key: ${keyPath} (keep it on this PC)\nValid until: ${parsed.validTo}`);
console.log('\nPowerShell, before starting a server:');
console.log(`$env:PITIN_PRESET_TLS_CERT='${certPath}'; $env:PITIN_PRESET_TLS_KEY='${keyPath}'`);
console.log(`$env:PITIN_TLS_CERT='${certPath}'; $env:PITIN_TLS_KEY='${keyPath}'`);
