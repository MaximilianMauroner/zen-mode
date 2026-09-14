import assert from 'node:assert/strict';
import test from 'node:test';

import { isSetupComplete } from '../src/features/protection/setup-policy.ts';

test('Android setup requires both durable UI completion and current native consent', () => {
  assert.equal(isSetupComplete('android', true, true), true);
  assert.equal(isSetupComplete('android', true, false), false);
  assert.equal(isSetupComplete('android', false, true), false);
});

test('web preview setup does not require an unavailable native consent record', () => {
  assert.equal(isSetupComplete('web', true, false), true);
  assert.equal(isSetupComplete('web', false, false), false);
});
