/**
 * Pure settings-lock rules. Storage stays in `lock.ts` so these can be tested
 * without the Expo native module, the same split as `setup-policy.ts`.
 */

const DAY_MS = 24 * 60 * 60 * 1000;
const MINUTES_PER_DAY = 24 * 60;

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
 * The most minutes a rule permits in any 24 hours, counted from any moment
 * rather than from midnight.
 *
 * Daily, timed-visit, and rolling rules are not otherwise comparable, so this
 * is what lets the lock judge a mode switch instead of refusing every one of
 * them. A timed visit with no downtime repeats all day, which is why a zero
 * cooldown reaches the ceiling.
 *
 * A daily budget counts double because it is the only mode with a reset
 * moment: `AppLimitStore` keys usage by local calendar day, so an evening and
 * the morning after each draw a full budget inside one 24-hour span. A rolling
 * allowance never resets, so the same number of minutes buys a weaker promise
 * as a daily budget than as a rolling one, and the lock has to see that.
 */
export function dailyCeilingMinutes(rule: AppRule): number {
  switch (rule.mode) {
    case 'daily':
      return Math.min(MINUTES_PER_DAY, Math.max(0, rule.minutes) * 2);
    case 'rolling': {
      if (rule.windowMinutes <= 0) return MINUTES_PER_DAY;
      const windows = Math.floor(MINUTES_PER_DAY / rule.windowMinutes);
      return Math.min(MINUTES_PER_DAY, rule.allowanceMinutes * windows);
    }
    case 'visit': {
      const cycle = rule.sessionMinutes + rule.cooldownMinutes;
      if (cycle <= 0) return MINUTES_PER_DAY;
      const visits = Math.floor(MINUTES_PER_DAY / cycle);
      return Math.min(MINUTES_PER_DAY, rule.sessionMinutes * visits);
    }
  }
}

/**
 * The longest unbroken stretch of use a rule permits.
 *
 * A daily budget can be spent in one sitting. A rolling allowance and a timed
 * visit each cap a single stretch, which is the guarantee a daily total cannot
 * express: "5 minutes in any hour" and "120 minutes a day" both come to 120
 * over a day, but only one of them prevents a two-hour sitting.
 *
 * A sitting that straddles midnight can reach twice a daily budget. That is
 * counted on the 24-hour axis and deliberately not here, because doubling both
 * axes would refuse "5 minutes an hour" becoming "5 minutes a day" over a
 * 10-minute midnight sitting, and trap the user in the far looser rule.
 */
export function maxBurstMinutes(rule: AppRule): number {
  switch (rule.mode) {
    case 'daily':
      return Math.min(MINUTES_PER_DAY, Math.max(0, rule.minutes));
    case 'rolling':
      return Math.min(MINUTES_PER_DAY, Math.max(0, rule.allowanceMinutes));
    case 'visit':
      return Math.min(MINUTES_PER_DAY, Math.max(0, rule.sessionMinutes));
  }
}

/**
 * True when saving `proposed` over `stored` would loosen the guard, which the
 * settings lock refuses.
 *
 * A rule restricts on two axes that a mode switch can trade against each other,
 * so both have to hold: how much the day allows in total, and how much a single
 * sitting allows. Checking the daily total alone would let "5 minutes in any
 * hour" become "120 minutes a day", which keeps the total and throws away the
 * pacing.
 *
 * `stored` is a list because the native stores can hold a daily, visit, and
 * rolling rule for the same app at once. All of them apply, so the tightest
 * value on each axis is what binds today and what a replacement has to match.
 * An app with no rule only gains one, so an empty list is never weaker.
 */
export function isWeakerAppRule(stored: readonly AppRule[], proposed: AppRule): boolean {
  if (stored.length === 0) return false;
  const bindingCeiling = Math.min(...stored.map(dailyCeilingMinutes));
  const bindingBurst = Math.min(...stored.map(maxBurstMinutes));
  return dailyCeilingMinutes(proposed) > bindingCeiling || maxBurstMinutes(proposed) > bindingBurst;
}
