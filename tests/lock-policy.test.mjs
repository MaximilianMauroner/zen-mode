import assert from 'node:assert/strict';
import test from 'node:test';

import {
  armedRecord,
  dailyCeilingMinutes,
  endOf,
  formatRemaining,
  isWeakerAppRule,
  maxBurstMinutes,
  parseLockRecord,
  stateOf,
  UNLOCK_DELAY_MS,
} from '../src/features/protection/lock-policy.ts';

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

test('a daily budget permits twice its minutes across a midnight', () => {
  // The budget resets at local midnight, so an evening and the morning after
  // both draw it inside one 24-hour span. Only the calendar day sees 15.
  assert.equal(dailyCeilingMinutes({ mode: 'daily', minutes: 15 }), 30);
  assert.equal(dailyCeilingMinutes({ mode: 'daily', minutes: 120 }), 240);
});

test('a rolling allowance permits one helping per whole window in the day', () => {
  assert.equal(dailyCeilingMinutes({ mode: 'rolling', allowanceMinutes: 5, windowMinutes: 60 }), 120);
  assert.equal(dailyCeilingMinutes({ mode: 'rolling', allowanceMinutes: 5, windowMinutes: 180 }), 40);
  assert.equal(dailyCeilingMinutes({ mode: 'rolling', allowanceMinutes: 30, windowMinutes: 1440 }), 30);
});

test('a timed visit repeats once per visit-plus-downtime cycle', () => {
  assert.equal(dailyCeilingMinutes({ mode: 'visit', sessionMinutes: 5, cooldownMinutes: 60 }), 110);
  assert.equal(dailyCeilingMinutes({ mode: 'visit', sessionMinutes: 10, cooldownMinutes: 5 }), 960);
});

test('a visit that asks every time reaches the whole day', () => {
  assert.equal(dailyCeilingMinutes({ mode: 'visit', sessionMinutes: 5, cooldownMinutes: 0 }), 1440);
  assert.equal(dailyCeilingMinutes({ mode: 'visit', sessionMinutes: 1, cooldownMinutes: 0 }), 1440);
});

test('no ceiling exceeds a whole day', () => {
  assert.equal(dailyCeilingMinutes({ mode: 'daily', minutes: 5000 }), 1440);
  assert.equal(dailyCeilingMinutes({ mode: 'daily', minutes: 800 }), 1440);
  assert.equal(dailyCeilingMinutes({ mode: 'rolling', allowanceMinutes: 480, windowMinutes: 15 }), 1440);
  assert.equal(dailyCeilingMinutes({ mode: 'visit', sessionMinutes: 60, cooldownMinutes: 0 }), 1440);
});

test('the plan comparison table holds, including across a mode switch', () => {
  const cases = [
    ['daily raised', { mode: 'daily', minutes: 15 }, { mode: 'daily', minutes: 120 }, true],
    ['daily to visit', { mode: 'daily', minutes: 15 }, { mode: 'visit', sessionMinutes: 10, cooldownMinutes: 5 }, true],
    ['daily to rolling', { mode: 'daily', minutes: 5 }, { mode: 'rolling', allowanceMinutes: 5, windowMinutes: 60 }, true],
    ['rolling to daily', { mode: 'rolling', allowanceMinutes: 5, windowMinutes: 60 }, { mode: 'daily', minutes: 5 }, false],
    ['downtime removed', { mode: 'visit', sessionMinutes: 5, cooldownMinutes: 60 }, { mode: 'visit', sessionMinutes: 5, cooldownMinutes: 0 }, true],
    ['daily lowered', { mode: 'daily', minutes: 120 }, { mode: 'daily', minutes: 15 }, false],
    ['visit shortened', { mode: 'visit', sessionMinutes: 10, cooldownMinutes: 5 }, { mode: 'visit', sessionMinutes: 5, cooldownMinutes: 5 }, false],
    ['window narrowed', { mode: 'rolling', allowanceMinutes: 5, windowMinutes: 180 }, { mode: 'rolling', allowanceMinutes: 5, windowMinutes: 60 }, true],
    ['allowance widened', { mode: 'rolling', allowanceMinutes: 5, windowMinutes: 60 }, { mode: 'rolling', allowanceMinutes: 10, windowMinutes: 60 }, true],
  ];

  for (const [name, stored, proposed, expected] of cases) {
    assert.equal(isWeakerAppRule([stored], proposed), expected, name);
  }
});

