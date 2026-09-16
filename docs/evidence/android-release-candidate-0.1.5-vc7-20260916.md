# Android 0.1.5 version-code-7 candidate evidence — 2026-09-16

This records the Expo 57.0.23 replacement for the former code-6 bundle. The
code-7 artifacts were built and audited without a device install in that task.
The parent independently verified the local AAB hash, replaced code 6 with this
code-7 AAB in Internal release 1, and set the release name exactly to `0.1.5`.

## Identity and provenance

- Source: `45be98daf88685f9ac6f694304cb4ef1c75fbe6a` on `main`.
- Package and version: `com.lab4code.zenmode`, `0.1.5` / `7`.
- Dependency change: `expo` 57.0.23; `react-native-screens` remains 4.26.2.
- EAS project: `@thearizztokrat/zen-mode`, project ID
  `7ffa0a46-ef47-46e6-9765-697c9da8ba63`.
- EAS fingerprint: `484d957d6a47067088f65ca5636bd1abd89761ef` for both builds.
- AAB build `74b43109-837d-4910-a08d-042b3baaadac`, profile `production`,
  created `2026-09-16T08:13:50.011Z`, completed
  `2026-09-16T08:29:00.471Z`.
- APK build `9f950268-7c78-4b05-b8bc-2f54f3b66cc0`, profile
  `production-apk`, created `2026-09-16T08:29:31.514Z`, completed
  `2026-09-16T08:44:23.158Z`.

The builds ran serially with `--freeze-credentials` and used the existing
remote `Zen Mode Play upload key 2026`. No credential was exported, injected
locally, generated, replaced, or changed. EAS reports the same source SHA,
package, version, code, and fingerprint for both builds.

## Artifacts

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `zen-mode-0.1.5-vc7.aab` | 76,552,203 | `08bb1d84f4d92519a06c1796c9748e64f5f9a9384582b94790f6b84a0c49dde7` |
| `zen-mode-0.1.5-vc7-universal.apk` | 113,005,653 | `02efb41e1767079fcdc514a62bd76119dc253a3bcf5326f585933fa36529bbed` |

They are retained outside the repository at
`/home/codex/work/zen-mode-release-artifacts/0.1.5-vc7-45be98d/`. Exact EAS
metadata and decoded JSONL build logs are retained beside them. The log hashes
are `7f3ac7cda0b3c950f3e478d0545ca13d0b27ecd20ba309f583123134608d65da`
for the AAB and
`6c547e5d982fcb946b1af76e59083b0d2114e50f0c23a88a131445b092e6b340`
for the APK.

## Verification

Before building, `npm ci` reapplied the 4.26.2 screens patch, all 29 tests
passed, TypeScript and lint passed, the web export completed, and Expo Doctor
passed 21/21 checks. The source patch SHA-256 remains
`d2e0170866f318c5a3e96feece3f3718d8b79585d5e0898bb5c9e2888c0bf1d4`.
Both EAS logs record the patch applying during install and prebuild and record
successful Gradle builds. EAS's package-json record contains `expo` 57.0.23.

Bundletool 1.18.2 validated the AAB. Its manifest reports the expected package,
version, min SDK 24, and target SDK 36, has no `android:debuggable` application
attribute, and contains only the audited framework/native permissions. The AAB
has the approved upload-certificate SHA-256
`46ab4a5bdb4b5e57ccf0c35570076eb58c3adf02db04aa04bbbcfd1eda082d59`.
Bundle config reports `PAGE_ALIGNMENT_16K`.

The universal APK reports the same package/version and all four ABIs:
`arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`. It has no debuggable manifest
attribute. `apksigner` verified one v2 signer with the approved certificate,
and `zipalign -c -P 16 -v 4` reported `Verification successful`.

The AAB and APK each contain 24 shared objects per audited 64-bit ABI. Direct
`readelf -lW` inspection found 73 LOAD segments in each of `arm64-v8a` and
`x86_64`, with zero below 16 KiB alignment. Both packaged JavaScript bundles
contain the approved `Italy` operator wording once and no `Austria` match.

## Limits

Max reserved testing. No device/emulator installation, Play-generated split,
Play App Signing certificate, Play delivery, restricted-settings behavior,
runtime 16 KiB device, screenshot, or reviewer-media claim is made for these
hashes by the build task.

At 08:54 UTC on 2026-09-16 the parent saved and published Internal release 1.
Play Console reports it **Active**, with latest release `0.1.5`, available to
internal testers, and **Not reviewed**. Track `4700894893507986209` has one
selected list, `Max internal testing`, containing only
`maximilian.mauroner@gmail.com`; a second mistyped address was never added. The
opt-in URL is <https://play.google.com/apps/internaltest/4700894893507986209>.
No production release was created. Console showed only the optional missing
deobfuscation-file warning.

Play-installed testing, Play-generated split and Play App Signing correlation,
restricted-settings behavior, screenshots, reviewer media, Accessibility form
submission/review, and every public-review or production gate remain open.
