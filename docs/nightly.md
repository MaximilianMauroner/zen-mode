# Nightly to Play Internal

Scheduled build of `main` to the Play Internal track for the same package
`com.lab4code.zenmode`. No side-by-side install; the nightly replaces the
previous Internal artifact for testers.

## What runs

`.github/workflows/nightly.yml` runs daily at 02:00 UTC and on manual dispatch.
A scheduled run first compares `main` against the last attempted nightly,
successful or failed, and skips everything when this commit was already tried.
A failed attempt is not retried: the same commit repeats the same result, so a
fix needs a new commit first. Manual runs always build.

1. `npm ci`, `npm test`, `npm run lint`, `npx expo export --platform web`.
   `npx tsc --noEmit` runs informational only until the known `/privacy`
   typed-route errors on main are fixed.
2. `node scripts/stamp-nightly-version.mjs` sets a date-based identity, e.g.
   `0.1.5-nightly.20260917` with versionCode `2026091701`. Every upload needs
   a higher versionCode than the last, and the date scheme guarantees that.
3. `eas build --platform android --profile nightly --non-interactive --auto-submit`
   builds the `nightly` profile from `eas.json` and submits the AAB with the
   `nightly` submit profile: Internal track, `completed`, no review request.

Signing uses the existing EAS-managed upload key. The release guard verifies
the built AAB against the pinned approved certificate before any release task
succeeds. No keystore or password is committed.

## Required secrets

Set these as GitHub Actions secrets before enabling the schedule:

- `EXPO_TOKEN`: Expo access token that can build on `@thearizztokrat/zen-mode`.
- `GOOGLE_SERVICE_ACCOUNT_KEY_JSON`: full JSON of the Play service account
  with release access to app `4973526737842711493`. The workflow writes it to
  `google-service-account.json`, which is gitignored and exists only on the
  ephemeral runner.

## Owner gates

- The Internal tester list stays as is (currently one-person Max testing).
  A `completed` Internal release reaches those testers at once.
- To hold nightlies for manual promotion instead, change `releaseStatus` to
  `draft` in the `nightly` submit profile.
- Production releases stay manual. The nightly never touches the
  `production` build or submit profiles.
