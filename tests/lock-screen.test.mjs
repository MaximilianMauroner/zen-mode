import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

import { Window } from 'happy-dom';
import * as React from 'react';
import { createRoot } from 'react-dom/client';
import * as jsxRuntime from 'react/jsx-runtime';
import ts from 'typescript';

const source = readFileSync(new URL('../src/app/(controls)/lock.tsx', import.meta.url), 'utf8');
const { outputText } = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX },
});

test('a late warm-up read cannot replace the lock shown after focus', async () => {
  const window = new Window();
  const container = window.document.createElement('div');
  const root = createRoot(container);
  globalThis.window = window;
  globalThis.IS_REACT_ACT_ENVIRONMENT = true;

  const warm = Promise.withResolvers();
  const refresh = Promise.withResolvers();
  const reads = [warm.promise, refresh.promise];
  const Focus = React.createContext(false);
  const empty = () => null;
  const imports = {
    react: React,
    'react/jsx-runtime': jsxRuntime,
    'react-native': {
      Text: ({ accessibilityRole, children }) => React.createElement(accessibilityRole === 'header' ? 'h1' : 'span', {}, children),
      View: empty,
    },
    'expo-router': {
      useFocusEffect(effect) {
        const focused = React.useContext(Focus);
        React.useEffect(() => focused ? effect() : undefined, [effect, focused]);
      },
    },
    'expo-router/js-top-tabs': { TopTabs: { Screen: empty } },
    '@/components/ui/screen': { Screen: ({ children }) => children, ErrorNote: empty },
    '@/components/ui/weakening-gate': { useWeakeningGate: empty, WeakeningGateDialog: empty, PasscodeInput: empty },
    '@/features/protection/lock': { readLockState: () => reads.shift(), LOCK_DAY_OPTIONS: [], formatRemaining: () => '' },
    '@/theme/colors': { colors: {} },
  };
  const exports = {};
  const emptyComponents = new Proxy({}, { get: () => empty });
  vm.runInNewContext(outputText, {
    exports,
    require: (name) => imports[name] ?? emptyComponents,
    setInterval: () => 1,
    clearInterval: empty,
  });
  const render = (focused) => root.render(
    React.createElement(Focus.Provider, { value: focused }, React.createElement(exports.default)),
  );

  try {
    await React.act(() => render(false));
    await React.act(() => render(true));
    assert.equal(reads.length, 0);

    await React.act(() => refresh.resolve({ kind: 'locked', endsAt: Date.now() + 60_000 }));
    assert.equal(container.querySelector('h1')?.textContent, 'Settings locked');

    await React.act(() => warm.resolve({ kind: 'open' }));
    assert.equal(container.querySelector('h1')?.textContent, 'Settings locked');
  } finally {
    await React.act(() => root.unmount());
    delete globalThis.IS_REACT_ACT_ENVIRONMENT;
    delete globalThis.window;
    await window.close();
  }
});
