import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const require = createRequire(import.meta.url);

test('Android application ID and accessibility settings activity stay aligned', () => {
  const appConfig = JSON.parse(readFileSync(new URL('../app.json', import.meta.url), 'utf8'));
  const accessibilityConfig = readFileSync(
    new URL('../modules/zen-guard/android/src/main/res/xml/zen_guard_accessibility_service.xml', import.meta.url),
    'utf8',
  );

  assert.equal(appConfig.expo.android.package, 'com.lab4code.zenmode');
  assert.equal(appConfig.expo.android.versionCode, 5);
  assert.ok(appConfig.expo.plugins.includes('./plugins/with-android-release-signing'));
  assert.match(accessibilityConfig, /android:settingsActivity="com\.lab4code\.zenmode\.MainActivity"/);
});

test('release signing has no debug fallback and uses the verified bundle workflow', () => {
  const { applyReleaseSigning } = require('../plugins/with-android-release-signing.js');
  const packageJson = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8'));
  const sdk57Template = `
android {
    signingConfigs {
        debug { }
    }
    buildTypes {
        debug {
            signingConfig signingConfigs.debug
        }
        release {
            signingConfig signingConfigs.debug
        }
    }
}`;
  const generated = applyReleaseSigning(sdk57Template);

  assert.equal(generated.match(/signingConfig signingConfigs\.debug/g)?.length, 1);
  assert.match(generated, /signingConfig null/);
  assert.match(generated, /Debug signing is intentionally unavailable for release artifacts/);
  assert.match(generated, /ZEN_MODE_UPLOAD_STORE_FILE/);
  assert.match(generated, /ZEN_MODE_UPLOAD_CERT_SHA256/);
  assert.match(generated, /release artifacts cannot use the Android debug certificate/);
  assert.match(generated, /getCertificate\(uploadSigningEnvironment\.keyAlias\)/);
  assert.match(generated, /configured keystore certificate does not match/);
  assert.equal(applyReleaseSigning(generated), generated);
  assert.throws(() =>
    applyReleaseSigning(
      sdk57Template.replace('signingConfig signingConfigs.debug', 'signingConfig signingConfigs.release'),
    ),
  );
  assert.equal(packageJson.scripts['android:bundle:upload'], 'node scripts/build-android-upload-bundle.mjs');
});

test('the internal native module namespace remains independent of the app ID', () => {
  const moduleConfig = JSON.parse(
    readFileSync(new URL('../modules/zen-guard/expo-module.config.json', import.meta.url), 'utf8'),
  );

  assert.deepEqual(moduleConfig.android.modules, ['com.maxmauroner.zenguard.ZenGuardModule']);
});
