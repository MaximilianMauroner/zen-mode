# Stats availability review for #31

This slice makes `/stats` show unavailable in the web interface preview.
Android still reads the same native aggregate statistics; a zero comes from the
resolved native API call rather than the synthetic web stub. No protection
rule, observation gate, or native service code changes. iOS is not a supported
protection platform and this change does not make its route runnable.

## Reviewable state matrix

| Source and state | Expected total | By-protection area | Native read |
| --- | --- | --- | --- |
| Web preview, even if a synthetic zero object exists | `—` | “Statistics are available in the Android app.” | None |
| Android read still loading | `—` | “Loading statistics…” | Pending |
| Android read succeeds with no interventions | `0` | Fixed category rows with real zeroes | Succeeded |
| Android read succeeds with interventions | Native total | Native category counts | Succeeded |
| Android API call rejects | `—` | “No statistics available.” plus retry error | Rejected |

`tests/stats-screen-model.test.mjs` exercises the web and Android zero,
loading, rejected-call, and populated model paths. The web export was attempted with
normal and one-worker Metro settings, but both remained in bundling with no
output files under this host's memory pressure. No rendered web capture is
claimed. The owner can capture `/stats` at 320 px and desktop width from a
successful SDK 57 web export; confirm the first matrix row and scroll
reachability. Web evidence is an interface preview only.

## Owner Android acceptance gate

Use the installed build from the final reviewed PR head and record the build
identity, device model, Android version, font scale, and a screen recording or
screenshots with the PR or issue #31. Complete these steps before merge:

1. On a fresh Android install with no interventions, open Settings → Statistics.
   Confirm the all-time total and fixed category rows show native `0` values, not
   the web unavailable message. Confirm the Back action works.
2. Cause one supported, observed protection action on the device, then return to
   Statistics and pull to refresh. Confirm its relevant category and total rise
   from the native count. Record the rule and observed action used; an observed
   detector signal alone is not an intervention.
3. Disable the accessibility service and revisit Statistics. Confirm previously
   recorded local totals remain readable; the screen must not claim that the
   service is currently enforcing a rule.
4. At large font scale with TalkBack, check the title, total, section heading,
   each row label and count, refresh gesture, and Back reading order. Confirm no
   count or action is clipped at a compact phone width.
5. Reopen the app and confirm the same aggregate totals persist. If the native
   API call rejects, capture the error and confirm the screen shows an em dash
   and retry guidance. The existing native store can return an empty zero
   snapshot on internal schema or runtime errors; that behavior is outside this
   route-only slice and must not be described as a successfully verified zero.

Source-level state tests do not satisfy this device gate. The broader #31
design contract and route/device matrix also remain for the owner to review
before treating this as final design acceptance.
