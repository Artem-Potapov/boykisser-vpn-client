# Config Sanitization: Read-Only Runtime-Pipeline Diagnostic

Maintainer reference for the Advanced → Config sanitization screen and
`ConfigSanitizer`. The feature explains what the app would enforce on the active profile; it never
edits, saves, exports, or reconnects that profile.

## Contract: an inverse view of the real pipeline

`ConfigSanitizer.analyze(stored, log, tuning)` is a **read-only inverse-pipeline diagnostic**. It runs
the stored profile through the real `ConfigBuilder.buildRuntimeConfig` path, then inspects the final
JSON and reports which security normalizations and global overlays took effect. It delegates proxy
selection and normalization to `ConfigBuilder`; it must not grow a second implementation of those
rules.

This is not a JSON diff. Findings describe policy:

- `Rewrote` — a non-canonical stored value was normalized (currently inbounds or DNS).
- `AlreadyCompliant` — the sanitizer's limited structural checks pass: the stored inbound protocol
  list is exactly one `tun`, or the stored DNS server list is nonempty and every entry is a recognized
  secure resolver/pipeline bootstrap. It does not compare all inbound fields, the complete DNS
  object, or canonical JSON equality.
- `Applied` — an unconditional security rule or effective global setting is present.
- `NotApplicable(reason)` — an enabled overlay cannot apply to this profile, such as fragmentation on
  QUIC or Mux.Cool on XTLS Vision.

Disabled optional settings are omitted rather than shown as inactive. Always-effective values (MTU,
IPv6, DNS resolver, routing, and domain strategy) remain visible at defaults.

## Data and refresh behavior

`ConfigSanitizationActivity` resolves the active profile through `ActiveProfileRepository` and Room,
then reads the **current global settings** from `LogPreferences`, `FragmentationPreferences`,
`MuxPreferences`, `DnsPreferences`, `RoutingPreferences`, and `XrayCorePreferences`. It recomputes on
every lifecycle `ON_RESUME`, including the first, so returning from a sibling settings screen refreshes
the report without recreating the Activity.

The work runs off the main thread. UI states are explicit:

- loading spinner;
- no-active-profile message;
- failure message for malformed/unbuildable input;
- success grouped into Security enforcement and Global settings.

There is deliberately no Save action. The report does not mutate SharedPreferences, Room, the stored
config, or the running VPN session.

## Findings

Security findings cover the single tun inbound, DoH-only resolver shape, forced app-private log
posture, port 53 → `dns-out`, and proxy-outbound `ForceIP`. Global findings cover enabled
fragmentation/Mux.Cool, effective sniffing, MTU, IPv6, resolver, routing, and domain strategy.

The diagnostic determines applicability from the selected proxy outbound, not from a stale object
already present in the stored JSON. Thus an enabled fragmentation setting on QUIC remains
`NotApplicable` even if the imported config happens to contain a fragment block.

Summaries also use effective pipeline outcomes rather than merely echoing preferences: an invalid
custom DNS URL that makes `applyDns` return unchanged is reported as the retained Cloudflare resolver,
and unsupported `BLOCKED_ONLY + IR` is reported as the runtime backstop's Proxy everything result.

Output details and failure messages pass through a conservative redactor for UUID, `publicKey`, and
`shortId` identifiers. The screen never displays the full runtime JSON.

## Components

- `config/ConfigSanitizer.kt` — analysis model (`SanitizationReport`, `Finding`, `Status`) and
  final-config inspection.
- `settings/ConfigSanitizationActivity.kt` — active-profile/current-preference loading and
  resume-triggered refresh.
- `settings/SanitizationPresentation.kt` — pure grouping, title mapping, warning-chip decision, and UI
  state resolution.
- `config/ConfigBuilder.kt` — source of truth for normalization and the complete overlay chain.

## Testing and manual gate

`ConfigSanitizerTest` covers rewritten and already-compliant input, malformed failure, omitted disabled
settings, default always-visible values, effective DNS/routing/IPv6 summaries, redaction, and
`NotApplicable` cases. `SanitizationPresentationTest` covers grouping and loading/empty/failure/ready
states.

On a release APK, open the screen with no active profile, a canonical profile, and a profile whose
transport makes an enabled overlay inapplicable. Change a global setting, return to the still-alive
screen, and confirm the report refreshes. Confirm there is no write/save/export action.
