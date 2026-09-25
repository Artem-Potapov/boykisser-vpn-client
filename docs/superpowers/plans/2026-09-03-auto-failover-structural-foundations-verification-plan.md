# Auto-Failover Structural Foundations: Verification Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish the structural-foundations refactor with the exact shared connection-state helper, compile-time DAO query deduplication, authoritative documentation updates, and full JVM, instrumented, lint, release, and device verification.

**Architecture:** `VpnConnectionState.isActiveSessionState()` owns only the exact `{CONNECTING, CONNECTED, PAUSED, BLACKHOLED}` membership set. Main profile highlighting, tile Stop eligibility, and tile active/inactive rendering delegate only their state classification to it while keeping their labels, connect actions, Disconnect rules, and recovery semantics separate. `ProfileDao` keeps both existing APIs and shares one Room compile-time SQL constant.

**Tech Stack:** Kotlin extension functions, Room `@Query`, JUnit4, AndroidJUnit4, Gradle, maintainer Markdown and QA procedures

**Spec:** `docs/superpowers/specs/2026-09-03-auto-failover-structural-foundations-design.md`

**Prerequisites:**
- `docs/superpowers/plans/2026-09-03-auto-failover-structural-foundations-owner-plan.md` passed its Phase 2 gate.
- `docs/superpowers/plans/2026-09-03-auto-failover-structural-foundations-capture-plan.md` passed its Phase 3 gate.

## Global Constraints

- Share only the exact four-state membership invariant: `CONNECTING`, `CONNECTED`, `PAUSED`, `BLACKHOLED`.
- Do not replace `connectAction`, Main UI Disconnect visibility, labels, notifications, `ReconnectFlow`, or lifecycle decisions with the helper.
- `ERROR` remains outside `isActiveSessionState()` but remains included by the Main UI Disconnect gate.
- `BLACKHOLED` remains active and stoppable, while `connectAction(BLACKHOLED)` remains `RECONNECT`.
- `ProfileDao.getManual(): Flow<List<Profile>>` and `suspend getManualList(): List<Profile>` remain separate APIs. Do not implement one by collecting the other.
- Use the actual package `com.justme.xtls_core_proxy.db.Profile` and the existing in-memory Room fixture pattern.
- Update authoritative docs to current truth; do not retrofit historical specs or ignored raw artifacts.
- Do not change behavior-only profile, DNS, Xray-settings, or unrelated documentation.
- Heavy Gradle commands run serially. Human review covers all `vpn/` changes.
- Commits, if made, use `git commit -S`; signing failure is reported rather than worked around by changing external configuration.

## Files

**Create:**
- `app/src/main/java/com/justme/xtls_core_proxy/log/VpnConnectionStateExt.kt`
- `app/src/test/java/com/justme/xtls_core_proxy/log/VpnConnectionStateExtTest.kt`
- `app/src/androidTest/java/com/justme/xtls_core_proxy/db/ProfileDaoManualQueryTest.kt`

**Modify:**
- `app/src/main/java/com/justme/xtls_core_proxy/MainActivity.kt`
- `app/src/main/java/com/justme/xtls_core_proxy/tile/TileClickDecision.kt`
- `app/src/main/java/com/justme/xtls_core_proxy/tile/XrayVpnTileService.kt`
- `app/src/main/java/com/justme/xtls_core_proxy/db/ProfileDao.kt`
- `app/src/main/java/com/justme/xtls_core_proxy/vpn/XrayVpnService.kt` (KDocs/comments only after owner/capture plans)
- `app/src/main/java/com/justme/xtls_core_proxy/vpn/SessionLifecycleDecision.kt` (KDocs/comments only after owner plan)
- `docs/features/auto-failover.md`
- `AGENTS-ARCHITECTURE.md`
- `AGENTS-DEVELOPMENT.md`
- `docs/features/qs-tile-vpn-toggle.md`
- `docs/features/logs-screen.md`
- `docs/qa/auto-failover-qa.md`

---

### Task 1: Add and test `isActiveSessionState`

