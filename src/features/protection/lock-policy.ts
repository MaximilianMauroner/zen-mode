/**
 * Pure settings-lock rules. Storage stays in `lock.ts` so these can be tested
 * without the Expo native module, the same split as `setup-policy.ts`.
 */

const DAY_MS = 24 * 60 * 60 * 1000;

/** Cooling-off period between asking to unlock and the lock actually opening. */
export const UNLOCK_DELAY_MS = DAY_MS;

/** Lock lengths offered when arming or extending. */
export const LOCK_DAY_OPTIONS = [1, 3, 7, 14, 30];

export type LockRecord = {
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
export function endOf(record: LockRecord): number {
  if (record.unlockRequestedAt === null) return record.lockedUntil;
  return Math.max(record.lockedUntil, record.unlockRequestedAt + UNLOCK_DELAY_MS);
}

/**
 * The state a stored record describes at `now`. An expired record reads as
 * `open`; the caller is responsible for clearing it.
 */
export function stateOf(record: LockRecord | null, now: number): LockState {
  if (record === null) return { kind: 'open' };

  const endsAt = endOf(record);
  if (now >= endsAt) return { kind: 'open' };

  return record.unlockRequestedAt === null
    ? { kind: 'locked', lockedUntil: record.lockedUntil, endsAt }
    : { kind: 'pending', lockedUntil: record.lockedUntil, requestedAt: record.unlockRequestedAt, endsAt };
}

/**
 * Reads a stored record, or null when nothing usable is there.
 *
 * A record that fails these checks reads as no lock at all, which opens the
 * settings. That is deliberate: a lock the app cannot understand must not
 * leave the user permanently unable to change their own settings.
 */
export function parseLockRecord(serialized: string | null): LockRecord | null {
  if (serialized === null) return null;

  let parsed: unknown;
  try {
    parsed = JSON.parse(serialized);
  } catch {
    return null;
  }
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) return null;

  const value = parsed as Partial<LockRecord>;
  if (
    value.version !== 1 ||
    typeof value.lockedUntil !== 'number' ||
    !Number.isFinite(value.lockedUntil) ||
    (value.unlockRequestedAt !== null &&
      (typeof value.unlockRequestedAt !== 'number' || !Number.isFinite(value.unlockRequestedAt)))
  ) {
    return null;
  }
  return { version: 1, lockedUntil: value.lockedUntil, unlockRequestedAt: value.unlockRequestedAt ?? null };
}

/**
 * The record that arming for `days` should write, or an error message when the
 * request would shorten a live lock. Re-committing clears a pending unlock, so
 * arming only ever makes the commitment stronger.
 */
export function armedRecord(state: LockState, days: number, now: number): LockRecord | { error: string } {
  if (!Number.isInteger(days) || days < 1) {
    return { error: 'Choose a whole number of days.' };
  }

  const lockedUntil = now + days * DAY_MS;
  if (state.kind !== 'open' && lockedUntil <= state.endsAt) {
    return { error: 'That is shorter than the lock you already set. Pick a longer one.' };
  }

  return { version: 1, lockedUntil, unlockRequestedAt: null };
}

/** Coarse "6d 4h" style remaining time. Never renders a stale zero. */
export function formatRemaining(target: number, now: number): string {
  const remaining = Math.max(0, target - now);
  const days = Math.floor(remaining / DAY_MS);
  const hours = Math.floor((remaining % DAY_MS) / (60 * 60 * 1000));
  const minutes = Math.floor((remaining % (60 * 60 * 1000)) / (60 * 1000));

  if (days > 0) return hours > 0 ? `${days}d ${hours}h` : `${days}d`;
  if (hours > 0) return minutes > 0 ? `${hours}h ${minutes}m` : `${hours}h`;
  if (minutes > 0) return `${minutes}m`;
  return 'under a minute';
}

/** One app rule, in whichever of the three shapes the user chose. */
export type AppRule =
  | { mode: 'daily'; minutes: number }
  | { mode: 'visit'; sessionMinutes: number; cooldownMinutes: number }
  | { mode: 'rolling'; allowanceMinutes: number; windowMinutes: number };

/**
 * Why the settings lock refuses to save `proposed` over `stored`, or null when
 * the change is a tightening the lock allows.
 *
 * The lock allows an edit only inside one mode. That is narrower than comparing
 * the two rules, and it is deliberate, because a change of mode is not a
 * comparison the app can win:
 *
 * - Consumed time does not move between the stores. Saving a rule of a
 *   different mode writes to a store that holds nothing for this app and drops
 *   the old one, and `RollingLimitStore.removeRule` deletes the banked slices
 *   outright. A spent allowance therefore comes back in full the moment the
 *   mode changes, however tight the new rule reads.
 * - The modes promise different things at different spans. "15 minutes an
 *   hour" and "10 minutes with 30 minutes off" both come to 360 minutes a day
 *   with a shorter sitting, yet two visits fit inside one hour and give 20.
 *   Summarising a rule as a few numbers loses the span where it binds.
 *
 * Inside one mode neither problem exists. The rule keeps its own store and its
 * own consumed time, and its parameters are directly comparable at every span:
 * fewer minutes, a smaller allowance in a window no shorter, or a shorter visit
 * with downtime no shorter is tighter everywhere, not just on average.
 *
 * `stored` is a list because the native stores can hold a daily, visit, and
 * rolling rule for the same app at once. Saving replaces all of them, so a
 * second stored rule is always a guard the save would drop. An app with no rule
 * only gains one, so an empty list is never refused.
 */
export function appRuleLockRefusal(stored: readonly AppRule[], proposed: AppRule): string | null {
  if (stored.length === 0) return null;
  if (stored.length > 1 || stored[0].mode !== proposed.mode) {
    return 'Changing how an app is limited clears the time it has already used. Ask to unlock, then wait a day.';
  }
  return isLooserInMode(stored[0], proposed)
    ? 'That rule is looser than the one you set. Ask to unlock, then wait a day.'
    : null;
}

/** Compares two rules of the same mode on their own parameters. */
function isLooserInMode(stored: AppRule, proposed: AppRule): boolean {
  if (stored.mode === 'daily' && proposed.mode === 'daily') {
    return proposed.minutes > stored.minutes;
  }
  if (stored.mode === 'rolling' && proposed.mode === 'rolling') {
    return proposed.allowanceMinutes > stored.allowanceMinutes || proposed.windowMinutes < stored.windowMinutes;
  }
  if (stored.mode === 'visit' && proposed.mode === 'visit') {
    return proposed.sessionMinutes > stored.sessionMinutes || proposed.cooldownMinutes < stored.cooldownMinutes;
  }
  return true;
}
