# Google Play readiness

Internal release-preparation checklist for the Android app. This records the
current repository state. It does not publish or send anything externally.

## Current facts

| Item | Evidence and implication |
| --- | --- |
| App name | `Zen Mode` in `app.json`. |
| Play app | Console app `4973526737842711493`, personal developer `6468274858330069554`; Console changes are maintained separately from this repository. |
| Android application ID | `com.lab4code.zenmode` in `app.json`; the owner selected it as the permanent Play package ID and the Console app has been created with it. |
| Version | `0.1.5` with Android version code `5` in `app.json`; its AAB upload to the Play Internal draft is processing, but is not yet confirmed accepted or saved. Increment the code for every later upload. |
| Protection target | Android only. Web checks the interface; iOS does not provide protection. |
| Accessibility service | The native module declares `BIND_ACCESSIBILITY_SERVICE`, can retrieve window content, and receives window/content, click, and scroll events. The service is marked `isAccessibilityTool=false`. |
| Supported feed surfaces | YouTube Shorts; Instagram Reels, Home, Explore, and Direct Messages for setup/provenance; X Home and video viewer. X support is for the Android app package, not the browser. |
| Supported browser probes | Chrome, Samsung Internet, Opera, and Firefox have package-specific address-bar adapters. Each remains unverified until its current stable normal/private modes pass on-device checks. |
| Website rules | The adult-site switch is off by default. A small bundled hostname catalog and up to 100 user-added domains are matched locally. Visited addresses are not persisted. This is reactive accessibility enforcement, not network filtering. |
| Other app rules | Launchable Android apps can receive daily, timed-visit, or rolling-window rules. Foreground package events are used to charge configured app rules. |
| Package visibility | The module queries YouTube, Instagram, X, launcher activities, and exact Android Settings intents used to preserve the Accessibility escape path. Settings handlers are exempted only when Android identifies them as system or updated-system apps. `app.json` blocks unrelated storage and overlay permissions. Verify the merged release manifest. |
| Listing decisions | Free, Productivity, support@lab4code.com, repository website, no ads/AD_ID, no account requirement, target ages 13–15/16–17/18+, IARC PEGI 3 / ESRB Everyone. Console operator reports these saved. |
| Public policy identity | Maximilian Mauroner (Lab4Code), Austria, support@lab4code.com; explicitly approved for publication. |
| Build state | Release signing is fail-closed. EAS holds an app-specific Zen upload key, and the upload-key-signed 0.1.5 AAB/APK identified in `docs/evidence/android-release-candidate-0.1.5-20260916.md` have been built and audited. The key was used ephemerally and is not stored in this repository or VM workspace. |

The repeatable build, signer verification, and key-custody boundary are in
[android-release-signing.md](android-release-signing.md).

## Debug preview versus Play upload

`npm run android` installs a local native development build. Historical local
release-mode APKs were signed with the default debug key; they remain test
artifacts and are not Play upload candidates. Release configuration must never
fall back to that certificate.

A Play release needs a signed Android App Bundle (`.aab`) built by the chosen
release pipeline. Configure the release keystore or Play App Signing upload
key, keep its recovery material with the owner, set a unique incrementing
version code, and verify the final package and signature before upload. Do not
upload an APK signed with the debug key.

The new application ID does not replace an installed preview using
`com.maxmauroner.zenmode`. Android treats `com.lab4code.zenmode` as a separate
installation with separate local data. Do not uninstall or clear the old
preview as part of release preparation; decide separately whether any local
test data needs manual migration or retention.

## Release checklist

### Console and listing

