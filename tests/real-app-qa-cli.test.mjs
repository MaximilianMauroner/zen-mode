import test from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { qaIdentity } from '../scripts/real-app-qa/model.mjs';

const cli = resolve('scripts/real-app-qa/cli.mjs');
const qa = qaIdentity('cli1');
const firefoxSigner = 'a78b62a5165b4494b2fead9e76a280d22d937fee6251aece599446b2ea319b04';

// An emulator with the bound QA service and an official Firefox in front, in the formats
// of the real tools. FAKE_OVERLAY=1 shows the QA overlay. FAKE_TAG marks the screenshot
// and logcat bytes of one run.
const adb = `#!/bin/sh
[ "$1" = devices ] && { printf 'List of devices attached\\nemulator-5554\\tdevice\\n\\n'; exit 0; }
shift 2
window() {
  printf '  Window #%s Window{%s u0 %s}:\\n    package=%s appop=NONE\\n' "$1" "$1" "$2" "$2"
  printf '    mAttrs={(0,0)(fillxfill) ty=%s fmt=TRANSLUCENT}\\n    isOnScreen=true\\n    isVisible=true\\n' "$3"
}
case "$*" in
  'shell getprop') printf '[%s]: [%s]\\n' ro.product.manufacturer Google ro.product.model sdk_gphone64_arm64 \\
    ro.build.version.release 16 ro.build.version.sdk 36 ro.product.cpu.abi arm64-v8a \\
    ro.build.fingerprint google/sdk_gphone64_arm64/emu64a:16/BE2A/1:user/release-keys \\
    ro.kernel.qemu 1 ro.boot.qemu.avd_name zen-qa-api36 ;;
  "shell pm path $QA_PACKAGE" | 'shell pm path org.mozilla.firefox') echo "package:/data/app/$4/base.apk" ;;
  'shell dumpsys package '*) printf '  Package [%s] (1a2b):\\n    versionCode=5\\n    versionName=1.0\\n    flags=[ HAS_CODE ]\\n' "$4" ;;
  "shell sha256sum /data/app/$QA_PACKAGE/base.apk") echo "$QA_SHA256  $3" ;;
  'shell settings get secure enabled_accessibility_services') echo "$QA_SERVICE" ;;
  'shell dumpsys accessibility') echo "     Bound services:{Service[label=$QA_SERVICE_LABEL, feedbackType[FEEDBACK_GENERIC]]}" ;;
  'shell dumpsys window windows')
    [ "$FAKE_OVERLAY" = 1 ] && window 0 "$QA_PACKAGE" ACCESSIBILITY_OVERLAY
    window 1 org.mozilla.firefox BASE_APPLICATION ;;
  'shell am start '*) echo 'Status: ok' ;;
  'shell date '*) echo '10-11 14:00:00.000' ;;
  'pull '*) printf 'firefox apk' > "$3" ;;
  'exec-out screencap -p') printf 'screenshot %s' "$FAKE_TAG" ;;
  'logcat -b crash '*) ;;
  'logcat '*) echo "logcat $FAKE_TAG" ;;
  *) echo "unexpected adb $*" >&2; exit 1 ;;
esac
`;
const aapt = `#!/bin/sh\necho "package: name='org.mozilla.firefox' versionCode='2016186458' versionName='157.0'"\n`;
const apksigner = `#!/bin/sh\necho 'Signer #1 certificate SHA-256 digest: ${firefoxSigner}'\n`;
// Loaded before the CLI: stops its clock at FAKE_NOW and, with FAKE_UUID, makes every UUID the same.
const clock = `import crypto from 'node:crypto';
import { syncBuiltinESMExports } from 'node:module';
const now = Date.parse(process.env.FAKE_NOW);
globalThis.Date = class extends Date {
  constructor(...args) { super(...(args.length ? args : [now])); }
  static now() { return now; }
};
if (process.env.FAKE_UUID) {
  crypto.randomUUID = () => process.env.FAKE_UUID;
  syncBuiltinESMExports();
}
`;

function fixture() {
  const dir = mkdtempSync(join(tmpdir(), 'zen-qa-cli-'));
  const checkout = join(dir, 'checkout');
  const sdk = join(dir, 'sdk');
  const build = join(checkout, '.agents/artifacts/real-app-qa/builds', qa.id);
  mkdirSync(build, { recursive: true });
  mkdirSync(join(sdk, 'platform-tools'), { recursive: true });
  mkdirSync(join(sdk, 'build-tools/36.0.0'), { recursive: true });
  writeFileSync(join(sdk, 'platform-tools/adb'), adb, { mode: 0o755 });
  writeFileSync(join(sdk, 'build-tools/36.0.0/aapt'), aapt, { mode: 0o755 });
  writeFileSync(join(sdk, 'build-tools/36.0.0/apksigner'), apksigner, { mode: 0o755 });
  writeFileSync(join(dir, 'clock.mjs'), clock);
  writeFileSync(join(build, 'zen-qacli1.apk'), 'qa apk');
  const sha256 = createHash('sha256').update('qa apk').digest('hex');
  writeFileSync(join(build, 'build.json'), JSON.stringify({
    source: { head: '1'.repeat(40) },
    apk: { file: 'zen-qacli1.apk', sha256, package: qa.package, versionName: '0.1.5-qa.1111111' },
  }));
  const runs = join(checkout, '.agents/artifacts/real-app-qa/runs', `${qa.id}-emulator-5554`);

  const observe = (args, { overlay = false, tag = 'run', now, uuid } = {}) => spawnSync(process.execPath, [
    ...now ? ['--import', pathToFileURL(join(dir, 'clock.mjs')).href] : [],
    cli, 'observe', '--build', qa.id, '--serial', 'emulator-5554', '--settle', '0', '--seconds', '0', ...args,
  ], {
    cwd: checkout, encoding: 'utf8',
    env: { PATH: process.env.PATH, ANDROID_HOME: sdk, QA_PACKAGE: qa.package, QA_SHA256: sha256,
      QA_SERVICE: qa.serviceComponent, QA_SERVICE_LABEL: qa.serviceLabel, FAKE_OVERLAY: overlay ? '1' : '0',
      FAKE_TAG: tag, ...now && { FAKE_NOW: now }, ...uuid && { FAKE_UUID: uuid } },
  });
  const records = () => readFileSync(join(runs, 'evidence.jsonl'), 'utf8').split('\n').filter(Boolean)
    .map((line) => JSON.parse(line));
  const contents = () => Object.fromEntries(readdirSync(runs).map((file) => [file, readFileSync(join(runs, file), 'utf8')]));
  return { dir, runs, observe, records, contents };
}

