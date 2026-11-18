import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { registerHooks } from 'node:module';
import test, { after, beforeEach } from 'node:test';
import vm from 'node:vm';
import ts from 'typescript';

import * as controlState from '../src/features/protection/control-state.ts';
import { getProtectionOffNotice } from '../src/features/protection/overview-actions.ts';

const secureStoreUrl = 'data:text/javascript,' + encodeURIComponent(`
  const values = new Map();
  let unreadable = false;
  export function setUnreadable(value) { unreadable = value; }
  export async function getItemAsync(key) {
    if (unreadable) throw new Error('Could not read SecureStore.');
    return values.get(key) ?? null;
  }
  export async function setItemAsync(key, value) { values.set(key, value); }
  export async function deleteItemAsync(key) { values.delete(key); }
`);
const cryptoUrl = 'data:text/javascript,' + encodeURIComponent(`
  import { createHash, getRandomValues } from 'node:crypto';
  export { getRandomValues };
  export const CryptoDigestAlgorithm = { SHA256: 'sha256' };
  export async function digestStringAsync(algorithm, data) {
    return createHash(algorithm).update(data).digest('hex');
  }
`);

const hooks = registerHooks({
  resolve(specifier, context, nextResolve) {
    if (specifier === 'expo-secure-store') return nextResolve(secureStoreUrl, context);
    if (specifier === 'expo-crypto') return nextResolve(cryptoUrl, context);
    if (specifier === './lock' || specifier === './lock-policy') return nextResolve(specifier + '.ts', context);
    return nextResolve(specifier, context);
  },
});
const store = await import(secureStoreUrl);
const lock = await import('../src/features/protection/lock.ts');
const { armLock, readLockState, requestUnlock, updatePasscode } = lock;
const { authorizeWeakening } = await import('../src/features/protection/authorize-weakening.ts');
after(() => hooks.deregister());

const LOCK_KEY = 'zen-mode.commitment-lock.v1';
const PASSCODE = 'my focus code';
const passcodeEntry = (passcode = PASSCODE) => ({ passcode, confirmation: passcode });
const authorizePause = (request) => authorizeWeakening(true, 'pause protection', request);

beforeEach(async () => {
  store.setUnreadable(false);
  await store.deleteItemAsync(LOCK_KEY);
});

function findAction(node, title) {
  if (node?.props?.title === title) return node.props.onPress;
  for (const child of [node?.props?.children].flat(Infinity)) {
    if (child == null || typeof child !== 'object') continue;
    const action = findAction(child, title);
    if (action) return action;
  }
}

test('Home protects the notice when the user presses Dismiss notice', async () => {
  const source = readFileSync(new URL('../src/app/(controls)/index.tsx', import.meta.url), 'utf8');
  const { outputText } = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX },
  });
  const disabledAt = Date.UTC(2026, 10, 18);
  const status = { available: true, serviceEnabled: false, serviceDisabledAt: disabledAt, serviceDisabledThrough: disabledAt };
  const values = [];
  const prompts = [];
  const acknowledgments = [];
  let cursor = 0;
  let focus;
  let answer = false;
  const react = {
    useState(initial) {
      const index = cursor++;
      if (!(index in values)) values[index] = initial;
      return [values[index], (value) => { values[index] = value; }];
    },
    useRef(initial) { return react.useState({ current: initial })[0]; },
    useCallback: (callback) => callback,
  };
  const element = (type, props) => ({ type, props });
  const imports = {
    react,
    'react/jsx-runtime': { jsx: element, jsxs: element },
    'expo-router': { useLocalSearchParams: () => ({}), useFocusEffect: (callback) => { focus = callback; } },
    'expo-router/js-top-tabs': { TopTabs: { Screen: 'TopTabs.Screen' } },
    '@/components/ui/weakening-gate': { useWeakeningGate: () => ({
      request: async ({ kind }) => { prompts.push(kind); return answer; },
    }) },
    '@/features/protection/authorize-weakening': { authorizeWeakening },
    '@/features/protection/control-state': controlState,
    '@/features/protection/guard-status-context': { useSharedGuardStatus: () => ({
      read: { kind: 'ready', value: status, refreshing: false }, refresh: async () => {},
    }) },
    '@/features/protection/lock': lock,
    '@/features/protection/feed-presentation': { getFeedPresentation: () => ({}) },
    '@/features/protection/feed-status': { getFeedStatus: () => 'running', getFeedStatusDetail: () => '' },
    '@/features/protection/home-feed-time': { getHomeFeedTimeLabel: () => null },
    '@/features/protection/overview-actions': { getOverviewAction: () => null, getProtectionOffNotice },
    '@/features/protection/adult-site-presentation': { getAdultSitePresentation: () => ({}) },
    '@/features/protection/setup': { hasCompletedSetup: async () => true },
    '@/features/protection/setup-policy': { shouldShowCustomizePrompt: () => false },
    '@/features/protection/native': { acknowledgeServiceDisabled: async (through) => { acknowledgments.push(through); } },
    '@/theme/colors': { colors: {} },
  };
  const exports = {};
  const modules = new Proxy({}, { get: (_, key) => String(key) });
  vm.runInNewContext(outputText, {
    Error, exports, require: (name) => imports[name] ?? modules, setInterval: () => 1,
  });
  const render = () => {
    cursor = 0;
    return exports.default();
  };
  const dismiss = async () => {
    const press = findAction(render(), 'Dismiss notice');
    assert.ok(press);
    press();
    await new Promise(setImmediate);
  };
  render();
  focus();
  await new Promise(setImmediate);
  assert.equal(findAction(render(), 'Dismiss notice'), undefined, 'the notice cannot be dismissed while access is still off');
  status.serviceEnabled = true;

  await dismiss();
  assert.deepEqual(acknowledgments, [], 'canceling the challenge keeps the notice');
  assert.deepEqual(prompts, ['challenge']);

  await armLock(7);
  await dismiss();
  await requestUnlock();
  await dismiss();
  assert.deepEqual(acknowledgments, [], 'a time lock refuses dismissal before and after an unlock request');
  assert.deepEqual(prompts, ['challenge']);

  await store.deleteItemAsync(LOCK_KEY);
  await updatePasscode(passcodeEntry());
  answer = 'wrong code';
  await dismiss();
  assert.deepEqual(acknowledgments, []);
  answer = PASSCODE;
  await dismiss();
  assert.deepEqual(acknowledgments, [disabledAt]);
  assert.deepEqual(prompts, ['challenge', 'passcode', 'passcode']);
});

