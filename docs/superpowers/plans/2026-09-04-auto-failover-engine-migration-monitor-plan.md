# Auto-Failover Engine Monitor and Settings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **Do not execute now.** The required Phase-1 helper baseline is already owned by the rotation plan; Phase-3 tasks require separately authorized execution and completed Phases 0–2.

**Goal:** Move healthy/settings callback dispatch through the passive engine without moving monitor, collector, screen receiver, probe, or kill-switch ownership out of the service.

**Architecture:** Phase 1 exposes real typed exits from the existing healthy/settings helpers so the complete host can compile and rotation can reconcile settings. Phase 3 changes only the production entry callers to the already-tested engine and verifies the real monitor/receiver behavior. Startup/collector calls retain their captured tuple and epoch, while rotation/timer use the separate authoritative host method.

**Tech Stack:** Kotlin, JUnit4, existing `TunnelHealthMonitor`, `FailoverPreferences.state`, coroutines, Android broadcast receiver and probe construction.

**Spec:** `docs/superpowers/specs/2026-09-04-auto-failover-engine-migration-design.md`, Healthy-probe recovery, Settings/monitor/shared receiver, execution matrix, and Phase 3. Read the synchronized engine index, rotation plan, and give-up/re-arm plan.

## Global Constraints

- Written-spec approval authorizes planning only. Later execution needs a separate maintainer request and the structural-foundations index/owner/capture/verification gates plus completed rotation and give-up/re-arm phases.
- The engine delegates; it owns no settings cache, collector, monitor, receiver, Android context, clock, scope, or episode state. Do not introduce a second launcher, callback-liveness gate, or per-probe owner generation.
- `onHealthyProbe` runs directly on the existing monitor callback thread. `onFailoverSettings` runs directly on startup or the existing `serviceScope` collector. Only unhealthy reaches the existing `tunnelOpScope` rotation launcher.
- Preserve `FailoverPreferences.load` on connect. Reconciliation consumes the supplied emission; policy decisions at reservation/scheduling/fire/deadline use `authoritativeFailoverSettings()` at the existing boundaries. `failoverMonitorSettings` remains only the tuple used to construct the live monitor, never a current-settings cache.
- Healthy settlement rejects stopped/stale and non-CONNECTED first, then clears failed IDs. No give-up marker returns `EpisodeFailuresCleared` regardless of owner; marker plus `None` returns `IgnoredWithoutTunnel` after that clear. Only a held live/unread owner permits recovery publication. Do not strengthen this into a new live-only probe policy.
- Disable cancels/nulls the timer and clears attempts unconditionally. Contained release clears recovery markers/alert but not owner, recorded line, or displayed state; UNPROTECTED retains warning, marker, line, and consumed flag. A paused session stays PAUSED.
- Restore an UNPROTECTED re-arm only through the existing role predicate; keep the stored original timestamp or initialize it once if absent. Do not spend retry, restart an existing deadline, or add a new enablement rule.
- Measure monitor changes relative to transaction entry. `ABSENT`/`STOPPED` cannot be inferred from a second stop helper call; unchanged monitor returns without stop/start/receiver reconciliation. Rebuild only for interval, timeout, threshold, never max-rotations/window alone.
- Keep fixed `ConfigBuilder.HEALTH_PROBE_TARGET_URL`, `AndroidNetworkAvailability(applicationContext)`, named callbacks, delayed first probe, terminal unhealthy and one-shot healthy behavior.
- Terminal stop uses `stop()`, not `pausePolling()`, and nulls monitor plus built-from tuple. One shared receiver remains while either monitor exists. Screen events keep main-thread epoch hint then one existing serialized launch and authoritative locked check before independently pausing/resuming both monitors.
- Keep revive's existing `Kill-switch revive: restarting the health monitor failed: ...` context. Do not route revive through the context-neutral authoritative host wrapper used by rotation/timer.
- Tests of scripted results prove dispatch only. Host review and device QA prove effects/receiver ownership; missing evidence keeps the phase gate open.
- Heavy Gradle tasks run serially; preserve device app data. No generated AAR/geo, local properties, IDE or external/user configuration edits. Commits only when separately requested, always `-S`; stop on signing failure, never alter GPG/global settings or fall back unsigned.

## Review Focus

