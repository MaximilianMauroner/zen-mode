import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';
import ts from 'typescript';
import { RECOMMENDED_DEFAULTS_SUMMARY } from '../src/features/protection/recommended-defaults.ts';

const element = (type, props) => ({ type, props });
function find(node, predicate) {
  if (!node || typeof node !== 'object') return undefined;
  if (predicate(node)) return node;
  for (const child of [node.props?.children].flat(Infinity)) {
    const found = find(child, predicate);
    if (found) return found;
  }
}

async function setup({ accessGranted = true } = {}) {
  const values = [];
  const effects = [];
  const calls = { consent: 0, settings: 0, defaults: 0, completed: 0, destination: null };
  let cursor = 0;
  let onAppState;
  const hooks = {
    useState(initial) {
      const index = cursor++;
      if (!(index in values)) values[index] = typeof initial === 'function' ? initial() : initial;
      return [values[index], (value) => { values[index] = typeof value === 'function' ? value(values[index]) : value; }];
    },
    useRef(initial) { return hooks.useState(() => ({ current: initial }))[0]; },
    useCallback: (callback) => callback,
    useEffect: (effect) => effects.push(effect),
  };
  const imports = {
    react: hooks,
    'react/jsx-runtime': { jsx: element, jsxs: element },
    'react-native': { Platform: { OS: 'android' }, AppState: { addEventListener: (_event, callback) => { onAppState = callback; return { remove() {} }; } } },
    'expo-router': { router: { replace: (path) => { calls.destination = path; } } },
    '@/features/protection/setup': {
      async hasCompletedSetup() { return false; },
      async acceptSetupConsent() { calls.consent++; },
      async confirmAndroidAccess() { if (!accessGranted) throw Error('Enable Android access first.'); },
      async markSetupComplete() { calls.completed++; },
    },
    '@/features/protection/recommended-defaults': {
      RECOMMENDED_DEFAULTS_SUMMARY,
      async applyRecommendedDefaults() { calls.defaults++; },
    },
    '@/features/protection/native': { async openAccessibilitySettings() { calls.settings++; } },
    '@/theme/colors': { colors: {} },
  };
  const source = readFileSync(new URL('../src/app/setup.tsx', import.meta.url), 'utf8');
  const { outputText } = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX } });
  const module = { exports: {} };
  vm.runInNewContext(outputText, { Error, exports: module.exports, require: (name) => imports[name] ?? new Proxy({}, { get: (_, key) => `${name}:${String(key)}` }) });
  const render = () => { cursor = 0; return module.exports.default(); };
  render();
  effects.forEach((effect) => effect());
  await new Promise(setImmediate);
  return {
    find: (predicate) => find(render(), predicate),
    returnToApp: async () => { onAppState('active'); await new Promise(setImmediate); },
    calls,
  };
}

const choices = (view) => view.find((node) => node.props?.label === 'Starting rules');
const consent = (view) => view.find((node) => node.props?.accessibilityRole === 'checkbox');
const proceed = (view) => view.find((node) => node.props?.title === 'Continue to Android access');

test('setup requires both a starting choice and explicit consent', async () => {
  const view = await setup();
  await proceed(view).props.onPress();
  assert.equal(view.calls.consent, 0);
  choices(view).props.onSelect('customize');
  await proceed(view).props.onPress();
  assert.equal(view.calls.consent, 0);
  consent(view).props.onPress();
  await proceed(view).props.onPress();
  assert.equal(view.calls.consent, 1);
  assert.equal(view.calls.settings, 1);
  await view.returnToApp();
  assert.equal(view.calls.defaults, 0);
  assert.equal(view.calls.completed, 1);
  assert.equal(view.calls.destination, '/?setup=customize');
});

test('default rules are shown and applied only after Android access is confirmed', async () => {
  const view = await setup();
  choices(view).props.onSelect('defaults');
  for (const line of RECOMMENDED_DEFAULTS_SUMMARY) {
    assert.ok(view.find((node) => node.props?.children === line));
  }
  consent(view).props.onPress();
  await proceed(view).props.onPress();
  assert.equal(view.calls.defaults, 0);
  await view.returnToApp();
  assert.equal(view.calls.defaults, 1);
  assert.equal(view.calls.completed, 1);
  assert.equal(view.calls.destination, '/');
});

test('setup does not complete when Android access was not granted', async () => {
  const view = await setup({ accessGranted: false });
  choices(view).props.onSelect('defaults');
  consent(view).props.onPress();
  await proceed(view).props.onPress();
  await view.returnToApp();
  assert.equal(view.calls.defaults, 0);
  assert.equal(view.calls.completed, 0);
  assert.ok(view.find((node) => node.props?.message === 'Enable Android access first.'));
});
