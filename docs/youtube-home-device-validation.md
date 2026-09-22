# YouTube Home physical-device evidence gate

YouTube Home enforcement is intentionally dormant. The repository does not yet contain a captured,
current accessibility tree that distinguishes Home from every preserved YouTube surface, and no safe
in-app exit action has been verified. Synthetic tests are labeled as such and are not device evidence.

Before adding a positive `HOME` result or calling `recordYouTubeHomeObserved()`:

1. Record the Android version, device model, YouTube package version, account state, locale, Zen Mode
   commit, and whether YouTube Premium/Picture-in-Picture is enabled.
2. With a debug native build and the accessibility service enabled, capture sanitized node evidence for
   Home, Search before and after results, Subscriptions, You/library/history, a channel page,
   notifications, ordinary video playback, and Shorts. Keep resource IDs, class names, selected,
   clickable, scrollable, and visible state, plus event types. Do not capture or persist visible text,
   titles, channel names, search terms, or descriptions.
3. Convert those captures into clearly versioned fixtures. Find the smallest Home-positive signature
   corroborated by selected-tab state. Add explicit negative, sparse, and conflicting fixtures; all
   ambiguity must remain `UNKNOWN`.
4. Verify a safe Home-only action on the same build. Prefer a proven in-app destination such as
   Subscriptions. Do not use global Back/Home: ordinary video playback can enter Picture-in-Picture.
5. Wire recognition to `recordYouTubeHomeObserved()` and the action through `YouTubeHomePolicy`.
   Confirm no action occurs while disabled, unobserved, paused, outside Home, or on an unknown tree.
6. Run cold-launch and Home-tab-entry recordings, then manually verify Search/results, Subscriptions,
   library/history, channel pages, notifications, opened videos, and the independent one-Short rule.
   Also verify locked attempts to change Block Home to Allow Home are refused.

Only after this matrix passes should recommended setup enable Home by default or documentation claim
that YouTube Home is blocked.
