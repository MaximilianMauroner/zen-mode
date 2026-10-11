# Real-app testing on Android

Use `scripts/real-app-qa/cli.mjs` to test a source-matched Zen Mode build
against real YouTube, Instagram, X, Chrome and Firefox. The tool:

- builds a separately named QA APK from a clean commit;
- installs it on one explicitly selected device;
- installs or inventories authentic target apps and records their versions and
  signing certificates;
- grants only the QA accessibility service;
- records screenshots, window state and logs without UI Automator.

It does not tap through journeys. An operator, human or agent, drives the
apps. The tool judges only observations in which it opened the target app
itself. All other observations are `OBSERVED` for the operator to judge.

Use a disposable emulator or an owned test device. Do not use a personal
phone. Commands that change device state refuse a physical device unless you
pass `--allow-physical`.

## Prerequisites

- A Google Play emulator image, for example `Medium_Phone`, ARM64 on Apple
  silicon. Chrome and YouTube are part of that image.
- Android SDK with platform-tools and build-tools (`aapt`, `apksigner`).
  Set `ANDROID_HOME`.
- JDK 21. When `JAVA_HOME` is not set on macOS, the build uses
  `/usr/libexec/java_home -v 21`. A set `JAVA_HOME` must run `java`.
- Node 22.13 or later and `npm ci` in this checkout.

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
QA="node /absolute/path/to/tool-checkout/scripts/real-app-qa/cli.mjs"
```

## Test layers and emulator baseline

Use each layer for what it can prove:

1. Kotlin policy and parser fixtures (`npm run test:native`) cover edge cases,
   such as unknown trees, deadlines and window order. They do not prove that
   current app versions send those signals.
2. Real apps on an owned API 36 Play Store emulator, with the real
   accessibility service, prove current app behavior.
3. Screenshot-only observation (`observe`) gives timing and enforcement
   evidence.

Synthetic apps, fixtures and signed-out pages cannot establish Instagram or X
feed acceptance.

Make a reusable baseline once. Then load it for every run.

On the API 36 image, the emulator's default Vulkan graphics prevent
snapshots: `snapshot save` prints `KO` with `UNSUPPORTED_VK_APP`. Start the
emulator with `-feature -Vulkan` for the first boot and for every run. Make
the first boot a cold boot:

```bash
sdkmanager "system-images;android-36;google_apis_playstore;arm64-v8a"
avdmanager create avd -n zen-qa-api36 -k "system-images;android-36;google_apis_playstore;arm64-v8a"
emulator -avd zen-qa-api36 -no-snapshot-load -feature -Vulkan   # first boot, cold
```

On the first boot, do these steps once:

1. Sign in to the Play Store, Instagram and X with test accounts only. Max
   signs in once.
2. Install Firefox with `install-target`.
3. Complete the first-run screens of each browser.
4. Do not install a QA build.
5. Save the snapshot and read it back:

```bash
adb -s emulator-5554 emu avd snapshot save zen-baseline   # must print OK
adb -s emulator-5554 emu avd snapshot list                # must list zen-baseline
```

`adb` exits with 0 also when the save fails, so read the output. The baseline
is saved only when the save prints `OK` and the list shows `zen-baseline`.

For each run, start from the baseline with the same graphics setting and do
not save over it:

```bash
emulator -avd zen-qa-api36 -snapshot zen-baseline -no-snapshot-save -feature -Vulkan
```

Then install the QA build, grant access and record a new `inventory`. Check the
device time with `adb shell date` before timed journeys. The snapshot holds
signed-in sessions. Keep it on the owner's machine and never upload it.
Refresh the baseline when target apps need updates, and record the new
versions with `inventory`.

## Running the tool

Run every command from the root of the checkout under test. The tool can live
in another checkout, so you can test a PR head that does not contain the tool.
Builds and evidence stay in `.agents/artifacts/real-app-qa/` of the checkout
under test.

## 1. Build a QA APK

```bash
$QA build --id pr77a           # default --abi arm64-v8a; ID defaults to the short commit
```

The build refuses uncommitted or untracked changes. It also refuses an ID that
already has an output directory, so the evidence of every attempt stays. Use a
new ID for each attempt. To test a PR, use a separate checkout of its exact
head, for example `git worktree add --detach ../zen-pr77 origin/<branch>`, run
`npm ci` there, and build from it. Build each PR separately for per-head
evidence. A local merge of several PRs is faster, but its evidence
covers only that combination.

The command runs `expo prebuild --clean` and then `:app:assembleQa`. The Gradle
init script `scripts/real-app-qa/qa-build-type.init.gradle` adds a `qa` build
type with these properties:

- release configuration and an embedded JavaScript bundle, not debuggable;
- signed with the Expo template debug key. No task name contains "release", so
  the release signing guard is not involved, and the build environment drops
  every `ZEN_MODE_UPLOAD_*` and EAS signing variable;
- package `com.lab4code.zenmode.qa<id>`, label `Zen QA <id>`, service
  `Zen QA <id> protection`, and version `<version>-qa.<commit>`. The QA app
  has separate storage and a separate accessibility grant from the Play app.

Resource controls:

- Gradle `--no-daemon --no-parallel --max-workers=1`, JVM heap 1536 MiB, Kotlin
  in process;
- CMake job pools of one compile job and one link job, and
  `CMAKE_BUILD_PARALLEL_LEVEL=1`;
- Metro with one worker, and one ABI.

Run one native build at a time on a host, and monitor memory and disk while it
runs.

After the build, the tool checks the package, label, version suffix, debuggable
flag and signer with `aapt` and `apksigner`. It also checks that prebuild did
not change tracked files. Output goes to `.agents/artifacts/real-app-qa/builds/<id>/`:
the APK, `build.json` (commit, Java, controls, APK hash and identity) and
`build.log`.

## 2. Select the device and install

Every device command needs `--build <id>` and `--serial <adb serial>`. The tool
never selects a device by default.

```bash
adb devices
$QA install --build pr77a --serial emulator-5554
```

`install` replaces only the same QA package. Then it checks that the hash of
the APK on the device matches the build.

## 3. Target apps

| Target | Package | Official source | Authentic when | Sign-in |
| --- | --- | --- | --- | --- |
| Chrome | `com.android.chrome` | Google Play emulator image | System app signed by Google certificate `f0fd6c5b…` | No |
| YouTube | `com.google.android.youtube` | Google Play emulator image | System app signed by Google certificate `3d7a1223…` | No |
| Firefox | `org.mozilla.firefox` | Mozilla release archive | Signed by Mozilla certificate `a78b62a5…` | No |
| Instagram | `com.instagram.android` | Google Play | Installed and started by the system Google Play | Test account |
| X | `com.twitter.android` | Google Play | Installed and started by the system Google Play | Test account |

A valid signature alone does not make an app authentic: anyone can sign an APK
with the same package name. `model.mjs` pins the full certificates. A target
that fails its rule is never `READY`, and `observe` does not open it.

The tool reads only the `Signer #N` certificates that
`apksigner verify --print-certs` prints. Google Play adds a
`Source Stamp Signer` certificate, `3257d599…` on Chrome and YouTube. The stamp
records the distribution channel and does not sign the app, so it never
counts. An APK that `apksigner` reports only with v3.1 rotation signers,
`Signer (minSdkVersion=…)`, has no recorded signer and stays `BLOCKED`.

