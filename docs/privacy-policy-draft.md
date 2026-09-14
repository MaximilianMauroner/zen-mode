# Zen Mode privacy policy draft

**Draft only. Do not publish this file as the final privacy policy.** It is a
code-audited starting point for the Android release. The owner or legal advisor
must fill the marked fields, confirm the final build and SDK behavior, and
approve the wording before publication.

## Owner fields

- Developer or legal entity: `[OWNER INPUT]`
- Privacy policy effective date: `[OWNER INPUT]`
- Privacy contact and support email: `lab4code.dev@gmail.com`
- Public support URL: `[OWNER INPUT]`
- Applicable jurisdiction and controller details: `[OWNER INPUT]`

## Scope

Zen Mode is an Android app for applying user-selected screen-time and feed
rules. It uses an Android Accessibility service when the user enables the
service in Android Settings and accepts the in-app disclosure. The app has no
account sign-in or app-owned server in the audited source.

## Information processed

When the Accessibility service is enabled, Zen Mode processes:

- Accessibility screen data from YouTube, Instagram, and X to recognize
  supported surfaces and apply feed rules. The detectors use view identifiers,
  selected state, and limited visible text or descriptions while classifying a
  screen. The app does not take screenshots or store screen text as an app
  record.
- Foreground app events to charge time to a configured app rule. These events
  identify which app is in front and how long it remains there. Zen Mode does
  not use screen content from other apps for this purpose.
- The launchable-app inventory, including package names and labels, when the
  user opens the app picker and chooses an app rule.
- User-selected rules and local state: feed settings, protection and
  observation state, daily budgets, timed-visit rules and visit-end times,
  rolling allowances and timestamped usage slices, setup completion, and the
  settings lock.
- Local usage and detection summaries: same-day app usage, rolling-window use,
  feed detection timestamps, counts, class or reason, observed signals, and
  same-day stop and continue counts.

The current in-app disclosure says that screen contents and installed-app
inventory are not sent off the device, and that settings, local usage, and
detection summaries are stored instead of screen text. The owner must confirm
that this remains true for the final binary and all included SDKs.

## How the information is used

Zen Mode uses this information only to:

- identify supported YouTube, Instagram, and X surfaces;
- apply the feed and app rules chosen by the user;
- show rule status, usage summaries, and detection state in the app; and
- complete setup, enforce the settings lock, and remember the user's choices.

The audited app and native module contain no app-owned HTTP or WebSocket client,
server endpoint, analytics call, or upload path. Android Settings and the
installed third-party apps are opened through Android intents when the user or
the guard requests it. The owner must review bundled Expo and other third-party
SDKs before making a final no-network or no-sharing statement.

## Sharing

No app-owned service receives the information listed above in the audited
source, and the source has no sale or sharing path. The owner must confirm the
business practice, final SDK list, Android platform behavior, and any Play
Console declarations before publishing this section.

## Local storage and retention

The app stores state locally in Android app-private storage. Expo SecureStore
holds setup completion and the settings lock. Android SharedPreferences holds
guard settings, app rules, usage, detection summaries, and daily tally data.
Active visit and enforcement state also exists in memory while the service is
running.

The code does not define one universal retention period or a global erase job:

- daily usage and stop/continue counters are associated with the local day;
  reads for a new day return zero, but stale day fields can remain until they
  are overwritten;
- rolling usage is stored as timestamped duration slices and pruned during
  writes when slices are older than the supported window; and
- debug builds can write privacy-safe diagnostic metadata, such as resource
  identifiers, event types, surface, reason, counts, and rule state, to Android
  logs. The release build gates this diagnostic trace behind the debug build
  flag. Android log retention is controlled by the platform and device.

The owner must set and publish the actual retention policy after reviewing the
final SDKs and release manifest. Zen Mode configures Android backup off, so app
data is not included in Android cloud backup or device-to-device transfer.

## User controls and deletion

Users can:

- leave the setup consent unchecked, or disable the Accessibility service in
  Android Settings;
- pause protection in Zen Mode while keeping its saved rules;
- remove an individual app rule; and
- allow a settings lock to expire or manage its unlock request.

Removing a daily rule removes its rule definition but does not currently clear
the associated daily usage record. Removing a rolling rule clears its stored
rolling usage, and removing a timed-visit rule clears its stored visit-end
record. The app currently has no single in-app control to erase all local data.

Uninstalling the app normally removes its app-private data. Zen Mode configures
Android backup off, so this data is not restored from Android cloud backup or
device-to-device transfer. This setting must be verified in the final merged
release manifest.

## Security

Zen Mode keeps the state described above in local app-private storage and does
not upload screen content in the audited app/native code. The owner must add
the final security contact and review platform, SDK, backup, and release
signing settings before publication.

## Contact and changes

For privacy questions or requests, contact `lab4code.dev@gmail.com`. For policy
updates, the owner should publish the new effective date and explain the change
at the public privacy policy URL.

## Internal audit notes

These notes support review and should be removed or rewritten for a public
policy:

- Source reviewed: `src/app/setup.tsx`, `src/features/protection/setup.ts`,
  `src/features/protection/lock.ts`, `modules/zen-guard/android/src/main/`,
  and `app.json`.
- The Accessibility service reads screen trees only for the supported feed
  packages. Other-app handling uses foreground package events for configured
  app rules.
- The owner still needs an SDK inventory, final merged-manifest review,
  retention decision, legal identity, support URL, and public policy URL.
