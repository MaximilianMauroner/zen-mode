# Android 0.1.5 upload candidate evidence — 2026-09-16

This records the first upload-key-signed Zen Mode 0.1.5 Android App Bundle and
matching APK. Google Play accepted and saved this AAB as Internal testing draft
release 1 on track `4700894893507986209`. No test or public rollout was
started.

## Identity

- Packaged application source: `2134dc8e4743925751501e1fdaa28f5ac8599d77`.
- Device-harness source: `d722d167c4fb907e20474684f7044989d8ec8de5`.
  The only change between these commits is the external
  `scripts/android-settings-escape-integration.mjs` test harness; no packaged
  app source or dependency changed.
- Package: `com.lab4code.zenmode`.
- Version name/code: `0.1.5` / `5`.
- EAS project: `@thearizztokrat/zen-mode`, project ID
  `7ffa0a46-ef47-46e6-9765-697c9da8ba63`.
- EAS Android build credential ID:
  `98b1654f-bc0a-4389-9d9d-5ab546a1767c`; keystore ID
  `b3fc0d01-2b6d-4ddc-8857-becbc75a26a5`.
- Upload certificate SHA-256:
  `46ab4a5bdb4b5e57ccf0c35570076eb58c3adf02db04aa04bbbcfd1eda082d59`.
- Upload certificate SHA-1:
  `fea3abbba332f3cd752b7a997750463b06565107`.

The credential was read from the exact EAS project/package into a mode-700
temporary directory, supplied to the fail-closed build through environment
variables, and deleted in a `finally` block. No key or password was printed,
committed, or uploaded through Mauroner Tools.

## Build and artifacts

The first wrapper attempt failed before compilation because it did not select
the generated Android Gradle project. Commit `2134dc8` adds `-p android` and a
focused regression. The successful serialized build then ran:

```bash
npm run android:bundle:upload
./android/gradlew -p android :app:assembleRelease
```

The credential environment is intentionally omitted. The successful build ran
from `2026-09-16T05:46:00.185Z` through `2026-09-16T06:01:51.547Z`.

Toolchain:

- OpenJDK `17.0.20+8`;
- Gradle `9.3.1`;
- Android compile/target SDK `36`, build tools `36.0.0`;
- NDK `27.1.12297006` selected by the generated Expo project;
- bundletool `1.18.2`, JAR SHA-256
  `378b5434cd1378bef6b2bc527b8c7f0ff2584b273830335bce54d6d0813c8584`.

Artifacts:

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `android/app/build/outputs/bundle/release/app-release.aab` | 76,542,127 | `150d5504e0af96ccf4534cdeb7a16abeca5fc900ef44f99be52967cf179a4387` |
| `android/app/build/outputs/apk/release/app-release.apk` | 112,989,277 | `dccacc5be6db38ebabe011f88bc9162eaad1ffa897b021ec9749a459d650a3a1` |

The parent independently downloaded the hosted APK and matched its complete
SHA-256 to `dccacc5be6db38ebabe011f88bc9162eaad1ffa897b021ec9749a459d650a3a1`.
The parent also copied the AAB to the Mac release environment and matched its
complete SHA-256 to
`150d5504e0af96ccf4534cdeb7a16abeca5fc900ef44f99be52967cf179a4387`.

`jarsigner` and `keytool -printcert -jarfile` verified the AAB. `apksigner
verify --verbose --print-certs` verified the APK with one signer and APK
Signature Scheme v2. Both report the upload certificate above. This is the
upload key, not a claimed Play app-signing certificate.

`aapt dump badging`, `aapt dump xmltree`, and `bundletool dump manifest`
reported the expected package, version, min SDK 24, target SDK 36, and no
`android:debuggable` application attribute. `bundletool validate` passed.

