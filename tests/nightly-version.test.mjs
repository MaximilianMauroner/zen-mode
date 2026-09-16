import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { releaseIdentity, stampRelease } from '../scripts/stamp-nightly-version.mjs';
import { assertArtifactIdentity } from '../scripts/verify-release-artifacts.mjs';

test('rejects identities Google Play cannot accept', () => {
  for (const version of ['0.1.5-nightly.20260917', '0.2.0', '1.0', '0.1.-1']) {
    assert.throws(() => releaseIdentity(version, 8));
  }
  for (const code of [0, -1, 8.5, NaN, 2100000001]) assert.throws(() => releaseIdentity('0.1.6', code));
});

test('one reserved identity stamps app config and dependency metadata without changing dependencies', () => {
  const root = mkdtempSync(join(tmpdir(), 'nightly-stamp-test-'));
  try {
    writeFileSync(join(root, 'app.json'), JSON.stringify({ expo: { version: '0.1.5', android: { package: 'com.lab4code.test', versionCode: 7 } } }));
    writeFileSync(join(root, 'package.json'), JSON.stringify({ version: '0.1.5', dependencies: { expo: '57.0.23' } }));
    writeFileSync(join(root, 'package-lock.json'), JSON.stringify({ version: '0.1.5', packages: { '': { version: '0.1.5' }, 'node_modules/expo': { version: '57.0.23' } } }));
    stampRelease(root, '0.1.6', 8);
    const app = JSON.parse(readFileSync(join(root, 'app.json'), 'utf8')).expo;
    assert.equal(app.version, '0.1.6');
    assert.equal(app.android.versionCode, 8);
    assert.equal(app.android.package, 'com.lab4code.test');
    const manifest = JSON.parse(readFileSync(join(root, 'package.json'), 'utf8'));
    assert.deepEqual(manifest.dependencies, { expo: '57.0.23' });
    assert.equal(manifest.version, '0.1.6');
    const lock = JSON.parse(readFileSync(join(root, 'package-lock.json'), 'utf8'));
    assert.equal(lock.packages[''].version, '0.1.6');
    assert.equal(lock.packages['node_modules/expo'].version, '57.0.23');
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});


test('upload gate rejects stale artifacts and unapproved signing certificates', () => {
  const expected = { package: 'com.lab4code.zenmode', version: '0.1.6', versionCode: 8 };
  const actual = { ...expected, certificate: '46ab4a5bdb4b5e57ccf0c35570076eb58c3adf02db04aa04bbbcfd1eda082d59' };
  assert.doesNotThrow(() => assertArtifactIdentity(actual, expected, 'zen-mode'));
  assert.throws(() => assertArtifactIdentity({ ...actual, versionCode: 7 }, expected, 'zen-mode'), /versionCode mismatch/);
  assert.throws(() => assertArtifactIdentity({ ...actual, version: '0.1.5' }, expected, 'zen-mode'), /version mismatch/);
  assert.throws(() => assertArtifactIdentity({ ...actual, package: 'com.lab4code.other' }, expected, 'zen-mode'), /package mismatch/);
  assert.throws(() => assertArtifactIdentity({ ...actual, certificate: 'f'.repeat(64) }, expected, 'zen-mode'), /approved upload certificate/);
});
