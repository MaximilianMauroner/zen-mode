// Pure parsing and safety rules for the real-app QA tool. The CLI in cli.mjs
// runs adb, aapt and apksigner; this module only interprets their output.

export const PLAY_PACKAGE = 'com.lab4code.zenmode';
const SERVICE_CLASS = 'com.maxmauroner.zenguard.ZenGuardAccessibilityService';
// The generated activity keeps the source namespace when the QA build adds an application ID suffix.
const MAIN_ACTIVITY = 'com.lab4code.zenmode.MainActivity';

// Certificate of the debug keystore in Expo's Android template. The QA build type
// signs with it on purpose; the release signing guard rejects it for release artifacts.
export const QA_SIGNER_SHA256 = 'fac61745dc0903786fb9ede62a962b399f7348f0bb6f899b8332667591033b9c';

export const TARGETS = {
  youtube: { package: 'com.google.android.youtube', signIn: false },
  instagram: { package: 'com.instagram.android', signIn: true },
  x: { package: 'com.twitter.android', signIn: true },
  chrome: { package: 'com.android.chrome', signIn: false },
  firefox: { package: 'org.mozilla.firefox', signIn: false },
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

export function normalizeSha256(value) {
  const normalized = value?.replaceAll(':', '').toLowerCase();
  if (!/^[0-9a-f]{64}$/.test(normalized ?? '')) throw new Error(`Not a SHA-256 fingerprint: ${value}`);
  return normalized;
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

export function parseSignerDigests(apksignerOutput) {
  const digests = [...apksignerOutput.matchAll(/certificate SHA-256 digest:\s*([0-9a-f]{64})/gi)]
    .map((match) => match[1].toLowerCase());
  if (!digests.length) throw new Error('apksigner reported no SHA-256 signer certificate');
  return [...new Set(digests)].sort();
}

/**
 * Checks that supplied files form one installable app: one base APK, optional
 * splits of the same package and version, and one signer set. An expected
 * signer from an official source must be among the signers.
 */
export function checkTargetApks(files, expectedSigner) {
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
  if (expectedSigner && !first.signers.includes(normalizeSha256(expectedSigner))) {
    throw new Error(`Signer ${first.signers.join(', ')} does not match the expected official signer`);
  }
  return {
    package: first.package,
    versionCode: first.versionCode,
    versionName: bases[0].versionName,
    signers: first.signers,
  };
}

/** Reads the active package section of `dumpsys package <name>`, before any hidden system copy. */
export function parsePackageDump(text, packageName) {
  const start = text.indexOf(`Package [${packageName}]`);
  if (start < 0) throw new Error(`dumpsys has no package section for ${packageName}`);
  const rest = text.slice(start + 1);
  const end = rest.search(/\n\s*Package \[|\nHidden system packages:/);
  const section = end < 0 ? rest : rest.slice(0, end);
  const flags = /\bflags=\[([^\]]*)\]/.exec(section)?.[1].trim().split(/\s+/) ?? [];
  const installer = /installerPackageName=(\S+)/.exec(section)?.[1];
  return {
    versionCode: Number(/versionCode=(\d+)/.exec(section)?.[1]),
    versionName: /versionName=(\S+)/.exec(section)?.[1] ?? null,
    installer: installer && installer !== 'null' ? installer : null,
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

/**
 * Result of one observation. Samples taken while the QA service was unbound, or
 * after the QA app crashed, prove nothing: INVALID. With a target app, every
 * sample must show that app in front, so a failed intent or chooser cannot pass.
 * Without an expectation the operator judges the evidence: OBSERVED.
 *
 * `overlay`: the QA overlay shows at the end. `no-overlay`: it never shows.
 * `cleared`: it shows first and is gone at the end.
 */
export function observationResult(samples, { expect, foreground, crashed }) {
  if (!samples.length) throw new Error('No samples were recorded');
  if (crashed || samples.some((sample) => !sample.bound)) return 'INVALID';
  if (foreground && samples.some((sample) => sample.foreground !== foreground)) return 'INVALID';
  if (!expect) return 'OBSERVED';
  const first = samples[0].overlay;
  const last = samples.at(-1).overlay;
  const met = {
    overlay: last,
    'no-overlay': samples.every((sample) => !sample.overlay),
    cleared: first && !last,
  }[expect];
  if (met === undefined) throw new Error(`--expect must be ${EXPECTATIONS.join(', ')}, got ${expect}`);
  return met ? 'PASS' : 'FAIL';
}

/**
 * An absent overlay only means something after the same build blocked the same
 * app on this device. Otherwise a service that never reads the app also passes.
 */
export function hasPositiveControl(evidenceRecords, app) {
  return evidenceRecords.some((record) => record.command === 'observe' && record.app === app &&
    record.openUrl && record.expect === 'overlay' && record.result === 'PASS');
}

/** A journey is READY only with the matching QA build bound and every real target app present and signed in. */
export function journeyReadiness(journey, { qa, targets, signedIn }) {
  const reasons = [];
  if (!qa.installed) reasons.push('QA build not installed');
  else if (!qa.matchesBuild) reasons.push('installed QA APK differs from this build');
  else if (!qa.access.enabled || !qa.access.bound) reasons.push('QA service not enabled and bound');
  for (const key of journey.targets) {
    const target = targets[key];
    if (!target?.installed) reasons.push(`${key} not installed`);
    else if (!target.signers?.length) reasons.push(`${key} signer not recorded`);
    else if (TARGETS[key].signIn && !signedIn.includes(key)) reasons.push(`${key} sign-in not confirmed`);
  }
  return { ...journey, status: reasons.length ? 'BLOCKED' : 'READY', reasons };
}
