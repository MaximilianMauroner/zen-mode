# Narrow web scroll reachability for #31

This evidence extends the first-viewport [web route matrix](design-web-route-matrix.md)
in PR #48. It uses the same #47 UI code (`33a5e52`, followed only by an audit
note at `e42390d`) and an Expo SDK 57 static export served through `expo serve`.
Chromium 1.63.0 used a 320 × 800 CSS-pixel viewport. A script found each
route's React Native web `ScrollView` with computed `overflow-y: auto`, set
`scrollTop` to `scrollHeight - clientHeight`, then read back the actual offset
before capturing the viewport. This is a **web interface preview**, not an
Android touch, TalkBack, or enforcement test.

| Route | Scrollable height / viewport | Reached offset | Bottom capture and observed control |
| --- | --- | --- | --- |
| Setup | 1451 / 800 px | 651 / 651 px | [Capture](evidence/design-31-web-preview/setup-320-bottom.png): consent, Privacy, and Try again visible in a web read-error state. PR #43 changes this route. |
| Feeds | 635 / 522 px | 113 / 113 px | [Capture](evidence/design-31-web-preview/feeds-320-bottom.png): the Settings-lock link and feed rows visible below the fixed tabs; the error banner scrolls partly behind them. PR #44 changes guidance. |
| Settings | 1469 / 800 px | 669 / 669 px | [Capture](evidence/design-31-web-preview/settings-320-bottom.png): Stats, Privacy, feedback, and disabled app openers visible. PRs #45/#49 change this route. |
| Stats | 1277 / 800 px | 477 / 477 px | [Capture](evidence/design-31-web-preview/stats-320-bottom.png): all lower counters and the local-data note visible. |
| Privacy | 1133 / 800 px | 333 / 333 px | [Capture](evidence/design-31-web-preview/privacy-320-bottom.png): support copy and privacy-policy action visible. |

App limits and Lock fit within the 800 px viewport in their captured web
unavailable states, so the [first-viewport matrix](design-web-route-matrix.md)
already shows their visible controls. Reaching the measured maximum offset
supports web scroll reachability for **these exact states only**. It does not
show that controls work, that a keyboard or larger font leaves them reachable,
or that Android's native layout and service behave the same way. The remaining
Android route/state matrix and owner design-direction decision still gate #31.
