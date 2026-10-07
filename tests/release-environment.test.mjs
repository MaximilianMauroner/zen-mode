import test from 'node:test';
import * as requireFs from 'node:fs';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { releaseEnvironment, preflightRelease, releaseFailure, localChildEnvironment, validateEasVersion } from '../scripts/release-environment.mjs';

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
    run({ root, env: { EXPO_TOKEN: 'test-token', PATH: process.env.PATH, ANDROID_HOME: sdk, ANDROID_BUNDLETOOL_JAR: jar, PLAY_SERVICE_ACCOUNT_KEY_PATH: key } });
  } finally { rmSync(root, { recursive: true, force: true }); }
}

test('SDK aliases normalize and conflicting roots fail', () => fixture(({ env }) => {
  assert.equal(releaseEnvironment(env).ANDROID_SDK_ROOT, env.ANDROID_HOME);
  const { ANDROID_HOME, ...other } = env;
  assert.equal(releaseEnvironment({ ...other, ANDROID_SDK_ROOT: ANDROID_HOME }).ANDROID_HOME, ANDROID_HOME);
  assert.throws(() => releaseEnvironment({ ...env, ANDROID_SDK_ROOT: '/other' }), /must match/);
}));

test('local child authentication is limited to its phase', () => {
  const env = { PATH: '/fixture/bin', EXPO_TOKEN: 'expo', GH_TOKEN: 'gh', GITHUB_TOKEN: 'github', PLAY_SERVICE_ACCOUNT_JSON: 'private', PLAY_SERVICE_ACCOUNT_KEY_PATH: '/fixture/key' };
  assert.deepEqual(localChildEnvironment(env, 'checks'), { PATH: env.PATH });
  assert.deepEqual(localChildEnvironment(env, 'eas'), { PATH: env.PATH, EXPO_TOKEN: env.EXPO_TOKEN });
  assert.deepEqual(localChildEnvironment(env, 'ledger'), { PATH: env.PATH, GH_TOKEN: env.GH_TOKEN, GITHUB_TOKEN: env.GITHUB_TOKEN });
  assert.deepEqual(localChildEnvironment(env, 'upload'), { PATH: env.PATH, PLAY_SERVICE_ACCOUNT_KEY_PATH: env.PLAY_SERVICE_ACCOUNT_KEY_PATH });
  assert.deepEqual(localChildEnvironment({ PATH: env.PATH }, 'eas'), { PATH: env.PATH });
});

test('checked stable CLI proof uses the source minimum', () => {
  assert.equal(validateEasVersion('eas-cli/20.5.1 test', '>= 20.5.1'), '20.5.1');
  assert.throws(() => validateEasVersion('eas-cli/20.5.1-beta.1', '>= 20.5.1'), /stable/);
  assert.throws(() => validateEasVersion('eas-cli/20.5.1', '>= 20.5.2'), /does not satisfy/);
});

test('required environment and unreadable paths fail before build', () => fixture(({ env }) => {
  for (const name of ['PATH', 'ANDROID_HOME', 'ANDROID_BUNDLETOOL_JAR']) {
    assert.throws(() => releaseEnvironment({ ...env, [name]: '' }));
  }
  assert.throws(() => releaseEnvironment({ ...env, ANDROID_BUNDLETOOL_JAR: '/missing.jar' }), /missing or unreadable/);
}));

test('preflight rejects missing EAS and SDK verification tools', () => fixture(({ root, env }) => {
  assert.throws(() => preflightRelease(root, { ...env, EAS_BIN: '/missing-eas' }), /EAS is unavailable/);
  rmSync(join(env.ANDROID_HOME, 'build-tools'), { recursive: true });
  mkdirSync(join(env.ANDROID_HOME, 'build-tools'));
  assert.throws(() => preflightRelease(root, env), /build-tools are missing/);
}));