test('a wrong passcode refuses a weaker change', async () => {
  await updatePasscode(passcodeEntry());
  await assert.rejects(authorizePause(async () => 'wrong code'), /Incorrect passcode/);
});

test('the right passcode allows a weaker change after a single passcode prompt', async () => {
  await updatePasscode(passcodeEntry());
  const prompts = [];
  const allowed = await authorizePause(async (prompt) => {
    prompts.push(prompt);
    return PASSCODE;
  });
  assert.equal(allowed, true);
  assert.deepEqual(prompts.map(({ kind, action }) => ({ kind, action })), [{ kind: 'passcode', action: 'pause protection' }]);
  assert.equal(await authorizePause(async () => false), false);
});

test('attempt delays survive reloading the lock and increase up to 15 minutes', async (t) => {
  t.mock.timers.enable({ apis: ['Date'], now: 1_700_000_000_000 });
  await updatePasscode(passcodeEntry());
  const wrong = () => authorizePause(async () => 'wrong code');
  const firstGuesses = await Promise.allSettled(Array.from({ length: 5 }, wrong));
  assert.deepEqual(firstGuesses.map(({ status }) => status), Array(5).fill('rejected'));
  assert.deepEqual(firstGuesses.map(({ reason }) => reason.message).sort(), [
    ...Array(4).fill('Incorrect passcode.'),
    'Too many incorrect passcodes. Try again in 30 seconds.',
  ]);

  const reloadedLock = await import('../src/features/protection/lock.ts?reload');
  await assert.rejects(reloadedLock.requirePasscode(PASSCODE), /30 seconds/);
  await assert.rejects(reloadedLock.updatePasscode(null, PASSCODE), /30 seconds/);
  t.mock.timers.tick(29_000);
  await assert.rejects(reloadedLock.requirePasscode(PASSCODE), /1 second/);
  t.mock.timers.tick(1_000);

  for (const seconds of [60, 120, 240, 480, 900, 900]) {
    await assert.rejects(wrong(), new RegExp(`in ${seconds} seconds`));
    t.mock.timers.tick(seconds * 1000);
  }
  assert.equal(await authorizePause(async () => PASSCODE), true);
  for (let attempt = 1; attempt <= 4; attempt++) {
    await assert.rejects(wrong(), { message: 'Incorrect passcode.' });
  }
  await assert.rejects(wrong(), /30 seconds/);
});

test('passcode changes and weaker actions share consecutive attempts', async (t) => {
  t.mock.timers.enable({ apis: ['Date'], now: 1_700_000_000_000 });
  await updatePasscode(passcodeEntry());
  await assert.rejects(updatePasscode(passcodeEntry('new focus code'), 'wrong code'), /Incorrect passcode/);
  await assert.rejects(updatePasscode(null, 'wrong code'), /Incorrect passcode/);
  await assert.rejects(authorizePause(async () => 'wrong code'), /Incorrect passcode/);
  await assert.rejects(authorizePause(async () => 'wrong code'), /Incorrect passcode/);
  await assert.rejects(updatePasscode(null, 'wrong code'), /30 seconds/);
  await assert.rejects(authorizePause(async () => PASSCODE), /30 seconds/);
});

test('a stronger change never asks for a passcode', async () => {
  await updatePasscode(passcodeEntry());
  assert.equal(await authorizeWeakening(false, 'resume protection', () => assert.fail('Unexpected prompt')), true);
});

