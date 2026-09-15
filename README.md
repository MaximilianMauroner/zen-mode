# Zen Mode

Zen Mode is an Expo and React Native app for setting screen-time and feed
rules. Its protection service runs only in an Android native build.

## What it protects

After the user enables Android Accessibility access, the native service can:

- apply daily, rolling-window, or timed-visit rules to launchable Android apps;
- manage YouTube Shorts, Instagram Reels, Home, and Explore, and X Home and
  video feeds;
- close a bundled set of adult websites plus user-added domains in supported
  Android browsers; and
- show an overlay or return to Home when a configured rule is reached.

Feed detection depends on accessibility data exposed by YouTube, Instagram,
and X. Setup observes supported surfaces before feed enforcement starts. If a
surface is unknown or ambiguous, Zen Mode does not enforce that feed rule.
Updates to those apps can change detection behavior.

Website blocking reads only exact, known address-bar nodes in Chrome, Samsung
Internet, Opera, and Firefox. Each browser stays marked as needing a check until
the service observes a valid address-bar signal. Unknown browsers, in-app pages,
and ambiguous or hidden address bars are left alone. This is a focus boundary,
not network-level filtering; a page can begin loading before the browser exposes
its address.

Protection is off until the user enables it. The settings lock controls changes
that loosen existing rules.

## Interface

Feeds has one row and settings drawer for each supported platform:

- YouTube controls Shorts.
- Instagram controls Reels, Home, and Explore.
- X controls Home and videos.
- Sites controls the adult-site switch, browser readiness, and extra domains.

App limits manages whole-app daily, rolling-window, and timed-visit rules.
Lock manages the settings lock for feeds, websites, and app limits.

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

## Standalone ARM64 debug preview

This local build requires the Android SDK and a JDK.

```bash
npx expo prebuild --platform android --no-install
cd android
./gradlew :app:assembleDebug -PreactNativeArchitectures=arm64-v8a
```

The generated debug APK is suitable for local preview only and must not be
uploaded. Release builds fail closed unless external upload-key configuration
is complete. See [Android release signing](docs/android-release-signing.md) for
the verified App Bundle workflow.
