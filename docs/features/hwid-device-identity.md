# HWID / Device Identity

Maintainer reference for subscription-fetch device identity: Android-Happ parity headers, the
minted 16-hex HWID, the Privacy settings screen, and the pure/impure split under `privacy/`.

Design source: [`docs/superpowers/specs/2026-07-16-hwid-device-identity-design.md`](../superpowers/specs/2026-07-16-hwid-device-identity-design.md)
(working artifact; not a committed maintainer doc).

## Why this exists

Panels that enforce **HWID device limits** (Remnawave and clones) or **filter by client app** via
User-Agent reject or empty-body a fetch that looks unlike Happ. Without a stable `x-hwid` + device
headers (and optional Happ-like UA), those failures look like opaque HTTP 404 / empty parse with no
actionable guidance. This feature gives every subscription refresh the same wire shape Android Happ
sends, plus a Privacy UI to view / copy / reset the ID and spoof identity.

## Android-Happ parity target

Parity is **Android Happ 3.26.3**, not Windows Happ and not the Remnawave docs' shorter header list.

| Wire item | Shape |
|---|---|
| `x-hwid` | 16-char lowercase hex (Android-ID / SSAID shape); **minted random**, never `Settings.Secure.ANDROID_ID` |
| `x-device-locale` | Lowercase language code (`en`, `ru`) from `Locale.getDefault().language` — builder force-lowercases |
| `x-device-os` | `Android` or `iOS` (or custom) |
| `x-ver-os` | Plain OS release string (e.g. `16`, `18.5`) |
| `x-device-model` | Free-form model string |
| **No `x-app-version`** | Windows-only; Android Happ omits it — we never emit it |
| User-Agent (Happ-like) | `Happ/3.26.3/<os>/<build>` where `<build>` is the unsigned-decimal HWID hash |

Five `x-*` headers at most. Headers ride on **subscription fetches only** (http and https); Xray-core
tunnel traffic is untouched.

## Pure / impure architecture

Decision logic is pure JVM units; impurity is limited to prefs + the fetcher's `setRequestProperty`
loop + the Compose settings shell.

| Unit | Pure? | Role |
|---|---|---|
| `DeviceIdentitySettings` / `IdentityMode` / `UserAgentMode` | data | Immutable snapshot |
| `DeviceIdentityHeaders` | **pure** | Builds the `x-*` map; single source of truth for wire **and** settings preview; `sanitize` at the trust boundary |
| `SpoofIdentities` | **pure** | Curated plausible `(version, model)` tables + HWID-hash Auto derivation |
| `UserAgentBuilder` | **pure** | `DEFAULT` passthrough vs `Happ/3.26.3/<os>/<build>`; OS mirrors identity |
| `HwidRejectionDetector` | **pure** | Remnawave response-header → `MAX_DEVICES` / `NOT_SUPPORTED` |
| `UaHint` | **pure** | Suggest Happ-like UA on 403 or 2xx-with-zero-parsed when UA isn't already Happ-like |
| `DeviceIdentityRepository` | **impure** | `xray_prefs` (`hwid_*` keys); mint / load / save / `resetHwid` |

## Privacy ladder (four tiers)

Each step up adds a known, enumerable set — never a half-built map:

| Tier | Setting | Headers sent |
|---|---|---|
| 0 | `sendHwid == false` | **Empty map** (nothing device-related) |
| 1 | `IdentityMode.NONE` | `x-hwid` only |
| 2 | `REAL_DEVICE` / `ANDROID` / `IPHONE` | `x-hwid` + `x-device-os` + `x-ver-os` + `x-device-model` + `x-device-locale` |
| 2 (custom) | `customEnabled == true` | `x-hwid` + whichever of the four custom fields are non-blank (blank = omit that header); mode/pins ignored |

`userAgentMode` is **independent** of `sendHwid`: Happ-like UA works with HWID off and vice-versa.

## Spoof tables (current)

`SpoofIdentities` keeps pair-plausible tables (no "Pixel 9 on Android 9"). Pinning one axis filters the
pool; Auto indexes by `seed(hwid)`. Notable corrected pair: **Huawei Android 11 → `JAD-LX9`**
(P50 Pro), alongside `ELS-NX9` on 11 and `VOG-L29` on 9.

Android versions offered: `9, 11, 13, 14, 15, 16`. Model families: `pixel`, `samsung`, `xiaomi`,
`huawei`. iOS versions: `16.7, 17.6, 18.5, 26.0, 26.1` with curated iPhone models.

One **stable** 16-hex HWID across all modes (including iPhone spoof) — switching modes must not burn
a fresh panel device slot.

## Coordinator chokepoint

Every refresh flows through
[`SubscriptionRefreshCoordinator.runRefresh`](../../app/src/main/java/com/justme/xtls_core_proxy/subs/SubscriptionRefreshCoordinator.kt):

