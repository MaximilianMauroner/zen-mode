# Accessibility reviewer demonstration script

Use synthetic/local state only. Do not show personal notifications, accounts,
messages, browser history, developer menus, terminal windows, or credentials.
Record the production-signed candidate for final submission; until that exists,
label any recording **pre-signing reviewer walkthrough** with its commit and
artifact SHA-256.

## Shot list

1. **Identity** — briefly show Android App info with Zen Mode 1.0.3 and package
   `com.lab4code.zenmode`, then launch the app.
2. **Disclosure and consent** — scroll the complete setup disclosure, show that
   the guard cannot be enabled before the checkbox, select the affirmative
   consent checkbox, and tap **Turn on the guard**.
3. **Android approval** — show the system's Accessibility warning and enable
   **Zen Mode protection**. Return to Zen Mode and show `Granted` / `Running`.
4. **User-selected rule** — open App limits, choose a synthetic/non-personal app
   such as Clock, and create a short timed visit. Open Clock and show Zen Mode's
   real prompt/boundary, then leave it.
5. **In-app lock** — set a one-day lock and show that trying to weaken or remove
   the rule is refused. Do not reveal any password or private unlock material.
6. **Unconditional Settings escape** — while protection and the in-app lock are
   active, open Android Accessibility Settings from Zen Mode. Keep recording
   continuously: Settings must remain foreground with no overlay, Home, Back,
   or deep-link ejection.
7. **Disablement** — disable **Zen Mode protection** through the Android system
   confirmation. Return to Zen Mode and show that access is no longer granted
   and saved rules do not enforce.
8. **Privacy** — open the in-app Privacy screen and its public policy link.

## Acceptance checks

- One continuous capture covers the Settings transition and disable action.
- The app UI and Android system UI are real; no fabricated overlay or web
  preview is used as enforcement evidence.
- Every third-party account surface is omitted or uses a dedicated reviewer
  account supplied privately through Play if later required.
- The final file records candidate commit, AAB/APK digest, device model, Android
  version, date, duration, dimensions, and SHA-256 beside the video.