test('removing the passcode requires the current passcode', async () => {
  await updatePasscode(passcodeEntry());
  await assert.rejects(updatePasscode(null), /current passcode/);
  await assert.rejects(updatePasscode(null, 'wrong code'), /Incorrect passcode/);
  assert.equal((await readLockState()).kind, 'passcode');

  await updatePasscode(null, PASSCODE);
  assert.deepEqual(await readLockState(), { kind: 'open' });
  assert.equal(await authorizePause(async ({ kind }) => kind === 'challenge'), true);
});

test('changing the passcode requires the current passcode and replaces it', async () => {
  await updatePasscode(passcodeEntry());
  const replacement = 'new focus code';
  await assert.rejects(updatePasscode(passcodeEntry(replacement)), /current passcode/);
  await assert.rejects(updatePasscode(passcodeEntry(replacement), 'wrong code'), /Incorrect passcode/);
  await updatePasscode(passcodeEntry(replacement), PASSCODE);
  await assert.rejects(authorizePause(async () => PASSCODE), /Incorrect passcode/);
  assert.equal(await authorizePause(async () => replacement), true);
});

test('a mismatched confirmation leaves settings open', async () => {
  await assert.rejects(updatePasscode({ passcode: PASSCODE, confirmation: 'another code' }), /do not match/);
  assert.deepEqual(await readLockState(), { kind: 'open' });
});

test('spaces count toward the passcode length and must be entered again', async () => {
  const passcode = ' code ';
  await updatePasscode(passcodeEntry(passcode));
  await assert.rejects(authorizePause(async () => passcode.trim()), /Incorrect passcode/);
  assert.equal(await authorizePause(async () => passcode), true);
});

test('SecureStore gets a freshly salted hash instead of the passcode', async () => {
  await updatePasscode(passcodeEntry());
  const first = await store.getItemAsync(LOCK_KEY);
  assert.doesNotMatch(first, new RegExp(PASSCODE));

  await updatePasscode(passcodeEntry(), PASSCODE);
  const second = await store.getItemAsync(LOCK_KEY);
  assert.doesNotMatch(second, new RegExp(PASSCODE));
  assert.notEqual(JSON.parse(first).hash, JSON.parse(second).hash);
});

test('a SecureStore read error refuses weakening before any challenge', async () => {
  await updatePasscode(passcodeEntry());
  store.setUnreadable(true);
  await assert.rejects(authorizePause(async () => true), /Could not read SecureStore/);
  await assert.rejects(readLockState(), /Could not read SecureStore/);
  await assert.rejects(updatePasscode(null, PASSCODE), /Could not read SecureStore/);
  assert.equal(await authorizeWeakening(false, 'resume protection', () => assert.fail('Unexpected prompt')), true);
});

test('a malformed stored passcode refuses weakening before any challenge', async () => {
  await store.setItemAsync(LOCK_KEY, JSON.stringify({ version: 2, salt: 'bad', hash: 'bad' }));
  await assert.rejects(authorizePause(async () => true), /saved lock could not be read/);
  await assert.rejects(readLockState(), /saved lock could not be read/);
  await assert.rejects(updatePasscode(null, PASSCODE), /saved lock could not be read/);
});

test('an existing version 1 lock blocks a passcode until the time lock ends', async () => {
  const lockedUntil = Date.now() + 60_000;
  await store.setItemAsync(LOCK_KEY, JSON.stringify({ version: 1, lockedUntil, unlockRequestedAt: null }));
  assert.deepEqual(await readLockState(), { kind: 'locked', lockedUntil, endsAt: lockedUntil });
  await assert.rejects(updatePasscode(passcodeEntry()), /time lock is active/);
  await assert.rejects(authorizePause(() => assert.fail('Unexpected prompt')), /Settings are locked/);

  await requestUnlock();
  assert.equal((await readLockState()).kind, 'pending');
  await assert.rejects(updatePasscode(passcodeEntry()), /time lock is active/);

  await store.setItemAsync(LOCK_KEY, JSON.stringify({ version: 1, lockedUntil: Date.now() - 1, unlockRequestedAt: null }));
  await updatePasscode(passcodeEntry());
  assert.equal((await readLockState()).kind, 'passcode');
  await assert.rejects(armLock(1), /Remove the passcode/);
});

test('a passcode cannot replace a time lock saved by a concurrent request', async () => {
  const timeLock = armLock(1);
  await assert.rejects(updatePasscode(passcodeEntry()), /time lock is active/);
  await timeLock;
  assert.equal((await readLockState()).kind, 'locked');
});

test('a passcode set while the challenge is open still blocks the change', async () => {
  await assert.rejects(authorizePause(async ({ kind }) => {
    assert.equal(kind, 'challenge');
    await updatePasscode(passcodeEntry());
    return true;
  }), /Settings became locked/);
});

test('a passcode changed during a prompt invalidates the old answer', async () => {
  await updatePasscode(passcodeEntry());
  await assert.rejects(authorizePause(async () => {
    await updatePasscode(passcodeEntry('new focus code'), PASSCODE);
    return PASSCODE;
  }), /passcode lock changed/);
});
