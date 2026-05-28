# Play Console policy declarations

Android Studio's inspection flags three Google Play policy items for this app
(`ForegroundServicesPolicy`, `VpnServicePolicy`, `PackageVisibilityPolicy`). These are **not code
bugs** — the manifest is already technically compliant. They are reminders that each capability
requires a written justification in the Play Console **Policy → App content** declarations at
release time. This file holds ready-to-paste justifications so the submission isn't blocked.

Manifest evidence each declaration relies on lives in
[`app/src/main/AndroidManifest.xml`](../app/src/main/AndroidManifest.xml).

---

## 1. Special-use foreground service (`ForegroundServicesPolicy`)

**Manifest:** `XrayVpnService` declares `android:foregroundServiceType="specialUse"` with
`<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
android:value="vpn_tunnel_core_proxy" />` and the `FOREGROUND_SERVICE_SPECIAL_USE` permission.

**Justification:**
> The foreground service hosts the Xray-core proxy engine that runs the user's VPN tunnel. The
> tunnel must stay alive for the entire duration of a user-initiated connection — it carries all of
> the device's routed traffic through an Android `VpnService` TUN interface, so it cannot be
> deferred, batched, or run as a background/expedited job without dropping the user's connection.
> No existing standard FGS type (`dataSync`, `mediaPlayback`, `location`, etc.) describes a
> persistent user-controlled VPN tunnel core, so `specialUse` with the subtype
> `vpn_tunnel_core_proxy` is the correct classification. The service is started only in response to
> an explicit user action (tapping Connect in-app or the Quick Settings tile) and is stopped by an
> equally explicit user action.

## 2. VpnService usage (`VpnServicePolicy`)

**Manifest:** `XrayVpnService` extends `android.net.VpnService`, is guarded by
`android.permission.BIND_VPN_SERVICE`, and registers the `android.net.VpnService` intent filter.

**Justification:**
> The app is a VPN client. Its core and sole purpose is to let the user route device traffic through
> their own XTLS/Xray (VLESS/REALITY) server. The `VpnService` API is used to establish the TUN
> interface that the bundled Xray-core engine reads/writes. This is the app's primary, user-facing
> functionality — not a secondary or hidden use — and the VPN is only ever active after explicit
> user consent via the system `VpnService.prepare()` dialog.

## 3. All-apps visibility (`PackageVisibilityPolicy` / `QUERY_ALL_PACKAGES`)

**Manifest:** `android.permission.QUERY_ALL_PACKAGES` (annotated `tools:ignore="QueryAllPermission"`).

**Justification:**
> `QUERY_ALL_PACKAGES` is required by the per-app picker used by two user-facing features: the
> **kill-switch** (kill-on-foreground) and **split-tunnel** configuration screens. Both let the user
> choose, from a list of all installed apps, which apps trigger tunnel teardown or are excluded from
> routing (see `apps/AppPickerActivity` and `apps/InstalledAppsLoader`). Because the user may pick
> *any* installed app, the set of target packages is not known in advance, so a static `<queries>`
> filter cannot enumerate them. The permission is used exclusively to populate this user-driven
> picker; the app does not collect, transmit, or sell the installed-app list.

---

## Notes
- These inspection IDs are informational and do **not** fail `:app:lintDebug` or the build.
- Keep these paragraphs in sync with the manifest if the service type, subtype, or permission set
  changes.
