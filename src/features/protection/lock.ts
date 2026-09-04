import * as SecureStore from 'expo-secure-store';

const LOCK_KEY = 'zen-mode.commitment-lock.v1';
const DAY_MS = 24 * 60 * 60 * 1000;

/** Cooling-off period between asking to unlock and the lock actually opening. */
export const UNLOCK_DELAY_MS = DAY_MS;

/** Lock lengths offered when arming or extending. */
export const LOCK_DAY_OPTIONS = [1, 3, 7, 14, 30];

type LockRecord = {
  version: 1;
  lockedUntil: number;
  unlockRequestedAt: number | null;
};

export type LockState =
  /** Nothing is holding the settings. Anything can be weakened right away. */
  | { kind: 'open' }
  /** The lock is running. Weaker settings are refused until an unlock is asked for and waited out. */
  | { kind: 'locked'; lockedUntil: number; endsAt: number }
  /** An unlock was asked for. The lock opens at `endsAt` and not before. */
  | { kind: 'pending'; lockedUntil: number; requestedAt: number; endsAt: number };

/**
 * When the lock opens. Asking to unlock never shortens the term you chose: the
 * cooling-off runs alongside the remaining days, so the later of the two wins.
 */
function endOf(record: LockRecord): number {
  if (record.unlockRequestedAt === null) return record.lockedUntil;
  return Math.max(record.lockedUntil, record.unlockRequestedAt + UNLOCK_DELAY_MS);
}

/**
 * Current lock state. An expired lock is cleared as a side effect, so callers
 * always see either a live lock or a clean `open`.
 */
export async function readLockState(): Promise<LockState> {
  const record = await readRecord();
  if (record === null) return { kind: 'open' };

  const endsAt = endOf(record);
  if (Date.now() >= endsAt) {
    await SecureStore.deleteItemAsync(LOCK_KEY);
    return { kind: 'open' };
  }

  return record.unlockRequestedAt === null
    ? { kind: 'locked', lockedUntil: record.lockedUntil, endsAt }
    : { kind: 'pending', lockedUntil: record.lockedUntil, requestedAt: record.unlockRequestedAt, endsAt };
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
  if (!Number.isInteger(days) || days < 1) {
    throw new Error('Choose a whole number of days.');
  }

  const state = await readLockState();
  const lockedUntil = Date.now() + days * DAY_MS;
  if (state.kind !== 'open' && lockedUntil <= state.endsAt) {
    throw new Error('That is shorter than the lock you already set. Pick a longer one.');
  }

  await writeRecord({ version: 1, lockedUntil, unlockRequestedAt: null });
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
  const remaining = Math.max(0, target - now);
  const days = Math.floor(remaining / DAY_MS);
  const hours = Math.floor((remaining % DAY_MS) / (60 * 60 * 1000));
  const minutes = Math.floor((remaining % (60 * 60 * 1000)) / (60 * 1000));

  if (days > 0) return hours > 0 ? `${days}d ${hours}h` : `${days}d`;
  if (hours > 0) return minutes > 0 ? `${hours}h ${minutes}m` : `${hours}h`;
  if (minutes > 0) return `${minutes}m`;
  return 'under a minute';
}

async function readRecord(): Promise<LockRecord | null> {
  const serialized = await SecureStore.getItemAsync(LOCK_KEY);
  if (serialized === null) return null;

  try {
    const parsed: unknown = JSON.parse(serialized);
    if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) return null;

    const value = parsed as Partial<LockRecord>;
    if (
      value.version !== 1 ||
      typeof value.lockedUntil !== 'number' ||
      !Number.isFinite(value.lockedUntil) ||
      (value.unlockRequestedAt !== null && typeof value.unlockRequestedAt !== 'number')
    ) {
      return null;
    }
    return value as LockRecord;
  } catch {
    return null;
  }
}

async function writeRecord(record: LockRecord): Promise<void> {
  await SecureStore.setItemAsync(LOCK_KEY, JSON.stringify(record));
}
