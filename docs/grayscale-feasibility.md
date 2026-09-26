# Grayscale feasibility gate for #35

Decision: **no product implementation on the current Android app contract**. As of
23 September 2026, no documented API available to this Play-distributed app can
desaturate another interactive app's pixels on entry and reliably restore them
on exit. This is a platform/API no-go for the proposed feature, not a device
test result. No grayscale setting, rule, or bridge is exposed to users.

## Evidence and rejected mechanisms

| Mechanism | Finding |
| --- | --- |
| Android color correction | The system owns display color correction. Writing its secure setting needs `WRITE_SECURE_SETTINGS`, which [Android says is not for third-party apps](https://developer.android.com/reference/android/Manifest.permission#WRITE_SECURE_SETTINGS). User-grantable `WRITE_SETTINGS` is a different permission for `Settings.System`; it does not grant secure-setting writes. An ADB grant, root, device owner, or OEM/system signing would change the distribution contract. |
| Accessibility overlay | [`TYPE_ACCESSIBILITY_OVERLAY`](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_ACCESSIBILITY_OVERLAY) adds a window above other windows. Zen Mode's existing overlays draw their own pixels; a color matrix on that window cannot transform pixels in the app beneath it. A translucent gray layer changes brightness/contrast and is not grayscale. An opaque layer hides the app and defeats normal interaction. |
| Screen capture and redraw | [`MediaProjection`](https://developer.android.com/media/grow/media-projection) requires user consent for each session and creates captured frames, not a color transform on the underlying app. Replaying captured frames over an interactive app would add latency, expose screen content, and leave touch, secure windows, Recents, lock screen, and crash recovery unresolved. The issue's microplan expressly excludes a screen-capture workaround. `AccessibilityService.takeScreenshot` has the same capture-versus-render limitation. |
| Public native SDK | The installed Android 36 `android.jar` exposes accessibility overlay and screenshot APIs but no display or accessibility API to apply a per-app grayscale color matrix. Expo SDK 57 can package a native module, but it cannot supply a missing Android privilege. |

The repository currently has no grayscale state or UI. Its
[`app.json`](../app.json) blocks `SYSTEM_ALERT_WINDOW`, and the native
[`AndroidManifest.xml`](../modules/zen-guard/android/src/main/AndroidManifest.xml)
uses only the existing accessibility service and narrow package queries.
[`AppRuleSafety.kt`](../modules/zen-guard/android/src/main/java/com/maxmauroner/zenguard/AppRuleSafety.kt)
already exempts Zen Mode and Settings from whole-app rules. Neither manifest
nor package visibility should change for a mechanism that cannot meet the
acceptance criteria.

## Device boundary and reopening condition

There was no attached Android device or emulator in this environment (`adb`
was unavailable), so no enter/exit timing, API/OEM matrix, lifecycle, or Play
distribution result is claimed. A device prototype would be justified only
after identifying a documented, third-party Android API that desaturates the
underlying interactive app without screen capture or privileged settings
writes. Then test a Play-like signed build on the supported API/OEM matrix:
target entry, non-target switch, Recents, screen lock/unlock, service disable,
process death, reboot, and uninstall. Normal color must be the fail-safe state
in every case. Until then, stop #35 before policy, storage, bridge, and UI work.
