# Zen Mode

A personal screen-time companion for understanding attention, setting limits, and starting distraction-free focus sessions.

## Stack

- Expo SDK 57 and React Native
- Expo Router
- TypeScript
- NativeWind with Tailwind CSS

## Run locally

```bash
npm install
npm start
```

Press `i` for iOS, `a` for Android, or `w` for web from the Expo terminal. You can also run a platform directly with `npm run ios`, `npm run android`, or `npm run web`.

## Watch the connected phone

With one authorized Android phone connected over USB or wireless ADB, run:

```bash
npm run phone:mirror
```

The command refuses to start without exactly one connected phone. It uses `scrcpy` when installed and falls back to Android `screenrecord` with `ffplay`. The mirror is local to the laptop and stops when the mirror closes or the phone disconnects. Install `scrcpy` for longer sessions:

```bash
brew install scrcpy
```

## Verify

```bash
npx tsc --noEmit
npm run lint
npx expo export --platform web
```

The current slice is a polished dashboard prototype with screen-time progress, pickup metrics, a weekly trend, distraction ranking, and an interactive focus-mode control. Device Screen Time / Digital Wellbeing integrations are intentionally left for a development-build phase because those APIs require native entitlements and modules.
