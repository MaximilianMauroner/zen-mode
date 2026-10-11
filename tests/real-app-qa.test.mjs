import test from 'node:test';
import assert from 'node:assert/strict';
import {
  PLAY_PACKAGE, QA_SIGNER_SHA256, JOURNEYS, checkTargetApks, deviceState, editServiceList, foregroundPackage,
  hasPositiveControl, journeyReadiness, mediaStates, observationResult, observeTiming, overlayShown, parseBadging,
  parsePackageDump, parseSignerDigests, parseWindowRecords, qaIdentity, serviceBound, serviceEnabled, takeSamples,
  targetProblem,
} from '../scripts/real-app-qa/model.mjs';

const firefoxSigner = 'a78b62a5165b4494b2fead9e76a280d22d937fee6251aece599446b2ea319b04';
const googleSystemSigner = '3257d599a49d2c961a471ca9843f59d341a405884583fc087df4237b733bbd6d';
const qa77 = qaIdentity('77');

test('QA identities never use the Play package and keep separate service names', () => {
  assert.equal(qa77.package, 'com.lab4code.zenmode.qa77');
  assert.notEqual(qa77.package, PLAY_PACKAGE);
  assert.equal(qa77.serviceComponent, 'com.lab4code.zenmode.qa77/com.maxmauroner.zenguard.ZenGuardAccessibilityService');
  assert.equal(qa77.launchComponent, 'com.lab4code.zenmode.qa77/com.lab4code.zenmode.MainActivity');
  for (const id of ['', 'PR77', 'qa.77', '../77', undefined]) assert.throws(() => qaIdentity(id), /QA ID/);
});

test('adb device selection requires the exact authorized serial', () => {
  const devices = 'List of devices attached\nemulator-5554\tdevice\nR5CY1234\tunauthorized\n\n';
  assert.equal(deviceState(devices, 'emulator-5554'), 'device');
  assert.equal(deviceState(devices, 'R5CY1234'), 'unauthorized');
  assert.equal(deviceState(devices, 'emulator-555'), null);
});

test('aapt and apksigner output give package, split and signer identity', () => {
  const base = parseBadging([
    "package: name='org.mozilla.firefox' versionCode='2016131151' versionName='157.0' platformBuildVersionName='16' compileSdkVersionCodename='16'",
    "application-label:'Firefox'",
  ].join('\n'));
  assert.deepEqual(base, { package: 'org.mozilla.firefox', versionCode: 2016131151, versionName: '157.0',
    split: null, label: 'Firefox', debuggable: false });
  const split = parseBadging("package: name='com.instagram.android' versionCode='384000123' split='config.arm64_v8a'\napplication-debuggable");
  assert.equal(split.split, 'config.arm64_v8a');
  assert.equal(split.versionName, null);
  assert.equal(split.debuggable, true);
  assert.throws(() => parseBadging('ERROR: dump failed'), /package name/);

  const signer = parseSignerDigests([
    'Signer #1 certificate DN: CN=Android Debug, O=Android, C=US',
    `Signer #1 certificate SHA-256 digest: ${QA_SIGNER_SHA256.toUpperCase()}`,
    'Signer #1 certificate SHA-1 digest: 5e8f16062ea3cd2c4a0d547876baa6f38cabf625',
  ].join('\n'));
  assert.deepEqual(signer, [QA_SIGNER_SHA256]);
  assert.throws(() => parseSignerDigests('DOES NOT VERIFY'), /no SHA-256 signer/);
});

test('target APK sets must be one app and one signer set, signed by the pinned official signer', () => {
  const file = (name, overrides = {}) => ({ file: name, package: 'org.mozilla.firefox', versionCode: 5,
    versionName: '157.0', split: null, signers: [firefoxSigner], ...overrides });
  const splits = [file('base.apk'), file('split_config.arm64_v8a.apk', { split: 'config.arm64_v8a', versionName: null })];
  assert.deepEqual(checkTargetApks(splits), { package: 'org.mozilla.firefox', versionCode: 5, versionName: '157.0',
    signers: [firefoxSigner] });
  assert.throws(() => checkTargetApks([]), /at least one/);
  assert.throws(() => checkTargetApks([splits[0], file('other.apk', { package: 'com.example', split: 'config.en' })]), /expected org\.mozilla/);
  assert.throws(() => checkTargetApks([splits[0], file('re-signed.apk', { split: 'config.en', signers: ['c'.repeat(64)] })]), /different signer/);
  assert.throws(() => checkTargetApks([splits[0], file('second-base.apk')]), /exactly one base/);

  // A valid signature is not authenticity: a synthetic Firefox signed with the Expo debug key is refused.
  assert.throws(() => checkTargetApks([file('fenix.apk', { signers: [QA_SIGNER_SHA256] })]), /not the official firefox signer/);
  // Play and system-image targets are never sideloaded, even with a plausible signer.
  assert.throws(() => checkTargetApks([file('ig.apk', { package: 'com.instagram.android' })]), /only the official APK of firefox/);
  assert.throws(() => checkTargetApks([file('chrome.apk', { package: 'com.android.chrome', signers: [googleSystemSigner] })]),
    /only the official APK/);
});

