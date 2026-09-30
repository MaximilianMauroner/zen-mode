# Zen Mode design contract proposal (#31)

Status: **proposed, pending phone and expanded-width captures and owner approval**.
Source baseline: `ce6d39b`, with the route/state inventory in
[`design-audit.md`](design-audit.md). This contract describes the existing dark
identity and a small shared foundation. It does not approve a full migration.
Refresh Setup and Feeds against PRs #43–#45 after their heads land.

## Purpose and direction

The UI helps a user see which rules are saved, which are ready, and which are
actually running, then make a deliberate change. Keep the existing dark green
palette, compact cards, three destinations, and native Android status language.
For the first Feeds direction, retain the compact status header and improve its
spacing and reading order. A larger status-first card is an alternative to
compare with the same real content at phone and expanded widths; it is not a
selected replacement. No navigation or protection policy changes follow from
this document.

## Token and component rules

| Role | Contract | Source / first slice |
| --- | --- | --- |
| Surfaces | `night`, `panel`, `panel2`; distinguish nested areas with borders, not new colors per route. | `src/theme/palette.json` and Tailwind theme |
| Text | `copy` for primary, `muted` for supporting, `faint` for small labels and placeholders. Every text/background pair needs at least 4.5:1 unless text meets the large-text exception. | One palette feeds runtime props and NativeWind. This slice lifts `faint` to `#7E9281`. |
| Actions | `accent`/`onAccent` for the primary action, `danger`/`dangerBg` for destructive actions and errors. A saved but inactive rule must never use the active treatment. | Existing shared buttons and pills; no behavior change. |
| Type | Page title 34/40, section heading 18–20, body 14–15, supporting 13, compact label 11–12. Keep system font scaling enabled and test long labels before changing roles. | Existing styles; the shared `ScreenTitle` becomes an accessibility heading in this slice. |
| Space and shape | Keep the current 20 dp page gutter, 20 dp vertical page rhythm, 16 dp card inset, and current rounded cards until captures justify a change. | Existing `Screen` and `Card`; no broad restyling. |
| Hit targets | Every interactive icon and compact action needs at least 48 × 48 dp on Android, with separate adjacent targets. The visible glyph/tile may remain smaller. | `src/theme/metrics.ts`; this slice updates `IconTile` and the app-picker close button. Other compact controls remain in the audit matrix. |
| Focus and disabled | Keep native Pressable focus/Back behavior. Every icon-only action has a name, disabled controls expose state, and modal close actions remain reachable by system Back. Use the existing reduced-motion setting for modal transitions. | Verify on device before further component migration. |
| Feedback | Errors name the problem near the relevant action and offer retry or recovery. Loading and unavailable states never imply protection. | Existing `ErrorNote` and presentation helpers. |

The palette's measured contrast after the proposed `faint` change is 5.68:1 on
`night`, 5.32:1 on `panel`, and 4.80:1 on `panel2`. The unchanged `muted` text
is 7.06:1 on `night` and 6.61:1 on `panel`; `copy` is 17.02:1 on `night`.
These are source color calculations, not a rendered accessibility audit.

## Status vocabulary and anatomy

| State | User-facing meaning | Treatment |
| --- | --- | --- |
| Saved | A rule is stored; prerequisites may still be missing. | Neutral text/pill; never an active claim. |
| Awaiting observation | A supported signal has not yet been seen. | Neutral guidance naming the next check. |
| Unavailable | Android service, supported app, or a fresh status read is unavailable. | Explicit unavailable/access language and recovery action. |
| Paused | Rules remain saved but protection is off. | Warning/status treatment and a deliberate resume action. |
| Active | Access, protection, and the rule's observation gate are satisfied. | Accent treatment with the exact guarded surface named. |

The header summarizes overall protection. Cards and drawers name each rule's
own state. Status color supplements text; it does not carry meaning alone.
The shared screen structure remains: safe-area page frame, route context,
one primary title or status, grouped controls, local errors, and accessible
modal heading plus close action.

## Responsive and acceptance boundary

Use the current single column on compact phones. Do not infer that `max-w-xl`
is sufficient on tablets or foldables; compare a centered column with an
expanded composition using identical state content. At 320 px, large font,
landscape, tablet/foldable, and desktop web preview, no action may clip or
become unreachable. Web checks layout only; Android checks TalkBack order,
48 dp targets, keyboard, safe areas, system Back, and reduced motion.

Before approving this contract or migrating Setup/Feeds, capture the matrix in
`design-audit.md`, choose the Feeds direction from comparable captures, and
record any changed token or navigation decision here. PR #43 owns setup
completion; #44 owns Shorts result; #45 owns weakening consent. Their policy,
service, observation, and settings-lock semantics remain outside #31.
