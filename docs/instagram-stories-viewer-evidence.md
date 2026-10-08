# Instagram Stories viewer: evidence gate and handoff

Issue [#34](https://github.com/MaximilianMauroner/zen-mode/issues/34) is blocked
at the viewer-evidence gate. This preparation does not implement Stories
detection, enforcement, or its settings toggle. No entry path is supported yet.
The issue must stay open.

The [Microplan](https://tools.mauroner.net/artifacts/9iqfliPdw9AwS4pDX0udSiKqqPQdgISJ)
requires a viewer-specific signal proved against captured positive and negative
fixtures. The current source proves only that `reel_viewer_front_avatar` and
`clips_media_component` can occur in embedded DM media. Those IDs cannot establish
a full-screen Story viewer, separately or together. Labels, usernames, and avatar
rings cannot establish one either.

## Evidence available on 8 October 2026

- The issue has no comments or attached viewer captures. The supplied Microplan
  has no positive Story fixture or verified close/back target.
- The repository has negative shared-media unit inputs in
  `InstagramDetectorTest.kt`, but no captured Story-viewer fixture. Its tests
  that name Instagram 445 cover other surfaces. That version is not evidence
  of Stories support or of the current Instagram version.
- T3 `device_list` returned: `Agent device access is turned off for this environment.`
  No device was opened, connected, activated, or inspected. No exact Instagram,
  Android, device, or locale version was obtained.
- Local tools are present: Java 17.0.20.1, Android SDK platform 36, build tools
  35.0.0 and 36.0.0, and ADB 1.0.41 / 37.0.1-15733141. ADB was used only for
  its version. `ANDROID_HOME` and `ANDROID_SDK_ROOT` were unset at intake; the
  SDK is at `/home/codex/android-sdk`. A command-local environment variable is
  sufficient for local unit tests. No runtime setting was changed.
- The worktree started without `node_modules/` or generated `android/`.
  The existing project dependency installation has the same `package-lock.json`.
  Local package links reuse that installation without changing it.
- This host identifies as `coding-vm`. Heavy commands use the build skill's
  shared `fleet-build.service`, with a 3 GiB group memory cap, zero build swap,
  and verified effective cgroup controls. Another project's build occupied the
  slot at intake; preparation continued until it finished. No process belonging
  to another owner was stopped.

## Source-supported preparation

The added tests use constructed negative unit inputs with resource IDs already
present in the checked-in detector tests. They are not device-captured fixtures,
and do not claim to reproduce a Story entry path.

- An avatar plus an embedded media component, without a known host, remains
  `UNKNOWN` with the fixed reason `unknown`.
- Story labels and the shared avatar do not override Home, Reels, or Explore.
- The avatar does not resolve a conflicting DM/Reels input.
- Repeated ambiguous shared-media inputs produce no policy action, blocker, or
  Home usage charge.
- Repeated embedded DM media inputs do not start a Reels pause or viewing window,
  including when scroll events are present.

Production code, the service and bridge contracts, saved settings, settings lock,
and statistics are unchanged. Stories tray hiding (#32), Reels policy, and
#64/#65 delivery work are outside this preparation.

## Capture matrix for the next authorized device session

Every row is **unverified and unsupported for Stories enforcement**. Versions
and captures are missing for every row. Add support only for paths that pass
the evidence gate; record unsupported paths explicitly.

| Entry or surface | Required evidence |
| --- | --- |
| Home tray to another account's Story | Viewer tree, transition trees, close target, exit result |
| Profile avatar to a Story | Viewer tree, profile negative tree, exit result |
| Notification or deep link | Opening and transition trees, viewer tree, exit destination |
| Highlight | Viewer tree and exit result; leave unsupported if distinct |
| Archive or own Story | Viewer controls and exit result; leave unsupported if distinct |
| Story reply from or to DMs | Viewer versus message composer trees, reply destination |
| Embedded or shared media in DMs | Negative trees, scrolling and full-screen media transitions |
| Home posts, profiles, Reels, Explore | Preserved-surface trees and video with Stories rule alone enabled |
| Unknown or conflicting layout | No navigation, no overlay, no successful-intervention count |

Capture on an owned test device only after device use is authorized. Record
exact Instagram version name/code, Android version/API, device model, locale,
Zen Mode build, entry path, and service/protection/observation/rule state with
each evidence set. Capture the before, entry, confirmed viewer, transition, and
after states. Include hierarchy, visible state, and action support needed to
verify a close/back node. Sanitize usernames, messages, captions, media, and
other personal content before putting evidence in this repository. Do not
persist that content in detector reasons or production logs.

## Implementation after the evidence gate

1. Add a distinct `STORY_VIEWER` surface from proved signals. Require
   corroboration and return `UNKNOWN` for conflicts. Do not infer Stories from
   `reel_viewer_*`, labels, Reels, or a past observation alone.
2. Add an entry-based policy separate from Reels provenance and timers. Prefer
   the verified Instagram close/back target. Use a blocker with Leave only
   while the current viewer remains confirmed. Clear it on unknown/conflicting
   trees, known non-Story surfaces, app exit, pause, and service loss. Verify
   success before recording it, and allow at most one success per viewer entry.
   Failed navigation must not count as success.
3. Add an independent Stories-viewer preference and native bridge method,
   leaving existing settings calls intact. Keep it separate from #32 and
   Reels. Report support and observation honestly. Refuse activation until
   the viewer is supported and observed. Disabling the rule must use the
   existing weakening challenge and native settings-lock checks. Recommended
   defaults must not silently activate it.
4. Test pause, consent, service loss/restart, observation, toggle, settings lock,
   bridge status/setters, unavailable/read-failure presentation, transient
   windows, screen lock, repeated events, failed actions, and later viewer
   entries. Add captured-fixture tests and device videos for every supported
   path and preserved surface. Check app-version changes before claiming support.

## Validation and remaining acceptance

Local checks passed on 8 October 2026:

- `npm test`: 147 passed.
- `npx tsc --noEmit` and `npm run lint`: passed.
- `npx expo export --platform web --max-workers 1`: passed; 12 routes exported
  and `dist/index.html` verified.
- `npm run test:native`: 180 debug and 180 release tests passed, with no failures,
  errors, or skips. Both variants include the five new boundary tests.

Heavy commands ran through the build skill helper. Native tests used the local
SDK through command-local `ANDROID_HOME`, Gradle `--no-daemon --max-workers=1
--no-parallel`, a 768 MiB JVM heap, 512 MiB metaspace, Kotlin compilation in
process, and the build skill's native job limit. The native run exited 0; its
sampled peak group memory was 2,880,667,648 bytes and build swap stayed at zero.
The owned Java processes exited after the guarded command finished. Gradle
and existing dependency/source deprecation warnings are outside this change.

Local checks and independent code review cannot prove Instagram accessibility
behavior. No nesting or branching checker is configured in this repository;
the existing ESLint configuration applies to TypeScript, not Kotlin tests.

`npm run android` and real-device acceptance are blocked by disabled device
access and the task's restriction on activation. No install, release, upload,
paid hosted EAS build, or production change is authorized in this task.
The feature, toggle, lock/bridge/presentation coverage for the new rule, supported
paths, exact app/device versions, and preserved-surface videos remain missing.

**Owner and next action:** Max or the test-device owner must provide authorized,
sanitized captures and action results from the matrix. The implementation owner
can then prove the detector and complete the remaining slices. Existing
#64/#65 delivery work cannot replace this evidence.