Instagram and X do not publish a signing certificate, so the tool does not pin
one. It accepts them only from the install record in `dumpsys package`:

- `installerPackageName` is `com.android.vending` (Google Play);
- `initiatingPackageName`, the package that started the install, is also
  `com.android.vending`;
- that `com.android.vending` package is in the system image.

`adb install -i com.android.vending` sets only the installer. Android records
the shell, `com.android.shell`, as the initiator, so the app stays `BLOCKED`.
An app that uses the Google Play package name on an image without Google Play
is not in the system image.

Limits of this check:

- Android 13 and earlier do not print `initiatingPackageName`. There Instagram
  and X stay `BLOCKED`. Use the API 36 Play Store image.
- The check trusts Android's install record and the system Google Play. Root
  access can change the record. Use the Play Store image, which has no
  `adb root`, and do not root an owned test device.
- The record proves that Google Play installed the current version. It does
  not prove which Play release or account supplied it.

`install-target` installs only an official APK of a target with a pinned
signer, at present Firefox. It installs a base APK and its splits only after
these checks:

- every file has a valid `apksigner` signature;
- all files have one package, version and signer set;
- the pinned official signer signed the files;
- the device does not already have that app.

It never replaces or downgrades an installed app. It records the source, file
hashes, version and signer certificate. Do not use APK mirror sites. Install
Instagram and X from Google Play only.

Firefox 157 for ARM64. Mozilla publishes the Firefox signer in
<https://accounts.firefox.com/.well-known/assetlinks.json>. Put the download in
its own new directory:

