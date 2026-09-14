# Google Play readiness

Internal release-preparation checklist for the Android app. This records the
current repository state. It does not publish or send anything externally.

## Current facts

| Item | Evidence and implication |
| --- | --- |
| App name | `Zen Mode` in `app.json`. |
| Android package | `com.maxmauroner.zenmode` in `app.json`; owner must confirm that this is the permanent Play package ID before the first upload. |
| Version | `1.0.3` with Android version code `4` in `app.json`; confirm code `4` exceeds every artifact previously uploaded, then increment it for each later upload. |
| Protection target | Android only. Web checks the interface; iOS does not provide protection. |
| Accessibility service | The native module declares `BIND_ACCESSIBILITY_SERVICE`, can retrieve window content, and receives window/content, click, and scroll events. The service is marked `isAccessibilityTool=false`. |
| Supported feed surfaces | YouTube Shorts; Instagram Reels, Home, Explore, and Direct Messages for setup/provenance; X Home and video viewer. X support is for the Android app package, not the browser. |
| Other app rules | Launchable Android apps can receive daily, timed-visit, or rolling-window rules. Foreground package events are used to charge configured app rules. |
| Package visibility | The module queries YouTube, Instagram, X, and launcher activities. `app.json` blocks unrelated storage and overlay permissions. Verify the merged release manifest. |
| Build state | The generated release variant uses a debug key. Production upload signing and release automation are not configured. |

## Debug preview versus Play upload

`npm run android` installs a local native development build. The standalone
preview documented in `README.md` uses `assembleRelease` but is still signed
with the default debug key and produces an APK for local preview. Neither is a
Play upload artifact.

A Play release needs a signed Android App Bundle (`.aab`) built by the chosen
release pipeline. Configure the release keystore or Play App Signing upload
key, keep its recovery material with the owner, set a unique incrementing
version code, and verify the final package and signature before upload. Do not
upload an APK signed with the debug key.

## Release checklist

### Console and listing

- [ ] Resolve every item in [Unresolved owner inputs](#unresolved-owner-inputs)
      before creating or uploading the Play app.
- [ ] Prepare the required 512 × 512 app icon, 1024 × 500 feature graphic, and
      final screenshots. A promo video is optional.
- [ ] Choose the initial Play testing or release track after the signed build
      and reviewer path are ready.

### Build and signing

- [ ] Configure a repeatable release build that creates a signed `.aab`.
- [ ] Decide who owns the keystore and Play upload key; back up recovery
      material securely.
- [ ] Set the release version code and version name. Confirm the package in the
      built manifest is `com.maxmauroner.zenmode`.
- [ ] Inspect the merged release manifest. Confirm the accessibility service is
      present and no unwanted debug, storage, overlay, or broad package-query
      permissions were added.
- [ ] Install and test the exact signed candidate. Check that debug-only
      diagnostics are absent; Instagram trace and blocker diagnostics are gated
      by the debug build flag.
- [ ] Verify the AAB signature and contents with the release toolchain. Keep the
      debug APK and preview APK out of Play uploads.

### Accessibility, privacy, and review declarations

- [ ] Validate the native consent gate on a device upgrade with the service
      already enabled. The service now requires current native consent before
      event, timer, usage, overlay, or navigation processing; unit coverage is
      present, but the real upgrade journey is still required.
- [ ] When the accessibility disclosure changes, bump both
      `CONSENT_VERSION` in `src/features/protection/setup-policy.ts` and
      `CURRENT_VERSION` in the native `ConsentPolicy.kt` in the same release.
      A one-sided bump can either repeat setup unnecessarily or let the service
      accept an outdated disclosure.
- [ ] Complete Play Console's current Accessibility API declaration for the
      service. Explain that the core function is applying user-selected app and
      feed rules.
- [ ] Review the in-app prominent disclosure and consent screen against the
      final behavior. The current copy covers accessibility screen-content
      processing for YouTube, Instagram, and X, foreground tracking for app
      limits, and the launchable-app list used when choosing a rule. It also
      says screen contents and installed-app inventory are not sent off this
      device. The owner must approve that wording and keep it aligned with the
      shipped behavior.
- [ ] Complete the current Data safety form and privacy policy review. The
      native implementation has no server client and describes local handling,
      but the owner must decide how accessibility data, screen content, and
      foreground package metadata are represented under the current forms.
- [ ] Review and approve the [privacy policy draft](privacy-policy-draft.md),
      replace every owner placeholder, and publish it at the approved public
      URL before using that URL in the listing.
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

5. On Feeds, confirm exactly three grouped controls: YouTube, Instagram, and X.
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
10. Lock settings for one day. Confirm tightening a rule still works, while
    disabling a rule, removing an app rule, or increasing an allowance is
    refused. Request unlock and confirm the UI says it cannot open before the
    cooling-off period and lock expiry.
11. Disable the Accessibility service or pause protection in Android settings.
    Confirm saved rules remain visible but no longer enforce. Re-enable access
    and protection, then repeat one feed check.

### Capture procedure

Record a short, reviewer-safe walkthrough from the signed candidate:

1. Start on the setup disclosure, then show Android Accessibility access and
   the running status after returning.
2. Show the Feeds page with the three grouped rows. Open the Instagram drawer
   to show Reels, Home, and Explore, then show the App limits and Lock tabs.
3. Capture one real enforcement result, such as the Instagram Explore blocker
   or the YouTube one-Short boundary. Include the user action that caused it.
4. End on the saved rule/status screen. Keep personal messages, usernames,
   notifications, account data, and unrelated app content out of frame.

Do not record a fabricated state or claim that a web preview enforces Android
rules. Label any debug build as an internal preview; use the signed candidate
for final store media.

## Unresolved owner inputs

These items cannot be completed from the repository:

- permanent package ID and Play developer account identity;
- support URL and public privacy policy URL (support email is
  `lab4code.dev@gmail.com`);
- Accessibility API and Data safety declarations approved by the owner;
- release keystore, Play App Signing/upload-key ownership, and build pipeline;
- supported Android API/device and third-party app-version matrix;
- reviewer setup or test-account instructions, if required;
- category, countries, pricing, content rating, target audience, and launch
  track; and
- final screenshots, feature graphic, promo video, and any legal/trademark
  review of the YouTube, Instagram, and X references.
