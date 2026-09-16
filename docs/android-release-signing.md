# Android release signing

Zen Mode release builds have no debug-certificate fallback. The upload bundle
workflow requires an owner-approved Android upload key supplied outside the
repository and verifies the certificate before and after the build.

No upload key or production signing credential is committed here. Creating,
replacing, enrolling, backing up, or transferring a key is an owner/custodian
decision and must be completed through an approved secure channel.

## Required environment

Set these values in the release operator's secret manager or ephemeral shell:

- `ZEN_MODE_UPLOAD_STORE_FILE`: absolute path to the authorized keystore;
- `ZEN_MODE_UPLOAD_STORE_PASSWORD`;
- `ZEN_MODE_UPLOAD_KEY_ALIAS`;
- `ZEN_MODE_UPLOAD_KEY_PASSWORD`; and
- `ZEN_MODE_UPLOAD_CERT_SHA256`: owner-approved 64-hex SHA-256 certificate
  fingerprint. Colons and letter case are accepted. Gradle independently
  checks this fingerprint for every task graph that produces a release
  artifact, even when the documented wrapper is bypassed.

Optionally set `BUNDLETOOL_JAR` to an absolute path to an official bundletool
JAR. When present, the workflow also checks the bundle manifest package and
version code. Never commit these environment values or paste secret-bearing
terminal output into an issue.

## Build and verify

Use `npm run release:internal` from the synced release checkout. The shared
ledger reserves one patch version and Android version code for both artifacts.
EAS local builds use the existing remote credential with `--freeze-credentials`.
The release runner checks the APK and AAB package, version, signatures, and
pinned upload certificate before submitting the AAB to Internal testing.
See [nightly releases](nightly.md) for setup and the daily attempt rule.

The local `ZEN_MODE_UPLOAD_*` values remain supported by the native Gradle
signing guard for direct native diagnostics. They are not needed by the EAS
local release runner. Direct diagnostic builds must not be uploaded outside
the shared release ledger.

Remote EAS builds use the linked project's managed credential rather than the
local environment variables. The guard accepts that path only when EAS has
added its generated `eas-build-inject-android-credentials.gradle` file and set
`EAS_BUILD_WORKINGDIR`; it then verifies the resulting release signing config
against Zen Mode's pinned, approved upload-certificate SHA-256 before any
release artifact task runs. Merely setting the EAS environment variable, or
providing an arbitrary non-debug certificate, does not satisfy the guard.

Record the candidate commit, AAB SHA-256, signer SHA-256, build timestamp, Java,
Android Gradle Plugin, Gradle, bundletool, and SDK identities in release issue
#8. Install a Play-generated APK set from that exact AAB on the candidate device
and correlate all physical-device, screenshot, and reviewer-video evidence to
the same identity before calling it a release candidate.

After owner approval, the app-specific `@thearizztokrat/zen-mode` EAS project
was created and linked with one new EAS-managed upload key used only for
`com.lab4code.zenmode`. Max is the approved custodian. The managed primary copy
exists in EAS. The separate owner-held recovery archive is saved as the private
encrypted Proton Pass Dev-vault attachment `Zen Mode - Android upload-key
recovery`. The parent independently downloaded it through Proton Pass and
matched SHA-256
`19967000e4d6460f7e713342510601a1c0a140802566003e39c7e1475827a2f5`.
The parent Mac copies and the VM staging directory were then removed. Never
send this backup through the public artifact uploader or chat. A debug APK or
any older test-signed release APK is not a substitute for this workflow and
must not be uploaded.

## Proton Pass recovery backup

The backup is complete. The following is the recovery procedure retained for
future custody audits; it must not be repeated merely to test the backup. The
parent owns the Proton Pass and EAS credential UI:

1. Open the linked `@thearizztokrat/zen-mode` Android credentials with
   `eas credentials -p android` and choose the existing production keystore.
   Select the download/export action; do not generate, replace, or rotate it.
2. Write the keystore only to a newly created, owner-readable temporary
   directory. Do not print passwords, shell-trace the command, paste its output
   into chat, or use the public file uploader.
3. Create a dedicated Proton Pass item for Zen Mode and attach the keystore.
   Record the package, EAS owner/project slug and project ID, credential and
   keystore IDs, keystore password, key alias, key password, upload-certificate
   SHA-256 and SHA-1 fingerprints, export date, custodian, and recovery notes.
4. Compare the exported alias certificate fingerprint with the independently
   approved upload-certificate fingerprint already recorded in the candidate
   evidence. A mismatch stops the procedure; it is not permission to replace a
   credential.
5. After the parent confirms the Proton Pass attachment and fields can be
   recovered, securely delete the temporary keystore and any secret-bearing
   terminal transcript. Keep only non-secret verification metadata in release
   records.

The repository environment-variable names above describe the fields needed by
the fail-closed local build. Secret values remain exclusively in approved
secret storage.

The first verified 0.1.5/code-5 artifacts and device evidence are recorded in
`docs/evidence/android-release-candidate-0.1.5-20260916.md`. The corrected
Italy-wording code-6 artifacts and their source/build audit are recorded in
`docs/evidence/android-release-candidate-0.1.5-vc6-20260916.md`. The built and
audited code-7 Expo 57.0.23 replacement is recorded in
`docs/evidence/android-release-candidate-0.1.5-vc7-20260916.md` and is now the
active one-person Internal release. Exact Play-installed candidate testing and
Play App Signing correlation remain owner tasks; no production release exists.

The owner confirms that nobody else can access the internal EAS diagnostic
which previously included a credential payload and has chosen to retain the
existing Zen key. That access assertion was not independently audited, but key
reset is not a release blocker under the owner's decision.
