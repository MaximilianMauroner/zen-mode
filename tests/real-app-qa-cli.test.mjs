import test from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import {
  appendFileSync, copyFileSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { PLAY_PACKAGE, QA_SIGNER_SHA256, qaIdentity } from '../scripts/real-app-qa/model.mjs';

const toolDir = resolve('scripts/real-app-qa');
const cli = join(toolDir, 'cli.mjs');
const qa = qaIdentity('cli1');
const firefoxSigner = 'a78b62a5165b4494b2fead9e76a280d22d937fee6251aece599446b2ea319b04';
const fileHash = (path) => createHash('sha256').update(readFileSync(path)).digest('hex');
const toolHashes = (dir) => ({ cli: fileHash(join(dir, 'cli.mjs')), model: fileHash(join(dir, 'model.mjs')) });

// An emulator with the bound QA service and an official Firefox in front, in the formats
// of the real tools. FAKE_OVERLAY=1 shows the QA overlay. FAKE_TAG marks the screenshot
// and logcat bytes of one run. FAKE_CALLS gets each adb command. When FAKE_LOSE_AT
// (screencap or launch) runs, FAKE_LOSE (overlay, foreground or binding) is lost.
const adb = `#!/bin/sh
[ "$1" = devices ] && { printf 'List of devices attached\\nemulator-5554\\tdevice\\n\\n'; exit 0; }
shift 2
echo "$*" >> "$FAKE_CALLS"
lose() { [ "$FAKE_LOSE_AT" = "$1" ] && : > "$FAKE_LOST"; }
lost() { [ "$FAKE_LOSE" = "$1" ] && [ -e "$FAKE_LOST" ]; }
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
  'shell dumpsys accessibility')
    if lost binding; then echo '     Bound services:{}'
    else echo "     Bound services:{Service[label=$QA_SERVICE_LABEL, feedbackType[FEEDBACK_GENERIC]]}"; fi ;;
  'shell dumpsys window windows')
    [ "$FAKE_OVERLAY" = 1 ] && ! lost overlay && window 0 "$QA_PACKAGE" ACCESSIBILITY_OVERLAY
    if lost foreground; then window 1 com.google.android.apps.nexuslauncher BASE_APPLICATION
    else window 1 org.mozilla.firefox BASE_APPLICATION; fi ;;
  'shell am start '*) lose launch; echo 'Status: ok' ;;
  'shell date '*) echo '10-11 14:00:00.000' ;;
  'pull '*) printf 'firefox apk' > "$3" ;;
  'exec-out screencap -p') lose screencap; printf 'screenshot %s' "$FAKE_TAG" ;;
  'logcat -b crash '*) ;;
  'logcat '*) echo "logcat $FAKE_TAG" ;;
  *) echo "unexpected adb $*" >&2; exit 1 ;;
esac
`;
// aapt and apksigner describe the fake QA APK or the Firefox APK, by its bytes.
const aapt = `#!/bin/sh
if [ "$(cat "$3")" = 'qa apk' ]; then
  echo "package: name='$QA_PACKAGE' versionCode='1' versionName='0.1.5-qa.$(git rev-parse HEAD | cut -c1-7)'"
  echo "application-label:'$QA_LABEL'"
else
  echo "package: name='org.mozilla.firefox' versionCode='2016186458' versionName='157.0'"
fi
`;
const apksigner = `#!/bin/sh
signer=${firefoxSigner}
[ "$(cat "$3")" = 'qa apk' ] && signer=${QA_SIGNER_SHA256}
echo "Signer #1 certificate SHA-256 digest: $signer"
`;
// For build: prebuild does nothing, and Gradle records its arguments and writes the fake QA APK.
const gradlew = `#!/bin/sh
printf '%s\\n' "$@" > "$FAKE_GRADLE_ARGS"
mkdir -p app/build/outputs/apk/qa && printf 'qa apk' > app/build/outputs/apk/qa/app-qa.apk
`;
const java = `#!/bin/sh\necho 'openjdk version "21.0.5" 2024-10-15' >&2\n`;
const gitEnv = { GIT_CONFIG_GLOBAL: '/dev/null', GIT_CONFIG_NOSYSTEM: '1', GIT_AUTHOR_NAME: 'QA',
  GIT_AUTHOR_EMAIL: 'qa@example.com', GIT_COMMITTER_NAME: 'QA', GIT_COMMITTER_EMAIL: 'qa@example.com' };
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

// A clean commit of a Zen Mode checkout that build can build with the fake prebuild and Gradle.
function commitSource(dir, checkout) {
  mkdirSync(join(checkout, 'node_modules/expo/bin'), { recursive: true });
  mkdirSync(join(checkout, 'android'));
  mkdirSync(join(dir, 'jdk/bin'), { recursive: true });
  writeFileSync(join(checkout, 'app.json'), JSON.stringify({ expo: { android: { package: PLAY_PACKAGE } } }));
  writeFileSync(join(checkout, '.gitignore'), '/node_modules/\n/android/\n/.agents/\n');
  writeFileSync(join(checkout, 'node_modules/expo/bin/cli'), '');
  writeFileSync(join(checkout, 'android/gradlew'), gradlew, { mode: 0o755 });
  writeFileSync(join(dir, 'jdk/bin/java'), java, { mode: 0o755 });
  for (const args of [['init', '-q'], ['add', '.'], ['commit', '-q', '-m', 'source']]) {
    const git = spawnSync('git', args, { cwd: checkout, encoding: 'utf8', env: { PATH: process.env.PATH, ...gitEnv } });
    assert.equal(git.status, 0, git.stderr);
  }
}

// A copy of the tool outside Git, as in another checkout, with `changes` added to the end of its files.
function toolCopy(dir, name, changes = {}) {
  const copy = join(dir, name);
  mkdirSync(copy);
  for (const file of ['cli.mjs', 'model.mjs', 'qa-build-type.init.gradle']) {
    copyFileSync(join(toolDir, file), join(copy, file));
    if (changes[file]) appendFileSync(join(copy, file), changes[file]);
  }
  return copy;
}

// With `built`, the checkout has a QA build whose build.json is from before the tool recorded
// its builder. Without it, the checkout is a clean commit for the build command.
function fixture({ built = true } = {}) {
  const dir = mkdtempSync(join(tmpdir(), 'zen-qa-cli-'));
  const checkout = join(dir, 'checkout');
  const sdk = join(dir, 'sdk');
  const build = join(checkout, '.agents/artifacts/real-app-qa/builds', qa.id);
  mkdirSync(checkout);
  mkdirSync(join(sdk, 'platform-tools'), { recursive: true });
  mkdirSync(join(sdk, 'build-tools/36.0.0'), { recursive: true });
  writeFileSync(join(sdk, 'platform-tools/adb'), adb, { mode: 0o755 });
  writeFileSync(join(sdk, 'build-tools/36.0.0/aapt'), aapt, { mode: 0o755 });
  writeFileSync(join(sdk, 'build-tools/36.0.0/apksigner'), apksigner, { mode: 0o755 });
  writeFileSync(join(dir, 'clock.mjs'), clock);
  const sha256 = createHash('sha256').update('qa apk').digest('hex');
  if (built) {
    mkdirSync(build, { recursive: true });
    writeFileSync(join(build, 'zen-qacli1.apk'), 'qa apk');
    writeFileSync(join(build, 'build.json'), JSON.stringify({
      source: { head: '1'.repeat(40) },
      apk: { file: 'zen-qacli1.apk', sha256, package: qa.package, versionName: '0.1.5-qa.1111111' },
    }));
  } else {
    commitSource(dir, checkout);
  }
  const runs = join(checkout, '.agents/artifacts/real-app-qa/runs', `${qa.id}-emulator-5554`);
  const env = { PATH: process.env.PATH, ANDROID_HOME: sdk, QA_PACKAGE: qa.package, QA_LABEL: qa.label,
    QA_SHA256: sha256, QA_SERVICE: qa.serviceComponent, QA_SERVICE_LABEL: qa.serviceLabel };

  const gradleArgs = join(dir, 'gradle-args.txt');
  // Builds with the CLI `tool` and returns the process result and the arguments that Gradle got.
  const buildWith = (tool) => {
    const run = spawnSync(process.execPath, [tool, 'build', '--id', qa.id], {
      cwd: checkout, encoding: 'utf8',
      env: { ...env, ...gitEnv, JAVA_HOME: join(dir, 'jdk'), FAKE_GRADLE_ARGS: gradleArgs },
    });
    return { ...run, gradle: run.status === 0 ? readFileSync(gradleArgs, 'utf8').split('\n') : [] };
  };
  const calls = join(dir, 'calls.txt');
  const lost = join(dir, 'lost');
  // Returns the process result and the adb commands of this run with the CLI `tool`.
  const observe = (args, { tool = cli, overlay = false, tag = 'run', now, uuid, lose, loseAt } = {}) => {
    rmSync(calls, { force: true });
    rmSync(lost, { force: true });
    const run = spawnSync(process.execPath, [
      ...now ? ['--import', pathToFileURL(join(dir, 'clock.mjs')).href] : [],
      tool, 'observe', '--build', qa.id, '--serial', 'emulator-5554', '--settle', '0', '--seconds', '0', ...args,
    ], {
      cwd: checkout, encoding: 'utf8',
      env: { ...env, FAKE_OVERLAY: overlay ? '1' : '0',
        FAKE_TAG: tag, FAKE_CALLS: calls, FAKE_LOST: lost, ...lose && { FAKE_LOSE: lose, FAKE_LOSE_AT: loseAt },
        ...now && { FAKE_NOW: now }, ...uuid && { FAKE_UUID: uuid } },
    });
    return { ...run, calls: readFileSync(calls, 'utf8').split('\n').filter(Boolean) };
  };
  const buildRecord = () => readFileSync(join(build, 'build.json'), 'utf8');
  const records = () => readFileSync(join(runs, 'evidence.jsonl'), 'utf8').split('\n').filter(Boolean)
    .map((line) => JSON.parse(line));
  const contents = () => Object.fromEntries(readdirSync(runs).map((file) => [file, readFileSync(join(runs, file), 'utf8')]));
  return { dir, runs, buildWith, observe, buildRecord, records, contents };
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

test('cleared opens the target only over a live overlay, and a cleared overlay never passes', () => {
  const { dir, observe, records } = fixture();
  const clear = (label, lose, loseAt) => observe(['--open-url', 'https://example.org', '--app', 'firefox',
    '--expect', 'cleared', '--label', label], { overlay: true, lose, loseAt });
  const lastCheck = ['shell dumpsys accessibility', 'shell dumpsys window windows'];
  try {
    // The first sample sees the overlay. It is lost during the start screenshot, before the launch.
    for (const lose of ['overlay', 'foreground', 'binding']) {
      const run = clear(`lost-${lose}`, lose, 'screencap');
      assert.equal(run.status, 1, lose);
      assert.match(run.stderr, /^BLOCKED: right before the launch the bound QA overlay was not over org\.mozilla\.firefox/, lose);
      assert.ok(!run.calls.some((call) => call.startsWith('shell am start')), `${lose}: the URL does not open`);
      assert.deepEqual(run.calls.slice(-lastCheck.length), lastCheck, `${lose}: binding first, windows last`);
    }

    // The overlay is lost at the launch, after the last check. A dismissal and a break that
    // ended look the same, so the operator judges it.
    const run = clear('lost-at-launch', 'overlay', 'launch');
    assert.equal(run.status, 0, run.stderr);
    assert.match(run.stdout, /^OBSERVED lost-at-launch: 0ms overlay \(org\.mozilla\.firefox\) -> \d+ms no overlay \(org\.mozilla\.firefox\); .*cleared overlay by hand/);
    const launch = run.calls.findIndex((call) => call.startsWith('shell am start'));
    assert.deepEqual(run.calls.slice(launch - 3, launch), ['exec-out screencap -p', ...lastCheck],
      'no screenshot or media work between the last check and the launch');
    assert.deepEqual(records().map(({ label, expect, result }) => [label, expect, result]),
      [['lost-at-launch', 'cleared', 'OBSERVED']], 'a BLOCKED run writes no record');
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

test('build.json keeps the tool that built it, and a later command records its own tool', () => {
  const { dir, buildWith, observe, buildRecord, records } = fixture({ built: false });
  try {
    // The builder is a changed copy outside Git and outside the checkout under test.
    const builder = toolCopy(dir, 'builder', { 'cli.mjs': '// local change\n', 'qa-build-type.init.gradle': '// local change\n' });
    const run = buildWith(join(builder, 'cli.mjs'));
    assert.equal(run.status, 0, run.stderr);
    const { tool: built } = JSON.parse(buildRecord());
    assert.deepEqual(built, { ...toolHashes(builder), initScript: fileHash(join(builder, 'qa-build-type.init.gradle')) });
    assert.equal(fileHash(run.gradle[run.gradle.indexOf('--init-script') + 1]), built.initScript,
      'the recorded init script is the one that Gradle got');

    // The builder and then the tool of this checkout capture the same build and device.
    for (const tool of [builder, toolDir]) {
      const capture = observe(['--label', 'capture'], { tool: join(tool, 'cli.mjs') });
      assert.equal(capture.status, 0, capture.stderr);
    }
    const [byBuilder, later] = records();
    assert.deepEqual(byBuilder.tool, toolHashes(builder));
    assert.deepEqual(later.tool, toolHashes(toolDir), 'the later tool records itself, not the builder');
    assert.notEqual(later.tool.cli, built.cli);
    for (const record of [byBuilder, later]) assert.deepEqual(record.build.tool, built);
    assert.deepEqual([later.device, later.build], [byBuilder.device, byBuilder.build]);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('an older build.json stays usable with an unknown builder, and each tool copy is told apart by its bytes', () => {
  const { dir, observe, buildRecord, records } = fixture();
  try {
    const before = buildRecord();
    // This tool, an unchanged copy in another directory, and a copy with a changed model.
    const tools = [toolDir, toolCopy(dir, 'same'), toolCopy(dir, 'changed', { 'model.mjs': '// local change\n' })];
    for (const tool of tools) {
      const run = observe(['--open-url', 'https://example.com', '--app', 'firefox', '--expect', 'overlay',
        '--label', 'blocked'], { tool: join(tool, 'cli.mjs'), overlay: true });
      assert.equal(run.status, 0, run.stderr);
      assert.match(run.stdout, /^PASS blocked/);
    }
    const observed = records();
    assert.deepEqual(observed.map((record) => record.tool), tools.map(toolHashes),
      'each record names the tool that wrote it; a copy with the same bytes is the same tool');
    assert.notEqual(observed[2].tool.model, observed[0].tool.model);
    const subject = ({ device, build, target }) => ({ device, build, target });
    for (const record of observed) {
      assert.equal(record.build.tool, null, 'the builder is unknown, not the tool that wrote the record');
      assert.deepEqual(subject(record), subject(observed[0]));
    }
    assert.equal(buildRecord(), before, 'build.json gets no tool');
  } finally { rmSync(dir, { recursive: true, force: true }); }
});
