# Feature map

Reusable procedure map. README defines conservative protection. A map does
not establish completed device journeys.

| ID | Initial state, public action and expected result | Source / checks | Reset |
| --- | --- | --- | --- |
| Z1 | Fresh test profile, protection off. Open setup and enable Accessibility. Observe each supported surface before readiness; unavailable targets remain unavailable. | setup-policy, target-availability; setup-policy tests | Disable owned service. |
| Z2 | Configure Shorts, Reels, Instagram Home/Explore and X Home/videos. Visit supported feeds and reach a short limit. Observe configured overlay/Home action. Unknown or ambiguous screens do not enforce. | protection feed-status, native module; feed/readiness tests | Clear owned limits and usage. |
| Z3 | Add an owned harmless test domain and enable website rule. Observe known address bars in Chrome, Samsung Internet, Opera and Firefox. Exact known signals enforce; hidden/in-app/unknown signals do not. Browser remains needing check until observed. | adult-site-presentation, native parsers; presentation tests | Remove test domain and rule. |
| Z4 | Pick an owned launchable app. Set daily, rolling-window and timed-visit rules separately. Reach each short boundary and verify remaining time, action and reset. | app-limits-state, app-rule-presentation; native policy tests | Restore rules and disposable usage state. |
| Z5 | Configure settings lock. Try changes that loosen feeds, sites and app rules while locked. Lock prevents them; permitted stricter changes remain available. | lock-policy, lock; lock-policy tests | End owned lock by documented duration. |
| Z6 | Open overview and stats after owned visits. Inspect readiness, actions, usage and time presentation; UI must match native observed state. | overview-actions, stats-screen-model; corresponding tests | Dispose test profile. |
| Z7 | Inspect privacy/consent and feedback flow without sending an external message. Consent version and generated issue fields match current app. | privacy/policy, feedback; consent and github-feedback tests | Dismiss draft. |

All enforcement rows require bounded device observation in a run-owned profile.
An installed SDK alone does not prove a matching native build, enabled service
or supported app signal. Web export is supporting interface evidence only.
