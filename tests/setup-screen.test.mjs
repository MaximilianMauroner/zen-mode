import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { registerHooks } from 'node:module';
import test, { after } from 'node:test';
import vm from 'node:vm';
import ts from 'typescript';

import { RECOMMENDED_DEFAULTS_SUMMARY } from '../src/features/protection/recommended-defaults.ts';

const moduleUrl = (source) => 'data:text/javascript,' + encodeURIComponent(source);
const secureStoreUrl = moduleUrl(`
  export const values = new Map();
  export let nextWriteError;
  export function failNextWrite(error) { nextWriteError = error; }
  export async function getItemAsync(key) { return values.get(key) ?? null; }
  export async function setItemAsync(key, value) {
    if (nextWriteError) {
      const error = nextWriteError;
      nextWriteError = undefined;
      throw error;
    }
    values.set(key, value);
  }
`);
const cryptoUrl = moduleUrl(`
  import { createHash, getRandomValues } from 'node:crypto';
  export { getRandomValues };
  export const CryptoDigestAlgorithm = { SHA256: 'sha256' };
  export async function digestStringAsync(algorithm, data) {
    return createHash(algorithm).update(data).digest('hex');
  }
`);
const reactNativeUrl = moduleUrl("export const Platform = { OS: 'android' };");
const nativeUrl = moduleUrl(`
  export const status = {};
  export const calls = [];
  export async function getZenGuardStatus() {
    if (status.accessChecks.length > 0) status.serviceEnabled = status.accessChecks.shift();
    return { ...status };
  }
  export async function hasCurrentNativeConsent() { return status.currentConsent; }
  export async function acceptCurrentNativeConsent() { status.currentConsent = true; calls.push('consent'); }
  export async function openAccessibilitySettings() { calls.push('open settings'); }
  export async function setNativeProtectionEnabled(enabled) { status.protectionEnabled = enabled; }
  export async function setEndScreen(mode) { status.endScreen = mode; }
  export async function getAppLimits() {
    return Object.keys(status.dailyCaps).map((packageName) => ({
      packageName, guidedVisitPending: status.pendingVisits.has(packageName),
    }));
  }
  export async function getIntentApps() { return Object.keys(status.visits).map((packageName) => ({ packageName })); }
  export async function getRollingLimits() { return status.rolling; }
  export async function getEarnRules() { return status.earn; }
  export async function setAppLimit(packageName, minutes, guidedVisitPending) {
    status.dailyCaps[packageName] = minutes;
    if (guidedVisitPending) status.pendingVisits.add(packageName);
    else status.pendingVisits.delete(packageName);
  }
  export async function setIntentApp(packageName, sessionMinutes, cooldownMinutes) {
    if (status.failVisitFor === packageName) {
      status.failVisitFor = undefined;
      throw new Error('Could not save the timed visit');
    }
    status.visits[packageName] = { sessionMinutes, cooldownMinutes };
    status.pendingVisits.delete(packageName);
  }
  export async function setYouTubeSettings(...args) { status.rules.youtube = args; }
  export async function setXSettings(...args) { status.rules.x = args; }
  export async function setInstagramSettings(...args) { status.rules.instagram = args; }
  export async function setAdultSiteBlockingEnabled(enabled) { status.rules.adultSites = enabled; }
  export async function enableDefaultAdultSiteBlocking() { status.rules.adultSites ??= true; }
  export async function setObservationMode(enabled) { status.observation.youtube = enabled; }
  export async function setInstagramObservationMode(enabled) { status.observation.instagram = enabled; }
  export async function setXObservationMode(enabled) { status.observation.x = enabled; }
`);

const hooks = registerHooks({
  resolve(specifier, context, nextResolve) {
    if (specifier === 'expo-secure-store') return nextResolve(secureStoreUrl, context);
    if (specifier === 'expo-crypto') return nextResolve(cryptoUrl, context);
    if (specifier === 'react-native') return nextResolve(reactNativeUrl, context);
    if (specifier === './native') return nextResolve(nativeUrl, context);
    if (['./lock', './lock-policy', './recommended-defaults', './setup-policy'].includes(specifier)) {
      return nextResolve(specifier + '.ts', context);
    }
    return nextResolve(specifier, context);
  },
});