**Files:**
- Create: `app/src/main/java/com/justme/xtls_core_proxy/log/VpnConnectionStateExt.kt`
- Create: `app/src/test/java/com/justme/xtls_core_proxy/log/VpnConnectionStateExtTest.kt`

**Interfaces:**
- Consumes: existing `VpnConnectionState` from `log/LogRepository.kt`
- Produces:

```kotlin
internal fun VpnConnectionState.isActiveSessionState(): Boolean
```

- [ ] **Step 1: Write the exhaustive helper tests**

Create tests that assert exactly these results:

```kotlin
assertTrue(VpnConnectionState.CONNECTING.isActiveSessionState())
assertTrue(VpnConnectionState.CONNECTED.isActiveSessionState())
assertTrue(VpnConnectionState.PAUSED.isActiveSessionState())
assertTrue(VpnConnectionState.BLACKHOLED.isActiveSessionState())
assertFalse(VpnConnectionState.DISCONNECTED.isActiveSessionState())
assertFalse(VpnConnectionState.ERROR.isActiveSessionState())
```

Also assert the complete enum is classified by the test's expected map, so adding a future enum member requires an explicit test decision.

- [ ] **Step 2: Implement the exhaustive extension**

Create `VpnConnectionStateExt.kt` adjacent to `VpnConnectionState`:

```kotlin
package com.justme.xtls_core_proxy.log

/**
 * The shared four-state membership invariant used by active-profile highlighting, tile active
 * rendering, and tile Stop eligibility. This is not a general service-running, connectability,
 * disconnectability, label, notification, or recovery predicate.
 */
internal fun VpnConnectionState.isActiveSessionState(): Boolean = when (this) {
    VpnConnectionState.CONNECTING,
    VpnConnectionState.CONNECTED,
    VpnConnectionState.PAUSED,
    VpnConnectionState.BLACKHOLED -> true

    VpnConnectionState.DISCONNECTED,
    VpnConnectionState.ERROR -> false
}
```

Keep the KDoc explicit that `ERROR` is intentionally excluded and that `BLACKHOLED` is included.

- [ ] **Step 3: Run the focused test**

```powershell
./gradlew.bat :app:testDebugUnitTest --tests "*.VpnConnectionStateExtTest"
```

Expected: PASS.

- [ ] **Step 4: Commit the helper**

```bash
git add app/src/main/java/com/justme/xtls_core_proxy/log/VpnConnectionStateExt.kt app/src/test/java/com/justme/xtls_core_proxy/log/VpnConnectionStateExtTest.kt
git commit -S -m "refactor(state): centralize active session membership"
```

---

### Task 2: Migrate only the three approved state consumers

**Files:**
- Modify: `app/src/main/java/com/justme/xtls_core_proxy/MainActivity.kt`
- Modify: `app/src/main/java/com/justme/xtls_core_proxy/tile/TileClickDecision.kt`
- Modify: `app/src/main/java/com/justme/xtls_core_proxy/tile/XrayVpnTileService.kt`
- Retain/update: `app/src/test/java/com/justme/xtls_core_proxy/MainActivityStateTest.kt`
- Retain/update: `app/src/test/java/com/justme/xtls_core_proxy/tile/TileClickDecisionTest.kt`

**Interfaces:**
- Consumes: `VpnConnectionState.isActiveSessionState()`
- Produces: three callers sharing only state classification; all caller-specific behavior remains intact

- [ ] **Step 1: Migrate `MainActivity.isActive`**

Retain the profile-id comparison and delegate only the state part:

```kotlin
internal fun isActive(profile: Profile, activeId: Long?, state: VpnConnectionState): Boolean =
    profile.id == activeId && state.isActiveSessionState()
```

Do not change `shouldShowDisconnect`; its exact set remains the four active states plus `ERROR`.

- [ ] **Step 2: Migrate `shouldStopOnTileClick`**

Replace the four-state boolean chain with:

```kotlin
internal fun shouldStopOnTileClick(state: VpnConnectionState): Boolean =
    state.isActiveSessionState()
```

Retain the function as a tile-specific seam because the no-I/O fast path calls it before profile and permission work.