1. **Healthy result races a no-owner give-up:** failed IDs still clear but CONNECTED must not be published; helper ordering below plus real-host QA pins it.
2. **Disable while contained versus UNPROTECTED or PAUSED:** release only the correct markers, preserve packet truth and distinct user copy; inspect the three-valued branch and device matrix.
3. **Unchanged settings emission or budget-only edit:** retain the same monitor/poll cycle and avoid receiver churn; existing monitor/policy tests and service/device observations cover it.
4. **Failover-only screen monitoring and kill-switch disable:** do not unregister the shared receiver while either monitor remains; all four enablement pairings are required device cases.
5. **Old-session collector or earned callback after cancellation:** reject epoch inside the real helper without adding a callback-liveness veto or new launch; engine argument tests and device interleavings cover different layers.

## File structure

| File | Responsibility |
|---|---|
| Modify `app/src/main/java/com/justme/xtls_core_proxy/vpn/XrayVpnService.kt` | Named monitor callbacks, startup seed/collector/revive caller wiring; keep real private helpers |
| Extend `app/src/test/java/com/justme/xtls_core_proxy/vpn/FailoverEngineTest.kt` | Direct healthy/settings delegation, valid result fixtures, exceptions and epoch/tuple arguments |
| Extend `app/src/test/java/com/justme/xtls_core_proxy/vpn/SessionLifecycleDecisionTest.kt` | Existing production-used monitor/receiver/release policy characterization |
| Read existing `app/src/test/java/com/justme/xtls_core_proxy/failover/TunnelHealthMonitorTest.kt` and `HealthProbeSchemeTest.kt` | Delayed first probe, one-shot/terminal callbacks, stop/pause, fixed cleartext target configuration; no new monitor implementation |

No new contract type, fake, public service surface, or abstraction around the kill-switch is introduced.

## Required incoming baseline from Phases 1 and 2

Rotation Task 3 Step 5 owns the complete typed healthy/settings helper bodies. They must already be implemented; this phase does not supply code back to rotation or repeat their extraction. The incoming service-private signatures are:

```kotlin
private fun clearGiveUpStateOnRecovery(sessionEpoch: Long): HealthyProbeResult
private fun applyFailoverPreferences(settings: FailoverSettings, sessionEpoch: Long): SettingsReconciliationResult
private fun scheduleFailoverRearmLocked(sessionEpoch: Long, request: FailoverRearmRequest)
```

These signature references are not bodies to paste. The first two helpers return actual locked exits and are the sole producers of healthy/settings results. Phase 2 has already converted the restoration schedule call to `UnprotectedRecovery(since)` and installed the engine timer callback. Do not restore a Boolean scheduler call from an earlier phase snapshot.

At this phase's entry, the healthy monitor, startup seed, settings collector, and revive still call the typed private helpers directly. Rotation and timer use the adapter's authoritative settings method. Task 2 below switches the remaining named callers while leaving helper policy, owner, timer, monitor construction, and receiver lifetime unchanged.

---

### Task 1: Pin direct entries and existing monitor policy before caller migration

**Files:** Extend the existing `app/src/test/java/com/justme/xtls_core_proxy/vpn/FailoverEngineTest.kt` and `SessionLifecycleDecisionTest.kt`.

**Interfaces:** Consumes `RecordingTunnelHost`, four-entry `FailoverEngine`, exact healthy/settings result types, existing pure lifecycle functions. Produces tests of direct delegation and valid fixtures; no JVM service simulator. Rotation Task 2 already includes direct-entry smoke coverage; this task extends those tests with exhaustive legal results/exceptions during Phase 3. It does not supply code backward to rotation.

- [ ] **Step 1: Add the direct-entry tests to `FailoverEngineTest`**

Use its existing JUnit4 imports and add `com.justme.xtls_core_proxy.failover.FailoverSettings` if not present.

