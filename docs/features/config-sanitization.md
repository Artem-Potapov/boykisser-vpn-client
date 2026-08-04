# Config Sanitization: Read-Only Runtime-Pipeline Diagnostic

Maintainer reference for the Advanced → Config sanitization screen and
`ConfigSanitizer`. The feature explains what the app would enforce on the **subject profile** — the
active profile, or the first profile when none has been explicitly activated yet (see Data and
refresh behavior); it never edits, saves, exports, or reconnects that profile.

## Contract: an inverse view of the real pipeline

`ConfigSanitizer.analyze(stored, log, tuning)` is a **read-only inverse-pipeline diagnostic**. It runs
the stored profile through the real `ConfigBuilder.buildRuntimeConfig` path, then inspects the final
JSON and reports which security normalizations and global overlays took effect. It delegates proxy
selection and normalization to `ConfigBuilder`; it must not grow a second implementation of those
rules.

This is not a JSON diff. Findings describe policy:

- `Rewrote` — a non-canonical stored value was normalized (currently inbounds or DNS).
- `AlreadyCompliant` — the stored value already matches what the pipeline would produce. For inbounds
  this means the stored inbound protocol list is exactly one `tun`. For DNS it means the stored
  `dns.servers` list positionally equals what the FULL runtime pipeline emits for this exact config —
  `makeSecureDns`'s security shape, **then** every tuning overlay including a global DNS resolver
  override (`ConfigBuilder.storedDnsSurvivesPipeline`). So a hostname-addressed proxy must already
  carry the **complete** `https+local://` bootstrap pair, **and** a global resolver override (e.g.
  Quad9) that swaps the stored servers for a different preset also reports `Rewrote`, not
  `AlreadyCompliant` — the status reflects this profile's actual end-to-end outcome, not just
  baseline-shape compliance. The DNS comparison is by address + `domains` scope, not by every server
  field (see the precision boundaries below) or by canonical JSON equality.
- `Added` — the pipeline inserted a missing security object or rule the stored config lacked (e.g. the
  forced app-private `log` object, or the port-53 → `dns-out` route when the stored config had none).
- `Applied` — an unconditional security rule or effective global setting is present and enforced, as
  opposed to newly `Added`: the stored config already carried the rule, or the pipeline overwrote a
  value in place.
- `NotApplicable(reason)` — an enabled overlay cannot apply to this profile, such as fragmentation on
  QUIC or Mux.Cool on XTLS Vision.

Disabled optional settings are omitted rather than shown as inactive. Always-effective values (MTU,
IPv6, DNS resolver, routing, and domain strategy) remain visible at defaults.

## Data and refresh behavior

`ConfigSanitizationActivity` resolves its **subject profile** through the read-only
`resolveSanitizationSubjectProfile(context)` helper: the persisted active profile if it still exists
(`ActiveProfileRepository.getActiveProfileId` → `ProfileDao.getById`), otherwise the lowest-id profile
(`ProfileDao.getFirst`). That active-or-first order matches the "effective profile" the QS tile and
`XrayVpnService` resolve via `pickOrPersistActive` — but the sanitizer **never persists** the fallback
pick, because opening a read-only diagnostic must not change which profile the app considers active.

This matters: `getActiveProfileId` alone is `null` until a Connect/tile/service action first writes
it, so resolving on the raw active id showed the empty state to *every* user who had imported profiles
but never connected. With the `getFirst` fallback, the empty state now appears only when the profile
table is genuinely empty.

It then reads the **current global settings** from `LogPreferences`, `FragmentationPreferences`,
`MuxPreferences`, `DnsPreferences`, `RoutingPreferences`, and `XrayCorePreferences`. It recomputes on
every lifecycle `ON_RESUME`, including the first, so returning from a sibling settings screen refreshes
the report without recreating the Activity.

The work runs off the main thread. UI states are explicit:

- loading spinner;
- empty-state message only when there is no profile at all (empty Room table);
- failure message for malformed/unbuildable input;
- success grouped into Security enforcement and Global settings.

There is deliberately no Save action. The report does not mutate SharedPreferences, Room, the stored
config, or the running VPN session.

## Findings

Security findings cover the single tun inbound, DoH-only resolver shape, forced app-private log
posture, port 53 → `dns-out`, and proxy-outbound `ForceIP`. Global findings cover enabled
fragmentation/Mux.Cool, effective sniffing, MTU, IPv6, resolver, routing, domain strategy, and the
health-probe carve-out (a deliberate override of `EXCEPT_COUNTRY` / imported-direct for
`ConfigBuilder.HEALTH_PROBE_HOST` and `HEALTH_PROBE_IPS`, including the address-list residual when
sniffing is not forced).
The forced-log and port-53 findings are derived from the **final JSON structure** (log `access=none`,
a matching level, and an app-private `error` path; a present port-53 → `dns-out` rule) and report
`Added` vs `Applied` from that structure — a non-conforming final shape yields `NotApplicable`, never
a false success. The expected log path is passed to the pipeline as a plain string; the diagnostic
never creates the log directory or file.