test('targets are authentic only through their official origin', () => {
  const app = (overrides) => ({ installed: true, installer: null, system: false, ...overrides });
  // Signers as recorded for Chrome on the API 36 Google Play emulator.
  const chrome = app({ system: true, signers: [googleSystemSigner, 'f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83'] });
  assert.equal(targetProblem('chrome', chrome), null);
  assert.equal(targetProblem('youtube', chrome), null);
  assert.equal(targetProblem('firefox', app({ signers: [firefoxSigner] })), null);

  assert.equal(targetProblem('firefox', app({ signers: [QA_SIGNER_SHA256] })), 'not signed by the official signer');
  assert.equal(targetProblem('chrome', app({ signers: [googleSystemSigner] })), 'not in the system image');
  assert.equal(targetProblem('chrome', app({ system: true, signers: [QA_SIGNER_SHA256] })), 'not signed by the official signer');
  assert.equal(targetProblem('x', app({ signers: ['b'.repeat(64)] })), 'not installed by Google Play', 'sideloaded');
  assert.equal(targetProblem('x', app({ installer: 'com.android.vending', signatureError: 'DOES NOT VERIFY' })), 'signer not recorded');
  assert.equal(targetProblem('x', { installed: false }), 'not installed');
});

// The install source lines of `dumpsys package` on Android 16 (API 36), in the order that
// Settings.dumpPackageLPr prints them. Android 13 and earlier print only installerPackageName.
const installSource = ({ installer, initiator }) => [
  `    installerPackageName=${installer}`,
  '    installerPackageUid=10140',
  ...initiator === undefined ? [] : [`    initiatingPackageName=${initiator}`, '    originatingPackageName=null'],
  '    packageSource=0',
].join('\n');

test('package inventory reads the active version and install source, not the hidden system copy', () => {
  const dump = `Packages:
  Package [com.android.chrome] (1a2b):
    userId=10150
    versionCode=731500033 minSdk=29 targetSdk=35
    versionName=131.0.6778.39
    flags=[ SYSTEM HAS_CODE ALLOW_CLEAR_USER_DATA UPDATED_SYSTEM_APP ALLOW_BACKUP ]
    privateFlags=[ PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_RESIZEABLE ]
    timeStamp=2026-10-01 10:00:00
    lastUpdateTime=2026-10-01 10:00:00
${installSource({ installer: 'com.android.vending', initiator: 'com.android.vending' })}

Hidden system packages:
  Package [com.android.chrome] (5e6f):
    versionCode=681000033 minSdk=29 targetSdk=34
    versionName=124.0.6367.82
    flags=[ SYSTEM HAS_CODE ]
`;
  assert.deepEqual(parsePackageDump(dump, 'com.android.chrome'), {
    versionCode: 731500033, versionName: '131.0.6778.39', installer: 'com.android.vending',
    initiator: 'com.android.vending', system: true, updatedSystemApp: true, lastUpdateTime: '2026-10-01 10:00:00',
  });
  assert.throws(() => parsePackageDump(dump, 'org.mozilla.firefox'), /no package section/);
});