- [ ] **Step 3: Migrate tile active/inactive classification without changing labels**

In `XrayVpnTileService.updateTile`, use `state.isActiveSessionState()` only to choose `Tile.STATE_ACTIVE` versus `Tile.STATE_INACTIVE`. Keep the existing exhaustive label behavior unchanged:

- `DISCONNECTED`: inactive, localized disconnected label;
- `CONNECTING`: active, localized connecting label;
- `CONNECTED`: active, resolved profile name with existing fallback;
- `PAUSED`: active, localized paused label;
- `BLACKHOLED`: active, `vpnConnectionStateLabelRes(state, giveUpLine)`;
- `ERROR`: inactive, `vpnConnectionStateLabelRes(state, giveUpLine)`.

Do not replace this with a label `when` that treats `ERROR` as active or drops `resolveConnectedLabel`, `giveUpLine`, localization, or subtitle clearing.

- [ ] **Step 4: Run all state-semantic regression suites**

```powershell
./gradlew.bat :app:testDebugUnitTest --tests "*.VpnConnectionStateExtTest"
./gradlew.bat :app:testDebugUnitTest --tests "*.MainActivityStateTest"
./gradlew.bat :app:testDebugUnitTest --tests "*.TileClickDecisionTest"
./gradlew.bat :app:testDebugUnitTest --tests "*.ConnectActionTest"
./gradlew.bat :app:testDebugUnitTest --tests "*.VpnConnectionLabelsTest"
```

Expected: PASS. `ConnectActionTest` and label tests are regression guards and must remain unchanged in meaning even though they do not delegate to the new helper.

- [ ] **Step 5: Commit the state-consumer migration**

```bash
git add app/src/main/java/com/justme/xtls_core_proxy/MainActivity.kt app/src/main/java/com/justme/xtls_core_proxy/tile/TileClickDecision.kt app/src/main/java/com/justme/xtls_core_proxy/tile/XrayVpnTileService.kt app/src/test/java/com/justme/xtls_core_proxy/MainActivityStateTest.kt app/src/test/java/com/justme/xtls_core_proxy/tile/TileClickDecisionTest.kt
git commit -S -m "refactor(state): reuse active session membership in UI and tile"
```

---

### Task 3: Deduplicate the manual-profile Room SQL

**Files:**
- Modify: `app/src/main/java/com/justme/xtls_core_proxy/db/ProfileDao.kt`
- Create: `app/src/androidTest/java/com/justme/xtls_core_proxy/db/ProfileDaoManualQueryTest.kt`

**Interfaces:**
- Consumes: current `ProfileDao.getManual()` and `getManualList()` contracts
- Produces: one file-private compile-time SQL constant; both API signatures unchanged

- [ ] **Step 1: Add the real Room equivalence test**

Follow the existing `FailoverPoolResolverTest` fixture pattern:

```kotlin
@RunWith(AndroidJUnit4::class)
class ProfileDaoManualQueryTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun flowAndSnapshotReturnSameOrderedManualOnlyRows() = runBlocking {
        val subscriptionId = db.subscriptionDao().insert(
            Subscription(name = "Sub", url = "https://sub.test/list")
        )
        val dao = db.profileDao()
        val manual1 = dao.insert(Profile(name = "Manual 1", config = "{}"))
        val subscription = dao.insert(
            Profile(name = "Subscription", config = "{}", subscriptionId = subscriptionId)
        )
        val manual2 = dao.insert(Profile(name = "Manual 2", config = "{}"))

        val flowRows = dao.getManual().first()
        val snapshotRows = dao.getManualList()

        assertEquals(listOf(manual1, manual2), flowRows.map { it.id })
        assertEquals(flowRows, snapshotRows)
        assertEquals(listOf(manual1, manual2), snapshotRows.map { it.id })
        assertFalse(snapshotRows.any { it.id == subscription })
    }
}
```

Use the actual `db.Profile` and `db.Subscription` imports. Inserting the parent subscription is required by the Room foreign key.

- [ ] **Step 2: Add the shared compile-time constant**

At file scope in `ProfileDao.kt`, add:

