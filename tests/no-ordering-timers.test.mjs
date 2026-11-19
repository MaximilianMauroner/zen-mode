import assert from 'node:assert/strict';
import { join } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

import { ESLint } from 'eslint';

const root = fileURLToPath(new URL('..', import.meta.url));
const eslint = new ESLint({ cwd: root });

async function timerErrors(code, file = 'src/app/example.tsx') {
  const [result] = await eslint.lintText(code, { filePath: join(root, file) });
  return result.messages.filter((message) => message.ruleId === 'no-restricted-syntax');
}

const FRAME_WARMUP = `
function Screen({ refresh }) {
  useEffect(() => {
    const frame = requestAnimationFrame(() => { void refresh(); });
    return () => cancelAnimationFrame(frame);
  }, [refresh]);
}`;

const DIRECT_WARMUP = `
function Screen({ readLock, setLock }) {
  useEffect(() => {
    let active = true;
    void readLock().then((next) => { if (active) setLock(next); });
    return () => { active = false; };
  }, []);
}`;

test('a frame that defers a read is rejected with the fix in the message', async () => {
  const errors = await timerErrors(FRAME_WARMUP);
  assert.equal(errors.length, 1);
  assert.match(errors[0].message, /Wait for the mounted node, the committed state, or the result of the operation/);
});

test('the same read without the frame passes', async () => {
  assert.deepEqual(await timerErrors(DIRECT_WARMUP), []);
});

for (const call of [
  'setTimeout(run, 0)',
  'requestIdleCallback(run)',
  'queueMicrotask(run)',
  'window.setTimeout(run, 0)',
  'globalThis.requestAnimationFrame(run)',
  "globalThis['setTimeout'](run, 0)",
]) {
  test(`${call} is rejected`, async () => {
    assert.equal((await timerErrors(`${call};`)).length, 1);
  });
}

test('clearing a timer and setInterval are allowed', async () => {
  assert.deepEqual(await timerErrors('clearTimeout(timer); setInterval(tick, 60000);'), []);
});

test('the diagnostic poll may wait for wall-clock time', async () => {
  assert.deepEqual(await timerErrors('setTimeout(refresh, 1000);', 'src/app/report.tsx'), []);
});

test('the TypeScript rule leaves JavaScript tooling alone', async () => {
  assert.deepEqual(await timerErrors('setTimeout(done, 100);', 'tests/example.mjs'), []);
});
