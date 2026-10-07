# GitHub-hosted Android nightlies

`.github/workflows/nightly.yml` builds Zen Mode on an `ubuntu-24.04` GitHub-hosted
runner at midnight Europe/Vienna. The schedule uses GitHub's IANA timezone field,
so daylight saving time does not change the local start time. Scheduled runs can
be delayed by GitHub. Manual `workflow_dispatch` uses the same gates. Only `main`
can run. The workflow has a 90-minute limit and one per-app concurrency group
with `cancel-in-progress: false`.

The 90-minute limit is a planning budget, not a measured hosted build time.
Historical local APK and AAB builds together took about 22–30 minutes for Zen
Mode. The first hosted build remains unverified.

## Credentials and activation

Configure exactly two user repository secrets:

- `EXPO_TOKEN`: Expo access to the existing project and remote signing credentials.
- `PLAY_SERVICE_ACCOUNT_JSON`: the full Google Play service-account JSON key,
  authorized to publish this app to Internal testing.

GitHub's normal `GITHUB_TOKEN` writes state through `contents: write`. No user
GitHub token secret is needed. The job uses that permission only for persistent
state. Checkout does not persist its token. No production promotion or new signing
key is part of this workflow. Existing `credentialsSource: remote`, approved
certificates, and EAS `--freeze-credentials` remain required.

The Play key is written with mode 0600 to an ephemeral runner file. It is removed
in an `always()` cleanup step. Runner disposal is the final cleanup on hard
cancellation. The raw JSON is not passed to the build step. Raw EAS logs stay in
private files and are never uploaded as artifacts. Do not print tokens, key JSON,
or EAS logs when diagnosing a run.

Before the first dispatch, the parent/operator must copy the exact current coding
ledger history to an orphan `release-state` branch in this repository, in
`ledger.json`. Do not seed from app metadata, create an empty ledger, or reset
failed attempts. The runtime refuses missing/uninitialized state. The parent owns
this one-time cutover, live secret verification, removal of the old live Mac
schedule, and the first hosted dispatch. This code change does not perform them.
The workflow becomes schedulable on merge. Missing secrets or state stop it before
reservation. Keep the old live scheduler disabled before activating hosted runs.

## Durable state and source checks

The only state authority is `MaximilianMauroner/zen-mode`, branch `release-state`,
file `ledger.json`. Both hosted and local commands read the same authority.
The existing Python transition rules remain unchanged:

- At most one attempt per Vienna calendar day.
- No second attempt for a source SHA, including after failure.
- Failed attempts consume their patch version and versionCode.
- An active reservation blocks another source until explicit reconciliation.

The adapter GETs the file from the fixed `api.github.com` endpoint. It PUTs changed
state with the previous blob SHA as a compare-and-swap condition. Status, duplicate
gates, and idempotent completion do not write unchanged state. Conflicts, HTTP
errors, and uncertain writes are not retried. A reservation result is returned
only after GitHub confirms persistence. No filesystem, cache, or artifact is an
authoritative ledger. There is no SSH persistence or legacy fallback.

The runner fetches `origin/main`, reads its EAS minimum, and checks the selected
stable EAS version before reservation. It installs dependencies and runs app tests,
release tests, lint, typecheck, and web export in an isolated worktree of that
fetched SHA. Profile validation and resource preflight also run before reservation.
It then stamps one reserved identity into app and package metadata and builds
both APK and AAB with local EAS on the GitHub runner.

Node 24, EAS CLI 20.5.1, Java 17, SDK platform/build-tools 36, and checksum-verified
bundletool 1.18.3 are configured. Action versions are pinned by full commit SHA;
checkout/setup-node/setup-java v5 and upload-artifact v6 use Node 24. The runner
checks actual free disk space and total memory. If needed it removes only unused
preinstalled .NET/Haskell/CodeQL toolchains before checking the 15 GiB disk and
8 GiB RAM minimums. Preflight repeats after source checks so dependency install
and export cannot consume the required build space unnoticed.

## Entry points and recovery

Hosted runs use the workflow. Local manual operations remain available:

```sh
node scripts/nightly-release.mjs status
node scripts/nightly-release.mjs run
npm run release:status
npm run release:internal
```

Local `status` and manual `finish` use `GH_TOKEN`, `GITHUB_TOKEN`, or a captured
`gh auth token --hostname github.com`. The token is never printed. Local builds
use an existing `eas login` session or an optional `EXPO_TOKEN`. GitHub Actions
requires `EXPO_TOKEN`. Local builds also require Android SDK, Java,
`ANDROID_BUNDLETOOL_JAR`, and `PLAY_SERVICE_ACCOUNT_KEY_PATH`.
If both SDK aliases are set they must match.
The local host lock remains because these commands can still run on one host.
It is a build lock, not a state store. The obsolete Mac LaunchAgent generator and
pair scheduler were removed; there is no repository compatibility scheduler.
Removing their source files does not disable an installed LaunchAgent.

On cancellation, timeout, a lost reserve response, or a lost finish write, inspect
the GitHub ledger and Play Internal before any new run. The reservation remains
active when completion is uncertain or cancellation is observed. Confirm that no
build/upload is still running and determine whether the reserved identity reached
Internal. Then close it explicitly:

```sh
node scripts/nightly-release.mjs finish RESERVATION_ID failed
# Use succeeded only after confirming that identity reached Internal.
```

A PUT can succeed even if its response is lost. Read status before issuing another
command; never assume an HTTP/transport error means no state change occurred.
Closing an attempt does not reuse its number, retry its SHA, or reset the daily
gate. API errors report sanitized diagnostics without response bodies or tokens.
There is no runtime `seed` command. Ledger initialization belongs to the manual
cutover, with current history preserved.

## Output and upload

Both artifacts must match the reserved package, version, versionCode, and existing
approved upload certificate. Bundle validation and signature checks stay unchanged.
Only verified APK/AAB copies and sanitized `release.json` records are retained as
GitHub artifacts for 30 days. Unverified build files and raw EAS logs are excluded.
Local artifacts default to `~/Downloads/lab4code-releases/zen-mode`; set
`RELEASE_ARTIFACTS_DIR` to change the output path.
Source-check failures before reservation retain sanitized evidence in `checks-SHA/release.json`.
The record includes the app, source SHA, status, failure stage, and finish time.
It has no reserved identity. A duplicate-source skip does not create a failed record.

The AAB goes directly to the fixed Google Play `internal` track with one
`completed` release. Returned versionCode must match before track mutation.
`changesInReviewBehavior=ERROR_IF_IN_REVIEW` protects an existing review; there is
no retry, production promotion, or draft fallback. Install updates through Play
Internal testing. The raw APK uses the upload certificate and may not update an
app installed through Play with its app-signing certificate.

## Checks

```sh
npm test
npm run test:nightly
npx tsc --noEmit
npm run lint
npx expo export --platform web
```

Pure/mocked tests cover transition rules, competing Contents API writers, missing
state, unchanged writes, uncertain persistence, cancellation, pre-reservation
source checks, EAS stable minimums, artifact identity, and Internal-only upload.
No test initializes a live ledger, dispatches a release, or uses real secrets.
