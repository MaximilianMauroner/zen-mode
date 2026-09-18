import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

import { getActionError } from '../src/features/action-error.ts';
import { getConfiguredAppPresentation } from '../src/features/protection/app-rule-presentation.ts';
import { getAppLimitsPlatformState } from '../src/features/protection/app-limits-state.ts';
import { getAdultSitePresentation } from '../src/features/protection/adult-site-presentation.ts';
import { getFeedDrawerTargetPresentation } from '../src/features/protection/feed-drawer-presentation.ts';
import { getFeedStatus, getFeedStatusDetail, getProtectionReadiness } from '../src/features/protection/feed-status.ts';
import { getFeedPresentation } from '../src/features/protection/feed-presentation.ts';
import { getOverviewAction } from '../src/features/protection/overview-actions.ts';
import { checkedInstalledBrowserCount, getBrowserReadiness, getSupportedAppAvailability, getSupportedBrowserAvailability } from '../src/features/protection/target-availability.ts';

const overviewSource = readFileSync(new URL('../src/app/(controls)/index.tsx', import.meta.url), 'utf8');
const limitsSource = readFileSync(new URL('../src/app/(controls)/limits.tsx', import.meta.url), 'utf8');
const privacySource = readFileSync(new URL('../src/app/privacy.tsx', import.meta.url), 'utf8');
const feedDrawerSource = readFileSync(new URL('../src/components/ui/feed-controls-drawer.tsx', import.meta.url), 'utf8');
const adultSiteDrawerSource = readFileSync(new URL('../src/components/ui/adult-site-controls-drawer.tsx', import.meta.url), 'utf8');
const manifestSource = readFileSync(new URL('../modules/zen-guard/android/src/main/AndroidManifest.xml', import.meta.url), 'utf8');

const installedApps = {
  youtube: 'installed', instagram: 'installed', x: 'installed',
};
const installedBrowsers = {
  chrome: 'installed', samsungInternet: 'installed', opera: 'installed', firefox: 'installed',
};

const base = {
  available: true,
  serviceEnabled: true,
  currentConsent: true,
  protectionEnabled: true,
  observationMode: false,
  shortsEnabled: true,
  xHomeEnabled: true,
  xVideosEnabled: true,
  xHomeMinutes: 5,
  xHomeUsedMs: 0,
  xHomeBreakRemainingMs: 0,
  xObservationMode: false,
  xSignalMask: 3,
  lastEventAt: 0,
  lastDetectionAt: 1,
  detectionCount: 1,
  lastDetectionReason: 'Shorts',
  instagramObservationMode: false,
  instagramWaitSeconds: 30,
  instagramReelsMinutes: 5,
  instagramHomeMinutes: 5,
  instagramHomeUsedMs: 0,
  instagramHomeBreakRemainingMs: 0,
  instagramExploreBlocked: true,
  instagramLastDetectionAt: 1,
  instagramDetectionCount: 1,
  instagramSignalMask: 3,
  instagramLastDetectionReason: 'Reels',
  adultSiteEnabled: false,
  adultSiteCustomCount: 0,
  browserSignalMask: 1,
  appAvailability: installedApps,
  browserAvailability: installedBrowsers,
};

function onlyX(overrides = {}) {
  return {
    ...base,
    shortsEnabled: false,
    instagramObservationMode: false,
    instagramSignalMask: 0,
    instagramExploreBlocked: false,
    adultSiteEnabled: false,
    ...overrides,
  };
}

function onlyShorts(overrides = {}) {
  return {
    ...base,
    xHomeEnabled: false,
    xVideosEnabled: false,
    instagramObservationMode: false,
    instagramSignalMask: 0,
    instagramExploreBlocked: false,
    adultSiteEnabled: false,
    ...overrides,
  };
}

function onlySites(overrides = {}) {
  return {
    ...base,
    shortsEnabled: false,
    xHomeEnabled: false,
    xVideosEnabled: false,
    instagramObservationMode: false,
    instagramSignalMask: 0,
    instagramExploreBlocked: false,
    adultSiteEnabled: true,
    ...overrides,
  };
}

test('availability distinguishes null, native unavailable, absent, disabled, installed, and missing fields', () => {
  const cases = [
    [null, 'unknown'],
    [{ ...base, available: false }, 'unavailable'],
    [{ ...base, appAvailability: { ...installedApps, youtube: 'absent' } }, 'absent'],
    [{ ...base, appAvailability: { ...installedApps, youtube: 'disabled' } }, 'disabled'],
    [base, 'installed'],
    [{ ...base, appAvailability: undefined }, 'unknown'],
  ];
  for (const [status, expected] of cases) assert.equal(getSupportedAppAvailability(status, 'youtube'), expected);
  assert.equal(getSupportedBrowserAvailability({ ...base, browserAvailability: undefined }, 'chrome'), 'unknown');
  assert.equal(getSupportedBrowserAvailability({ ...base, available: false }, 'chrome'), 'unavailable');
});

