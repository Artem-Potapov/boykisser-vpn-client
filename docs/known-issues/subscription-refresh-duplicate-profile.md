# Subscription refresh duplicate profile (keep-id + re-insert)

**Status: CONFIRMED IN CODE, not yet fixed.** Device fingerprint is a 30-second check (see [Confirm on device](#confirm-on-device)); the mechanism does not need a device to be understood. Do not re-investigate from scratch — this page is the investigation record for whoever implements the fix.

Known-issue record of a real Room-row clone: two identical-looking copies of one subscription server. A later subscription update can remove the clone, which is what made it look "local" and intermittent.

This is **not** a subscription-management feature doc. Fetch/parse/UI is still a known `docs/features/` gap. This page lives under [`docs/known-issues/`](README.md) so the keep-id clone is not forgotten, and so a fixer does not have to re-derive it.

Investigated 2026-09-30 on branch `auto-failover-engine-migration-development` @ `62c602b`. The defect is **pre-existing on `main`**: `git diff --name-only main..HEAD` shows no changes under `subs/` or `db/`. Introduced in `89ef7d3` ("feat: add HTTP subscriptions with grouped HAPP-style profile UI", 2026-05-07) and never touched since.

## Symptom

- Sometimes the home list shows **2 identical copies of one config** (same display name; config is never shown on the row).
- It is **local**: a subsequent subscription update can remove the clone.
- Maintainer initially suspected the QS tile. The tile does **not** insert a profile row. It can **arm** the keeper (see [QS tile is a trigger, not an inserter](#qs-tile-is-a-trigger-not-an-inserter)).

## Verdict (read this first)

**Root cause is `ProfileDao.replaceProfilesForSubscription`.**

When a refresh runs while the stored active profile belongs to that subscription:

1. `DELETE FROM profiles WHERE subscriptionId = :subId AND id != :keepId` — the active row survives.
2. `insertAll` of the **entire** freshly parsed list — including a new row for the same server the kept row already is.

Result: two Room rows, same `subscriptionId`, same `name`, same stored `config`, different `id`s. Compose keys are `"p-${id}"`, so this is two real items, not a double-render.

The keep exists so `ActiveProfileRepository`'s stored id does not dangle across a wipe. The implementation keeps the row **and** inserts its twin. There is no identity match (no config hash, share-link, UUID, host:port). The only "this row already existed" notion in the refresh path is that single spared `id`.

## The duplicating code

`db/ProfileDao.kt`:

```kotlin
@Query("DELETE FROM profiles WHERE subscriptionId = :subId")
suspend fun deleteForSub(subId: Long)

@Query("DELETE FROM profiles WHERE subscriptionId = :subId AND id != :keepId")
suspend fun deleteForSubExceptId(subId: Long, keepId: Long)

@Transaction
suspend fun replaceProfilesForSubscription(
    subId: Long,
    keepProfileId: Long?,
    newProfiles: List<Profile>
) {
    if (keepProfileId != null) {
        deleteForSubExceptId(subId, keepProfileId)
    } else {
        deleteForSub(subId)
    }
    if (newProfiles.isNotEmpty()) {
        insertAll(newProfiles.map { it.copy(subscriptionId = subId) })
    }
}
```

Sole production caller, `subs/SubscriptionRefreshCoordinator.kt:87-104`:

```kotlin
val newProfiles = outcome.parsed.map { p ->
    Profile(
        name = p.displayName,
        config = p.config,
        subscriptionId = subId,
        sanitizedDns = p.sanitizedDns
    )  // id defaults to 0
}

val activeId = activeProfileIdProvider()
val keepProfileId = activeId
    ?.let { profileDao.getById(it) }
    ?.takeIf { it.subscriptionId == subId }
    ?.id

profileDao.replaceProfilesForSubscription(subId, keepProfileId, newProfiles)
```

`newProfiles` is never filtered against the kept row. Parsed `Profile` rows carry `id = 0`. Generated insert (`ProfileDao_Impl.kt`) is:

```sql
INSERT OR ABORT INTO `profiles` (`id`,`name`,`config`,`subscriptionId`,`sanitizedDns`)
VALUES (nullif(?, 0),?,?,?,?)
```

`nullif(?, 0)` mints a fresh autoincrement id. Schema (`db/Profile.kt`) has only a non-unique index on `subscriptionId`. No unique constraint on `name`, `config`, or `(subscriptionId, config)`. `@Insert` default `OnConflictStrategy.ABORT` never fires here.

Room wraps the replace body in one transaction (`ProfileDao_Impl.kt:99-105` `performInTransactionSuspending`).

## Why they look pixel-identical

`ProfileRow` (`MainActivity.kt:1051-1066`) renders:

- `profile.name`
- optional `profile_badge_dns_corrected` when `profile.sanitizedDns`
- `PingResultLine` keyed on `profile.id`

**The config JSON is never displayed.** Two rows with the same name and same `sanitizedDns` are visually indistinguishable except for the active dot (`isActive` matches on `profile.id`, `MainActivity.kt:1122-1128`). Ordering is `ORDER BY id ASC`, so the older kept row (the one with the dot, if still active) sorts first; the clone sits below it.

Name collision is forced, not luck. `SubscriptionBodyParser.uniquify` (`:225-230`) only runs **inside one parse**, appending `" (2)"`, `" (3)"` to repeated display names in that fetch. The kept row is **not a participant** in that parse, so it is never counted. The fresh copy is the first occurrence this pass → both get the bare name.

`uniquify` is case-insensitive on the key (`base.lowercase()`) but returns the original casing of the first-seen form. Documented by `SubscriptionBodyParserTest.deduplicatesNamesCaseInsensitive`. It does **not** dedupe configs. A payload that lists the same server twice yields `"X"` and `"X (2)"` — a **different** symptom. Use that as a discriminator (H2 below).

Stored config is produced by `toProfileStorageConfig` → `makeSecureDns` → `buildRuntimeConfig` (validate only) (`SubscriptionBodyParser.kt:41-45`). `makeSecureDns` (`ConfigBuilder.kt:520-590`) builds new `JSONObject`/`JSONArray` by ordered insertion and returns `root.toString()`. Two parses of the same input produce byte-identical stored JSON on the ordered-insert path. The kept row is never `@Update`d, so it can go **stale** relative to the twin if the provider rotated credentials — still the same name on screen.

## Why a later update can remove the clone (conditional)

This is the load-bearing clue and it is **not** "every refresh deletes the extra row".

| Refresh while… | `keepProfileId` | Rows after |
|---|---|---|
| No stored active id, or active row is not in this sub | `null` | `len(newProfiles)` — full `deleteForSub` then insert |
| Active profile **is** a row of this sub | that row's id | **`1 + len(newProfiles)`** — keep + insert all |
| Same as previous, second keep-path refresh | still that id | still `1 + N` (clone recreated, does **not** grow) |
| User connected to **another server in the same sub**, then refresh | the *new* server | clone of A gone; **B is now the duplicate** |
| In-app **Disconnect** (clears active id), then refresh | `null` | clone gone |

`ActiveProfileRepository.setActiveProfileId(..., null)` happens in exactly three places:

1. `VpnViewModel.signalStop` (`:575`) — in-app Disconnect / `disconnectAbandoningReconnect`
2. `VpnViewModel.deleteProfile` (`:410`) — only when deleting the active row
3. `ActiveProfileRepository.pickOrPersistActive` (`:58`) — only when the profiles table is empty

**Tile Stop does not clear the active id.** `XrayVpnTileService.sendStopIntent` sends `ACTION_STOP` only. `XrayVpnService.stopVpn` tears the tunnel down and does **not** call `setActiveProfileId`. After tile Off, the keeper is still armed. Next stale refresh of that sub can recreate the clone.

Service-side stops (`onRevoke`, give-up `stopSelf()`, OS kill) also leave the id set. A refresh the user believes is happening "while disconnected" can still take the keep branch.

So "a subscription update removes the clone" is true **only on a keep-less refresh**, not on every update. If they refresh while still connected to that server (or after tile Off without in-app Disconnect), H1 predicts the twin **stays / is reborn**. That asymmetry is the cheapest confirm/deny.

## Refresh triggers (all reach the same coordinator)

| Trigger | Site |
|---|---|
| App foreground, stale sweep | `MainActivity.onStart` `:369-372` → `VpnViewModel.refreshAllStaleSubscriptions` `:491-510` |
| Group refresh icon | `MainActivity.kt:784` → `refreshSubscription` |
| Subscriptions list refresh | `subs/SubscriptionsActivity.kt:116` |
| Editor "Save and refresh now" | `subs/SubscriptionEditActivity.kt` via `updateSubscription(refreshAfterUpdate = true)` |
| First fetch after add | `addSubscription(refreshAfterInsert = true)` — paste (`MainActivity.kt:460`), Boykisser confirm (`:303`) |

Stale rule (`VpnViewModel.kt:494-498`): `lastFetchedAt == null` always qualifies; otherwise `now - last >= effectiveIntervalHours() * 1h`. Default interval 12 h (`db/Subscription.kt:23`), overridable by user setting or provider `profile-update-interval` header (floor 1 h, `SubscriptionFetcher.kt:133-145`).

`onStart` reads `viewModel.subscriptions.value`, a `stateIn(..., WhileSubscribed(5_000), emptyList())`. On a **cold first launch** Compose may not have subscribed yet, so the list can still be empty and **no** auto-refresh runs. Later foregrounds are warm. That is one source of "sometimes".

First import (`refreshAfterInsert = true`) has no keep unless some other row of that new sub is already active (impossible on insert). Duplication starts at the **second** refresh that lands while a member of that sub is the stored active id.

`inFlight` de-dup (`SubscriptionRefreshCoordinator.kt:24, 34-46`) is per `subId`, check-then-set, not atomic. Two overlapping refreshes of the same sub still land on `1 + N`, not `1 + 2N`, because each replace is a full wipe-except-K + reinsert and Room serializes the `@Transaction`. Concurrent refreshes are **not** a clone multiplier.

## QS tile is a trigger, not an inserter

Complete tile click → start (`tile/XrayVpnTileService.kt`):

- Stop path: `ACTION_STOP`. **No DB write.**
- Start path: `ActiveProfileRepository.pickOrPersistActive` on IO, then `decideTileClick`.
- `Start(id)` → `ACTION_START` + `EXTRA_PROFILE_ID`. **No DB write.**
- `HandoffToMainActivity(id)` → `MainActivity` with `EXTRA_TILE_AUTOCONNECT` + `EXTRA_TILE_PROFILE_ID`. **No DB write.**
- CONNECTED label: `profileDao.getById` for the name. **Read only.**

`pickOrPersistActive` (`state/ActiveProfileRepository.kt:49-63`) persists the **id** in `vpn_prefs.active_profile_id`, never a profile copy. If nothing is stored (or the stored row is gone), it writes `ProfileDao.getFirst()` — **lowest id across manual AND subscription rows**. A cold start / empty stored id can therefore pin `activeId` to a subscription row without the user picking it. That is enough to arm the keep branch on the next refresh of that sub.

Two tile → duplication couplings:

1. Tile (or `VpnViewModel.connect`, or failover rotation at `XrayVpnService.kt:1451`) names a subscription-owned row as active.
2. Tile `HandoffToMainActivity` (consent / `POST_NOTIFICATIONS` missing) and tile long-press (`QS_TILE_PREFERENCES`, no extras) both open `MainActivity`, whose `onStart` runs `refreshAllStaleSubscriptions`.

`maybeAutoConnectFromTile` strips extras and calls `viewModel.connect` — no insert. No clipboard import on launch. Boykisser extra is not set by the tile.

Every `ProfileDao` **write** in `app/src/main`:

| Site | What |
|---|---|
| `VpnViewModel.addProfile` `:364` | manual, `subscriptionId` defaults `null` |
| `VpnViewModel.addRawProfile` `:375` | DEBUG-only, manual |
| `VpnViewModel.confirmDnsFixAndAdd` `:386` | manual, `sanitizedDns = true` |
| `replaceProfilesForSubscription` → `insertAll` `:70` | **only** path that sets `subscriptionId` |
| `dao.update` `:402` | per-server editor |
| `dao.delete` `:408` | profile menu Delete |
| `deleteForSub` / `deleteForSubExceptId` | refresh wipe |

Tile, `XrayVpnService` (start/revive/rotation DAO sites), `FailoverPoolResolver`, `ConfigSanitizationActivity`: **reads only**. There is no "save runtime config back to DB" path. `ConfigBuilder.buildRuntimeConfig` output is never persisted.

## UI cannot show the same row twice

`VpnViewModel.groupedProfiles` (`:170-177`):

```kotlin
val bySubId = allProfiles.groupBy { it.subscriptionId }
ProfilesView(
    manual = bySubId[null].orEmpty(),
    groups = subs.map { sub -> SubGroup(sub, bySubId[sub.id].orEmpty()) }
)
```

`groupBy` partitions: each row lands in exactly one bucket. Subscription ids are a PK, so no group is emitted twice. A row whose `subscriptionId` names a missing subscription is **dropped** (FK is `ON DELETE CASCADE`, `PRAGMA foreign_keys = ON`).

`LazyColumn` keys: `"h-manual"` / `"h-${sub.id}"` headers, `"p-${it.id}"` rows (`MainActivity.kt:741, 755, 774, 788`). Ids are a single-table autoincrement PK. A duplicate key would **throw** `IllegalArgumentException: Key was already used`, not silently twin.

The documented auto-ping grouped-view race (`VpnViewModel.kt:47-66, 179-188`) causes **omission** of subscription servers from the probe set, not list duplication. `MainScreen` renders `groupedProfiles`, not `autoPingProfiles`.

## Confirm on device (30 seconds)

1. Connect to a server **inside** a subscription (in-app Connect, so `active_profile_id` is that row).
2. Tap that group's refresh icon (do not need to wait for the 12 h stale window).
3. Expected under H1:
   - group header count (`MainActivity.kt:777`, `main_subscription_profile_count`) goes **N → N+1**
   - two rows, **byte-identical names** (not `"X"` / `"X (2)"`)
   - active dot on the **older** (lower-id) row
4. In-app **Disconnect** (not tile Off), then refresh again → count back to N.

SQL fingerprint (read-only):

```sql
SELECT subscriptionId, name, COUNT(*) c, COUNT(DISTINCT config) dc
FROM profiles
GROUP BY subscriptionId, name, config
HAVING c > 1;
```

Two rows, same non-null `subscriptionId`, same `config`, lower id = previously active → H1 confirmed.

**Falsifiers:**

- Pair is `"X"` / `"X (2)"` → H2 (payload listed the server twice), not H1.
- One row under "My profiles" (`subscriptionId IS NULL`) → manual duplicate; refresh cannot remove it.
- Two group **headers** with the same URL → H3 (subscription URL added twice).
- Refresh while **still connected** to that server **removes** the clone → H1 is wrong (H1 predicts the twin is reborn).

## Ranked hypotheses

| # | Hypothesis | Fits creation? | Fits "update removes clone"? | Status |
|---|---|---|---|---|
| **H1** | Keep-id + full re-insert | Yes, intermittent, self-limited to 2 | Yes, **iff** the next refresh is keep-less | **Mechanism proven in code.** Four independent investigators + coordinator re-read of DAO, generated SQL, coordinator, tile, list grouping. Device fingerprint still the 30 s check above. |
| H2 | Payload lists the same server twice | Two rows, but names `"X"` / `"X (2)"` | **No** — each refresh re-inserts both | Real parser gap (`parseBody` has no config-level dedup). Wrong symptom. |
| H3 | Same subscription URL added twice | Two **groups**, every server copied | **No** — refresh is per `subId` | `addSubscription` has no URL uniqueness (`VpnViewModel.kt:415-442`). Boykisser confirm double-tap (`MainActivity.kt:302-310`) is a concrete path (`inFlight` is keyed by `subId`, two rows have different ids). |
| H4 | Manual double-add / copy-link then paste | Two identical **manual** rows, or manual + sub member | **No** — `DELETE ... WHERE subscriptionId = :subId` never matches `NULL` | Zero add-path dedup. `PasteAddDialog.kt:62` unguarded `TextButton`. Permanent until user deletes. |
| H5 | UI double-render of one row | — | — | **Ruled out** by `groupBy` + id keys. |
| H6 | Tile / failover / session restore inserts a profile | — | — | **Ruled out.** Writes listed above. |
| H7 | Migration 2→3 splits a row | — | — | **Ruled out.** `ALTER TABLE ... ADD COLUMN sanitizedDns` (`AppDatabase.kt:23-29`) cannot duplicate rows. |
| H8 | Editor result `profileId == -1L` treated as add | — | — | **Ruled out** in production. Only launcher (`MainActivity.kt:270-279`) always supplies a real id. |

## Secondary issues (same defect; fold into the fix)

1. **The kept row is never `@Update`d.** `ProfileDao.update` exists (`:46-47`) but the refresh path never calls it. After a keep-path refresh, the row `ActiveProfileRepository` names — the one the tunnel is actually running, and the one `FailoverPoolResolver` puts in the rotation pool — holds a **stale config** indefinitely, while its fresh twin holds the current one. If a provider rotates a UUID / host / REALITY key, the connected row silently keeps the old config. Independent of the visible duplicate, still a correctness bug.

2. **The clone pollutes failover and connect-fastest.** `FailoverPoolResolver.resolve` returns `dao.getBySubscriptionId` (the whole subscription). Pool contains the same server twice. `FailoverDecision.nextCandidate` (`:26-27`) is `firstOrNull { it.id != currentId && it.id !in recentlyFailed }` and can rotate onto the twin. `FastestPick` / `pingTestGroup` probe the same server twice. Feature-doc and KDoc used to say ids always churn on refresh — that is **false on the keep branch** (the kept id survives). Both now point here.

3. **Zero test coverage of this path.** `replaceProfilesForSubscription` / `deleteForSubExceptId` appear in tests only as `throw UnsupportedOperationException("not used")` in `FailoverPoolResolverDispatchTest.kt:38-42`. No JVM or instrumented test asserts row count after a keep-path refresh. `SubscriptionIntegrationTest` only parses a fixture body.

4. **Stale-id editor save.** `MainActivity.kt:177` looks up `profiles.value.firstOrNull { it.id == profileId }`. If a keep-less refresh re-created the row while the editor was open, the id is gone → save is a silent no-op. Separate from the clone, same replace transaction.

## For the fixer (do not re-investigate)

**Do not drop `keepProfileId`.** The original intent (commit `89ef7d3`) is load-bearing: the connected profile's Room id is stored in `ActiveProfileRepository` and read by the tile, UI active-dot, failover rotation, always-on `resolveActiveAndStart`, and `deleteProfile`. A full wipe while connected would dangle that id (active-dot gone, tile `getById` falls back, next start may `pickOrPersistActive` onto `getFirst()`).

**Recommended shape: UPDATE the kept row from the matching parsed entry; insert the rest.**

Sketch (illustrative, not a patch):

```kotlin
@Transaction
suspend fun replaceProfilesForSubscription(
    subId: Long,
    keepProfileId: Long?,
    newProfiles: List<Profile>
) {
    if (keepProfileId == null) {
        deleteForSub(subId)
        if (newProfiles.isNotEmpty()) {
            insertAll(newProfiles.map { it.copy(subscriptionId = subId) })
        }
        return
    }
    val kept = getById(keepProfileId)
    val matchIndex = kept?.let { k ->
        newProfiles.indexOfFirst { incoming -> incoming.config == k.config }
    } ?: -1
    deleteForSubExceptId(subId, keepProfileId)
    if (matchIndex >= 0) {
        val incoming = newProfiles[matchIndex]
        update(
            kept.copy(
                name = incoming.name,
                config = incoming.config,
                sanitizedDns = incoming.sanitizedDns,
                subscriptionId = subId,
            )
        )
        val rest = newProfiles.filterIndexed { i, _ -> i != matchIndex }
        if (rest.isNotEmpty()) insertAll(rest.map { it.copy(subscriptionId = subId) })
    } else {
        // Provider dropped this server (or credentials rotated so config no longer equals).
        // Keep the connected row as-is; insert the new list around it. Not a clone.
        if (newProfiles.isNotEmpty()) {
            insertAll(newProfiles.map { it.copy(subscriptionId = subId) })
        }
    }
}
```

Matching by **stored config string** is the right first key: parse is deterministic, so an unchanged server hits. When the provider rotates credentials, config equality fails → we keep the old row (tunnel still has the old config in-session anyway) and insert the new list, which includes the rotated server as a **distinct** row. That is a real extra server, not a clone of the same config. If product later wants "this is the same server, retarget the kept id", that needs an identity key this codebase does not have today — do not invent remarks/host:port matching in the first fix.

**Alternative that also works:** full wipe even when connected, then `insertAll`, then `setActiveProfileId` to the new row that matches the old config (or the first new row if unmatched), **and** teach `XrayVpnService` that `currentProfileId` moved. That is more call-site wiring (`SubscriptionRefreshCoordinator` would have to write the active id; the service's in-memory `currentProfileId` would desync until the next rotation). Prefer in-place UPDATE.

**Out of scope for the clone fix (do not bundle unless asked):**

- Tile Stop clearing `active_profile_id` — product question; clone fix does not require it.
- URL uniqueness on `addSubscription`.
- Unguarded `TextButton` double-submit on paste / Boykisser / DNS-warning.
- Config-level dedup inside `parseBody` (H2).
- Writing a full subscription-management feature doc.

**Tests that must exist before claiming fixed** (TDD; JVM with a real or fake DAO is enough if the replace method is the unit):

1. Keep-path refresh, active server still in payload → row count equals parsed count, **not** parsed+1; kept id still present; kept row's `config`/`name`/`sanitizedDns` equal the matching parsed entry.
2. Keep-path refresh, active server **absent** from payload → kept id still present; parsed count other rows inserted; no second copy of the kept config.
3. Keep-less refresh (`keepProfileId == null`) → row count equals parsed count; previous ids all gone (existing churn behaviour).
4. Two keep-path refreshes in a row while still active → still parsed count, not growth.
5. Manual (`subscriptionId IS NULL`) rows untouched by either branch.
6. Failover pool after keep-path refresh does not contain two rows with the same config.

`FailoverPoolResolverDispatchTest`'s stub of `deleteForSubExceptId` will need to become real behaviour or the new tests live next to `ProfileDao` / a small coordinator test with an in-memory Room DB (instrumented) or a fake that actually implements replace.

## Files

| Path | Why |
|---|---|
| `app/src/main/java/com/justme/xtls_core_proxy/db/ProfileDao.kt:52-72` | replace + spared row |
| `app/src/main/java/com/justme/xtls_core_proxy/subs/SubscriptionRefreshCoordinator.kt:87-104` | `keepProfileId` |
| `app/src/main/java/com/justme/xtls_core_proxy/db/Profile.kt:8-26` | entity, non-unique index |
| `app/src/main/java/com/justme/xtls_core_proxy/subs/SubscriptionBodyParser.kt:31-57, 225-230` | parse + name uniquify |
| `app/src/main/java/com/justme/xtls_core_proxy/state/VpnViewModel.kt:164-188, 415-510, 513, 575` | grouping, refresh triggers, connect/disconnect active id |
| `app/src/main/java/com/justme/xtls_core_proxy/state/ActiveProfileRepository.kt:39-63` | id persist; `getFirst()` fallback |
| `app/src/main/java/com/justme/xtls_core_proxy/MainActivity.kt:369-372, 718-804, 1051-1066, 1122-1128` | `onStart` stale sweep, LazyColumn, ProfileRow, `isActive` |
| `app/src/main/java/com/justme/xtls_core_proxy/tile/XrayVpnTileService.kt` | trigger; Stop does not clear active id |
| `app/src/main/java/com/justme/xtls_core_proxy/failover/FailoverPoolResolver.kt:12-29` | pool is whole subscription; comment about id churn |
| `app/src/main/java/com/justme/xtls_core_proxy/vpn/XrayVpnService.kt:327, 365, 1451, 2517+` | `pickOrPersistActive` on always-on start; rotation writes active id; `stopVpn` does not clear it |

Related feature docs: [`qs-tile-vpn-toggle.md`](../features/qs-tile-vpn-toggle.md) (tile does not insert), [`auto-failover.md`](../features/auto-failover.md) (pool / id churn). Index: [`README.md`](README.md).

---

## Appendix A — Coordinator synthesis (verbatim, 2026-09-30)

The following is the investigation synthesis as delivered to the maintainer, preserved so a fixer does not need the session transcript.

Four independent investigators named the same keep+reinsert; the coordinator independently re-read the DAO, generated Room SQL, tile Stop, and list grouping.

**Verdict.** Root cause is in subscription refresh, not in the QS tile as an inserter.

When a refresh runs while the active profile belongs to that subscription, the DAO **keeps that one row by id** and then **inserts every parsed server**, including a new row for the same server. Two Room rows, same `subscriptionId`, same display name, different ids.

The tile does **not** write `profiles`. It *can* make a subscription row "active" (`pickOrPersistActive`) and *can* open `MainActivity`, whose `onStart` auto-refreshes stale subs. That is a trigger, not the insert.

This is still **code-trace confirmed, not device-reproduced**. No test covers this path (`replaceProfilesForSubscription` is only stubbed in a failover fake).

**The duplicating transaction** — `ProfileDao.kt` as quoted above. `keepProfileId` is computed in `SubscriptionRefreshCoordinator.kt:98–104`: active id, **only if** that row's `subscriptionId` is this sub. `newProfiles` is the **entire** parse. Nothing removes the kept server from that list.

Generated insert is `INSERT OR ABORT ... VALUES (nullif(?, 0), ...)` — parsed rows have `id = 0` → new autoincrement ids. Schema has **no unique index** on `name` or `config` (`Profile.kt:18`).

Introduced in `89ef7d3` ("preserves the active profile row if it belongs to the refreshing subscription"). Intent: don't dangle the connected id. Implementation: keep the row **and** insert its twin. Unchanged since. This branch's VPN/failover work did not touch `db/` or `subs/`.

**Why they look identical.** `ProfileRow` shows `name`, optional DNS-corrected badge, ping. **Config is never shown.** `uniquify()` (`SubscriptionBodyParser.kt:225–230`) only runs **inside one parse**, so the kept row is not counted. The fresh copy is the first occurrence this pass → both get the bare name, not `"X (2)"`.

Compose keys are `"p-${it.id}"`. A same-id double render would **crash** LazyColumn, not silently twin. `groupedProfiles` is a `groupBy { subscriptionId }` partition — each row in exactly one bucket. **UI-only duplication is out.**

**Why a later update can remove it — with a condition the first-pass investigators underplayed.** See the table in the body. Tile Stop does **not** disarm the keeper.

**Tile / connect write nothing into `profiles`.** Manual duplicates (double-tap paste, copy-link then re-add) are real, and there is **zero** add-path dedup — but `DELETE ... WHERE subscriptionId = :subId` never matches `NULL`. Those cannot be what "update removes the clone" describes.

**Secondary.** Kept row never `@Update`d. Failover pool contains the same server twice. Zero tests of the keep path.

**How to confirm in ~30 seconds.** Connect to a server inside a subscription → group refresh → N→N+1, same name, active dot on older row. In-app Disconnect + refresh → back to N.

A fix belongs in `replaceProfilesForSubscription` / the coordinator: **update the kept row from the matching parsed entry and insert the rest**, so the connected id stays valid without minting a twin.

## Appendix B — Investigation notes (for anyone who distrusts Appendix A)

Process: systematic-debugging Phase 1 only. Four `space-bunny[1m]` investigators in parallel (subscription refresh, QS tile/persist, add/import, UI vs Room), plus coordinator independent re-read. A first wave launched as the `sonnet` alias before identity was confirmed was killed; reports discarded. Maintainer rule: never trust a single investigator; exception when two or more independently converge — here four did, then the coordinator re-derived from the files.

Insert call sites in `app/src/main` (complete): `VpnViewModel.addProfile` / `addRawProfile` / `confirmDnsFixAndAdd` (manual); `replaceProfilesForSubscription` `insertAll` (subscription-owned). Android tests construct `Profile(...)` under `app/src/androidTest` only.

Active-id writers: `ActiveProfileRepository.setActiveProfileId` from `VpnViewModel.connect` `:513`, `signalStop` `:575`, `deleteProfile` `:410`, `addRawProfile` `:376`, `pickOrPersistActive` `:61`, `XrayVpnService` refused-start rollback `:365`, failover rotation `:1451`.

`ClipboardAddRouter.classify` has no URL-duplicate check. Share-target / `vless://` `ACTION_VIEW` filters do not exist on `MainActivity`. Boykisser deep-link intent-filters are commented out in the manifest; in-app promo add is live and goes through `addSubscription(refreshAfterInsert = true)`.

`FailoverDecision.nextCandidate` can land on the clone. `ProfileActionsDialog` has Connect / Disconnect / Connect-fastest / Ping / Edit / Copy link / Copy JSON / Delete — **no duplicate/clone action**. Copy-link then "Add from clipboard" creates a **manual** twin that refresh cannot remove (H4).
