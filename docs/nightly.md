# Local nightly releases

Both apps build on this Mac at 23:00 Europe/Vienna. Each successful run saves an
APK and an AAB with the same version and signing identity. It submits the AAB
to Google Play Internal testing. Production promotion remains manual.

## One release entrypoint

From either repository on the Mac:

```sh
node scripts/nightly-release.mjs run
node scripts/nightly-release.mjs status
# Equivalent package scripts:
npm run release:internal
npm run release:status
```

On coding, use the same command with `RELEASE_COORDINATOR=local`. The Mac uses
`RELEASE_COORDINATOR=coding` by default. Both use the same ledger on coding:
`~/.local/state/lab4code-releases/<app>.json`.

The host preflight runs before the ledger reserves a patch version and Android
versionCode. It checks readable SDK, bundletool and Play key paths, Android
verification tools, EAS and Java commands, 15 GiB free disk space and 8 GiB RAM.
A preflight failure does not consume a release number or mark the SHA attempted.
Run `node scripts/release-environment.mjs` to check the current environment.
It allows at most one attempt per app per Vienna calendar day. An attempted Git
SHA is never retried automatically, even after a failed check, build, or upload.
A new SHA can run on the next day. Failed attempts consume their reserved numbers.
For example, a failed `0.1.6` is followed by `0.1.7` for the next source change.

A host lock prevents concurrent local builds from these entrypoints. An active
central reservation prevents overlapping releases for the same app across hosts.
The runner builds fetched `origin/main` in an isolated worktree. It preserves
local changes and does not move the working branch.

Use this entrypoint for every tester release. Direct EAS or Play uploads can
bypass the ledger. If one is necessary, reconcile the ledger with that release
before enabling another nightly. Do not edit state to reuse an attempted number.

## Setup

Install the project's Node/Bun versions, Java, Android SDK, and EAS CLI. Keep the
existing Expo login and approved EAS signing credentials on the build host.
The release profile uses `--freeze-credentials` to prevent credential changes.

Set these paths in the scheduler's environment:

- `ANDROID_HOME` or `ANDROID_SDK_ROOT`: absolute Android SDK directory. If both
  are set, they must match. LaunchAgent generation writes both aliases.
- `ANDROID_BUNDLETOOL_JAR`: the official Google bundletool JAR.
- `JAVA_HOME`: the installed supported JDK, if the default Java is different.
- `PLAY_SERVICE_ACCOUNT_KEY_PATH`: an existing Google Play release service-account
  JSON key. The file stays on the build host. It is never sent to Expo.

`nightly` builds an AAB; `nightly-apk` builds an APK.
`scripts/upload-play-internal.mjs` sends the AAB directly to Google Play and
sets one `completed` release on the fixed `internal` track.
Both must explicitly set `autoIncrement: false`. The runner changes
`cli.appVersionSource` to `local` only in the isolated worktree. It stamps the
reserved version into app and package metadata after project checks pass.
Moodinator's `build`, `build:preview`, and `build:production` commands and Zen
Mode's `android:bundle:upload` command use this same entrypoint. The obsolete
remote increment and standalone version-bump paths have been removed.

Before enabling the schedule, verify that an authorized Play API key exists.
An Expo signing key does not grant Play API upload access. Missing local
credentials stop the runner before it reserves a version or starts a build.
A configured key that Google rejects will fail the attempted release and consume
its number, as other build or upload failures do. Live API publishing remains
unverified until the authorized credential is available.

After the initial 0.1.5 releases are available to Internal testers, seed each
ledger once with its actual versionCode and source SHA:

```sh
node scripts/nightly-release.mjs seed 0.1.5 VERSION_CODE FULL_SOURCE_SHA
```

Seeding is idempotent for the same release. It refuses to overwrite an existing
ledger with another identity. The initial release also counts as that day's
attempt, so a new nightly starts on a later day after a new commit.

## Schedule both apps

Generate one LaunchAgent. It runs both apps in sequence so a failed app does not
stop the other app. The Mac must use the Europe/Vienna time zone.

