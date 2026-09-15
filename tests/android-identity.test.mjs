import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

test('Android application ID and accessibility settings activity stay aligned', () => {
  const appConfig = JSON.parse(readFileSync(new URL('../app.json', import.meta.url), 'utf8'));
  const accessibilityConfig = readFileSync(
    new URL('../modules/zen-guard/android/src/main/res/xml/zen_guard_accessibility_service.xml', import.meta.url),
    'utf8',
  );

  assert.equal(appConfig.expo.android.package, 'com.lab4code.zenmode');
  assert.match(accessibilityConfig, /android:settingsActivity="com\.lab4code\.zenmode\.MainActivity"/);
});

test('the internal native module namespace remains independent of the app ID', () => {
  const moduleConfig = JSON.parse(
    readFileSync(new URL('../modules/zen-guard/expo-module.config.json', import.meta.url), 'utf8'),
  );

  assert.deepEqual(moduleConfig.android.modules, ['com.maxmauroner.zenguard.ZenGuardModule']);
});
