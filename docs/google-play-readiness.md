# Google Play readiness

Internal release-preparation checklist for the Android app. This records the
repository state and dated release evidence. It does not publish or send anything externally.

For the 2026-09-26 release/QA identity audit and short physical acceptance
journeys, start with [Android acceptance handoff](android-acceptance-handoff.md).
Historical code-7 evidence below does not identify today's installed Play build.

## Current facts

| Item | Evidence and implication |
| --- | --- |
| App name | `Zen Mode` in `app.json`. |
| Play app | Console app `4973526737842711493`, personal developer `6468274858330069554`; Console changes are maintained separately from this repository. |
| Android application ID | `com.lab4code.zenmode` in `app.json`; the owner selected it as the permanent Play package ID and the Console app has been created with it. |
| Current source version | `app.json` is the source of truth. At this revision it declares `versionName` `0.1.5` and Android `versionCode` `8`; the consistency test keeps this statement aligned when the source version changes. |
| Historical verified artifact | The dated code-7 AAB evidence below refers to the `0.1.5` artifact that was independently matched, replaced code 6 in Internal release 1, and reported Active, available to internal testers, and Not reviewed at 08:54 UTC on 2026-09-16 on track `4700894893507986209`. No production release exists. |
| Protection target | Android only. Web checks the interface; iOS does not provide protection. |
| Accessibility service | The native module declares `BIND_ACCESSIBILITY_SERVICE`, can retrieve window content, and receives window/content, click, and scroll events. The service is marked `isAccessibilityTool=false`. |
| Supported feed surfaces | YouTube Shorts; Instagram Reels, Home, Explore, and Direct Messages for setup/provenance; X Home and video viewer. X support is for the Android app package, not the browser. |
| Supported browser probes | Chrome, Samsung Internet, Opera, and Firefox have package-specific address-bar adapters. Each remains unverified until its current stable normal/private modes pass on-device checks. |
| Website rules | The adult-site switch is off by default. A small bundled hostname catalog and up to 100 user-added domains are matched locally. Visited addresses are not persisted. This is reactive accessibility enforcement, not network filtering. |
| Other app rules | Launchable Android apps can receive daily, timed-visit, or rolling-window rules. Foreground package events are used to charge configured app rules. |
| Package visibility | The module queries only YouTube, Instagram, X, Chrome, Samsung Internet, Opera, Firefox, launcher activities, and exact Android Settings intents used to preserve the Accessibility escape path. Settings handlers are exempted only when Android identifies them as system or updated-system apps. `app.json` blocks unrelated storage and overlay permissions. Verify the merged release manifest. |
| Listing decisions | Free, Productivity, support@lab4code.com, repository website, no ads/AD_ID, no account requirement, target ages 13–15/16–17/18+, IARC PEGI 3 / ESRB Everyone. Console operator reports these plus the listing text, icon, and public policy URL saved as a draft. |
| Public policy identity | Maximilian Mauroner (Lab4Code), Italy, support@lab4code.com; explicitly approved for publication. No street address is published. |
| Build state | Release signing is fail-closed. EAS holds an app-specific Zen upload key. The local release ledger inspected on 2026-09-26 records code 11 (`0.1.8`, source `8d0206c`) as its latest success and code 12 as failed. This is runner evidence, not a fresh Console or Play-installed observation. The code-7 audit is historical; see the acceptance handoff for exact identities. Play-installed acceptance remains pending. Code-5 build/device and code-6 superseded-candidate evidence remain preserved separately. The key is not stored in this repository or VM workspace. |
| Internal access | Opt-in: <https://play.google.com/apps/internaltest/4700894893507986209>. The sole selected list is `Max internal testing`, containing only `maximilian.mauroner@gmail.com`; a second mistyped address was never added. |

The repeatable build, signer verification, and key-custody boundary are in
[android-release-signing.md](android-release-signing.md).

## Product journey and control acceptance matrix

This matrix describes the release-readiness slice in this branch. “Code/tests
verified” means the selector or wiring is covered by repository checks; it does
not stand in for native visual interaction or a signed Play artifact.

