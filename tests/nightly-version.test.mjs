import assert from 'node:assert/strict';
import test from 'node:test';

import { nightlyVersionCode, nightlyVersionName } from '../scripts/stamp-nightly-version.mjs';

test('nightly version code rises within a day and across days', () => {
  const day = new Date('2026-09-17T02:00:00Z');
  assert.equal(nightlyVersionCode(day, 1), 2026091701);
  assert.ok(nightlyVersionCode(day, 2) > nightlyVersionCode(day, 1));
  assert.ok(nightlyVersionCode(new Date('2026-09-18T02:00:00Z'), 1) > nightlyVersionCode(day, 99));
});

test('nightly version code stays below the Play limit', () => {
  assert.ok(nightlyVersionCode(new Date('2099-12-31T02:00:00Z'), 99) < 2100000000);
});

test('nightly version name keeps the base version and stamps the date', () => {
  assert.equal(nightlyVersionName('0.1.5', new Date('2026-09-17T02:00:00Z')), '0.1.5-nightly.20260917');
});
