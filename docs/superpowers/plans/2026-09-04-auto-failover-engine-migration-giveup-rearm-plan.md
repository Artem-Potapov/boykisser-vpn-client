# Auto-Failover Engine Give-Up and Re-Arm Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **Execution is not authorized by plan authoring.** The required Phase-1 helper baseline is already owned by the rotation plan; the Phase-2 tasks execute only after Phase 1 and the separate timeout-correction gate pass.

**Goal:** Route the existing recovery timer through the passive engine using typed requests, preserving containment, retry allowance, original episode time, and synchronous stop.

**Architecture:** The service still performs one locked give-up settlement, owns its timer on `serviceScope`, and enqueues rotation on `tunnelOpScope`. Phase 1 exposes typed private helper exits to complete its 17-member adapter; Phase 2 changes the private schedule request and timer dispatch only. The approved effect-time check around timeout diagnostics and stop is a separately reviewed prerequisite, not an extraction side effect.

**Tech Stack:** Kotlin, coroutines, JUnit4, `kotlinx-coroutines-test`, existing lifecycle decisions and Android service effects.

**Spec:** `docs/superpowers/specs/2026-09-04-auto-failover-engine-migration-design.md`, sections Give-up settlement, Timer admission, Complete engine flow, and Phase 2. Also read the synchronized engine index and rotation plan.

## Global Constraints

- No implementation is authorized now. Later execution requires separate maintainer authorization, all structural-foundations index/owner/capture/verification gates, and a completed rotation phase.
- Gate timeout publication separately: after deciding STOP_SERVICE, a second lifecycle-lock acquisition must recheck running/epoch and cover the warning, both error surfaces, and synchronous `stopVpn` together. Source baseline `1798–1843` does not do this. Do not conceal the correction in callback migration.
- Service owns containment, owner/descriptor, outcome/line, pending kill, attempt timestamps, retry-consumed flag, episode timestamp, notifications, timer, settings, and clock. Engine owns none of them; its existing `onRearmTimer` has no scope, delay, or new policy.
- Keep `giveUpRotationLocked(sessionEpoch: Long, reason: String): GiveUpSettlement` service-private and called only under a validated lock. It is not a host method. Returned completion asserts all required settlement steps finished; no broad best-effort conversion of give-up errors.
- Give-up logs first, captures/clears deferred kill, normalizes ROTATING, clears failed IDs, stops monitor/reconciles receiver, settles containment or stands down, publishes surfaces, schedules last, then announces dropped kill.
- Live proxy, unread containment, and UNPROTECTED remain different packet truths and messages. Only a consumed UNPROTECTED give-up stops; contained outcomes never do.
- Scheduling, refusal, and DEFER never spend the single retry. Successful rotation reservation spends it before thrash admission. The original timestamp survives deferral and re-enable.
- `UNPROTECTED_UNATTEMPTED_RETRY_WINDOWS` stays `3`. CONNECTED chooses ATTEMPT even past the deadline. Use existing wall clock and fresh authoritative settings at existing boundaries; no monotonic-clock migration, fixed deadline snapshot, overflow policy, or new timing constant.
- Admission resets only `rotationAttempts`. Action takes a separate lock. No timer generation, extra liveness/enable veto, extra launch, or cancellation join. Cancellation propagates.
- `stopVpn` stays synchronous, with no await or dispatcher handoff. The replacing timer is a new `serviceScope` child, not a child of the cancelled timer; no suspension after scheduling replacement in DEFER.
- Existing accepted consumed-budget, healthy-window-reset, pause/disable, and containment-backstop policies are not fixes to make in this migration.
- JVM fake tests prove dispatch, not service containment or effect-time epoch safety. Human VPN-code review and device evidence are mandatory; missing evidence keeps the gate open.
- Run heavy Gradle tasks serially. Preserve device app data. No generated AAR/geo, local properties, IDE state, user/global configuration, commits, or pushes without their separate authorization; signing, if requested, uses `git commit -S` and stops on failure.

## Review Focus

1. **Disable or stop wins while a timer wakes:** admission rejects with no reset/dispatch; direct-entry tests cover terminal results, device QA covers the real guard.
2. **CONNECTED appears after the deferral deadline:** ATTEMPT wins; policy characterization below pins the exact boundary.
3. **Repeated DEFER or re-enable:** preserve one original timestamp and unspent allowance; engine scripts cover arguments, service review covers stored state and replacement job ownership.
4. **Stop/start between action decision and error publication:** old session must emit no warning/notification/stop against the new session; the separate correction gate and device pause point cover it.
5. **Partial give-up throws or held live tunnel reaches give-up:** never claim completed settlement or mix live/blackhole/unprotected copy; Phase-1 engine tests cover double-settlement prevention, device/host review covers packet truth.

