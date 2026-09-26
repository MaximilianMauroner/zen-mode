# Android acceptance handoff — 26 September 2026

Related: #6 (release), #13 (candidate acceptance), #39–#41 (current behavior),
#31 (design). This is a dated evidence record, not release authorization.

## Outcome and present boundary

Zen Mode should help someone keep chosen limits on distracting Android surfaces
while preserving intentional app use and the Android Settings escape path.
Main contains the native consent gate, conservative observation, feed rules,
app limits, settings lock and local statistics. Source checks are useful but
cannot establish reliable behavior on today's YouTube/Instagram/X versions.
YouTube Home support remains false/default-off pending positive device evidence
(#26). New Instagram ads/Stories rules (#30/#32/#34) need fixtures first.
The owner accepted the grayscale API no-go and closed #35 as not planned:
[decision](https://github.com/MaximilianMauroner/zen-mode/issues/35#issuecomment-5838807152).
#46 preserves that report; it is not a grayscale implementation or device pass.

The next useful work is accepting the already-built changes, then correlating
the resulting release candidate. Additional feature or broad design migration
would increase the unaccepted surface. #31's contract still needs an owner
direction and Android evidence; web captures prove only their named web states.

## Separate source, runner and installed identities

- Remote main inspected: `ce6d39bd4cd465766e7e98bf3951f3a6fca147a4`.
  Its source app version is `0.1.5` / code `8`; the release runner stamps builds.
- The local runner ledger at
  `~/.local/state/lab4code-releases/zen-mode.json` records latest success
  `0.1.8` / code `11`, source `8d0206cbbbe606ad51694bba37966cfdb9081d26`,
  on September 20, attempt `8a563ec2-461b-4b66-8959-19cfae380e80`.
  The ledger snapshot SHA-256 was
  `8e0aef4f0fe2bbd9171d2070181b3eb6ee59ac2994badeed000c46797ca5367d`.
- Code `12` / `0.1.9`, source `31298595c3e3edaa81eecd332d25a106288b99b5`,
  is recorded failed on September 23, attempt
  `226249b9-9ecd-406f-8966-d4be8e27f1a7`. No active reservation is recorded.
  Its private host log is unavailable in this environment; cause is unverified.
  Do not reuse code 12 or infer a successful upload from a built file.
- This audit did not access Play Console or a Play-installed device. The ledger
  is not proof of today's active track, delivered version, or Play signer.
  Historical code-7 evidence must not be presented as current delivery.
- [Internal opt-in](https://play.google.com/apps/internaltest/4700894893507986209)
  cannot validate unmerged #43/#44/#45/#49. Use the separate QA builds below.

## QA downloads and provenance

All four files were downloaded again on September 26. `sha256sum`, SDK 36
`aapt dump badging`, and `apksigner verify --print-certs` confirmed the hashes,
package identities and signer below. The source association and packaging
recipe come from the preceding build record in this T3 thread; this audit
reverified the same bytes, not a new reproducible build or device launch.

| QA | Exact source head | Package / included PRs | Download |
| --- | --- | --- | --- |
| 43 | `8269ee8f6cb46f3840fb63b58a4cf6a3172c0a91` | `com.lab4code.zenmode.qa43`; #43 | [APK](https://tools.mauroner.net/files/R7jpoCfLeZHgTeZQlMie9OPDV62_hmQ0/zen-qa43-8269ee8-labeled.apk) |
| 44 | `8e1485cbd85c17aca737183aadb8dd6ccaa7f5f0` | `com.lab4code.zenmode.qa44`; #44 | [APK](https://tools.mauroner.net/files/N3Wa7dW3Yg2hPJhNFWdjsUloOGZb0HTH/zen-qa44-8e1485c-labeled.apk) |
| 49 | `8188b643a4d7e4437c1d1e1d67bc5890c187faba` | `com.lab4code.zenmode.qa49`; #45 → #49 | [APK](https://tools.mauroner.net/files/oXTkAvp1BuIvFGeCYPppagGga6wqjpj5/zen-qa49-8188b64.apk) |
| 50 | `46350865dd119f2c9a3303c20e092339f9416a9c` | `com.lab4code.zenmode.qa50`; #47 → #48 → #50 | [APK](https://tools.mauroner.net/files/JIs4hvC-ba-R5EGZRpttbk2A7o2GRvnH/zen-qa50-4635086.apk) |

```text
SHA-256
43  0a74e816e86095f7857c837e4788bc2fc56df21b8fd50a899972086e56edeb22
44  e354ca9d9f2300e838270dca5dd3c571cbad236b546dfbe44590339eed13aa90
49  2ca10c1a8f192800ce62b17783457e308e3b66c59aa1279e4a2ec38148e2a199
50  59f149131c62bc2e97acd7c56e46927b9c1b51bbfce118330a2575e7a4c5fd0e
Android Debug certificate SHA-256 (all four)
fac61745dc0903786fb9ede62a962b399f7348f0bb6f899b8332667591033b9c
```

All report version `0.1.5` / code `8`, minimum API 24, target API 36.
The previous upload record gives September 28 expiry at 20:55/21:04/20:43/20:48
UTC for 43/44/49/50 respectively. HTTP confirms availability now, not an expiry
extension. Save the desired APK before then; request a renewed link if expired.

The build-only QA variant used release configuration with debug signing,
distinct application IDs and labels; PR source heads were unchanged. These
IDs give separate app storage from Play's `com.lab4code.zenmode`. Installing
them does not replace or clear that package. No Play data migration is tested.
The previous emulator coexistence test used a debug-signed original package,
not actual Play delivery. Previous install/launch smoke is not physical acceptance.

Runtime self-exemption uses the installed package name; generated provider
authorities use the QA ID. The activity class keeps its original Java namespace.
The shared deep-link scheme can show a chooser: launch the numbered app icon.
Separate service IDs require separate accessibility grants. Sideloaded Android
13+ builds may require App info → menu → Allow restricted settings. This differs
from Play delivery and does not test Play signing, upgrade or restriction behavior.

## Short physical acceptance journeys

Install only the QA package being tested; retain the Play app and its data.
Disable the Play accessibility service temporarily and enable only one QA service
at a time, then restore the previous service state. Do not clear Play app data.
For every result return the QA number, device/OEM, Android version, target app
version, steps, expected/actual result, and a short recording without private
messages or account information. A failure blocks the affected PR; a launch alone
does not satisfy the journey. These are quick-start journeys; the full acceptance
matrices in the linked issues/PRs still apply, including restart, revocation and
service disable/re-enable.

| Order / scope | Exact steps | Expected result / evidence | Gate |
| --- | --- | --- | --- |
| 1. QA43 / #39 | On fresh QA storage choose defaults; decline/leave access off, return, then grant and return. Repeat Customize after reinstalling only QA43 or in a fresh profile. Restart. | No completion without current consent and fresh access; Customize keeps protection paused until deliberate setup. Record both paths and post-restart status. | #43 physical acceptance; does not test #44/#45. |
| 2. QA44 / #40 | Observe one Short, activate Shorts, scroll to the next; then use ordinary video, Search, Home and Android Settings. Repeat with protection paused and with TalkBack/large text. | Exit feedback only after confirmed Home, readable without trapping touch/focus; first Short and unrelated surfaces remain usable; no action while paused. Record transition and overlay disappearance. | #44 physical acceptance. |
| 3. QA49 / #41/#31 | Attempt weaker feed/site/app-limit and pause actions; cancel, answer incorrectly, complete questions, then cancel/confirm separately. Repeat while locked; use system Back, keyboard and TalkBack. Check saved status after restart. | No mutation on cancel/wrong answer; separate final confirmation; lock still refuses weakening, tightening works, Android Settings escape works. Raw status reflects available observations. Record each action family and lock refusal. | #45/#49 physical acceptance; read-failure/unavailable status also needs a safe reproducible test, not access revocation mislabeled as native unavailability. |
| 4. QA50 / #31 | Visit every route/drawer at normal and large text; use TalkBack, Back and keyboard. Compare compact phone and expanded width, capturing the same states. | Reachable controls, heading order, distinct 48dp targets and honest statuses; no clipped actions. Return captures and choose compact Feeds header versus larger status card. | #47 design/device gate and broader #31 migration; #48/#50 captures remain web-only. |
| 5. Play candidate / #9/#13 | Console operator records active track/version/source and artifact identity; install via Internal on a test profile, capture installed package/version/Play signer. Test consent upgrade with service already enabled, reconnect/reboot/revoke, then current-app enforcement and Settings escape. | Exact candidate correlation, no processing before current consent, no enforcement after revocation, intended app surfaces preserved. Return Console identity, package/signer evidence and recordings. | Release acceptance; QA suffix builds cannot clear this. |

No attached device was available during this audit and T3 device access was off.
Do not run destructive Settings test harnesses on the owner's phone; their
disposable-emulator data-reset procedure is a separate test.

## Review and release gates

At the initial inspection, #43–#50 were open and conflict-free with no hosted check runs.
Stacks are `main → #45 → #49` and `main → #47 → #48 → #50`; #43, #44 and
#46 independently target main. #44/#45 remain draft. Existing exact-head
GitHub `@prometheus` comments on #43/#46/#47/#48/#49/#50 did not invoke the
configured reviewer and are not approval. Prometheus is the independent native
Codex reviewer (GPT-6 Astra/high), distinct from the implementation agent.
Invoke that reviewer directly and retain its PASS or HOLD with the exact head
and review source. An implementation agent's self-review or an unrelated
automatic Codex review does not substitute for that independent review.

Subsequently, #46 received an independent native Prometheus PASS and merged in
main `7254eb2643c87d8534319c257fe034b934340bca`. #35 stays closed as the accepted
API no-go; this adds no device-validation claim.

Merge requires independent Prometheus PASS explicitly naming the current head,
required checks, no blocking concerns/conflicts, and applicable parent, device
and design gates. Refresh heads and reviews immediately before any merge.
Feature acceptance and release device/Console gates remain with their owning
issues and PRs. They do not require physical acceptance before merging an
accurate documentation-only handoff such as this one.
This document does not close any acceptance issue or authorize Play publication.

Owner/Console-only decisions still needed for release: confirm account testing
eligibility and intended countries/track; review final Accessibility declaration,
Data Safety and candidate media; supply reviewer access only if actually required.
Keep credentials in the approved secret channel, never in issues or recordings.
Existing package/signing/privacy setup should not be recreated merely because
older issue bodies say it is missing. For code12, the release-host operator can
provide the failed stage and a redacted error excerpt for the attempt above;
diagnose that evidence before another upload. Missing failure logs block diagnosis,
not the separate QA journeys.
