import assert from 'node:assert/strict';
import test from 'node:test';

import { createProtectionRead } from '../src/features/protection/protection-reads.ts';

/** Native reads that stay pending until the test settles them. */
function nativeReads() {
  const pending = [];
  return {
    pending,
    read: () => new Promise((resolve, reject) => pending.push({ resolve, reject })),
    get started() {
      return pending.length;
    },
  };
}

/** Background triggers that the test fires by hand. */
function triggers() {
  const active = new Set();
  let installs = 0;
  return {
    whileWatched(refresh) {
      installs += 1;
      active.add(refresh);
      return () => active.delete(refresh);
    },
    fire() {
      active.forEach((refresh) => refresh());
    },
    get installs() {
      return installs;
    },
    get active() {
      return active.size;
    },
  };
}

function ownedRead(options = {}) {
  const reads = nativeReads();
  const owner = createProtectionRead({ read: reads.read, failure: 'Could not read.', ...options });
  const seen = [];
  const release = owner.subscribe(() => seen.push(owner.getSnapshot()));
  return { reads, owner, seen, release };
}

const ready = (value) => ({ kind: 'ready', value });
const settled = () => new Promise(setImmediate);

test('a refresh keeps the last snapshot visible and marks it refreshing', async () => {
  const { reads, owner } = ownedRead();
  const first = owner.refresh();
  assert.deepEqual(owner.getSnapshot(), { kind: 'loading' });
  reads.pending[0].resolve(ready({ usedMs: 1 }));
  await first;
  assert.deepEqual(owner.getSnapshot(), { kind: 'ready', value: { usedMs: 1 }, refreshing: false });

  const second = owner.refresh();
  assert.deepEqual(owner.getSnapshot(), { kind: 'ready', value: { usedMs: 1 }, refreshing: true });
  reads.pending[1].resolve(ready({ usedMs: 2 }));
  await second;
  assert.deepEqual(owner.getSnapshot(), { kind: 'ready', value: { usedMs: 2 }, refreshing: false });
});

test('a failed read clears the stale snapshot, and the next read starts without the old failure', async () => {
  const { reads, owner } = ownedRead();
  const first = owner.refresh();
  reads.pending[0].resolve(ready('stale'));
  await first;

  const failing = owner.refresh();
  reads.pending[1].reject(new Error('native'));
  await failing;
  assert.deepEqual(owner.getSnapshot(), { kind: 'failed', message: 'Could not read.' });

  void owner.refresh();
  assert.deepEqual(owner.getSnapshot(), { kind: 'loading' });
});

test('a web preview reads as android-required, not as data', async () => {
  const { reads, owner } = ownedRead();
  const done = owner.refresh();
  reads.pending[0].resolve({ kind: 'android-required' });
  await done;
  assert.deepEqual(owner.getSnapshot(), { kind: 'android-required' });
});

test('each focus reads once, and background triggers exist only while a screen watches', async () => {
  const background = triggers();
  const { reads, owner } = ownedRead({ whileWatched: background.whileWatched });

  const blurFeeds = owner.watch();
  assert.equal(reads.started, 1, 'focus reads');
  assert.equal(background.active, 1);
  reads.pending[0].resolve(ready(1));
  await settled();

  const blurSettings = owner.watch();
  assert.equal(background.installs, 1, 'a second watcher shares the same triggers');
  reads.pending[1].resolve(ready(2));
  await settled();
  background.fire();
  assert.equal(reads.started, 3, 'a resume or poll reads while watched');
  blurSettings();
  blurSettings();
  assert.equal(background.active, 1, 'a repeated release counts once');

  blurFeeds();
  assert.equal(background.active, 0, 'the last blur removes the triggers');
  background.fire();
  assert.equal(reads.started, 3, 'no trigger after the last blur');
});

test('a detached owner starts no read and calls no listener', async () => {
  const { reads, owner, seen, release } = ownedRead();
  const done = owner.refresh();
  release();
  reads.pending[0].resolve(ready(1));
  await done;
  assert.deepEqual(seen, [], 'no listener call after teardown');

  await owner.refresh();
  assert.equal(reads.started, 1, 'no read after teardown');
});