const store = await import(secureStoreUrl);
const native = await import(nativeUrl);
const { Platform } = await import(reactNativeUrl);
const lock = await import('../src/features/protection/lock.ts');
const setupActions = await import('../src/features/protection/setup.ts');
after(() => hooks.deregister());

const source = readFileSync(new URL('../src/app/setup.tsx', import.meta.url), 'utf8');
const { outputText } = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX } });
const element = (type, props) => ({ type, props });

function find(node, predicate) {
  if (!node || typeof node !== 'object') return undefined;
  if (predicate(node)) return node;
  for (const child of [node.props?.children].flat(Infinity)) {
    const found = find(child, predicate);
    if (found) return found;
  }
}

const previewStorage = new Map();

async function setup({ activePasscode, platform = 'android', endScreen = 'off', appAvailability = { youtube: 'installed', instagram: 'installed', x: 'installed' } } = {}) {
  Platform.OS = platform;
  previewStorage.clear();
  store.values.clear();
  native.calls.length = 0;
  Object.assign(native.status, {
    available: true, serviceEnabled: false, currentConsent: false, protectionEnabled: false, endScreen,
    appAvailability, dailyCaps: {}, visits: {}, rules: {}, observation: {}, accessChecks: [], rolling: [], earn: [],
    pendingVisits: new Set(), failVisitFor: undefined,
  });
  if (activePasscode) await lock.updatePasscode({ passcode: activePasscode, confirmation: activePasscode });
  return mountSetup();
}

async function mountSetup() {
  const values = [];
  const effects = [];
  const pendingEffects = [];
  let cursor = 0;
  let effectCursor = 0;
  let onAppState;
  let destination;
  let openedUrl;
  const reactHooks = {
    useState(initial) {
      const index = cursor++;
      if (!(index in values)) values[index] = typeof initial === 'function' ? initial() : initial;
      return [values[index], (value) => { values[index] = typeof value === 'function' ? value(values[index]) : value; }];
    },
    useRef(initial) { return reactHooks.useState(() => ({ current: initial }))[0]; },
    useCallback: (callback) => callback,
    useEffect(effect, deps) {
      const index = effectCursor++;
      const previous = effects[index];
      if (previous && deps.every((dep, i) => Object.is(dep, previous.deps[i]))) return;
      pendingEffects.push(() => {
        previous?.cleanup?.();
        effects[index] = { deps, cleanup: effect() };
      });
    },
  };
  const imports = {
    react: reactHooks,
    'react/jsx-runtime': { jsx: element, jsxs: element },
    'react-native': { Platform, AppState: { addEventListener: (_event, callback) => {
      onAppState = callback;
      return { remove() { onAppState = undefined; } };
    } } },
    'expo-router': { router: { replace: (path) => { destination = path; } } },
    'expo-linking': { async openURL(url) { openedUrl = url; } },
    '@/features/protection/setup': setupActions,
    '@/features/protection/lock': lock,
    '@/features/protection/recommended-defaults': await import('../src/features/protection/recommended-defaults.ts'),
    '@/features/protection/native': native,
    '@/theme/colors': { colors: {} },
  };
  const module = { exports: {} };
  vm.runInNewContext(outputText, { Error, exports: module.exports, require: (name) => imports[name] ?? new Proxy({}, { get: (_, key) => String(key) }) });
  const render = () => {
    cursor = 0;
    effectCursor = 0;
    const screen = module.exports.default();
    for (const effect of pendingEffects.splice(0)) effect();
    return screen;
  };
  const locate = (predicate) => find(render(), predicate);
  const props = (name) => locate((node) => node.props?.title === name && node.props?.onPress)?.props;
  const press = async (name) => {
    const button = props(name);
    assert.ok(button, `Expected an action named ${name}`);
    assert.equal(Boolean(button.disabled), false);
    await button.onPress();
  };
  render();
  await new Promise(setImmediate);
  return {
    press, props, find: locate,
    async chooseMode(mode) { await press(mode); await press('Continue'); },
    chooseStartingRules: (value) => locate((node) => node.props?.label === 'Starting rules').props.onSelect(value),
    selectDays: (days) => locate((node) => node.props?.label === 'Lock length').props.onSelect(days),
    enter: (label, value) => locate((node) => node.type === 'PasscodeInput' && node.props.label === label).props.onChangeText(value),
    consent: () => locate((node) => node.props?.accessibilityRole === 'checkbox').props.onPress(),
    returnToApp: async (enabled) => {
      native.status.serviceEnabled = enabled;
      render();
      onAppState?.('active');
      await new Promise(setImmediate);
    },
    get destination() { return destination; },
    get openedUrl() { return openedUrl; },
  };
}