```kotlin
private fun settingsFixture() = FailoverSettings(
    enabled = true, probeIntervalMs = 15000L, probeTimeoutMs = 5000L,
    failureThreshold = 2, maxRotations = 3, rotationWindowMs = 600000L,
)

@Test fun healthyEntryReturnsEveryResultWithoutExtraWork() {
    val results = listOf(HealthyProbeResult.StoppedSession, HealthyProbeResult.StaleSession,
        HealthyProbeResult.EpisodeFailuresCleared, HealthyProbeResult.IgnoredWithoutTunnel,
        HealthyProbeResult.GiveUpCleared) +
        SessionTunnelState.entries.filter { it != SessionTunnelState.CONNECTED }
            .map { HealthyProbeResult.IgnoredOutsideConnected(it) }
    val host = RecordingTunnelHost()
    val engine = FailoverEngine(host)
    for ((index, result) in results.withIndex()) {
        host.responds("settleHealthyProbe", result)
        val before = host.calls.size
        val epoch = index.toLong() + 10L
        assertSame(result, engine.onHealthyProbe(epoch))
        assertEquals(listOf(RecordingTunnelHost.Call("settleHealthyProbe", epoch)), host.calls.drop(before))
        host.assertConsumed()
    }
}

// Fixture guard only: rejects impossible scripted answers, not a simulation of service policy/effects.
private fun assertValidReconciliationFixture(result: SettingsReconciliationResult) {
    when (result) {
        SettingsReconciliationResult.StoppedSession, SettingsReconciliationResult.StaleSession -> Unit
        is SettingsReconciliationResult.Disabled -> {
            require(result.monitor in setOf(MonitorChange.ABSENT, MonitorChange.STOPPED))
            val effect = result.giveUp
            if (effect is DisableGiveUpEffect.ContainedReleased)
                require(effect.outcome != FailoverGiveUpOutcome.UNPROTECTED)
        }
        is SettingsReconciliationResult.NotMonitoring ->
            require(result.monitor in setOf(MonitorChange.ABSENT, MonitorChange.STOPPED))
        is SettingsReconciliationResult.Monitoring ->
            require(result.monitor in setOf(MonitorChange.UNCHANGED, MonitorChange.STARTED, MonitorChange.REBUILT))
    }
}

@Test fun settingsEntryPreservesEachValidResultAndSuppliedTuple() {
    val results = mutableListOf<SettingsReconciliationResult>(
        SettingsReconciliationResult.StoppedSession, SettingsReconciliationResult.StaleSession,
    )
    val disableEffects = listOf(DisableGiveUpEffect.None, DisableGiveUpEffect.UnprotectedRetained,
        DisableGiveUpEffect.ContainedReleased(FailoverGiveUpOutcome.CONTAINED_BY_LIVE_TUNNEL),
        DisableGiveUpEffect.ContainedReleased(FailoverGiveUpOutcome.CONTAINED_BY_BLACKHOLE))
    for (change in listOf(MonitorChange.ABSENT, MonitorChange.STOPPED)) {
        for (effect in disableEffects) results += SettingsReconciliationResult.Disabled(change, effect)
        for (restored in UnprotectedRearmRestoration.entries)
            results += SettingsReconciliationResult.NotMonitoring(change, restored)
    }
    for (change in listOf(MonitorChange.UNCHANGED, MonitorChange.STARTED, MonitorChange.REBUILT)) {
        for (restored in UnprotectedRearmRestoration.entries)
            results += SettingsReconciliationResult.Monitoring(change, restored)
    }
    val host = RecordingTunnelHost()
    val engine = FailoverEngine(host)
    for ((index, result) in results.withIndex()) {
        assertValidReconciliationFixture(result)
        val settings = settingsFixture().copy(enabled = result !is SettingsReconciliationResult.Disabled,
            maxRotations = 1 + index % 10)
        val epoch = index.toLong() + 100L
        host.responds("reconcileFailoverSettings", result)
        val before = host.calls.size
        assertSame(result, engine.onFailoverSettings(epoch, settings))
        assertEquals(listOf(RecordingTunnelHost.Call("reconcileFailoverSettings", epoch, listOf(settings))),
            host.calls.drop(before))
        host.assertConsumed()
    }
}

@Test fun invalidSettingsFixturesAreNotAcceptedAsSuccessfulCoverage() {
    val invalid = mutableListOf<SettingsReconciliationResult>()
    for (change in listOf(MonitorChange.UNCHANGED, MonitorChange.STARTED, MonitorChange.REBUILT)) {
        invalid += SettingsReconciliationResult.Disabled(change, DisableGiveUpEffect.None)
        invalid += SettingsReconciliationResult.NotMonitoring(change, UnprotectedRearmRestoration.NOT_NEEDED)
    }
    for (change in listOf(MonitorChange.ABSENT, MonitorChange.STOPPED)) {
        invalid += SettingsReconciliationResult.Monitoring(change, UnprotectedRearmRestoration.NOT_NEEDED)
        invalid += SettingsReconciliationResult.Disabled(change,
            DisableGiveUpEffect.ContainedReleased(FailoverGiveUpOutcome.UNPROTECTED))
    }
    for (result in invalid) assertThrows(IllegalArgumentException::class.java) {
        assertValidReconciliationFixture(result)
    }
}

@Test fun healthyAndSettingsExceptionsKeepTheirOriginalCallerBoundary() {
    for (operation in listOf("settleHealthyProbe", "reconcileFailoverSettings")) {
        for (error in listOf(IllegalStateException("callback"), CancellationException("callback cancelled"))) {
            val host = RecordingTunnelHost()
            host.throws(operation, error)
            val engine = FailoverEngine(host)
            val observed = runCatching {
                if (operation == "settleHealthyProbe") engine.onHealthyProbe(88L)
                else engine.onFailoverSettings(88L, settingsFixture())
            }.exceptionOrNull()
            assertSame(error, observed)
            assertEquals(listOf(operation), host.calls.map { it.name })
            assertEquals(88L, host.calls.single().epoch)
            host.assertConsumed()
        }
    }
}
```

