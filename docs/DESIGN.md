# Zen Mode interface rules

These rules describe the shipped app interface. Use them when you add or change a
screen. The background and status vocabulary are in
[`design-contract-proposal.md`](design-contract-proposal.md).

## Direction

Dark, dense, and calm. Each screen shows what is saved, what is ready, and what
is running, then gives one clear next action. Do not use looping animation,
decorative motion, or a new color for each route.

## Color

`src/theme/palette.json` is the only source. Tailwind and `src/theme/colors.ts`
read it. The native overlays mirror it in
`modules/zen-guard/android/src/main/res/values/zen_guard_colors.xml`. A change
to one file needs the same change in the other.

| Role | Tokens |
| --- | --- |
| Surfaces | `night` page, `panel` card, `panel2` control or nested area |
| Borders | `line` around surfaces, `line2` around controls and inputs |
| Text | `copy` primary, `muted` supporting, `faint` footnotes and placeholders |
| Active or selected | `accent` text and border on `accentBg` |
| Primary action | `accent` fill with `onAccent` text |
| Error or destructive | `danger` text and `dangerLine` border on `dangerBg` |

Use `accent` only for a state that is actually active or selected. A saved rule
that is not running uses neutral text.

## Type

Use the named roles from `tailwind.config.js`. Do not use `text-base`,
`text-sm`, or pixel sizes in new code: their size differs between web and
native.

| Role | Size | Use |
| --- | --- | --- |
| `text-metric` | 40/46 | One large number, such as a countdown or total |
| `text-display` | 34/40 | First-run page title (`ScreenTitle`) |
| `text-headline` | 20/26 | Pushed screen and sheet titles |
| `text-title` | 17/22 | Section headings, header status summary |
| `text-item` | 15/20 | Row titles, button labels |
| `text-body` | 14/21 | Paragraphs, chip labels |
| `text-support` | 13/19 | Row details, section labels, errors |
| `text-caption` | 12/17 | Status words beside a heading |
| `text-micro` | 11/14 | Pills |

Weights: `font-semibold` for headings, titles, and labels. `font-medium` for
supporting labels. The `ZEN MODE` wordmark is the only bold, tracked, upper
case text. Every other label is sentence case.

## Shape and space

- `rounded-3xl` (24) for grouping surfaces: `Card`, `RowGroup`, sheets, dialogs.
- `rounded-2xl` (16) for standalone controls: buttons, inputs, chips, choice
  cards, notes. The native overlay buttons use the same 16 dp radius.
- `rounded-full` for pills and switches.
- 20 dp page gutter, 20 dp gap between page blocks, 16 dp card inset.
- On web, `SafeAreaView` drops horizontal padding set with `className`. Put the
  gutter on the content inside it (`SheetHeader`, `contentContainerStyle`, or
  an inner `View`).

## Components

All shared parts are in `src/components/ui/`.

| Need | Use |
| --- | --- |
| Page frame | `Screen` |
| Pushed screen title with Back | `ScreenHeader` |
| First-run title | `ScreenTitle` |
| Sheet title with close | `SheetHeader` |
| Actions | `PrimaryButton`, `SecondaryButton`, `DangerButton` |
| List of links or settings | `RowGroup` with `Row` |
| Single choice from short values | `ChipGroup` |
| Single choice that needs a sentence | `ChoiceCard` |
| Status word | `StatusPill` |
| Error near the action | `ErrorNote`, with a retry when one is possible |

Use lucide icons for glyphs (`ChevronRight`, `X`, `Plus`). Do not use text
characters such as `›` or `×` as icons.

## States and accessibility

- Every interactive element has a touch target of at least 48 × 48 dp
  (`metrics.touchTarget`). The visible shape can be smaller.
- Disabled controls use `opacity-40` and set `accessibilityState.disabled`.
- Pressed controls use `active:opacity-60` or `active:opacity-70`.
- Selected chips and choice cards are tonal: `border-accent bg-accentBg` with
  `text-accent`. Do not use a solid accent fill for selection.
- Radios set both `checked` and `selected`. TalkBack announces `checked`. The
  Android integration scripts read `selected` from the UI dump.
- Screen, sheet, and section titles set `accessibilityRole="header"`.
- Errors set `accessibilityLiveRegion="polite"`.
- Keep the visible text that the Android scripts in `scripts/` match, such as
  `Save rule`, `No app limits yet.`, `Lock for 7 days`, and `Search apps`.

## Native overlays

The blocked screen and the Shorts result card are drawn in Kotlin. They use the
`zen_*` colors and 16 dp button radius. When you change a color, radius, or
weight here, check the overlays for the same change.