test('preflight checks the selected CLI against the release source before reserving a version', () => fixture(({ root, env }) => {
  const eas = join(root, 'eas');
  const java = join(root, 'java');
  writeFileSync(java, '#!/bin/sh\nexit 1\n', { mode: 0o700 });
  writeFileSync(join(root, 'eas.json'), JSON.stringify({ cli: { version: '>= 20.5.1' } }));
  const environment = { ...env, PATH: `${root}:${env.PATH}`, EAS_BIN: eas };
  for (const version of ['16.28.0', '20.5.0']) {
    writeFileSync(eas, `#!/bin/sh\necho eas-cli/${version} test\n`, { mode: 0o700 });
    assert.throws(() => preflightRelease(root, environment), /does not satisfy >= 20.5.1/);
  }
  for (const version of ['20.5.1', '24.8.0']) {
    writeFileSync(eas, `#!/bin/sh\necho eas-cli/${version} test\n`, { mode: 0o700 });
    assert.throws(() => preflightRelease(root, environment), /Java is unavailable/);
  }
  assert.throws(() => preflightRelease(root, environment, '>= 25.0.0'), /does not satisfy >= 25.0.0/);
  writeFileSync(eas, '#!/bin/sh\necho eas-cli/25.0.0-beta.1 test\n', { mode: 0o700 });
  assert.throws(() => preflightRelease(root, environment), /Cannot read a stable EAS CLI version/);
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
    writeFileSync(join(bin, 'git'), '#!/bin/sh\nif [ "$1" = rev-parse ]; then echo abcdef1234567890; fi\nif [ "$1" = show ]; then echo \'{"cli":{"version":">= 20.5.1"}}\'; fi\n', { mode: 0o700 });
    const marker = join(root, 'ledger-called');
    writeFileSync(join(bin, 'python3'), `#!/bin/sh\ntouch '${marker}'\nexit 1\n`, { mode: 0o700 });
    const eas = join(bin, 'eas');
    writeFileSync(eas, '#!/bin/sh\necho eas-cli/16.28.0 test\n', { mode: 0o700 });
    for (const overrides of [{ ANDROID_HOME: '' }, { EAS_BIN: eas }]) {
      const result = spawnSync(process.execPath, [fileURLToPath(new URL('../scripts/nightly-local-release.mjs', import.meta.url)), 'run'], {
        env: { ...env, PATH: `${bin}:${env.PATH}`, ...overrides, NIGHTLY_HOST_LOCKED: '1', RELEASE_ARTIFACTS_DIR: root }, encoding: 'utf8',
      });
      assert.equal(result.status, 1);
      if (overrides.EAS_BIN) assert.match(result.stderr, /EAS CLI 16.28.0 does not satisfy/);
      const { existsSync, readdirSync, readFileSync } = requireFs;
      assert.equal(existsSync(marker), false);
      const app = readdirSync(root).find((name) => ['zen-mode', 'moodinator'].includes(name));
      const record = JSON.parse(readFileSync(join(root, app, 'preflight-abcdef123456', 'release.json'), 'utf8'));
      assert.equal(record.failure.stage, 'preflight');
      assert.equal(record.versionCode, undefined);
    }
  });
});

test('nightly rejects missing fetched CLI minimums without local fallback or ledger access', async () => {
  const { spawnSync } = await import('node:child_process');
  const { fileURLToPath } = await import('node:url');
  fixture(({ root, env }) => {
    const bin = join(root, 'bin');
    mkdirSync(bin);
    const ledgerMarker = join(root, 'ledger-called');
    const javaMarker = join(root, 'java-called');
    writeFileSync(join(bin, 'python3'), `#!/bin/sh\ntouch '${ledgerMarker}'\nexit 1\n`, { mode: 0o700 });
    writeFileSync(join(bin, 'java'), `#!/bin/sh\ntouch '${javaMarker}'\nexit 1\n`, { mode: 0o700 });
    const eas = join(bin, 'eas');
    writeFileSync(eas, '#!/bin/sh\necho eas-cli/20.5.1 test\n', { mode: 0o700 });
    for (const config of [{ cli: {} }, { cli: { version: null } }, {}]) {
      writeFileSync(join(bin, 'git'), `#!/bin/sh\nif [ "$1" = rev-parse ]; then echo abcdef1234567890; fi\nif [ "$1" = show ]; then echo '${JSON.stringify(config)}'; fi\n`, { mode: 0o700 });
      const result = spawnSync(process.execPath, [fileURLToPath(new URL('../scripts/nightly-local-release.mjs', import.meta.url)), 'run'], {
        env: { ...env, PATH: `${bin}:${env.PATH}`, EAS_BIN: eas, NIGHTLY_HOST_LOCKED: '1', RELEASE_ARTIFACTS_DIR: root }, encoding: 'utf8',
      });
      assert.equal(result.status, 1);
      assert.match(result.stderr, /Expected fetched eas.json cli.version to specify a minimum/);
      assert.equal(requireFs.existsSync(ledgerMarker), false);
      assert.equal(requireFs.existsSync(javaMarker), false);
      const record = JSON.parse(requireFs.readFileSync(join(root, 'zen-mode', 'preflight-abcdef123456', 'release.json'), 'utf8'));
      assert.equal(record.failure.stage, 'preflight');
      assert.equal(record.versionCode, undefined);
    }
  });
});
