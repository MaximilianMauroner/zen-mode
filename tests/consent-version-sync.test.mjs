import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

import { CONSENT_VERSION } from '../src/features/protection/setup-policy.ts';

test('TypeScript and native accessibility consent versions stay synchronized', async () => {
  const nativePolicy = await readFile(
    new URL('../modules/zen-guard/android/src/main/java/com/maxmauroner/zenguard/ConsentPolicy.kt', import.meta.url),
    'utf8',
  );
  const nativeVersion = Number(nativePolicy.match(/CURRENT_VERSION\s*=\s*(\d+)/)?.[1]);
  assert.equal(Number.isInteger(nativeVersion), true, 'native consent version must be readable');
  assert.equal(CONSENT_VERSION, nativeVersion);
});
