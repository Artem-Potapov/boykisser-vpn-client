# Boykisser VPN — Promoted Subscription

The promoted "Boykisser VPN" subscription service has shut down. Its in-app
promotion is **dormant**: the Home banner and Subscriptions promo row are hidden,
and neither screen runs the promo's HTTP probes. The UI and gate code are retained.
The deep-link / App Link intent-filters remain commented out.

## Activation state

| Layer | Status |
| --- | --- |
| `bkvpn://add` deep link + `https://boykiss3r.site/app/add` App Link | **Dormant.** Both `<intent-filter>`s on `BoykisserLinkActivity` are commented out in `AndroidManifest.xml` (`TEMP` markers). The activity stays `android:exported="true"` (`singleTop`): it can be launched explicitly but does not resolve these implicit links. |
| Home banner / Subscriptions promo row / nag-screen entry | **Dormant.** Both screens check `PromoGate.PROMOTION_ENABLED = false` before running the gate or showing a promo. |
| `MainActivity.maybeAddBoykisserSub` (inbound add path) | **Unchanged.** Called from `onCreate` and `onNewIntent`; an approved inbound URL still requires user confirmation. |
| Nag-screen paste-and-submit | Retained, but the nag screen has no active entry point. Normal subscription add is unaffected. |

## Surfaces

Visibility rule, identical at both call sites:

```kotlin
showPromo = PromoGate.canShow(promoGate) && !PromotedSubscription.hasValidSubscription(subscriptions)
```

`PromoGate.canShow` requires both `PROMOTION_ENABLED` and a positive verdict,
so even a previously saved `true` cannot render the promo. Both screens also
check `PROMOTION_ENABLED` before composing the effect that would call
`PromoGate.resolve`. While disabled, neither screen contacts the primary or
fallback promo host or reads or changes the promo lease. The separate
name-theft warning keeps its own gate.

- **Home banner** (`MainActivity` → `BoykisserBanner`): magenta, dismissible.
  `bannerDismissed` is `rememberSaveable`, so a dismissal is session-scoped and
  the banner returns on the next launch. Rendered when
  `showPromo && !bannerDismissed`.
- **Subscriptions row** (`SubscriptionsActivity` → `BoykisserPromoRow`): magenta
  "Recommended" row at the top of the subscription list, rendered when
  `showPromo`.
- **Nag screen** (`BoykisserInfoActivity`, `exported="false"`): the onboarding
  walkthrough both surfaces open (`onOpenBoykisserInfo`); reachable only through
  them, so it inherits the same gating. See
  [`boykisser-nag-screen.md`](boykisser-nag-screen.md).

## Remote gate (`subs/PromoGate.kt`)

The following behavior is retained but inactive while `PROMOTION_ENABLED` is false.

The gate is a deliberate behavioral clone of the name-theft bomb
(`docs/features/name-theft-warning.md`) — same signal semantics, date fallback,
and lease behavior, with its own endpoints and lease key. The duplication is
intentional; do **not** extract a shared helper.

- **Probe:** GET `https://boykiss3r.site/dowepromote`; only if that yields no
  verdict (timeout / connection error / unlisted status → UNKNOWN) it probes
  the fallback `https://somenewsteps.space/dowepromote`. 10 s budget per host.
- **Signals:** `418` → FIRE (show this launch; lease *preserved* — a
  single-launch override, nothing persisted), `409` → DISARM (hide + persist
  the lease), `451` → REARM (clear the lease, then date-gate), anything else →
  UNKNOWN (lease preserved: disarmed stays hidden, otherwise the date gate
  decides).
- **Date gate:** an armed gate shows on/after `PROMO_CUTOFF_DATE` =
  **2026-08-01** (device-local, inclusive).
- **Lease:** `xray_prefs` / `promote_disarmed` via `PromoGateRepository`
  (independent of the bomb's `name_theft_disarmed`).

Note the promo is therefore *not* simply "shown until the user has a valid
subscription": the gate can keep it hidden with zero subscriptions (a 409
disarm lease, or an armed-but-pre-cutoff date with an inconclusive probe).

