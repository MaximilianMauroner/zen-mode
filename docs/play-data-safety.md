# Zen Mode Data safety worksheet

Prepared for Play app `4973526737842711493` and package
`com.lab4code.zenmode`. This is source material for the parent-managed Console,
not a submitted declaration. Review it against the exact production-signed
candidate before accepting the form.

Candidate correlation: the uploaded artifact is Zen Mode `0.1.5` (version code
`5`), package `com.lab4code.zenmode`, built from packaged source
`2134dc8e4743925751501e1fdaa28f5ac8599d77`. Its AAB SHA-256 is
`150d5504e0af96ccf4534cdeb7a16abeca5fc900ef44f99be52967cf179a4387`.
Google Play accepted and saved it as Internal testing draft release 1 on track
`4700894893507986209`; no rollout was started.

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

## Console decision requiring approval

The owner/Console operator must decide how Google's current form classifies the
optional app-version/platform query sent to GitHub. The conservative option is
to declare the closest available **App info and performance** subtype as
optional collection for app functionality/developer communications and name
GitHub's processing. If the current form and counsel treat this explicit,
user-initiated external navigation as outside collection or within the
user-initiated-transfer exception, record that basis with the submitted form.

Do not answer "no data collected" merely because guard data stays local without
also resolving this GitHub-feedback classification. Do not mark accessibility,
browser, app inventory, rules, or usage as transmitted: the audited source has
no such path.

## Console handoff

- The permission inventory and release-source paths above were correlated to
  the exact uploaded candidate. Runtime network testing remains owner-reserved
  and is not claimed here.
- The public policy URL and in-app disclosure use the same local-processing,
  retention, consent, and optional GitHub-feedback facts.
- Record the exact candidate commit, AAB SHA-256, review date, and final Console
  answers in issue #10 without secrets or private account screenshots.