The diagnostic determines applicability from the selected proxy outbound, not from a stale object
already present in the stored JSON. Thus an enabled fragmentation setting on QUIC remains
`NotApplicable` even if the imported config happens to contain a fragment block.

Summaries also use effective pipeline outcomes rather than merely echoing preferences: an invalid
custom DNS URL that makes `applyDns` return unchanged is reported as the retained Cloudflare resolver,
and unsupported `BLOCKED_ONLY + IR` is reported as the runtime backstop's Proxy everything result.

Output is **structural by construction, not redaction-by-blacklist**. Every finding detail is built
from an allowlist of safe structural facts — protocol category, a `scheme://host` resolver label (with
user-info, port, path, query, and fragment stripped; bracket-aware for IPv6), rule presence, and
effective policy — so arbitrary input strings, full URLs, and credentials have no path to the screen.
The failure path never surfaces a parser exception (which can echo the submitted URI/JSON): it always
returns one generic, known-safe reason, rendered from a fully-localized string with no interpolation.
A widened identifier regex (UUID, `publicKey`, `shortId`, `pbk`, `sid`, and password/secret/token/auth
keywords) remains only as a defense-in-depth backstop over that allowlist, never as the primary guard.
The screen never displays the full runtime JSON.

### Diagnostic-precision boundaries

One deliberate imprecision affects only the *label*, never runtime enforcement or credential safety:

- **DNS entry comparison granularity.** `storedDnsSurvivesPipeline` compares server entries by
  address + `domains` scope only. A stored bootstrap-position entry carrying extra fields (e.g.
  `expectIPs`) that `makeSecureDns` would drop at runtime still compares equal, so `AlreadyCompliant`
  can be marginally optimistic. The effective runtime shape is still the enforced secure shape on every
  path — this is label precision, not a safety gap.

(Formerly a second boundary existed here: DNS_DOH's `AlreadyCompliant`/`Rewrote` *status* was decided
only against the `makeSecureDns` baseline shape, separately from the *detail* text, which already
reflected the post-`applyDns` effective resolver. That split let a profile read `AlreadyCompliant` even
when a global resolver override — e.g. Quad9 — had silently replaced its stored DoH servers, which QA
flagged as misleading (§5 SA5). `storedDnsSurvivesPipeline` now decides status against the actual final
`dns.servers` array, the same source the detail text already used, so status and detail agree again and
this is no longer a documented boundary.)

## Components

- `config/ConfigSanitizer.kt` — analysis model (`SanitizationReport`, `Finding`, `Status`) and
  final-config inspection.
- `settings/ConfigSanitizationActivity.kt` — subject-profile resolution
  (`resolveSanitizationSubjectProfile`, active-or-first, read-only, never persists),
  current-preference loading, and resume-triggered refresh.
- `settings/SanitizationPresentation.kt` — pure grouping, title mapping, warning-chip decision, and UI
  state resolution.
- `config/ConfigBuilder.kt` — source of truth for normalization and the complete overlay chain; its
  read-only `internal storedDnsSurvivesPipeline(storedConfigJson, finalServers)` is the DNS-compliance
  classifier the sanitizer consumes (it structurally compares the stored server list against the array
  `ConfigSanitizer` already computed by running the FULL pipeline — `makeSecureDns` plus every tuning
  overlay, including a global resolver override) instead of re-deriving DoH rules. **Gated file** —
  changes need maintainer review.

## Testing and manual gate

`ConfigSanitizerTest` covers rewritten and already-compliant input (including hostname-proxy bootstrap
pair present/partial/absent, and a global resolver override — e.g. Quad9 — silently replacing a
stored-compliant resolver pair), malformed failure, omitted disabled settings, default always-visible
values, effective DNS/routing/IPv6 summaries, `Added` vs `Applied` from final JSON, credential-safety
regressions (`pbk`/`sid`/user-info/query values absent from both failure and success output), and
`NotApplicable` cases. `SanitizationPresentationTest` covers grouping and loading/empty/failure/ready
states. `SanitizationProfileResolutionTest` (instrumented) covers subject-profile resolution:
active-or-first fallback, stale-active-id fallback to first, empty-DB → null (legitimate empty state),
valid-active-id resolves that profile, and the no-persist read-only guarantee.

On a release APK, open the screen with no profiles at all (must show the empty state), with a profile
imported but never connected (must resolve the first profile — **not** the empty state), a canonical
profile, and a profile whose transport makes an enabled overlay inapplicable. Change a global setting,
return to the still-alive screen, and confirm the report refreshes. Confirm there is no
write/save/export action, and that opening the screen does not change the app's active profile.
