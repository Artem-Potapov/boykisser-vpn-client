# Boykisser VPN — Promoted Subscription

Promotes the "Boykisser VPN" subscription service in-app. The promo is
**partially silenced**: the deep-link / App Link entry points are dormant
(manifest intent-filters commented out), while the in-app surfaces are live —
gated by the remote `PromoGate` AND by the user not already being a customer.

## Activation state

| Layer | Status |
| --- | --- |
| `bkvpn://add` deep link + `https://boykiss3r.site/app/add` App Link | **Dormant.** Both `<intent-filter>`s on `BoykisserLinkActivity` are commented out in `AndroidManifest.xml` (`TEMP` markers) — restore by uncommenting. The activity stays `android:exported="true"` (`singleTop`), but with no filters nothing can launch it implicitly. |
| Home banner / Subscriptions promo row / nag-screen entry | **Live, remote-gated.** Rendered when `PromoGate.resolve(...) == true && !PromotedSubscription.hasValidSubscription(...)`. |
| `MainActivity.maybeAddBoykisserSub` (inbound add path) | **Active** — called from both `onCreate` and `onNewIntent`, not commented out. |
| User-driven add | Nag-screen paste-and-submit → `EXTRA_ADD_BOYKISSER_SUB` → MainActivity confirmation dialog. |

## Surfaces

Visibility rule, identical at both call sites:

```kotlin
showPromo = promoGate == true && !PromotedSubscription.hasValidSubscription(subscriptions)
```

`promoGate` is a `rememberSaveable` nullable Boolean resolved once via
`LaunchedEffect { PromoGate.resolve(context, LocalDate.now()) }` — the probe
result survives rotation and is re-resolved on a fresh launch.

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

A deliberate behavioral clone of the name-theft bomb
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

- **Live — nag screen.** `BoykisserInfoActivity`'s paste-and-submit validates
  the pasted URL via `BoykisserCallback.validate` and, on approval, starts
  `MainActivity` (`FLAG_ACTIVITY_CLEAR_TOP`) with `EXTRA_ADD_BOYKISSER_SUB`.
- **Dormant — link callback.** `BoykisserLinkActivity` handles
  `bkvpn://add?sub=<url-encoded sub URL>` and
  `https://boykiss3r.site/app/add?sub=<...>`; it validates the same way
  (rejects with an "Invalid domain" toast), routes the URL identically, shows
  no UI of its own, and finishes. Unreachable while its intent-filters stay
  commented out.

Consumer: `MainActivity.maybeAddBoykisserSub` (called from `onCreate` and
`onNewIntent`) strips the extra first (single-shot — rotation/recreate cannot
re-trigger), **re-validates** with `isApprovedLink` (MainActivity is an
exported launcher, so the extra could be forged by another app), and sets
`pendingBoykisserUrl`, which renders a confirmation `AlertDialog`. Only the
confirm button performs
`viewModel.addSubscription(name = <brand>, url, refreshAfterInsert = true)` on
the durable `viewModelScope`; cancel / outside-tap discards without adding.
Keeping the confirmation in MainActivity ensures a forged Intent sent straight
to MainActivity cannot add a subscription without user consent. The promo
surfaces disappear only once the resulting fetch succeeds (`lastFetchedAt`
set).

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

- JVM: `PromoGateTest` (HTTP-status → signal mapping and the `evaluate`
  semantics, incl. FIRE preserving the lease and REARM re-enabling the date
  gate), `PromoGateRepositoryTest` (lease persistence),
  `PromotedSubscriptionTest`, `BoykisserCallbackTest`.
- Manual, in-app (works with the filters still dormant): get the gate to
  resolve `true` (a live 418, or an inconclusive probe on/after 2026-08-01
  with no disarm lease) → banner or promo row → nag screen → paste an
  approved-domain URL → the MainActivity confirmation dialog appears.
- Manual deep-link smoke test — **only after uncommenting the intent-filters**
  in `AndroidManifest.xml`; while they are commented out the VIEW intent will
  not resolve to the app:

  ```
  adb shell am start -a android.intent.action.VIEW -d "bkvpn://add?sub=https%3A%2F%2Fx.boykiss3r.site%2Fsub"
  ```
