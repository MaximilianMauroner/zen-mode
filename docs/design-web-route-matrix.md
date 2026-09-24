# Web route capture matrix for #31

This is a web interface-preview audit, stacked on the proposed #31 foundation
in PR #47 at `e42390d`. The captures use the SDK 57 static export from the
same UI code (`33a5e52`); `e42390d` changed only an audit note. Chromium used
800 px high viewports at 320 and 1280 CSS px wide. Each PNG is the **first
viewport**: the React Native `ScrollView` does not make `--full-page` capture
its internal scrolled content. These images cannot establish Android behavior.

| Route | 320 px | 1280 px | Captured web state |
| --- | --- | --- | --- |
| Setup | [phone](evidence/design-31-web-preview/setup-320.png) | [desktop](evidence/design-31-web-preview/setup-1280.png) | First-use defaults and disclosure; the desktop capture also shows a web setup-read error. Superseded in part by PR #43. |
| Feeds | [phone](evidence/design-31-web-preview/feeds-320.png) | [desktop](evidence/design-31-web-preview/feeds-1280.png) | Android unavailable, read error, disabled feed rows. PR #44 changes Shorts guidance. |
| App limits | [phone](evidence/design-31-web-preview/limits-320.png) | [desktop](evidence/design-31-web-preview/limits-1280.png) | Android required and disabled add action. |
| Lock | [phone](evidence/design-31-web-preview/lock-320.png) | [desktop](evidence/design-31-web-preview/lock-1280.png) | Lock read unavailable; duration choices and action are disabled. |
| Settings | [phone](evidence/design-31-web-preview/settings-320.png) | [desktop](evidence/design-31-web-preview/settings-1280.png) | Service unavailable and guard details visible. PR #45 changes the protection action. |
| Stats | [phone](evidence/design-31-web-preview/stats-320.png) | [desktop](evidence/design-31-web-preview/stats-1280.png) | Web-local zero totals. |
| Privacy | [phone](evidence/design-31-web-preview/privacy-320.png) | [desktop](evidence/design-31-web-preview/privacy-1280.png) | Long static copy and policy action; phone capture shows only its first viewport. |

## Findings from the captured states

- **P2, status wording to reconcile:** Settings labels Android access and
  protection `Unavailable`, but the Shorts detection row says `WAITING` and
  `0 detections recorded`, while Instagram checks says `0/2`, in the same web
  state. Both rows use a non-null status object without checking
  `status.available` (`src/app/settings.tsx`). Show unavailable for both raw
  check metrics when their source cannot be read; keep feed readiness and
  enforcement rules untouched. Coordinate with PR #45, which edits Settings,
  before changing that file.
- At 320 px, the visible first viewports show no horizontal clipping in these
  seven captured states. The compact Feeds tabs and the visible portion of
  grouped rows fit horizontally. Below-the-fold reachability, keyboard input,
  and Android font-scale behavior remain untested by these captures.
- At 1280 px, the existing centered `max-w-xl` column remains readable. The
  comparison does not decide whether tablet/foldable Android should keep one
  column or use an expanded composition; that is still an owner design choice.

## Remaining matrix

Capture below-the-fold content, drawers, loading/ready/active/locked states,
large text, TalkBack order, keyboard, Back, and reduced motion on Android.
Repeat Setup after #43 and the affected Feeds/Settings flows after #44/#45.
Do not use these web captures as evidence that the native service enforced a
rule. The proposed design contract in PR #47 still needs comparable Android
captures and an explicit Feeds direction before broader migration.
