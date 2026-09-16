# Zen Mode Data safety worksheet

Prepared for Play app `4973526737842711493` and package
`com.lab4code.zenmode`. This records the source audit and the parent-managed
Console draft; it does not authorize a rollout.

Console correlation: the sole bundle currently saved in Internal draft release
1 is Zen Mode `0.1.5` (version code `6`), package
`com.lab4code.zenmode`, built from packaged source
`25547c880f022c59b298aa3e8f621162387b2c80`, has AAB SHA-256
`6e85ec913c7e36c0be762667f8039488b8a969f6cc688c45904799084cad2cff`,
and packages Italy. Google Play accepted and saved it at 08:02 UTC on
2026-09-16 on track `4700894893507986209`, retaining the original release
notes; no rollout was started. Code 7 replaces it to incorporate Expo 57.0.23.
The runtime/data behavior relevant to this worksheet is unchanged; exact
replacement-candidate device evidence remains pending.

## Release audit facts

- Zen Mode has no account, advertising SDK, AD_ID permission, analytics SDK,
  app-owned backend, crash-report upload, cloud sync, or in-app purchase flow.
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
- Selecting **Send feedback** opens an external GitHub issue draft whose URL
  contains the app version and platform. GitHub receives those two fields when
  the draft opens. Nothing is submitted automatically; a submitted issue and
  user-entered content are public.
- Selecting the privacy-policy link opens the public policy on GitHub. Selecting
  the browser-readiness check opens `https://example.com` in an external
  browser. Those are user-directed external navigations; the external handler,
  not a Zen Mode backend, makes the resulting request. The latter carries no
  Zen Mode data in its URL.
- The audited release source has no app-owned API client, analytics, or
  telemetry uploader. Launching YouTube, Instagram, or X hands control to the
  selected third-party app without uploading guard data.
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
| User-generated content | Optional text entered on GitHub after opening an external draft | User-directed public GitHub submission; not automatically collected by Zen Mode |
| App info and performance | App version and platform in the optional GitHub draft URL | Optional external transmission to GitHub; see decision below |
| Device or other IDs | No advertising ID, hardware ID, Android ID, or generated user ID is read by app code | Not collected or shared |
| Location, personal information, financial information, health and fitness, messages, photos/videos, audio, files/documents, calendar, contacts | No corresponding app behavior or permission | Not collected or shared |

## Console classification saved

The parent saved **No required collection or sharing** in Console using the
current Google guidance distinction for explicit external/user-initiated
sharing. The audited guard data is processed locally. The optional feedback
action opens an external GitHub draft containing only app version and platform;
those fields are not diagnostics, performance data, or an identifier, and
nothing is submitted automatically. Accessibility data, browser addresses, app
inventory, rules, and usage are not transmitted.

This records the exact source facts and saved Console rationale. Re-evaluate the
answer if Google changes the form guidance or the app later adds any automatic
network transfer, analytics, diagnostics, identifier, or backend path.

## Console handoff

- The permission inventory and release-source paths above were correlated to
  the exact uploaded candidate. Runtime network testing remains owner-reserved
  and is not claimed here.
- The public policy URL and in-app disclosure use the same local-processing,
  retention, consent, and optional GitHub-feedback facts.
- The parent saved the public policy URL and the classification above in
  Console. No rollout was started.
