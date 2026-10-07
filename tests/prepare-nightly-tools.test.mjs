import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';

const script = resolve('scripts/prepare-nightly-tools.sh');

function fixture() {
  const dir = mkdtempSync(join(tmpdir(), 'zen-sdk-'));
  const sdk = join(dir, 'android sdk');
  const bin = join(dir, 'bin');
  mkdirSync(join(sdk, 'cmdline-tools/latest/bin'), { recursive: true });
  mkdirSync(join(dir, 'scripts'));
  mkdirSync(bin);
  writeFileSync(join(dir, 'scripts/prepare-nightly-space.sh'), 'exit 0\n');
  writeFileSync(join(sdk, 'cmdline-tools/latest/bin/sdkmanager'), '#!/bin/bash\nprintf "%s\\n" "$@" > "$SDK_CALLS"\n', { mode: 0o755 });
  for (const command of ['curl', 'sha256sum', 'npm']) {
    writeFileSync(join(bin, command), '#!/bin/bash\nexit 0\n', { mode: 0o755 });
  }
  const env = { PATH: `${bin}:/usr/bin:/bin`, ANDROID_HOME: sdk,
    GITHUB_ENV: join(dir, 'env'), GITHUB_PATH: join(dir, 'path'),
    SDK_CALLS: join(dir, 'calls'), ANDROID_BUNDLETOOL_JAR: join(dir, 'bundletool.jar') };
  return { dir, sdk, env };
}

for (const phase of ['eas', '']) {
  test(`hosted SDK setup works without sdkmanager on PATH (${phase || 'verification'})`, () => {
    const { dir, sdk, env } = fixture();
    try {
      const result = spawnSync('/bin/bash', [script, phase], { cwd: dir, env, encoding: 'utf8' });
      assert.equal(result.status, 0, result.stderr);
      assert.equal(readFileSync(env.SDK_CALLS, 'utf8'), `--sdk_root=${sdk}\nplatforms;android-36\nbuild-tools;36.0.0\n`);
      assert.equal(readFileSync(env.GITHUB_ENV, 'utf8'), `ANDROID_HOME=${sdk}\nANDROID_SDK_ROOT=${sdk}\n`);
      assert.equal(readFileSync(env.GITHUB_PATH, 'utf8'), `${sdk}/cmdline-tools/latest/bin\n${sdk}/platform-tools\n`);
    } finally { rmSync(dir, { recursive: true, force: true }); }
  });
}

test('conflicting SDK aliases fail before invoking the manager', () => {
  const { dir, env } = fixture();
  try {
    const result = spawnSync('/bin/bash', [script], { cwd: dir,
      env: { ...env, ANDROID_SDK_ROOT: join(dir, 'other-sdk') }, encoding: 'utf8' });
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /must match/);
    assert.throws(() => readFileSync(env.SDK_CALLS));
  } finally { rmSync(dir, { recursive: true, force: true }); }
});
