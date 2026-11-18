import { CryptoDigestAlgorithm, digestStringAsync, getRandomValues } from 'expo-crypto';
import * as SecureStore from 'expo-secure-store';

import {
  armedRecord,
  formatRemaining as formatRemainingAt,
  parseLockRecord,
  passcodeEntryRefusal,
  passcodeUpdateRefusal,
  stateOf,
  type LockRecord,
  type LockState,
  type PasscodeLockRecord,
} from './lock-policy';

const LOCK_KEY = 'zen-mode.commitment-lock.v1';
let lockWrite = Promise.resolve();

export { LOCK_DAY_OPTIONS, UNLOCK_DELAY_MS, passcodeEntryRefusal, type LockState } from './lock-policy';

export async function readLockState(): Promise<LockState> {
  return stateOf(await readLockRecord(), Date.now());
}

export async function readLockRecord(): Promise<LockRecord | null> {
  await lockWrite;
  return readRecord();
}

export function armLock(days: number): Promise<void> {
  return updateLock((current, now) => {
    const next = armedRecord(stateOf(current, now), days, now);
    if ('error' in next) throw new Error(next.error);
    return next;
  });
}

export function requestUnlock(): Promise<void> {
  return updateLock((current, now) => {
    const state = stateOf(current, now);
    if (state.kind === 'pending') throw new Error('An unlock is already waiting.');
    if (state.kind !== 'locked') throw new Error('There is no active time lock.');
    return { version: 1, lockedUntil: state.lockedUntil, unlockRequestedAt: now };
  });
}

export function cancelUnlock(): Promise<void> {
  return updateLock((current, now) => {
    const state = stateOf(current, now);
    if (state.kind !== 'pending') throw new Error('No unlock is waiting.');
    return { version: 1, lockedUntil: state.lockedUntil, unlockRequestedAt: null };
  });
}

export function updatePasscode(
  entry: { passcode: string; confirmation: string } | null,
  currentPasscode?: string,
): Promise<void> {
  return updateLock(async (current, now) => {
    const refusal = passcodeUpdateRefusal(stateOf(current, now), entry === null || currentPasscode !== undefined);
    if (refusal) throw new Error(refusal);
    if (current?.version === 2) {
      if (currentPasscode === undefined) throw new Error('Enter the current passcode.');
      await requirePasscode(current, currentPasscode);
    }
    if (entry === null) return null;

    const { passcode, confirmation } = entry;
    const entryRefusal = passcodeEntryRefusal(passcode, confirmation);
    if (entryRefusal) throw new Error(entryRefusal);
    const salt = Array.from(getRandomValues(new Uint8Array(16)), (byte) => byte.toString(16).padStart(2, '0')).join('');
    return { version: 2, salt, hash: await hashPasscode(salt, passcode) };
  });
}

export async function requirePasscode(record: PasscodeLockRecord, passcode: string): Promise<void> {
  if (await hashPasscode(record.salt, passcode) !== record.hash) throw new Error('Incorrect passcode.');
}

export function formatRemaining(target: number | null, now: number = Date.now()): string {
  return target === null ? 'Passcode required' : formatRemainingAt(target, now);
}

function hashPasscode(salt: string, passcode: string): Promise<string> {
  return digestStringAsync(CryptoDigestAlgorithm.SHA256, salt + passcode);
}

async function readRecord(): Promise<LockRecord | null> {
  try {
    return parseLockRecord(await SecureStore.getItemAsync(LOCK_KEY));
  } catch {
    return null;
  }
}

function updateLock(
  update: (current: LockRecord | null, now: number) => LockRecord | null | Promise<LockRecord | null>,
): Promise<void> {
  const write = lockWrite.then(async () => {
    const next = await update(await readRecord(), Date.now());
    if (next === null) await SecureStore.deleteItemAsync(LOCK_KEY);
    else await SecureStore.setItemAsync(LOCK_KEY, JSON.stringify(next));
  });
  lockWrite = write.catch(() => {});
  return write;
}