## File structure and phase handoff

| File | Responsibility |
|---|---|
| Modify `app/src/main/java/com/justme/xtls_core_proxy/vpn/XrayVpnService.kt` | Typed private scheduling, timer callback, all schedule callers; retain owner/effects |
| Extend `app/src/test/java/com/justme/xtls_core_proxy/vpn/FailoverEngineTest.kt` | Re-arm dispatch, terminal results, unchanged timestamp, exception propagation |
| Extend `app/src/test/java/com/justme/xtls_core_proxy/vpn/SessionLifecycleDecisionTest.kt` | Existing production-used retry policy boundaries |
| Read `vpn/FailoverEngine.kt`, `TunnelHost.kt`, `FailoverContract.kt`, test `vpn/RecordingTunnelHost.kt` | Already created by rotation; no alternate engine, fake, interface, or result types |

All abbreviated `vpn/` paths in this document are beneath `app/src/main/java/com/justme/xtls_core_proxy/` or `app/src/test/java/com/justme/xtls_core_proxy/` as specified above.

## Required Phase-1 baseline

Rotation Task 3 Step 5 owns and includes the complete give-up, admission, action, and scheduling helper bodies. They must already exist when this phase starts; do not reimplement them here. The incoming private service signatures are:

```kotlin
private fun giveUpRotationLocked(sessionEpoch: Long, reason: String): GiveUpSettlement
private fun admitFailoverRearmFromService(sessionEpoch: Long): FailoverRearmAdmission
private fun handleUnprotectedRearmFromService(sessionEpoch: Long, unprotectedSinceMs: Long): UnprotectedRearmResult
private fun scheduleFailoverRearmLocked(
    sessionEpoch: Long,
    retryByRotation: Boolean,
    unprotectedSinceMs: Long = System.currentTimeMillis(),
)
```

These are signature references to implemented helpers, not bodies to paste. Give-up results come from actual locked exits after required effects; no broad catch fabricates `Completed`. Timer admission/action have one implementation shared by the full adapter and the still-direct service timer. Phase 1 keeps Boolean scheduling and direct helper dispatch; this phase converts both together.

The timeout STOP_SERVICE effect boundary is the explicit incoming gap if its independent correction has not already landed. Task 1 below stops until that correction is separately approved and verified. Preserve a previously landed correction; never regress it to the source baseline. No later plan supplies code back to Phase 1, and this phase does not redo its helper extraction.

---

### Task 1: Pin recovery dispatch and policy boundaries before switching the timer

**Files:** Extend `app/src/test/java/com/justme/xtls_core_proxy/vpn/FailoverEngineTest.kt` and `SessionLifecycleDecisionTest.kt`.

**Interfaces:** Consumes full `RecordingTunnelHost` (`responds`, `throws`, `calls`, `assertConsumed`) and `FailoverEngine.onRearmTimer(sessionEpoch, request)`. Produces exhaustive characterization of all re-arm results and retry-time policy, extending the rotation plan's direct-entry smoke coverage. Add the tests during this phase, once; no code or tests are supplied backward to Rotation Task 2.

- [ ] **Step 1: Add these direct-entry tests inside the existing `FailoverEngineTest` class**