test('availability package visibility stays limited to the supported targets', () => {
  for (const packageName of [
    'com.google.android.youtube', 'com.instagram.android', 'com.twitter.android',
    'com.android.chrome', 'com.sec.android.app.sbrowser', 'com.opera.browser', 'org.mozilla.firefox',
  ]) assert.match(manifestSource, new RegExp(`android:name="${packageName.replaceAll('.', '\\.')}`));
  assert.doesNotMatch(
    manifestSource,
    /<uses-permission[^>]+android:name="android\.permission\.QUERY_ALL_PACKAGES"/,
  );
});

test('browser readiness requires one current installed supported browser, not all four', () => {
  const cases = [
    [{ ...base, browserSignalMask: 0 }, 'check'],
    [{ ...base, browserSignalMask: undefined }, 'check'],
    [{ ...base, browserSignalMask: 1 }, 'ready'],
    [{ ...base, browserSignalMask: 15 }, 'ready'],
    [{ ...base, browserAvailability: { chrome: 'installed', samsungInternet: 'absent', opera: 'absent', firefox: 'absent' }, browserSignalMask: 1 }, 'ready'],
    [{ ...base, browserAvailability: { chrome: 'installed', samsungInternet: 'absent', opera: 'absent', firefox: 'absent' }, browserSignalMask: 0 }, 'check'],
    [{ ...base, browserAvailability: { chrome: 'disabled', samsungInternet: 'disabled', opera: 'disabled', firefox: 'disabled' }, browserSignalMask: 15 }, 'disabled'],
    [{ ...base, browserAvailability: { chrome: 'disabled', samsungInternet: 'disabled', opera: 'absent', firefox: 'absent' }, browserSignalMask: 15 }, 'disabled'],
    [{ ...base, browserAvailability: { chrome: 'disabled', samsungInternet: 'absent', opera: 'absent', firefox: 'absent' }, browserSignalMask: 15 }, 'disabled'],
    [{ ...base, browserAvailability: { chrome: 'absent', samsungInternet: 'installed', opera: 'installed', firefox: 'installed' }, browserSignalMask: 1 }, 'check'],
    [{ ...base, browserAvailability: { chrome: 'absent', samsungInternet: 'absent', opera: 'absent', firefox: 'absent' }, browserSignalMask: 15 }, 'none-installed'],
    [{ ...base, browserAvailability: { chrome: 'unknown', samsungInternet: 'absent', opera: 'absent', firefox: 'absent' }, browserSignalMask: 0 }, 'unknown'],
    [{ ...base, browserAvailability: { chrome: 'unknown', samsungInternet: 'disabled', opera: 'absent', firefox: 'absent' }, browserSignalMask: 15 }, 'unknown'],
    [{ ...base, available: false }, 'unavailable'],
  ];
  for (const [status, expected] of cases) assert.equal(getBrowserReadiness(status), expected);
  assert.equal(checkedInstalledBrowserCount({ ...base, browserSignalMask: undefined }), 0);
  assert.equal(checkedInstalledBrowserCount({ ...base, browserSignalMask: 3 }), 2);
  assert.equal(checkedInstalledBrowserCount({ ...base, browserAvailability: { chrome: 'absent', samsungInternet: 'installed', opera: 'installed', firefox: 'installed' }, browserSignalMask: 1 }), 0);
});

test('global status has explicit off, access, empty, setup, partial, active, and unavailable-target outcomes', () => {
  const allOff = {
    ...base,
    shortsEnabled: false,
    xHomeEnabled: false,
    xVideosEnabled: false,
    instagramObservationMode: false,
    instagramSignalMask: 0,
    instagramExploreBlocked: false,
    adultSiteEnabled: false,
  };
  const absent = {
    ...base,
    appAvailability: { youtube: 'absent', instagram: 'absent', x: 'absent' },
    browserAvailability: { chrome: 'absent', samsungInternet: 'absent', opera: 'absent', firefox: 'absent' },
    adultSiteEnabled: true,
  };
  const unknownFields = { ...base, appAvailability: undefined, browserAvailability: undefined };
  const table = [
    [null, 'unknown'],
    [{ ...base, available: false }, 'unavailable'],
    [{ ...base, serviceEnabled: false }, 'permission'],
    [{ ...base, protectionEnabled: false }, 'paused'],
    [allOff, 'empty'],
    [onlyShorts({ observationMode: true, lastDetectionAt: 0 }), 'setup'],
    [{ ...base, observationMode: true }, 'partial'],
    [base, 'active'],
    [absent, 'targets-unavailable'],
    [unknownFields, 'targets-unknown'],
  ];
  for (const [status, expected] of table) assert.equal(getFeedStatus(status), expected);
  assert.match(getFeedStatusDetail({ ...base, observationMode: true }), /active for ready rules/i);
  assert.match(getFeedStatusDetail(allOff), /No feed or site rules enabled/);
  assert.match(getFeedStatusDetail(absent), /Install or enable/);
  assert.equal(getProtectionReadiness({ ...base, observationMode: true }).pending, 1);
});

