import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import test from 'node:test';
import vm from 'node:vm';
import ts from 'typescript';
import * as controlState from '../src/features/protection/control-state.ts';

const app = { packageName: 'org.mozilla.firefox', label: 'Firefox' };
const element = (type, props) => ({ type, props });

function find(node, predicate) {
  if (!node || typeof node !== 'object') return undefined;
  if (predicate(node)) return node;
  for (const child of [node.props?.children].flat(Infinity)) {
    const found = find(child, predicate);
    if (found) return found;
  }
}

function localState() {
  const values = [];
  let cursor = 0;
  const hooks = {
    useState(initial) {
      const index = cursor++;
      if (!(index in values)) values[index] = typeof initial === 'function' ? initial() : initial;
      return [values[index], (next) => { values[index] = typeof next === 'function' ? next(values[index]) : next; }];
    },
    useRef(initial) { return hooks.useState(() => ({ current: initial }))[0]; },
    useMemo: (read) => read(),
    useCallback: (callback) => callback,
    useEffect() {},
  };
  return { hooks, render(component, props) { cursor = 0; return component(props); } };
}

function fixture() {
  const screenState = localState();
  const pickerState = localState();
  let read = { kind: 'ready', refreshing: false, value: { limits: [], intents: [], rolling: [], installedApps: [app] } };
  let AppPicker;
  const nativeViews = Object.fromEntries(['FlatList', 'KeyboardAvoidingView', 'Modal', 'Pressable', 'Text', 'TextInput', 'View'].map((name) => [name, name]));

  function load(path, state) {
    const source = process.env.PICKER_TEST_SOURCE_REF
      ? execFileSync('git', ['show', `${process.env.PICKER_TEST_SOURCE_REF}:${path}`], { encoding: 'utf8' })
      : readFileSync(new URL(`../${path}`, import.meta.url), 'utf8');
    const { outputText } = ts.transpileModule(source, {
      compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX, target: ts.ScriptTarget.ES2022 },
    });
    const exports = {};
    const imports = {
      react: state.hooks,
      'react/jsx-runtime': { jsx: element, jsxs: element, Fragment: 'Fragment' },
      'react-native': { ...nativeViews, Platform: { OS: 'android' } },
      'react-native-reanimated': { useReducedMotion: () => true },
      'expo-router': { useNavigation: () => ({ isFocused: () => true }), router: {} },
      'expo-router/js-top-tabs': { TopTabs: { Screen: 'TopTabs.Screen' } },
      '@/features/protection/control-state': controlState,
      '@/features/protection/use-protection-read': { useScreenRead: () => ({ read, refresh() {} }) },
      '@/components/ui/weakening-gate': { useWeakeningGate: () => ({}), WeakeningGateDialog: 'WeakeningGateDialog' },
      '@/components/ui/app-picker': { AppPicker },
      '@/theme/colors': { colors: {} },
    };
    vm.runInNewContext(outputText, {
      exports,
      require: (name) => imports[name] ?? new Proxy({}, { get: (_, key) => `${name}:${String(key)}` }),
    }, { filename: path });
    return exports;
  }

  AppPicker = load('src/components/ui/app-picker.tsx', pickerState).AppPicker;
  const Limits = load('src/app/(controls)/limits.tsx', screenState).default;
  const screen = () => screenState.render(Limits);
  const pickerProps = () => find(screen(), (node) => node.type === AppPicker).props;
  const picker = () => pickerState.render(AppPicker, pickerProps());
  const input = () => find(picker(), (node) => node.type === 'TextInput');
  const row = () => {
    const list = find(picker(), (node) => node.type === 'FlatList');
    return list.props.renderItem({ item: list.props.data[0] });
  };
  const open = () => find(screen(), (node) => node.props?.title === '＋ Add app').props.onPress();
  return {
    open, screen, pickerProps, input, row,
    refresh(value) { read = { ...read, refreshing: value }; },
  };
}

test('an accepted picker selection opens that app and clears the query', () => {
  const view = fixture();
  view.open();
  view.input().props.onChangeText('fire');
  view.row().props.onPress();
  assert.equal(view.pickerProps().visible, false);
  const header = find(view.screen(), (node) => node.props?.title === 'Firefox');
  assert.ok(header, 'the selected app editor is visible');
  header.props.onBack();
  view.open();
  assert.equal(view.input().props.value, '');
});

test('a tap rejected during refresh keeps the picker and query until selection is allowed', () => {
  const view = fixture();
  view.open();
  view.input().props.onChangeText('fire');
  view.refresh(true);
  const row = view.row();
  row.props.onPress();
  assert.equal(view.pickerProps().visible, true, 'a rejected selection does not close the picker');
  assert.equal(view.input().props.value, 'fire', 'a rejected selection keeps the search');
  assert.equal(find(view.screen(), (node) => node.props?.title === 'Firefox'), undefined);
  assert.equal(row.props.disabled, true);
  assert.equal(row.props.accessibilityState.disabled, true);

  view.refresh(false);
  assert.equal(view.row().props.disabled, false);
  view.row().props.onPress();
  assert.equal(view.pickerProps().visible, false);
  assert.ok(find(view.screen(), (node) => node.props?.title === 'Firefox'));
});

test('closing the picker without selecting keeps the query on reopen', () => {
  const view = fixture();
  view.open();
  view.input().props.onChangeText('fire');
  view.pickerProps().onClose();
  assert.equal(view.pickerProps().visible, false);
  view.open();
  assert.equal(view.input().props.value, 'fire');
});
