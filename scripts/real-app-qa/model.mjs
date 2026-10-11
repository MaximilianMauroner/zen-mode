// Pure parsing and safety rules for the real-app QA tool. The CLI in cli.mjs
// runs adb, aapt and apksigner; this module only interprets their output.

export const PLAY_PACKAGE = 'com.lab4code.zenmode';
const SERVICE_CLASS = 'com.maxmauroner.zenguard.ZenGuardAccessibilityService';
// The generated activity keeps the source namespace when the QA build adds an application ID suffix.
const MAIN_ACTIVITY = 'com.lab4code.zenmode.MainActivity';

// Certificate of the debug keystore in Expo's Android template. The QA build type
// signs with it on purpose; the release signing guard rejects it for release artifacts.
export const QA_SIGNER_SHA256 = 'fac61745dc0903786fb9ede62a962b399f7348f0bb6f899b8332667591033b9c';

const GOOGLE_PLAY = 'com.android.vending';
// App signers recorded with apksigner from Chrome 133 and YouTube 19.17.42 in the
// API 36 Google Play emulator image. Their shared Play source stamp is not a signer.
const CHROME_SIGNER = 'f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83';
const YOUTUBE_SIGNER = '3d7a1223019aa39d9ea0e3436ab7c0896bfb4fb679f4de5fe7c23f326c8f994a';
// Mozilla publishes it in https://accounts.firefox.com/.well-known/assetlinks.json.
const MOZILLA_FIREFOX_SIGNER = 'a78b62a5165b4494b2fead9e76a280d22d937fee6251aece599446b2ea319b04';

// `origin` is how an authentic copy reaches the device: `play` (installed by the system
// Google Play, see targetProblem), `system` (in the system image, with `signer`) or
// `apk` (an official APK with `signer`).
export const TARGETS = {
  youtube: { package: 'com.google.android.youtube', signIn: false, origin: 'system', signer: YOUTUBE_SIGNER },
  instagram: { package: 'com.instagram.android', signIn: true, origin: 'play' },
  x: { package: 'com.twitter.android', signIn: true, origin: 'play' },
  chrome: { package: 'com.android.chrome', signIn: false, origin: 'system', signer: CHROME_SIGNER },
  firefox: { package: 'org.mozilla.firefox', signIn: false, origin: 'apk', signer: MOZILLA_FIREFOX_SIGNER },
};

// Acceptance journeys for the open PRs. Steps and expected results are in docs/real-app-testing.md.
export const JOURNEYS = [
  { id: 'pr76-editor-error', pr: 76, issue: 73, targets: [] },
  { id: 'pr76-usage-on-resume', pr: 76, issue: 69, targets: [] },
  { id: 'pr77-firefox-address-bar', pr: 77, issue: 67, targets: ['firefox'] },
  { id: 'pr77-reels-deadline', pr: 77, issue: 71, targets: ['instagram'] },
  { id: 'pr77-x-search-under-break', pr: 77, issue: 70, targets: ['x'] },
  { id: 'pr78-site-stats', pr: 78, issue: 72, targets: ['chrome', 'firefox'] },
  { id: 'pr78-instagram-stats', pr: 78, issue: 72, targets: ['instagram'] },
  { id: 'pr78-domain-error', pr: 78, issue: 68, targets: [] },
  { id: 'pr79-reels-close-audio', pr: 79, issue: 74, targets: ['instagram', 'youtube'] },
];

/** Names, package and service of one QA install. Each ID gets separate storage and its own service grant. */
export function qaIdentity(id) {
  if (!/^[a-z0-9]{1,24}$/.test(id ?? '')) {
    throw new Error(`QA ID must be 1-24 lowercase letters or digits, got ${JSON.stringify(id)}`);
  }
  const packageName = `${PLAY_PACKAGE}.qa${id}`;
  return {
    id,
    package: packageName,
    label: `Zen QA ${id}`,
    serviceLabel: `Zen QA ${id} protection`,
    serviceComponent: `${packageName}/${SERVICE_CLASS}`,
    launchComponent: `${packageName}/${MAIN_ACTIVITY}`,
  };
}

export function deviceState(devicesOutput, serial) {
  const line = devicesOutput.split('\n').slice(1).map((row) => row.trim().split(/\s+/))
    .find(([name]) => name === serial);
  return line?.[1] ?? null;
}