The fixture guard prevents impossible answers from being counted as valid coverage. It does not test the real service producer; compare that producer to the approved result table in human review and exercise it on device. The passive engine intentionally does not validate or reinterpret the returned result.

- [ ] **Step 2: Pin existing pure monitor/release/receiver rules**

Add within `SessionLifecycleDecisionTest`; construct the local settings tuple rather than referring to a fixture private to another test class.

```kotlin
@Test fun engineMigrationPreservesMonitorTimingOnlyRebuildAndReceiverOrOwnership() {
    val settings = com.justme.xtls_core_proxy.failover.FailoverSettings(
        true, 15000L, 5000L, 2, 3, 600000L,
    )
    assertFalse(failoverMonitorNeedsRebuild(settings, settings))
    assertFalse(failoverMonitorNeedsRebuild(settings, settings.copy(maxRotations = 5, rotationWindowMs = 120000L)))
    assertTrue(failoverMonitorNeedsRebuild(settings, settings.copy(probeIntervalMs = 20000L)))
    assertTrue(failoverMonitorNeedsRebuild(settings, settings.copy(probeTimeoutMs = 6000L)))
    assertTrue(failoverMonitorNeedsRebuild(settings, settings.copy(failureThreshold = 3)))
    assertTrue(failoverMonitorNeedsRebuild(null, settings))
    for (killLive in listOf(false, true)) for (failoverLive in listOf(false, true)) {
        assertEquals(killLive || failoverLive, shouldHoldScreenReceiver(killLive, failoverLive))
    }
    for (enabled in listOf(false, true)) for (running in listOf(false, true)) {
        for (state in SessionTunnelState.entries) {
            assertEquals(enabled && running && state == SessionTunnelState.CONNECTED,
                shouldRunFailoverMonitor(enabled, running, state))
        }
    }
}

@Test fun engineMigrationDisableRetainsUnprotectedAndRestoresOnlyItsAbsentTimer() {
    for (outcome in FailoverGiveUpOutcome.entries) {
        assertEquals(outcome != FailoverGiveUpOutcome.UNPROTECTED, shouldReleaseGiveUpOnDisable(false, outcome))
        assertFalse(shouldReleaseGiveUpOnDisable(true, outcome))
        for (role in TunnelRole.entries) for (jobActive in listOf(false, true)) {
            val expected = outcome == FailoverGiveUpOutcome.UNPROTECTED && role == TunnelRole.NONE && !jobActive
            assertEquals(expected, shouldRestoreUnprotectedRearm(true, outcome, role, jobActive))
            assertFalse(shouldRestoreUnprotectedRearm(false, outcome, role, jobActive))
        }
    }
    assertFalse(shouldReleaseGiveUpOnDisable(false, null))
    assertFalse(shouldRestoreUnprotectedRearm(true, null, TunnelRole.NONE, false))
}
```

These characterize production-used pure rules, not service operation order. If an identical existing test already pins a row, extend that existing test instead of adding a duplicate with a new name.

- [ ] **Step 3: Run baseline characterization**

```powershell
./gradlew.bat :app:testDebugUnitTest --tests "*.FailoverEngineTest"
./gradlew.bat :app:testDebugUnitTest --tests "*.SessionLifecycleDecisionTest"
./gradlew.bat :app:testDebugUnitTest --tests "*.TunnelHealthMonitorTest"
./gradlew.bat :app:testDebugUnitTest --tests "*.HealthProbeSchemeTest"
```

Expected PASS before Phase-3 caller changes; behavior is intentionally unchanged. Do not invent a failing implementation just to claim RED. Rotation Task 2 already exercised the direct entries with smoke tests; these exhaustive result/exception tests extend them during this phase. Caller/Android evidence below is still required.

---

