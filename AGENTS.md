# Zen Mode

Zen Mode is an Expo Router app with an Android accessibility-service module.
The app manages focus rules. The native service enforces them on Android.

## Before coding

Read the exact versioned Expo docs at https://docs.expo.dev/versions/v57.0.0/ before writing any code. Expo APIs and configuration change between SDK releases.

Inspect the closest existing screen, component, or native rule before adding a
new path. Make the smallest complete change. Do not add fallback behavior,
compatibility layers, or unrelated refactors.

## Architecture

- Put routes in `src/app/`, reusable UI in `src/components/`, and app logic in
  `src/features/`.
- Keep `modules/zen-guard/` as the boundary between TypeScript and Android.
  Keep policy state machines separate from accessibility-event parsing and UI
  overlays.
- Protection is Android-only. It requires the installed native build and the
  user-enabled accessibility service. Web is an interface preview only.
- Feed detection must be conservative. If a supported app surface is unknown
  or ambiguous, do not enforce a feed action. Preserve the setup and
  observation checks that prevent activation without an observed signal.
- Do not weaken the settings lock or expand Android package visibility without
  an explicit need and a review of the Android manifest.

## Validation

Run the checks that cover the change:

```bash
npm test
npx tsc --noEmit
npm run lint
npx expo export --platform web
```

For Android native changes, also build and test on an Android device or
emulator with `npm run android`. State any check you could not run.

## Prompting guidance

Keep agent instructions short and free of conflicts. Prefer an outcome,
constraints, available evidence, success criteria, and a stopping condition
over a scripted procedure.

- For GPT-5.6, specify approval boundaries and tool-routing rules once. Ask it
  to use only relevant tools, validate the changed behavior, and stop after the
  result meets the stated bar. Remove repeated rules and examples that do not
  affect behavior. Source: [GPT-5.6 prompting guidance](https://developers.openai.com/api/docs/guides/prompt-guidance-gpt-5p6).
- For GPT-6 Astra, make the task scope and instruction precedence explicit.
  For tool-heavy work, ask for a short update at major phases, a plan or task
  list, and verification proportional to the change. State the desired writing
  style and the exact subagent policy. Source: [GPT-6 Astra prompting guidance](https://developers.openai.com/api/docs/guides/latest-model#gpt-6-astra).
