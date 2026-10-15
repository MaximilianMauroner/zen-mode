import assert from 'node:assert/strict';
import test from 'node:test';

import { actionFailure, getGuardNotes, IDLE_ACTION, isReading, readValue } from '../src/features/protection/control-state.ts';
import { createProtectionRead } from '../src/features/protection/protection-reads.ts';

const READ_FAILURE = 'Zen Mode could not read the guard. Try again.';
const LOCKED = 'Settings are locked. Ask to unlock in the Lock tab.';

/** A guard status owner whose native reads succeed or fail in order. */
function guardStatus(...outcomes) {
  const owner = createProtectionRead({
    read: async () => {
      const outcome = outcomes.shift();
      if (outcome instanceof Error) throw outcome;
      return { kind: 'ready', value: outcome };
    },
    failure: READ_FAILURE,
  });
  owner.subscribe(() => {});
  return owner;
}

const messages = (read, action) => getGuardNotes(read, action).map(({ message }) => message);

test('a later status read failure stays visible beside an earlier action failure', async () => {
  const status = guardStatus({ protectionEnabled: true }, new Error('native'));
  await status.refresh();
  const action = actionFailure(new Error('Protection changed. Review it and try again.'), 'The action could not finish.');

  await status.refresh();
  assert.equal(readValue(status.getSnapshot()), null, 'the stale status is cleared');
  assert.deepEqual(messages(status.getSnapshot(), action), [READ_FAILURE, 'Protection changed. Review it and try again.']);
});

test('a refresh cannot clear a lock refusal', async () => {
  const status = guardStatus({ usedMs: 1 }, { usedMs: 2 });
  await status.refresh();
  const refusal = actionFailure(new Error(LOCKED), 'That rule could not be saved.');

  await status.refresh();
  assert.deepEqual(readValue(status.getSnapshot()), { usedMs: 2 }, 'resume read the new usage');
  assert.deepEqual(messages(status.getSnapshot(), refusal), [LOCKED]);
});

test('closing an editor clears its own feedback and keeps the read failure', async () => {
  const status = guardStatus(new Error('native'));
  await status.refresh();
  const refusal = actionFailure(new Error(LOCKED), 'That rule could not be saved.');
  assert.deepEqual(messages(status.getSnapshot(), refusal), [READ_FAILURE, LOCKED]);

  assert.deepEqual(messages(status.getSnapshot(), IDLE_ACTION), [READ_FAILURE]);
});

test('only a settings-lock refusal offers the Lock tab', () => {
  assert.equal(actionFailure(new Error('Settings became locked. The change was not saved.'), 'fallback').kind, 'refused');
  assert.deepEqual(actionFailure(new Error('The app rule changed. Review it and try again.'), 'fallback'), {
    kind: 'failed',
    message: 'The app rule changed. Review it and try again.',
  });
  assert.deepEqual(actionFailure('not an Error', 'That rule could not be saved.'), {
    kind: 'failed',
    message: 'That rule could not be saved.',
  });
});

test('a background refresh keeps the last values but disables the controls', async () => {
  let finish;
  const owner = createProtectionRead({
    read: () => new Promise((resolve) => { finish = resolve; }),
    failure: READ_FAILURE,
  });
  owner.subscribe(() => {});
  const first = owner.refresh();
  assert.equal(isReading(owner.getSnapshot()), true, 'the first read shows loading');
  finish({ kind: 'ready', value: 'first' });
  await first;
  assert.equal(isReading(owner.getSnapshot()), false);

  void owner.refresh();
  assert.equal(isReading(owner.getSnapshot()), true);
  assert.equal(readValue(owner.getSnapshot()), 'first');
});