test('a Play target needs the system Google Play as installer and as the package that started the install', () => {
  const dumpOf = (packageName, flags, source = '') => `  Package [${packageName}] (7c1d):
    versionCode=384000123 minSdk=28 targetSdk=35
    versionName=384.0.0.40.97
    flags=[ ${flags} ]
    lastUpdateTime=2026-10-11 09:00:00
${source}`;
  // What cli.mjs records: the target dump, and whether the initiator's own dump has the SYSTEM flag.
  const target = (source, initiatorDump = dumpOf('com.android.vending', 'SYSTEM HAS_CODE UPDATED_SYSTEM_APP')) => {
    const app = parsePackageDump(dumpOf('com.instagram.android', 'HAS_CODE ALLOW_CLEAR_USER_DATA', source), 'com.instagram.android');
    return { ...app, installed: true, signers: ['b'.repeat(64)],
      initiatorSystem: Boolean(app.initiator && parsePackageDump(initiatorDump, app.initiator).system) };
  };
  const play = installSource({ installer: 'com.android.vending', initiator: 'com.android.vending' });
  assert.equal(targetProblem('instagram', target(play)), null);

  // `adb install -i com.android.vending` names Play as installer, but Android records the shell as initiator.
  const spoofed = target(installSource({ installer: 'com.android.vending', initiator: 'com.android.shell' }),
    dumpOf('com.android.shell', 'SYSTEM HAS_CODE'));
  assert.equal(spoofed.installer, 'com.android.vending');
  assert.equal(targetProblem('instagram', spoofed), 'install not started by the system Google Play');
  // An app that took the Play package name, on an image without Google Play.
  assert.equal(targetProblem('x', target(play, dumpOf('com.android.vending', 'HAS_CODE ALLOW_CLEAR_USER_DATA'))),
    'install not started by the system Google Play');
  // No recorded initiator, as on Android 13 and earlier: fail closed.
  assert.equal(targetProblem('x', target(installSource({ installer: 'com.android.vending' }))),
    'install not started by the system Google Play');
  assert.equal(targetProblem('x', target(installSource({ installer: 'com.android.vending', initiator: 'null' }))),
    'install not started by the system Google Play');
  assert.equal(targetProblem('x', target(installSource({ installer: 'null', initiator: 'com.android.vending' }))),
    'not installed by Google Play');
});

test('window sampling only counts a visible accessibility overlay owned by this QA package', () => {
  const window = (index, owner, type, shown = true) => `  Window #${index} Window{${index} u0 ${owner}}:
    mOwnerUid=10190 showForAllUsers=false package=${owner} appop=NONE
    mAttrs={(0,0)(fillxfill) ty=${type} fmt=TRANSLUCENT}
    isOnScreen=${shown}
    isVisible=${shown}
`;
  const firefoxOnly = parseWindowRecords(`WINDOW MANAGER WINDOWS (dumpsys window windows)
${window(0, 'com.android.systemui', 'NAVIGATION_BAR')}${window(1, 'org.mozilla.firefox', 'BASE_APPLICATION')}`);
  assert.equal(overlayShown(firefoxOnly, qa77.package), false);
  assert.equal(foregroundPackage(firefoxOnly), 'org.mozilla.firefox');

  const blocked = parseWindowRecords(`${window(0, qa77.package, 'ACCESSIBILITY_OVERLAY')}${window(1, 'org.mozilla.firefox', 'BASE_APPLICATION')}`);
  assert.equal(overlayShown(blocked, qa77.package), true);
  assert.equal(overlayShown(blocked, PLAY_PACKAGE), false, 'the Play install overlay is not QA evidence');
  const hidden = parseWindowRecords(window(0, qa77.package, 'ACCESSIBILITY_OVERLAY', false));
  assert.equal(overlayShown(hidden, qa77.package), false);
});

test('service grants change only the QA component', () => {
  const play = `${PLAY_PACKAGE}/com.maxmauroner.zenguard.ZenGuardAccessibilityService`;
  const talkback = 'com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService';
  assert.equal(editServiceList(`${play}:${talkback}`, qa77.serviceComponent, true), `${play}:${talkback}:${qa77.serviceComponent}`);
  assert.equal(editServiceList(`${play}:${qa77.serviceComponent}`, qa77.serviceComponent, true), `${play}:${qa77.serviceComponent}`);
  assert.equal(editServiceList(`${qa77.serviceComponent}:${play}`, qa77.serviceComponent, false), play);
  assert.equal(editServiceList(qa77.serviceComponent, qa77.serviceComponent, false), null);
  assert.equal(editServiceList('null', qa77.serviceComponent, true), qa77.serviceComponent);
  assert.equal(serviceEnabled(`${play}\n`, qa77.serviceComponent), false);
  assert.equal(serviceEnabled(`${play}:${qa77.serviceComponent}\n`, qa77.serviceComponent), true);
});

