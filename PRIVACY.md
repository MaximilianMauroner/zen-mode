# Zen Mode privacy policy

Effective date: November 7, 2026

Zen Mode is provided by Maximilian Mauroner (Lab4Code), Italy. This policy
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

## Optional problem reports

If the user opens **Report a problem or idea** and taps **Send report**, Zen
Mode sends one report to the Lab4Code reports inbox at tools.mauroner.net. The
report contains only:

- whether it is a problem or an idea;
- the text the user types, and the optional steps or reasons they add;
- an email address, only if the user enters one for a reply; and
- the Zen Mode version and build number, and the operating system name and
  version (for example, Android 15).

A report never contains rules, blocked apps or websites, usage records,
settings, accessibility data, browser addresses, or a device or advertising
identifier. Zen Mode sends a report only when the user taps Send report. It
does not need an account or a token. Like any website, the server can receive
ordinary connection data, such as the IP address, when the report arrives.
Lab4Code uses reports only to fix problems, consider ideas, and reply when an
email is given. Requests to delete a report can be sent to
support@lab4code.com. Only release builds can send reports.

## Use and sharing

Zen Mode uses locally processed information to recognize supported surfaces,
apply the user's rules, show usage and protection status, complete setup, and
enforce the settings lock. Lab4Code does not receive accessibility content,
browser addresses, installed-app inventory, rules, or usage records from the
app.

The app does not include analytics, advertising, or an upload path for guard
data. Its only app-owned network request is the optional problem report
described above. The problem report is the only external transmission.

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
updates current.

## Children

Zen Mode is intended for people aged 13 and older and is not directed to
children under 13. The app does not create user profiles or knowingly collect
personal information from children through an app-owned service. A parent or
guardian who has a concern can contact support@lab4code.com.

## Changes and contact

Material changes will be reflected in this public document with a new effective
date. For privacy questions, deletion requests concerning direct support
communications, or other requests, contact:

Maximilian Mauroner (Lab4Code)<br>
Italy<br>
[support@lab4code.com](mailto:support@lab4code.com)
