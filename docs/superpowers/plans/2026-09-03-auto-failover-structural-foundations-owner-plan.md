# Auto-Failover Structural Foundations: Owner Migration Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `XrayVpnService`'s parallel TUN fields with one sealed owner and migrate every lifecycle decision and service mutation. Preserve fail-closed outcomes except for the approved establish-first candidate handover: during `ROTATING`, keep `UnreadRotationBridge` authoritative through `Builder.establish()`, install `LiveProxy` only after a non-null result, and close the old bridge only as a bounded local cleanup handle. Production is still release-first; follow this plan's target, not the current service comment.

**Architecture:** `TunnelInterfaceOwner<Handle>` binds one handle to exactly one role: `None`, `LiveProxy`, `UnreadRotationBridge`, or `UnreadBlackhole`. `TunnelRole` is a derived, never-stored policy projection. `TunnelOwnerTransitions` remains Android-independent and returns next owners plus handles for the service to close; it never acquires locks, closes descriptors, establishes a Builder, publishes state, or performs I/O.

**Tech Stack:** Kotlin sealed interfaces, JUnit4, Android `ParcelFileDescriptor`, existing `XrayVpnService` lock/epoch/serialized-lane lifecycle

**Spec:** `docs/superpowers/specs/2026-09-03-auto-failover-structural-foundations-design.md`

## Global Constraints

- Preserve reservation rules, thrash caps, pool resolution, bridge ownership, containment outcomes, recovery timing, kill/Stop/disable interleaving, and packet-level fail-closed behavior.
- The owner phase must remove `tunInterface`, `tunInterfaceKind`, and `rotationBridgeInterface`; no shadow fields or dual writes are allowed.
- `SessionTunnelState`, `GiveUpContainment`, `FailoverGiveUpOutcome`, and all decision functions remain top-level declarations in `SessionLifecycleDecision.kt`.
- `Profile` is `com.justme.xtls_core_proxy.db.Profile`; do not import `com.justme.xtls_core_proxy.db.entity.Profile`.
- Service logs continue through `LogRepository.append`; preserve existing text and prefixes.
- All service owner reads and writes remain under the existing `lock` where they are currently protected.
- `onDestroy`, `onRevoke`, notification `ACTION_STOP`, `stopVpn` synchrony, and serialized `tunnelOpScope` ownership remain unchanged.
- Real candidate handover is establish-first, not the current production release-then-establish order. Under the lock, while the transition is `ROTATING` and the owner is `UnreadRotationBridge`, call `Builder.establish()` with that owner still authoritative. `null` or a throw leaves the bridge unchanged: do not emit a release audit, close or detach the bridge, or install a live owner. A non-null candidate is installed as `LiveProxy(candidate)` immediately. The old bridge is then only a bounded local cleanup handle: close it once, never re-adopt it, detach it, or retry its raw fd. Close failure is non-fatal and is retained as deferred diagnostic state. No log, repository read, suspension, config work, notification, or other unrelated operation is inserted between non-null establishment, live-owner installation, and that one close. After the close attempt, append `TUN established with fd=…`, then the optional exact close warning `Failover: rotation bridge close warning: ${error.message}` only when close failed, then the exact release audit `Failover: rotation bridge released (handing over to the newly established tunnel)`, then the existing geofile, protector, and Xray diagnostics. A failure of one of those post-establishment diagnostic appends must not enter the bring-up failure funnel or tear down/reclassify the installed `LiveProxy`. Initial start and revive still require `None`, have no bridge, and emit no release audit. Packet capture across the whole rotation is the product-policy oracle; log order is diagnostic evidence only.
- Every test and verification command runs against the actual current signatures. Do not introduce a `SessionLifecycleDecision` object merely to make call sites look namespaced.
- Heavy Gradle commands run serially. Human review covers all `vpn/` changes.
- Commits, if made, use `git commit -S`; signing failure must be reported rather than worked around by changing external configuration.

## Files

**Create:**
- `app/src/main/java/com/justme/xtls_core_proxy/vpn/TunnelInterfaceOwner.kt`
- `app/src/main/java/com/justme/xtls_core_proxy/vpn/TunnelRole.kt`
- `app/src/main/java/com/justme/xtls_core_proxy/vpn/TunnelOwnerTransitions.kt`
- `app/src/test/java/com/justme/xtls_core_proxy/vpn/TunnelInterfaceOwnerTest.kt`
- `app/src/test/java/com/justme/xtls_core_proxy/vpn/TunnelOwnerTransitionsTest.kt`

**Modify:**
- `app/src/main/java/com/justme/xtls_core_proxy/vpn/SessionLifecycleDecision.kt`
- `app/src/main/java/com/justme/xtls_core_proxy/vpn/XrayVpnService.kt`
- `app/src/test/java/com/justme/xtls_core_proxy/vpn/SessionLifecycleDecisionTest.kt`
- `app/src/test/java/com/justme/xtls_core_proxy/vpn/SessionLifecycleRotationTest.kt`

## Interfaces Produced

```kotlin
internal sealed interface TunnelInterfaceOwner<out Handle> {
    data object None : TunnelInterfaceOwner<Nothing>
    class LiveProxy<Handle>(val handle: Handle) : TunnelInterfaceOwner<Handle>
    class UnreadRotationBridge<Handle>(val handle: Handle) : TunnelInterfaceOwner<Handle>
    class UnreadBlackhole<Handle>(val handle: Handle) : TunnelInterfaceOwner<Handle>
}

internal enum class TunnelRole {
    NONE,
    LIVE_PROXY,
    UNREAD_ROTATION_BRIDGE,
    UNREAD_BLACKHOLE,
}

internal fun <Handle> TunnelInterfaceOwner<Handle>.toRole(): TunnelRole
```

