# Android 0.1.5 version-code-6 candidate evidence — 2026-09-16

This records the upload-key-signed candidate that corrects the packaged legal
operator country to Italy. It was built and audited without a device install in
that task. Google Play later accepted this AAB as the sole bundle in Internal
draft release 1 on track `4700894893507986209` at 08:02 UTC on 2026-09-16,
retaining the original release notes. No rollout was started.

## Identity and provenance

- Packaged source: `25547c880f022c59b298aa3e8f621162387b2c80`.
- Package: `com.lab4code.zenmode`.
- Version name/code: `0.1.5` / `6`.
- EAS project: `@thearizztokrat/zen-mode`, project ID
  `7ffa0a46-ef47-46e6-9765-697c9da8ba63`.
- Upload certificate SHA-256:
  `46ab4a5bdb4b5e57ccf0c35570076eb58c3adf02db04aa04bbbcfd1eda082d59`.
- EAS fingerprint: `b554ef0943add1d23feb27849ea66a8111fc95a6` for both builds.
- AAB build `ab5e78d5-2a2a-4a67-ba15-bb2540cbe8dc`, profile `production`,
  created `2026-09-16T07:25:43.577Z`, completed
  `2026-09-16T07:45:15.347Z`.
- APK build `29087af8-e581-4999-9da1-893d71f3477a`, profile
  `production-apk`, created `2026-09-16T07:46:28.057Z`, completed
  `2026-09-16T08:00:03.905Z`.

Both EAS records report the same source SHA, package, version name, version
code, and fingerprint. EAS used its existing `Zen Mode Play upload key 2026`
credential. No key was exported, generated, replaced, or re-backed up.

## Artifacts

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `zen-mode-0.1.5-vc6.aab` | 76,552,208 | `6e85ec913c7e36c0be762667f8039488b8a969f6cc688c45904799084cad2cff` |
| `zen-mode-0.1.5-vc6-universal.apk` | 113,005,653 | `ff28948a381c6cf80c1ac28f96842083ac3a730f06c1ba8672014b236160bdc3` |

The artifacts are retained outside the repository at
`/home/codex/work/zen-mode-release-artifacts/0.1.5-vc6-25547c8/`.
The exact timestamped EAS JSONL logs are retained beside them as
`eas-aab-ab5e78d5.jsonl` (SHA-256
`f3df3ddd35fa90f6eac128b29eb74828a4a0f9e043bfbc88d3d7f9f197316a80`)
and `eas-apk-29087af8.jsonl` (SHA-256
`2d28146d38c79229cbbf5a74d4283600c46a7cb686c09fcc19ff34f78c5d72ba`).

## Verification

The AAB passed `bundletool 1.18.1 validate`. `bundletool dump manifest`
reported package `com.lab4code.zenmode`, version `0.1.5` / `6`, min SDK 24,
and target SDK 36. The manifest has no `android:debuggable` application
attribute. `keytool -printcert -jarfile` reported the approved upload
certificate. Ordinary `jarsigner -verify` succeeded; `jarsigner -strict`
reported the expected trust-chain error for the self-signed upload certificate
and no timestamp, so a strict public-CA trust result is not claimed.

`aapt dump badging` reported the same APK package/version identity and all four
ABIs: `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`. `apksigner verify
--verbose --print-certs` passed with one signer, APK Signature Scheme v2, and
the approved certificate SHA-256. `zipalign -c -P 16 -v 4` reported
`Verification successful`.

`bundletool dump config` reports `PAGE_ALIGNMENT_16K`. The AAB contains 24
shared objects in each ABI. Direct NDK 27.1 `llvm-readelf -lW` inspection found
73 LOAD segments in each of `arm64-v8a` and `x86_64`, with zero segments below
16 KiB alignment.

The packaged JavaScript contains exactly one `Italy` match in the approved
operator sentence and no `Austria` match. The source retains
`patches/react-native-screens+4.26.2.patch` with SHA-256
`d2e0170866f318c5a3e96feece3f3718d8b79585d5e0898bb5c9e2888c0bf1d4`;
the EAS install log records `patch-package` applying the pinned 4.26.2 patch.

Before the native builds, `npm ci`, all 29 tests, `npx tsc --noEmit`,
`npm run lint`, and `npx expo export --platform web` passed. The focused
Android identity/signing suite passed 5/5. A generated SDK-57 Android project
also completed `:app:tasks --all`, proving the Gradle plugin configures.
Expo Doctor separately reported the advisory patch mismatch `expo` 57.0.22
versus the current SDK-57 recommendation 57.0.23; this scoped candidate did not
change dependencies.

## Build-boundary findings and limits

The first remote AAB attempt, build
`d15555e7-0c8c-4d3d-abf4-2be869fab435`, failed before compilation because the
repository guard recognized only local `ZEN_MODE_UPLOAD_*` variables after EAS
had injected its managed signing config. Commit `25547c8` adds the narrowly
scoped EAS path: it requires EAS's generated credential script and working-dir
marker, then independently validates the resulting signing config against the
pinned Zen upload-certificate SHA-256. Local missing/partial credential and
debug-certificate rejection remain fail-closed.

An earlier local EAS diagnostic attempt also failed before compilation because
`ANDROID_HOME` was not inherited. EAS CLI then included its credential payload
in an internal failed-child-command diagnostic. No secret value is reproduced
here. The temporary workspace was absent afterward. The owner confirms nobody
else can access those internal diagnostics and has chosen to retain the existing
key; that access assertion was not independently audited. Resetting the upload
key is therefore not treated as a release blocker. No credential rotation or
replacement was performed here.

Max reserved all emulator/device testing. This task did not install either
artifact. The code-5 Settings safety and Fabric evidence remains relevant to
unchanged production behavior, but it is not test evidence for these exact
code-6 hashes. Play-generated splits, Play App Signing identity, Play delivery,
restricted-settings behavior, a 16 KiB runtime, physical/OEM devices, final
screenshots, and candidate-correlated reviewer media remain open.
