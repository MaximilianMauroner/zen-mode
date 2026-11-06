import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';
import ts from 'typescript';
import * as controlState from '../src/features/protection/control-state.ts';

const element = (type, props) => ({ type, props });
function find(node, predicate) {
  if (!node || typeof node !== 'object') return undefined;
  if (predicate(node)) return node;
  for (const child of [node.props?.children].flat(Infinity)) {
    const found = find(child, predicate);
    if (found) return found;
  }
}

async function settings(exportError) {
  const values = [];
  const effects = [];
  let cursor = 0;
  let exports = 0;
  let textShares = 0;
  const hooks = {
    useState(initial) {
      const index = cursor++;
      if (!(index in values)) values[index] = typeof initial === 'function' ? initial() : initial;
      return [values[index], (value) => { values[index] = value; }];
    },
    useRef(initial) { return hooks.useState(() => ({ current: initial }))[0]; },
    useCallback: (callback) => callback,
    useEffect: (effect) => { effects.push(effect); },
  };
  const imports = {
    react: hooks,
    'react/jsx-runtime': { jsx: element, jsxs: element },
    'react-native': { Share: { async share() { textShares++; } } },
    'expo-constants': { default: { expoConfig: { version: 'test' } } },
    '@/features/protection/control-state': controlState,
    '@/features/protection/native': {
      async getXDiagnostics() { return { state: 'complete', json: '{"events":[]}' }; },
      async shareXDiagnostics() { exports++; if (exportError) throw exportError; },
    },
    '@/features/protection/guard-status-context': {
      useGuardStatusFocus() {},
      useSharedGuardStatus: () => ({ read: { kind: 'ready', value: { available: true }, refreshing: false } }),
    },
    '@/features/protection/feed-presentation': { getFeedPresentation: () => ({}) },
    '@/components/ui/weakening-gate': { useWeakeningGate: () => ({}) },
    '@/theme/colors': { colors: {} },
  };
  const source = process.env.X_EXPORT_TEST_SOURCE_REF
    ? execFileSync('git', ['show', `${process.env.X_EXPORT_TEST_SOURCE_REF}:src/app/settings.tsx`], { encoding: 'utf8' })
    : readFileSync(new URL('../src/app/settings.tsx', import.meta.url), 'utf8');
  const { outputText } = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX },
  });
  const module = { exports: {} };
  vm.runInNewContext(outputText, {
    exports: module.exports,
    require: (name) => imports[name] ?? new Proxy({}, { get: (_, key) => `${name}:${String(key)}` }),
    setInterval: () => 1,
    clearInterval() {},
  });
  const render = () => { cursor = 0; return module.exports.default(); };
  render();
  effects.forEach((effect) => effect());
  await new Promise(setImmediate);
  return {
    button: () => find(render(), (node) => node.props?.title === 'Share diagnostic report'),
    action: () => find(render(), (node) => node.props?.action)?.props.action,
    counts: () => ({ exports, textShares }),
  };
}

test('Settings shares a completed X report through the native file exporter', async () => {
  const view = await settings();
  view.button().props.onPress();
  assert.equal(view.button().props.disabled, true);
  await new Promise(setImmediate);
  assert.deepEqual(view.counts(), { exports: 1, textShares: 0 });
  assert.equal(view.button().props.disabled, false);
  assert.equal(view.action().kind, 'idle');
});

test('an X export error is visible and releases the Settings controls', async () => {
  const view = await settings(new Error('The report could not be shared.'));
  view.button().props.onPress();
  await new Promise(setImmediate);
  assert.equal(view.action().kind, 'failed');
  assert.equal(view.button().props.disabled, false);
});