```kotlin
private const val MANUAL_PROFILES_SQL =
    "SELECT * FROM profiles WHERE subscriptionId IS NULL ORDER BY id ASC"
```

- [ ] **Step 3: Point both annotations at the constant**

Retain the two distinct API shapes:

```kotlin
@Query(MANUAL_PROFILES_SQL)
fun getManual(): Flow<List<Profile>>

@Query(MANUAL_PROFILES_SQL)
suspend fun getManualList(): List<Profile>
```

Do not use `getManual().first()` to implement the snapshot method.

- [ ] **Step 4: Run the Room equivalence test**

```powershell
./gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.justme.xtls_core_proxy.db.ProfileDaoManualQueryTest
```

Expected: PASS.

- [ ] **Step 5: Compile and run existing Room/failover instrumentation**

```powershell
./gradlew.bat :app:compileDebugKotlin
./gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.justme.xtls_core_proxy.failover.FailoverPoolResolverTest
```

Expected: PASS; the resolver still uses `getManualList()` as its snapshot seam.

- [ ] **Step 6: Commit the DAO change**

```bash
git add app/src/main/java/com/justme/xtls_core_proxy/db/ProfileDao.kt app/src/androidTest/java/com/justme/xtls_core_proxy/db/ProfileDaoManualQueryTest.kt
git commit -S -m "refactor(db): share manual profile query SQL"
```

---

### Task 4: Update authoritative documentation and source KDocs

**Files:**
- Modify: `docs/features/auto-failover.md`
- Modify: `AGENTS-ARCHITECTURE.md`
- Modify: `AGENTS-DEVELOPMENT.md`
- Modify: `docs/features/qs-tile-vpn-toggle.md`
- Modify: `docs/features/logs-screen.md`
- Modify: `docs/qa/auto-failover-qa.md`
- Modify source KDocs/comments in `XrayVpnService.kt`, `SessionLifecycleDecision.kt`, `TileClickDecision.kt`, `MainActivity.kt`, `VpnViewModel.kt`, and `TunnelHealthMonitor.kt` where old fields or stale claims remain

**Interfaces:**
- Consumes: completed owner, capture, state, and DAO changes
- Produces: authoritative documentation matching current code; no behavior change

- [ ] **Step 1: Update `docs/features/auto-failover.md` completely**

Update every section that currently names `tunInterface`, `tunInterfaceKind`, or `rotationBridgeInterface`, including the ownership overview, rotation bridge, give-up/adoption, components, testing, known limitations, and deferred-engine sections. Document:

- the four `TunnelInterfaceOwner` variants;
- unstored derived `TunnelRole`;
- `TunnelOwnerTransitions` and clear-before-close;
- one-descriptor invariant and same-handle bridge adoption;
- `TunCapturePlan` and shared applicator;
- per-establishment split preference timing;
- unconditional IPv6 address/route and conditional IPv6 DNS;
- establish-first candidate handover, which replaces the current production release-before-establish baseline: during `ROTATING`, `UnreadRotationBridge` stays authoritative through `Builder.establish()`; `null`/throw leaves that owner unchanged, with no release audit, close, detach, or live owner; a non-null candidate installs `LiveProxy` immediately; the old bridge is closed once as a bounded local cleanup handle and is never re-adopted, detached, or retried by raw fd; close failure is non-fatal deferred diagnostic state;
- the exact strings are retained but reordered: `TUN established with fd=…`, then the optional `Failover: rotation bridge close warning: …`, then the mandatory successful-handover audit `Failover: rotation bridge released (handing over to the newly established tunnel)`, then `Using geofiles from …` and the existing protector/Xray diagnostics; initial start and revive emit no release audit;
- a post-establishment diagnostic append failure cannot enter the bring-up failure funnel or tear down/reclassify `LiveProxy`;
- packet capture across the whole rotation is the no-underlying-packet-escape oracle; log order is diagnostic evidence only;
- `isActiveSessionState` exact four-state set and its three consumers;
- distinct `connectAction`, Disconnect, labels, notifications, and lifecycle semantics;
- `FailoverEngine`/`TunnelHost` as the later follow-up, not part of this structural phase.

