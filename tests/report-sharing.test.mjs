import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';
import ts from 'typescript';
import * as reports from '../src/features/feedback/reports.ts';

const element = (type, props) => ({ type, props });
function find(node, predicate) {
  if (!node || typeof node !== 'object') return undefined;
  if (predicate(node)) return node;
  for (const child of [node.props?.children].flat(Infinity)) {
    const found = find(child, predicate);
    if (found) return found;
  }
}

async function report(recording = { state: 'complete', json: '{"events":[]}' }, shareError = false) {
  const values = [];
  let focus;
  let cursor = 0;
  let nextTimer = 0;
  const timers = new Map();
  const shares = [];
  const hooks = {
    useState(initial) {
      const index = cursor++;
      if (!(index in values)) values[index] = typeof initial === 'function' ? initial() : initial;
      return [values[index], (value) => { values[index] = value; }];
    },
    useCallback: (callback) => callback,
  };
  const imports = {
    react: hooks,
    'react/jsx-runtime': { jsx: element, jsxs: element },
    'react-native': { Platform: { OS: 'android', constants: { Release: '15' } }, Share: { async share(value) { if (shareError) throw Error('failed'); shares.push({ kind: 'text', ...value }); } } },
    'expo-constants': { default: { expoConfig: { version: 'test' } } },
    'expo-router': { router: {}, useFocusEffect: (effect) => { focus = effect; } },
    '@/features/feedback/reports': reports,
    '@/features/protection/native': {
      async getDiagnostics() { return recording; },
      async shareDiagnostics(message) { if (shareError) throw Error('failed'); shares.push({ kind: 'attachment', message }); },
    },
    '@/theme/colors': { colors: {} },
  };
  const source = readFileSync(new URL('../src/app/report.tsx', import.meta.url), 'utf8');
  const { outputText } = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX } });
  const module = { exports: {} };
  vm.runInNewContext(outputText, {
    exports: module.exports,
    require: (name) => imports[name] ?? new Proxy({}, { get: (_, key) => `${name}:${String(key)}` }),
    setTimeout: (callback) => { const id = ++nextTimer; timers.set(id, callback); return id; },
    clearTimeout: (id) => timers.delete(id),
  });
  const render = () => { cursor = 0; return module.exports.default(); };
  render();
  const blur = focus();
  await new Promise(setImmediate);
  return {
    find: (predicate) => find(render(), predicate),
    finishRecording: () => { recording = { state: 'complete', json: '{"events":[]}' }; },
    tick: () => { const callbacks = [...timers.values()]; timers.clear(); callbacks.forEach((callback) => callback()); },
    blur,
    shares,
  };
}

const field = (view) => view.find((node) => node.props?.label === 'What happened?');
const checkbox = (view) => view.find((node) => node.props?.accessibilityLabel === 'Include diagnostic recording');
const submit = (view) => view.find((node) => node.props?.title === 'Share report');

test('a problem report shares only text until the user checks the recording', async () => {
  const view = await report();
  field(view).props.onChange('The rule did not run.');
  assert.equal(checkbox(view).props.accessibilityState.checked, false);
  submit(view).props.onPress();
  await new Promise(setImmediate);
  assert.equal(view.shares.length, 1);
  assert.equal(view.shares[0].kind, 'text');
  assert.match(view.shares[0].message, /The rule did not run/);
});

test('checking a completed recording attaches it to the problem report', async () => {
  const view = await report();
  field(view).props.onChange('The rule did not run.');
  checkbox(view).props.onPress();
  assert.equal(checkbox(view).props.accessibilityState.checked, true);
  submit(view).props.onPress();
  await new Promise(setImmediate);
  assert.equal(view.shares.length, 1);
  assert.equal(view.shares[0].kind, 'attachment');
  assert.match(view.shares[0].message, /The rule did not run/);
});

test('a recording in progress cannot be attached', async () => {
  const view = await report({ state: 'recording', remainingSeconds: 10 });
  assert.equal(checkbox(view).props.disabled, true);
});

test('a recording that finishes while the report is open can be attached', async () => {
  const view = await report({ state: 'recording', remainingSeconds: 1 });
  assert.equal(checkbox(view).props.disabled, true);
  view.finishRecording();
  view.tick();
  await new Promise(setImmediate);
  assert.equal(checkbox(view).props.disabled, false);
  field(view).props.onChange('The rule did not run.');
  checkbox(view).props.onPress();
  submit(view).props.onPress();
  await new Promise(setImmediate);
  assert.equal(view.shares[0].kind, 'attachment');
});

test('leaving the report stops checking for a finished recording', async () => {
  const view = await report({ state: 'recording', remainingSeconds: 1 });
  view.blur();
  view.finishRecording();
  view.tick();
  await new Promise(setImmediate);
  assert.equal(checkbox(view).props.disabled, true);
});

test('an idea never attaches a previously selected recording', async () => {
  const view = await report();
  field(view).props.onChange('Add a timer.');
  checkbox(view).props.onPress();
  view.find((node) => node.props?.title === 'Idea').props.onPress();
  submit(view).props.onPress();
  await new Promise(setImmediate);
  assert.equal(view.shares.length, 1);
  assert.equal(view.shares[0].kind, 'text');
  assert.match(view.shares[0].message, /^Idea/);
});

test('share errors remain visible and do not clear the report', async () => {
  const view = await report(undefined, true);
  field(view).props.onChange('The rule did not run.');
  submit(view).props.onPress();
  await new Promise(setImmediate);
  assert.equal(field(view).props.value, 'The rule did not run.');
  assert.ok(view.find((node) => node.props?.message === 'Could not open the share sheet. Try again.'));
});