### Task 2: Switch all four settings/healthy caller contexts and preserve receiver ownership

**Files:** Modify `app/src/main/java/com/justme/xtls_core_proxy/vpn/XrayVpnService.kt`; no `TunnelHealthMonitor`, kill-switch, or contract changes.

**Interfaces:** Consumes typed private helpers and all 17 adapter members already present, plus the Phase-2 typed scheduler. Produces direct engine callback wiring with exactly the contexts below. `stopFailoverMonitorLocked`, `reconcileScreenReceiverLocked`, `registerScreenReceiver`, `unregisterScreenReceiver` remain service-private with their unchanged signatures.

- [ ] **Step 1: Verify completed Phase-2 state before editing**

Confirm exactly one `scheduleFailoverRearmLocked(sessionEpoch, request: FailoverRearmRequest)` and timer `failoverEngine.onRearmTimer`; no old Boolean scheduler. Confirm the separately reviewed timeout check/effects are atomic. The Phase-1 settings helper from the rotation plan has already had its restoration call converted by Phase 2; do not restore its old Boolean scheduler invocation.

- [ ] **Step 2: Replace startup seed and collector calls at the existing sites**

The current baseline anchors are `startVpn` lines 517–545. Keep the seed/load outside the lock and all surrounding session-admission/log/kill-switch code in place. Replace only these calls:

```kotlin
// Existing startup code still owns this read; it is not moved into the engine.
val failoverPrefs = FailoverPreferences.load(this@XrayVpnService)

// Inside the existing successful-start commit lock, at the former applyFailoverPreferences site:
failoverEngine.onFailoverSettings(sessionEpoch, failoverPrefs)
failoverSettingsJob?.cancel()
failoverSettingsJob = serviceScope.launch {
    FailoverPreferences.state.collect { newSettings ->
        failoverEngine.onFailoverSettings(sessionEpoch, newSettings)
    }
}
```

The snippet identifies two existing sites, not a new second startup block. Keep the actual startup `committed` state/epoch guard between them. Reentrant service locking remains legal; add no dispatch/await. The collector passes its captured epoch and tuple unchanged—no current-settings cache, no swallowed exception, no extra outer liveness guard.

- [ ] **Step 3: Replace the named monitor callbacks in the existing helper**

```kotlin
monitor.start(
    onHealthy = { failoverEngine.onHealthyProbe(sessionEpoch) },
    onUnhealthy = { rotateTunnel(sessionEpoch, RotationRequest.UnhealthyProbe) },
)
```

No other monitor construction change. Do not wrap either callback in `launch`; healthy stays direct and unhealthy reaches the sole service launcher. Do not add a check of the monitor's `isStarted`/job after it earns an unhealthy event: that monitor is already terminal before callback invocation.

- [ ] **Step 4: Replace revive's direct reconciliation call without changing its diagnostic context**

Inside the existing `afterReviveCommitted("restarting the health monitor")` step, substitute only the body call:

```kotlin
afterReviveCommitted("restarting the health monitor") {
    failoverEngine.onFailoverSettings(session.epoch, authoritativeFailoverSettings())
}
```

The variable is the existing revive-local `SessionContext` (`session.epoch`), not a global/latest epoch read. Do not call `host.reconcileAuthoritativeFailoverSettings` from revive: it emits the engine/timer diagnostic and would alter the approved revive context. Keep `afterTransitionCommitted`/`afterReviveCommitted` for the kill-switch path.

- [ ] **Step 5: Audit all callers and unchanged shared receiver mechanics**

Search by exact symbols, not modifier-anchored declaration patterns. Expected final call map:

| Source | Target / context |
|---|---|
| Startup seeded settings and service collector | `failoverEngine.onFailoverSettings(capturedEpoch, suppliedTuple)` directly |
| Revive post-commit | Same entry with fresh authoritative tuple, inside existing revive diagnostic wrapper |
| Healthy monitor callback | `failoverEngine.onHealthyProbe(capturedEpoch)` directly |
| Unhealthy monitor callback | `rotateTunnel(capturedEpoch, UnhealthyProbe)`; exactly one serialized launch |
| Rotation post-commit / re-arm RecheckMonitor | Host `reconcileAuthoritativeFailoverSettings` with fresh authoritative tuple |
| Engine healthy/settings | Adapter delegates to single private `clearGiveUpStateOnRecovery` / `applyFailoverPreferences` implementation |
| Screen off/on, kill-switch preference changes, Stop/revoke/destroy | Existing service paths, not engine entries |