const recommendedRules = { youtube: [true, false], x: [true, true, 5], instagram: [30, 5, 5, true], adultSites: true };
const timedVisit = { sessionMinutes: 5, cooldownMinutes: 5 };

test('Kids setup confirms a passcode, waits for access, and caps only installed apps before Family Link', async () => {
  const view = await setup({ appAvailability: { youtube: 'installed', instagram: 'installed', x: 'absent' } });
  await view.chooseMode('Kids');
  await view.press('Passcode');
  view.enter('Passcode', 'family code');
  view.enter('Confirm passcode', 'different code');
  await view.press('Continue');
  assert.ok(view.find((node) => /do not match/.test(node.props?.message)));
  assert.equal((await lock.readLockState()).kind, 'open');

  view.enter('Confirm passcode', 'family code');
  await view.press('Continue');
  await view.press('Continue to Android access');
  assert.deepEqual(native.calls, []);
  view.consent();
  await view.press('Continue to Android access');
  assert.deepEqual(native.calls, ['consent', 'open settings']);
  await view.returnToApp(false);
  assert.equal(await setupActions.hasCompletedSetup(), false);
  assert.deepEqual(native.status.dailyCaps, {});
  assert.equal((await lock.readLockState()).kind, 'open');

  await view.returnToApp(true);
  assert.equal(await setupActions.hasCompletedSetup(), false);
  assert.equal(native.status.protectionEnabled, true);
  assert.deepEqual(native.status.rules, { adultSites: true });
  assert.equal(native.status.endScreen, 'kids');
  assert.deepEqual(native.status.dailyCaps, { 'com.google.android.youtube': 60, 'com.instagram.android': 60 });
  assert.deepEqual(native.status.visits, { 'com.google.android.youtube': timedVisit, 'com.instagram.android': timedVisit });
  assert.equal((await lock.readLockState()).kind, 'passcode');
  await lock.requirePasscode('family code');
  assert.equal(view.destination, undefined);
  const relaunched = await mountSetup();
  await relaunched.press('Open Google Family Link');
  assert.equal(relaunched.openedUrl, 'https://families.google/familylink/');
  await relaunched.press('Finish setup');
  assert.equal(relaunched.destination, '/');
  assert.equal(await setupActions.hasCompletedSetup(), true);
});

test('Focus setup keeps the recommended rules, adds timed visits and arms the chosen time lock', async (t) => {
  const now = Date.UTC(2026, 10, 18);
  t.mock.timers.enable({ apis: ['Date'], now });
  const view = await setup();
  await view.chooseMode('Focus');
  await view.press('Time lock');
  view.selectDays(3);
  await view.press('Continue');
  view.consent();
  await view.press('Continue to Android access');
  await view.returnToApp(true);
  assert.equal((await lock.readLockState()).kind, 'open');
  await view.press('Lock for 3 days and finish');

  assert.deepEqual(native.status.rules, { adultSites: true });
  assert.deepEqual(native.status.dailyCaps, {});
  assert.deepEqual(native.status.visits, {
    'com.google.android.youtube': timedVisit,
    'com.instagram.android': timedVisit,
    'com.twitter.android': timedVisit,
  });
  assert.equal(native.status.endScreen, 'focus');
  assert.equal(native.status.protectionEnabled, true);
  assert.equal((await lock.readLockState()).lockedUntil, now + 3 * 86_400_000);
  assert.equal(view.props('Open Google Family Link'), undefined);
  assert.equal(view.destination, '/');
});

