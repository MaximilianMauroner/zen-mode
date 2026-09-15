import assert from 'node:assert/strict';
import test from 'node:test';
import { getHomeFeedTimeLabel } from '../src/features/protection/home-feed-time.ts';

const active = {
  available: true,
  serviceEnabled: true,
  protectionEnabled: true,
  instagramObservationMode: false,
  instagramHomeMinutes: 5,
  instagramHomeUsedMs: 125_000,
  instagramHomeBreakRemainingMs: 0,
  xObservationMode: false,
  xHomeEnabled: true,
  xHomeMinutes: 5,
  xHomeUsedMs: 241_000,
  xHomeBreakRemainingMs: 0,
};

test('shows remaining Home-feed time from native usage', () => {
  assert.equal(getHomeFeedTimeLabel(active, 'instagram'), '3m left');
  assert.equal(getHomeFeedTimeLabel(active, 'x'), '1m left');
});

test('shows when a Home-feed break ends', () => {
  assert.equal(getHomeFeedTimeLabel({ ...active, instagramHomeBreakRemainingMs: 2_520_000 }, 'instagram'), 'Available again in 42m');
});

test('a native expired-break snapshot presents the full next visit', () => {
  assert.equal(getHomeFeedTimeLabel({ ...active, xHomeUsedMs: 0, xHomeBreakRemainingMs: 0 }, 'x'), '5m left');
});

test('never presents runtime time for a rule that is not running', () => {
  assert.equal(getHomeFeedTimeLabel({ ...active, protectionEnabled: false }, 'instagram'), null);
  assert.equal(getHomeFeedTimeLabel({ ...active, xHomeEnabled: false }, 'x'), null);
  assert.equal(getHomeFeedTimeLabel({ ...active, instagramObservationMode: true }, 'instagram'), null);
});