| Journey or control | Acceptance outcome | Exact source and test evidence | State |
| --- | --- | --- | --- |
| First use and disclosure | The first screen says what Zen Mode limits, that it is Android-only, and that supported feeds/sites need checks after access. Consent still precedes native protection. | [setup.tsx](../src/app/setup.tsx), [setup.ts](../src/features/protection/setup.ts), [setup-policy.test.mjs](../tests/setup-policy.test.mjs) | Code/tests verified; newcomer and native UI not verified |
| Android access and return | Android access remains a separate Settings action; status is refreshed on focus and when the app returns active. | [use-guard-status.ts](../src/features/protection/use-guard-status.ts), [index.tsx](../src/app/%28controls%29/index.tsx) | Wiring verified; native Settings walk not verified in this PR |
| Supported app availability | Only YouTube, Instagram, X and the four adapter-backed browsers are reported. Installed, disabled, absent, unknown, and native-unavailable are distinct. Known absent targets do not create an endless setup prompt. | [target-availability.ts](../src/features/protection/target-availability.ts), [ZenGuardModule.kt](../modules/zen-guard/android/src/main/java/com/maxmauroner/zenguard/ZenGuardModule.kt), [AndroidManifest.xml](../modules/zen-guard/android/src/main/AndroidManifest.xml), [release-readiness.test.mjs](../tests/release-readiness.test.mjs) | Code/tests verified; package-manager/device evidence not verified |
| Overview and next action | Header and overview distinguish protection off, Android access needed, setup, partial readiness, active-for-ready-rules, no rules, and unavailable/unknown targets. Existing feed precedence is retained; Sites is offered when it is the remaining check. | [feed-status.ts](../src/features/protection/feed-status.ts), [overview-actions.ts](../src/features/protection/overview-actions.ts), [control-header.tsx](../src/components/ui/control-header.tsx), [index.tsx](../src/app/%28controls%29/index.tsx) | Code/tests verified; native visual hierarchy not verified |
| Feed controls | Disabled/absent feeds remain explained; X Home and Videos retain independent signal readiness. An opener failure is shown in the drawer and does not mutate saved rules. | [feed-presentation.ts](../src/features/protection/feed-presentation.ts), [feed-controls-drawer.tsx](../src/components/ui/feed-controls-drawer.tsx), [x-readiness.ts](../src/features/protection/x-readiness.ts) | Code/tests verified; live third-party app behavior not verified |
| Sites and browser check | One checked installed supported browser is enough; stale bits for an uninstalled browser do not claim active blocking. Copy says the action opens the default browser and identifies browser-specific checks. | [adult-site-presentation.ts](../src/features/protection/adult-site-presentation.ts), [adult-site-controls-drawer.tsx](../src/components/ui/adult-site-controls-drawer.tsx), [release-readiness.test.mjs](../tests/release-readiness.test.mjs) | Code/tests verified; no real adult sites visited and native browser interaction not verified |
| App limits and stale rules | Web/non-native routes stop at “Android app required” before Android-only methods. Fresh picker data refreshes labels, while a missing launcher row is shown as “not currently available” without deleting the saved rule; Remove remains behind existing lock checks. | [limits.tsx](../src/app/%28controls%29/limits.tsx), [app-limits-state.ts](../src/features/protection/app-limits-state.ts), [app-rule-presentation.ts](../src/features/protection/app-rule-presentation.ts) | Code/tests verified; native route and restart persistence not verified |
| External recovery | Privacy/open-app actions surface contextual errors instead of leaving rejected promises unhandled. | [privacy.tsx](../src/app/privacy.tsx), [action-error.ts](../src/features/action-error.ts), [release-readiness.test.mjs](../tests/release-readiness.test.mjs) | Code/tests verified; external-handler/OEM behavior not verified |

The status contract deliberately keeps missing fields from an older native build
as `unknown`; they are never coerced to `absent` or `false`. Saved switches,
consent, observation signals, usage timers, and the settings lock are not
changed by availability refreshes or install/uninstall transitions.

## Verification boundary and residual gates

Verified for this source head: the table-driven JS regression suite, TypeScript,
lint, web export, and native Kotlin unit suite must pass before this PR is
reviewed. The native service's enforcement state machines and Settings escape
path were not changed. Native visual interaction, a clean-install journey,
permission revocation/restart, touch/back/font-scaling/contrast checks, and
third-party browser/app-version coverage are not verified here. This document
carries source-check evidence, not a fresh device run. On 2026-09-26 no device
was attached; earlier emulator evidence is dated separately.

Publishing remains unverified. This repository does not establish current Play
Console track/account state. Before publication, the owner still needs a
current signed AAB identity and Play-installed test, merged-manifest/target-API
check, Accessibility declaration and prominent disclosure/affirmative-consent
review including the acceptance/refusal video, Data Safety review, final
screenshots and reviewer-safe video, supported device/OEM matrix, and any
account-specific testing requirement. The applicability of the 12 opted-in
testers for 14 consecutive days is account-specific and unknown.

Publish blockers are the missing signed-candidate/Play-installed evidence,
current target and merged-manifest confirmation, native clean-install and
permission/revocation journey, Accessibility declaration/disclosure/reviewer
material, Data Safety decision, final store assets/video, and any applicable
tester requirement. Non-blocking polish is the broader OEM and third-party
version matrix, additional screen-reader/font-size passes, and future native
visual refinements after the core journeys are proven.