export function parseBadging(text) {
  const header = /^package: (.*)$/m.exec(text)?.[1] ?? '';
  const attribute = (name) => new RegExp(`(?:^| )${name}='([^']*)'`).exec(header)?.[1];
  const versionCode = Number(attribute('versionCode'));
  if (!attribute('name') || !Number.isInteger(versionCode)) {
    throw new Error('aapt did not report a package name and version code');
  }
  return {
    package: attribute('name'),
    versionCode,
    versionName: attribute('versionName') ?? null,
    split: attribute('split') ?? null,
    label: /^application-label:'(.*)'$/m.exec(text)?.[1] ?? null,
    debuggable: /^application-debuggable$/m.test(text),
  };
}

/**
 * App signers from `apksigner verify --print-certs`. Only `Signer #N` lines count: the
 * `Source Stamp Signer` certificate records the distribution channel and does not sign the app.
 */
export function parseSignerDigests(apksignerOutput) {
  const digests = [...apksignerOutput.matchAll(/^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$/gm)]
    .map((match) => match[1].toLowerCase());
  if (!digests.length) throw new Error('apksigner reported no SHA-256 signer certificate');
  return [...new Set(digests)].sort();
}

/**
 * Checks that supplied files form one installable app: one base APK, optional
 * splits of the same package and version, and one signer set. Only a target
 * with an official APK can be installed this way, signed by its pinned signer.
 */
export function checkTargetApks(files) {
  if (!files.length) throw new Error('Pass at least one --apk');
  const [first] = files;
  for (const file of files) {
    if (file.package !== first.package || file.versionCode !== first.versionCode) {
      throw new Error(`${file.file} is ${file.package} (${file.versionCode}); expected ${first.package} (${first.versionCode})`);
    }
    if (file.signers.join() !== first.signers.join()) throw new Error(`${file.file} has a different signer set`);
  }
  const bases = files.filter((file) => !file.split);
  if (bases.length !== 1) throw new Error(`Expected exactly one base APK, found ${bases.length}`);
  const key = Object.keys(TARGETS).find((name) => TARGETS[name].package === first.package);
  if (TARGETS[key]?.origin !== 'apk') {
    const apkTargets = Object.keys(TARGETS).filter((name) => TARGETS[name].origin === 'apk');
    throw new Error(`install-target installs only the official APK of ${apkTargets.join(', ')}, not ${first.package}`);
  }
  if (!first.signers.includes(TARGETS[key].signer)) {
    throw new Error(`Signer ${first.signers.join(', ')} is not the official ${key} signer`);
  }
  return {
    package: first.package,
    versionCode: first.versionCode,
    versionName: bases[0].versionName,
    signers: first.signers,
  };
}

/**
 * Reads the active package section of `dumpsys package <name>`, before any hidden system copy.
 * `installer` is the installing package, which adb can name freely. `initiator` is the package
 * that started the install; Android records the shell for adb, and prints it from Android 14.
 */
export function parsePackageDump(text, packageName) {
  const start = text.indexOf(`Package [${packageName}]`);
  if (start < 0) throw new Error(`dumpsys has no package section for ${packageName}`);
  const rest = text.slice(start + 1);
  const end = rest.search(/\n\s*Package \[|\nHidden system packages:/);
  const section = end < 0 ? rest : rest.slice(0, end);
  const flags = /\bflags=\[([^\]]*)\]/.exec(section)?.[1].trim().split(/\s+/) ?? [];
  const field = (name) => {
    const value = new RegExp(`\\b${name}=(\\S+)`).exec(section)?.[1];
    return value && value !== 'null' ? value : null;
  };
  return {
    versionCode: Number(/versionCode=(\d+)/.exec(section)?.[1]),
    versionName: /versionName=(\S+)/.exec(section)?.[1] ?? null,
    installer: field('installerPackageName'),
    initiator: field('initiatingPackageName'),
    system: flags.includes('SYSTEM'),
    updatedSystemApp: flags.includes('UPDATED_SYSTEM_APP'),
    lastUpdateTime: /lastUpdateTime=([^\n]+)/.exec(section)?.[1].trim() ?? null,
  };
}

