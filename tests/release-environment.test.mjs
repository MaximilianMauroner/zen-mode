import test from 'node:test';
import * as requireFs from 'node:fs';
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

test('nightly preflight failure writes evidence without touching the ledger', async () => {
  const { spawnSync } = await import('node:child_process');
  const { fileURLToPath } = await import('node:url');
  fixture(({ root, env }) => {
    const bin = join(root, 'bin');
    mkdirSync(bin);
    writeFileSync(join(bin, 'git'), '#!/bin/sh\nif [ "$1" = rev-parse ]; then echo abcdef1234567890; fi\n', { mode: 0o700 });
    const marker = join(root, 'ledger-called');
    writeFileSync(join(bin, 'ssh'), `#!/bin/sh\ntouch '${marker}'\nexit 1\n`, { mode: 0o700 });
    const result = spawnSync(process.execPath, [fileURLToPath(new URL('../scripts/nightly-release.mjs', import.meta.url)), 'run'], {
      env: { ...env, PATH: `${bin}:${env.PATH}`, ANDROID_HOME: '', NIGHTLY_HOST_LOCKED: '1', RELEASE_ARTIFACTS_DIR: root }, encoding: 'utf8',
    });
    assert.equal(result.status, 1);
    const { existsSync, readdirSync, readFileSync } = requireFs;
    assert.equal(existsSync(marker), false);
    const app = readdirSync(root).find((name) => ['zen-mode', 'moodinator'].includes(name));
    const record = JSON.parse(readFileSync(join(root, app, 'preflight-abcdef123456', 'release.json'), 'utf8'));
    assert.equal(record.failure.stage, 'preflight');
    assert.equal(record.versionCode, undefined);
  });
});

test('LaunchAgent rejects missing values and writes both SDK aliases', async (t) => {
  if (process.platform !== 'darwin' || Intl.DateTimeFormat().resolvedOptions().timeZone !== 'Europe/Vienna') return t.skip('Mac Vienna LaunchAgent only');
  const { spawnSync } = await import('node:child_process');
  const { fileURLToPath } = await import('node:url');
  fixture(({ root, env }) => {
    mkdirSync(join(root, 'scripts'));
    for (const name of ['write-nightly-launch-agent.mjs', 'release-environment.mjs']) {
      requireFs.copyFileSync(fileURLToPath(new URL(`../scripts/${name}`, import.meta.url)), join(root, 'scripts', name));
    }
    const script = join(root, 'scripts/write-nightly-launch-agent.mjs');
    const target = join(root, '.agents/artifacts/net.lab4code.nightly.plist');
    const args = [script, root, root];
    assert.equal(spawnSync(process.execPath, args, { env: { ...env, ANDROID_BUNDLETOOL_JAR: '' }, stdio: 'ignore' }).status, 1);
    assert.equal(requireFs.existsSync(target), false);
    assert.equal(spawnSync(process.execPath, args, { env, stdio: 'ignore' }).status, 0);
    const plist = requireFs.readFileSync(target, 'utf8');
    for (const key of ['ANDROID_HOME', 'ANDROID_SDK_ROOT']) assert.ok(plist.includes(`<key>${key}</key><string>${env.ANDROID_HOME}</string>`));
    assert.equal(spawnSync('plutil', ['-lint', target], { stdio: 'ignore' }).status, 0);
  });
});