test('permission and pause precedence remains above feed readiness', () => {
  assert.equal(getFeedStatus({ ...onlyX({ xSignalMask: 0 }), serviceEnabled: false }), 'permission');
  assert.equal(getFeedStatus({ ...onlyX({ xSignalMask: 0 }), protectionEnabled: false }), 'paused');
});

test('each X signal direction keeps only its missing feed in setup', () => {
  for (const [mask, expected] of [[0, 2], [1, 1], [2, 1], [3, 0]]) {
    const status = onlyX({ xSignalMask: mask });
    const readiness = getProtectionReadiness(status);
    assert.equal(readiness.pending, expected);
    assert.equal(getFeedStatus(status), expected === 2 ? 'setup' : expected ? 'partial' : 'active');
    assert.equal(getOverviewAction(status, false), expected ? 'set-up-x' : null);
  }
});

test('absent YouTube cannot monopolize setup while an available X rule remains', () => {
  const status = onlyX({
    shortsEnabled: true,
    observationMode: true,
    lastDetectionAt: 0,
    xSignalMask: 1,
    appAvailability: { youtube: 'absent', instagram: 'absent', x: 'installed' },
  });
  assert.equal(getOverviewAction(status, false), 'set-up-x');
  assert.equal(getFeedPresentation(status, 'shorts').statusLabel, 'Not installed');
  assert.match(getFeedStatusDetail({ ...status, xSignalMask: 3 }), /unavailable/i);
  assert.equal(getOverviewAction({ ...status, appAvailability: { ...status.appAvailability, youtube: 'installed' } }, false), 'check-youtube');
});

test('unknown supported apps never take the automatic CTA from an installed X target', () => {
  assert.equal(getOverviewAction(onlyX({
    shortsEnabled: true,
    xSignalMask: 1,
    appAvailability: { youtube: 'unknown', instagram: 'absent', x: 'installed' },
  }), false), 'set-up-x');
  assert.equal(getOverviewAction(onlyX({
    instagramObservationMode: true,
    xSignalMask: 1,
    appAvailability: { youtube: 'absent', instagram: 'unknown', x: 'installed' },
  }), false), 'set-up-x');
  const allUnknown = onlyX({
    shortsEnabled: true,
    xSignalMask: 0,
    appAvailability: { youtube: 'unknown', instagram: 'unknown', x: 'unknown' },
  });
  assert.equal(getOverviewAction(allUnknown, false), null);
  assert.equal(getFeedStatus(allUnknown), 'targets-unknown');
  assert.equal(getOverviewAction(onlyX({ shortsEnabled: true, xSignalMask: 0, appAvailability: undefined }), false), null);
});

test('disabled and absent rules do not create setup actions', () => {
  assert.equal(getOverviewAction(onlyX({ xHomeEnabled: false, xVideosEnabled: false }), false), null);
  assert.equal(getOverviewAction(onlyShorts({ shortsEnabled: true, appAvailability: { ...installedApps, youtube: 'absent' }, observationMode: true }), false), null);
  assert.equal(getOverviewAction(onlySites({ browserAvailability: { chrome: 'absent', samsungInternet: 'absent', opera: 'absent', firefox: 'absent' }, browserSignalMask: 0 }), false), null);
});

test('sites action appears only when an installed or unknown supported browser needs a check', () => {
  assert.equal(getOverviewAction(onlySites({ browserSignalMask: 0 }), false), 'check-sites');
  assert.equal(getOverviewAction(onlySites({ browserSignalMask: 1 }), false), null);
  assert.equal(getOverviewAction(onlySites({ browserAvailability: undefined, browserSignalMask: 0 }), false), 'check-sites');
  assert.equal(getOverviewAction(onlySites({ browserAvailability: { chrome: 'absent', samsungInternet: 'absent', opera: 'absent', firefox: 'absent' } }), false), null);
  assert.equal(getAdultSitePresentation(onlySites({ browserAvailability: { chrome: 'absent', samsungInternet: 'absent', opera: 'absent', firefox: 'absent' } })).statusLabel, 'NO BROWSER');
  assert.equal(getAdultSitePresentation(onlySites({ browserAvailability: { chrome: 'disabled', samsungInternet: 'absent', opera: 'absent', firefox: 'absent' } })).statusLabel, 'ENABLE');
  assert.match(adultSiteDrawerSource, /getBrowserReadiness\(status\) === 'none-installed' \|\| getBrowserReadiness\(status\) === 'disabled'/);
  assert.match(adultSiteDrawerSource, /getBrowserReadiness\(status\) === 'disabled' \? <Text[^>]*>Enable one of the supported browsers/);
});