/** Window records from `dumpsys window windows`, top of the z-order first. */
export function parseWindowRecords(text) {
  return text.split(/(?=^ {2}Window #\d+ Window\{)/m).map((record) => ({
    index: Number(/^ {2}Window #(\d+)/m.exec(record)?.[1]),
    ownerPackage: /\bpackage=(\S+)/.exec(record)?.[1] ?? null,
    type: /\bty=(\S+)/.exec(record)?.[1] ?? null,
    shown: /\bisOnScreen=true\b/.test(record) && /\bisVisible=true\b/.test(record),
  })).filter((record) => Number.isInteger(record.index)).sort((a, b) => a.index - b.index);
}

export function overlayShown(records, packageName) {
  return records.some((record) => record.shown && record.ownerPackage === packageName &&
    record.type === 'ACCESSIBILITY_OVERLAY');
}

export function foregroundPackage(records) {
  return records.find((record) => record.shown &&
    ['BASE_APPLICATION', 'APPLICATION'].includes(record.type))?.ownerPackage ?? null;
}

/** Adds or removes only this component and keeps every other enabled service. Returns null for an empty list. */
export function editServiceList(current, component, enable) {
  const services = (current ?? '').trim().split(':').filter((entry) => entry && entry !== 'null');
  const others = services.filter((entry) => entry !== component);
  const next = enable ? [...others, component] : others;
  return next.length ? next.join(':') : null;
}

export function serviceEnabled(settingValue, component) {
  return (settingValue ?? '').trim().split(':').includes(component);
}

/** True when AccessibilityManager reports the QA service as bound, not just listed in settings. */
export function serviceBound(accessibilityDump, identity) {
  const bound = /^\s*Bound services:(.*)$/m.exec(accessibilityDump)?.[1] ?? '';
  return bound.includes(`label=${identity.serviceLabel},`) || bound.includes(identity.serviceComponent);
}

const PLAYBACK_STATES = ['NONE', 'STOPPED', 'PAUSED', 'PLAYING'];

