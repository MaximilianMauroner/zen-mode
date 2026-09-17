import * as SecureStore from 'expo-secure-store';

import {
  armedRecord,
  formatRemaining as formatRemainingAt,
  parseLockRecord,
  stateOf,
  type LockRecord,
  type LockState,
} from './lock-policy';

const LOCK_KEY = 'zen-mode.commitment-lock.v1';

export { LOCK_DAY_OPTIONS, UNLOCK_DELAY_MS, type LockState } from './lock-policy';

/**
 * Current lock state. An expired lock is cleared as a side effect, so callers
 * always see either a live lock or a clean `open`.
 */
export async function readLockState(): Promise<LockState> {
  const record = await readRecord();
  if (record === null) return { kind: 'open' };

  const state = stateOf(record, Date.now());
  if (state.kind === 'open') await SecureStore.deleteItemAsync(LOCK_KEY);
  return state;
}

/** True while weaker settings must be refused. */
export async function isChangeBlocked(): Promise<boolean> {
  return (await readLockState()).kind !== 'open';
}

/**
 * Start or extend the lock. Re-committing clears a pending unlock, so this only
 * ever makes the commitment stronger. Shortening a live lock is refused.
 */
export async function armLock(days: number): Promise<void> {
  const next = armedRecord(await readLockState(), days, Date.now());
  if ('error' in next) throw new Error(next.error);

  await writeRecord(next);
}

/** Start the cooling-off period. The lock stays closed until it runs out. */
export async function requestUnlock(): Promise<void> {
  const state = await readLockState();
  if (state.kind === 'open') throw new Error('Nothing is locked.');
  if (state.kind === 'pending') throw new Error('An unlock is already waiting.');

  await writeRecord({ version: 1, lockedUntil: state.lockedUntil, unlockRequestedAt: Date.now() });
}

/** Withdraw a pending unlock and keep the original lock. Always allowed. */
export async function cancelUnlock(): Promise<void> {
  const state = await readLockState();
  if (state.kind !== 'pending') throw new Error('No unlock is waiting.');

  await writeRecord({ version: 1, lockedUntil: state.lockedUntil, unlockRequestedAt: null });
}

/** Coarse "6d 4h" style remaining time. Never renders a stale zero. */
export function formatRemaining(target: number, now: number = Date.now()): string {
  return formatRemainingAt(target, now);
}

async function readRecord(): Promise<LockRecord | null> {
  return parseLockRecord(await SecureStore.getItemAsync(LOCK_KEY));
}

async function writeRecord(record: LockRecord): Promise<void> {
  await SecureStore.setItemAsync(LOCK_KEY, JSON.stringify(record));
}
