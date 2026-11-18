import { CryptoDigestAlgorithm, digestStringAsync, getRandomValues } from 'expo-crypto';
import * as SecureStore from 'expo-secure-store';

import {
  armedRecord,
  failedPasscodeAttempt,
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

type PasscodeRefusal = { record: PasscodeLockRecord; refusal: string };
type LockUpdate = LockRecord | null | PasscodeRefusal;

let lockWrite = Promise.resolve();

export { LOCK_DAY_OPTIONS, UNLOCK_DELAY_MS, UnreadableLockError, passcodeEntryRefusal, type LockState } from './lock-policy';

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
    if (entry !== null) {
      const entryRefusal = passcodeEntryRefusal(entry.passcode, entry.confirmation);
      if (entryRefusal) throw new Error(entryRefusal);
    }
    if (current?.version === 2) {
      if (currentPasscode === undefined) throw new Error('Enter the current passcode.');
      const attempt = await checkPasscode(current, currentPasscode, now);
      if (attempt) return attempt;
    }
    if (entry === null) return null;

    const salt = Array.from(getRandomValues(new Uint8Array(16)), (byte) => byte.toString(16).padStart(2, '0')).join('');
    return { version: 2, salt, hash: await hashPasscode(salt, entry.passcode) };
  });
}

export function requirePasscode(passcode: string, expected?: PasscodeLockRecord): Promise<void> {
  return updateLock(async (current, now) => {
    if (current?.version !== 2 || (expected && (current.salt !== expected.salt || current.hash !== expected.hash))) {
      throw new Error('The passcode lock changed. Try again.');
    }

    const attempt = await checkPasscode(current, passcode, now);
    if (attempt) return attempt;
    return current.attempts ? { version: 2, salt: current.salt, hash: current.hash } : current;
  });
}

async function checkPasscode(record: PasscodeLockRecord, passcode: string, now: number): Promise<PasscodeRefusal | null> {
  if (record.attempts && record.attempts.retryAt > now) {
    return { record, refusal: retryMessage(record.attempts.retryAt - now) };
  }
  if (await hashPasscode(record.salt, passcode) === record.hash) return null;

  const failedAt = Date.now();
  const attempts = failedPasscodeAttempt(record.attempts, failedAt);
  return {
    record: { ...record, attempts },
    refusal: attempts.retryAt === 0 ? 'Incorrect passcode.' : retryMessage(attempts.retryAt - failedAt),
  };
}

function retryMessage(delay: number): string {
  const seconds = Math.ceil(delay / 1000);
  return `Too many incorrect passcodes. Try again in ${seconds} ${seconds === 1 ? 'second' : 'seconds'}.`;
}

export function formatRemaining(target: number | null, now: number = Date.now()): string {
  return target === null ? 'Passcode required' : formatRemainingAt(target, now);
}

function hashPasscode(salt: string, passcode: string): Promise<string> {
  return digestStringAsync(CryptoDigestAlgorithm.SHA256, salt + passcode);
}

async function readRecord(): Promise<LockRecord | null> {
  return parseLockRecord(await SecureStore.getItemAsync(LOCK_KEY));
}

function updateLock(
  update: (current: LockRecord | null, now: number) => LockUpdate | Promise<LockUpdate>,
): Promise<void> {
  const write = lockWrite.then(async () => {
    const current = await readRecord();
    const result = await update(current, Date.now());
    const [next, refusal]: [LockRecord | null, string | null] = result && 'refusal' in result
      ? [result.record, result.refusal]
      : [result, null];
    if (next === null) await SecureStore.deleteItemAsync(LOCK_KEY);
    else if (next !== current) await SecureStore.setItemAsync(LOCK_KEY, JSON.stringify(next));
    if (refusal) throw new Error(refusal);
  });
  lockWrite = write.catch(() => {});
  return write;
}