test('readiness needs the service bound, not only enabled in settings', () => {
  const dump = (bound) => `User state[
     attributes:{id=0, touchExplorationEnabled=false}
     Bound services:{${bound}}
     Enabled services:{{${qa77.serviceComponent}}}
     Binding services:{}`;
  assert.equal(serviceBound(dump('Service[label=Zen QA 77 protection, feedbackType[FEEDBACK_GENERIC], capabilities=1]'), qa77), true);
  assert.equal(serviceBound(dump(''), qa77), false, 'enabled but suspended or unbound');
  assert.equal(serviceBound(dump('Service[label=Zen QA 7 protection, feedbackType[FEEDBACK_GENERIC]]'), qa77), false);
  assert.equal(serviceBound(dump('Service[label=Zen QA 77 protection, feedbackType[FEEDBACK_GENERIC]]'), qaIdentity('7')), false);
});

test('media session state is read per package in both Android formats', () => {
  const dump = `  Sessions Stack - have 2 sessions:
    com.google.android.youtube/youtube (userId=0)
      ownerPid=4321, ownerUid=10140, userId=0
      package=com.google.android.youtube
      active=true
      state=PlaybackState {state=PAUSED(2), position=12000, buffered position=0, speed=0.0}
    com.instagram.android/ig (userId=0)
      package=com.instagram.android
      state=PlaybackState {state=3, position=0, buffered position=0, speed=1.0}
`;
  assert.deepEqual(mediaStates(dump, 'com.google.android.youtube'), ['PAUSED']);
  assert.deepEqual(mediaStates(dump, 'com.instagram.android'), ['PLAYING']);
  assert.deepEqual(mediaStates(dump, 'com.spotify.music'), []);
});

test('observations pass only with the bound service and the opened target in front', () => {
  const firefox = 'org.mozilla.firefox';
  const samples = (...overlays) => overlays.map((overlay, ms) => ({ ms, overlay, bound: true, foreground: firefox }));
  const judge = (list, expect) => observationResult(list, { expect, foreground: firefox, crashed: false });
  assert.equal(judge(samples(false, true, true), 'overlay'), 'PASS');
  assert.equal(judge(samples(true, false), 'overlay'), 'FAIL');
  assert.equal(judge(samples(false, false), 'no-overlay'), 'PASS');
  assert.equal(judge(samples(false, true, false), 'no-overlay'), 'FAIL');
  const beforeOpen = (list) => list.map((sample, index) => index ? sample : { ...sample, beforeOpen: true });
  assert.equal(judge(beforeOpen(samples(true, true, false)), 'cleared'), 'PASS');
  assert.equal(judge(samples(true, true, false), 'cleared'), 'FAIL', 'an overlay seen only after opening is not cleared');
  assert.equal(judge(beforeOpen(samples(false, false)), 'cleared'), 'FAIL');
  assert.throws(() => judge(samples(true), 'block'), /--expect/);

  const chooser = samples(false, false).map((sample, index) => index ? sample : { ...sample, foreground: 'android' });
  assert.equal(judge(chooser, 'no-overlay'), 'INVALID', 'a chooser or failed intent cannot pass');
  const launcher = samples(false, false).map((sample) => ({ ...sample, foreground: 'com.google.android.apps.nexuslauncher' }));
  assert.equal(judge(launcher, 'no-overlay'), 'INVALID');
  const suspended = samples(false, false, false).map((sample, index) => ({ ...sample, bound: index !== 1 }));
  assert.equal(judge(suspended, 'no-overlay'), 'INVALID', 'a hierarchy dump during sampling suspends the service');
  assert.equal(observationResult(samples(true), { expect: 'overlay', foreground: firefox, crashed: true }), 'INVALID');
  const manual = samples(false, true).map((sample) => ({ ...sample, foreground: 'com.instagram.android' }));
  assert.equal(observationResult(manual, { crashed: false }), 'OBSERVED');
});

test('cleared keeps the overlay seen before the target opens, so an immediate dismissal passes', async () => {
  const x = 'com.twitter.android';
  // A fake device whose overlay closes as soon as the target opens, before any later sample.
  const device = ({ overlay = true, foreground = x } = {}) => {
    const calls = [];
    let clock = 0;
    let opened = false;
    const options = {
      seconds: 2, interval: 1, expect: 'cleared', foreground: x, now: () => clock,
      sleep: async (ms) => { calls.push(`sleep ${ms}`); clock += ms; },
      open: async () => { calls.push('open'); opened = true; },
      sample: (ms) => {
        calls.push(`sample ${ms}`);
        return { ms, bound: true, foreground: opened ? x : foreground, overlay: !opened && overlay };
      },
    };
    return { calls, options };
  };

  const dismissed = device();
  const timeline = await takeSamples(dismissed.options);
  assert.deepEqual(dismissed.calls, ['sample 0', 'open', 'sample 0', 'sleep 1000', 'sample 1000', 'sleep 1000', 'sample 2000']);
  assert.deepEqual(timeline.map((sample) => sample.overlay), [true, false, false, false]);
  const judge = (list) => observationResult(list, { expect: 'cleared', foreground: x, crashed: false });
  assert.equal(judge(timeline), 'PASS');
  assert.equal(judge(timeline.slice(1)), 'FAIL', 'samples after opening alone cannot prove the overlay was there');

  for (const before of [{ overlay: false }, { foreground: 'com.google.android.apps.nexuslauncher' }]) {
    const blocked = device(before);
    await assert.rejects(takeSamples(blocked.options), /BLOCKED: --expect cleared needs the bound QA overlay/);
    assert.deepEqual(blocked.calls, ['sample 0'], `${JSON.stringify(before)}: the target must not open`);
  }
});

