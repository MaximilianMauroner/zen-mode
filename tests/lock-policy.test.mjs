import assert from 'node:assert/strict';
import test from 'node:test';

import {
  appRuleLockRefusal,
  armedRecord,
  endOf,
  formatRemaining,
  parseLockRecord,
  stateOf,
  UNLOCK_DELAY_MS,
} from '../src/features/protection/lock-policy.ts';

/** True when the lock refuses the save, whatever reason it gives. */
const refused = (stored, proposed) => appRuleLockRefusal(stored, proposed) !== null;

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;
const NOW = 1_700_000_000_000;

const locked = (days, unlockRequestedAt = null) => ({
  version: 1,
  lockedUntil: NOW + days * DAY,
  unlockRequestedAt,
});

test('asking to unlock never shortens the term that was chosen', () => {
  // Seven days left, so the day of cooling-off is absorbed by the remaining term.
  assert.equal(endOf(locked(7, NOW)), NOW + 7 * DAY);
  // Two hours left, so the cooling-off is what the user now waits out.
  assert.equal(endOf({ version: 1, lockedUntil: NOW + 2 * HOUR, unlockRequestedAt: NOW }), NOW + UNLOCK_DELAY_MS);
  // No request pending, so the chosen term stands alone.
  assert.equal(endOf(locked(3)), NOW + 3 * DAY);
});

test('a live lock reports locked, and a requested unlock reports pending', () => {
  assert.deepEqual(stateOf(locked(7), NOW), { kind: 'locked', lockedUntil: NOW + 7 * DAY, endsAt: NOW + 7 * DAY });
  assert.deepEqual(stateOf(locked(7, NOW), NOW), {
    kind: 'pending',
    lockedUntil: NOW + 7 * DAY,
    requestedAt: NOW,
    endsAt: NOW + 7 * DAY,
  });
});

test('an absent or expired record reports open', () => {
  assert.deepEqual(stateOf(null, NOW), { kind: 'open' });
  assert.deepEqual(stateOf(locked(-1), NOW), { kind: 'open' });
  // The boundary belongs to open: at endsAt the lock has finished holding.
  assert.deepEqual(stateOf({ version: 1, lockedUntil: NOW, unlockRequestedAt: null }, NOW), { kind: 'open' });
  // A pending unlock holds until its own later end, not until lockedUntil.
  assert.equal(stateOf({ version: 1, lockedUntil: NOW, unlockRequestedAt: NOW }, NOW).kind, 'pending');
});

test('a malformed record reads as no lock, so settings can never be stranded', () => {
  for (const serialized of [
    null,
    '',
    'not json',
    '[]',
    'null',
    '"locked"',
    '{}',
    '{"version":2,"lockedUntil":1,"unlockRequestedAt":null}',
    '{"version":1,"lockedUntil":"soon","unlockRequestedAt":null}',
    '{"version":1,"unlockRequestedAt":null}',
    '{"version":1,"lockedUntil":null,"unlockRequestedAt":null}',
    // An absent request field is not read as "no unlock requested". A partial
    // record is a record this version does not understand.
    '{"version":1,"lockedUntil":1700}',
  ]) {
    assert.equal(parseLockRecord(serialized), null, `expected null for ${JSON.stringify(serialized)}`);
  }
});

test('a non-finite stored time cannot masquerade as a lock', () => {
  // JSON.stringify writes Infinity and NaN as null, but a hand-edited or
  // third-party record can still carry them through a permissive parser.
  assert.equal(parseLockRecord('{"version":1,"lockedUntil":1e999,"unlockRequestedAt":null}'), null);
  assert.equal(parseLockRecord('{"version":1,"lockedUntil":1,"unlockRequestedAt":1e999}'), null);
});

test('a well-formed record round-trips, and a missing request field means none', () => {
  assert.deepEqual(parseLockRecord('{"version":1,"lockedUntil":1700,"unlockRequestedAt":null}'), {
    version: 1,
    lockedUntil: 1700,
    unlockRequestedAt: null,
  });
  assert.deepEqual(parseLockRecord('{"version":1,"lockedUntil":1700,"unlockRequestedAt":1500}'), {
    version: 1,
    lockedUntil: 1700,
    unlockRequestedAt: 1500,
  });
});

