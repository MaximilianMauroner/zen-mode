# Android Fabric listener-lifetime backport evidence

Verification performed on 2026-09-15 UTC from isolated worktree
`/home/codex/work/zen-mode-settings-safety`, based on clean source commit
`435cb21a24eef13090d6d865f7134b44cf9d5d37`. The accepted Android Settings
safety implementation at that commit was not changed.

## Patch identity and provenance

Zen Mode remains on Expo SDK 57's supported `react-native-screens` range
`~4.26.0` and its lockfile still resolves 4.26.2. The patch is the merged
Android listener-lifetime fix from:

- https://github.com/software-mansion/react-native-screens/pull/4413
- https://github.com/software-mansion/react-native-screens/commit/b3badd012f83679b12f4e29f2e28eceaa4830efd
- https://github.com/software-mansion/react-native-screens/pull/4637
- https://github.com/software-mansion/react-native-screens/commit/584fc86ceb0b00fbabfbc878dd8f8f5a8a7c94f5

The patched `NativeProxy.cpp`, `NativeProxy.h`,
`RNSScreenRemovalListener.cpp`, and `RNSScreenRemovalListener.h` were compared
with the npm 4.28.0 tarball and all four matched byte-for-byte. No adaptation,
diagnostic logging, timing instrumentation, or debug endpoint remains. Patch
SHA-256:

```text
d2e0170866f318c5a3e96feece3f3718d8b79585d5e0898bb5c9e2888c0bf1d4  patches/react-native-screens+4.26.2.patch
```

This backport is needed because the observed 4.26.2 race can tear the lazily
initialized listener's `shared_ptr`, leaving React Native to dispatch through a
freed mounting-override delegate in
`MountingCoordinator::pullTransaction`. The merged fix uses a thread-safe,
process-lifetime listener and serialized callback ownership. Remove the patch
and `patch-package` only after upgrading to an Expo-supported
`react-native-screens` version containing the fix, then passing a clean install,
both Android cold-start variants, and the Settings escape harness without it.

## Clean dependency install

Command:

```bash
npm ci
```

Relevant exact output:

```text
> zen-mode@1.0.3 postinstall
> patch-package

patch-package 8.0.1
Applying patches...
react-native-screens@4.26.2 ✔

added 907 packages, and audited 908 packages in 23s
```

The install also reported 14 moderate audit findings. They were not modified as
part of this narrow native backport. Full log SHA-256:
`a58ba5d163de72c981a6b3ec967200611a83a67627acdcf17f116bf5fa00706d`.

## Native build and device identity

Both builds targeted x86_64 and used Android SDK/target 36, NDK
27.1.12297006, React Native 0.86.3, Hermes, New Architecture/Fabric, and
`react-native-screens` 4.26.2 plus the patch above.

Debug command:

```bash
cd android
ANDROID_HOME=/home/codex/android-sdk \
  ./gradlew app:assembleDebug -PreactNativeArchitectures=x86_64 --console=plain
```

Result: `BUILD SUCCESSFUL in 5m 39s`; 517 actionable tasks, 457 executed and
60 up-to-date.

- APK SHA-256: `da9292c668a67f900c5cbe3daadd445e428687b9daeb897b6a6e2929b1fa5107`
- `librnscreens.so` SHA-256: `73c408feac46cbb9ab69cf7a3913191b97ca9a8b815dc79a2593d8848a13e829`
- `librnscreens.so` Build ID: `942b4558f4044e5ee4983be58cf567c3ce76ed95`

Release-mode command:

```bash
cd android
NODE_ENV=production ANDROID_HOME=/home/codex/android-sdk \
  ./gradlew app:assembleRelease -PreactNativeArchitectures=x86_64 --console=plain
```

Result: `BUILD SUCCESSFUL in 7m 49s`; 871 actionable tasks, 646 executed and
225 up-to-date.

- APK SHA-256: `7e2208a18ae412543e5dd0569e1284bac51b914674aae8ca3ea4f941c4a1442d`
- installed `base.apk` had the identical SHA-256 and byte-for-byte `cmp` passed
- `librnscreens.so` SHA-256: `df4b0131a2c1e9289985c5083ec333a8d2d7ddf15f52012f761cbfc16b8bf3d2`
- `librnscreens.so` Build ID: `211f6c94c2aacbb9601c06f4401f775f835f8ebb`
- signer certificate: `CN=Android Debug`; SHA-256
  `fac61745dc0903786fb9ede62a962b399f7348f0bb6f899b8332667591033b9c`

The release-mode APK is signed with the repository's existing debug keystore.
It is not production-signed and is not a release candidate.

All device runs used only `emulator-5554`, disposable AVD `moodqa`, Android
15/API 35, x86_64, fingerprint:

```text
google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys
```

## Cold-start regression

The durable harness force-stops the package, launches it once, requires
Android's `LaunchState: COLD`, observes for 15 seconds, then records the PID,
selected crash buffers, and exit information. It neither retries a failed
attempt nor counts a missing process as valid.

The 200-start runs used harness SHA-256
`5e7038dab4b85360c1632343591f1b67a22976ab2be48504d577fd3aadd9c9ad`.
Before commit, its classifier was tightened to reject a nonzero launch status
or any launch state other than `COLD`; final harness SHA-256 is
`0f2e55eb57175f4a6923814bc1a62ee5f628dad893c33aa027f980dc3b5b34d0`.
The saved debug and release logs were re-audited against those stricter
conditions: each independently contains exactly 100 `LAUNCH_STATUS=0`, 100
`LaunchState: COLD`, 100 `RESULT=VALID`, and no invalid/crash result, so the
classifier tightening does not reclassify any recorded attempt.

