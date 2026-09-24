# Design audit inventory for #31

Source baseline: `ce6d39b` (`main`, 23 September 2026). This is the first
source audit and capture plan, not an approved design contract or Android visual
acceptance. Pending PRs #43, #44, and #45 change Setup, Feeds, and dialogs;
refresh those rows against their merged heads before migration.

## Route and state matrix

Capture each applicable cell at 320 px and a common Android phone width, at
large system font, and at tablet/foldable width. Capture web at 320 px and
desktop width as an interface preview. For Android, include TalkBack focus order,
system Back, keyboard, and reduced motion in the evidence record.

| Surface | States and journeys to capture | Source |
| --- | --- | --- |
| Setup `/setup` | First use, disclosure/consent, loading, error/retry, Android access denied/granted, return from Settings, restart. Recheck after #43. | `src/app/setup.tsx` |
| Feeds `/` | Status checking/unavailable/access needed/paused/empty/awaiting observation/partly ready/active; enabled and disabled rules; action error; Settings and Lock links. Recheck after #44. | `src/app/(controls)/index.tsx`, `src/components/ui/control-header.tsx` |
| YouTube, Instagram, X drawers | Each feed's observed/unobserved, supported/unavailable, active/paused, locked, busy, error and long guidance; Back and close. Recheck after #44/#45. | `src/components/ui/feed-controls-drawer.tsx` |
| Sites drawer | Loading, unavailable, browser observation, blocked/allowed, custom-host add/remove, invalid input, lock refusal, error and keyboard. Recheck after #45. | `src/components/ui/adult-site-controls-drawer.tsx` |
| App limits `/limits` | Loading/error/Android required, empty list, daily/timed/rolling edit, existing rules, lock refusal, busy and app picker search/empty/error. Recheck after #45. | `src/app/(controls)/limits.tsx`, `src/components/ui/app-picker.tsx` |
| Lock `/lock` | Loading/error, open, locked, unlock pending, countdown, duration selection and busy action. | `src/app/(controls)/lock.tsx` |
| Settings `/settings` | Checking/unavailable/access needed/running/paused, read error, protection action, long guard details and navigation to Stats/Privacy. Recheck after #45. | `src/app/settings.tsx` |
| Stats `/stats` | Loading, empty, populated, refresh and error. | `src/app/stats.tsx` |
| Privacy `/privacy` | Long content, feedback action busy/error and return navigation. | `src/app/privacy.tsx` |

## Verified source findings

1. **P1, minimum target size:** `IconTile` uses a 40 × 40 dp pressable
   (`src/components/ui/screen.tsx`), below Android's 48 × 48 dp target. The
   shared Back action inherits it. `src/components/ui/app-picker.tsx` also uses
   44 × 44 dp close controls. Increase the interactive bounds while preserving
   visual size and check neighboring hit areas on a phone.
2. **P2, heading semantics:** `ScreenTitle` renders the page title as plain
   `Text` without an accessibility header role (`src/components/ui/screen.tsx`).
   Setup and Stats use it, so TalkBack heading navigation may skip the title.
   Add the role after checking its reading order with the adjacent header.
3. **P2, shared responsive contract to verify:** `Screen` limits content to `max-w-xl`
   and fixed 20 px gutters; `ControlHeader` uses the same narrow column and a
   fixed 110 × 125 px image. There is no documented expanded-width rule or
   hinge-aware placement. Capture tablet/foldable and 320 px layouts before
   deciding whether to keep the single column or add a wider composition.
4. **Preserve the state vocabulary:** The shared header has explicit `unknown`,
   `unavailable`, `permission`, `paused`, `setup`, `partial`, and `active`
   summaries (`src/components/ui/control-header.tsx`). Keep these distinctions
   when normalizing tokens and components; compare each screen and drawer label
   against the same status in the capture matrix.

Source positives: route ownership is clear; the feed presentation helpers keep
status wording separate from detector policy; key controls have accessibility
roles and many busy/error states are explicit. Finding 3 is an inspection
target, not a claim of a reproduced visual defect.

## Capture and decision boundary

Four **web interface-preview** samples from the SDK 57 static export at
`33a5e52` are attached under
[`evidence/design-31-web-preview/`](evidence/design-31-web-preview/):
Setup and Stats at 320 × 800 and 1280 × 800. They show the current compact
column and Stats text wrapping; the narrow Setup image captures only the first
scroll viewport. The 1280 Setup sample shows a web-only setup-read error. These
samples do not exercise Android protection or cover the state matrix. T3 device
access was off, so no native capture is available. Capture the remaining matrix
before approving either of two candidate Feeds directions: (A) keep the
existing compact status header and improve its responsive spacing, or (B) place
the current protection state in a larger first card and keep the route tabs
visually quieter. Both must preserve the same action and status semantics.
Choose from comparable phone and expanded-width captures, then document color,
type, spacing, radius, focus, disabled, danger, motion, component anatomy, and
responsive rules before migrating shared UI.
