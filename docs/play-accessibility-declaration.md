# Zen Mode Accessibility API declaration source

Prepared for Play app `4973526737842711493`, package
`com.lab4code.zenmode`. This text is reviewer material, not a declaration that
has been submitted or accepted by Google Play.

Console correlation: Internal release 1 now contains Zen Mode `0.1.5` (version
code `7`), built from source
`45be98daf88685f9ac6f694304cb4ef1c75fbe6a`. Its AAB SHA-256 is
`08bb1d84f4d92519a06c1796c9748e64f5f9a9384582b94790f6b84a0c49dde7`
and it packages Italy. At 08:54 UTC on 2026-09-16 Console reported the release
Active, available to the approved one-person Internal list, and Not reviewed on
track `4700894893507986209`. There is no production release. Exact
Play-installed device/reviewer evidence and Accessibility review remain pending.

## Is this an accessibility tool?

No. Zen Mode declares `android:isAccessibilityTool="false"`. It is a
user-configured focus and screen-time tool, not a disability-support service.

## Core purpose and use of AccessibilityService

Zen Mode uses Android AccessibilityService after prominent in-app disclosure,
affirmative consent, and the user's separate approval in Android Settings. The
uploaded candidate records disclosure/consent version `3`; its native service
refuses protection and event, timer, usage, overlay, and navigation processing
without current consent. The service enables these user-selected features:

- recognize supported YouTube Shorts, Instagram Reels/Home/Explore, and X
  Home/video surfaces and apply the configured feed boundary;
- read the current address only from exact known browser address-bar nodes
  while website blocking is enabled and close a locally matched hostname;
- identify the foreground app and elapsed foreground time for daily,
  timed-visit, and rolling app rules; and
- display an accessibility overlay when a configured boundary needs a user
  choice or explanation.

The service receives window state/content, click, and scroll events and requests
view IDs plus interactive windows. It does not take screenshots, record typing,
remotely control the device, bypass Android security, or use AccessibilityService
to install software, change security settings, or prevent the user disabling
the service.

## Data handling

Accessibility screen data and browser addresses are processed locally and are
not sent off-device or persisted as screen text/visited URLs. The app stores
rules, selected package names, local usage/session values, consent and setup
state, and privacy-reduced detection summaries in app-private storage. The full
launchable-app inventory is neither saved nor transmitted.

The separate optional GitHub feedback action sends only the app version and
platform in an external issue-draft URL. It is not fed by AccessibilityService.

## User control and safety

- Protection does not start until current consent is recorded and the user
  enables the service in Android Settings.
- Feed enforcement also waits for required observation signals; unknown or
  ambiguous supported-app surfaces fail open.
- The in-app settings lock restricts weakening Zen Mode rules inside the app.
  It does not lock Android Settings.
- Android Settings is excluded from selectable app rules and is natively exempt
  from daily, timed-visit, rolling, overlay, delayed-navigation, Home, and Back
  enforcement paths, including stale saved rules.
- The user can always open Accessibility Settings and disable the service.

## Reviewer steps

1. Install the exact candidate and open Zen Mode. No account or credentials are
   required.
2. Read the setup disclosure, select the affirmative consent checkbox, and tap
   **Turn on the guard**.
3. In Android Accessibility Settings, open **Zen Mode protection**, review the
   system warning, and enable the service.
4. Return to Zen Mode and confirm the guard reports Android access and
   protection as running.
5. Open the YouTube, Instagram, and X drawers. Feed rules remain in observation
   until their required real-app signals have been observed.
6. Open **App limits**, add a launchable non-system app, and configure one of
   the three rule types. Opening that app demonstrates the corresponding local
   timer/boundary.
7. Open Zen Mode's **Lock** screen and create a settings lock. Confirm weakening
   an in-app rule is refused.
8. From Zen Mode, open Android Accessibility Settings. Confirm Settings remains
   usable despite the in-app lock, then disable **Zen Mode protection**.
9. Return to Zen Mode and confirm Android access is no longer granted and rules
   do not enforce.

Final submission must attach the production-candidate video and exact candidate
identity. The current synthetic demonstration is preparation evidence only if
it is not built from that exact signed candidate.
