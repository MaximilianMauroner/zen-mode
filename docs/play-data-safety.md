# Zen Mode Data safety worksheet

Prepared for Play app `4973526737842711493` and package
`com.lab4code.zenmode`. This records the source audit and the parent-managed
Console state; it does not authorize any broader rollout.

Historical Console correlation: Internal release 1 contained Zen Mode `0.1.5`
(version code `7`), package `com.lab4code.zenmode`, built from source
`45be98daf88685f9ac6f694304cb4ef1c75fbe6a`. Its AAB SHA-256 is
`08bb1d84f4d92519a06c1796c9748e64f5f9a9384582b94790f6b84a0c49dde7`
and it packages Italy. At 08:54 UTC on 2026-09-16 Console reported the release
Active, available to the approved one-person Internal list, and Not reviewed on
track `4700894893507986209`. There is no production release. The runtime/data
behavior relevant to this worksheet is unchanged; exact Play-installed device
evidence remains pending.

## Release audit facts

- Zen Mode has no account, advertising SDK, AD_ID permission, analytics SDK,
  crash-report upload, cloud sync, or in-app purchase flow.
- Release builds have **Report a problem or idea**. When the user taps Send
  report, the app posts one report to the Lab4Code reports inbox at
  `tools.mauroner.net`. It holds the kind, the typed text, an optional email
  for a reply, the app version and build, and the OS name and version. It
  never holds guard data, settings, or an identifier.
- Accessibility screen data from YouTube, Instagram, and X is processed in the
  accessibility-service process and is not persisted as screen text or sent
  off-device.
- Known browser address-bar values are read only while website blocking is on.
  Hostnames are matched locally; visited addresses are not persisted or sent.
- Foreground package names and durations, user-selected rules, usage slices,
  consent, observation/setup status, browser readiness, and privacy-reduced
  detection summaries are stored in app-private storage.
- The app queries launchable apps only when presenting the app picker. It does
  not persist or transmit the complete inventory.
- Android backup is disabled. Clearing app storage or uninstalling normally
  deletes the app-private records; manufacturer-controlled device transfer can
  vary.
- Selecting the privacy-policy link opens the public policy on GitHub. Selecting
  the browser-readiness check opens `https://example.com` in an external
  browser. Those are user-directed external navigations; the external handler,
  not a Zen Mode backend, makes the resulting request. The latter carries no
  Zen Mode data in its URL.
- Apart from the problem report, the release source has no app-owned API
  client, analytics, or telemetry uploader. Launching YouTube, Instagram, or X
  hands control to the selected third-party app without uploading guard data.
- The merged release manifest currently contains `INTERNET`,
  `ACCESS_NETWORK_STATE`, `VIBRATE`, `USE_BIOMETRIC`, and `USE_FINGERPRINT`
  through the Expo/framework dependency set. It does not contain AD_ID,
  location, camera, microphone, contacts, media/storage, or notification
  permissions.

## Proposed category answers

For the guard itself, the following are processed only on-device and therefore
are not "collected" under Play's off-device transmission definition:

| Play category | Actual behavior | Proposed form treatment |
| --- | --- | --- |
| App activity / installed apps | Foreground package names, local usage, and a launchable-app picker | Not collected or shared; on-device only |
| Web browsing | Current known address-bar value while blocking is enabled | Not collected or shared; on-device only and not saved |
| User-generated content and email address | Optional problem report text and reply email sent to the Lab4Code inbox | Collected, optional, user-initiated, not shared; purpose app functionality and developer communications. Max must confirm the Console answer |
| App info and performance | App version, build, and Android version in the optional problem report | Collected with the report, optional, user-initiated, not shared. Max must confirm the Console answer |
| Device or other IDs | No advertising ID, hardware ID, Android ID, or generated user ID is read by app code | Not collected or shared |
| Location, personal information, financial information, health and fitness, messages, photos/videos, audio, files/documents, calendar, contacts | No corresponding app behavior or permission | Not collected or shared |

## Console classification saved

The parent saved **No required collection or sharing** in Console using the
current Google guidance distinction for explicit external/user-initiated
sharing. That answer predates the problem report and must be updated before a
release build with the report screen reaches testers. The audited guard data is
processed locally. Accessibility data, browser addresses, app
inventory, rules, and usage are not transmitted.

This records the exact source facts and saved Console rationale. Re-evaluate the
answer if Google changes the form guidance or the app later adds any automatic
network transfer, analytics, diagnostics, identifier, or backend path.

The problem report is an app-owned backend path. The saved answer of no
collection does not cover it. Update the Console answer before a release build
with the report screen reaches testers.

## Console handoff

- The permission inventory and release-source paths above were correlated to
  the exact uploaded candidate. Runtime network testing remains owner-reserved
  and is not claimed here.
- The public policy URL and in-app disclosure use the same local-processing,
  retention, consent, and optional problem-report facts.
- The parent saved the public policy URL and the classification above in
  Console. Only the approved one-person Internal test is active; no production
  rollout was started.
