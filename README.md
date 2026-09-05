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
npm test
npx tsc --noEmit
npm run lint
npx expo export --platform web
```

The Android app separates settings into three tabs: Feeds, App limits, and Lock. Feeds controls Shorts, Reels, Instagram home, Explore, X home, and X videos. Every feed row opens a settings drawer over the feed list. Reels keeps its pause and viewing window, Home feeds keep their break intervals, and Explore, X videos, and Shorts retain their block or allow choices. Disabling a rule or increasing its allowance requires an open settings lock. App limits holds whole-app time rules. Lock holds both categories. Settings contains Android access, the protection switch, and detection details.

The tabs use Expo Router UI under `src/app/(controls)`. Feed detail controls use `/instagram?section=reels`, `home`, or `explore`. Refuge artwork and the launcher icon live in `assets/images/refuge/`.

Protection needs the native Android build and accessibility access. The web export checks the interface and routing; it does not enforce app limits.

App limits loads configured rules independently of the installed-app inventory. Add app opens a bottom drawer with a cached, virtualized picker.

YouTube Shorts permits one video per viewer visit. A change in the Shorts pager row returns to YouTube’s Home tab with a brief notice. The Home tab action avoids the PiP behavior caused by Android Back. Detection is conservative when viewer identifiers or pager metadata are unavailable.

X support targets the Android app (`com.twitter.android`), not browser x.com. Home breaks use a foreground session allowance like Instagram, checked once per second even when the feed is still. The video viewer permits the first video; a verified full-screen pager scroll exits through X’s Back control. Post text, playback progress, and nested scrolling are not video advancement signals. Setup records Home and video-pager detection before activation. Unknown layouts fail open, and the next video may briefly appear before its accessibility event is handled.
