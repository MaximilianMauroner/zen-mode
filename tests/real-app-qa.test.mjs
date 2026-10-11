import test from 'node:test';
import assert from 'node:assert/strict';
import {
  PLAY_PACKAGE, QA_SIGNER_SHA256, JOURNEYS, checkTargetApks, deviceState, editServiceList, foregroundPackage,
  hasPositiveControl, journeyReadiness, mediaStates, observationResult, overlayShown, parseBadging,
  parsePackageDump, parseSignerDigests, parseWindowRecords, qaIdentity, serviceBound, serviceEnabled,
} from '../scripts/real-app-qa/model.mjs';

const firefoxSigner = 'a78b62a5165b4494b2fead9e76a280d22d937fee6251aece599446b2ea319b04';
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

test('target APK sets must be one app, one signer and the expected official signer', () => {
  const file = (name, overrides = {}) => ({ file: name, package: 'com.instagram.android', versionCode: 5,
    versionName: '400.0', split: null, signers: ['b'.repeat(64)], ...overrides });
  const splits = [file('base.apk'), file('split_config.arm64_v8a.apk', { split: 'config.arm64_v8a', versionName: null })];
  assert.deepEqual(checkTargetApks(splits), { package: 'com.instagram.android', versionCode: 5, versionName: '400.0',
    signers: ['b'.repeat(64)] });
  assert.throws(() => checkTargetApks([]), /at least one/);
  assert.throws(() => checkTargetApks([splits[0], file('other.apk', { package: 'com.example', split: 'config.en' })]), /expected com\.instagram/);
  assert.throws(() => checkTargetApks([splits[0], file('re-signed.apk', { split: 'config.en', signers: ['c'.repeat(64)] })]), /different signer/);
  assert.throws(() => checkTargetApks([splits[0], file('second-base.apk')]), /exactly one base/);

  const firefox = [file('fenix.apk', { package: 'org.mozilla.firefox', signers: [firefoxSigner] })];
  const published = 'A7:8B:62:A5:16:5B:44:94:B2:FE:AD:9E:76:A2:80:D2:2D:93:7F:EE:62:51:AE:CE:59:94:46:B2:EA:31:9B:04';
  assert.equal(checkTargetApks(firefox, published).package, 'org.mozilla.firefox');
  assert.throws(() => checkTargetApks(firefox, QA_SIGNER_SHA256), /expected official signer/);
  assert.throws(() => checkTargetApks(firefox, 'not-a-digest'), /SHA-256/);
});

test('package inventory reads the active version, not the hidden system copy', () => {
  const dump = `Packages:
  Package [com.android.chrome] (1a2b):
    userId=10150
    versionCode=731500033 minSdk=29 targetSdk=35
    versionName=131.0.6778.39
    flags=[ SYSTEM HAS_CODE ALLOW_CLEAR_USER_DATA UPDATED_SYSTEM_APP ALLOW_BACKUP ]
    privateFlags=[ PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_RESIZEABLE ]
    lastUpdateTime=2026-10-01 10:00:00
    installerPackageName=com.android.vending

Hidden system packages:
  Package [com.android.chrome] (5e6f):
    versionCode=681000033 minSdk=29 targetSdk=34
    versionName=124.0.6367.82
    flags=[ SYSTEM HAS_CODE ]
`;
  assert.deepEqual(parsePackageDump(dump, 'com.android.chrome'), {
    versionCode: 731500033, versionName: '131.0.6778.39', installer: 'com.android.vending',
    system: true, updatedSystemApp: true, lastUpdateTime: '2026-10-01 10:00:00',
  });
  assert.throws(() => parsePackageDump(dump, 'org.mozilla.firefox'), /no package section/);
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
  assert.equal(judge(samples(true, true, false), 'cleared'), 'PASS');
  assert.equal(judge(samples(false, false), 'cleared'), 'FAIL');
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

test('an absent overlay counts only after the same app was blocked on this build and device', () => {
  const observe = (overrides) => ({ command: 'observe', app: 'firefox', openUrl: 'https://example.com',
    expect: 'overlay', result: 'PASS', ...overrides });
  assert.equal(hasPositiveControl([observe()], 'firefox'), true);
  assert.equal(hasPositiveControl([observe()], 'chrome'), false);
  for (const overrides of [{ result: 'FAIL' }, { result: 'INVALID' }, { expect: 'no-overlay' }, { openUrl: null, app: null }]) {
    assert.equal(hasPositiveControl([observe(overrides)], 'firefox'), false, JSON.stringify(overrides));
  }
  assert.equal(hasPositiveControl([{ command: 'install-target', app: 'firefox', result: 'PASS' }], 'firefox'), false);
});

test('login-dependent journeys stay blocked until the real app is present and sign-in is confirmed', () => {
  const qa = { installed: true, matchesBuild: true, access: { enabled: true, bound: true } };
  const app = { installed: true, signers: [firefoxSigner] };
  const journey = (id) => JOURNEYS.find((entry) => entry.id === id);
  const browsersOnly = { chrome: app, firefox: app, youtube: app, instagram: { installed: false } };

  assert.equal(journeyReadiness(journey('pr77-firefox-address-bar'), { qa, targets: browsersOnly, signedIn: [] }).status, 'READY');
  assert.deepEqual(journeyReadiness(journey('pr79-reels-close-audio'), { qa, targets: browsersOnly, signedIn: [] }).reasons,
    ['instagram not installed']);
  const withInstagram = { ...browsersOnly, instagram: app };
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