The final classifier then passed an affected release-mode device smoke:

```text
2026-09-15T14:11:17.906Z HARNESS_START
2026-09-15T14:11:40.780Z HARNESS_END VARIANT=release ATTEMPTS=1 VALID=1 INVALID=0 CRASHES=0
```

Smoke log SHA-256:
`b377c189c9607dbec45097f513bc6233d2fe12b00479818de73afee2ff457169`.

Debug/dev-client setup and command:

```bash
npx expo start --dev-client --localhost --clear

ZEN_FABRIC_TEST_SERIAL=emulator-5554 \
ZEN_FABRIC_VARIANT=debug \
ZEN_FABRIC_VALID_STARTS=100 \
ZEN_FABRIC_MAX_ATTEMPTS=130 \
ZEN_FABRIC_OBSERVE_MS=15000 \
npm run test:android-fabric-startup
```

Exact bounds/result:

```text
2026-09-15T12:42:43.305Z HARNESS_START
2026-09-15T13:14:06.206Z HARNESS_END VARIANT=debug ATTEMPTS=100 VALID=100 INVALID=0 CRASHES=0
```

All 100 launches were reported `COLD`. Raw timestamped log SHA-256:
`5d3cf1cc041cf5ccf6e707740385c257b8db0a87b92ab0649d7eef11c2b2742f`.
Metro served the development bundles. React Native DevTools itself could not
launch Chrome because Chrome's sandbox was unavailable; that did not stop
Metro, but it remains a development-tool environment limitation.

Release-mode command:

```bash
ZEN_FABRIC_TEST_SERIAL=emulator-5554 \
ZEN_FABRIC_VARIANT=release \
ZEN_FABRIC_VALID_STARTS=100 \
ZEN_FABRIC_MAX_ATTEMPTS=130 \
ZEN_FABRIC_OBSERVE_MS=15000 \
npm run test:android-fabric-startup
```

Exact bounds/result:

```text
2026-09-15T13:33:22.805Z HARNESS_START
2026-09-15T14:04:18.375Z HARNESS_END VARIANT=release ATTEMPTS=100 VALID=100 INVALID=0 CRASHES=0
```

All 100 launches were reported `COLD`. No setup failure or timeout was removed
from either denominator. Raw timestamped log SHA-256:
`d9ab8a42dd963e3210485ccc742c59fe709b1339ec11c2f4c7663da10b29c60a`.

During release attempt 41 the emulator's separate
`com.google.android.bluetooth` process aborted with hardware error `0x42`.
The raw log preserves its full tombstone. Its cmdline and PID were not Zen
Mode's; the Zen Mode process survived the observation and remained a valid
start. The final app crash buffer was empty. A resource sample during the run
showed 1.4 GiB available RAM, 9.4 GiB free swap, and load average 1.11, so the
run did not repeat the severe host contention documented in the earlier
diagnostic release sample.

The complete raw logs and build artifacts are preserved outside the Git
worktree under:

```text
/home/codex/work/zen-mode-settings-safety-diagnostics/patched-435cb21
```

## Patched-build Settings safety regression

Command against the patched debug APK:

```bash
ZEN_GUARD_TEST_SERIAL=emulator-5554 \
ZEN_GUARD_ALLOW_TASK_DATA_RESET=clear-com.lab4code.zenmode-on-moodqa \
npm run test:android-settings-safety
```

The first attempt reached installed-build identity, then timed out during setup
while Metro was still rebuilding and the cleared dev client was at its first-run
menu. It did not enter any protection scenario and is preserved rather than
reported as a safety pass. Log SHA-256:
`80a2f832ec9401be51b11ad301ec800f7e2778b596dde1f50650c913e20f021e`.

The unchanged harness then completed from `2026-09-15T13:19:05.470Z` through
`2026-09-15T13:23:47.383Z`. It created current consent, active native
protection, the enabled accessibility service, and a live seven-day in-app
settings lock through real UI paths. Literal pre-seeded `com.android.settings`
rules passed in daily, timed-visit, and rolling stores. Android Settings and
Accessibility Settings stayed foreground continuously after one launch;
enforcement resumed against Clock; the user disabled the service through
Android Settings; and SecureStore plus the live in-app lock remained unchanged.
The timed scenario also correlated a visible Zen accessibility-overlay window
above a visible Settings-owned window, then proved that the overlay was removed
without its queued Home callback ejecting Settings.

Exact final lines:

```text
2026-09-15T13:23:46.899Z PASS SecureStore-backed lock data and protection preference remain unchanged
2026-09-15T13:23:46.899Z PASS in-app settings lock remains live in the real app UI
2026-09-15T13:23:46.952Z PASS no task-app entry was recorded in Android's crash buffer during the run
2026-09-15T13:23:46.952Z PASS all three stale-rule scenarios passed; service finishes disabled and the lock remains active
2026-09-15T13:23:47.383Z PASS task-app test data was cleared after the final assertions
```

Successful raw log SHA-256:
`dc4440eb3a12086e13d913491da77ac2e161974118a54704e0d71db7922b2529`.

## Limits and remaining release gates

The previous unpatched dev-client SIGSEGV was real, directly reproduced, and
symbolized against matching native artifacts; these passing samples do not
retroactively make it harmless. They validate the exact upstream fix on one
x86_64 API-35 AVD. Physical arm64 hardware, representative OEM Settings
implementations, a production-signed candidate, longer soak/lifecycle testing,
and the normal production release gates remain untested. No signing credential,
Play action, publication, legal declaration, merge, or push was performed.