- [ ] **Step 2: Update `AGENTS-ARCHITECTURE.md`**

Replace the bridge-field ownership description in the auto-failover guarantee with the sealed owner and derived role. Document establish-first handover: the unread bridge stays authoritative until replacement establishment succeeds, the old bridge is then only a bounded local cleanup handle, and the close/release diagnostics are deferred until after `TUN established`. State that current production is still release-first until this structural work lands. Clarify that `BLACKHOLED` may own unread blackhole containment or a retained live proxy outcome, that the exact active-session helper is not a universal state predicate, and that packet capture across the whole rotation — not log order — remains the no-underlying-packet-escape proof.

- [ ] **Step 3: Update `AGENTS-DEVELOPMENT.md`**

Replace `TunInterfaceKind` references with `TunnelInterfaceOwner`/`TunnelRole`, document `TunCapturePlan` and the shared Builder applicator, and document the establish-first rotation handover: retained bridge on `null`/throw, `LiveProxy` before the one prior-bridge close, and post-establishment deferred diagnostics. Retain the one-lock/epoch/serialized-lane constraints, and continue to list the engine extraction as a later migration. Do not describe the current release-before-establish service order as the extension-point constraint.

- [ ] **Step 4: Update `docs/features/qs-tile-vpn-toggle.md` without dropping labels**

Update the state-machine diagram's active set to reference `isActiveSessionState()` and list exactly four states. Document that `updateTile` delegates only active/inactive classification while preserving the connected profile-name lookup, localized labels, `giveUpLine`, `ERROR` inactive behavior, and `subtitle = null`. Keep tile Stop, Main Disconnect, `connectAction`, and state-specific labels distinct.

- [ ] **Step 5: Update `docs/features/logs-screen.md` conservatively**

Replace stale `tunInterface == null` wording with the final owner predicate relevant to log-tail startup/establishment. Preserve the existing session-epoch, `ROTATING`, tailer, and log-capture behavior. Do not gate the tailer on `LiveProxy` if current code starts it at session admission; document the actual precondition after the owner migration.

- [ ] **Step 6: Update `docs/qa/auto-failover-qa.md` by editing rationales, not deleting procedures**

Update Test 7 rationale to say that active rendering and tile Stop eligibility share `isActiveSessionState`, while the device test still exercises the live tile and Stop action. Update Test 23 rationale to say that capture-plan construction finishes before the establish-first handover and that the unread bridge remains the containment interface while the replacement is established. Update Test 23a so the expected diagnostic order is: rotating; bridge-established; replacement `TUN established with fd=…`; optional close warning; mandatory release audit; geofile/protector/Xray diagnostics. State that packet capture covers the whole switch and that logs do not prove the no-underlying-packet-escape policy. Update the forced-failure checks so a `null`/throw from replacement `establish()` leaves the original bridge available to the existing retry/give-up path. Preserve one-bridge-per-episode, same-handle adoption, and the packet-capture pass/fail criteria. Do not preserve a release-before-establish check as the target.

- [ ] **Step 7: Update source KDocs/comments**

Search and correct old names and stale claims in the listed source files. Comments must describe actual code and must not imply that the new helper controls connectability, Disconnect visibility, labels, or recovery.

- [ ] **Step 8: Commit documentation updates**

```bash
git add docs/features/auto-failover.md AGENTS-ARCHITECTURE.md AGENTS-DEVELOPMENT.md docs/features/qs-tile-vpn-toggle.md docs/features/logs-screen.md docs/qa/auto-failover-qa.md app/src/main/java/com/justme/xtls_core_proxy/vpn/XrayVpnService.kt app/src/main/java/com/justme/xtls_core_proxy/vpn/SessionLifecycleDecision.kt app/src/main/java/com/justme/xtls_core_proxy/tile/TileClickDecision.kt app/src/main/java/com/justme/xtls_core_proxy/MainActivity.kt app/src/main/java/com/justme/xtls_core_proxy/state/VpnViewModel.kt app/src/main/java/com/justme/xtls_core_proxy/failover/TunnelHealthMonitor.kt
git commit -S -m "docs: update structural failover foundations"
```

