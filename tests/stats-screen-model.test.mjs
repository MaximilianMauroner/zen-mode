import assert from 'node:assert/strict';
import test from 'node:test';

import {
  ENFORCEMENT_STAT_CATEGORIES,
} from '../src/features/protection/stats-presentation.ts';
import { getStatsScreenModel, getStatsScreenState } from '../src/features/protection/stats-screen-model.ts';

test('/stats does not present synthetic web zeroes as Android data', () => {
  const synthetic = { total: 0, counts: {}, lastEventAt: 0 };

  assert.deepEqual(getStatsScreenState('web', synthetic, false), {
    available: false,
    model: null,
    message: 'Statistics are available in the Android app.',
  });
});

test('/stats distinguishes a real Android zero from loading and read failure', () => {
  const realZero = { total: 0, counts: {}, lastEventAt: 0 };

  assert.equal(getStatsScreenState('android', realZero, false).model?.total, '0');
  assert.equal(getStatsScreenState('android', null, true).message, 'Loading statistics…');
  assert.equal(getStatsScreenState('android', null, false).message, 'No statistics available.');
});

test('/stats renders every fixed protection row when all categories are zero', () => {
  const model = getStatsScreenModel({ total: 0, counts: {}, lastEventAt: 0 });

  assert.equal(model.total, '0');
  assert.equal(model.other, null);
  assert.deepEqual(
    model.rows,
    ENFORCEMENT_STAT_CATEGORIES.map(({ key, label, detail }) => ({
      key,
      label,
      detail,
      value: '0',
    })),
  );
});

test('/stats keeps an aggregate unknown category in its explicit Other row', () => {
  const model = getStatsScreenModel({
    total: 4,
    counts: { youtube_shorts: 1, future_reason: 3 },
    lastEventAt: 42,
  });

  assert.equal(model.total, '4');
  assert.deepEqual(model.other, {
    key: 'other',
    label: 'Other',
    detail: 'Additional fixed enforcement reasons.',
    value: '3',
  });
  assert.equal(model.rows.find(({ key }) => key === 'youtube_shorts')?.value, '1');
});