The three handle-bearing variants are deliberately ordinary `class` declarations, matching the design's specified identity semantics; only `None` is a `data object`.

---

### Task 1: Establish the owner-phase baseline

**Files:**
- Read/search: `app/src/main/java/com/justme/xtls_core_proxy/vpn/XrayVpnService.kt`
- Read/search: `app/src/main/java/com/justme/xtls_core_proxy/vpn/SessionLifecycleDecision.kt`
- Tests: existing lifecycle and state suites

**Interfaces:**
- Consumes: current three-field owner implementation and current top-level decision APIs
- Produces: a recorded baseline and an exhaustive owner-reference inventory; no production changes

- [ ] **Step 1: Record the current field and decision references**

Run:

```powershell
rg -n "tunInterface|tunInterfaceKind|rotationBridgeInterface|containmentForGiveUp|classifyGiveUpOutcome|shouldEstablishRotationBridge|shouldAbortRotationForMissingBridge|shouldFunnelRotationReservationRefusal|shouldRestoreUnprotectedRearm" app/src/main/java app/src/test
```

The inventory must include, at minimum, these service sites:

- field declarations and KDocs;
- `bringUpTunnel` ownership check, retained-bridge establish, live-owner install, and bounded prior-bridge cleanup;
- `tearDownTunnelLocked` stop-Xray, then clear/close path (today closes inside `try` and clears in `finally`; the migration installs `None` before close);
- `rotateTunnel` reservation refusal, bridge creation, missing-bridge abort, and candidate selection;
- candidate bring-up failure bridge restoration;
- `giveUpRotationLocked` state stand-down, held-kind capture, containment action, and outcome classification;
- `announceDroppedDeferredKillLocked` tunnel-presence calculation;
- `clearGiveUpStateOnRecovery` no-tunnel guard;
- `establishUnreadTunnelLocked`, `establishBlackholeTunnelLocked`, `establishRotationBridgeLocked`, `adoptRotationBridgeLocked`, and `releaseRotationBridgeLocked`;
- `applyFailoverPreferences` re-enable guard;
- `stopVpn` bridge cleanup, no-live-session early return, and final teardown.

- [ ] **Step 2: Run the existing baseline suites**

Run each command serially:

```powershell
./gradlew.bat :app:testDebugUnitTest --tests "*.SessionLifecycleDecisionTest"
./gradlew.bat :app:testDebugUnitTest --tests "*.SessionLifecycleRotationTest"
./gradlew.bat :app:testDebugUnitTest --tests "*.TileClickDecisionTest"
./gradlew.bat :app:testDebugUnitTest --tests "*.MainActivityStateTest"
./gradlew.bat :app:testDebugUnitTest --tests "*.ConnectActionTest"
./gradlew.bat :app:testDebugUnitTest --tests "*.VpnConnectionLabelsTest"
```

Expected: all current suites pass before any owner change.

- [ ] **Step 3: Commit only the baseline test additions if needed**

Do not commit generated reports. If the baseline exposes a pre-existing failure, stop this plan and record the failure instead of changing production code under the owner migration.

```bash
git status --short
```

---

### Task 2: Add the sealed owner and derived role

