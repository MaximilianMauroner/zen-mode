# Zen Mode

Zen Mode is an Expo and React Native app for setting screen-time and feed
rules. Its protection service runs only in an Android native build.

## What it protects

After the user enables Android Accessibility access, the native service can:

- apply daily, rolling-window, or timed-visit rules to launchable Android apps;
- manage YouTube Shorts, Instagram Reels, Home, and Explore, and X Home and
  video feeds;
- show an overlay or return to Home when a configured rule is reached.

Feed detection depends on accessibility data exposed by YouTube, Instagram,
and X. Setup observes supported surfaces before feed enforcement starts. If a
surface is unknown or ambiguous, Zen Mode does not enforce that feed rule.
Updates to those apps can change detection behavior.

Protection is off until the user enables it. The settings lock controls changes
that loosen existing rules.

## Interface

Feeds has one row and settings drawer for each supported platform:

- YouTube controls Shorts.
- Instagram controls Reels, Home, and Explore.
- X controls Home and videos.

App limits manages whole-app daily, rolling-window, and timed-visit rules.
Lock manages the settings lock for feeds and app limits.

For distribution requirements, see [Google Play readiness](docs/google-play-readiness.md)
and the [store listing](docs/store-listing.md).

## Run locally

Install dependencies with the lockfile:

```bash
npm ci
```

Start the interface preview:

```bash
npm run web
```

Build and install the Android native app on an emulator or connected device:

```bash
npm run android
```

Then enable Zen Mode in Android Accessibility settings and complete the
in-app setup. `npm run phone:mirror` mirrors exactly one ADB-connected Android
device. It uses `scrcpy` when available.

## Check changes

```bash
npm test
npx tsc --noEmit
npm run lint
npx expo export --platform web
```

Run `npm run android` after a change to `modules/zen-guard/` or Android app
configuration. Test the affected rule on a real device when accessibility
events or overlays change.

## Preview and release limits

Web export checks routing and the interface. It cannot enforce Android app or
feed limits. iOS is not a supported protection target.

Expo Go cannot include this project's local Android module. Use `npm run
android` for native development and testing. This repository has no EAS build
profiles or release automation.

## Standalone ARM64 preview

This local build requires the Android SDK and a JDK.

```bash
npx expo prebuild --platform android --no-install
cd android
./gradlew :app:assembleRelease -PreactNativeArchitectures=arm64-v8a
```

The generated release APK is signed with the default debug key. It is suitable
for local preview only and is not ready to upload. Configure a release keystore
and signing process before distribution.