test('an unchanged rule is not weaker, so re-saving is always allowed', () => {
  const rule = { mode: 'rolling', allowanceMinutes: 5, windowMinutes: 60 };
  assert.equal(isWeakerAppRule([rule], { ...rule }), false);
});

test('adding a rule to an unguarded app only tightens', () => {
  assert.equal(isWeakerAppRule([], { mode: 'daily', minutes: 480 }), false);
  assert.equal(isWeakerAppRule([], { mode: 'visit', sessionMinutes: 60, cooldownMinutes: 0 }), false);
});

test('a single sitting is capped by the allowance or visit length, not the daily total', () => {
  assert.equal(maxBurstMinutes({ mode: 'daily', minutes: 120 }), 120);
  assert.equal(maxBurstMinutes({ mode: 'rolling', allowanceMinutes: 5, windowMinutes: 60 }), 5);
  assert.equal(maxBurstMinutes({ mode: 'visit', sessionMinutes: 5, cooldownMinutes: 0 }), 5);
});

test('a mode switch cannot trade pacing away for the same daily total', () => {
  // Both permit 120 minutes in any 24 hours, but only the rolling rule stops a
  // one-hour sitting. Keeping the total is not keeping the restriction.
  const rolling = { mode: 'rolling', allowanceMinutes: 5, windowMinutes: 60 };
  const daily = { mode: 'daily', minutes: 60 };
  assert.equal(dailyCeilingMinutes(rolling), dailyCeilingMinutes(daily));
  assert.equal(isWeakerAppRule([rolling], daily), true);

  // The same trade through a timed visit.
  assert.equal(isWeakerAppRule([rolling], { mode: 'visit', sessionMinutes: 120, cooldownMinutes: 1320 }), true);
});

test('a longer sitting is refused even when the day gets tighter', () => {
  const rolling = { mode: 'rolling', allowanceMinutes: 5, windowMinutes: 60 };
  // 60 a day is well under the stored 120, but it permits an hour in one go.
  assert.equal(isWeakerAppRule([rolling], { mode: 'daily', minutes: 60 }), true);
  // Matching the sitting cap and lowering the day is a genuine tightening.
  assert.equal(isWeakerAppRule([rolling], { mode: 'daily', minutes: 5 }), false);
});

test('a rolling day cannot become a calendar day with the same minutes', () => {
  // Both are offered by the screen as "30m / day". They are not the same
  // promise: the rolling one holds across any 24 hours, the daily one lets the
  // evening and the morning after each draw a full 30.
  const rolling = { mode: 'rolling', allowanceMinutes: 30, windowMinutes: 1440 };
  assert.equal(isWeakerAppRule([rolling], { mode: 'daily', minutes: 30 }), true);
  // Halving the budget restores the 24-hour promise, so it is allowed.
  assert.equal(isWeakerAppRule([rolling], { mode: 'daily', minutes: 15 }), false);
});

test('a rolling rule may widen its window when the sitting cap holds', () => {
  // 5m per hour to 5m per 3 hours: tighter over the day, same single sitting.
  const hourly = { mode: 'rolling', allowanceMinutes: 5, windowMinutes: 60 };
  assert.equal(isWeakerAppRule([hourly], { mode: 'rolling', allowanceMinutes: 5, windowMinutes: 180 }), false);
  // Widening the allowance itself lengthens the sitting, so it is refused.
  assert.equal(isWeakerAppRule([hourly], { mode: 'rolling', allowanceMinutes: 10, windowMinutes: 180 }), true);
});

test('when an app carries several rules, the tightest one is what a replacement must match', () => {
  // The stores allow this even though the screen writes one rule at a time.
  // Daily 5m binds at 10 over any 24 hours, rolling 5m/hour at 120.
  const stored = [
    { mode: 'daily', minutes: 5 },
    { mode: 'rolling', allowanceMinutes: 5, windowMinutes: 60 },
  ];

  // Replacing both with the loose one alone would hand back 115 minutes.
  assert.equal(isWeakerAppRule(stored, { mode: 'rolling', allowanceMinutes: 5, windowMinutes: 60 }), true);
  // Keeping the binding limit is allowed.
  assert.equal(isWeakerAppRule(stored, { mode: 'daily', minutes: 5 }), false);
  assert.equal(isWeakerAppRule(stored, { mode: 'daily', minutes: 3 }), false);
});