**Files:**
- Create: `app/src/main/java/com/justme/xtls_core_proxy/vpn/TunnelInterfaceOwner.kt`
- Create: `app/src/main/java/com/justme/xtls_core_proxy/vpn/TunnelRole.kt`
- Test: `app/src/test/java/com/justme/xtls_core_proxy/vpn/TunnelInterfaceOwnerTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces: the exact owner and role types listed above

- [ ] **Step 1: Write the JVM tests first**

Create tests using token handles and assert both handle retention and role projection:

```kotlin
package com.justme.xtls_core_proxy.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelInterfaceOwnerTest {
    @Test
    fun `all four owner variants are constructible`() {
        val none: TunnelInterfaceOwner<String> = TunnelInterfaceOwner.None
        assertTrue(none is TunnelInterfaceOwner.None)
        assertEquals("live", (TunnelInterfaceOwner.LiveProxy("live")).handle)
        assertEquals("bridge", (TunnelInterfaceOwner.UnreadRotationBridge("bridge")).handle)
        assertEquals("blackhole", (TunnelInterfaceOwner.UnreadBlackhole("blackhole")).handle)
    }

    @Test
    fun `role projection is exhaustive`() {
        val owners = listOf(
            TunnelInterfaceOwner.None,
            TunnelInterfaceOwner.LiveProxy("live"),
            TunnelInterfaceOwner.UnreadRotationBridge("bridge"),
            TunnelInterfaceOwner.UnreadBlackhole("blackhole"),
        )
        assertEquals(
            listOf(
                TunnelRole.NONE,
                TunnelRole.LIVE_PROXY,
                TunnelRole.UNREAD_ROTATION_BRIDGE,
                TunnelRole.UNREAD_BLACKHOLE,
            ),
            owners.map { it.toRole() },
        )
    }
}
```

- [ ] **Step 2: Implement the exact sealed owner**

Create `TunnelInterfaceOwner.kt` with the interface shown in the plan header. Do not add Android imports, close behavior, lifecycle state, or Builder data.

- [ ] **Step 3: Implement the exact role projection**

Create `TunnelRole.kt` with the four enum values and an exhaustive `when` over all four sealed variants. The role must not be stored in a service field.

- [ ] **Step 4: Run the focused JVM test**

```powershell
./gradlew.bat :app:testDebugUnitTest --tests "*.TunnelInterfaceOwnerTest"
```

Expected: PASS.

- [ ] **Step 5: Commit the type seam**

```bash
git add app/src/main/java/com/justme/xtls_core_proxy/vpn/TunnelInterfaceOwner.kt app/src/main/java/com/justme/xtls_core_proxy/vpn/TunnelRole.kt app/src/test/java/com/justme/xtls_core_proxy/vpn/TunnelInterfaceOwnerTest.kt
git commit -S -m "feat(vpn): add sealed tunnel interface owner"
```

---

### Task 3: Add pure owner transitions and exhaustive transition tests

**Files:**
- Create: `app/src/main/java/com/justme/xtls_core_proxy/vpn/TunnelOwnerTransitions.kt`
- Test: `app/src/test/java/com/justme/xtls_core_proxy/vpn/TunnelOwnerTransitionsTest.kt`

**Interfaces:**
- Consumes: `TunnelInterfaceOwner<Handle>` and the existing top-level `SessionTunnelState`
- Produces: `PreparationResult`, `ReleaseResult`, install functions, bridge adoption, and release functions

- [ ] **Step 1: Use the existing top-level state declaration**

Import the state directly:

```kotlin
import com.justme.xtls_core_proxy.vpn.SessionTunnelState
```

Do not write `SessionLifecycleDecision.SessionTunnelState`; no such nested type exists.

- [ ] **Step 2: Write the preparation tests**

Cover every allowed case and every invalid state/role combination. Preparation does **not** clear the bridge. The allowed cases are exactly:

```kotlin
prepareRealEstablishment(None, STARTING)      -> owner stays None, priorBridge null
prepareRealEstablishment(None, REVIVING)      -> owner stays None, priorBridge null
prepareRealEstablishment(Bridge(h), ROTATING) -> owner stays Bridge(h), priorBridge h
```

`priorBridge` on the `ROTATING` case is the same handle the owner still holds. It is not a handle to close before `establish()`, and the returned owner is not `None`. Initial start and revive have no bridge. The test must reject all of these:

- `STARTING` with `LiveProxy`, `UnreadRotationBridge`, or `UnreadBlackhole`;
- `REVIVING` with `LiveProxy`, `UnreadRotationBridge`, or `UnreadBlackhole`;
- `ROTATING` with `None`, `LiveProxy`, or `UnreadBlackhole`;
- every `PAUSED`, `CONNECTED`, and `STOPPED` combination with every owner role.

Use `org.junit.Assert.assertEquals`, `org.junit.Assert.assertTrue`, and `org.junit.Assert.assertThrows`. Do not use `kotlin.test` (`assertFailsWith`, `assertIs`, or `kotlin.test.assertEquals`); this module's JVM tests are JUnit4-only. Include the state and role in each failure message. Cover the three allowed cases above and every rejected state/role combination listed here, with no omissions. A rejected case has this shape:

```kotlin
val error = assertThrows(IllegalStateException::class.java) {
    TunnelOwnerTransitions.prepareRealEstablishment(
        owner = TunnelInterfaceOwner.LiveProxy("live"),
        expectedState = SessionTunnelState.STARTING,
    )
}
assertTrue(error.message!!.contains("STARTING"))
assertTrue(error.message!!.contains("LIVE_PROXY"))
```

Repeat that shape for every rejected combination. Allowed `STARTING` and `REVIVING` cases use `assertTrue(result.owner is TunnelInterfaceOwner.None)` and `assertEquals(null, result.priorBridge)`. The allowed `ROTATING` case uses `assertTrue(result.owner is TunnelInterfaceOwner.UnreadRotationBridge)` and `assertSame` (or equal token identity) between `result.priorBridge` and the bridge handle the owner still holds. Do not assert that preparation returns `None` for a bridge.

- [ ] **Step 3: Implement `TunnelOwnerTransitions` without side effects**

The required pure shapes are:

```kotlin
internal object TunnelOwnerTransitions {
    data class PreparationResult<Handle>(
        val owner: TunnelInterfaceOwner<Handle>,
        val priorBridge: Handle?,
    )

    data class LiveInstallResult<Handle>(
        val owner: TunnelInterfaceOwner.LiveProxy<Handle>,
        val priorBridgeToClose: Handle?,
    )

    data class ReleaseResult<Handle>(
        val owner: TunnelInterfaceOwner<Handle>,
        val handleToClose: Handle?,
    )

    fun <Handle> prepareRealEstablishment(
        owner: TunnelInterfaceOwner<Handle>,
        expectedState: SessionTunnelState,
    ): PreparationResult<Handle>

    fun <Handle> installLiveProxy(
        owner: TunnelInterfaceOwner<Handle>,
        handle: Handle,
    ): LiveInstallResult<Handle>

    fun <Handle> installRotationBridge(
        owner: TunnelInterfaceOwner<Handle>,
        handle: Handle,
    ): TunnelInterfaceOwner.UnreadRotationBridge<Handle>

    fun <Handle> installBlackhole(
        owner: TunnelInterfaceOwner<Handle>,
        handle: Handle,
    ): TunnelInterfaceOwner.UnreadBlackhole<Handle>

    fun <Handle> adoptBridgeAsBlackhole(
        owner: TunnelInterfaceOwner<Handle>,
    ): TunnelInterfaceOwner.UnreadBlackhole<Handle>