- [ ] Resolve every item in [Remaining human gates](#remaining-human-gates)
      before uploading an artifact or making the listing available.
- [ ] Prepare the required 512 × 512 app icon, 1024 × 500 feature graphic, and
      final screenshots. A promo video is optional.
- [x] Use Play Internal testing for the initial AAB upload. This does not
      authorize publication or a production rollout.

### Build and signing

- [x] Configure a repeatable fail-closed release build that creates a signed
      `.aab` only when all external upload-key values and its approved
      certificate fingerprint are supplied.
- [ ] Export the approved owner-held encrypted recovery backup for the
      EAS-managed Zen upload key through a secure owner destination. Max is the
      approved custodian; EAS currently holds the managed primary copy.
- [x] Set the release version code and version name. Confirm the package in the
      built manifest is `com.lab4code.zenmode`.
- [x] Inspect the merged release manifest. Confirm the accessibility service is
      present and no unwanted debug, storage, overlay, or broad package-query
      permissions were added.
- [x] Install and test the exact signed candidate. Check that debug-only
      diagnostics are absent; Instagram trace and blocker diagnostics are gated
      by the debug build flag. The API-35 harness used
      `adb -i com.android.vending`, which assigns synthetic installer-package
      metadata only; it was not Play delivery and does not verify Play
      installation or restricted-settings behavior.
- [x] Backport the exact merged `react-native-screens` listener-lifetime fix
      and run bounded API-35 regression samples: 100/100 valid debug cold starts
      and 100/100 valid test-signed release-mode cold starts, with no Zen Mode
      crash. See `docs/evidence/android-fabric-lifetime-backport-20260915.md`.
      Remove the patch only after an Expo-supported dependency includes the fix
      and the documented clean-install/device checks pass without it.
- [x] Verify the AAB signature and contents with the release toolchain. Keep the
      debug APK and preview APK out of Play uploads.

### Accessibility, privacy, and review declarations

- [ ] Validate the native consent gate on a device upgrade with the service
      already enabled. The service now requires current native consent before
      event, timer, usage, overlay, or navigation processing; unit coverage is
      present, but the real upgrade journey is still required.
- [x] On the API-35 `moodqa` AVD with literal stale daily, timed-visit, and
      rolling rules for the resolved Settings package, confirm Android Settings
      remains reachable without an overlay or Home navigation. Repeat while the
      in-app settings lock is active, then disable the service from Accessibility
      settings. The signed-candidate automated path passed. See
      `docs/evidence/android-release-candidate-0.1.5-20260916.md`. The debug
      harness can still be repeated on the dedicated `moodqa` task AVD:

      ```bash
      ZEN_GUARD_TEST_SERIAL=emulator-5554 \
        ZEN_GUARD_ALLOW_TASK_DATA_RESET=clear-com.lab4code.zenmode-on-moodqa \
        npm run test:android-settings-safety
      ```

      The harness clears only `com.lab4code.zenmode` data, seeds its private rule
      stores, toggles its accessibility service through Android UI, and clears
      the task-app data after its final assertions. It refuses non-emulators,
      other AVD names, and runs without the explicit reset acknowledgement.
- [ ] Confirm Android Settings does not appear in the real app picker on the
      exact candidate, and repeat the escape/disable journey on representative
      physical OEM devices. The automated stale-rule run does not exercise the
      launchable-app picker, and the signed-candidate run covered only the
      `moodqa` emulator.
- [ ] When the accessibility disclosure changes, bump both
      `CONSENT_VERSION` in `src/features/protection/setup-policy.ts` and
      `CURRENT_VERSION` in the native `ConsentPolicy.kt` in the same release.
      A one-sided bump can either repeat setup unnecessarily or let the service
      accept an outdated disclosure.
- [x] Prepare the Accessibility API declaration and reviewer instructions in
      [play-accessibility-declaration.md](play-accessibility-declaration.md).
      Console acceptance and exact signed-candidate evidence remain pending.
- [ ] Review the in-app prominent disclosure and consent screen against the
      final behavior. The current copy covers accessibility screen-content
      processing for YouTube, Instagram, and X, known browser address bars when
      website blocking is enabled, foreground tracking for app limits, and the
      launchable-app list used when choosing a rule. It also says screen contents,
      browser addresses, and installed-app inventory are not sent off this device.
      The owner must approve that wording and keep it aligned with the shipped behavior.
- [x] Prepare the current Data safety evidence and category worksheet in
      [play-data-safety.md](play-data-safety.md). The Console operator must
      resolve the documented GitHub-feedback classification and submit it.
- [x] Finalize the owner-approved [privacy policy](../PRIVACY.md) and align the
      in-app privacy screen. Verify the public GitHub URL before entering it in
      Console.
- [ ] Ensure the listing and review notes say that protection needs Android
      Accessibility access, an installed native Android build, and observation
      setup. Do not describe the web preview or iOS as enforcement targets.
- [ ] Prepare reviewer steps for enabling the service and testing YouTube,
      Instagram, and X. If a third-party app account or specific app version is
      needed, provide the owner's approved review instructions or test account.

## Google references

Check these official sources when completing the current Play Console forms and
upload flow:

- [Create and set up your app](https://support.google.com/googleplay/android-developer/answer/9859152)
- [Add preview assets to showcase your app](https://support.google.com/googleplay/android-developer/answer/9866151)
- [Permissions and APIs that Access Sensitive Information](https://support.google.com/googleplay/android-developer/answer/16558241)
- [User Data](https://support.google.com/googleplay/android-developer/answer/10144311)
- [Sign your app](https://developer.android.com/studio/publish/app-signing)

## Manual test and video procedure

Use a clean, supported Android device or emulator with the current YouTube,
Instagram, and X Android apps installed. Run this procedure on the exact signed
candidate before upload. Use the shortest available values for fast checks:
15 seconds for the Instagram pause, 1 minute for viewing or Home allowances,
and 1 minute for timed visits where available.

### Setup and permission path

1. Clear Zen Mode app data or use a fresh install. Launch the app.
2. Confirm the setup disclosure is visible and that the guard cannot be
   enabled until the consent checkbox is selected.
3. Tap `Turn on the guard`, enable Zen Mode in Android Accessibility settings,
   and return to the app. Confirm the status changes from access needed to
   running.
4. Complete observation in order: open one YouTube Short; in Instagram open
   Direct Messages and one Reel from a message; in X open Home and one video.
   Return to Zen Mode after each check. Unknown or ambiguous surfaces must not
   start a feed rule.

### Controls and rule behavior

5. On Feeds, confirm exactly four grouped controls: YouTube, Instagram, X, and
   Sites.
   Open each drawer and verify its controls, saved status, and `Open` button.
6. Set a daily app budget. Use the app until it reaches the budget and confirm
   Zen Mode returns to Home. Set a timed visit and confirm Start grants one
   visit, expiry returns Home, and the configured downtime is shown on the next
   open. Set a rolling allowance and confirm it returns Home at the limit.
7. YouTube: after observation, start Shorts protection. Confirm one Short is
   allowed and scrolling to another returns to YouTube Home with the block
   notice.
8. Instagram: set the shortest Reels pause and viewing window. Confirm a Reel
   opened from a DM is allowed, the pause appears after the window or a blocked
   swipe, and Continue becomes available only after the pause. Confirm Home
   stops at its allowance and Explore is blocked when that option is enabled.
9. X: set the shortest Home break. Confirm the Home break appears after the
   allowance and a verified video-pager scroll leaves the video viewer. Check
   that ordinary posts and playback progress do not trigger the video rule.
10. Open Sites and turn on adult-site blocking. Add a safe fixture domain and
    test direct navigation, link navigation, redirect, reload, tab switch,
    collapsed toolbar, Back, and Home in current stable Chrome and Samsung
    Internet, in normal and private modes. Repeat for Opera and Firefox before
    claiming them supported. Do not load or record real adult content. Confirm
    lookalike domains, unknown browsers, and in-app pages remain open.
11. Lock settings for one day. Confirm tightening a rule and adding a domain
    still work, while disabling a rule, removing a domain or app rule, or
    increasing an allowance is refused. Request unlock and confirm the UI says
    it cannot open before the cooling-off period and lock expiry.
12. Confirm Android Settings is absent from the app picker. Seed or retain stale
    daily, timed-visit, and rolling rules for its resolved package, then open
    Accessibility settings while protection and the in-app settings lock are
    active. Confirm no overlay, timer, or navigation sends the user Home, and
    disable the service successfully.
13. Return to Zen Mode and confirm saved rules remain visible but no longer
    enforce. Re-enable Accessibility access and protection, then repeat one
    feed check.

### Capture procedure

Record a short, reviewer-safe walkthrough from the signed candidate:

1. Start on the setup disclosure, then show Android Accessibility access and
   the running status after returning.
2. Show the Feeds page with the four grouped rows. Open the Sites drawer to show
   the switch, browser readiness, and an empty custom list; then show the App
   limits and Lock tabs.
3. Capture one real enforcement result, such as the Instagram Explore blocker
   or the YouTube one-Short boundary. Include the user action that caused it.
4. End on the saved rule/status screen. Keep personal messages, usernames,
   notifications, account data, and unrelated app content out of frame.

Do not record a fabricated state or claim that a web preview enforces Android
rules. Label any debug build as an internal preview; use the signed candidate
for final store media.

The synthetic, Settings-focused shot list is maintained in
[reviewer-video-script.md](reviewer-video-script.md).

## Remaining human gates

These items cannot be completed from the repository:

- owner approval of the bundled adult-site catalog, its review source, and its
  release update policy;
- final Data safety classification of the optional GitHub feedback URL and
  submission of Data safety/Accessibility forms by the Console operator;
- owner-held encrypted upload-key recovery backup and Play App Signing
  enrollment/verification in Console; the app-specific EAS-managed upload key,
  Max custody decision, and approved certificate fingerprint are complete;
- confirmation that the processing Internal draft upload was accepted and
  saved, followed by installation of a Play-generated split APK set and
  verification of its Play App Signing identity and restricted-settings
  behavior;
- supported Android API/device and third-party app-version matrix;
- reviewer setup or test-account instructions, if required;
- exact production-signed candidate correlation for the reported physical-device
  testing, plus candidate-specific screenshots and reviewer video;
- countries and any later release track or rollout beyond the approved Internal
  testing draft; and
- any final legal/trademark review of the YouTube, Instagram, and X references.
