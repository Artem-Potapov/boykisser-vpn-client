# Name-Theft Warning Popup

A launch popup warning users that the channel `t.me/femboiVPN` («ЧВК Фембойчик»)
copied "Boykisser VPN", pointing them to the real channels. Activation is a
remotely-controllable "time bomb" with an August 1, 2026 offline date fallback.

## Activation gate (`nametheft/NameTheftWarning.kt`)

- **Probe:** GET `https://boykiss3r.site/didtheyconfess`; only if that yields no
  verdict (timeout / connection error / unlisted status → UNKNOWN) it probes
  the fallback `https://somenewsteps.space/didtheyconfess`. 10 s budget per host.
- `signalFor(code)` and `evaluate(signal, wasDisarmed, today)` are pure + unit-tested.

| Probe | HTTP | Lease after | Show? |
|---|---|---|---|
| FIRE | 418 | unchanged | yes, always |
| DISARM | 409 | → disarmed | no |
| REARM | 451 | → armed | date gate (today >= 2026-08-01) |
| UNKNOWN | timeout / other / error | unchanged | disarmed → no; else date gate |

- FIRE never alters the lease; REARM is the only re-arm; a 409 disarm survives
  timeouts (the "lease"), revocable only by a 451.
- **[resolve]** reads the persisted lease, probes, applies [evaluate], **persists
  the lease when DISARM/REARM changes it** (`outcome.disarmed != wasDisarmed`),
  and returns whether to show the warning this launch.

## Lease (`nametheft/NameTheftWarningRepository.kt`)

`xray_prefs` / `name_theft_disarmed` (boolean, default **`false` = not disarmed** —
armed; the bomb can fire per the date gate when the probe is inconclusive).

## Dialog (`nametheft/NameTheftDialog.kt`)

Fully modal (`dismissOnBackPress=false`, `dismissOnClickOutside=false`, no-op
`onDismissRequest`). Single Dismiss button disabled behind a 5s countdown.
`t.me/boykisservpn_news` / `t.me/boykisser_vpn_bot` render as tappable links;
`t.me/femboiVPN` is plain text. Toast fallback if no handler.

## Wiring (`MainActivity`)

`nameTheftDecision` and `nameTheftDismissed` are `rememberSaveable` — the probe
result and a user dismissal survive rotation. `LaunchedEffect` runs
`NameTheftWarning.resolve` **once per cold launch** (only while
`nameTheftDecision == null`). The dialog renders when
`nameTheftDecision == true && !nameTheftDismissed`; Dismiss sets
`nameTheftDismissed = true` for the rest of the session (returns on the next
launch).

## Strings

`name_theft_*` in `values/` + `values-ru/`.

## Tests

`app/src/test/.../nametheft/NameTheftWarningTest.kt` (signalFor + evaluate matrix),
`NameTheftWarningRepositoryTest.kt` (lease persistence). Probe hosts, the modal,
and link taps are manual QA.

## Related temporary changes

- Sideload launch popup disabled via `MainActivity.SIDELOAD_WARNING_LAUNCH_ENABLED=false`.
- Promoted subscription: deep-link / App Link entry points are dormant (manifest
  intent-filters commented out); in-app surfaces are live and remote-gated via
  `PromoGate`. See [`boykisser-vpn.md`](boykisser-vpn.md).