/** Playback states of the media sessions that one package owns, from `dumpsys media_session`. */
export function mediaStates(text, packageName) {
  const states = [];
  let owner = null;
  for (const line of text.split('\n')) {
    owner = /^\s+package=(\S+)\s*$/.exec(line)?.[1] ?? owner;
    const state = /state=PlaybackState \{state=(?:([A-Z_]+)\()?(\d+)/.exec(line);
    if (state && owner === packageName) states.push(state[1] ?? PLAYBACK_STATES[Number(state[2])] ?? `STATE_${state[2]}`);
  }
  return states;
}

export const EXPECTATIONS = ['overlay', 'no-overlay', 'cleared'];

// Longer than any journey and far below Node's timer limit of about 24.8 days.
const MAX_OBSERVE_SECONDS = 3600;

/** Parses `--seconds`, `--interval` and `--settle`: decimal seconds from 0 to one hour, and an interval above 0. */
export function observeTiming({ seconds = '10', interval = '1', settle = '3' }) {
  const timing = Object.fromEntries(Object.entries({ seconds, interval, settle }).map(([name, text]) => {
    const value = /^\d+(\.\d+)?$/.test(text) ? Number(text) : NaN;
    if (!(value <= MAX_OBSERVE_SECONDS)) {
      throw new Error(`--${name} must be a number of seconds from 0 to ${MAX_OBSERVE_SECONDS}, got ${JSON.stringify(text)}`);
    }
    return [name, value];
  }));
  if (timing.interval === 0) throw new Error('--interval must be above 0');
  return timing;
}

/**
 * Takes the samples of one observation in time order, on one clock. `sample(ms)`,
 * `open()` and `sleep(ms)` do the device work. Times count from the first sample.
 *
 * The observation lasts `seconds` from when `open()` returns, so a slow launch does
 * not shorten it. Sleeps stop at that end. The last sample starts at or after the end,
 * also when the sample before it started earlier and finished after the end.
 *
 * For `cleared`, the first sample comes before the target opens, because a correct
 * dismissal can finish before `am start -W` returns. Without the bound QA overlay
 * over the target at that moment, the target does not open: BLOCKED. The later
 * times of `cleared` include the launch.
 */
export async function takeSamples({ seconds, interval, expect, foreground, sample, open, sleep, now = Date.now }) {
  let started = now();
  const elapsed = () => now() - started;
  const samples = [];
  if (expect === 'cleared') {
    const initial = { ...await sample(elapsed()), beforeOpen: true };
    if (!initial.bound || !initial.overlay || initial.foreground !== foreground) {
      throw new Error(`BLOCKED: --expect cleared needs the bound QA overlay over ${foreground} before the URL opens`);
    }
    samples.push(initial);
  }
  await open?.();
  // Without a sample before the launch, the first sample is the next one.
  if (!samples.length) started = now();
  const end = elapsed() + seconds * 1000;
  for (;;) {
    const at = elapsed();
    samples.push(await sample(at));
    if (at >= end) return samples;
    const remaining = end - elapsed();
    if (remaining > 0) await sleep(Math.min(interval * 1000, remaining));
  }
}

/**
 * Result of one observation. Samples taken while the QA service was unbound, or
 * after the QA app crashed, prove nothing: INVALID. With a target app, every
 * sample must show that app in front, so a failed intent or chooser cannot pass.
 * Without an expectation the operator judges the evidence: OBSERVED.
 *
 * `overlay`: the QA overlay shows at the end. `no-overlay`: it never shows.
 * `cleared`: it shows before the target opens and is gone at the end.
 */
export function observationResult(samples, { expect, foreground, crashed }) {
  if (!samples.length) throw new Error('No samples were recorded');
  if (crashed || samples.some((sample) => !sample.bound)) return 'INVALID';
  if (foreground && samples.some((sample) => sample.foreground !== foreground)) return 'INVALID';
  if (!expect) return 'OBSERVED';
  const last = samples.at(-1).overlay;
  const met = {
    overlay: last,
    'no-overlay': samples.every((sample) => !sample.overlay),
    cleared: Boolean(samples[0].beforeOpen && samples[0].overlay) && !last,
  }[expect];
  if (met === undefined) throw new Error(`--expect must be ${EXPECTATIONS.join(', ')}, got ${expect}`);
  return met ? 'PASS' : 'FAIL';
}

const sameFields = (recorded, current) => Object.entries(current).every(([key, value]) => recorded?.[key] === value);

/**
 * An absent overlay only means something after the same QA build blocked the same
 * target APK on this device. Otherwise a service that never reads the app also
 * passes. An emulator serial alone is not the device: other AVDs reuse it.
 */
export function hasPositiveControl(evidenceRecords, { app, device, build, target }) {
  return evidenceRecords.some((record) => record.command === 'observe' && record.app === app &&
    record.openUrl && record.expect === 'overlay' && record.result === 'PASS' &&
    sameFields(record.device, device) && sameFields(record.build, build) &&
    record.target?.baseSha256 === target.baseSha256);
}

/**
 * Why an installed target is not the authentic app, or null. A valid signature
 * alone proves nothing, because anyone can sign an APK with the same package name.
 *
 * A Play target needs Google Play as installer and as initiator, and that Google Play
 * (`initiatorSystem`) must be in the system image. `adb install -i com.android.vending`
 * sets only the installer, and another app can take the Google Play package name only
 * on an image without Google Play. Without a recorded initiator, as before Android 14,
 * the target is refused.
 */
export function targetProblem(key, app) {
  const { origin, signer } = TARGETS[key];
  if (!app?.installed) return 'not installed';
  if (!app.signers?.length) return 'signer not recorded';
  if (origin === 'play' && app.installer !== GOOGLE_PLAY) return 'not installed by Google Play';
  if (origin === 'play' && (app.initiator !== GOOGLE_PLAY || !app.initiatorSystem)) {
    return 'install not started by the system Google Play';
  }
  if (origin === 'system' && !app.system) return 'not in the system image';
  if (signer && !app.signers.includes(signer)) return 'not signed by the official signer';
  return null;
}

/** A journey is READY only with the matching QA build bound and every target authentic, present and signed in. */
export function journeyReadiness(journey, { qa, targets, signedIn }) {
  const reasons = [];
  if (!qa.installed) reasons.push('QA build not installed');
  else if (!qa.matchesBuild) reasons.push('installed QA APK differs from this build');
  else if (!qa.access.enabled || !qa.access.bound) reasons.push('QA service not enabled and bound');
  for (const key of journey.targets) {
    const problem = targetProblem(key, targets[key]);
    if (problem) reasons.push(`${key} ${problem}`);
    else if (TARGETS[key].signIn && !signedIn.includes(key)) reasons.push(`${key} sign-in not confirmed`);
  }
  return { ...journey, status: reasons.length ? 'BLOCKED' : 'READY', reasons };
}