```bash
URL=https://archive.mozilla.org/pub/fenix/releases/157.0/android/fenix-157.0-android-arm64-v8a/fenix-157.0.multi.android-arm64-v8a.apk
mkdir -p .agents/artifacts/real-app-qa/targets/firefox-157.0
curl -fL --proto '=https' -o .agents/artifacts/real-app-qa/targets/firefox-157.0/fenix.apk "$URL"
$QA install-target --build pr77a --serial emulator-5554 \
  --apk .agents/artifacts/real-app-qa/targets/firefox-157.0/fenix.apk --source "$URL"
```

Instagram and X need the Play Store on the emulator, a test Google account and
test app accounts. If an app or account is missing, its journeys stay
`BLOCKED`. Do not sign in with personal accounts. After you sign in, confirm it
with `--signed-in instagram` or `--signed-in x` on `inventory`. The tool cannot
verify sign-in without disturbing accessibility.

Complete each browser's first-run screens once before you run browser checks.

## 4. Consent and accessibility

1. Open the QA app:
   `adb -s emulator-5554 shell am start -n com.lab4code.zenmode.qa<id>/com.lab4code.zenmode.MainActivity`.
2. Complete the in-app setup and consent. The native service does no work
   before current consent.
3. Grant the service:

```bash
$QA access enable --build pr77a --serial emulator-5554    # also: disable, status
```

`access` edits only the QA component in `enabled_accessibility_services`. Other
services, such as the Play app or TalkBack, are kept. It waits until
AccessibilityManager reports the service bound. This skips Android's
confirmation dialog. For journeys that test the grant itself, use Android
Settings. Sideloaded builds on Android 13 and later may need
App info → Allow restricted settings first.

## 5. Inventory and readiness

```bash
$QA inventory --build pr77a --serial emulator-5554 [--signed-in instagram --signed-in x]
```

This records the device model, Android version, API and build fingerprint. It
also records the QA install state and, for each target, the version, installer,
install initiator and signer certificate. It pulls each target APK to read its
signer and deletes the copy afterwards, and checks each target against its
authenticity rule in section 3. Then it lists every PR journey as `READY` or
`BLOCKED`, with reasons.

## 6. Observe without disturbing accessibility

UI Automator hierarchy dumps and agent-device snapshots suspend accessibility
services while they run. An enforcement or timer result from such a moment is
not valid. Use dumps only to configure and navigate. To drive a journey, use
screenshots, `adb shell input tap|swipe|keyevent` and intents.

```bash
$QA observe --build pr77a --serial emulator-5554 --label firefox-block \
  --open-url https://example.com --app firefox --expect overlay --seconds 15
```

Before it captures anything, `observe` checks these points:

- the installed QA APK is this build;
- the QA service is enabled;
- with `--app`, the installed target APK is authentic now. It pulls the APK
  again and does not trust an earlier `inventory`;
- the service is bound again, then waits `--settle` seconds (default 3).

With `--open-url` and `--app`, it opens the URL in that app with `am start -W`.
It samples each `--interval` second (default 1) for `--seconds` (default 10).
The first sample comes after `am start -W` returns, or at once without
`--open-url`. The last sample starts at or after the end: if a slow sample
runs past the end, one more sample follows. Times in the timeline count
from the first sample, so a slow launch does not shorten the observation or
add to its times. Only `--expect cleared` also samples before the launch; see
below. Each sample records:

- whether a QA-owned accessibility overlay is on screen;
- the foreground app;
- whether the service is still bound;
- optional `--audio-package` media session states.

Each time is a decimal number of seconds from 0 to 3600. `--interval` must be
above 0. `--seconds 0` takes one sample, for a capture.

It takes screenshots at the start, at each overlay or foreground change, and at
the end. It also saves a logcat slice and the crash buffer.

`--expect` needs `--open-url`:

- `overlay`: the overlay shows at the end;
- `no-overlay`: the overlay never shows. This is allowed only after an
  `overlay` PASS in the same app on the same device (every recorded device
  field, such as serial, AVD and build fingerprint), with the same QA build
  and the same target APK. A service that
  never reads the app would otherwise pass. Other AVDs can reuse a serial such
  as `emulator-5554`;
- `cleared`: the QA overlay shows over the target before the tool opens the
  URL, and is gone at the end. A correct dismissal can finish before
  `am start -W` returns, so the first sample comes before the URL opens. If
  that sample does not show the bound overlay with the target in front,
  `observe` stops with `BLOCKED` and does not open the URL. Times count from
  this sample, so the later times include the launch. The observation still
  lasts `--seconds` after `am start -W` returns.