    fun <Handle> release(
        owner: TunnelInterfaceOwner<Handle>,
    ): ReleaseResult<Handle>
}
```

`release` must return `None` before the service closes `handleToClose` on a terminal release; it must not close anything itself. `prepareRealEstablishment` keeps `UnreadRotationBridge` authoritative for `ROTATING` and returns that same handle as `priorBridge`; it must not return `None` or a handle the service closes before `establish()`. `installLiveProxy` accepts only `None` or `UnreadRotationBridge`. From `None` (initial start and revive) it installs `LiveProxy` and returns a null cleanup handle. From `UnreadRotationBridge` it installs `LiveProxy(candidate)` immediately and returns the old bridge only as `priorBridgeToClose`, a bounded local cleanup handle that is not a second owner. Install of a rotation bridge or blackhole still accepts only `None`. Adoption accepts only `UnreadRotationBridge` and returns the same handle in `UnreadBlackhole`. A null/throw from `Builder.establish()` never calls `installLiveProxy`.

- [ ] **Step 4: Test install, adoption, release, and same-handle behavior**

Assert with `org.junit.Assert.assertEquals`, `org.junit.Assert.assertTrue`, and `org.junit.Assert.assertThrows(IllegalStateException::class.java)` (no `kotlin.test`):

- each unread install succeeds from `None` and rejects all three occupied roles;
- `installLiveProxy` succeeds from `None` with a null cleanup handle, and from `UnreadRotationBridge` returns `LiveProxy` plus the old bridge handle as `priorBridgeToClose` without clearing that handle before the caller closes it;
- `installLiveProxy` rejects `LiveProxy` and `UnreadBlackhole`;
- a retained-bridge preparation for `ROTATING` leaves `UnreadRotationBridge` authoritative;
- bridge adoption retains object identity of a token handle;
- release of each open role returns `None` plus that role's handle;
- release of `None` returns `None` plus `null`;
- no transition mutates or closes a handle.

- [ ] **Step 5: Run the exhaustive transition suite**

```powershell
./gradlew.bat :app:testDebugUnitTest --tests "*.TunnelOwnerTransitionsTest"
```

Expected: PASS.

- [ ] **Step 6: Commit the pure transition seam**

```bash
git add app/src/main/java/com/justme/xtls_core_proxy/vpn/TunnelOwnerTransitions.kt app/src/test/java/com/justme/xtls_core_proxy/vpn/TunnelOwnerTransitionsTest.kt
git commit -S -m "feat(vpn): add pure tunnel owner transitions"
```

---

### Task 4: Migrate lifecycle decisions without changing semantics

**Files:**
- Modify: `app/src/main/java/com/justme/xtls_core_proxy/vpn/SessionLifecycleDecision.kt`
- Modify: `app/src/test/java/com/justme/xtls_core_proxy/vpn/SessionLifecycleDecisionTest.kt`
- Modify: `app/src/test/java/com/justme/xtls_core_proxy/vpn/SessionLifecycleRotationTest.kt`

**Interfaces:**
- Consumes: `TunnelRole`
- Produces: role-based owner decision inputs with all unrelated decision signatures unchanged

- [ ] **Step 1: Preserve the current containment precedence in tests**

`containmentForGiveUp` must preserve the existing `hasTunnel`-wins behavior while replacing the two booleans with one role. The required truth table is:

| Role | State | Result |
|---|---|---|
| `LIVE_PROXY` | any state | `NONE` |
| `UNREAD_BLACKHOLE` | any state | `NONE` |
| `UNREAD_ROTATION_BRIDGE` | `CONNECTED` | `ADOPT_ROTATION_BRIDGE` |
| `UNREAD_ROTATION_BRIDGE` | any other state | `NONE` |
| `NONE` | `CONNECTED` | `ESTABLISH_BLACKHOLE` |
| `NONE` | any other state | `NONE` |

This preserves the current implementation's `hasTunnel` check before its state check; do not place the state guard first.

- [ ] **Step 2: Change only the approved function signatures**

Use these exact top-level signatures:

```kotlin
internal fun containmentForGiveUp(
    tunnelRole: TunnelRole,
    tunnelState: SessionTunnelState,
): GiveUpContainment

internal fun shouldEstablishRotationBridge(
    tunnelRole: TunnelRole,
    tunnelState: SessionTunnelState,
): Boolean

internal fun shouldAbortRotationForMissingBridge(
    bridgeRequired: Boolean,
    resultingRole: TunnelRole,
): Boolean

internal fun shouldFunnelRotationReservationRefusal(
    running: Boolean,
    activeSessionEpoch: Long?,
    callbackSessionEpoch: Long,
    tunnelState: SessionTunnelState,
    tunnelRole: TunnelRole,
): Boolean

internal fun classifyGiveUpOutcome(
    resultingRole: TunnelRole,
): FailoverGiveUpOutcome

internal fun shouldRestoreUnprotectedRearm(
    failoverEnabled: Boolean,
    giveUpOutcome: FailoverGiveUpOutcome?,
    tunnelRole: TunnelRole,
    rearmJobActive: Boolean,
): Boolean
```

Keep `deferredKillNoticeLabel(tunnelStillUp: Boolean)` unchanged because the design explicitly retains that semantic boolean. Do not alter unrelated lifecycle decisions.

- [ ] **Step 3: Implement the role mappings**

Use these rules:

```kotlin
containmentForGiveUp:
    LIVE_PROXY or UNREAD_BLACKHOLE -> NONE
    non-CONNECTED -> NONE
    UNREAD_ROTATION_BRIDGE -> ADOPT_ROTATION_BRIDGE
    NONE -> ESTABLISH_BLACKHOLE

shouldEstablishRotationBridge:
    tunnelRole == NONE && tunnelState == ROTATING

shouldAbortRotationForMissingBridge:
    bridgeRequired && resultingRole != UNREAD_ROTATION_BRIDGE

