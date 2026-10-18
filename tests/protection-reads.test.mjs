import assert from 'node:assert/strict';
import test from 'node:test';

import { createProtectionRead } from '../src/features/protection/protection-reads.ts';

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

function screen(owner) {
  const seen = [];
  const unsubscribe = owner.subscribe(() => seen.push(owner.getSnapshot()));
  return { seen, unsubscribe };
}

const PRE_SETUP = { serviceEnabled: false, protectionEnabled: false };
const POST_SETUP = { serviceEnabled: true, protectionEnabled: true };

test('detach: a ready snapshot does not reach the next attachment', async () => {
  const reads = nativeReads();
  const owner = createProtectionRead({ read: reads.read, failure: 'Could not read.' });
  const controls = screen(owner);
  const first = owner.refresh();
  reads.pending[0].resolve(ready(PRE_SETUP));
  await first;
  controls.unsubscribe();

  screen(owner);
  assert.deepEqual(owner.getSnapshot(), { kind: 'loading' }, 'the remount shows Checking, not Android access needed');
});

test('detach: a read pending at detach does not publish after it resolves', async () => {
  const reads = nativeReads();
  const owner = createProtectionRead({ read: reads.read, failure: 'Could not read.' });
  const controls = screen(owner);
  const pending = owner.refresh();
  controls.unsubscribe();
  reads.pending[0].resolve(ready(PRE_SETUP));
  await pending;

  const remount = screen(owner);
  assert.deepEqual(owner.getSnapshot(), { kind: 'loading' });
  assert.deepEqual(remount.seen, []);
  assert.equal(reads.started, 1, 'no read while detached');
});

test('detach: a failure pending at detach does not reach the next attachment', async () => {
  const reads = nativeReads();
  const owner = createProtectionRead({ read: reads.read, failure: 'Could not read.' });
  const controls = screen(owner);
  const pending = owner.refresh();
  controls.unsubscribe();
  reads.pending[0].reject(new Error('native'));
  await pending;

  screen(owner);
  assert.deepEqual(owner.getSnapshot(), { kind: 'loading' });
});

test('detach: a remount during the old read waits for a newer read, and the old result is never shown', async () => {
  const reads = nativeReads();
  const owner = createProtectionRead({ read: reads.read, failure: 'Could not read.' });
  const controls = screen(owner);
  const blur = owner.watch();
  blur();
  controls.unsubscribe();

  const remount = screen(owner);
  let confirmed = false;
  const focus = owner.refresh().then(() => {
    confirmed = true;
  });
  assert.equal(reads.started, 1, 'one in-flight read, so the remount request queues');

  reads.pending[0].resolve(ready(PRE_SETUP));
  await settled();
  assert.deepEqual(remount.seen.filter((state) => state.kind === 'ready'), [], 'the old result is not published');
  assert.equal(confirmed, false, 'the old completion does not confirm the remount request');
  assert.equal(reads.started, 2, 'the follow-up read starts after the old read ends');

  reads.pending[1].resolve(ready(POST_SETUP));
  await focus;
  assert.deepEqual(owner.getSnapshot(), { kind: 'ready', value: POST_SETUP, refreshing: false });
});

test('detach: an old rejection after reattach cannot fail or confirm the new request', async () => {
  const { reads, owner, seen, release } = ownedRead();
  const oldRead = owner.refresh();
  release();
  const remount = screen(owner);
  let confirmed = false;
  const freshRead = owner.refresh().then(() => { confirmed = true; });
  assert.deepEqual(owner.getSnapshot(), { kind: 'loading' });
  assert.equal(reads.started, 1);

  reads.pending[0].reject(new Error('old native failure'));
  await oldRead;
  await settled();
  assert.deepEqual(owner.getSnapshot(), { kind: 'loading' });
  assert.deepEqual(seen, [], 'no callback to the detached screen');
  assert.deepEqual(remount.seen, [], 'no stale failure reaches the remount');
  assert.equal(confirmed, false, 'the old rejection cannot confirm a fresh request');
  assert.equal(reads.started, 2, 'the fresh read waits for the old physical read');

  reads.pending[1].resolve(ready(POST_SETUP));
  await freshRead;
  assert.equal(confirmed, true);
  assert.deepEqual(remount.seen, [{ kind: 'ready', value: POST_SETUP, refreshing: false }]);
  assert.equal(reads.started, 2);
  remount.unsubscribe();
});

test('detach: queued requests from separate attachments complete independently', async () => {
  const { reads, owner, release } = ownedRead();
  const oldRead = owner.refresh();
  let oldConfirmed = false;
  const oldQueued = owner.refresh().then(() => { oldConfirmed = true; });
  release();
  await oldQueued;
  assert.equal(oldConfirmed, true, 'detach releases the old queued request');
  assert.equal(reads.started, 1, 'releasing the old queue starts no read');
  assert.deepEqual(owner.getSnapshot(), { kind: 'loading' });

  const remount = screen(owner);
  let newConfirmed = false;
  const newQueued = owner.refresh().then(() => { newConfirmed = true; });
  assert.equal(reads.started, 1, 'remount shares the physical read slot');
  assert.equal(newConfirmed, false);
  reads.pending[0].resolve(ready(PRE_SETUP));
  await oldRead;
  await settled();
  assert.equal(newConfirmed, false, 'old completion cannot release the new queue');
  assert.equal(reads.started, 2, 'only the new attachment gets a follow-up');
  assert.deepEqual(owner.getSnapshot(), { kind: 'loading' });
  assert.deepEqual(remount.seen, [], 'old data never reaches the new attachment');

  reads.pending[1].resolve(ready(POST_SETUP));
  await newQueued;
  assert.equal(newConfirmed, true);
  assert.deepEqual(remount.seen, [{ kind: 'ready', value: POST_SETUP, refreshing: false }]);
  remount.unsubscribe();
  await owner.refresh();
  assert.equal(reads.started, 2, 'no read after the second teardown');
  assert.deepEqual(owner.getSnapshot(), { kind: 'loading' });
});
