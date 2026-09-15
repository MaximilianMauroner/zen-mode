# Zen Mode privacy policy

Effective date: September 15, 2026

Zen Mode is provided by Maximilian Mauroner (Lab4Code), Austria. This policy
explains how the Android app processes information. Questions or privacy
requests can be sent to [support@lab4code.com](mailto:support@lab4code.com).

## Scope

Zen Mode lets people aged 13 and older create screen-time, feed, app, and
website rules on their own Android device. It does not require or provide a Zen
Mode account. It has no advertising and does not sell personal information.

Zen Mode uses an Android Accessibility service only after the user accepts the
in-app disclosure and enables the service in Android Settings. Android always
allows the user to return to Accessibility Settings and disable the service.

## Information processed on the device

When the Accessibility service and protection are enabled, Zen Mode processes
the following information locally:

- accessibility screen data exposed by YouTube, Instagram, and X, including
  view identifiers, selected state, and limited visible text or descriptions,
  to recognize supported feed surfaces;
- the current address exposed by exact, known address-bar fields in supported
  browsers while website blocking is enabled, to compare its hostname with
  local website rules;
- foreground-app events and app identifiers to apply user-selected whole-app
  limits;
- the list of launchable apps, including package names and labels, while the
  user chooses an app for a rule; and
- the rules and local state described below.

Zen Mode does not take screenshots, record typing, inspect browser page
content, or save accessibility screen text, visited addresses, or the complete
launchable-app list. Accessibility screen data, browser addresses, and the app
inventory are not sent to Lab4Code or another server.

## Information stored on the device

Zen Mode stores the following in Android app-private storage:

- feed, app-limit, timed-visit, rolling-window, and website rules;
- selected app package names and user-added blocked hostnames;
- consent, setup, protection, observation, browser-readiness, and settings-lock
  state;
- daily and rolling usage totals, timed-visit end times, and rolling timestamp
  slices; and
- privacy-reduced detection summaries such as timestamps, counters, fixed
  surface/reason values, and observed-signal flags.

The settings lock and setup completion are stored using Expo SecureStore.
Other guard state is stored in Android SharedPreferences. Active enforcement
state also exists in memory while the Accessibility service runs.

## Optional GitHub feedback

If the user selects **Send feedback**, Zen Mode asks Android to open an editable
issue draft on GitHub. The draft URL contains the Zen Mode version and platform
(for example, Android), so GitHub receives those details when the draft opens.
Nothing is submitted automatically. If the user submits the issue, the issue
and any text they add are public. GitHub processes this information under the
[GitHub Privacy Statement](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement).

Do not include personal or sensitive information in a public feedback issue.
Feedback can instead be sent privately to support@lab4code.com.

## Use and sharing

Zen Mode uses locally processed information to recognize supported surfaces,
apply the user's rules, show usage and protection status, complete setup, and
enforce the settings lock. Lab4Code does not receive accessibility content,
browser addresses, installed-app inventory, rules, or usage records from the
app.

The audited app does not include analytics, advertising, an app-owned backend,
or an upload path for guard data. Its Android package includes framework-level
internet and network-state permissions, but Zen Mode's release code does not
use them to transmit guard data. The optional GitHub feedback action is the
external transmission described above.

## Retention and deletion

Rules and configuration remain until the user changes them, clears Zen Mode's
storage, or uninstalls the app. More specific behavior is:

- daily usage and stop/continue counts apply only to the recorded local day;
  fields from an older day can remain in private storage until later writes or
  deletion, but are not counted as current-day usage;
- rolling usage slices are pruned as they leave the configured rolling window
  and are capped by the app; removing a rolling rule removes its saved slices;
- removing a timed-visit rule removes its saved visit-end time;
- removing a daily-limit rule does not currently remove that app's saved usage
  field, although it no longer enforces without a rule; and
- detection summaries and other settings remain until overwritten, app storage
  is cleared, or the app is uninstalled.

Zen Mode does not currently have a single in-app erase button. Android's
**Clear storage** control or uninstalling Zen Mode removes its app-private
data. The app opts out of Android cloud backup. Device-to-device transfer
behavior can still depend on Android and the device manufacturer.

## User choices

Users can leave consent unchecked, pause protection, change or remove rules
subject to an active settings lock, disable the Accessibility service at any
time in Android Settings, clear app storage, or uninstall Zen Mode. Disabling
the Accessibility service stops accessibility processing and enforcement while
saved local rules remain until deleted.

## Security

Zen Mode limits its processing to the functionality described above and stores
guard state in Android app-private storage. No method of storage is guaranteed
to be perfectly secure. Users should keep Android and their device security
updates current and should not put private information in public GitHub issues.

## Children

Zen Mode is intended for people aged 13 and older and is not directed to
children under 13. The app does not create user profiles or knowingly collect
personal information from children through an app-owned service. A parent or
guardian who has a concern can contact support@lab4code.com.

## Changes and contact

Material changes will be reflected in this public document with a new effective
date. For privacy questions, deletion requests concerning direct support
communications, or other requests, contact:

Maximilian Mauroner (Lab4Code)  
Austria  
[support@lab4code.com](mailto:support@lab4code.com)