test('observation timing is finite and bounded, and the last sample is at the end', async () => {
  assert.deepEqual(observeTiming({}), { seconds: 10, interval: 1, settle: 3 });
  assert.deepEqual(observeTiming({ seconds: '0', interval: '0.5', settle: '0' }), { seconds: 0, interval: 0.5, settle: 0 });
  assert.equal(observeTiming({ seconds: '320' }).seconds, 320);
  for (const bad of [{ seconds: 'Infinity' }, { seconds: '1e400' }, { seconds: '1e3' }, { seconds: '3601' },
    { seconds: '' }, { seconds: '-1' }, { settle: 'NaN' }, { interval: '0' }, { interval: '2147484' }]) {
    assert.throws(() => observeTiming(bad), /^Error: --(seconds|interval|settle) must/, JSON.stringify(bad));
  }

  const run = async (seconds, interval, overlayFrom = Infinity) => {
    let clock = 0;
    const sleeps = [];
    const samples = await takeSamples({
      seconds, interval, now: () => clock,
      sleep: async (ms) => { sleeps.push(ms); clock += ms; },
      sample: (ms) => ({ ms, bound: true, foreground: null, overlay: ms >= overlayFrom }),
    });
    return { sleeps, at: samples.map((sample) => sample.ms), samples };
  };
  const long = await run(15, 60, 10_000);
  assert.deepEqual([long.sleeps, long.at], [[15_000], [0, 15_000]], 'the interval does not outlast --seconds');
  assert.equal(observationResult(long.samples, { expect: 'overlay', crashed: false }), 'PASS', 'the endpoint is judged');
  const uneven = await run(2.5, 1);
  assert.deepEqual([uneven.sleeps, uneven.at], [[1000, 1000, 500], [0, 1000, 2000, 2500]]);
  const capture = await run(0, 1);
  assert.deepEqual([capture.sleeps, capture.at], [[], [0]]);
});

test('a slow launch neither shortens the observation nor shifts its times', async () => {
  const firefox = 'org.mozilla.firefox';
  // `am start -W` returns after 20 s. On the device clock, the overlay is up before the
  // launch, gone when it returns, and back 5 s later, inside `--seconds 15`.
  const observe = async (expect) => {
    let clock = 0;
    let opened = false;
    const taken = [];
    const samples = await takeSamples({
      seconds: 15, interval: 5, expect, foreground: firefox, now: () => clock,
      open: async () => { clock += 20_000; opened = true; },
      sleep: async (ms) => { clock += ms; },
      sample: (ms) => {
        taken.push(clock);
        return { ms, bound: true, foreground: firefox, overlay: !opened || clock >= 25_000 };
      },
    });
    return { taken, at: samples.map((sample) => sample.ms),
      result: observationResult(samples, { expect, foreground: firefox, crashed: false }) };
  };
  // Sampling runs the full 15 s after the launch returns, and its times start at the first of those samples.
  const afterLaunch = [20_000, 25_000, 30_000, 35_000];
  assert.deepEqual(await observe('overlay'), { taken: afterLaunch, at: [0, 5_000, 10_000, 15_000], result: 'PASS' });
  assert.deepEqual(await observe('no-overlay'), { taken: afterLaunch, at: [0, 5_000, 10_000, 15_000], result: 'FAIL' });
  // `cleared` keeps its sample before the launch first, and later times count from it.
  assert.deepEqual(await observe('cleared'), { taken: [0, ...afterLaunch], at: [0, ...afterLaunch], result: 'FAIL' });
});