`bundletool dump config` reports `PAGE_ALIGNMENT_16K`, and
`zipalign -c -P 16 -v 4` reports `Verification successful`. Direct
`llvm-readelf -l` inspection found 24 shared objects in each ABI. Every LOAD
segment in `arm64-v8a` and `x86_64` has alignment at least 16,384 bytes (73
segments per ABI, zero below 16 KiB). The APK also includes `armeabi-v7a` and
`x86`; their 4 KiB segments do not change Android's 64-bit-device Play
requirement. A real 16 KiB emulator/device run is still not recorded.

## API-35 signed-candidate safety run

The exact APK above was installed only on the disposable `moodqa` API-35 AVD.
An existing test-signed install could not be upgraded because Android correctly
rejected its different signer, so only the task AVD's Zen package was removed.

A first plain-ADB install produced a preserved setup failure because Android 15
recorded no installer and hid the sideloaded accessibility service under its
restricted-settings policy. The service was present in package resolution, but
the UI row was absent. See
`android-settings-escape-release-0.1.5-20260916-failed-null-installer.log`.

The same APK was reinstalled with `adb -i com.android.vending`, which assigned
synthetic installer-package metadata on the disposable emulator. This was only
a harness setup condition; it was not Play delivery and does not verify Play
installation or restricted-settings behavior. With that metadata, Settings
showed `Downloaded apps` and `Zen Mode protection`:

```bash
adb -s emulator-5554 install -r -i com.android.vending \
  android/app/build/outputs/apk/release/app-release.apk

ZEN_GUARD_TEST_SERIAL=emulator-5554 \
ZEN_GUARD_ALLOW_TASK_DATA_RESET=clear-com.lab4code.zenmode-on-moodqa \
ZEN_GUARD_PRIVATE_DATA_ACCESS=root \
ZEN_GUARD_TEST_APK=android/app/build/outputs/apk/release/app-release.apk \
ZEN_GUARD_EVIDENCE_LOG=docs/evidence/android-settings-escape-release-0.1.5-20260916.log \
ANDROID_HOME=/home/codex/android-sdk \
node scripts/android-settings-escape-integration.mjs
```

The AVD was rooted only so the external test harness could seed literal stale
private preferences into this non-debuggable build. No debug endpoint or
production consent/security bypass was added.

The successful timestamped transcript SHA-256 is
`a27e098c87199b72e4245da6a31bd6fa3de40b2eafc78a80b6838a699b6c4565`.
It proves current native consent v3, active protection, a live seven-day
SecureStore lock, real UI rule mutation, first-launch timed enforcement,
literal stale Settings daily/timed/rolling stores, continuous one-launch
Settings observations, a visible Settings window under a Zen-owned overlay,
overlay removal without a queued Home action, resumed Clock enforcement,
user-facing service disablement, unchanged lock state, and no candidate entry
in Android's crash buffer. The harness cleared task-app data afterward and the
AVD was shut down.

## Limits and remaining handoff

- Google Play accepted and saved the AAB as Internal testing draft release 1 on
  track `4700894893507986209`; no rollout was started. No Play-generated split
  APK set was installed, and Play App Signing identity has not been verified.
- The `adb -i com.android.vending` harness setup was synthetic installer
  metadata, not Play delivery or evidence of Play installation and
  restricted-settings behavior.
- The signed candidate was tested on one x86_64 API-35 emulator, not a physical
  device, OEM Android build, or 16 KiB page-size runtime.
- The earlier owner-reported physical-device tests are not correlated to these
  exact artifact hashes.
- This version-code-5 artifact packages the former in-app legal-operator country
  `Austria`. The public policy and source were corrected to `Italy` afterward;
  a corrected uploaded candidate must use a version code greater than 5.
- EAS holds the managed primary key. The owner-held recovery archive is saved
  as a private encrypted attachment in the Proton Pass Dev vault. The parent
  independently verified archive SHA-256
  `19967000e4d6460f7e713342510601a1c0a140802566003e39c7e1475827a2f5`;
  parent Mac copies and the VM staging directory were removed afterward.
