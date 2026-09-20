import assert from 'node:assert/strict';
import test from 'node:test';

import { RECOMMENDED_DEFAULTS, RECOMMENDED_DEFAULTS_SUMMARY } from '../src/features/protection/recommended-defaults.ts';

test('recommended defaults match the strict native feed rules', () => {
  assert.equal(RECOMMENDED_DEFAULTS.shortsEnabled, true);
  assert.equal(RECOMMENDED_DEFAULTS.youtubeHomeEnabled, false);
  assert.equal(RECOMMENDED_DEFAULTS.xHomeEnabled, true);
  assert.equal(RECOMMENDED_DEFAULTS.xVideosEnabled, true);
  assert.equal(RECOMMENDED_DEFAULTS.xHomeMinutes, 5);
  assert.equal(RECOMMENDED_DEFAULTS.instagramWaitSeconds, 30);
  assert.equal(RECOMMENDED_DEFAULTS.instagramReelsMinutes, 5);
  assert.equal(RECOMMENDED_DEFAULTS.instagramHomeMinutes, 5);
  assert.equal(RECOMMENDED_DEFAULTS.instagramExploreBlocked, true);
});

test('recommended defaults include adult-site blocking', () => {
  assert.equal(RECOMMENDED_DEFAULTS.adultSiteBlockingEnabled, true);
});

test('recommended summary lists every default the button applies', () => {
  const joined = RECOMMENDED_DEFAULTS_SUMMARY.join('\n');
  assert.match(joined, /YouTube/);
  assert.match(joined, /Instagram/);
  assert.match(joined, /X:/);
  assert.match(joined, /Sites/);
});