Only the adapter may call the two typed private healthy/settings helpers after this switch; retain those definitions as real implementation, not dead-code wrappers. The authoritative host wrapper also calls the same settings helper. No leftover callback drives the old path in parallel.

Confirm unchanged service-private signatures:

```kotlin
private fun stopFailoverMonitorLocked(): Unit
private fun reconcileScreenReceiverLocked(sessionEpoch: Long): Unit
private fun registerScreenReceiver(sessionEpoch: Long): Unit
private fun unregisterScreenReceiver(): Unit
```

These are signature references, not bodies to paste. Verify existing `stop()` plus nulling both fields, OR-ownership of one receiver, main-thread epoch hint then one `tunnelOpScope` launch, and locked current-epoch checks before independent pause/resume of both monitors. Rotation admission stops without transient receiver reconciliation; give-up, ineligible settings, and completed monitor start/rebuild reconcile. Do not rewrite receiver code to make it fit an engine abstraction.

- [ ] **Step 6: Run compile, JVM, and lint serially**

```powershell
./gradlew.bat :app:compileDebugKotlin
./gradlew.bat :app:testDebugUnitTest --tests "*.FailoverEngineTest"
./gradlew.bat :app:testDebugUnitTest
./gradlew.bat :app:lintDebug
```

Expected zero failures. Preserve existing monitor/probe/settings/health-target tests. A compile error caused by a tuple/result name returns to the exact ledger; no interface changes are permitted here.

- [ ] **Step 7: Human-review and execute Phase-3 device matrix**

Human-review the actual locked helper returns against the spec's legal result table. Especially inspect entry-relative ABSENT/STOPPED, no-op early return, construction exceptions, healthy clear-before-owner ordering, and tuple/epoch sourcing. Do not count the test-only fixture guard as host validation.

Use `docs/qa/auto-failover-qa.md`: Test 8 Process-fresh arm, Test 9a–9c failover without kill-switch, Test 10 and 10a kill-switch cycle, Test 11 live settings edits, Test 17 offline guard, Test 24a–24d switch/foreground interleavings. Test 24d is a revive regression, not newly engine-owned code. The fixed probe target follows `docs/features/auto-failover.md` and `ConfigBuilder`, not Test 11's outdated suggestion that Ping Test's editable URL is the watchdog target.

Required observations:

- All four failover/kill-switch enablement pairings: screen off/on pauses/resumes whichever monitors are live; disabling either does not strand the other.
- Process-fresh startup uses persisted enabled settings, not DEFAULT; no duplicate monitor from seed plus first flow emission.
- No-op or max-rotations/window-only emission does not reset polling; each timing edit rebuilds exactly once, with the fixed probe target.
- Disable retained live/blackhole preserves owner/recorded line/state and correct copy; UNPROTECTED retains marker/warning; re-enable restores original timestamp once.
- A callback for an old epoch after stop/start causes no new-session mutation. Do not add a liveness gate to hide an earned unhealthy callback.
- Current CONNECTED healthy with no marker clears failed IDs even without an owner; marker plus no owner does not publish recovery. Live/unread owner recovery preserves the accepted policy and resets the budget.
- No health polling while kill-switch deliberately PAUSED; terminal unhealthy and first-probe delay still behave as existing component tests specify.

Use consented device/debugger fault/pause points when ordinary configuration cannot exercise a race. Do not persist fault hooks without approval. Record undrivable cases as missing evidence and leave their gates open. Run `./gradlew.bat :app:connectedDebugAndroidTest` serially on the prepared device with app preservation. Re-run the routine full-switch Test 23 capture to ensure callback changes did not bypass rotation containment.

- [ ] **Step 8: Record stop/go and conditional signed commit**

No phase completion if any callback, receiver, policy, stale-epoch, exception, human-review, or device gate is missing. Record exact commands/results, reviewer, device/build, observations, and any missing evidence without private endpoints or unredacted logs.

Only when separately requested and all phase gates pass:

```powershell
git add -- app/src/main/java/com/justme/xtls_core_proxy/vpn/XrayVpnService.kt app/src/test/java/com/justme/xtls_core_proxy/vpn/FailoverEngineTest.kt app/src/test/java/com/justme/xtls_core_proxy/vpn/SessionLifecycleDecisionTest.kt
git commit -S -m "Route failover monitor callbacks through passive engine" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

Stage only actual Phase-3 changes, not unrelated existing files. Stop on signing failure; do not alter external configuration or retry unsigned. Final verification follows this phase rather than replacing its review/device gate.