```sh
node scripts/write-nightly-launch-agent.mjs /absolute/moodinator /absolute/zen-mode
plutil -lint .agents/artifacts/net.lab4code.nightly.plist
```

The generator writes the plist only. To enable it after access and initial
releases are checked:

```sh
mkdir -p "$HOME/Library/LaunchAgents"
cp .agents/artifacts/net.lab4code.nightly.plist "$HOME/Library/LaunchAgents/net.lab4code.nightly.plist"
launchctl bootstrap "gui/$(id -u)" "$HOME/Library/LaunchAgents/net.lab4code.nightly.plist"
```

The Mac must be logged in and available. Launchd runs a missed calendar job after
wake; it does not queue every missed day. No cloud nightly workflow remains.
To pause:

```sh
launchctl bootout "gui/$(id -u)/net.lab4code.nightly"
```

To run the pair manually, use
`node scripts/run-nightlies.mjs /absolute/moodinator /absolute/zen-mode`.
The normal daily and source gates still apply to manual runs.

## Artifacts and recovery

Artifacts default to
`~/Downloads/lab4code-releases/<app>/<version>-<versionCode>-<sha>/`.
Set `RELEASE_ARTIFACTS_DIR` to change the base directory. Each release includes
its APK, AAB, and `release.json` with the source SHA and outcome.
Install updates through the Play Internal testing link on your phone. The raw
APK uses the upload certificate; Play can use a different app-signing certificate,
so that APK may not update an app installed through Play.
Before submission, the runner checks both artifacts' package name, marketing
version, versionCode, and signature against the existing approved certificate.

EAS logs are private files with mode 0600 under
`~/.local/state/lab4code-releases/logs/<app>/<reservation-id>/`. They can contain
credential-bearing job output. Share the APK, AAB, and release record only.
Scheduler summaries are in `~/Library/Logs/lab4code-nightlies/`.

If the Mac shuts down during a build or loses SSH before completion, the central
reservation stays active and safely blocks later releases. Inspect `status` and
Play Internal first. After confirming no build/upload is running, close the
reservation using the outcome that actually occurred:

```sh
node scripts/nightly-release.mjs finish RESERVATION_ID failed
# Or succeeded, only after confirming the reserved version reached Internal.
```

Closing a reservation does not permit another attempt for its source SHA.
No automatic retry runs for submission failures, including ambiguous outcomes.

The release record includes a fixed, sanitized failure stage and message for
`preflight`, `checks`, `apk-build`, `aab-build`, `artifact-verify`, or `play-upload`.
Preflight records use `preflight-<SHA>` folders because no identity is reserved.
Final artifact verification remains a required gate before upload.

## Checks

```sh
node --test tests/nightly-version.test.mjs tests/upload-play-internal.test.mjs
python3 tests/nightly-ledger.test.py
```

The tests cover stale artifacts and wrong certificates, invalid versions,
failed-SHA retry prevention, daily gates, Vienna dates, versionCode exhaustion,
simultaneous reservations by two processes, uploaded versionCode mismatches,
Internal-only track changes, signed Google OAuth assertions, and safe API errors.

Local builds use the official [EAS local build command](https://docs.expo.dev/build-reference/local-builds/).
The uploader signs a short-lived [service-account OAuth assertion](https://developers.google.com/identity/protocols/oauth2/service-account)
locally. It sends that assertion only to `oauth2.googleapis.com`. It streams the
verified AAB to Google's [bundle upload API](https://developers.google.com/android-publisher/api-ref/rest/v3/edits.bundles/upload).
It checks the returned versionCode before changing Internal. It commits with
`changesInReviewBehavior=ERROR_IF_IN_REVIEW`, which protects an existing review from Google's default cancellation behavior.
Internal publishes automatically, so `changesNotSentForReview` is omitted.
Google rejects that parameter for these Internal releases.
See the [commit API](https://developers.google.com/android-publisher/api-ref/rest/v3/edits/commit).
No request retries run, and an API error does not fall back to a draft release.

The uploader's direct command accepts an already verified, reserved AAB:
`node scripts/upload-play-internal.mjs AAB_PATH VERSION VERSION_CODE`.
Use the coordinated release runner for normal nightly and manual releases.
