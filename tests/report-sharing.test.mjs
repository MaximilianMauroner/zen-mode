import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';
import ts from 'typescript';
process.env.EXPO_PUBLIC_REPORTS_ORIGIN = 'https://inbox.test';
const reports = await import('../src/features/feedback/reports.ts');
delete process.env.EXPO_PUBLIC_REPORTS_ORIGIN;

const element = (type, props) => ({ type, props });
function find(node, predicate) {
  if (!node || typeof node !== 'object') return undefined;
  if (predicate(node)) return node;
  for (const child of [node.props?.children].flat(Infinity)) {
    const found = find(child, predicate);
    if (found) return found;
  }
}

async function report(recording = { state: 'complete', json: '{"events":[]}' }, shareError = false, testBuild = true) {
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
    useRef(initial) { return hooks.useState(() => ({ current: initial }))[0]; },
  };
  const imports = {
    react: hooks,
    'react/jsx-runtime': { jsx: element, jsxs: element },
    'react-native': { Platform: { OS: 'android', constants: { Release: '15' } } },
    'expo-constants': { __esModule: true, default: { expoConfig: { version: 'test' } } },
    'expo-router': { router: {}, useFocusEffect: (effect) => { focus = effect; } },
    '@/features/feedback/reports': reports,
    '@/features/protection/native': {
      diagnosticsAvailable: testBuild,
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
const checkbox = (view) => view.find((node) => node.props?.accessibilityLabel === 'Share a diagnostic recording');
const submit = (view) => view.find((node) => node.props?.title === 'Share report with recording');

const send = (view) => view.find((node) => node.props?.title === 'Send report');
const confirmed = (view) => view.find((node) => node.props?.children === 'Your report was sent.');

test('a problem goes to the inbox once and shows confirmation only after acceptance', async (t) => {
  const requests = [];
  let accept;
  t.mock.method(globalThis, 'fetch', (url, options) => {
    requests.push({ url, options });
    return new Promise((resolve) => { accept = resolve; });
  });
  const view = await report();
  field(view).props.onChange('The rule did not run.');
  checkbox(view).props.onPress();
  const press = send(view).props.onPress;
  press();
  press();
  assert.equal(requests.length, 1);
  assert.equal(requests[0].url, 'https://inbox.test/api/reports');
  assert.equal(requests[0].options.method, 'POST');
  assert.equal(requests[0].options.credentials, 'omit');
  assert.deepEqual(JSON.parse(requests[0].options.body), {
    app: 'zen-mode', kind: 'bug', message: 'The rule did not run.', page: '/report',
    context: 'Zen Mode test · android 15',
  });
  assert.deepEqual(view.shares, []);
  assert.equal(confirmed(view), undefined);
  accept(new Response(null, { status: 201 }));
  await new Promise(setImmediate);
  assert.ok(confirmed(view));
  assert.equal(field(view).props.value, '');
});

test('a public build keeps the idea draft after a failed send and can retry', async (t) => {
  const fetch = t.mock.method(globalThis, 'fetch', async () => new Response(null, { status: 500 }));
  const view = await report(undefined, false, false);
  assert.equal(checkbox(view), undefined);
  assert.equal(view.find((node) => node.props?.title === 'Record or review diagnostics'), undefined);
  view.find((node) => node.props?.title === 'Idea').props.onPress();
  const idea = () => view.find((node) => node.props?.label === 'Your idea');
  idea().props.onChange('Add a timer.');
  send(view).props.onPress();
  await new Promise(setImmediate);
  assert.equal(JSON.parse(fetch.mock.calls[0].arguments[1].body).kind, 'feature');
  assert.equal(idea().props.value, 'Add a timer.');
  assert.equal(confirmed(view), undefined);
  assert.ok(view.find((node) => node.props?.message === 'The report could not be sent. Try again later.'));
  fetch.mock.mockImplementation(async () => new Response(null, { status: 201 }));
  send(view).props.onPress();
  await new Promise(setImmediate);
  assert.ok(confirmed(view));
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

test('switching to an idea hides the recording share action', async () => {
  const view = await report();
  field(view).props.onChange('Add a timer.');
  checkbox(view).props.onPress();
  view.find((node) => node.props?.title === 'Idea').props.onPress();
  assert.equal(submit(view), undefined);
  assert.ok(send(view));
});

test('share errors remain visible and do not clear the report', async () => {
  const view = await report(undefined, true);
  field(view).props.onChange('The rule did not run.');
  checkbox(view).props.onPress();
  submit(view).props.onPress();
  await new Promise(setImmediate);
  assert.equal(field(view).props.value, 'The rule did not run.');
  assert.ok(view.find((node) => node.props?.message === 'Could not open the share sheet. Try again.'));
});