shouldFunnelRotationReservationRefusal:
    accepts current callback && tunnelState == CONNECTED &&
    tunnelRole == UNREAD_ROTATION_BRIDGE

classifyGiveUpOutcome:
    LIVE_PROXY -> CONTAINED_BY_LIVE_TUNNEL
    UNREAD_ROTATION_BRIDGE or UNREAD_BLACKHOLE -> CONTAINED_BY_BLACKHOLE
    NONE -> UNPROTECTED

shouldRestoreUnprotectedRearm:
    failoverEnabled && giveUpOutcome == UNPROTECTED &&
    tunnelRole == NONE && !rearmJobActive
```

The bridge role is mapped to blackhole in `classifyGiveUpOutcome` as a defensive packet-truth mapping, but the service must adopt it before classification so the ownership invariant ends in `UnreadBlackhole`.

- [ ] **Step 4: Update all existing decision tests**

Update calls in both decision test files to use top-level functions and `TunnelRole`. Retain tests for:

- live and unread owner precedence in every non-`CONNECTED` state;
- bridge adoption only in `CONNECTED`;
- every role/state combination for bridge establishment;
- every `bridgeRequired`/resulting-role combination;
- current-session and stale-session reservation refusal;
- the disabled queued-retry case: `CONNECTED` plus `UNREAD_ROTATION_BRIDGE` must funnel to give-up;
- all six `VpnConnectionState` consumers in their existing separate suites;
- all four `shouldRestoreUnprotectedRearm` conditions, including disabled and already-active re-arm.

- [ ] **Step 5: Run the decision suites**

```powershell
./gradlew.bat :app:testDebugUnitTest --tests "*.SessionLifecycleDecisionTest"
./gradlew.bat :app:testDebugUnitTest --tests "*.SessionLifecycleRotationTest"
```

Expected: PASS with no behavior-changing test deletion.

- [ ] **Step 6: Commit the decision migration**

```bash
git add app/src/main/java/com/justme/xtls_core_proxy/vpn/SessionLifecycleDecision.kt app/src/test/java/com/justme/xtls_core_proxy/vpn/SessionLifecycleDecisionTest.kt app/src/test/java/com/justme/xtls_core_proxy/vpn/SessionLifecycleRotationTest.kt
git commit -S -m "refactor(vpn): use derived tunnel roles in lifecycle decisions"
```

---

### Task 5: Replace every service owner read and write

**Files:**
- Modify: `app/src/main/java/com/justme/xtls_core_proxy/vpn/XrayVpnService.kt`

**Interfaces:**
- Consumes: sealed owner, pure transitions, migrated top-level decisions
- Produces: one authoritative `tunnelOwner` field and no old field/kind references

- [ ] **Step 1: Replace the fields and verify no shadow state is introduced**

Replace the three declarations with exactly:

```kotlin
private var tunnelOwner: TunnelInterfaceOwner<ParcelFileDescriptor> = TunnelInterfaceOwner.None
```

Remove `TunInterfaceKind`; do not retain a compatibility field.

- [ ] **Step 2: Migrate `bringUpTunnel` to establish-first handover**

Keep all existing off-lock work, epoch checks, config/geo preparation, and split read where they are. Under the existing lock, replace the current production release-then-establish block (`XrayVpnService.kt` handover comment and `releaseRotationBridgeLocked` call immediately before `builder.establish()`). That block is the baseline being replaced, not the target:

1. Re-check `ownsTunnelTransitionLocked(sessionEpoch, expectedState)` and preserve the active-transition precondition.
2. Call `prepareRealEstablishment(tunnelOwner, expectedState)`. For `ROTATING`, the returned owner stays `UnreadRotationBridge`. Do not assign `None` and do not close the bridge here. `STARTING` and `REVIVING` stay `None` and have no prior bridge.
3. Call `builder.establish()` while that bridge owner is still authoritative. Do not emit a release audit before this call.
4. If `establish()` throws or returns `null` while the authoritative owner is `UnreadRotationBridge`, leave that owner and the same bridge handle unchanged. Do not call `tearDownTunnelLocked()` on this path: after this migration that helper releases whatever owner is authoritative, so calling it here would close the retained bridge and open a new fd. Do not close or detach the bridge, do not install a live owner, and do not emit the release audit. A null result remains `throw IllegalStateException("VpnService.establish() returned null")`. The existing rotation failure funnel then decides bridge retention, retry, give-up, or `UNPROTECTED` handling against that same handle. `STARTING` and `REVIVING` have no bridge; a null/throw there still fails without installing a live owner.
5. If `establish()` returns a non-null candidate, call `installLiveProxy` immediately and assign the returned `LiveProxy`. The old bridge is now only `priorBridgeToClose`, a bounded local cleanup handle. It is not a second service owner.
6. Attempt one `close()` on that local handle, inside `try { priorBridgeToClose?.close() } catch`. Log the current warning text only when close throws: `Failover: rotation bridge close warning: ${error.message}`. Do not put the bridge back, re-adopt it, detach it, or retry its raw fd if close throws. A null handle (initial start and revive) is a no-op and emits neither the warning nor the release audit. No log, repository read, suspension, config work, notification, or other unrelated operation is inserted between the non-null `establish()`, the `LiveProxy` install, and this close.
7. After that close attempt, append the four deferred diagnostic logs in this order, and only these four are diagnostic appends: `TUN established with fd=…`; then the close warning retained from step 6, only if close failed; then, only when a prior bridge handle existed, the exact audit `Failover: rotation bridge released (handing over to the newly established tunnel)`; then `Using geofiles from …`. Do not emit the release audit before `TUN established`.
8. Only those four log appends are best-effort and isolated from the bring-up failure funnel. If one throws, do not record a replacement-failure state, do not tear down or reclassify the installed `LiveProxy`, and continue the remaining post-establishment steps. Losing a diagnostic is acceptable; losing owner truth or packet containment is not.
9. After those four logs, `XrayBridge.registerProtector` and `XrayBridge.startXray` stay operational bring-up steps in that order, followed by `Xray core started`. They are not diagnostic appends. They keep their existing failure behavior and can still enter the existing bring-up failure funnel. A failure of one of the four log appends must not itself trigger that funnel or be treated as a protector or Xray failure.

Do not call the generic `release` helper for this handover. It would clear the authoritative owner before establishment. A bridge-only release remains the terminal/pause path in Step 4, not candidate handover.

Current production baseline, superseded by this step: `releaseRotationBridgeLocked("handing over to the newly established tunnel")` runs before `builder.establish()`, and the release audit is therefore emitted before `TUN established with fd=…`. Do not preserve that order.

- [ ] **Step 3: Migrate `tearDownTunnelLocked` exactly once**

This is live-owner teardown, not the bridge-only release in Step 4. Keep `stopXray()` first, including its existing `onFailure` warning (`"Xray stop warning: ${error.message}"`). Then release the one authoritative owner exactly once:

1. Call `TunnelOwnerTransitions.release(tunnelOwner)`. The pure helper returns `None` plus the handle to close; it does not close anything.
2. Install that returned `None` into `tunnelOwner` before any close. This is the spec's clear-before-close order (`design.md` production transition seam). Do not close first and clear in `finally`: today's `tearDownTunnelLocked` does that, and a throwing `close()` leaves the old owner installed until `finally` runs.
3. Close `handleToClose` inside the existing `try/catch`, not before the assignment and not as the thing that restores ownership:
   ```kotlin
   try {
       released.handleToClose?.close()
   } catch (error: Throwable) {
       LogRepository.append("TUN close warning: ${error.message}")
   }
   ```
   Keep this warning. A throwing close must not reassign `tunnelOwner` or otherwise restore or retain the old owner; ownership is already `None`.
4. Do not separately touch a bridge field, and do not call `releaseRotationBridgeLocked` from this method. Step 4's bridge-only path is a different exit: it returns immediately unless the owner is `UnreadRotationBridge`, then uses the same install-`None`-then-`try/catch`-close order with the rotation-bridge warning text. Live teardown here closes whichever handle `release` returned, including a live proxy or unread blackhole.

- [ ] **Step 4: Make bridge release role-specific**

Rewrite `releaseRotationBridgeLocked(reason)` as:

```kotlin
private fun releaseRotationBridgeLocked(reason: String) {
    val bridge = tunnelOwner as? TunnelInterfaceOwner.UnreadRotationBridge
        ?: return
    val released = TunnelOwnerTransitions.release(bridge)
    tunnelOwner = released.owner
    try {
        released.handleToClose?.close()
    } catch (error: Throwable) {
        LogRepository.append("Failover: rotation bridge close warning: ${error.message}")
    }
    LogRepository.append("Failover: rotation bridge released ($reason)")
}
```

Adapt the exact Kotlin syntax if needed for generic smart casts, but preserve the critical property: a live proxy or unread blackhole is never closed by this bridge-only method. Keep the clear-before-close order already in the snippet: assign `released.owner` (`None`) before `close()`, keep the `try/catch` warning, and do not restore `tunnelOwner` if `close()` throws.

- [ ] **Step 5: Migrate unread tunnel creation and ownership installation**

In `establishUnreadTunnelLocked`, replace the two-field precondition with:

```kotlin
check(tunnelOwner is TunnelInterfaceOwner.None) {
    "Cannot establish an unread TUN while this session already owns one"
}
```

Keep the check inside the existing `try/catch` and preserve the existing `LogRepository` failure behavior.

Then use:

```kotlin
tunnelOwner = TunnelOwnerTransitions.installBlackhole(tunnelOwner, pfd)
```

for `establishBlackholeTunnelLocked`, and:

```kotlin
tunnelOwner = TunnelOwnerTransitions.installRotationBridge(tunnelOwner, pfd)
```

for `establishRotationBridgeLocked`.

For adoption, require `UnreadRotationBridge`, call `adoptBridgeAsBlackhole`, and retain the same descriptor without closing it:

```kotlin
tunnelOwner = TunnelOwnerTransitions.adoptBridgeAsBlackhole(tunnelOwner)
```

Preserve the existing adoption log and return values.

- [ ] **Step 6: Migrate every decision call site in `rotateTunnel`**

At reservation refusal, pass `tunnelOwner.toRole()` to `shouldFunnelRotationReservationRefusal`. The refusal rule must still be evaluated while `sessionTunnelState == CONNECTED`; a bridge-held refusal funnels to `giveUpRotationLocked`, while a non-bridge refusal releases only a bridge if one exists and preserves the unprotected re-arm logic.

At bridge creation and missing-bridge abort, pass `tunnelOwner.toRole()` to `shouldEstablishRotationBridge` and pass the role after the establishment attempt to `shouldAbortRotationForMissingBridge`.

At candidate failure, branch on the owner that `bringUpTunnel` actually left behind. Do not treat every failure as a half-built live owner.

While `ownsTunnelTransitionLocked(session.epoch, SessionTunnelState.ROTATING)` still holds:

1. Record the candidate failure in `episodeFailedIds`.
2. If the authoritative owner is still `UnreadRotationBridge`, this was a pre-establishment failure or replacement `establish()` returned null or threw before any live owner was installed. Do **not** call `tearDownTunnelLocked()`. That call would release the authoritative bridge. Preserve the same bridge handle. Do not close it, detach it, or establish a replacement fd for this failure. Continue with that same owner.
3. Call `tearDownTunnelLocked()` only when a non-null candidate was installed as `LiveProxy` and a later operational bring-up step failed (`registerProtector` or `startXray`). That teardown closes the newly installed live candidate only. It must not run while the owner is still the unread bridge.
4. Reopen a bridge only in the case from item 3, and only when the existing role/state predicate authorizes it. The null/throw case already holds the original bridge, so it must not create another fd.
5. Abort to give-up if the resulting role is not `UnreadRotationBridge` when a bridge was required.
6. While ownership still holds, return to `CONNECTED` and roll back `currentProfileId`.

After the guarded block, preserve the existing unconditional failure log and enqueue `rotateTunnel(session.epoch)`. A superseded failure still enqueues; the later reservation check refuses that stale retry. KDoc on `bringUpTunnel`, `tearDownTunnelLocked`, and the candidate-failure arm must say this split explicitly: a null/throw while `UnreadRotationBridge` is authoritative preserves that handle; `tearDownTunnelLocked()` is only for a `LiveProxy` installed after non-null `establish()`.

Do not move DAO reads or `FailoverPoolResolver.resolve` under the lock as part of this migration.

- [ ] **Step 7: Migrate give-up classification and other owner reads**

In `giveUpRotationLocked`:

- preserve deferred-kill discharge, state stand-down, episode reset, monitor stop, and screen-receiver reconciliation ordering;
- use `tunnelOwner.toRole()` for `containmentForGiveUp`;
- adopt a bridge or establish a blackhole through the existing service methods;
- classify from the resulting `tunnelOwner.toRole()` with `classifyGiveUpOutcome(resultingRole = ...)`;
- preserve all notification, `LogRepository` state, give-up-line, retry, and stop-service behavior.

Replace these remaining reads with role semantics without changing their meaning:

- `announceDroppedDeferredKillLocked`: `tunnelStillUp = tunnelOwner !is TunnelInterfaceOwner.None`;
- `clearGiveUpStateOnRecovery`: return only when `tunnelOwner is TunnelInterfaceOwner.None`;
- `applyFailoverPreferences`: pass `tunnelRole = tunnelOwner.toRole()` to `shouldRestoreUnprotectedRearm`;
- `stopVpn` early return: use `tunnelOwner is TunnelInterfaceOwner.None` after bridge-only cleanup;
- any KDoc or comment that names the old fields.

- [ ] **Step 8: Preserve terminal cleanup and dispatcher boundaries**

Review the complete `XrayVpnService` lifecycle after the sweep:

- `onDestroy`: cancel `serviceScope`, cancel `tunnelOpScope`, synchronously call `stopVpn`, then `super.onDestroy`;
- `onRevoke`: synchronously call `stopVpn` before `super.onRevoke`;
- notification/user `ACTION_STOP`: remains serialized on `tunnelOpScope`;
- `stopVpn`: releases any bridge before its no-live-session early return, then lets `tearDownTunnelLocked` release the one remaining owner exactly once;
- no new `await`, `runBlocking`, suspend call, or dispatcher handoff is added to `stopVpn`.

- [ ] **Step 9: Compile and prove the old references are gone**

Run:

```powershell
rg -n "tunInterface|tunInterfaceKind|TunInterfaceKind|rotationBridgeInterface" app/src/main/java app/src/test docs/features AGENTS-ARCHITECTURE.md AGENTS-DEVELOPMENT.md
./gradlew.bat :app:compileDebugKotlin
```

Expected: the source search returns no old owner field/type references after documentation is updated in the verification plan; compilation succeeds.

- [ ] **Step 10: Run JVM regression tests**

```powershell
./gradlew.bat :app:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 11: Commit the owner migration**