The result is `INVALID` in these cases:

- the service was not bound for every sample;
- the opened app was not in front for every sample, for example after a
  chooser or a failed intent;
- the QA app crashed.

Without `--expect`, the result is `OBSERVED`. The operator reads the timeline
and screenshots.

## 7. Journeys for PRs 76 to 79

Use short limits. Reset the QA app between unrelated journeys with
`adb -s <serial> shell pm clear com.lab4code.zenmode.qa<id>`. This clears only
the disposable QA storage. Run the lock journey last.

| Journey | Steps | Expected |
| --- | --- | --- |
| `pr77-firefox-address-bar` (#67) | Sites on, add `example.com`. Run `observe --open-url https://example.com --app firefox --expect overlay`, then `--open-url https://example.org --expect no-overlay`. Open Sites and capture Firefox readiness with `observe --seconds 0`. | PASS, PASS, and Firefox `READY` in the capture. |
| `pr78-site-stats` (#72) | Capture Statistics. Make one Firefox and one Chrome boundary with `observe --expect overlay`. Leave each with its Go home button. Refresh Statistics and capture. | Site count rises by one for each boundary. |
| `pr78-domain-error` (#68) | Sites: submit `localhost` as a custom domain and capture. Then submit `127.0.0.1` and capture. | `localhost`: "Enter a complete domain like example.com." The dot check runs before the local address check. `127.0.0.1`: "Local and IP addresses are not supported." No `java.lang` or `ZenGuard.addBlockedDomain` text in either. |
| `pr76-usage-on-resume` (#69) | Add a 5m daily Clock rule, the shortest daily option, and keep App limits selected. Start Clock with `am start -n com.google.android.deskclock/com.android.deskclock.DeskClock`, then `observe --seconds 320`. When Home shows, bring the QA app to the front and capture. | Clock is sent Home after about 300 s, before the observation ends. The list shows "5 of 5 minutes today" and SPENT with no tab switch. |
| `pr76-editor-error` (#73) | Add a 5m daily Clock rule. Lock settings. Change Clock to Visit and save, then Cancel. Add another launchable app and choose Timed visit. Capture each state. | The refusal shows for Clock. The new editor shows no earlier error and no Manage lock. Saving the new rule works. |
| `pr77-reels-deadline` (#71) | Instagram signed in. Observe setup, set a 30s pause and a 1m Reels window, start protection. Open a shared Reel from a DM and swipe to get the pause. Run `observe --seconds 110`. Tap Continue when it unlocks, then touch nothing. | The timeline shows the overlay go at Continue and return 60 to 62 s later. |
| `pr77-x-search-under-break` (#70) | X signed in. Set Home breaks to 1m and view Home until the break shows. While it shows, run `observe --open-url 'twitter://search?query=nasa' --app x --expect cleared`. Then open Home again and capture with `observe`. | PASS: the break shows over X before Search opens and closes over Search. Home stays blocked during the rest. |
| `pr78-instagram-stats` (#72) | Instagram signed in. Capture Statistics. Make one Reels, Explore and Home boundary. Refresh and capture. | Each Instagram category rises by one. |
| `pr79-reels-close-audio` (#74) | Instagram signed in. Play and pause a YouTube video. Open a Reel until the blocker shows. Run `observe --audio-package com.google.android.youtube --seconds 20` and tap Leave. Repeat with Continue, and once with YouTube still playing. | YouTube never returns to `PLAYING` after the blocker closes. |

## 8. Evidence

Device evidence goes to `.agents/artifacts/real-app-qa/runs/<id>-<serial>/`.
This directory is not in Git.

- `evidence.jsonl`: one record per command, each with device identity, QA
  commit, version and APK hash. An `observe` record with `--app` also has the
  target APK that it verified;
- `<time>-<label>-NN-<phase>.png`: screenshots;
- `<time>-<label>.timeline.json`: samples;
- `<time>-<label>.logcat.txt`: Zen Mode trace, React Native and crash logs.

A report gives these items for each journey:

- QA ID, commit and APK hash;
- device fingerprint;
- target app version and signer;
- `PASS`, `FAIL`, `BLOCKED` or not run, with the evidence files.

A `BLOCKED` journey is not a pass. Screenshots must not show private messages
or account details.

## 9. Cleanup

```bash
$QA access disable --build pr77a --serial emulator-5554
adb -s emulator-5554 uninstall com.lab4code.zenmode.qapr77a
```

Remove only QA packages. Keep system apps and target apps unless the emulator
is disposable and you installed them for this run.