## Approval policy (`subs/PromotedSubscription.kt`)

Pure (no Android deps), unit-tested. The approved-domain list —
`somenewsteps.space`, `boykisser-keys.top`, `boykiss3r.site` — is **private**;
the public surface is:

- `isApprovedLink(url)`: http/https + host equals an approved domain or is a
  dot-suffix subdomain of one (rejects suffix/prefix spoofing and malformed
  URLs).
- `hasValidSubscription(subs)`: any approved-domain sub with
  `lastFetchedAt != null` (i.e. fetched over HTTP without error at least once).

## Add path

Two producers hand a URL to the same consumer:

- **Dormant — nag screen.** `BoykisserInfoActivity`'s paste-and-submit validates
  the pasted URL via `BoykisserCallback.validate` and, on approval, starts
  `MainActivity` (`FLAG_ACTIVITY_CLEAR_TOP`) with `EXTRA_ADD_BOYKISSER_SUB`.
- **Implicit links dormant — link callback.** `BoykisserLinkActivity` handles
  `bkvpn://add?sub=<url-encoded sub URL>` and
  `https://boykiss3r.site/app/add?sub=<...>`; it validates the same way
  (rejects with an "Invalid domain" toast), routes the URL identically, shows
  no UI of its own, and finishes. Its implicit routes remain disabled.

Consumer: `MainActivity.maybeAddBoykisserSub` (called from `onCreate` and
`onNewIntent`) strips the extra first (single-shot — rotation/recreate cannot
re-trigger), **re-validates** with `isApprovedLink` (MainActivity is an
exported launcher, so the extra could be forged by another app), and sets
`pendingBoykisserUrl`, which renders a confirmation `AlertDialog`. Only the
confirm button performs
`viewModel.addSubscription(name = <brand>, url, refreshAfterInsert = true)` on
the durable `viewModelScope`; cancel / outside-tap discards without adding.
Keeping the confirmation in MainActivity ensures a forged Intent sent straight
to MainActivity cannot add a subscription without user consent. If the promo
is re-enabled, the existing-customer check hides it once an approved-domain
subscription has fetched successfully (`lastFetchedAt` set).

## App Link verification (owner action required)

Applies once the App Link intent-filter is restored. The `https://` App Link
auto-verifies only when the site hosts
`https://boykiss3r.site/.well-known/assetlinks.json` listing this app's package
and the **release** signing SHA-256:

1. Get the fingerprint: `./gradlew :app:signingReport` (or
   `keytool -list -v -keystore <release.jks> -alias <alias>`).
2. Copy `app/src/main/assets/boykisser-assetlinks.json`, replace
   `REPLACE_WITH_RELEASE_SIGNING_SHA256` with the SHA-256, and host it at the
   `.well-known` path above.
3. The site's "Add to app" button and the Telegram bot reply should link to
   `https://boykiss3r.site/app/add?sub=<encoded>` (or
   `bkvpn://add?sub=<encoded>`).

Release signing **is configured**: `app/build.gradle.kts` loads the gitignored
`key.properties` into `signingConfigs("release")` (guarded — an absent file
just leaves the config empty), and the release build type uses it. The
credentials are machine-local, so run `signingReport` where `key.properties`
exists. The `bkvpn://` custom scheme needs none of this website/signing setup.

## Testing

- JVM: `PromoGateTest` (a saved positive verdict cannot show the retired
  promo; retained status mapping and lease/date decisions),
  `PromoGateRepositoryTest`, `PromotedSubscriptionTest`, `BoykisserCallbackTest`.
- Manual dormancy smoke check: open Home and Subscriptions and confirm that
  neither promo surface appears.
- Manual deep-link smoke test — **only after uncommenting the intent-filters**
  in `AndroidManifest.xml`; while they are commented out the VIEW intent will
  not resolve to the app:

  ```
  adb shell am start -a android.intent.action.VIEW -d "bkvpn://add?sub=https%3A%2F%2Fx.boykiss3r.site%2Fsub"
  ```