```bash
git add app/src/main/java/com/justme/xtls_core_proxy/vpn/XrayVpnService.kt
git commit -S -m "refactor(vpn): consolidate TUN ownership into sealed owner"
```

---

### Task 6: Phase 2 stop/go gate

**Files:**
- No production files; verification and human review

**Interfaces:**
- Consumes: completed Tasks 1-5
- Produces: authorization to begin the capture plan

- [ ] **Step 1: Run the focused and full JVM suites**

```powershell
./gradlew.bat :app:testDebugUnitTest --tests "*.TunnelInterfaceOwnerTest"
./gradlew.bat :app:testDebugUnitTest --tests "*.TunnelOwnerTransitionsTest"
./gradlew.bat :app:testDebugUnitTest --tests "*.SessionLifecycleDecisionTest"
./gradlew.bat :app:testDebugUnitTest --tests "*.SessionLifecycleRotationTest"
./gradlew.bat :app:testDebugUnitTest
```

- [ ] **Step 2: Review every owner mutation manually**

The reviewer must inspect every `tunnelOwner =` assignment and verify it is one of the transition results, every close installs the returned `None` first and keeps the close inside `try/catch` so a throwing close cannot restore or retain the old owner, and no bridge-only path can close a live owner. Review every current owner site from Task 1, not only the declarations.

