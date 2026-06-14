# Boykisser VPN — Promoted Subscription

Promotes "Boykisser VPN" until the user has a valid subscription on an approved domain.

## Surfaces
- **Home banner** (`MainActivity` → `BoykisserBanner`): magenta, dismissible (session-scoped
  via `rememberSaveable`; returns on next launch). Shown while
  `!PromotedSubscription.hasValidSubscription(...)`.
- **Subscriptions row** (`SubscriptionsActivity` → `BoykisserPromoRow`): magenta
  "VPN by Boykisser (Recommended)" at the top of the list, same visibility rule.
- **Nag screen** (`BoykisserInfoActivity`): 4-step onboarding roadmap that walks the user from
  "no key" to a validated paste-and-submit. See [`boykisser-nag-screen.md`](boykisser-nag-screen.md).

## Detection

**Remote gate (`subs/PromoGate.kt`).** Visibility is now also gated by a remote
"promo gate" — a behavioral clone of the name-theft bomb
(`docs/features/name-theft-warning.md`). Probed on launch:
`https://boykiss3r.site/dowepromote`, fallback `https://somenewsteps.space/dowepromote`
(10s/host). `418`→show always; `409`→hide + persist `promote_disarmed` lease;
`451`→re-arm (date-gated); else/timeout→lease, then date gate (show on/after
2026-08-01). Final visibility: `gateResult && !hasValidSubscription(...)`. Lease
in `xray_prefs/promote_disarmed` (independent of the bomb). The `bkvpn://` and
`https://boykiss3r.site/app/add` manifest filters remain dormant; the promo
works through the in-app nag screen.

`PromotedSubscription` (pure, unit-tested):
- `approvedDomains` = `somenewsteps.space`, `boykisser-keys.top`, `boykiss3r.site`.
- `isApprovedLink(url)`: http/https + host equals or is a dot-suffix subdomain of an approved
  domain (rejects suffix/prefix spoofing).
- `hasValidSubscription(subs)`: any approved-domain sub with `lastFetchedAt != null`
  (i.e. fetched over HTTP without error at least once).

## Callback (add a subscription)
`BoykisserLinkActivity` (exported) handles two URI forms:
- `bkvpn://add?sub=<url-encoded full sub URL>` — works immediately, no setup.
- `https://boykiss3r.site/app/add?sub=<...>` — verified Android App Link.

`BoykisserLinkActivity` validates the `sub` payload via `BoykisserCallback.validate` (rejects
non-approved domains with an "Invalid domain" toast) and, if valid, routes the URL to
`MainActivity` via the `EXTRA_ADD_BOYKISSER_SUB` extra (no UI of its own). `MainActivity`
re-validates the domain (defense in depth — it is an exported launcher, so the extra could be
forged) and shows the single confirmation dialog before performing
`addSubscription(refreshAfterInsert = true)` on its durable `viewModelScope`. Keeping the
confirmation in `MainActivity` ensures a forged Intent sent directly to `MainActivity` cannot
add a subscription without user consent. The promo surfaces disappear only once the resulting
fetch succeeds (`lastFetchedAt` is set).

## App Link verification (owner action required)
The `https://` App Link auto-verifies only once the site hosts
`https://boykiss3r.site/.well-known/assetlinks.json` listing this app's package and the
**release** signing SHA-256. Steps:
1. Get the fingerprint: `./gradlew :app:signingReport` (or
   `keytool -list -v -keystore <release.jks> -alias <alias>`).
2. Copy `app/src/main/assets/boykisser-assetlinks.json`, replace
   `REPLACE_WITH_RELEASE_SIGNING_SHA256` with the SHA-256, and host it at the
   `.well-known` path above.
3. The site's "Add to app" button and the Telegram bot reply should link to
   `https://boykiss3r.site/app/add?sub=<encoded>` (or `bkvpn://add?sub=<encoded>`).

The repo currently has no release signing config, so this is gated on a release key existing.
The `bkvpn://` custom scheme works without any of the above.

## Testing
- JVM: `PromotedSubscriptionTest`, `BoykisserCallbackTest`.
- Manual deep-link smoke test:
  `adb shell am start -a android.intent.action.VIEW -d "bkvpn://add?sub=https%3A%2F%2Fx.boykiss3r.site%2Fsub"`
