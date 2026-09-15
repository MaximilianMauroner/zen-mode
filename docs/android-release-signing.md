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
  fingerprint. Colons and letter case are accepted.

Optionally set `BUNDLETOOL_JAR` to an absolute path to an official bundletool
JAR. When present, the workflow also checks the bundle manifest package and
version code. Never commit these environment values or paste secret-bearing
terminal output into an issue.

## Build and verify

From a clean checkout at the reviewed candidate commit:

```bash
npm ci
npm run android:bundle:upload
```

The command refuses missing/partial values, relative or absent keystore paths,
malformed fingerprints, the repository's known Android debug certificate, and
certificate mismatches. It then builds `app-release.aab`, verifies its JAR
signature, checks that its signer is the approved certificate, optionally
audits its manifest with bundletool, and prints the AAB and signer SHA-256
values. Gradle itself also refuses partial signing configuration and never
assigns `signingConfigs.debug` to the release build type.

Record the candidate commit, AAB SHA-256, signer SHA-256, build timestamp, Java,
Android Gradle Plugin, Gradle, bundletool, and SDK identities in release issue
#8. Install a Play-generated APK set from that exact AAB on the candidate device
and correlate all physical-device, screenshot, and reviewer-video evidence to
the same identity before calling it a release candidate.

The current VM has no authorized upload credential. A debug APK or any older
test-signed release APK is not a substitute for this workflow and must not be
uploaded.
