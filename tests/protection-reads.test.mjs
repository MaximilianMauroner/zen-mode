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

test('a refresh during a read waits for a newer read, so fresh data confirms a write', async () => {
  const { reads, owner } = ownedRead();
  void owner.refresh();
  let confirmed = false;
  const afterWrite = owner.refresh().then(() => {
    confirmed = true;
  });

  reads.pending[0].resolve(ready({ protectionEnabled: true }));
  await settled();
  assert.equal(confirmed, false, 'a read that started before the request does not confirm it');
  assert.equal(reads.started, 2, 'a newer read starts after the older one');

  reads.pending[1].resolve(ready({ protectionEnabled: false }));
  await afterWrite;
  assert.deepEqual(owner.getSnapshot(), { kind: 'ready', value: { protectionEnabled: false }, refreshing: false });
});

test('overlapping requests share one follow-up read, and a request during it queues one more', async () => {
  const { reads, owner } = ownedRead();
  void owner.refresh();
  const waiting = [owner.refresh(), owner.refresh(), owner.refresh()];
  reads.pending[0].resolve(ready(1));
  await settled();
  assert.equal(reads.started, 2, 'one in-flight read and one follow-up');

  const later = owner.refresh();
  reads.pending[1].resolve(ready(2));
  await Promise.all(waiting);
  assert.equal(reads.started, 3, 'the request made during the follow-up gets its own read');
  reads.pending[2].resolve(ready(3));
  await later;
  assert.equal(reads.started, 3);
});

test('teardown releases a queued follow-up without reading', async () => {
  const { reads, owner, release } = ownedRead();
  void owner.refresh();
  const queued = owner.refresh();
  release();
  await queued;
  reads.pending[0].resolve(ready(1));
  await settled();
  assert.equal(reads.started, 1, 'no read after teardown');
});

test('a StrictMode remount keeps one set of triggers and reads again after the first read', async () => {
  const reads = nativeReads();
  const background = triggers();
  const owner = createProtectionRead({ read: reads.read, failure: 'Could not read.', whileWatched: background.whileWatched });
  const seen = [];
  const listener = () => seen.push(owner.getSnapshot());

  let unsubscribe = owner.subscribe(listener);
  let blur = owner.watch();
  unsubscribe();
  blur();
  unsubscribe = owner.subscribe(listener);
  blur = owner.watch();
  assert.equal(background.active, 1, 'one live set of triggers');

  reads.pending[0].resolve(ready('mounted'));
  await settled();
  assert.equal(reads.started, 2, 'the remount focus gets its own read');
  reads.pending[1].resolve(ready('remounted'));
  await settled();
  assert.deepEqual(seen.at(-1), { kind: 'ready', value: 'remounted', refreshing: false });

  blur();
  unsubscribe();
  background.fire();
  assert.equal(background.active, 0);
  assert.equal(reads.started, 2);
});
