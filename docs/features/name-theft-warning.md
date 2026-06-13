# Name-Theft Warning Popup

A launch popup warning users that the channel `t.me/femboiVPN` («ЧВК Фембойчик»)
copied "Boykisser VPN", pointing them to the real channels. Activation is a
remotely-controllable "time bomb" with an August 1, 2026 offline date fallback.

## Activation gate (`nametheft/NameTheftWarning.kt`)
Probed on **every launch**. Primary host `https://boykiss3r.site/didtheyconfess`;
if it gives no verdict (UNKNOWN), the fallback `https://somenewsteps.space/didtheyconfess`.
10s timeout per host; any throw/timeout -> UNKNOWN.

| Probe | HTTP | Lease after | Show? |
|---|---|---|---|
| FIRE | 418 | unchanged | yes, always |
| DISARM | 409 | -> disarmed | no |
| REARM | 451 | -> armed | date gate (today >= 2026-08-01) |
| UNKNOWN | timeout / other / error | unchanged | disarmed -> no; else date gate |

- `signalFor(code)` and `evaluate(signal, wasDisarmed, today)` are pure + unit-tested.
- FIRE never alters the lease; REARM is the only re-arm; a 409 disarm survives
  timeouts (the "lease"), revocable only by a 451.

## Lease (`nametheft/NameTheftWarningRepository.kt`)
`xray_prefs` / `name_theft_disarmed` (boolean, default armed=false).

## Dialog (`nametheft/NameTheftDialog.kt`)
Fully modal (`dismissOnBackPress=false`, `dismissOnClickOutside=false`, no-op
`onDismissRequest`). Single Dismiss button disabled behind a 5s countdown.
`boykisservpn_news` / `boykisser_vpn_bot` render as tappable links;
`t.me/femboiVPN` is plain text. Toast fallback if no handler.

## Wiring (`MainActivity`)
`rememberSaveable` decision + dismissed flags survive rotation (can't be escaped);
`LaunchedEffect` runs `resolve` once per fresh launch. Shows on every app opening.

## Strings
`name_theft_*` in `values/` + `values-ru/`.

## Tests
`app/src/test/.../nametheft/NameTheftWarningTest.kt` (signalFor + evaluate matrix),
`NameTheftWarningRepositoryTest.kt` (lease persistence). Probe hosts, the modal,
and link taps are manual QA.

## Related temporary changes
- Sideload launch popup disabled via `MainActivity.SIDELOAD_WARNING_LAUNCH_ENABLED=false`.
- Promoted subscription silenced (banner, row, nag screen, deep-link/App-Link)
  by commenting out call sites; definitions kept as dead code.