const openFirefox = (observe, url, expect, label, overlay) =>
  observe(['--open-url', url, '--app', 'firefox', '--expect', expect, '--label', label], { overlay });

test('an earlier overlay PASS with the same build, device and target does not let an absent overlay pass', () => {
  const { dir, observe, records } = fixture();
  try {
    // A live overlay is judged both ways.
    let run = openFirefox(observe, 'https://example.com', 'overlay', 'blocked', true);
    assert.equal(run.status, 0, run.stderr);
    assert.match(run.stdout, /^PASS blocked/);
    run = openFirefox(observe, 'https://example.org', 'no-overlay', 'allowed-overlay', true);
    assert.equal(run.status, 1, run.stderr);
    assert.match(run.stdout, /^FAIL allowed-overlay/);
    // After `pm clear` or Sites off the rule is gone, but the PASS record stays. That record can
    // also come from another rule, so it cannot make the absent overlay a PASS.
    run = openFirefox(observe, 'https://example.org', 'no-overlay', 'allowed-after-reset', false);
    assert.equal(run.status, 0, run.stderr);
    assert.match(run.stdout, /^OBSERVED allowed-after-reset/);

    const observed = records();
    assert.deepEqual(observed.map(({ label, expect, result }) => [label, expect, result]), [
      ['blocked', 'overlay', 'PASS'],
      ['allowed-overlay', 'no-overlay', 'FAIL'],
      ['allowed-after-reset', 'no-overlay', 'OBSERVED'],
    ]);
    const subject = ({ device, build, target }) => ({ device, build, target });
    assert.deepEqual(subject(observed[2]), subject(observed[0]), 'the PASS record is for this exact build, device and APK');
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('without earlier records, an absent overlay is for the operator to judge', () => {
  const { dir, observe, records } = fixture();
  try {
    const run = openFirefox(observe, 'https://example.org', 'no-overlay', 'allowed', false);
    assert.equal(run.status, 0, run.stderr);
    assert.match(run.stdout, /^OBSERVED allowed: 0ms no overlay \(org\.mozilla\.firefox\); .*absent overlay by hand/);
    assert.deepEqual(records().map(({ expect, result }) => [expect, result]), [['no-overlay', 'OBSERVED']]);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('two observations with one label in one second keep their own evidence', () => {
  const { dir, runs, observe, records } = fixture();
  try {
    for (const tag of ['first', 'second']) {
      const run = observe(['--label', 'capture'], { tag, now: '2026-10-11T14:00:00.250Z' });
      assert.equal(run.status, 0, run.stderr);
    }
    const [first, second] = records();
    assert.equal(first.at, second.at, 'one clock');
    for (const [record, tag] of [[first, 'first'], [second, 'second']]) {
      for (const file of record.screenshots) assert.equal(readFileSync(join(runs, file), 'utf8'), `screenshot ${tag}`);
      assert.match(readFileSync(join(runs, record.logcat), 'utf8'), new RegExp(`^logcat ${tag}\\n`));
      assert.equal(JSON.parse(readFileSync(join(runs, record.timeline), 'utf8')).length, 1);
    }
    const files = (record) => [...record.screenshots, record.timeline, record.logcat];
    assert.ok([...files(first), ...files(second)].every((file) => file.startsWith('20261011T140000-capture-')));
    assert.equal(new Set([...files(first), ...files(second)]).size, files(first).length + files(second).length);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('an observation whose file name exists stops without replacing evidence or adding a record', () => {
  const { dir, observe, records, contents } = fixture();
  try {
    const same = { now: '2026-10-11T14:00:00.250Z', uuid: '11111111-2222-4333-8444-555555555555' };
    assert.equal(observe(['--label', 'capture'], { ...same, tag: 'first' }).status, 0);
    const before = contents();
    const run = observe(['--label', 'capture'], { ...same, tag: 'second' });
    assert.equal(run.status, 1);
    assert.match(run.stderr, /EEXIST.*20261011T140000-capture-11111111-00-start\.png/);
    assert.deepEqual(contents(), before, 'the first files keep their bytes and no file is added');
    assert.equal(records().length, 1);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});
