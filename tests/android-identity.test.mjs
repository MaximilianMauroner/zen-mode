import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const require = createRequire(import.meta.url);

test('Android application ID and accessibility settings activity stay aligned', () => {
  const appConfig = JSON.parse(readFileSync(new URL('../app.json', import.meta.url), 'utf8'));
  const packageJson = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8'));
  const packageLock = JSON.parse(readFileSync(new URL('../package-lock.json', import.meta.url), 'utf8'));
  const easConfig = JSON.parse(readFileSync(new URL('../eas.json', import.meta.url), 'utf8'));
  const settingsScreen = readFileSync(new URL('../src/app/settings.tsx', import.meta.url), 'utf8');
  const accessibilityConfig = readFileSync(
    new URL('../modules/zen-guard/android/src/main/res/xml/zen_guard_accessibility_service.xml', import.meta.url),
    'utf8',
  );

  assert.equal(appConfig.expo.android.package, 'com.lab4code.zenmode');
  assert.equal(appConfig.expo.version, '0.1.5');
  assert.equal(appConfig.expo.android.versionCode, 7);
  assert.equal(appConfig.expo.owner, 'thearizztokrat');
  assert.equal(appConfig.expo.extra.eas.projectId, '7ffa0a46-ef47-46e6-9765-697c9da8ba63');
  assert.equal(easConfig.cli.appVersionSource, 'local');
  assert.equal(easConfig.build.production.credentialsSource, 'remote');
  assert.equal(easConfig.build.production.android.buildType, 'app-bundle');
  assert.equal(easConfig.build['production-apk'].android.buildType, 'apk');
  assert.equal(packageJson.version, appConfig.expo.version);
  assert.equal(packageLock.version, appConfig.expo.version);
  assert.equal(packageLock.packages[''].version, appConfig.expo.version);
  assert.ok(appConfig.expo.plugins.includes('./plugins/with-android-release-signing'));
  assert.match(settingsScreen, /Constants\.expoConfig\?\.version/);
  assert.match(accessibilityConfig, /android:settingsActivity="com\.lab4code\.zenmode\.MainActivity"/);
});