test('Customize offers both starting choices and leaves manually selected rules paused', async () => {
  const view = await setup();
  await view.chooseMode('Customize');
  assert.equal(view.props('Continue').disabled, true);
  view.chooseStartingRules('recommended');
  for (const line of RECOMMENDED_DEFAULTS_SUMMARY) {
    assert.ok(view.find((node) => node.props?.children === line));
  }
  view.chooseStartingRules('customize');
  await view.press('Continue');
  view.consent();
  await view.press('Continue to Android access');
  await view.returnToApp(true);

  assert.equal(await setupActions.hasCompletedSetup(), true);
  assert.equal(native.status.protectionEnabled, false);
  assert.deepEqual(native.status.rules, {});
  assert.equal(view.destination, '/?setup=customize');
});

test('Customize can still apply the healthy defaults without arming a new lock or visits', async () => {
  const view = await setup();
  await view.chooseMode('Customize');
  view.chooseStartingRules('recommended');
  await view.press('Continue');
  view.consent();
  await view.press('Continue to Android access');
  await view.returnToApp(true);

  assert.equal(native.status.protectionEnabled, true);
  assert.deepEqual(native.status.rules, recommendedRules);
  assert.equal(native.status.endScreen, 'off');
  assert.deepEqual(native.status.visits, {});
  assert.equal((await lock.readLockState()).kind, 'open');
  assert.equal(view.destination, '/');
});

test('setup with an existing lock restores access without replacing rules or the lock', async () => {
  const view = await setup({ activePasscode: 'existing code', endScreen: 'kids' });
  native.status.visits = { 'com.google.android.youtube': { sessionMinutes: 1, cooldownMinutes: 30 } };
  assert.equal(view.props('Focus'), undefined);
  view.consent();
  await view.press('Continue to Android access');
  await view.returnToApp(true);

  assert.deepEqual(native.status.visits, { 'com.google.android.youtube': { sessionMinutes: 1, cooldownMinutes: 30 } });
  assert.deepEqual(native.status.rules, {});
  assert.equal(native.status.protectionEnabled, true);
  await lock.requirePasscode('existing code');
  assert.ok(view.props('Open Google Family Link'));
});

test('guided setup with no lock leaves existing rules and feed observation unchanged', async () => {
  const view = await setup();
  const youtube = 'com.google.android.youtube';
  const instagram = 'com.instagram.android';
  const x = 'com.twitter.android';
  native.status.dailyCaps = { [youtube]: 15 };
  native.status.visits = { [youtube]: { sessionMinutes: 2, cooldownMinutes: 30 } };
  native.status.rolling = [{ packageName: instagram, allowanceMinutes: 2, windowMinutes: 60 }];
  const feedAndSiteRules = { youtube: [true, true], x: [true, true, 1], instagram: [60, 1, 1, true], adultSites: false };
  const observation = { youtube: false, instagram: false, x: false };
  native.status.rules = { ...feedAndSiteRules };
  native.status.observation = { ...observation };
  assert.equal((await lock.readLockState()).kind, 'open');

  await view.chooseMode('Kids');
  await view.press('Time lock');
  await view.press('Continue');
  view.consent();
  await view.press('Continue to Android access');
  await view.returnToApp(true);
  await view.press('Lock for 7 days and finish');

  assert.deepEqual(native.status.dailyCaps, { [youtube]: 15, [x]: 60 });
  assert.deepEqual(native.status.visits, { [youtube]: { sessionMinutes: 2, cooldownMinutes: 30 }, [x]: timedVisit });
  assert.deepEqual(native.status.rolling, [{ packageName: instagram, allowanceMinutes: 2, windowMinutes: 60 }]);
  assert.deepEqual(native.status.rules, feedAndSiteRules);
  assert.deepEqual(native.status.observation, observation);
  assert.ok(view.props('Open Google Family Link'));
});

