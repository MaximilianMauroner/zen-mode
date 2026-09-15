# Android upload-signing boundary evidence — 2026-09-15

## Source and scope

- Branch: `fix/android-settings-safety-package-id`
- Signing safeguard commit: `5e12dc56331ac73f329e12ad5163b5a5b3237158`
- Application ID: `com.lab4code.zenmode`
- No credential was created, replaced, copied into the repository, or printed.
- No APK or AAB was uploaded or submitted to Play.

## Read-only credential and EAS discovery

The five `ZEN_MODE_UPLOAD_*` variables, `EXPO_TOKEN`, and
`EAS_BUILD_PROFILE` were unset. `eas.json`, `.eas/`, user Gradle properties,
and the user EAS configuration directory were absent. GitHub Actions reported
no repository secret names and no repository variables. The only repository
keystore found was the generated, ignored `android/app/debug.keystore`.

The authenticated EAS identity was `thearizztokrat` / `expo@relay.mauroner.eu`,
but the read-only command ended with:

```text
EAS project not configured. This command cannot configure it in non-interactive mode.
```

Therefore approved Zen upload credentials are absent from the VM process,
repository, and GitHub Actions configuration. Remote EAS credentials are not
discoverable while this checkout has no project link; that is **not** evidence
that no remote EAS project or credential exists.

## Fail-closed checks

After a clean SDK 57 Android prebuild, raw Gradle release tasks were exercised
with `ANDROID_HOME=/home/codex/android-sdk`:

| Invocation | Result |
| --- | --- |
| `./gradlew :app:assembleRelease --console=plain` with all upload variables unset | Refused: all five variables are required; exit 1 |
| The same task with the four keystore variables but no approved fingerprint | Refused as partially configured; exit 1 |
| The same task with the generated debug keystore and its real debug fingerprint | Refused: `Zen Mode release artifacts cannot use the Android debug certificate.`; exit 1 |
| The same task with the generated debug keystore and a different well-formed fingerprint | Refused because the keystore certificate did not match; exit 1 |
| `npm run android:bundle:upload` with the generated debug keystore and its real fingerprint | Refused before Gradle: `the approved certificate is the Android debug certificate`; exit 1 |
| `./gradlew :app:assembleDebug -PreactNativeArchitectures=x86_64 --console=plain` with upload variables unset | `BUILD SUCCESSFUL in 4m 45s` |

The generated Gradle configuration now requires the approved certificate
fingerprint in addition to the four keystore values and verifies the selected
alias certificate in-process before any app release-artifact task executes.
Passwords are not passed on a command line or logged. The repository has no CI
workflow, so CI currently provides no separate signing rejection; the Gradle
guard is the direct-command boundary.

## Non-candidate artifact

The device-test APK remained debug signed:

```text
APK SHA-256: 5df57c17e195875e0cffc35b6e5682381078a3113d59cb73d583775628e7f6e8
Signer SHA-256: fac61745dc0903786fb9ede62a962b399f7348f0bb6f899b8332667591033b9c
Signer DN: CN=Android Debug, OU=Android, O=Unknown, L=Unknown, ST=Unknown, C=US
```

It is an x86_64 task-emulator build, not a release candidate, and must never be
uploaded as the distributable APK.

## Exact remaining credential gate

Before a production AAB or distributable APK can exist, the owner must do one
of these through the approved secret/custody channel:

1. provide the already approved Zen upload keystore, alias, passwords, and
   owner-verified SHA-256 certificate fingerprint; or
2. provide an existing approved EAS project ID so it can be linked and its
   existing Android credential inspected without creating a replacement.

If neither exists, Max must explicitly authorize creation and name the
custodian/backup location for a new upload key. Until that decision is made,
the build must remain blocked. Once supplied, run the documented verified AAB
workflow and a guarded `assembleRelease`, then verify package, version, APK
SHA-256, and `apksigner --print-certs` output before the file-upload workflow.

