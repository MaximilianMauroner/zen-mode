import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';
import ts from 'typescript';

function render(mode) {
  let back = false;
  const element = (type, props) => ({ type, props });
  const imports = {
    'react/jsx-runtime': { jsx: element, jsxs: element },
    'react-native': { Pressable: 'Pressable', Text: 'Text', View: 'View' },
    'react-native-safe-area-context': { SafeAreaView: 'SafeAreaView' },
    'expo-router': { router: { back: () => { back = true; } }, useLocalSearchParams: () => ({ mode, appName: 'Example' }) },
  };
  const source = readFileSync(new URL('../src/app/end-screen.tsx', import.meta.url), 'utf8');
  const { outputText } = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX } });
  const module = { exports: {} };
  vm.runInNewContext(outputText, { exports: module.exports, require: (name) => imports[name] });
  const root = module.exports.default();
  const children = root.props.children.props.children;
  return { children, wentBack: () => back };
}

test('the route shows the chosen screen and its app name', () => {
  const kids = render('kids');
  assert.equal(kids.children[0].props.children, "Time's up.\nGo play.");
  assert.equal(kids.children[1].props.children, 'Example');
  kids.children[2].props.onPress();
  assert.equal(kids.wentBack(), true);
  assert.equal(render('focus').children[0].props.children, 'Daily limit reached');
});