- [ ] **Step 3: Compile the app**

```powershell
./gradlew.bat :app:compileDebugKotlin
```

- [ ] **Step 4: Execute device QA before capture work**

Exercise all of these on a real device or approved emulator:

1. normal start and user Stop;
2. successful establish-first rotation handover: bridge retained through `establish()`, `LiveProxy` installed before the old bridge is closed, release audit after `TUN established`;
3. candidate `establish()` null/throw leaving the original unread bridge unchanged for the existing retry/give-up path;
4. candidate bring-up failure followed by bridge restoration and another candidate;
5. give-up adopting the existing bridge without a second unread TUN;
6. kill-switch pause and subsequent revive, with no release audit on the revive;
7. VPN revoke;
8. destruction or Stop during rotation;
9. no descriptor or pending cleanup handle after pause and full stop;
10. truthful `BLACKHOLED` and `ERROR`/`UNPROTECTED` presentation.

Record evidence for descriptor cleanup and same-handle bridge adoption. Packet capture across the whole rotation, not the log order, is the no-underlying-packet-escape proof. Do not begin the capture plan until all cases pass or a separately designed defect is recorded.

- [ ] **Step 5: Commit the gate record if the repository uses one**

Do not alter the approved spec to hide a failed gate. A failed gate blocks Phase 3 and must be reported with the failing scenario and evidence.