1. `DeviceIdentityRepository.load(context)`
2. `DeviceIdentityHeaders.build(settings, Build.VERSION.RELEASE, Build.MODEL, Locale.getDefault().language)`
3. `UserAgentBuilder.build(settings, defaultUserAgent)` → effective default UA
4. Per-subscription `userAgentOverride` still wins over that default (existing fetcher `?:`)
5. `SubscriptionFetcher.fetch(..., identityHeaders)` attaches the map
6. On failure: `HwidRejectionDetector.detect` → specific device-limit / enable-HWID strings; else
   optional `UaHint` append on 403
7. On success with zero parsed servers: `UaHint` may set a Happ-UA warning via `markFetchResult`

`VpnViewModel` is not on the header path; it already passes `defaultUserAgent` into the coordinator.

## Settings UI (`HwidSettingsActivity`)

Hub path: **Settings → Privacy → Device identity (HWID)** (real row, release-visible — not a debug
placeholder). See [`settings-hub.md`](settings-hub.md).

Top → bottom:

1. **Send device ID (HWID)** master switch — OFF disables identity/custom controls at 38% alpha; UA
   block stays enabled; preview shows "Nothing sent (HWID off)".
2. **Your HWID** — read-only 16-hex; **Copy** / **Reset** (confirm dialog). Reset re-mints and
   re-rolls Auto spoof + Happ-like UA build.
3. **Custom override** — four free-text fields; blank omits that header.
4. **Identity** — Real device · Android · iPhone · None, with dependent version/model pickers for
   Android/iPhone.
5. **Live preview** — same `DeviceIdentityHeaders.build` / `UserAgentBuilder.build` as the fetch
   path (cannot drift).
6. **User-Agent** — Default · Happ-like, with UA preview line.

**Autosave:** every control change calls `DeviceIdentityRepository.save` immediately — no Save
button; back is plain `finish()`. Prefs-only (not a `TuningSettings` connection snapshot).

**Sensitive clipboard:** Copy marks the clip with `ClipDescription.EXTRA_IS_SENSITIVE` on API 33+
and suppresses the duplicate toast on those versions (same pattern as profile share-link copy).

## Security

- **Header injection:** every header *value* passes `DeviceIdentityHeaders.sanitize` — trim, strip
  CR/LF and control chars (`code < 0x20` or `0x7F`), length-cap 128 — at the builder trust boundary.
- **Not the real Android ID:** minted `SecureRandom` 16-hex only.
- **Locale normalization:** `putLocale` sanitizes then `.lowercase()` so custom or real language
  codes match Android Happ's lowercase shape.
- Out of scope by design: per-sub HWID, changing the base DEFAULT UA / override mechanism, reacting
  to `x-hwid-active`, transmitting HWID on non-subscription requests.

## Components

| File | Role |
|---|---|
| [`privacy/DeviceIdentitySettings.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/privacy/DeviceIdentitySettings.kt) | Snapshot + enums |
| [`privacy/DeviceIdentityRepository.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/privacy/DeviceIdentityRepository.kt) | Prefs mint/load/save/reset |
| [`privacy/DeviceIdentityHeaders.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/privacy/DeviceIdentityHeaders.kt) | Pure header builder + `sanitize` |
| [`privacy/SpoofIdentities.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/privacy/SpoofIdentities.kt) | Spoof tables + Auto seed |
| [`privacy/UserAgentBuilder.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/privacy/UserAgentBuilder.kt) | Effective default UA |
| [`privacy/HwidRejection.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/privacy/HwidRejection.kt) | Panel rejection detector |
| [`privacy/UaHint.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/privacy/UaHint.kt) | App-filter hint decision |
| [`privacy/HwidSettingsActivity.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/privacy/HwidSettingsActivity.kt) | Privacy settings UI |
| [`subs/SubscriptionRefreshCoordinator.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/subs/SubscriptionRefreshCoordinator.kt) | Load → build → fetch → enrich errors |
| [`subs/SubscriptionFetcher.kt`](../../app/src/main/java/com/justme/xtls_core_proxy/subs/SubscriptionFetcher.kt) | `identityHeaders` → `setRequestProperty` |

## Testing

Pure JUnit4 under `app/src/test/.../privacy/` covers headers (tiers, no `x-app-version`, locale
lowercase, injection sanitize), spoof determinism / pinning (incl. `JAD-LX9`), UA OS mirroring,
rejection flags, and UA hint symptoms. Repository round-trip uses the prefs test double.

**Manual / on-device** (not in CI): Privacy row in release; Copy/Reset/preview ladder; Remnawave
device-list match when a test panel is available. **Limitation:** without a connected device,
on-device smoke and Remnawave panel verification cannot be run — unit/release gates still apply.
