import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';
import ts from 'typescript';

const element = (type, props) => ({ type, props });
function find(node, predicate) {
  if (!node || typeof node !== 'object') return undefined;
  if (predicate(node)) return node;
  for (const child of [node.props?.children].flat(Infinity)) {
    const found = find(child, predicate);
    if (found) return found;
  }
}

async function diagnostics() {
  const values = [];
  let cursor = 0;
  let focus;
  let recording = { state: 'empty' };
  let started;
  const hooks = {
    useState(initial) {
      const index = cursor++;
      if (!(index in values)) values[index] = typeof initial === 'function' ? initial() : initial;
      return [values[index], (value) => { values[index] = value; }];
    },
    useRef(initial) { return hooks.useState(() => ({ current: initial }))[0]; },
    useCallback: (callback) => callback,
    useEffect() {},
  };
  const imports = {
    react: hooks,
    'react/jsx-runtime': { jsx: element, jsxs: element },
    'react-native': { Platform: { OS: 'android' } },
    'expo-router': { router: {}, useFocusEffect: (effect) => { focus = effect; } },
    '@/features/protection/native': {
      async getDiagnostics() { return recording; },
      async startDiagnostics(seconds) { started = seconds; recording = { state: 'recording', remainingSeconds: seconds }; },
      async stopDiagnostics() { recording = { state: 'complete', json: '{"events":[]}' }; },
    },
    '@/theme/colors': { colors: {} },
  };
  const source = readFileSync(new URL('../src/app/diagnostics.tsx', import.meta.url), 'utf8');
  const { outputText } = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX } });
  const module = { exports: {} };
  vm.runInNewContext(outputText, { exports: module.exports, require: (name) => imports[name] ?? new Proxy({}, { get: (_, key) => `${name}:${String(key)}` }) });
  const render = () => { cursor = 0; return module.exports.default(); };
  render();
  focus();
  await new Promise(setImmediate);
  return { find: (predicate) => find(render(), predicate), started: () => started };
}

test('the chosen seconds control the recording, which can be stopped and reviewed', async () => {
  const view = await diagnostics();
  view.find((node) => node.props?.label === 'Duration unit').props.onSelect('seconds');
  view.find((node) => node.props?.accessibilityLabel === 'Recording duration').props.onChangeText('12');
  view.find((node) => node.props?.title === 'Start recording').props.onPress();
  await new Promise(setImmediate);
  assert.equal(view.started(), 12);
  view.find((node) => node.props?.title === 'Stop recording').props.onPress();
  await new Promise(setImmediate);
  view.find((node) => node.props?.title === 'Review recording').props.onPress();
  assert.ok(view.find((node) => node.props?.selectable && node.props.children === '{"events":[]}'));
});

test('invalid durations cannot start a recording', async () => {
  const view = await diagnostics();
  const input = () => view.find((node) => node.props?.accessibilityLabel === 'Recording duration');
  input().props.onChangeText('0');
  assert.equal(view.find((node) => node.props?.title === 'Start recording').props.disabled, true);
  input().props.onChangeText('61');
  assert.equal(view.find((node) => node.props?.title === 'Start recording').props.disabled, true);
});
