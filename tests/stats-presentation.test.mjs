import assert from 'node:assert/strict';
import test from 'node:test';

import {
  ENFORCEMENT_STAT_CATEGORIES,
  formatStatCount,
  getOtherStatCount,
  getStatCount,
  normalizeCount,
} from '../src/features/protection/stats-presentation.ts';

test('derives fixed protection counts and unknown categories', () => {
  const stats = {
    total: 8,
    counts: { youtube_shorts: 3, x_home: 2, future_reason: 3 },
    lastEventAt: 100,
  };

  assert.equal(getStatCount(stats, 'youtube_shorts'), 3);
  assert.equal(getStatCount(stats, 'instagram_reels'), 0);
  assert.equal(getOtherStatCount(stats), 3);
  assert.equal(ENFORCEMENT_STAT_CATEGORIES.length >= 6, true);
  assert.equal(ENFORCEMENT_STAT_CATEGORIES.some(({ key }) => key === 'tiktok_feed'), false);
});

test('normalizes malformed or fractional values for display', () => {
  assert.equal(normalizeCount(-2), 0);
  assert.equal(normalizeCount(Number.NaN), 0);
  assert.equal(normalizeCount(2.9), 2);
  assert.equal(formatStatCount(1200), '1,200');
});