---

### Task 5: Stale-reference and semantic audit

**Files:**
- Tracked source and documentation only

**Interfaces:**
- Consumes: all preceding tasks
- Produces: a clean reference audit with no accidental policy collapse

- [ ] **Step 1: Search for removed owner names**

```powershell
rg -n "tunInterfaceKind|TunInterfaceKind|rotationBridgeInterface" app/src docs AGENTS-ARCHITECTURE.md AGENTS-DEVELOPMENT.md
```

Expected: no matches, except an explicitly dated change-control entry that describes the migration if the maintainer chooses to retain one.

- [ ] **Step 2: Search for stale capture and precondition claims**

```powershell
rg -n "separate.*Builder|duplicat.*Builder|tunInterface\s*==\s*null|two.*descriptor|bridge.*field" app/src docs AGENTS-ARCHITECTURE.md AGENTS-DEVELOPMENT.md
```

Review every match; update stale authoritative prose without changing historical artifacts.

- [ ] **Step 3: Search for forbidden semantic collapse**

```powershell
rg -n "isActiveSessionState|connectAction|shouldShowDisconnect|shouldStopOnTileClick|VpnConnectionLabels|BLACKHOLED|ERROR" app/src/main/java docs/features/qs-tile-vpn-toggle.md docs/features/auto-failover.md
```

Verify:

- helper has exactly four true states;
- `connectAction(BLACKHOLED)` remains `RECONNECT`;
- Disconnect still includes `ERROR`;
- tile `ERROR` remains inactive and `BLACKHOLED` active;
- labels and notifications still use their existing mappings;
- `ReconnectFlow` remains the contained-state coordinator.

- [ ] **Step 4: Search for API-shape regressions**

```powershell
rg -n "db\.entity\.Profile|SessionLifecycleDecision\.|SplitTunnelPlan\(|Log\.w\(|Log\.e\(" docs/superpowers/plans/2026-09-03-auto-failover-structural-foundations*.md
```

Expected: repaired plans contain no nonexistent profile package, nested decision references, set/list constructor mismatch, or invented service logging API.

---

### Task 6: Full verification and mandatory final device QA

**Files:**
- No production files; verification only

**Interfaces:**
- Consumes: all structural-foundations plans
- Produces: final acceptance evidence

- [ ] **Step 1: Run the complete JVM suite**