test('a lock write failure leaves the saved rules paused and setup can finish on retry', async () => {
  const view = await setup();
  await view.chooseMode('Focus');
  await view.press('Time lock');
  await view.press('Continue');
  view.consent();
  await view.press('Continue to Android access');
  await view.returnToApp(true);
  store.failNextWrite(new Error('Could not save the lock'));
  await view.press('Lock for 7 days and finish');

  assert.ok(view.find((node) => node.props?.message === 'Could not save the lock'));
  assert.equal(view.destination, undefined);
  assert.equal(native.status.protectionEnabled, false);
  assert.equal(await setupActions.hasCompletedSetup(), false);
  assert.equal((await lock.readLockState()).kind, 'open');
  const visits = { ...native.status.visits };
  assert.equal(Object.keys(visits).length, 3);

  await view.press('Lock for 7 days and finish');
  assert.equal((await lock.readLockState()).kind, 'locked');
  assert.deepEqual(native.status.visits, visits);
  assert.equal(native.status.protectionEnabled, true);
  assert.equal(await setupActions.hasCompletedSetup(), true);
  assert.equal(view.destination, '/');
});

test('Kids setup retries an unfinished timed visit and preserves a user cap of the same length', async () => {
  const view = await setup({ appAvailability: { youtube: 'installed', instagram: 'installed', x: 'absent' } });
  const youtube = 'com.google.android.youtube';
  const instagram = 'com.instagram.android';
  native.status.dailyCaps[instagram] = 60;
  native.status.failVisitFor = youtube;
  await view.chooseMode('Kids');
  await view.press('Time lock');
  await view.press('Continue');
  view.consent();
  await view.press('Continue to Android access');
  await view.returnToApp(true);
  await view.press('Lock for 7 days and finish');

  assert.ok(view.find((node) => node.props?.message === 'Could not save the timed visit'));
  assert.deepEqual(native.status.dailyCaps, { [instagram]: 60, [youtube]: 60 });
  assert.deepEqual(native.status.visits, {});

  await view.press('Lock for 7 days and finish');
  assert.deepEqual(native.status.dailyCaps, { [instagram]: 60, [youtube]: 60 });
  assert.deepEqual(native.status.visits, { [youtube]: timedVisit });
  assert.ok(view.props('Open Google Family Link'));
});

test('losing access after the lock is armed keeps the lock and lets setup resume after relaunch', async (t) => {
  t.mock.timers.enable({ apis: ['Date'], now: Date.UTC(2026, 10, 18) });
  const view = await setup();
  await view.chooseMode('Kids');
  await view.press('Time lock');
  view.selectDays(1);
  await view.press('Continue');
  view.consent();
  await view.press('Continue to Android access');
  native.status.accessChecks = [true, false];
  await view.press('Lock for 1 day and finish');

  assert.ok(view.find((node) => /Enable Zen Mode in Android/.test(node.props?.message)));
  assert.equal(view.destination, undefined);
  assert.equal(native.status.protectionEnabled, false);
  assert.equal(await setupActions.hasCompletedSetup(), false);
  const savedLock = await lock.readLockState();
  assert.equal(savedLock.kind, 'locked');
  const caps = { ...native.status.dailyCaps };
  const visits = { ...native.status.visits };
  assert.equal(Object.keys(caps).length, 3);

  t.mock.timers.tick(60_000);
  const relaunched = await mountSetup();
  relaunched.consent();
  await relaunched.press('Continue to Android access');
  await relaunched.returnToApp(true);
  assert.deepEqual(await lock.readLockState(), savedLock);
  assert.deepEqual(native.status.dailyCaps, caps);
  assert.deepEqual(native.status.visits, visits);
  assert.equal(native.status.protectionEnabled, true);
  await relaunched.press('Finish setup');
  assert.equal(await setupActions.hasCompletedSetup(), true);
  assert.equal(relaunched.destination, '/');
});

test('Kids on web can finish setup and reach Home without Android access or Family Link', async (t) => {
  globalThis.localStorage = {
    getItem: (key) => previewStorage.get(key) ?? null,
    setItem: (key, value) => { previewStorage.set(key, value); },
  };
  t.after(() => { delete globalThis.localStorage; });
  const view = await setup({ platform: 'web' });
  await view.chooseMode('Kids');
  await view.press('Time lock');
  await view.press('Finish setup');
  assert.equal(view.destination, '/');
  assert.equal(await setupActions.hasCompletedSetup(), true);
  assert.equal(view.props('Open Google Family Link'), undefined);
  assert.deepEqual(native.calls, []);
  assert.deepEqual(native.status.visits, {});
  assert.equal((await lock.readLockState()).kind, 'open');
});