test('arming refuses anything that is not a whole number of days', () => {
  for (const days of [0, -1, 1.5, Number.NaN, Number.POSITIVE_INFINITY]) {
    const result = armedRecord({ kind: 'open' }, days, NOW);
    assert.ok('error' in result, `expected ${days} to be refused`);
  }
});

test('arming over a live lock must extend it, never shorten it', () => {
  const live = stateOf(locked(7), NOW);

  const shorter = armedRecord(live, 3, NOW);
  assert.ok('error' in shorter);
  assert.match(shorter.error, /shorter than the lock you already set/);

  // Equal length is not an extension either; it would silently reset the term.
  assert.ok('error' in armedRecord(live, 7, NOW));

  const longer = armedRecord(live, 14, NOW);
  assert.deepEqual(longer, { version: 1, lockedUntil: NOW + 14 * DAY, unlockRequestedAt: null });
});

test('extending clears a pending unlock, so re-committing only strengthens', () => {
  const pending = stateOf(locked(7, NOW), NOW);
  const extended = armedRecord(pending, 30, NOW);
  assert.deepEqual(extended, { version: 1, lockedUntil: NOW + 30 * DAY, unlockRequestedAt: null });
});

test('a pending unlock is measured from its own end, not from the term', () => {
  // Two hours of term left, but a day of cooling-off. Arming for one day is
  // still shorter than the pending end and must be refused.
  const pending = stateOf({ version: 1, lockedUntil: NOW + 2 * HOUR, unlockRequestedAt: NOW }, NOW);
  assert.ok('error' in armedRecord(pending, 1, NOW));
  assert.ok(!('error' in armedRecord(pending, 3, NOW)));
});

test('remaining time reads coarsely and never renders a stale zero', () => {
  assert.equal(formatRemaining(NOW + 6 * DAY + 4 * HOUR, NOW), '6d 4h');
  assert.equal(formatRemaining(NOW + 6 * DAY, NOW), '6d');
  assert.equal(formatRemaining(NOW + 6 * DAY + 30 * MINUTE, NOW), '6d');
  assert.equal(formatRemaining(NOW + 3 * HOUR + 12 * MINUTE, NOW), '3h 12m');
  assert.equal(formatRemaining(NOW + 3 * HOUR, NOW), '3h');
  assert.equal(formatRemaining(NOW + 45 * MINUTE, NOW), '45m');
  assert.equal(formatRemaining(NOW + 30_000, NOW), 'under a minute');
  assert.equal(formatRemaining(NOW, NOW), 'under a minute');
  assert.equal(formatRemaining(NOW - DAY, NOW), 'under a minute');
});

test('an app with no rule only gains one, so nothing is refused', () => {
  assert.equal(appRuleLockRefusal([], { mode: 'daily', minutes: 480 }), null);
  assert.equal(appRuleLockRefusal([], { mode: 'visit', sessionMinutes: 60, cooldownMinutes: 0 }), null);
});

test('a daily budget may be lowered but not raised', () => {
  const stored = [{ mode: 'daily', minutes: 15 }];
  assert.equal(refused(stored, { mode: 'daily', minutes: 120 }), true);
  assert.equal(refused(stored, { mode: 'daily', minutes: 10 }), false);
  // Re-saving the same rule changes nothing, so it is always allowed.
  assert.equal(refused(stored, { mode: 'daily', minutes: 15 }), false);
});

test('a rolling rule may shrink its allowance or widen its window', () => {
  const stored = [{ mode: 'rolling', allowanceMinutes: 5, windowMinutes: 60 }];
  assert.equal(refused(stored, { mode: 'rolling', allowanceMinutes: 10, windowMinutes: 60 }), true);
  assert.equal(refused(stored, { mode: 'rolling', allowanceMinutes: 5, windowMinutes: 30 }), true);
  assert.equal(refused(stored, { mode: 'rolling', allowanceMinutes: 5, windowMinutes: 180 }), false);
  assert.equal(refused(stored, { mode: 'rolling', allowanceMinutes: 2, windowMinutes: 60 }), false);
  assert.equal(refused(stored, { mode: 'rolling', allowanceMinutes: 5, windowMinutes: 60 }), false);
});

