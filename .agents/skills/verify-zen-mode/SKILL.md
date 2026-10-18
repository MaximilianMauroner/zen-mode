---
name: verify-zen-mode
description: Audit Zen Mode focus-rule setup and Android enforcement on an owned test device or emulator. Use only for an explicit device audit request.
---

# Verify Zen Mode

Use this skill only for an explicit Android device audit request.
For ordinary code changes, follow [code validation](../../../AGENTS.md#validation).

Use an owned Android emulator or test device.
Read AGENTS.md, README.md, SDK 57 docs and [features.md](features.md).
Use an isolated worktree and separate device profile. Do not use a daily phone.
Record revision, device/API level, installed build, starting permissions and rules.
Check active work, host memory, load and device ownership before commands.
Select only journeys affected by work in the requested audit window.

Install with `npm ci`. Run `npm run verify` for supporting code checks.
The shared command includes nightly tests. These checks do not prove
accessibility behavior. For the requested audit, `npm run android` installs
the native build.
Confirm the app opens before enabling its service in Android Accessibility.
Do not use release/internal upload commands. Expo Go and web cannot enforce rules.

For real YouTube, Instagram, X, Chrome or Firefox, follow
[real-app testing](../../../docs/real-app-testing.md): QA build, explicit serial,
authentic targets and observations without UI Automator.

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