## Owner Plan Self-Review

- [ ] Every owner-dependent decision from the spec has an exact task.
- [ ] `shouldAbortRotationForMissingBridge` uses `resultingRole`, not a Boolean.
- [ ] `classifyGiveUpOutcome` uses only the resulting role.
- [ ] `shouldRestoreUnprotectedRearm` retains enable and active-job guards.
- [ ] Top-level Kotlin declarations and actual package names are used.
- [ ] Current `hasTunnel` precedence is preserved for live and unread owners in non-`CONNECTED` states.
- [ ] Candidate handover retains `UnreadRotationBridge` through `establish()` and does not clear it first.
- [ ] Null/throw establishment while the owner is `UnreadRotationBridge` leaves that same handle authoritative, does not call `tearDownTunnelLocked()`, and emits no release audit.
- [ ] Successful handover installs `LiveProxy` before the one bounded prior-bridge close.
- [ ] The release audit is after `TUN established with fd=…`, and initial start/revive emit none.
- [ ] No generic bridge-release wrapper can close a live tunnel.
- [ ] No old field/type references remain after this plan.
- [ ] The Phase 2 stop/go gate is complete before capture work.

## Change Control

### 2026-09-21 — Owner plan split and correction

- **Previous decision:** The monolithic plan supplied partial role rewrites and illustrative service snippets.
- **Replacement decision:** Owner migration is isolated here with an exhaustive transition matrix, a complete current call-site inventory, and source-grounded top-level signatures.
- **Reason:** Review found omitted owner reads/writes, incorrect role precedence, wrong decision signatures, and a bridge-release wrapper that could close a live tunnel.
- **Affected sections:** Tasks, interfaces, tests, service migration, and Phase 2 gate.

### 2026-09-21 — JUnit, teardown, and handover correction

Superseded for candidate-handover order by the 2026-09-22 entry below. Retained as history. Do not implement its release-before-`establish()` sentence.

- **Previous decision:** JVM examples used `kotlin.test` APIs absent from the module classpath; live teardown did not spell out warning-safe clear-before-close; handover wording could be read as deleting the existing bridge-release audit log; candidate retry appeared inside the `ROTATING` ownership guard.
- **Replacement decision:** Examples use JUnit4 assertions; teardown installs `None` before closing inside the existing warning path; handover retains the exact release log and permits no new work after it before `Builder.establish()`; only rollback mutations are ownership-guarded, while the existing failure log and retry enqueue remain outside that guard.
- **Reason:** Source review found compile failure in the examples and control-flow wording that could change close-failure cleanup, QA-visible log ordering, or superseded-failure behavior.
- **Affected sections:** Global Constraints, Tasks 2 and 3 test guidance, Task 5 Steps 2, 3, 4, and 6.

### 2026-09-22 — Establish-first handover alignment

- **Previous decision:** Real handover was release-then-establish. `prepareRealEstablishment` for `ROTATING` returned `None` plus `bridgeToClose`; the service assigned `None`, closed the bridge, emitted `Failover: rotation bridge released (handing over to the newly established tunnel)`, and only then called `Builder.establish()`. That order is still the current production baseline in `XrayVpnService.kt`.
- **Replacement decision:** `prepareRealEstablishment` keeps `UnreadRotationBridge` authoritative and returns it as `priorBridge`. `establish()` runs against that owner. `null`/throw leaves the owner unchanged, with no release audit, close, detach, or live owner. A non-null candidate is installed as `LiveProxy` immediately; the old bridge is `priorBridgeToClose`, closed once, never re-adopted, detached, or retried by raw fd. Close failure is non-fatal deferred diagnostic state. The existing close-warning text and the exact release audit are appended only after `TUN established with fd=…`, before the geofile/protector/Xray diagnostics. A diagnostic-append failure cannot enter the bring-up failure funnel or reclassify `LiveProxy`. Initial start and revive still have no bridge and no release audit. Packet capture across the whole rotation is the policy oracle.
- **Reason:** The 2026-09-22 spec amendment replaced release-first. Android's documented `Builder.establish()` contract leaves the existing interface and descriptor untouched when replacement creation fails, so releasing first creates an avoidable interface-free interval. This plan alignment does not implement the change and does not authorize `FailoverEngine`/`TunnelHost` work. The 2026-09-04 engine contract-recovery gate remains binding.
- **Affected sections:** Goal, Global Constraints, Task 1 inventory, Task 3 preparation/install API and tests, Task 5 Step 2, Task 6 device QA, Owner Plan Self-Review, and this Change Control section. The 2026-09-21 handover correction above is retained as history and is superseded for candidate-handover order.
