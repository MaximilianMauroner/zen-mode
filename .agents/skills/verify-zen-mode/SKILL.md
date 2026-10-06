---
name: verify-zen-mode
description: Verify Zen Mode focus-rule setup and Android enforcement. Use when checking changed rules or settings lock on an owned test device.
---

# Verify Zen Mode

Draft: native journeys require an Android SDK and an owned emulator or test device.
Read AGENTS.md, README.md, SDK 57 docs and [features.md](features.md).
Use an isolated worktree and separate device profile. Do not use a daily phone.
Record revision, device/API level, installed build, starting permissions and rules.
Check active work, host memory, load and device ownership before commands.
Select only journeys affected by work in the requested audit window.

Install with `npm ci`. Supporting checks are `npm test`, `npx tsc --noEmit`,
`npm run lint` and `npx expo export --platform web`. Native edits additionally
require `npm run test:native`. These checks do not prove accessibility behavior.
With the Android SDK configured, `npm run android` installs the native build.
Confirm the app opens before enabling its service in Android Accessibility.
Do not use release/internal upload commands. Expo Go and web cannot enforce rules.

Run feature rows in order. Use a disposable app/profile and short rule durations.
Observe setup's signal before asserting readiness. Unknown signals must not
trigger enforcement. Record action, visible outcome and overlay/Home effect.
Reset owned rules between rows. If enforcement surprises you, recheck the service,
app build and observed signal, then reset before one targeted retry.

For web interface checks, use `npm run web -- --port 43132`, confirm HTTP readiness,
then T3 preview status/open/navigation. A preview navigation failure blocks browser
proof; it does not permit replacing Android enforcement with web evidence.

After success or failure, disable the owned accessibility service, restore owned
rules and permissions, and uninstall only this run's disposable installation.
Stop owned Metro/build processes and confirm the port closes. Preserve redacted
captures outside device state. Never reset a shared device. Report blocked/unrun
native and browser rows separately from command results.
