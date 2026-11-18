import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';
import ts from 'typescript';

import * as challenge from '../src/features/protection/weakening-challenge.ts';

const source = readFileSync(new URL('../src/components/ui/weakening-gate.tsx', import.meta.url), 'utf8');
const { outputText } = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX },
});

function mountGate() {
  const values = [];
  let cursor = 0;
  const hooks = {
    useState(initial) {
      const index = cursor++;
      if (!(index in values)) values[index] = initial;
      return [values[index], (value) => { values[index] = value; }];
    },
    useRef: (current) => hooks.useState({ current })[0],
    useCallback: (callback) => callback,
    useEffect() {},
  };
  const element = (type, props) => ({ type, props });
  const imports = {
    react: hooks,
    'react/jsx-runtime': { jsx: element, jsxs: element },
    'react-native': { Platform: { OS: 'android' } },
    'react-native-reanimated': { useReducedMotion: () => false },
    '@/features/protection/weakening-challenge': challenge,
  };
  const exports = {};
  vm.runInNewContext(outputText, { Error, exports, require: (name) => imports[name] ?? {} });
  return () => {
    cursor = 0;
    const gate = exports.useWeakeningGate();
    return { gate, dialog: exports.WeakeningGateDialog({ gate, embedded: true }) };
  };
}

function find(node, predicate) {
  if (!node?.props) return undefined;
  if (predicate(node.props)) return node.props;
  for (const child of [node.props.children].flat()) {
    const found = find(child, predicate);
    if (found) return found;
  }
}

test('a wrong passcode leaves the embedded dialog open with an announced error', async () => {
  const render = mountGate();
  const result = render().gate.request({
    kind: 'passcode',
    action: 'pause protection',
    async verifyPasscode(answer) {
      if (answer !== 'correct code') throw Error('Incorrect passcode.');
    },
  });
  const input = () => find(render().dialog, (props) => props.label === 'Current passcode');
  const submit = () => find(render().dialog, (props) => props.title === 'Continue').onPress();

  assert.equal(input().autoFocus, true);
  input().onChangeText('wrong code');
  await submit();
  assert.ok(input());
  const feedback = find(render().dialog, (props) => props.children === 'Incorrect passcode.');
  assert.equal(feedback?.accessibilityLiveRegion, 'polite');

  input().onChangeText('correct code');
  await submit();
  assert.equal(await result, 'correct code');
  assert.equal(render().dialog, null);
});