test('an absent overlay counts only after the same build blocked the same app version on this device', () => {
  const device = { serial: 'emulator-5554', model: 'sdk_gphone64_arm64', sdk: 36, emulator: true, avd: 'zen-qa-api36',
    fingerprint: 'google/sdk_gphone64_arm64/emu64a:16/BE2A.250530.026.D1/13818094:user/release-keys' };
  const build = { id: 'pr77a', package: qa77.package, source: '1'.repeat(40), versionName: '0.1.5-qa.1111111',
    apkSha256: 'a'.repeat(64) };
  const target = { package: 'org.mozilla.firefox', versionCode: 2016186458, baseSha256: 'f'.repeat(64) };
  const current = { app: 'firefox', device, build, target };
  const observe = (overrides) => ({ command: 'observe', app: 'firefox', openUrl: 'https://example.com',
    expect: 'overlay', result: 'PASS', device, build, target, ...overrides });
  assert.equal(hasPositiveControl([observe()], current), true);
  assert.equal(hasPositiveControl([observe()], { ...current, app: 'chrome' }), false);
  for (const overrides of [
    { result: 'FAIL' }, { result: 'INVALID' }, { expect: 'no-overlay' }, { openUrl: null, app: null },
    { device: { ...device, avd: 'other-avd' } },
    { device: { ...device, fingerprint: 'google/sdk_gphone64_arm64/emu64a:15/AP3A/1:user/release-keys' } },
    { build: { ...build, apkSha256: 'b'.repeat(64) } },
    { target: { ...target, baseSha256: 'e'.repeat(64) } },
    { device: undefined, build: undefined, target: undefined },
  ]) {
    assert.equal(hasPositiveControl([observe(overrides)], current), false, JSON.stringify(overrides));
  }
  assert.equal(hasPositiveControl([{ command: 'install-target', app: 'firefox', result: 'PASS', device, build }], current), false);
});

test('journeys stay blocked until every target is authentic and sign-in is confirmed', () => {
  const qa = { installed: true, matchesBuild: true, access: { enabled: true, bound: true } };
  const system = { installed: true, system: true, installer: null, signers: [googleSystemSigner] };
  const firefox = { installed: true, system: false, installer: null, signers: [firefoxSigner] };
  const play = { installed: true, system: false, installer: 'com.android.vending', initiator: 'com.android.vending',
    initiatorSystem: true, signers: ['b'.repeat(64)] };
  const journey = (id) => JOURNEYS.find((entry) => entry.id === id);
  const browsersOnly = { chrome: system, firefox, youtube: system, instagram: { installed: false } };

  assert.equal(journeyReadiness(journey('pr77-firefox-address-bar'), { qa, targets: browsersOnly, signedIn: [] }).status, 'READY');
  assert.deepEqual(journeyReadiness(journey('pr77-firefox-address-bar'), {
    qa, targets: { ...browsersOnly, firefox: { ...firefox, signers: [QA_SIGNER_SHA256] } }, signedIn: [],
  }).reasons, ['firefox not signed by the official signer'], 'a synthetic Firefox is not READY');
  assert.deepEqual(journeyReadiness(journey('pr79-reels-close-audio'), { qa, targets: browsersOnly, signedIn: [] }).reasons,
    ['instagram not installed']);
  assert.deepEqual(journeyReadiness(journey('pr77-reels-deadline'), {
    qa, targets: { ...browsersOnly, instagram: { ...play, installer: null } }, signedIn: ['instagram'],
  }).reasons, ['instagram not installed by Google Play']);
  const withInstagram = { ...browsersOnly, instagram: play };
  assert.deepEqual(journeyReadiness(journey('pr77-reels-deadline'), { qa, targets: withInstagram, signedIn: [] }).reasons,
    ['instagram sign-in not confirmed']);
  assert.equal(journeyReadiness(journey('pr77-reels-deadline'), { qa, targets: withInstagram, signedIn: ['instagram'] }).status, 'READY');
  assert.deepEqual(journeyReadiness(journey('pr78-site-stats'), {
    qa: { ...qa, access: { enabled: true, bound: false } }, targets: browsersOnly, signedIn: [],
  }).reasons, ['QA service not enabled and bound']);
  assert.deepEqual(journeyReadiness(journey('pr78-site-stats'), {
    qa, targets: { ...browsersOnly, firefox: { installed: true, signatureError: 'DOES NOT VERIFY' } }, signedIn: [],
  }).reasons, ['firefox signer not recorded']);
  assert.deepEqual(new Set(JOURNEYS.map((entry) => entry.pr)), new Set([76, 77, 78, 79]));
});
