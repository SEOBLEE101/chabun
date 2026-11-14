// Test-only self-signed certificate. Uses Node crypto, never a bundled private key.
import { generateKeyPairSync, randomBytes, sign, X509Certificate } from 'node:crypto';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

// Minimal DER encoding of this fixed test certificate, not a runtime CA service.
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

export function createTestTlsFixture() {
  const { publicKey, privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
  const serial = randomBytes(16); serial[0] |= 0x80; // The leading zero below is then required by DER.
  const algorithm = seq(der(6, hex('2a864886f70d01010b')), der(5));
  const name = seq(der(0x31, seq(der(6, hex('550403')), der(12, Buffer.from('Chabun EPHEMERAL TEST ONLY')))));
  const validity = seq(der(24, Buffer.from('20200101000000Z')), der(24, Buffer.from('21200101000000Z')));
  const san = seq(der(0x82, Buffer.from('localhost')), der(0x87, Buffer.from([127, 0, 0, 1])));
  const extensions = der(0xa3, seq(
    seq(der(6, hex('551d13')), der(1, hex('ff')), der(4, seq(der(1, hex('ff'))))),
    seq(der(6, hex('551d11')), der(4, san))
  ));
  const tbs = seq(der(0xa0, der(2, hex('02'))), der(2, Buffer.concat([hex('00'), serial])),
    algorithm, name, validity, name, publicKey.export({ type: 'spki', format: 'der' }), extensions);
  const cert = Buffer.from(pem('CERTIFICATE', seq(tbs, algorithm, der(3, hex('00'), sign('sha256', tbs, privateKey)))));
  const key = Buffer.from(privateKey.export({ type: 'pkcs8', format: 'pem' }));
  const parsed = new X509Certificate(cert);
  if (!parsed.verify(publicKey) || !parsed.checkIP('127.0.0.1') || !parsed.checkHost('localhost')) throw new Error('Invalid ephemeral TLS fixture');
  const directory = mkdtempSync(join(tmpdir(), 'chabun-test-tls-'));
  const certPath = join(directory, 'cert.pem'), keyPath = join(directory, 'key.pem');
  try {
    writeFileSync(certPath, cert, { mode: 0o600 });
    writeFileSync(keyPath, key, { mode: 0o600 });
  } catch (error) { rmSync(directory, { recursive: true, force: true }); throw error; }
  const cleanup = () => rmSync(directory, { recursive: true, force: true });
  process.once('exit', cleanup);
  return { cert, key, certPath, keyPath };
}
