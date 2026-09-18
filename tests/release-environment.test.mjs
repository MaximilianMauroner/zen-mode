import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { releaseEnvironment, preflightRelease, releaseFailure } from '../scripts/release-environment.mjs';

function fixture(run) {
  const root = mkdtempSync(join(tmpdir(), 'release-environment-'));
  try {
    const sdk = join(root, 'sdk');
    mkdirSync(join(sdk, 'build-tools', '36.0.0'), { recursive: true });
    for (const name of ['aapt', 'apksigner']) writeFileSync(join(sdk, 'build-tools', '36.0.0', name), '#!/bin/sh\nexit 0\n', { mode: 0o700 });
    const jar = join(root, 'bundletool.jar');
    const key = join(root, 'key.json');
    writeFileSync(jar, 'jar');
    writeFileSync(key, JSON.stringify({ type: 'service_account', client_email: 'test@example.com', private_key: 'test' }));
    run({ root, env: { PATH: process.env.PATH, ANDROID_HOME: sdk, ANDROID_BUNDLETOOL_JAR: jar, PLAY_SERVICE_ACCOUNT_KEY_PATH: key } });
  } finally { rmSync(root, { recursive: true, force: true }); }
}

test('SDK aliases normalize and conflicting roots fail', () => fixture(({ env }) => {
  assert.equal(releaseEnvironment(env).ANDROID_SDK_ROOT, env.ANDROID_HOME);
  const { ANDROID_HOME, ...other } = env;
  assert.equal(releaseEnvironment({ ...other, ANDROID_SDK_ROOT: ANDROID_HOME }).ANDROID_HOME, ANDROID_HOME);
  assert.throws(() => releaseEnvironment({ ...env, ANDROID_SDK_ROOT: '/other' }), /must match/);
}));

test('required environment and unreadable paths fail before build', () => fixture(({ env }) => {
  for (const name of ['PATH', 'ANDROID_HOME', 'ANDROID_BUNDLETOOL_JAR', 'PLAY_SERVICE_ACCOUNT_KEY_PATH']) {
    assert.throws(() => releaseEnvironment({ ...env, [name]: '' }));
  }
  assert.throws(() => releaseEnvironment({ ...env, ANDROID_BUNDLETOOL_JAR: '/missing.jar' }), /missing or unreadable/);
  writeFileSync(env.PLAY_SERVICE_ACCOUNT_KEY_PATH, '{}');
  assert.throws(() => releaseEnvironment(env), /service-account/);
}));

test('preflight rejects missing EAS and SDK verification tools', () => fixture(({ root, env }) => {
  assert.throws(() => preflightRelease(root, { ...env, EAS_BIN: '/missing-eas' }), /EAS is unavailable/);
  rmSync(join(env.ANDROID_HOME, 'build-tools'), { recursive: true });
  mkdirSync(join(env.ANDROID_HOME, 'build-tools'));
  assert.throws(() => preflightRelease(root, env), /build-tools are missing/);
}));

test('failure evidence has only a known stage and fixed sanitized message', () => {
  for (const stage of ['preflight', 'checks', 'apk-build', 'aab-build', 'artifact-verify', 'play-upload']) {
    assert.deepEqual(releaseFailure(stage), { stage, message: `Release failed during ${stage}; inspect the private host logs` });
  }
});