test('a timed visit may shorten its session or lengthen its downtime', () => {
  const stored = [{ mode: 'visit', sessionMinutes: 5, cooldownMinutes: 30 }];
  assert.equal(refused(stored, { mode: 'visit', sessionMinutes: 10, cooldownMinutes: 30 }), true);
  assert.equal(refused(stored, { mode: 'visit', sessionMinutes: 5, cooldownMinutes: 0 }), true);
  assert.equal(refused(stored, { mode: 'visit', sessionMinutes: 5, cooldownMinutes: 60 }), false);
  assert.equal(refused(stored, { mode: 'visit', sessionMinutes: 2, cooldownMinutes: 30 }), false);
  assert.equal(refused(stored, { mode: 'visit', sessionMinutes: 5, cooldownMinutes: 30 }), false);
});

test('a change of mode is refused however tight the replacement reads', () => {
  // Consumed time does not move between the stores, so any mode change hands
  // back a spent allowance in full. None of these may pass while locked.
  const daily = [{ mode: 'daily', minutes: 5 }];
  const rolling = [{ mode: 'rolling', allowanceMinutes: 5, windowMinutes: 60 }];
  const visit = [{ mode: 'visit', sessionMinutes: 5, cooldownMinutes: 60 }];

  assert.equal(refused(rolling, { mode: 'daily', minutes: 5 }), true);
  assert.equal(refused(rolling, { mode: 'daily', minutes: 1 }), true);
  assert.equal(refused(daily, { mode: 'rolling', allowanceMinutes: 1, windowMinutes: 1440 }), true);
  assert.equal(refused(visit, { mode: 'daily', minutes: 1 }), true);
  assert.equal(refused(daily, { mode: 'visit', sessionMinutes: 1, cooldownMinutes: 600 }), true);
});

test('a mode change cannot trade a sub-day guarantee for the same daily total', () => {
  // 15m an hour and 10m with 30m off both come to 360 minutes a day with a
  // shorter sitting, yet two visits fit inside one hour and give 20.
  const stored = [{ mode: 'rolling', allowanceMinutes: 15, windowMinutes: 60 }];
  assert.equal(refused(stored, { mode: 'visit', sessionMinutes: 10, cooldownMinutes: 30 }), true);
});

test('a rolling day cannot become a calendar day with the same minutes', () => {
  // Both are offered by the screen as "30m / day". They are not the same
  // promise: the rolling one holds across any 24 hours, the daily one lets the
  // evening and the morning after each draw a full 30.
  const stored = [{ mode: 'rolling', allowanceMinutes: 30, windowMinutes: 1440 }];
  assert.equal(refused(stored, { mode: 'daily', minutes: 30 }), true);
});

test('a second stored rule is a guard the save would drop, so it is refused', () => {
  // The stores allow this even though the screen writes one rule at a time.
  const stored = [
    { mode: 'daily', minutes: 5 },
    { mode: 'rolling', allowanceMinutes: 5, windowMinutes: 60 },
  ];
  assert.equal(refused(stored, { mode: 'rolling', allowanceMinutes: 5, windowMinutes: 60 }), true);
  assert.equal(refused(stored, { mode: 'daily', minutes: 5 }), true);
  assert.equal(refused(stored, { mode: 'daily', minutes: 3 }), true);
});

test('the reason names the mode change, so the screen can say why', () => {
  const rolling = [{ mode: 'rolling', allowanceMinutes: 5, windowMinutes: 60 }];
  assert.match(appRuleLockRefusal(rolling, { mode: 'daily', minutes: 5 }), /already used/);
  assert.match(appRuleLockRefusal(rolling, { mode: 'rolling', allowanceMinutes: 10, windowMinutes: 60 }), /looser/);
});