```kotlin
@Test fun rearmAdmissionRejectsWithoutASecondCall() {
    for (result in listOf(FailoverRearmAdmission.StoppedSession, FailoverRearmAdmission.StaleSession,
        FailoverRearmAdmission.FeatureDisabled)) {
        for (request in listOf(FailoverRearmRequest.RecheckMonitor, FailoverRearmRequest.UnprotectedRecovery(123L))) {
            val host = RecordingTunnelHost()
            host.responds("admitFailoverRearm", result)
            FailoverEngine(host).onRearmTimer(29L, request)
            assertEquals(listOf("admitFailoverRearm"), host.calls.map { it.name })
            assertEquals(29L, host.calls.single().epoch)
            host.assertConsumed()
        }
    }
}

@Test fun admittedRearmDispatchesOnceAndPreservesOriginalTimestampAcrossDeferrals() {
    val host = RecordingTunnelHost()
    val engine = FailoverEngine(host)
    for (result in listOf(UnprotectedRearmResult.StoppedSession, UnprotectedRearmResult.StaleSession,
        UnprotectedRearmResult.RotationEnqueued, UnprotectedRearmResult.RecoveryDeferred,
        UnprotectedRearmResult.ServiceStopRequested, UnprotectedRearmResult.RecoveryDeferred)) {
        host.responds("admitFailoverRearm", FailoverRearmAdmission.Admitted)
        host.responds("handleUnprotectedRearm", result)
        val before = host.calls.size
        engine.onRearmTimer(29L, FailoverRearmRequest.UnprotectedRecovery(1234L))
        val calls = host.calls.drop(before)
        assertEquals(listOf("admitFailoverRearm", "handleUnprotectedRearm"), calls.map { it.name })
        assertEquals(listOf(1234L), calls.last().args)
        assertTrue(calls.all { it.epoch == 29L })
        host.assertConsumed()
    }
    host.responds("admitFailoverRearm", FailoverRearmAdmission.Admitted)
    host.responds("reconcileAuthoritativeFailoverSettings", SettingsReconciliationResult.StaleSession)
    val before = host.calls.size
    engine.onRearmTimer(30L, FailoverRearmRequest.RecheckMonitor)
    assertEquals(listOf("admitFailoverRearm", "reconcileAuthoritativeFailoverSettings"),
        host.calls.drop(before).map { it.name })
    assertTrue(host.calls.drop(before).all { it.epoch == 30L })
    host.assertConsumed()
}

@Test fun timerExceptionsPropagateWithoutRotationFailureOrRepeat() {
    for (operation in listOf("admitFailoverRearm", "handleUnprotectedRearm", "reconcileAuthoritativeFailoverSettings")) {
        for (error in listOf(IllegalStateException("timer"), CancellationException("timer cancelled"))) {
            val host = RecordingTunnelHost()
            if (operation != "admitFailoverRearm") host.responds("admitFailoverRearm", FailoverRearmAdmission.Admitted)
            host.throws(operation, error)
            val request = if (operation == "reconcileAuthoritativeFailoverSettings")
                FailoverRearmRequest.RecheckMonitor else FailoverRearmRequest.UnprotectedRecovery(888L)
            assertSame(error, runCatching { FailoverEngine(host).onRearmTimer(5L, request) }.exceptionOrNull())
            assertEquals(operation, host.calls.last().name)
            assertFalse(host.calls.any { it.name == "failRotation" || it.name == "beginRotation" })
            host.assertConsumed()
        }
    }
}
```

These tests script host results only. They do not prove real admission reset, delay, containment, or budget consumption.

- [ ] **Step 2: Add exact existing-policy boundary assertions to `SessionLifecycleDecisionTest`**

Use existing JUnit4 imports; the class is in the production `vpn` package.

```kotlin
@Test fun engineMigrationRetryDeadlineKeepsConnectedAttemptAndThreeWindowBound() {
    val since = 1000L
    val window = 60000L
    val deadline = since + window * UNPROTECTED_UNATTEMPTED_RETRY_WINDOWS
    assertEquals(3, UNPROTECTED_UNATTEMPTED_RETRY_WINDOWS)
    assertEquals(UnprotectedRetryAction.ATTEMPT,
        unprotectedRetryAction(SessionTunnelState.CONNECTED, since, deadline + 1L, window))
    for (state in SessionTunnelState.entries.filter { it != SessionTunnelState.CONNECTED }) {
        assertEquals(UnprotectedRetryAction.DEFER, unprotectedRetryAction(state, since, deadline - 1L, window))
        assertEquals(UnprotectedRetryAction.STOP_SERVICE, unprotectedRetryAction(state, since, deadline, window))
    }
    for (outcome in FailoverGiveUpOutcome.entries) {
        assertFalse(shouldStopServiceOnGiveUp(outcome, false))
        assertEquals(outcome == FailoverGiveUpOutcome.UNPROTECTED, shouldStopServiceOnGiveUp(outcome, true))
    }
}
```

- [ ] **Step 3: Run baseline characterization and inspect source gates**

```powershell
./gradlew.bat :app:testDebugUnitTest --tests "*.FailoverEngineTest"
./gradlew.bat :app:testDebugUnitTest --tests "*.SessionLifecycleDecisionTest"
```

Expected PASS before this phase's callback switch, because rotation already implements the passive entry and pure policy is unchanged. This is behavior-preserving refactoring, not a new failing engine feature; do not claim a nonexistent RED. The separate timeout correction must have its own regression/review evidence. Stop if the current host does not have the final block below:

```kotlin
UnprotectedRetryAction.STOP_SERVICE -> synchronized(lock) {
    when {
        !running -> UnprotectedRearmResult.StoppedSession
        activeSessionEpoch != sessionEpoch -> UnprotectedRearmResult.StaleSession
        else -> {
            LogRepository.append("Failover: no tunnel, and no chance to retry for " +
                "$UNPROTECTED_UNATTEMPTED_RETRY_WINDOWS rotation windows; stopping the VPN")
            LogRepository.emitError(R.string.vpn_failover_stopped_error)
            postErrorNotification(R.string.vpn_failover_stopped_error)
            stopVpn(expectedSessionEpoch = sessionEpoch)
            UnprotectedRearmResult.ServiceStopRequested
        }
    }
}
```

This block is the required reviewed target for the helper's STOP_SERVICE arm, not an instruction to slip an unapproved safety change into this phase. It deliberately adds no CONNECTED/enable recheck at effect time; those existing policy boundaries are unchanged.

---

### Task 2: Replace the private scheduling signature and switch the timer callback atomically

**Files:** Modify `app/src/main/java/com/justme/xtls_core_proxy/vpn/XrayVpnService.kt` only; keep tests above green.

**Interfaces:** Consumes Phase-1 typed helper/adapter/engine and verified timeout correction. Produces exact `private fun scheduleFailoverRearmLocked(sessionEpoch: Long, request: FailoverRearmRequest)`; all callers pass either `RecheckMonitor` or `UnprotectedRecovery(originalSince)`. The old Boolean overload is removed in the same task.

- [ ] **Step 1: Replace the timer with this complete body**

```kotlin
private fun scheduleFailoverRearmLocked(sessionEpoch: Long, request: FailoverRearmRequest) {
    val windowMs = authoritativeFailoverSettings().rotationWindowMs
    failoverRearmJob?.cancel()
    failoverRearmJob = serviceScope.launch {
        delay(windowMs)
        failoverEngine.onRearmTimer(sessionEpoch, request)
    }
}
```

No extra `launch`, `isActive`, generation token, stored deadline, or inline duplicate admission/action remains. The timer now reaches `ServiceTunnelHost.admitFailoverRearm` and `handleUnprotectedRearm`/authoritative reconciliation through the engine. The ordinary contained timer acquires the approved context-neutral settings diagnostic and propagates its original non-cancellation exception; rotation catches that exception only in its own post-commit path.

- [ ] **Step 2: Replace all five scheduling contexts in the same edit**

Do not rely on compiler errors alone: these callers encode different retry policies. The exact replacements are:

```kotlin
// ServiceTunnelHost.beginRotation: refused, current recovery request, enabled; keep exact refusal log.
scheduleFailoverRearmLocked(sessionEpoch, FailoverRearmRequest.UnprotectedRecovery(recovery.unprotectedSinceMs))

// giveUpRotationLocked: another lifecycle owner, after bridge-only release and before dropped-kill notice.
scheduleFailoverRearmLocked(sessionEpoch, FailoverRearmRequest.RecheckMonitor)

// handleUnprotectedRearmFromService: DEFER inside decision lock; unchanged input timestamp.
scheduleFailoverRearmLocked(sessionEpoch, FailoverRearmRequest.UnprotectedRecovery(unprotectedSinceMs))

// applyFailoverPreferences: restoration, after keeping/initializing and storing the original since value.
scheduleFailoverRearmLocked(sessionEpoch, FailoverRearmRequest.UnprotectedRecovery(since))
```

The fifth context is the normal tail of `giveUpRotationLocked`; replace its `retryByRotation`/`since` block, not its completed-result mapping:

```kotlin
val request = when (outcome) {
    FailoverGiveUpOutcome.CONTAINED_BY_LIVE_TUNNEL,
    FailoverGiveUpOutcome.CONTAINED_BY_BLACKHOLE -> FailoverRearmRequest.RecheckMonitor
    FailoverGiveUpOutcome.UNPROTECTED -> {
        val since = System.currentTimeMillis()
        unprotectedEpisodeSinceMs = since
        FailoverRearmRequest.UnprotectedRecovery(since)
    }
}
scheduleFailoverRearmLocked(sessionEpoch, request)
announceDroppedDeferredKillLocked(deferredKillLabel)
return GiveUpSettlement.Completed(when (outcome) {
    FailoverGiveUpOutcome.CONTAINED_BY_LIVE_TUNNEL -> GiveUpDisposition.LIVE_TUNNEL_REARMED
    FailoverGiveUpOutcome.CONTAINED_BY_BLACKHOLE -> GiveUpDisposition.BLACKHOLE_REARMED
    FailoverGiveUpOutcome.UNPROTECTED -> GiveUpDisposition.UNPROTECTED_RECOVERY_REARMED
})
```

