import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { createHash } from 'node:crypto';
import { applyDelta } from '../packaging/apply-delta.mjs';
const sha = x => createHash('sha256').update(x).digest('hex');
function fixture() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pitin-delta-test-')), delta = path.join(root, 'delta'), target = path.join(root, 'project');
  fs.mkdirSync(path.join(delta, 'payload'), { recursive: true }); fs.mkdirSync(target);
  fs.writeFileSync(path.join(target, 'package.json'), JSON.stringify({ name: 'pit-in-recovery-prototype' })); fs.writeFileSync(path.join(target, 'PACKAGE_MANIFEST.json'), JSON.stringify({ version: '0.2' }));
  fs.writeFileSync(path.join(target, 'old.txt'), 'baseline'); fs.writeFileSync(path.join(target, 'unrelated.txt'), 'my notes');
  fs.writeFileSync(path.join(delta, 'payload', 'old.txt'), 'updated'); fs.writeFileSync(path.join(delta, 'payload', 'new.txt'), 'new file');
  const manifest = { packageType: 'windows-android-delta', baseVersion: '0.2', version: '0.4', files: [{ path: 'old.txt', bytes: 7, sha256: sha('updated'), previousSha256: sha('baseline') }, { path: 'new.txt', bytes: 8, sha256: sha('new file'), previousSha256: null }] };
  const save = () => fs.writeFileSync(path.join(delta, 'DELTA_MANIFEST.json'), JSON.stringify(manifest)); save();
  return { root, delta, target, manifest, save, cleanup: () => fs.rmSync(root, { recursive: true, force: true }) };
}
test('delta dry-run makes no writes; apply keeps originals and is idempotent', () => {
  const f = fixture(); try {
    const check = applyDelta({ deltaDir: f.delta, targetDir: f.target }); assert.equal(check.changes, 2); assert.equal(fs.existsSync(path.join(f.target, 'new.txt')), false);
    const result = applyDelta({ deltaDir: f.delta, targetDir: f.target, apply: true }); assert.equal(result.applied, 2); assert.equal(fs.readFileSync(path.join(result.backup, 'original', 'old.txt'), 'utf8'), 'baseline');
    assert.equal(fs.readFileSync(path.join(f.target, 'unrelated.txt'), 'utf8'), 'my notes'); assert.equal(applyDelta({ deltaDir: f.delta, targetDir: f.target, apply: true }).applied, 0);
  } finally { f.cleanup(); }
});
test('delta refuses local changes before any destination file is written', () => {
  const f = fixture(); try { fs.writeFileSync(path.join(f.target, 'old.txt'), 'my own edit'); assert.throws(() => applyDelta({ deltaDir: f.delta, targetDir: f.target, apply: true }), e => e.report.conflicts.includes('old.txt')); assert.equal(fs.existsSync(path.join(f.target, 'new.txt')), false); assert.equal(fs.readFileSync(path.join(f.target, 'old.txt'), 'utf8'), 'my own edit'); } finally { f.cleanup(); }
});
test('delta rejects damaged payload, traversal and existing new-file collisions', () => {
  const f = fixture(); try {
    fs.writeFileSync(path.join(f.delta, 'payload', 'new.txt'), 'damaged'); assert.throws(() => applyDelta({ deltaDir: f.delta, targetDir: f.target, apply: true }), /verification failed/);
    fs.writeFileSync(path.join(f.delta, 'payload', 'new.txt'), 'new file'); fs.writeFileSync(path.join(f.target, 'new.txt'), 'my unrelated new file'); assert.throws(() => applyDelta({ deltaDir: f.delta, targetDir: f.target, apply: true }), e => e.report.conflicts.includes('new.txt'));
    f.manifest.files[0].path = '../outside.txt'; f.save(); assert.throws(() => applyDelta({ deltaDir: f.delta, targetDir: f.target, apply: true }), /Unsafe manifest/);
  } finally { f.cleanup(); }
});
test('delta refuses symlink traversal and the wrong project baseline', () => {
  const f = fixture(); try {
    const outside = path.join(f.root, 'outside'); fs.mkdirSync(outside); fs.writeFileSync(path.join(outside, 'old.txt'), 'baseline');
    fs.mkdirSync(path.join(f.delta, 'payload', 'linked')); fs.writeFileSync(path.join(f.delta, 'payload', 'linked', 'old.txt'), 'updated');
    // Windows junctions exercise real directory traversal without requiring symlink privileges.
    fs.symlinkSync(outside, path.join(f.target, 'linked'), process.platform === 'win32' ? 'junction' : 'dir');
    f.manifest.files[0].path = 'linked/old.txt'; f.save();
    assert.throws(() => applyDelta({ deltaDir: f.delta, targetDir: f.target, apply: true }), /Symlink/);
    assert.equal(fs.readFileSync(path.join(outside, 'old.txt'), 'utf8'), 'baseline');
    assert.equal(fs.existsSync(path.join(f.target, 'new.txt')), false);
    fs.writeFileSync(path.join(f.target, 'PACKAGE_MANIFEST.json'), JSON.stringify({ version: '0.1' })); assert.throws(() => applyDelta({ deltaDir: f.delta, targetDir: f.target, apply: true }), /original Pit-In v0.2/);
  } finally { f.cleanup(); }
});