```powershell
./gradlew.bat :app:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 2: Run the complete instrumented suite**

Before running on a data-bearing device, follow the repository warning about preserving installed app data. Prepare the device according to `AGENTS-DEVELOPMENT.md` if using One UI. Then run:

```powershell
./gradlew.bat :app:connectedDebugAndroidTest
```

Expected: PASS; the app remains installed under the repository's safe default unless destructive uninstall is explicitly requested.

- [ ] **Step 3: Run lint**

```powershell
./gradlew.bat :app:lintDebug
```

Expected: PASS with no new errors. Fix missing translations in the tracked locale resources if lint reports them; do not suppress a new behavioral warning.

- [ ] **Step 4: Build the release APK**

```powershell
./gradlew.bat :app:assembleRelease
```

Expected: PASS with R8 and resource shrinking. Do not modify signing or external GPG configuration if signing fails.

- [ ] **Step 5: Execute the complete device matrix**

Run the QA procedures in `docs/qa/auto-failover-qa.md`, including:

1. normal connection and Stop;
2. successful automatic rotation;
3. candidate bring-up failure followed by another candidate;
4. give-up contained by a live TUN;
5. give-up adopting the unread rotation bridge;
6. fresh unread blackhole when no bridge exists;
7. forced real and unread establishment failures with truthful `UNPROTECTED` behavior;
8. kill, user Stop, revoke, and destruction during rotation;
9. split allow-only and exclude modes;
10. IPv6 enabled and disabled;
11. packet capture proving no cleartext escapes during bridge, handover, and blackhole states;
12. one bridge per episode and same-handle adoption;
13. no descriptor after pause or full stop;
14. Main UI and Quick Settings behavior for every connection state, especially `BLACKHOLED` and `ERROR`.

- [ ] **Step 6: Human review all VPN changes**

Review the final diff for lock regions, epoch checks, dispatcher ownership, descriptor clear-before-close on terminal release, establish-first candidate handover, exact diagnostic text after `TUN established`, and no new engine/host API. Reject release-before-establish as the landed handover. A green build is not a substitute for this review. Packet capture, not the log sequence, is the policy proof.

- [ ] **Step 7: Record final acceptance**

The structural-foundations work is complete only when all three phase plans passed their gates, all verification commands passed, documentation is current, and device QA has evidence for packet containment and descriptor ownership. Any uncovered correctness defect is reported as a separate design item.

## Verification Plan Self-Review

- [ ] The helper is shared only by the three approved consumers.
- [ ] Existing full-enum guards remain and are run.
- [ ] Tile labels and `ERROR`/`BLACKHOLED` presentation are preserved.
- [ ] Room foreign-key setup is valid and both DAO contracts are tested.
- [ ] All six authoritative documents and source comments are covered.
- [ ] Stale-reference searches cover source, tracked docs, and plan artifacts.
- [ ] Release verification and mandatory device QA are explicit.

## Change Control

### 2026-09-21 — Verification plan split and correction

- **Previous decision:** The monolithic plan grouped the helper, DAO, documentation, and final gates with unsafe service pseudo-code.
- **Replacement decision:** Verification work is isolated here and preserves exhaustive state-specific behavior, real Room fixtures, complete documentation sections, and the repository's packet-capture acceptance criteria.
- **Reason:** Review found that the original tile migration would drop labels, the Room fixture used an invalid package and foreign-key setup, and the documentation steps omitted stale sections and QA pass/fail procedures.
- **Affected sections:** State helper, state consumers, DAO test, documentation, stale-reference audit, and final verification.

### 2026-09-21 — Handover documentation precision

Superseded for candidate-handover order by the 2026-09-22 entry below. Retained as history. Do not document the audit log between bridge close and `Builder.establish()`.

- **Previous decision:** Documentation guidance described release and establishment as having no intervening I/O, without identifying the existing bridge-release audit log.
- **Replacement decision:** The docs task retains the exact audit log between bridge close and `Builder.establish()` when a bridge was held, and forbids only new work after that log before establishment.
- **Reason:** The existing service log is required by QA Test 23a; documenting its removal would conflict with the owner and capture plans.
- **Affected sections:** Task 4 Step 1.

### 2026-09-22 — Establish-first handover alignment

- **Previous decision:** The documentation task told maintainers to record release-before-establish, with `Failover: rotation bridge released (handing over to the newly established tunnel)` between bridge close and `Builder.establish()`. Test 23 rationale was to describe adjacent bridge release/establish, and the final human review checked that adjacency. That order is still the current production baseline.
- **Replacement decision:** Authoritative docs and QA describe the amended target: `UnreadRotationBridge` stays authoritative through `establish()`; `null`/throw leaves it unchanged with no release audit; `LiveProxy` is installed before one bounded prior-bridge close; the existing close warning and release audit follow `TUN established with fd=…` and cannot re-enter pre-commit failure handling. Test 23a expects rotating, bridge-established, replacement TUN-established, optional close warning, mandatory release audit, then geofile/protector/Xray diagnostics. Packet capture across the whole switch is the policy oracle; logs are not. Current production remains labeled release-first until implementation lands. This task still does not authorize `FailoverEngine`/`TunnelHost` implementation.
- **Reason:** The 2026-09-22 spec amendment replaced release-first because it opens an avoidable interface-free interval. Android's documented `Builder.establish()` contract leaves the existing interface untouched when replacement creation fails. This entry aligns the documentation task with that target. The 2026-09-04 engine contract-recovery gate remains binding.
- **Affected sections:** Task 4 Steps 1, 2, 3, and 6, Task 5 Step 6, and this Change Control section. The 2026-09-21 handover-precision entry above is retained as history and is superseded for candidate-handover order.