The coordinator supplied these policy references as current on 2026-09-17;
they are gates for the eventual release record, not evidence of Console state:
[target API](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en),
[Accessibility API](https://support.google.com/googleplay/android-developer/answer/10964491?hl=en),
[User Data](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en),
[personal-account testing](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en),
and [core app quality](https://developer.android.com/docs/quality-guidelines/core-app-quality).

Intentionally retained from the full audit scope in this slice: native
visual/OEM/browser-layout verification; exact current YouTube, Instagram, X,
Chrome, Samsung Internet, Opera, and Firefox behavior; accessibility font-size
and screen-reader walkthroughs; Play-installed split/signing review; listing
legal/trademark review; and any broader app-picker or platform-feature changes.
The product still makes no universal feed, webview, browser, or network-level
blocking claim, and this PR adds no tutorial, telemetry, permission, or
settings-lock bypass.

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
      before any public-review, production, or broader-track rollout.
- [x] Save the listing text and app icon as a Console draft. The uploaded icon
      is not stored or hash-correlated in this repository.
- [x] Save the 1024 × 500 feature graphic as a Console draft. Its uploaded file
      is not hash-correlated in this repository.
- [ ] Capture final candidate screenshots. A promo video is optional.
- [x] Use Play Internal testing for the initial AAB upload. This does not
      authorize publication or a production rollout.
- [x] Historical: publish code 7 to the owner-approved one-person Internal testing list.
      Console reports Active and Not reviewed. The optional missing
      deobfuscation-file warning remains; no production release was created.

### Build and signing

- [x] Configure a repeatable fail-closed release build that creates a signed
      `.aab` only when all external upload-key values and its approved
      certificate fingerprint are supplied.
- [x] Save the owner-held recovery archive as the private encrypted Proton Pass
      Dev-vault attachment `Zen Mode - Android upload-key recovery`. The parent
      independently verified its complete SHA-256; parent Mac copies and the VM
      staging directory were removed. EAS retains the managed primary copy.
- [x] Historical code-7 release: confirm the package in the built manifest is
      `com.lab4code.zenmode`. Code 7 replaced code 6 in the active Internal
      release; the Expo 57.0.23 artifact is audited in
      `docs/evidence/android-release-candidate-0.1.5-vc7-20260916.md`. The
      displayed version remains `0.1.5`.
- [x] Inspect the merged release manifest. Confirm the accessibility service is
      present and no unwanted debug, storage, overlay, or broad package-query
      permissions were added.
- [ ] Identify, install from Play, and test the current exact signed candidate;
      record its package, version, source and Play signer rather than assuming code 7. Check that debug-only
      diagnostics are absent; Instagram trace and blocker diagnostics are gated
      by the debug build flag. The earlier code-5 API-35 harness used
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
      [play-data-safety.md](play-data-safety.md). The parent saved **No required
      collection or sharing** using the documented local-processing and
      explicit external/user-initiated-sharing distinction.
- [x] Finalize the owner-approved [privacy policy](../PRIVACY.md) and align the
      in-app privacy screen. The parent saved the public GitHub URL in Console.
      The historical code-6 and code-7 Expo 57.0.23
      artifacts both package `Italy` and are recorded in their respective
      candidate evidence. Current Play-installed candidate testing remains the owner's
      separate work.
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
candidate before any rollout. Use the shortest available values for fast checks:
15 seconds for the Instagram pause, 1 minute for viewing or Home allowances,
and 1 minute for timed visits where available.

### Setup and permission path

1. Use a disposable test profile/device or a separate QA package for a fresh
   install. Do not clear or uninstall the owner's Play app. QA packages do not
   establish Play delivery or upgrade acceptance; use the exact signed candidate
   in a disposable profile for that release gate. Launch the app.
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
- submission of the Accessibility form by the Console operator; the Data
  Safety classification is saved with its source rationale;
- installation from the active Internal release and verification of its
  Play-generated split set, Play App Signing identity, and restricted-settings
  behavior; the app-specific EAS-managed upload key, private Proton Pass backup,
  Max custody decision, and approved upload-key fingerprint are complete;
- supported Android API/device and third-party app-version matrix;
- reviewer setup or test-account instructions, if required;
- exact production-signed candidate correlation for the reported physical-device
  testing, plus candidate-specific screenshots and reviewer video;
- countries and any later release track, public review, or rollout beyond the
  active one-person Internal test; and
- any final legal/trademark review of the YouTube, Instagram, and X references.