STOPPED_AFTER_UNPROTECTED_RETRY returns earlier and schedules nothing. Do not move scheduling ahead of outcome publication or reset `unprotectedRetryConsumed` here. The timer's ATTEMPT still only enqueues a rotation; its successful reservation spends the allowance.

- [ ] **Step 3: Audit caller wiring and run serial regression checks**

Search `XrayVpnService.kt` for `scheduleFailoverRearmLocked`, `retryByRotation`, `unprotectedSinceMs`, `onRearmTimer`, `admitFailoverRearmFromService`, and `handleUnprotectedRearmFromService` by symbol. Expected: one typed scheduler, no Boolean overload, the five contexts above, exactly one direct timer engine invocation, and only adapter delegation to the admission/action helpers. Remove stale KDoc parameter names from the scheduler and update its KDoc to describe the two typed requests. Keep references to original timestamp where still meaningful.

```powershell
./gradlew.bat :app:compileDebugKotlin
./gradlew.bat :app:testDebugUnitTest --tests "*.FailoverEngineTest"
./gradlew.bat :app:testDebugUnitTest
./gradlew.bat :app:lintDebug
```

Expected zero failures. If a helper result is ignored, verify the actual effect is already complete and no duplicate caller action follows. No new interface member, engine field, or give-up policy may be introduced to resolve a compile failure.

- [ ] **Step 4: Review real-host outcomes and perform device gate**

Human review must trace all give-up callers, helper returns, ordinary exception versus settlement marker, exact line/state/notification order, budget mutation sites, timestamp propagation, timer job parentage, and two-lock timeout effect boundary. Scripted `BLACKHOLE_REARMED` does not prove adoption; inspect the owner after the actual operation.

Use `docs/qa/auto-failover-qa.md`: Test 2 Fail-closed give-up, Test 3 tunnel STILL UP, Test 5a–5e UNPROTECTED first give-up/single recovery/second-stop/disable/contained-disable, Test 6 notification Stop from UNPROTECTED, Test 16 Connect from UNPROTECTED, and Test 19a–19c contained Reconnect. Add explicit evidence rows within the existing Result log for:

- retained live, adopted bridge, existing unread, fresh blackhole, and failed blackhole; correct copy for each;
- pause before timer ATTEMPT, repeated DEFER at unchanged timestamp, exact three-window boundary, CONNECTED beyond deadline;
- disable/re-enable retaining original timestamp and UNPROTECTED warning; contained-disable keeps owner/line/state;
- debugger pause after timeout decision, stop/start a new session, then release old callback: no old warning/error/stop;
- give-up settlement log/notification/schedule failure: no fabricated completed result or second give-up;
- dropped deferred kill notice follows packet truth; no queued stale kill survives give-up.

Use a consented debug/device setup or debugger to exercise fault boundaries; do not add permanent production fault toggles without approval. Missing driveability is missing evidence, not a pass. Run `./gradlew.bat :app:connectedDebugAndroidTest` serially when device evidence is available; preserve installed app data. Re-run Test 23 full-switch capture for any changed rotation/give-up path, excluding proxy endpoints and using a tester-chosen plain HTTP destination.

- [ ] **Step 5: Record phase stop/go and conditional commit**

Phase 2 remains open if any containment copy, timestamp, budget, timeout effect boundary, cancellation, reviewed prerequisite, device/fault or packet evidence is missing or wrong. Do not begin monitor migration until it passes. Record exact test outputs, device/build, reviewed diff, and any unmet gate; no private endpoints or unredacted logs.

Only if commits were separately requested and this phase passed:

```powershell
git add -- app/src/main/java/com/justme/xtls_core_proxy/vpn/XrayVpnService.kt app/src/test/java/com/justme/xtls_core_proxy/vpn/FailoverEngineTest.kt app/src/test/java/com/justme/xtls_core_proxy/vpn/SessionLifecycleDecisionTest.kt
git commit -S -m "Route failover recovery timer through passive engine" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

Stage only actual phase changes; do not scoop up user edits or independently reviewed prerequisite corrections. Signing failure stops; no external config workaround or unsigned retry.
