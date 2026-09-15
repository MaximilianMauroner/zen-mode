# Android Settings escape-path device evidence

Successful final bounded run on 2026-09-15 (UTC). The harness changes were
uncommitted during this run; the exact harness file used had SHA-256
`b33abd77f01ee2582216ba9583b7f18a73bb0f4b7bb2954e11d7e91ab8c08a5d`.
The harness separately recorded the clean production-source Git HEAD and
proved that the installed APK was byte-for-byte identical to the local build
artifact. This does not embed or independently attest a Git commit inside the
APK.

Command:

```bash
ZEN_GUARD_TEST_SERIAL=emulator-5554 \
ZEN_GUARD_ALLOW_TASK_DATA_RESET=clear-com.lab4code.zenmode-on-moodqa \
npm run test:android-settings-safety
```

Exact successful npm-command output:

```text
> zen-mode@1.0.3 test:android-settings-safety
> node scripts/android-settings-escape-integration.mjs


2026-09-15T11:19:47.058Z ## Isolated device and installed-build identity
2026-09-15T11:19:48.354Z PASS emulator-5554: Android 15/API 35, com.lab4code.zenmode 1.0.3 (4), target SDK 36, debuggable task build
2026-09-15T11:19:48.354Z PASS source HEAD=38370857ad8e2fc0774cf66ed0f57651401d4814; APK sha256=c39c21c953306fd71fd510df3a6c82d8ff143df1871228088b6fc7415658edc9; lastUpdateTime=2026-09-15 10:33:37

2026-09-15T11:19:48.458Z ## Real consent, protection, service confirmation, and settings lock setup
2026-09-15T11:20:40.680Z PASS service enabled through Android confirmation UI and bound to the installed app
2026-09-15T11:21:01.690Z PASS current native consent v3, active protection, and live 7-day SecureStore lock created through UI
2026-09-15T11:21:12.017Z PASS service disabled through Android Settings UI

2026-09-15T11:21:12.097Z ## daily stale Settings rule
2026-09-15T11:21:13.981Z PASS daily store contains a literal stale com.android.settings rule
2026-09-15T11:21:33.081Z PASS service enabled through Android confirmation UI and bound to the installed app
2026-09-15T11:22:08.237Z PASS daily: Android Settings stayed foreground continuously for 8 seconds after one Settings launch
2026-09-15T11:22:29.609Z PASS daily: Accessibility Settings stayed foreground continuously for 8 seconds after one Settings launch
2026-09-15T11:22:32.232Z PASS daily enforcement resumed for com.google.android.deskclock and sent it Home
2026-09-15T11:22:32.361Z PASS SecureStore-backed lock data and protection preference remain unchanged
2026-09-15T11:22:44.457Z PASS service disabled through Android Settings UI

2026-09-15T11:22:44.458Z ## timed-visit stale Settings rule
2026-09-15T11:22:46.772Z PASS timed-visit store contains a literal stale com.android.settings rule
2026-09-15T11:22:59.236Z PASS service enabled through Android confirmation UI and bound to the installed app
2026-09-15T11:23:15.110Z PASS timed-visit: Android Settings stayed foreground continuously for 8 seconds after one Settings launch
2026-09-15T11:23:29.178Z PASS timed-visit: Accessibility Settings stayed foreground continuously for 8 seconds after one Settings launch
2026-09-15T11:23:34.938Z PASS real timed-visit overlay relation: #2 owner=com.lab4code.zenmode type=ACCESSIBILITY_OVERLAY isOnScreen=true isVisible=true above #9 owner=com.google.android.contacts type=BASE_APPLICATION isOnScreen=true isVisible=true
2026-09-15T11:23:53.922Z PASS Accessibility Settings opened beneath an existing Zen overlay stayed foreground continuously for 8 seconds after one Settings launch
2026-09-15T11:23:53.922Z PASS Settings-under-overlay relation: #2 owner=com.lab4code.zenmode type=ACCESSIBILITY_OVERLAY isOnScreen=true isVisible=true above #8 owner=com.android.settings type=APPLICATION_STARTING isOnScreen=true isVisible=true
2026-09-15T11:23:53.968Z PASS existing timed overlay was removed and did not run its Home callback over Settings
2026-09-15T11:23:54.030Z PASS SecureStore-backed lock data and protection preference remain unchanged
2026-09-15T11:24:03.690Z PASS service disabled through Android Settings UI

2026-09-15T11:24:03.691Z ## rolling stale Settings rule
2026-09-15T11:24:05.243Z PASS rolling store contains a literal stale com.android.settings rule
2026-09-15T11:24:15.988Z PASS service enabled through Android confirmation UI and bound to the installed app
2026-09-15T11:24:31.994Z PASS rolling: Android Settings stayed foreground continuously for 8 seconds after one Settings launch
2026-09-15T11:24:46.588Z PASS rolling: Accessibility Settings stayed foreground continuously for 8 seconds after one Settings launch
2026-09-15T11:24:49.140Z PASS rolling enforcement resumed for com.google.android.deskclock and sent it Home
2026-09-15T11:24:49.401Z PASS SecureStore-backed lock data and protection preference remain unchanged
2026-09-15T11:25:00.270Z PASS service disabled through Android Settings UI

2026-09-15T11:25:00.271Z ## Final lock and user escape assertions
2026-09-15T11:25:29.545Z PASS SecureStore-backed lock data and protection preference remain unchanged
2026-09-15T11:25:29.545Z PASS in-app settings lock remains live in the real app UI
2026-09-15T11:25:29.610Z PASS no task-app entry was recorded in Android's crash buffer during the run
2026-09-15T11:25:29.610Z PASS all three stale-rule scenarios passed; service finishes disabled and the lock remains active
2026-09-15T11:25:30.207Z PASS task-app test data was cleared after the final assertions
```

This evidence is limited to the dedicated `moodqa` AVD, Android 15/API 35,
and the debuggable APK identified above. The exact signed release candidate and
representative OEM devices remain release-gate tests. The earlier React
Native/Fabric SIGSEGV reproduced in a preceding attempt on the same day during
development-client startup: `MountingCoordinator::pullTransaction` crashed
with a null dereference in `mqt_v_js` after 15 seconds of process uptime. It
remains unattributed and must be retested against the signed release candidate;
this subsequent passing run does not establish that it was harmless.