test('feed drawers suppress impossible observation guidance but preserve recovery', () => {
  for (const appLabel of ['YouTube', 'Instagram', 'X']) {
    const installed = getFeedDrawerTargetPresentation('installed', appLabel, true, true, true);
    assert.deepEqual(installed, { showObservationGuidance: true, showPostSetupGuidance: true, showOpener: true, recovery: null });
    const absent = getFeedDrawerTargetPresentation('absent', appLabel, true, true, true);
    assert.equal(absent.showObservationGuidance, false);
    assert.equal(absent.showPostSetupGuidance, false);
    assert.equal(absent.showOpener, false);
    assert.match(absent.recovery, /not installed/);
    const disabled = getFeedDrawerTargetPresentation('disabled', appLabel, true, true, true);
    assert.equal(disabled.showObservationGuidance, false);
    assert.equal(disabled.showPostSetupGuidance, false);
    assert.equal(disabled.showOpener, false);
    assert.match(disabled.recovery, /disabled in Android/);
    const unknown = getFeedDrawerTargetPresentation('unknown', appLabel, true, true, true);
    assert.equal(unknown.showObservationGuidance, false);
    assert.equal(unknown.showPostSetupGuidance, false);
    assert.equal(unknown.showOpener, true);
    assert.match(unknown.recovery, /could not be confirmed/);
  }
  assert.match(feedDrawerSource, /getFeedDrawerTargetPresentation\(/);
  assert.match(feedDrawerSource, /targetPresentation\.showObservationGuidance/);
  assert.match(feedDrawerSource, /targetPresentation\.showPostSetupGuidance/);
  assert.match(feedDrawerSource, /targetPresentation\.showOpener/);
  assert.match(feedDrawerSource, /targetPresentation\.recovery/);
});

test('saved rules show a stale app from fresh picker evidence and recover after install', () => {
  const rule = { packageName: 'com.example.focus', label: 'Focus app' };
  assert.deepEqual(getConfiguredAppPresentation(rule.label, rule.packageName, null), { label: 'Focus app', availability: 'unknown', detail: null });
  const absent = getConfiguredAppPresentation(rule.label, rule.packageName, []);
  assert.equal(absent.label, 'Focus app');
  assert.equal(absent.availability, 'unknown');
  assert.match(absent.detail, /not currently available in the app picker/i);
  assert.match(absent.detail, /saved rule stays/i);
  assert.match(limitsSource, /entry\.availability !== 'installed' && entry\.availabilityDetail/);
  assert.deepEqual(getConfiguredAppPresentation(rule.label, rule.packageName, [{ ...rule }]), { label: 'Focus app', availability: 'installed', detail: null });
});

test('web App limits state is decided before Android-only methods are called', () => {
  assert.equal(getAppLimitsPlatformState(null), 'unknown');
  assert.equal(getAppLimitsPlatformState({ available: false }), 'android-required');
  assert.equal(getAppLimitsPlatformState({ available: true }), 'native');
  assert.match(limitsSource, /getAppLimitsPlatformState\(nativeStatus\)[\s\S]*getAppLimits\(\),/);
  assert.match(limitsSource, /App limits require the native Android app/);
});

test('external action failures retain context and privacy handles rejected opens', () => {
  assert.equal(getActionError(new Error('No activity found'), 'The privacy policy could not be opened.'), 'The privacy policy could not be opened. No activity found');
  assert.equal(getActionError('bad result', 'Try again.'), 'Try again.');
  assert.match(privacySource, /openPrivacyPolicy\(\)[\s\S]*\.catch/);
  assert.match(privacySource, /ErrorNote message=\{error\}/);
});

test('the overview uses the production site action and a truthful active summary', () => {
  assert.match(overviewSource, /getFeedStatusDetail\(status\)/);
  assert.match(overviewSource, /case 'check-sites'[\s\S]*openBrowserCheck/);
  assert.match(overviewSource, /getOverviewAction\(status, Boolean\(readError\)\)/);
});