test('release signing has no debug fallback and uses the verified bundle workflow', () => {
  const { applyReleaseSigning } = require('../plugins/with-android-release-signing.js');
  const packageJson = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8'));
  const uploadBundleScript = readFileSync(
    new URL('../scripts/build-android-upload-bundle.mjs', import.meta.url),
    'utf8',
  );
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
  assert.match(generated, /eas-build-inject-android-credentials\.gradle/);
  assert.match(generated, /System\.getenv\("EAS_BUILD_WORKINGDIR"\)\?\.trim\(\)/);
  assert.match(
    generated,
    /46ab4a5bdb4b5e57ccf0c35570076eb58c3adf02db04aa04bbbcfd1eda082d59/,
  );
  assert.match(generated, /def storePath = signingConfig\.storeFile/);
  assert.match(generated, /release artifacts cannot use the Android debug certificate/);
  assert.match(generated, /getCertificate\(signingConfig\.keyAlias\)/);
  assert.match(generated, /configured keystore certificate does not match/);
  assert.match(
    generated,
    /easSigningInjected && android\.buildTypes\.release\.signingConfig != null/,
  );
  assert.match(
    generated,
    /verifyUploadSigningIdentity\(\s*android\.buildTypes\.release\.signingConfig,\s*uploadSigningEnvironment\.certificateSha256,/,
  );
  assert.match(
    generated,
    /storePassword: System\.getenv\("ZEN_MODE_UPLOAD_STORE_PASSWORD"\),/,
  );
  assert.match(generated, /keyPassword: System\.getenv\("ZEN_MODE_UPLOAD_KEY_PASSWORD"\),/);
  assert.doesNotMatch(generated, /(?:storePassword|keyPassword): System\.getenv\([^\n]+\)\?\.trim\(\)/);
  assert.match(generated, /storeFile: System\.getenv\([^\n]+\)\?\.trim\(\)/);
  assert.match(generated, /keyAlias: System\.getenv\([^\n]+\)\?\.trim\(\)/);
  assert.match(generated, /certificateSha256: System\.getenv\([^\n]+\)\?\.trim\(\)/);
  assert.equal(applyReleaseSigning(generated), generated);
  assert.throws(() =>
    applyReleaseSigning(
      sdk57Template.replace('signingConfig signingConfigs.debug', 'signingConfig signingConfigs.release'),
    ),
  );
  assert.equal(packageJson.scripts['android:bundle:upload'], 'node scripts/build-android-upload-bundle.mjs');
  assert.match(
    uploadBundleScript,
    /run\(resolve\(root, 'android\/gradlew'\), \['-p', resolve\(root, 'android'\), ':app:bundleRelease'\]/,
  );
});

test('release signing upgrades the complete block generated by 5e6a32d without touching native code', () => {
  const { applyReleaseSigning } = require('../plugins/with-android-release-signing.js');
  const oldEnvironmentBlock = `// @generated by with-android-release-signing: environment begin
def uploadSigningEnvironment = [
    storeFile: System.getenv("ZEN_MODE_UPLOAD_STORE_FILE")?.trim(),
    storePassword: System.getenv("ZEN_MODE_UPLOAD_STORE_PASSWORD")?.trim(),
    keyAlias: System.getenv("ZEN_MODE_UPLOAD_KEY_ALIAS")?.trim(),
    keyPassword: System.getenv("ZEN_MODE_UPLOAD_KEY_PASSWORD")?.trim(),
]
def uploadSigningValues = uploadSigningEnvironment.values()
def uploadSigningReady = uploadSigningValues.every { it }
def uploadSigningPartiallyConfigured = uploadSigningValues.any { it } && !uploadSigningReady

if (uploadSigningPartiallyConfigured) {
    throw new GradleException("Zen Mode upload signing is only partially configured. Set all four ZEN_MODE_UPLOAD_* signing variables or unset all of them.")
}
// @generated by with-android-release-signing: environment end`;
  const oldTaskGuardBlock = `// @generated by with-android-release-signing: task guard begin
def producesReleaseArtifact = { task ->
    def taskName = task.name.toLowerCase()
    task.project == project && taskName.contains("release") &&
        ["assemble", "bundle", "package", "sign", "install"].any { taskName.contains(it) }
}

gradle.taskGraph.whenReady { graph ->
    if (!uploadSigningReady && graph.allTasks.any(producesReleaseArtifact)) {
        throw new GradleException("Zen Mode release builds require all four ZEN_MODE_UPLOAD_* signing variables. Debug signing is intentionally unavailable for release artifacts.")
    }
}
// @generated by with-android-release-signing: task guard end`;
  const oldGenerated = `${oldEnvironmentBlock}

// unrelated-before-android
android {
    // unrelated-inside-android
    signingConfigs { debug { } }
    buildTypes {
        debug { signingConfig signingConfigs.debug }
        release {
            // @generated by with-android-release-signing: release begin
            if (uploadSigningReady) {
                def uploadSigningConfig = signingConfigs.create("zenModeUpload")
                uploadSigningConfig.storeFile file(uploadSigningEnvironment.storeFile)
                uploadSigningConfig.storePassword uploadSigningEnvironment.storePassword
                uploadSigningConfig.keyAlias uploadSigningEnvironment.keyAlias
                uploadSigningConfig.keyPassword uploadSigningEnvironment.keyPassword
                signingConfig uploadSigningConfig
            } else {
                signingConfig null
            }
            // @generated by with-android-release-signing: release end
            // unrelated-after-release
        }
    }
}

${oldTaskGuardBlock}
// unrelated-after-guard
`;

  const upgraded = applyReleaseSigning(oldGenerated);
  const replaceOwnedRegions = (contents) =>
    ['environment', 'release', 'task guard'].reduce(
      (result, name) =>
        result.replace(
          new RegExp(
            `// @generated by with-android-release-signing: ${name} begin[\\s\\S]*?` +
              `// @generated by with-android-release-signing: ${name} end`,
          ),
          `<${name}>`,
        ),
      contents,
    );

  assert.match(upgraded, /all five ZEN_MODE_UPLOAD_\* signing variables/);
  assert.match(upgraded, /verifyUploadSigningIdentity\(/);
  assert.match(upgraded, /release artifacts cannot use the Android debug certificate/);
  assert.doesNotMatch(upgraded, /all four ZEN_MODE_UPLOAD_\*/);
  assert.doesNotMatch(upgraded, /STORE_PASSWORD"\)\?\.trim/);
  assert.doesNotMatch(upgraded, /KEY_PASSWORD"\)\?\.trim/);
  assert.match(upgraded, /\/\/ unrelated-before-android/);
  assert.match(upgraded, /\/\/ unrelated-inside-android/);
  assert.match(upgraded, /\/\/ unrelated-after-release/);
  assert.match(upgraded, /\/\/ unrelated-after-guard/);
  assert.equal(replaceOwnedRegions(upgraded), replaceOwnedRegions(oldGenerated));
  assert.equal(applyReleaseSigning(upgraded), upgraded);
});

test('release signing rejects malformed generated ownership markers', () => {
  const { applyReleaseSigning } = require('../plugins/with-android-release-signing.js');
  const sdk57Template = `android {
    signingConfigs { debug { } }
    buildTypes {
        debug { signingConfig signingConfigs.debug }
        release { signingConfig signingConfigs.debug }
    }
}`;
  const generated = applyReleaseSigning(sdk57Template);
  const cleanRegeneration = /clean-regenerate Android/;

  assert.throws(
    () =>
      applyReleaseSigning(
        generated.replace('// @generated by with-android-release-signing: release end', ''),
      ),
    cleanRegeneration,
  );
  assert.throws(
    () =>
      applyReleaseSigning(
        `${generated}\n// @generated by with-android-release-signing: environment begin\n` +
          '// @generated by with-android-release-signing: environment end',
      ),
    cleanRegeneration,
  );
  assert.throws(
    () => applyReleaseSigning(generated.replace('environment end', 'unexpected end')),
    cleanRegeneration,
  );
});

test('the internal native module namespace remains independent of the app ID', () => {
  const moduleConfig = JSON.parse(
    readFileSync(new URL('../modules/zen-guard/expo-module.config.json', import.meta.url), 'utf8'),
  );

  assert.deepEqual(moduleConfig.android.modules, ['com.maxmauroner.zenguard.ZenGuardModule']);
});
