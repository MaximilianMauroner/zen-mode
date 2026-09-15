# Zen Mode 1.0.3 pre-signing media

These files are preparation assets, not final Play submission media. They were
captured on 2026-09-15 from source commit
`5e6a32db1c50b76764233c31bce2820dd82b06e2` using a release-mode APK signed
with the repository's existing Android debug test certificate.

## Build and device identity

- APK: `android/app/build/outputs/apk/release/app-release.apk` (local build
  output; intentionally not committed)
- APK SHA-256:
  `1c75e6fac9b1923fa88deba2650ed08b010f0b11cbc67a63354bf096f7633a2e`
- Package/version: `com.lab4code.zenmode`, `1.0.3` (`versionCode 5`)
- Compile/target SDK: 36; minimum SDK: 24
- APK signer: `CN=Android Debug, OU=Android, O=Unknown, L=Unknown, ST=Unknown, C=US`
- Signer certificate SHA-256:
  `fac61745dc0903786fb9ede62a962b399f7348f0bb6f899b8332667591033b9c`
- Build completed: 2026-09-15 18:02:10 UTC; Gradle reported
  `BUILD SUCCESSFUL in 14m 55s`
- AVD: disposable `moodqa`, Android 15/API 35, 1080 x 2400 at 420 dpi
- Device fingerprint:
  `google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.043/12960925:userdebug/dev-keys`

The direct `assembleRelease` command used the existing debug keystore only to
obtain clean release-mode UI without Expo development controls. The verified
upload wrapper rejects this signer, and this APK is not a production or upload
candidate.

## Screenshots and artwork

The screenshots use synthetic Clock rule state. Captures 01–06 were made from
the release-mode APK between 18:08:01 and 18:09:28 UTC. Android accessibility
was enabled, protection was running, and a one-day in-app settings lock was
active for the relevant screens.

| File | SHA-256 |
| --- | --- |
| `01-feeds.png` | `9e92042f85fd0d62e7a3739d054477cc4923be76e8418983d69e823a0b91b850` |
| `02-app-limits.png` | `7d5ad11153ce7474ad33e7cd364984144a4c91f699f13ff32331ecfa54b2b41f` |
| `03-clock-limit.png` | `e330904a5227099ad2ee9585598bf59f21f79b3acfdae8959cd87b54bb236d12` |
| `04-lock.png` | `9fae5bf5fea55c98cb79fc87aa2054e1e67c43bff5bee2eb0c7539ac712157b5` |
| `05-settings.png` | `303924ec3e45a461f00b78f6f0011bd8939a283ad98188382420c672d107d606` |
| `06-privacy.png` | `974cf409cd4b922a4c8786016fc1e1e74cf2ad541deed5777eb98dc2e09c2a4c` |
| `feature-graphic-1024x500.png` | `0a4283a0050e304c925154bcdc08578e1c4b546ceda85e525d938c4b4590cbac` |

The feature graphic is composed from `assets/images/refuge/artwork.jpg`; it
does not fabricate an app screen. All screenshots must be recaptured from and
correlated to the final production-signed candidate before submission.

## Raw walkthrough clips

These are reviewer-safe, synthetic-data preparation clips, not a finished
reviewer video:

- `reviewer-01-consent-enable.mp4` (69.877 s, 1080 x 2400, H.264) records the
  full first-run disclosure, refusal before affirmative consent, consent, the
  Android system enable flow, and return to the app. SHA-256:
  `38e5885365b69e7419689a505442d65f41a33735266f1019f7f83fb6a95f48b8`.
- `reviewer-02-lock-and-failed-clock-attempt.mp4` (173.352 s, 1080 x 2400,
  H.264) records a live settings lock, the 24-hour unlock wait, and a synthetic
  Clock rule. The unscripted Clock launch did **not** display the expected Zen
  timed-visit overlay, so this clip is preserved as a failed attempt and must
  not be used to claim enforcement. SHA-256:
  `734a5237767bcbcb7b079c84bc2ce466006d02e0b861df4da9e0a199a3313f2a`.
  Frame review confirms that the clip shows a saved `VISIT` rule; however, it
  has no correlated service-bound assertion or accessibility-event log. The
  green “You can find the privacy policy here” coachmark is owned by Clock
  (`com.google.android.deskclock`), not Zen Mode or SystemUI. The follow-up
  device harness reproduced the real Visit → Daily → Visit save sequence,
  verified the native stores, observed Clock beneath the Zen overlay on the
  first launch, and retained the overlay across a transient SystemUI window.
  No current product failure was reproduced, so production behavior was not
  changed. See `docs/evidence/android-clock-timed-visit-and-settings-20260915.log`.
- `reviewer-03-settings-escape-disable.mp4` (71.034 s, 1080 x 2400, H.264)
  continuously records `Granted`/`Running`, navigation into Android
  Accessibility settings, the user-visible disable confirmation, disablement,
  and return to `Access needed`. SHA-256:
  `fe0489250bbf2f25c5e9958f2007874248ae52439d1d70426aac976e950957de`.

The automated device evidence in
`docs/evidence/android-settings-escape-vc5-20260915.log` separately exercises
the real timed-visit accessibility overlay and its transition to Settings.
The final reviewer video still requires one coherent recording from the exact
production-signed candidate, including a visible successful ordinary-app
enforcement boundary and candidate-correlated physical-device evidence.
