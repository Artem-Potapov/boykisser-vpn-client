# Auto-Failover Engine Rotation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **Execution is not authorized now.** Read the entry gate before any implementation step.

**Goal:** Introduce the exact passive engine and complete recording fake, then replace rotation sequencing with semantic service transactions, one candidate per invocation.

**Architecture:** `FailoverEngine` stores only `TunnelHost`. A private `XrayVpnService.ServiceTunnelHost` owns all effects under the existing lifecycle lock and scopes. The engine/interface/fake are complete in this phase; production timer, healthy, and settings callers switch in later phases, while their existing private helpers expose real typed results for the adapter—never stubs or a second implementation.

**Tech Stack:** Kotlin, JUnit4, `kotlinx-coroutines-test`, Room `db.Profile`, Android VpnService, `Dispatchers.IO.limitedParallelism(1)`.

**Spec:** `docs/superpowers/specs/2026-09-04-auto-failover-engine-migration-design.md`. Exact declarations also appear in the synchronized `docs/superpowers/plans/2026-09-04-auto-failover-engine-migration-plan.md`.

## Global Constraints

- Planning approval is not execution permission. Require a separate implementation request and maintainer review of these plans.
- Phase 0 must already have passed the structural-foundations index, owner, capture, and verification plans at their existing `2026-09-03-auto-failover-structural-foundations-*-plan.md` paths. Require the single `tunnelOwner`, role predicates, capture applicator, and full-switch establish-first packet evidence. Current inspected source is still three-field/release-first: **do not execute against it** or add compatibility overloads.
- Separately approved and verified rotation prerequisites: stale reservation cleanup, stale switched-server notice, post-commit settle-up and diagnostic isolation. Stop if absent; this plan does not authorize opportunistic corrections. One-settlement exception handling is explicit target hardening: review that change separately within this phase, not as unchanged source behavior.
- Service retains lock, epochs, descriptor/Builder, owner, config/geo/split preparation, notifications, clocks, scopes, monitors/receiver, and all episode fields. Engine retains only immutable `host`; no clock, scope, timer, collector, captured epoch, mutable episode/session state, Android type, DAO, or owner.
- Pure policy remains in top-level `SessionLifecycleDecision.kt`, `FailoverDecision`, `FailoverEpisodeDecision`, and `FailoverPoolResolver.resolve(dao, current)`.
- Only `rotate` and `loadRotationPool` suspend. Bring-up stays non-suspending; the serialized lane is not a mutex across DAO suspension. Preserve one launcher and one candidate per invocation.
- Cancellation propagates; a marker for a give-up already entered propagates its original cause without a second settlement. The pre-commit catch never surrounds commit or follow-ups.
- Keep the approved establish-first structural operations. Null/throw retains the same bridge. Non-null installs live immediately then closes the predecessor once. Protected handover diagnostics cannot skip protector/Xray startup. Initial start/revive retain `None` and no bridge audit.
- Preserve all three give-up outcomes, retry budget/timestamp rules, and synchronous `stopVpn`. Accepted policies listed in the spec are not defects to fix here.
- Preserve diagnostics except the two explicitly approved differences: omitted name-bearing stale persistence log, and context-neutral reconciliation diagnostic in engine/timer contexts only. Revive keeps its existing diagnostic context.
- JVM scripts prove engine calls, not service lock/owner/packet behavior. Human `vpn/` review and per-phase device QA remain mandatory. Missing evidence leaves the gate open.
- Heavy Gradle tasks run serially. Do not uninstall/clear a data-bearing device app. No generated AAR/geo, local properties, IDE state, or external/user-level config edits. Do not change GPG or signing settings.
- A later commit requires explicit authorization, an in-place non-main branch, and `git commit -S`. Stop on signing failure; never retry unsigned. No commit is authorized by plan authoring.

## Review Focus

1. **Stop/start while a DAO read suspends:** old epoch must not read new failed IDs or clean its bridge. Task 2 tests epoch propagation; Task 3 reviews the real lock boundary.
2. **Null profile versus lookup/selector exception:** distinct diagnostics and only one failure funnel. Task 2 pins both and the real list-order selector.
3. **Failure after live installation versus failure before establishment:** rollback inspects the current owner, not the old result; a retained bridge is never generically torn down. Task 3 fault/device gate covers this.
4. **Close/diagnostic error during establish-first:** the new live owner survives, later diagnostics and operational startup continue. Task 3 requires real-host fault evidence.
5. **Post-commit error or same-session pause:** later follow-ups continue except cancellation; current epoch suffices for persistence/notice, and no post-commit error can give up. Tasks 2–3 cover dispatch and atomic effects separately.

## File structure and staged ownership

| File | Role |
|---|---|
| Create `app/src/main/java/com/justme/xtls_core_proxy/vpn/FailoverContract.kt` | Exact request/result declarations |
| Create `app/src/main/java/com/justme/xtls_core_proxy/vpn/TunnelHost.kt` | Single exact 17-member interface |
| Create `app/src/main/java/com/justme/xtls_core_proxy/vpn/FailoverEngine.kt` | Passive engine; file-private settlement marker and non-host wrapper helper |
| Create `app/src/test/java/com/justme/xtls_core_proxy/vpn/RecordingTunnelHost.kt` | Full scripted fake, no implicit successes |
| Create `app/src/test/java/com/justme/xtls_core_proxy/vpn/FailoverEngineTest.kt` | Engine ordering/error/epoch tests; later phases extend this same file |
| Modify `app/src/main/java/com/justme/xtls_core_proxy/vpn/XrayVpnService.kt` | All 17 real adapter members, typed private helper exits, rotation launcher |

Task 1 is contract/fake only; Task 2 is a complete engine tested without service wiring; Task 3 integrates the complete adapter in one compiling deliverable. Do not compile/commit half of a 17-member adapter. Private give-up/healthy/settings/timer helpers gain real typed exits in Task 3 because rotation needs a complete host. That is return-value exposure and consolidation of existing logic, not an early engine-caller switch. All Phase-1 helper bodies are included in Task 3 Step 5 below; this plan does not depend on executing a later plan. Phase 2 changes the timer request/callback and completes its separately gated stop-effect boundary; Phase 3 changes healthy/settings callbacks. The later plans consume this phase's concrete baseline rather than supplying code back to it. `VpnServiceTunBuilderTarget` constructor details come from the landed structural file; verify the actual constructor rather than inventing a new capture API.

## Entry gate

- [ ] Confirm separate execution authorization, non-main in-place branch, clean understanding of pre-existing user changes, and all four structural completion records.
- [ ] Confirm the three rotation correctness prerequisites above have their own reviewed evidence. Baseline anchors are `rotateTunnel:1134`, post-commit notice `1398–1400`, and `clearGiveUpLineLocked:1330`/reporting helper `1869–1889`; locate by symbol after foundations, not stale line numbers.
- [ ] Confirm exact post-structural service helper names: `tearDownTunnelLocked`, `releaseRotationBridgeLocked`, `establishRotationBridgeLocked`, `establishBlackholeTunnelLocked`, `adoptRotationBridgeLocked`, plus `TunnelOwnerTransitions` and `TunCapturePlanApplicator`. Their effects stay structural. If the landed foundation differs from its approved plan, stop for reconciliation; do not guess a replacement API.

---

### Task 1: Add exact contract declarations and the full scripted fake

**Files:** Create `vpn/FailoverContract.kt`, `vpn/TunnelHost.kt`, test `vpn/RecordingTunnelHost.kt`, and test `vpn/FailoverEngineTest.kt` at the full paths above.

**Interfaces:** Consumes `db.Profile`, `failover.FailoverSettings`, existing `SessionTunnelState`/`FailoverGiveUpOutcome`. Produces the exact declarations in the index's **Requests and result ledger**, the **Single 17-member host**, and the fake below. The index contains full Kotlin code for every variant/field; it is required task input, not a request to design types.

- [ ] **Step 1: Write this failing test in `FailoverEngineTest.kt`**

```kotlin
package com.justme.xtls_core_proxy.vpn

import org.junit.Assert.*
import org.junit.Test

class FailoverEngineTest {
    @Test fun fakeNeverInventsSuccess() {
        val host = RecordingTunnelHost()
        assertEquals(17, TunnelHost::class.java.declaredMethods.size)
        assertThrows(IllegalStateException::class.java) {
            host.beginRotation(7L, RotationRequest.CandidateRetry)
        }
        assertEquals(listOf(RecordingTunnelHost.Call(
            "beginRotation", 7L, listOf(RotationRequest.CandidateRetry),
        )), host.calls)
    }
}
```

- [ ] **Step 2: Run RED**

`./gradlew.bat :app:testDebugUnitTest --tests "*.FailoverEngineTest"`

Expected missing contract/fake symbols. An unrelated environment failure is not RED evidence. If types already exist, stop and reconcile rather than overwrite.

- [ ] **Step 3: Copy the exact contract code from the synchronized index**

`FailoverContract.kt` begins with:

```kotlin
package com.justme.xtls_core_proxy.vpn

import com.justme.xtls_core_proxy.db.Profile
```

Then copy the entire **Requests and result ledger** Kotlin block from the index, without altering names/fields/visibility. It includes all request/result/enum declarations, from `RotationRequest` through `SettingsReconciliationResult`; `GiveUpSettlement` is a private service helper's result, not a new host member.

`TunnelHost.kt` begins with:

```kotlin
package com.justme.xtls_core_proxy.vpn

import com.justme.xtls_core_proxy.db.Profile
import com.justme.xtls_core_proxy.failover.FailoverSettings
```

Then copy the index's complete **Single 17-member host** Kotlin block, not the separate interface excerpts in the spec. No default methods, Android imports, aliases, Boolean result substitutions, or compatibility overloads.

- [ ] **Step 4: Add this complete fake**

```kotlin
package com.justme.xtls_core_proxy.vpn

import com.justme.xtls_core_proxy.db.Profile
import com.justme.xtls_core_proxy.failover.FailoverSettings

internal class RecordingTunnelHost : TunnelHost {
    data class Call(val name: String, val epoch: Long, val args: List<Any?> = emptyList())
    val calls = mutableListOf<Call>()
    private val responses = mutableMapOf<String, ArrayDeque<() -> Any?>>()

    fun responds(name: String, value: Any?) = respondsWith(name) { value }
    fun throws(name: String, error: Throwable) = respondsWith(name) { throw error }
    fun respondsWith(name: String, response: () -> Any?) {
        responses.getOrPut(name) { ArrayDeque() }.addLast(response)
    }
    fun assertConsumed() {
        check(responses.values.all { it.isEmpty() }) { "Unused scripted responses" }
    }
    @Suppress("UNCHECKED_CAST")
    private fun <T> answer(name: String, epoch: Long, vararg args: Any?): T {
        calls += Call(name, epoch, args.toList())
        val queue = responses[name] ?: error("Unscripted $name")
        check(queue.isNotEmpty()) { "Unscripted $name" }
        return queue.removeFirst().invoke() as T
    }
    override fun beginRotation(sessionEpoch: Long, request: RotationRequest): RotationStartResult =
        answer("beginRotation", sessionEpoch, request)
    override suspend fun loadRotationPool(sessionEpoch: Long, currentProfileId: Long): RotationPoolResult =
        answer("loadRotationPool", sessionEpoch, currentProfileId)
    override fun giveUpExhaustedPool(sessionEpoch: Long): ExhaustedPoolResult =
        answer("giveUpExhaustedPool", sessionEpoch)
    override fun prepareRotationCandidate(sessionEpoch: Long, current: Profile, candidate: Profile): CandidatePreparationResult =
        answer("prepareRotationCandidate", sessionEpoch, current, candidate)
    override fun bringUpRotationCandidate(sessionEpoch: Long, candidate: Profile): CandidateBringUpResult =
        answer("bringUpRotationCandidate", sessionEpoch, candidate)
    override fun rollBackFailedCandidate(sessionEpoch: Long, candidateId: Long, previousProfileId: Long): CandidateRollbackResult =
        answer("rollBackFailedCandidate", sessionEpoch, candidateId, previousProfileId)
    override fun reportCandidateFailureAndEnqueueRetry(sessionEpoch: Long, candidateName: String, failure: CandidateBringUpResult.Failure) =
        answer<Unit>("reportCandidateFailureAndEnqueueRetry", sessionEpoch, candidateName, failure)
    override fun commitRotation(sessionEpoch: Long): RotationCommitResult = answer("commitRotation", sessionEpoch)
    override fun recordCommittedActiveProfile(sessionEpoch: Long, profileId: Long): CommittedSessionEffectResult =
        answer("recordCommittedActiveProfile", sessionEpoch, profileId)
    override fun postCommittedSwitchNotice(sessionEpoch: Long, previousProfileName: String, candidateProfileName: String): CommittedSessionEffectResult =
        answer("postCommittedSwitchNotice", sessionEpoch, previousProfileName, candidateProfileName)
    override fun enqueueDeferredKillReplay(sessionEpoch: Long): DeferredKillEnqueueResult =
        answer("enqueueDeferredKillReplay", sessionEpoch)
    override fun failRotation(sessionEpoch: Long, logMessage: String): RotationFailureResult =
        answer("failRotation", sessionEpoch, logMessage)
    override fun admitFailoverRearm(sessionEpoch: Long): FailoverRearmAdmission =
        answer("admitFailoverRearm", sessionEpoch)
    override fun handleUnprotectedRearm(sessionEpoch: Long, unprotectedSinceMs: Long): UnprotectedRearmResult =
        answer("handleUnprotectedRearm", sessionEpoch, unprotectedSinceMs)
    override fun settleHealthyProbe(sessionEpoch: Long): HealthyProbeResult =
        answer("settleHealthyProbe", sessionEpoch)
    override fun reconcileFailoverSettings(sessionEpoch: Long, settings: FailoverSettings): SettingsReconciliationResult =
        answer("reconcileFailoverSettings", sessionEpoch, settings)
    override fun reconcileAuthoritativeFailoverSettings(sessionEpoch: Long): SettingsReconciliationResult =
        answer("reconcileAuthoritativeFailoverSettings", sessionEpoch)
}
```

- [ ] **Step 5: Run GREEN and review**

Run the focused command above, then `./gradlew.bat :app:testDebugUnitTest`, serially. Expect zero failures. Review exact interface/variant transcription and fake strictness. No production service change or phase commit yet.

---

### Task 2: Implement and test exhaustive passive ordering

**Files:** Create `vpn/FailoverEngine.kt`; extend `vpn/FailoverEngineTest.kt`.

**Interfaces:** Consumes the Task 1 contract and `FailoverDecision.nextCandidate(pool, currentId, recentlyFailed): Profile?`. Produces all four exact engine entries. File-private `GiveUpSettlementFailure` is shared indirectly via `internal fun <T> withGiveUpSettlementBoundary(block: () -> T): T`; this non-inline helper is implementation plumbing, not a host member, engine entry, field, or result.

- [ ] **Step 1: Add these concrete tests to `FailoverEngineTest`**

Add imports `com.justme.xtls_core_proxy.db.Profile`, `kotlinx.coroutines.CancellationException`, `kotlinx.coroutines.test.runTest`. These helpers and tests live inside the existing test class:

```kotlin
private val old = Profile(id = 1L, name = "old", config = "{}")
private val next = Profile(id = 2L, name = "next", config = "{}")
private val epoch = 42L
private val prefix = listOf("beginRotation", "loadRotationPool", "prepareRotationCandidate", "bringUpRotationCandidate")
private fun names(host: RecordingTunnelHost) = host.calls.map { it.name }
private fun hostThroughBringUp(result: CandidateBringUpResult = CandidateBringUpResult.Started): RecordingTunnelHost =
    RecordingTunnelHost().also {
        it.responds("beginRotation", RotationStartResult.Reserved(old.id))
        it.responds("loadRotationPool", RotationPoolResult.Available(old, listOf(old, next), setOf(old.id)))
        it.responds("prepareRotationCandidate", CandidatePreparationResult.Prepared(RotationBridgePreparation.RETAINED))
        it.responds("bringUpRotationCandidate", result)
    }
private fun successfulEffects(host: RecordingTunnelHost, replay: DeferredKillReplay) {
    host.responds("recordCommittedActiveProfile", CommittedSessionEffectResult.Applied)
    host.responds("postCommittedSwitchNotice", CommittedSessionEffectResult.Applied)
    host.responds("reconcileAuthoritativeFailoverSettings", SettingsReconciliationResult.Monitoring(
        MonitorChange.STARTED, UnprotectedRearmRestoration.NOT_NEEDED,
    ))
    if (replay == DeferredKillReplay.PENDING)
        host.responds("enqueueDeferredKillReplay", DeferredKillEnqueueResult.Enqueued)
}

@Test fun successUsesExactEpochArgumentsAndOptionalReplay() = runTest {
    for (replay in DeferredKillReplay.entries) {
        val host = hostThroughBringUp()
        host.responds("commitRotation", RotationCommitResult.Committed(replay))
        successfulEffects(host, replay)
        FailoverEngine(host).rotate(epoch, RotationRequest.UnhealthyProbe)
        val expected = prefix + listOf("commitRotation", "recordCommittedActiveProfile",
            "postCommittedSwitchNotice", "reconcileAuthoritativeFailoverSettings") +
            (if (replay == DeferredKillReplay.PENDING) listOf("enqueueDeferredKillReplay") else emptyList())
        assertEquals(expected, names(host))
        assertEquals(listOf(epoch), host.calls.map { it.epoch }.distinct())
        assertEquals(listOf(next.id), host.calls.single { it.name == "recordCommittedActiveProfile" }.args)
        assertEquals(listOf(old.name, next.name), host.calls.single { it.name == "postCommittedSwitchNotice" }.args)
        host.assertConsumed()
    }
}

@Test fun everyReservationExitStopsWithoutAnotherSettlement() = runTest {
    val exits = listOf(RotationStartResult.StoppedSession, RotationStartResult.StaleSession,
        RotationStartResult.RefusedWithBridgeAdopted) +
        RotationRefusalCleanup.entries.map { RotationStartResult.RefusedAfterCleanup(it) } +
        GiveUpDisposition.entries.map { RotationStartResult.ThrashCapDenied(it) }
    for (result in exits) {
        val host = RecordingTunnelHost()
        host.responds("beginRotation", result)
        FailoverEngine(host).rotate(epoch, RotationRequest.UnprotectedRecovery(123L))
        assertEquals(listOf("beginRotation"), names(host))
        assertEquals(listOf(RotationRequest.UnprotectedRecovery(123L)), host.calls.single().args)
        host.assertConsumed()
    }
}

@Test fun poolRejectionMissingAndExhaustionAreDifferent() = runTest {
    for (result in listOf(RotationPoolResult.StoppedSession, RotationPoolResult.StaleSession,
        RotationPoolResult.MissingCurrentProfile(old.id))) {
        val host = RecordingTunnelHost()
        host.responds("beginRotation", RotationStartResult.Reserved(old.id))
        host.responds("loadRotationPool", result)
        if (result is RotationPoolResult.MissingCurrentProfile)
            host.responds("failRotation", RotationFailureResult.StoodDown(SessionTunnelState.PAUSED))
        FailoverEngine(host).rotate(epoch, RotationRequest.UnhealthyProbe)
        val missing = result is RotationPoolResult.MissingCurrentProfile
        val expected = listOf("beginRotation", "loadRotationPool") +
            (if (missing) listOf("failRotation") else emptyList())
        assertEquals(expected, names(host))
        if (missing) assertEquals(listOf("rotateTunnel: current profile 1 not found"), host.calls.last().args)
        host.assertConsumed()
    }
    val endings = listOf(ExhaustedPoolResult.StoppedSession, ExhaustedPoolResult.StaleSession,
        ExhaustedPoolResult.TransitionNotOwned(SessionTunnelState.PAUSED)) +
        GiveUpDisposition.entries.map { ExhaustedPoolResult.GaveUp(it) }
    for (pool in listOf(emptyList(), listOf(old), listOf(old, next))) for (ending in endings) {
        val host = RecordingTunnelHost()
        host.responds("beginRotation", RotationStartResult.Reserved(old.id))
        host.responds("loadRotationPool", RotationPoolResult.Available(old, pool, setOf(old.id, next.id)))
        host.responds("giveUpExhaustedPool", ending)
        FailoverEngine(host).rotate(epoch, RotationRequest.CandidateRetry)
        assertEquals(listOf("beginRotation", "loadRotationPool", "giveUpExhaustedPool"), names(host))
        host.assertConsumed()
    }
}

@Test fun selectorUsesListOrderNotIdsAndPreparationRejectionsStop() = runTest {
    val first = next.copy(id = 90L)
    val later = next.copy(id = 3L)
    val exits = listOf(CandidatePreparationResult.StoppedSession, CandidatePreparationResult.StaleSession,
        CandidatePreparationResult.TransitionNotOwned(SessionTunnelState.PAUSED)) +
        GiveUpDisposition.entries.map { CandidatePreparationResult.MissingBridgeAfterTeardown(it) }
    for (exit in exits) {
        val host = RecordingTunnelHost()
        host.responds("beginRotation", RotationStartResult.Reserved(old.id))
        host.responds("loadRotationPool", RotationPoolResult.Available(old, listOf(old, first, later), setOf(old.id)))
        host.responds("prepareRotationCandidate", exit)
        FailoverEngine(host).rotate(epoch, RotationRequest.UnhealthyProbe)
        assertEquals(listOf("beginRotation", "loadRotationPool", "prepareRotationCandidate"), names(host))
        assertEquals(listOf(old, first), host.calls.last().args)
        host.assertConsumed()
    }
    for (bridge in RotationBridgePreparation.entries) {
        val host = RecordingTunnelHost()
        host.responds("beginRotation", RotationStartResult.Reserved(old.id))
        host.responds("loadRotationPool", RotationPoolResult.Available(old, listOf(next), emptySet()))
        host.responds("prepareRotationCandidate", CandidatePreparationResult.Prepared(bridge))
        host.responds("bringUpRotationCandidate", CandidateBringUpResult.Started)
        host.responds("commitRotation", RotationCommitResult.StaleSession)
        FailoverEngine(host).rotate(epoch, RotationRequest.CandidateRetry)
        assertEquals(prefix + "commitRotation", names(host))
        host.assertConsumed()
    }
}

@Test fun everyFailureRollsBackWithReservedIdAndKeepsObsoleteTail() = runTest {
    val cause = IllegalStateException("candidate fault")
    val failures = listOf(CandidateBringUpResult.StoppedSession, CandidateBringUpResult.StaleSession,
        CandidateBringUpResult.TransitionNotOwned(SessionTunnelState.PAUSED),
        CandidateBringUpResult.PreparationFailed(cause), CandidateBringUpResult.EstablishReturnedNull,
        CandidateBringUpResult.EstablishThrew(cause)) +
        CandidatePostEstablishmentStage.entries.map { CandidateBringUpResult.FailedAfterLiveInstallation(it, cause) }
    val rollbacks = listOf(CandidateRollbackResult.StoppedSession, CandidateRollbackResult.StaleSession,
        CandidateRollbackResult.TransitionNotOwned(SessionTunnelState.PAUSED)) +
        RotationBridgePreparation.entries.map { CandidateRollbackResult.RolledBack(it) } +
        GiveUpDisposition.entries.map { CandidateRollbackResult.MissingBridgeAfterCandidateFailure(it) }
    for (failure in failures) for (rollback in rollbacks) {
        val host = hostThroughBringUp(failure)
        host.responds("rollBackFailedCandidate", rollback)
        val tail = rollback !is CandidateRollbackResult.MissingBridgeAfterCandidateFailure
        if (tail) host.responds("reportCandidateFailureAndEnqueueRetry", Unit)
        FailoverEngine(host).rotate(epoch, RotationRequest.CandidateRetry)
        val expected = prefix + listOf("rollBackFailedCandidate") +
            (if (tail) listOf("reportCandidateFailureAndEnqueueRetry") else emptyList())
        assertEquals(expected, names(host))
        assertEquals(listOf(next.id, old.id), host.calls.single { it.name == "rollBackFailedCandidate" }.args)
        if (tail) assertEquals(listOf(next.name, failure), host.calls.last().args)
        assertTrue(host.calls.all { it.epoch == epoch })
        host.assertConsumed()
    }
}

@Test fun rejectedOrThrowingCommitNeverRollsBackOrGivesUp() = runTest {
    for (result in listOf(RotationCommitResult.StoppedSession, RotationCommitResult.StaleSession,
        RotationCommitResult.TransitionNotOwned(SessionTunnelState.PAUSED))) {
        val host = hostThroughBringUp()
        host.responds("commitRotation", result)
        FailoverEngine(host).rotate(epoch, RotationRequest.UnhealthyProbe)
        assertEquals(prefix + "commitRotation", names(host))
        host.assertConsumed()
    }
    for (cause in listOf(IllegalStateException("commit"), CancellationException("cancel"))) {
        val host = hostThroughBringUp()
        host.throws("commitRotation", cause)
        val observed = runCatching { FailoverEngine(host).rotate(epoch, RotationRequest.UnhealthyProbe) }.exceptionOrNull()
        assertSame(cause, observed)
        assertEquals(prefix + "commitRotation", names(host))
    }
}

@Test fun eachPostCommitThrowContinuesUnlessCancelled() = runTest {
    val effects = listOf("recordCommittedActiveProfile", "postCommittedSwitchNotice",
        "reconcileAuthoritativeFailoverSettings", "enqueueDeferredKillReplay")
    for (failedIndex in effects.indices) for (cancel in listOf(false, true)) {
        val host = hostThroughBringUp()
        host.responds("commitRotation", RotationCommitResult.Committed(DeferredKillReplay.PENDING))
        val cause = if (cancel) CancellationException("cancel") else IllegalStateException("effect")
        for ((i, effect) in effects.withIndex()) {
            if (i == failedIndex) host.throws(effect, cause)
            else host.responds(effect, when (effect) {
                "reconcileAuthoritativeFailoverSettings" -> SettingsReconciliationResult.StaleSession
                "enqueueDeferredKillReplay" -> DeferredKillEnqueueResult.Enqueued
                else -> CommittedSessionEffectResult.Applied
            })
        }
        val observed = runCatching { FailoverEngine(host).rotate(epoch, RotationRequest.UnhealthyProbe) }.exceptionOrNull()
        if (cancel) assertSame(cause, observed) else assertNull(observed)
        val expected = prefix + listOf("commitRotation") +
            (if (cancel) effects.take(failedIndex + 1) else effects)
        assertEquals(expected, names(host))
        assertFalse(names(host).contains("failRotation"))
        if (!cancel) host.assertConsumed()
    }
}

@Test fun returnedFailuresAndRejectedEffectsDoNotStopLaterAttempts() = runTest {
    val failure = IllegalStateException("effect")
    for (effect in listOf(CommittedSessionEffectResult.Applied, CommittedSessionEffectResult.StoppedSession,
        CommittedSessionEffectResult.StaleSession, CommittedSessionEffectResult.Failed(failure))) {
        val host = hostThroughBringUp()
        host.responds("commitRotation", RotationCommitResult.Committed(DeferredKillReplay.PENDING))
        host.responds("recordCommittedActiveProfile", effect)
        host.responds("postCommittedSwitchNotice", effect)
        host.responds("reconcileAuthoritativeFailoverSettings", SettingsReconciliationResult.StoppedSession)
        host.responds("enqueueDeferredKillReplay", DeferredKillEnqueueResult.Failed(failure))
        FailoverEngine(host).rotate(epoch, RotationRequest.UnhealthyProbe)
        assertEquals(prefix + listOf("commitRotation", "recordCommittedActiveProfile", "postCommittedSwitchNotice",
            "reconcileAuthoritativeFailoverSettings", "enqueueDeferredKillReplay"), names(host))
        host.assertConsumed()
    }
}

private fun hostFailingAt(method: String, response: () -> Any?): RecordingTunnelHost {
    val host = RecordingTunnelHost()
    val sequence = when (method) {
        "beginRotation" -> listOf("beginRotation")
        "loadRotationPool" -> listOf("beginRotation", "loadRotationPool")
        "giveUpExhaustedPool" -> listOf("beginRotation", "loadRotationPool", "giveUpExhaustedPool")
        "prepareRotationCandidate" -> prefix.take(3)
        "bringUpRotationCandidate" -> prefix
        "rollBackFailedCandidate" -> prefix + "rollBackFailedCandidate"
        "reportCandidateFailureAndEnqueueRetry" -> prefix + listOf("rollBackFailedCandidate", method)
        else -> error("Unknown boundary $method")
    }
    for (call in sequence) {
        if (call == method) host.respondsWith(call, response)
        else host.responds(call, when (call) {
            "beginRotation" -> RotationStartResult.Reserved(old.id)
            "loadRotationPool" -> RotationPoolResult.Available(old,
                if (method == "giveUpExhaustedPool") emptyList() else listOf(next), emptySet())
            "prepareRotationCandidate" -> CandidatePreparationResult.Prepared(RotationBridgePreparation.RETAINED)
            "bringUpRotationCandidate" -> CandidateBringUpResult.EstablishReturnedNull
            "rollBackFailedCandidate" -> CandidateRollbackResult.StaleSession
            else -> error("Unexpected predecessor $call")
        })
    }
    return host
}

@Test fun ordinaryThrowsFunnelOnceAndEnteredGiveUpNeverFunnelsTwice() = runTest {
    val boundaries = listOf("beginRotation", "loadRotationPool", "giveUpExhaustedPool",
        "prepareRotationCandidate", "bringUpRotationCandidate", "rollBackFailedCandidate",
        "reportCandidateFailureAndEnqueueRetry")
    for (boundary in boundaries) {
        val cause = IllegalStateException("ordinary")
        val host = hostFailingAt(boundary) { throw cause }
        host.responds("failRotation", RotationFailureResult.GaveUp(GiveUpDisposition.BLACKHOLE_REARMED))
        FailoverEngine(host).rotate(epoch, RotationRequest.UnhealthyProbe)
        assertEquals(1, names(host).count { it == "failRotation" })
        assertEquals(listOf("rotateTunnel failed: ordinary"), host.calls.last().args)
        host.assertConsumed()
    }
    for (boundary in listOf("beginRotation", "giveUpExhaustedPool", "prepareRotationCandidate", "rollBackFailedCandidate")) {
        val cause = IllegalStateException("settlement")
        val host = hostFailingAt(boundary) { withGiveUpSettlementBoundary<Unit> { throw cause } }
        val observed = runCatching { FailoverEngine(host).rotate(epoch, RotationRequest.UnhealthyProbe) }.exceptionOrNull()
        assertSame(cause, observed)
        assertEquals(boundary, host.calls.last().name)
        assertFalse(names(host).contains("failRotation"))
        host.assertConsumed()
    }
    for (boundary in boundaries) {
        val cause = CancellationException("cancel")
        val host = hostFailingAt(boundary) { throw cause }
        assertSame(cause, runCatching { FailoverEngine(host).rotate(epoch, RotationRequest.CandidateRetry) }.exceptionOrNull())
        assertEquals(boundary, host.calls.last().name)
    }
    val cancellation = CancellationException("settlement cancellation")
    assertSame(cancellation, runCatching {
        withGiveUpSettlementBoundary<Unit> { throw cancellation }
    }.exceptionOrNull())
}

@Test fun failureFunnelThrowIsNotMissingProfileOrDoubleSettlement() = runTest {
    val cause = IllegalStateException("failure funnel")
    for (missing in listOf(false, true)) {
        val failed = RecordingTunnelHost()
        failed.responds("beginRotation", RotationStartResult.Reserved(old.id))
        if (missing) failed.responds("loadRotationPool", RotationPoolResult.MissingCurrentProfile(old.id))
        else failed.throws("loadRotationPool", IllegalStateException("lookup"))
        failed.throws("failRotation", cause)
        assertSame(cause, runCatching { FailoverEngine(failed).rotate(epoch, RotationRequest.UnhealthyProbe) }.exceptionOrNull())
        assertEquals(1, names(failed).count { it == "failRotation" })
    }
}

@Test fun oneEngineReusesNoSessionIdentity() = runTest {
    val host = hostThroughBringUp(CandidateBringUpResult.StaleSession)
    host.responds("rollBackFailedCandidate", CandidateRollbackResult.StaleSession)
    host.responds("reportCandidateFailureAndEnqueueRetry", Unit)
    host.responds("beginRotation", RotationStartResult.StaleSession)
    val engine = FailoverEngine(host)
    engine.rotate(3L, RotationRequest.CandidateRetry)
    val boundary = host.calls.size
    engine.rotate(4L, RotationRequest.UnhealthyProbe)
    assertTrue(host.calls.take(boundary).all { it.epoch == 3L })
    assertEquals(4L, host.calls.last().epoch)
    assertEquals("reportCandidateFailureAndEnqueueRetry", host.calls[boundary - 1].name)
    assertEquals("beginRotation", host.calls.last().name)
    host.assertConsumed()
}
```

- [ ] **Step 2: Run RED**

`./gradlew.bat :app:testDebugUnitTest --tests "*.FailoverEngineTest"` must fail because the engine/boundary helper is absent. Do not turn RED into a service simulation.

- [ ] **Step 3: Implement this complete engine**

```kotlin
package com.justme.xtls_core_proxy.vpn

import com.justme.xtls_core_proxy.db.Profile
import com.justme.xtls_core_proxy.failover.FailoverDecision
import com.justme.xtls_core_proxy.failover.FailoverSettings
import kotlinx.coroutines.CancellationException

private class GiveUpSettlementFailure(val originalCause: Throwable) : RuntimeException(originalCause)

internal fun <T> withGiveUpSettlementBoundary(block: () -> T): T = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    throw GiveUpSettlementFailure(error)
}

internal class FailoverEngine(private val host: TunnelHost) {
    private data class Selection(val profile: Profile?)

    private suspend fun <T : Any> beforeCommit(epoch: Long, block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (entered: GiveUpSettlementFailure) {
        throw entered.originalCause
    } catch (error: Throwable) {
        // A throw from this call is outside the protected block and cannot cause a second call.
        when (host.failRotation(epoch, "rotateTunnel failed: ${error.message}")) {
            RotationFailureResult.StoppedSession, RotationFailureResult.StaleSession,
            is RotationFailureResult.StoodDown, is RotationFailureResult.GaveUp -> Unit
        }
        null
    }

    suspend fun rotate(sessionEpoch: Long, request: RotationRequest) {
        val start = beforeCommit(sessionEpoch) { host.beginRotation(sessionEpoch, request) } ?: return
        val originalId = when (start) {
            is RotationStartResult.Reserved -> start.currentProfileId
            RotationStartResult.StoppedSession, RotationStartResult.StaleSession,
            RotationStartResult.RefusedWithBridgeAdopted, is RotationStartResult.RefusedAfterCleanup,
            is RotationStartResult.ThrashCapDenied -> return
        }
        val loaded = beforeCommit(sessionEpoch) { host.loadRotationPool(sessionEpoch, originalId) } ?: return
        val pool = when (loaded) {
            is RotationPoolResult.Available -> loaded
            RotationPoolResult.StoppedSession, RotationPoolResult.StaleSession -> return
            is RotationPoolResult.MissingCurrentProfile -> {
                when (host.failRotation(sessionEpoch, "rotateTunnel: current profile ${loaded.profileId} not found")) {
                    RotationFailureResult.StoppedSession, RotationFailureResult.StaleSession,
                    is RotationFailureResult.StoodDown, is RotationFailureResult.GaveUp -> Unit
                }
                return
            }
        }
        val selection = beforeCommit(sessionEpoch) {
            Selection(FailoverDecision.nextCandidate(pool.pool, pool.current.id, pool.recentlyFailed))
        } ?: return
        val candidate = selection.profile ?: run {
            val result = beforeCommit(sessionEpoch) { host.giveUpExhaustedPool(sessionEpoch) } ?: return
            when (result) {
                ExhaustedPoolResult.StoppedSession, ExhaustedPoolResult.StaleSession,
                is ExhaustedPoolResult.TransitionNotOwned, is ExhaustedPoolResult.GaveUp -> return
            }
        }
        when (beforeCommit(sessionEpoch) { host.prepareRotationCandidate(sessionEpoch, pool.current, candidate) } ?: return) {
            is CandidatePreparationResult.Prepared -> Unit
            CandidatePreparationResult.StoppedSession, CandidatePreparationResult.StaleSession,
            is CandidatePreparationResult.TransitionNotOwned, is CandidatePreparationResult.MissingBridgeAfterTeardown -> return
        }
        when (val broughtUp = beforeCommit(sessionEpoch) { host.bringUpRotationCandidate(sessionEpoch, candidate) } ?: return) {
            CandidateBringUpResult.Started -> Unit
            is CandidateBringUpResult.Failure -> {
                when (beforeCommit(sessionEpoch) { host.rollBackFailedCandidate(sessionEpoch, candidate.id, originalId) } ?: return) {
                    is CandidateRollbackResult.MissingBridgeAfterCandidateFailure -> return
                    CandidateRollbackResult.StoppedSession, CandidateRollbackResult.StaleSession,
                    is CandidateRollbackResult.TransitionNotOwned, is CandidateRollbackResult.RolledBack -> {
                        beforeCommit(sessionEpoch) {
                            host.reportCandidateFailureAndEnqueueRetry(sessionEpoch, candidate.name, broughtUp)
                        }
                        return
                    }
                }
            }
        }
        // No call below is enclosed by beforeCommit.
        val committed = when (val result = host.commitRotation(sessionEpoch)) {
            is RotationCommitResult.Committed -> result
            RotationCommitResult.StoppedSession, RotationCommitResult.StaleSession,
            is RotationCommitResult.TransitionNotOwned -> return
        }
        afterCommit { finishEffect(host.recordCommittedActiveProfile(sessionEpoch, candidate.id)) }
        afterCommit { finishEffect(host.postCommittedSwitchNotice(sessionEpoch, pool.current.name, candidate.name)) }
        afterCommit { finishReconciliation(host.reconcileAuthoritativeFailoverSettings(sessionEpoch)) }
        when (committed.deferredKillReplay) {
            DeferredKillReplay.NOT_PENDING -> Unit
            DeferredKillReplay.PENDING -> afterCommit {
                when (host.enqueueDeferredKillReplay(sessionEpoch)) {
                    DeferredKillEnqueueResult.Enqueued, is DeferredKillEnqueueResult.Failed -> Unit
                }
            }
        }
    }

    private fun finishEffect(result: CommittedSessionEffectResult): Unit = when (result) {
        CommittedSessionEffectResult.Applied, CommittedSessionEffectResult.StoppedSession,
        CommittedSessionEffectResult.StaleSession, is CommittedSessionEffectResult.Failed -> Unit
    }
    private fun finishReconciliation(result: SettingsReconciliationResult): Unit = when (result) {
        SettingsReconciliationResult.StoppedSession, SettingsReconciliationResult.StaleSession,
        is SettingsReconciliationResult.Disabled, is SettingsReconciliationResult.NotMonitoring,
        is SettingsReconciliationResult.Monitoring -> Unit
    }
    private inline fun afterCommit(step: () -> Unit) {
        try { step() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Throwable) { /* Host owns diagnostics; later independent effects still run. */ }
    }
    fun onRearmTimer(sessionEpoch: Long, request: FailoverRearmRequest) {
        when (host.admitFailoverRearm(sessionEpoch)) {
            FailoverRearmAdmission.StoppedSession, FailoverRearmAdmission.StaleSession,
            FailoverRearmAdmission.FeatureDisabled -> return
            FailoverRearmAdmission.Admitted -> Unit
        }
        when (request) {
            FailoverRearmRequest.RecheckMonitor -> finishReconciliation(host.reconcileAuthoritativeFailoverSettings(sessionEpoch))
            is FailoverRearmRequest.UnprotectedRecovery -> when (
                host.handleUnprotectedRearm(sessionEpoch, request.unprotectedSinceMs)
            ) {
                UnprotectedRearmResult.StoppedSession, UnprotectedRearmResult.StaleSession,
                UnprotectedRearmResult.RotationEnqueued, UnprotectedRearmResult.RecoveryDeferred,
                UnprotectedRearmResult.ServiceStopRequested -> Unit
            }
        }
    }
    fun onHealthyProbe(sessionEpoch: Long): HealthyProbeResult = host.settleHealthyProbe(sessionEpoch)
    fun onFailoverSettings(sessionEpoch: Long, settings: FailoverSettings): SettingsReconciliationResult =
        host.reconcileFailoverSettings(sessionEpoch, settings)
}
```

`finishEffect`/`finishReconciliation` merely make sealed results exhaustively terminal; they own no state or diagnostics. The private marker is wrapped only by `withGiveUpSettlementBoundary`, never by engine timing/selection or pre-give-up diagnostics.

- [ ] **Step 4: Add direct-entry smoke coverage and run GREEN**

Add this test inside the same `FailoverEngineTest` class. It makes all four entries usable in this phase without requiring code from a later plan. Phase 2 and Phase 3 add exhaustive timer/healthy/settings cases to this same test file before their own caller migrations.

```kotlin
@Test fun otherEntriesAreRealDelegationsNotStubs() {
    val host = RecordingTunnelHost()
    val engine = FailoverEngine(host)
    val settings = com.justme.xtls_core_proxy.failover.FailoverSettings(
        true, 15000L, 5000L, 2, 3, 600000L,
    )
    host.responds("admitFailoverRearm", FailoverRearmAdmission.Admitted)
    host.responds("handleUnprotectedRearm", UnprotectedRearmResult.RecoveryDeferred)
    engine.onRearmTimer(71L, FailoverRearmRequest.UnprotectedRecovery(123L))
    host.responds("admitFailoverRearm", FailoverRearmAdmission.Admitted)
    host.responds("reconcileAuthoritativeFailoverSettings", SettingsReconciliationResult.StaleSession)
    engine.onRearmTimer(72L, FailoverRearmRequest.RecheckMonitor)
    host.responds("settleHealthyProbe", HealthyProbeResult.IgnoredWithoutTunnel)
    assertEquals(HealthyProbeResult.IgnoredWithoutTunnel, engine.onHealthyProbe(73L))
    host.responds("reconcileFailoverSettings", SettingsReconciliationResult.StoppedSession)
    assertEquals(SettingsReconciliationResult.StoppedSession, engine.onFailoverSettings(74L, settings))
    assertEquals(listOf(
        RecordingTunnelHost.Call("admitFailoverRearm", 71L),
        RecordingTunnelHost.Call("handleUnprotectedRearm", 71L, listOf(123L)),
        RecordingTunnelHost.Call("admitFailoverRearm", 72L),
        RecordingTunnelHost.Call("reconcileAuthoritativeFailoverSettings", 72L),
        RecordingTunnelHost.Call("settleHealthyProbe", 73L),
        RecordingTunnelHost.Call("reconcileFailoverSettings", 74L, listOf(settings)),
    ), host.calls)
    host.assertConsumed()
}
```

Run `./gradlew.bat :app:testDebugUnitTest --tests "*.FailoverEngineTest"`, then `./gradlew.bat :app:testDebugUnitTest`, serially. Expected zero failures. The rotation tests had their RED run in Step 2; this added smoke test is additional coverage, not evidence of a RED run that did not happen. Later plans consume this working engine and add their own characterization tests; none supplies implementation back to this task.

- [ ] **Step 5: Review this independently testable engine deliverable**

Check every sealed branch, original epoch/id, actual pure selector use, one-candidate limit, marker cause identity, lack of Android/state/scheduler imports, and distinct pre-/post-commit scopes. Green scripts do not satisfy the service phase gate. No phase commit yet.

---

### Task 3: Integrate the complete semantic host and the single rotation launcher

**Files:** Modify `app/src/main/java/com/justme/xtls_core_proxy/vpn/XrayVpnService.kt`; retain existing policy/owner tests. Add no substitute JVM service simulator.

**Interfaces:** Consumes exact contract/engine, post-structural owner/capture helpers, and the typed private helper bodies in this task's Step 5. Produces one private inner `ServiceTunnelHost` with all 17 real members, typed private helpers, and one rotation launcher. Private helper results describe actual exits; no result is inferred afterwards from potentially changed service state.

**Testing boundary:** This task changes Android service wiring that the approved JVM seam intentionally does not expose. Engine tests from Task 2 are already green and remain regression checks, not a fabricated failing test of the service. The task's acceptance stays RED/open until code review, compile, and the real-host/device checks below pass. Do not claim a scripted fake proves these effects.

- [ ] **Step 1: Add the service instance and launcher without adding a second scheduling path**

```kotlin
private val failoverEngine = FailoverEngine(ServiceTunnelHost())

private fun rotateTunnel(
    sessionEpoch: Long,
    request: RotationRequest = RotationRequest.UnhealthyProbe,
) {
    tunnelOpScope.launch { failoverEngine.rotate(sessionEpoch, request) }
}
```

Replace the old launcher body, not its scope. Constructing the host/engine performs no action. Change the old timer's single ATTEMPT call mechanically to `rotateTunnel(sessionEpoch, RotationRequest.UnprotectedRecovery(unprotectedSinceMs))`; its timer still dispatches directly until Phase 2. Existing `onUnhealthy = { rotateTunnel(sessionEpoch) }` retains default `UnhealthyProbe` until Phase 3 makes it explicit. Candidate failure tail below names `CandidateRetry`. These are all three sources; no leftover recursive old rotation body may run in parallel.

- [ ] **Step 2: Add these private service support functions**

The give-up helper body with real return branches is in Step 5 below. Only its invocation belongs in the settlement wrapper; a helper caller's earlier log or policy exception stays an ordinary pre-commit failure.

```kotlin
private fun settleRotationGiveUpLocked(sessionEpoch: Long, reason: String): GiveUpSettlement =
    withGiveUpSettlementBoundary { giveUpRotationLocked(sessionEpoch, reason) }

private fun completedGiveUpLocked(sessionEpoch: Long, reason: String): GiveUpSettlement.Completed =
    when (val settlement = settleRotationGiveUpLocked(sessionEpoch, reason)) {
        is GiveUpSettlement.Completed -> settlement
        is GiveUpSettlement.StoodDown -> error("Unexpected give-up stand-down in ${settlement.actualState}")
    }

private fun bestEffortRotationDiagnostic(message: () -> String) {
    try { LogRepository.append(message()) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Throwable) { /* Diagnostic-only failure must not replace the operational outcome. */ }
}

private fun afterCommittedRotationStep(step: String, effect: () -> Unit) {
    try { effect() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Throwable) {
        bestEffortRotationDiagnostic {
            "Failover rotation: $step failed after the transition committed: ${error.message}"
        }
    }
}

private fun CandidateBringUpResult.Failure.diagnostic(): String = when (this) {
    CandidateBringUpResult.StoppedSession, CandidateBringUpResult.StaleSession,
    is CandidateBringUpResult.TransitionNotOwned -> "VPN session is no longer active"
    CandidateBringUpResult.EstablishReturnedNull -> "VpnService.establish() returned null"
    is CandidateBringUpResult.PreparationFailed -> "${cause.message}"
    is CandidateBringUpResult.EstablishThrew -> "${cause.message}"
    is CandidateBringUpResult.FailedAfterLiveInstallation -> "${cause.message}"
}
```

`completedGiveUpLocked`'s stand-down assertion is unreachable after the caller's locked CONNECTED/ROTATING validation; do not treat it as permission to add a second give-up for invalid fake/service states. The wrapper encloses precisely `giveUpRotationLocked`, which produces completion only after all required effects finish.

- [ ] **Step 3: Share existing preparation, preserving initial-start/revive ownership**

Extract only the common off-lock config/geo/Builder work into this service-private helper. In the post-structural shared `bringUpTunnel`, replace that off-lock work with `prepareTunnelBringUp(profile, log, sessionTuning)` and use its three fields in the existing structural handover/startup block. Do not change its start/revive ownership checks or `Result<Unit>` signature. Rotation uses the captured pair in Step 4 instead.

```kotlin
private data class PreparedTunnelBringUp(
    val builder: Builder,
    val configJson: String,
    val geoAssetDir: File,
)

private fun prepareTunnelBringUp(profile: Profile, log: LogSettings, tuning: TuningSettings): PreparedTunnelBringUp {
    val configJson = ConfigBuilder.buildRuntimeConfig(profile.config, log, tuning)
    val geoAssetDir = GeoAssetPreparer.prepare(this).getOrElse { error ->
        throw IllegalStateException("Geofile preparation failed: ${error.message}", error)
    }
    val split = SplitTunnelRepository.load(this)
    if (split.mode == SplitTunnelMode.ALLOW_ONLY && split.packages.isEmpty()) {
        LogRepository.append("Split tunnel allow-only mode enabled with no selected apps")
    }
    val capture = TunCapturePlanFactory.create(
        sessionName = localizedString(R.string.app_name),
        mtu = tuning.core.mtu,
        ipv6Enabled = tuning.core.ipv6,
        splitPlan = SplitTunnelPlanner.plan(split.mode, split.packages, packageName),
    )
    val builder = Builder()
    // Construct the landed VpnServiceTunBuilderTarget using its actual Phase-0 constructor;
    // it wraps this Builder and does not call establish().
    val target: TunBuilderTarget = VpnServiceTunBuilderTarget(builder)
    TunCapturePlanApplicator.apply(capture, target) { missing ->
        LogRepository.append("Split tunnel skipped missing package: $missing")
    }
    return PreparedTunnelBringUp(builder, configJson, geoAssetDir)
}
```

The common helper delegates capture to the already-approved structural seam. It does not recreate address/route policy, call `establish()`, or access an owner. Session tuning is captured once for the rotation call. No Android data passes through the engine.

- [ ] **Step 4: Add the complete 17-member adapter below in the same service**

The referenced private helpers `giveUpRotationLocked`, `admitFailoverRearmFromService`, `handleUnprotectedRearmFromService`, `clearGiveUpStateOnRecovery`, and `applyFailoverPreferences` have their complete bodies in this task's Step 5. Apply Steps 1–5 as one compiling integration deliverable before testing. Do not add temporary dummy implementations or execute a later phase to obtain these helpers.

```kotlin
private inner class ServiceTunnelHost : TunnelHost {
    override fun beginRotation(sessionEpoch: Long, request: RotationRequest): RotationStartResult = synchronized(lock) {
        if (!running) return@synchronized RotationStartResult.StoppedSession
        if (activeSessionEpoch != sessionEpoch) return@synchronized RotationStartResult.StaleSession
        if (!canReserveRotationFromAuthoritativeState(running, activeSessionEpoch, sessionEpoch, sessionTunnelState)) {
            if (shouldFunnelRotationReservationRefusal(running, activeSessionEpoch, sessionEpoch,
                    sessionTunnelState, tunnelOwner.toRole())) {
                val settled = completedGiveUpLocked(sessionEpoch,
                    "rotation reservation refused while holding the containment bridge")
                check(settled.disposition == GiveUpDisposition.BLACKHOLE_REARMED)
                return@synchronized RotationStartResult.RefusedWithBridgeAdopted
            }
            val attempted = tunnelOwner.toRole() == TunnelRole.UNREAD_ROTATION_BRIDGE
            releaseRotationBridgeLocked("rotation reservation refused")
            val recovery = request as? RotationRequest.UnprotectedRecovery
            var rearmed = false
            if (recovery != null && authoritativeFailoverSettings().enabled) {
                LogRepository.append("Failover: recovery rotation could not reserve the transition " +
                    "(tunnel is $sessionTunnelState); re-arming without spending the retry")
                scheduleFailoverRearmLocked(sessionEpoch, retryByRotation = true,
                    unprotectedSinceMs = recovery.unprotectedSinceMs)
                rearmed = true
            }
            return@synchronized RotationStartResult.RefusedAfterCleanup(when {
                attempted && rearmed -> RotationRefusalCleanup.BRIDGE_CLEANUP_ATTEMPTED_RECOVERY_REARMED
                attempted -> RotationRefusalCleanup.BRIDGE_CLEANUP_ATTEMPTED_NO_REARM
                rearmed -> RotationRefusalCleanup.BRIDGE_CLEANUP_NOT_NEEDED_RECOVERY_REARMED
                else -> RotationRefusalCleanup.BRIDGE_CLEANUP_NOT_NEEDED_NO_REARM
            })
        }
        if (request is RotationRequest.UnprotectedRecovery) unprotectedRetryConsumed = true
        val settings = authoritativeFailoverSettings()
        when (val admission = FailoverDecision.admitRotation(rotationAttempts, System.currentTimeMillis(),
            settings.maxRotations, settings.rotationWindowMs)) {
            RotationAdmission.Denied -> RotationStartResult.ThrashCapDenied(
                completedGiveUpLocked(sessionEpoch, "thrash cap reached").disposition,
            )
            is RotationAdmission.Admitted -> {
                rotationAttempts = admission.attempts
                sessionTunnelState = SessionTunnelState.ROTATING
                val id = currentProfileId
                episodeFailedIds = FailoverEpisodeDecision.recordProbeFailedCurrent(episodeFailedIds, id)
                stopFailoverMonitorLocked()
                RotationStartResult.Reserved(id)
            }
        }
    }

    override suspend fun loadRotationPool(sessionEpoch: Long, currentProfileId: Long): RotationPoolResult {
        val dao = AppDatabase.get(this@XrayVpnService).profileDao()
        val current = dao.getById(currentProfileId)
        val pool = current?.let { FailoverPoolResolver.resolve(dao, it) }
        return synchronized(lock) {
            when {
                !running -> RotationPoolResult.StoppedSession
                activeSessionEpoch != sessionEpoch -> RotationPoolResult.StaleSession
                current == null -> RotationPoolResult.MissingCurrentProfile(currentProfileId)
                else -> RotationPoolResult.Available(current, checkNotNull(pool).toList(), episodeFailedIds.toSet())
            }
        }
    }

    override fun giveUpExhaustedPool(sessionEpoch: Long): ExhaustedPoolResult = synchronized(lock) {
        when {
            !running -> ExhaustedPoolResult.StoppedSession
            activeSessionEpoch != sessionEpoch -> ExhaustedPoolResult.StaleSession
            sessionTunnelState != SessionTunnelState.ROTATING -> ExhaustedPoolResult.TransitionNotOwned(sessionTunnelState)
            else -> ExhaustedPoolResult.GaveUp(completedGiveUpLocked(sessionEpoch, "no healthy candidate left in pool").disposition)
        }
    }

    override fun prepareRotationCandidate(sessionEpoch: Long, current: Profile, candidate: Profile): CandidatePreparationResult {
        LogRepository.append("Failover: rotating ${current.name} -> ${candidate.name}")
        return synchronized(lock) {
            if (!running) return@synchronized CandidatePreparationResult.StoppedSession
            if (activeSessionEpoch != sessionEpoch) return@synchronized CandidatePreparationResult.StaleSession
            if (sessionTunnelState != SessionTunnelState.ROTATING)
                return@synchronized CandidatePreparationResult.TransitionNotOwned(sessionTunnelState)
            val retained = tunnelOwner.toRole() == TunnelRole.UNREAD_ROTATION_BRIDGE
            if (!retained) tearDownTunnelLocked()
            val required = shouldEstablishRotationBridge(tunnelOwner.toRole(), sessionTunnelState)
            if (required) establishRotationBridgeLocked()
            if (shouldAbortRotationForMissingBridge(required, tunnelOwner.toRole())) {
                LogRepository.append("Failover: rotation bridge could not be established after teardown; " +
                    "aborting the uncovered rebuild into give-up")
                return@synchronized CandidatePreparationResult.MissingBridgeAfterTeardown(
                    completedGiveUpLocked(sessionEpoch, "rotation bridge could not be established").disposition,
                )
            }
            currentProfileId = candidate.id
            LogRepository.setConnectionState(VpnConnectionState.CONNECTING)
            updateNotification(localizedString(R.string.vpn_status_switching))
            CandidatePreparationResult.Prepared(if (retained) RotationBridgePreparation.RETAINED else RotationBridgePreparation.ESTABLISHED)
        }
    }

    override fun bringUpRotationCandidate(sessionEpoch: Long, candidate: Profile): CandidateBringUpResult {
        val inputs = synchronized(lock) {
            if (!running) return CandidateBringUpResult.StoppedSession
            if (activeSessionEpoch != sessionEpoch) return CandidateBringUpResult.StaleSession
            if (sessionTunnelState != SessionTunnelState.ROTATING)
                return CandidateBringUpResult.TransitionNotOwned(sessionTunnelState)
            sessionLog to sessionTuning
        }
        val prepared = try {
            prepareTunnelBringUp(candidate, inputs.first, inputs.second)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Throwable) { return CandidateBringUpResult.PreparationFailed(error) }
        return synchronized(lock) {
            if (!running) return@synchronized CandidateBringUpResult.StoppedSession
            if (activeSessionEpoch != sessionEpoch) return@synchronized CandidateBringUpResult.StaleSession
            if (sessionTunnelState != SessionTunnelState.ROTATING)
                return@synchronized CandidateBringUpResult.TransitionNotOwned(sessionTunnelState)
            check(tunnelOwner.toRole() == TunnelRole.UNREAD_ROTATION_BRIDGE)
            TunnelOwnerTransitions.prepareRealEstablishment(tunnelOwner, SessionTunnelState.ROTATING)
            val descriptor = try {
                prepared.builder.establish()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) { return@synchronized CandidateBringUpResult.EstablishThrew(error) }
                ?: return@synchronized CandidateBringUpResult.EstablishReturnedNull
            val installed = TunnelOwnerTransitions.installLiveProxy(tunnelOwner, descriptor)
            tunnelOwner = installed.owner
            var closeFailure: Throwable? = null
            try { installed.priorBridgeToClose?.close() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) { closeFailure = error }
            val fd = descriptor.fd
            bestEffortRotationDiagnostic { "TUN established with fd=$fd" }
            closeFailure?.let { error ->
                bestEffortRotationDiagnostic { "Failover: rotation bridge close warning: ${error.message}" }
            }
            if (installed.priorBridgeToClose != null) {
                bestEffortRotationDiagnostic { "Failover: rotation bridge released (handing over to the newly established tunnel)" }
            }
            bestEffortRotationDiagnostic { "Using geofiles from ${prepared.geoAssetDir.absolutePath}" }
            var stage = CandidatePostEstablishmentStage.REGISTER_PROTECTOR
            try {
                XrayBridge.registerProtector(this@XrayVpnService).getOrThrow()
                stage = CandidatePostEstablishmentStage.START_XRAY
                XrayBridge.startXray(prepared.configJson, fd, prepared.geoAssetDir.absolutePath).getOrThrow()
                stage = CandidatePostEstablishmentStage.APPEND_CORE_STARTED_LOG
                LogRepository.append("Xray core started")
                CandidateBringUpResult.Started
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) { CandidateBringUpResult.FailedAfterLiveInstallation(stage, error) }
        }
    }

    override fun rollBackFailedCandidate(sessionEpoch: Long, candidateId: Long, previousProfileId: Long): CandidateRollbackResult = synchronized(lock) {
        if (!running) return@synchronized CandidateRollbackResult.StoppedSession
        if (activeSessionEpoch != sessionEpoch) return@synchronized CandidateRollbackResult.StaleSession
        if (sessionTunnelState != SessionTunnelState.ROTATING)
            return@synchronized CandidateRollbackResult.TransitionNotOwned(sessionTunnelState)
        episodeFailedIds = FailoverEpisodeDecision.recordBringUpFailure(episodeFailedIds, candidateId)
        val retained = tunnelOwner.toRole() == TunnelRole.UNREAD_ROTATION_BRIDGE
        if (!retained) tearDownTunnelLocked()
        val required = shouldEstablishRotationBridge(tunnelOwner.toRole(), sessionTunnelState)
        if (required) establishRotationBridgeLocked()
        if (shouldAbortRotationForMissingBridge(required, tunnelOwner.toRole())) {
            LogRepository.append("Failover: rotation bridge could not be re-established; " +
                "aborting the uncovered rebuild into give-up")
            return@synchronized CandidateRollbackResult.MissingBridgeAfterCandidateFailure(
                completedGiveUpLocked(sessionEpoch, "rotation bridge could not be established").disposition,
            )
        }
        sessionTunnelState = SessionTunnelState.CONNECTED
        currentProfileId = previousProfileId
        CandidateRollbackResult.RolledBack(if (retained) RotationBridgePreparation.RETAINED else RotationBridgePreparation.ESTABLISHED)
    }

    override fun reportCandidateFailureAndEnqueueRetry(sessionEpoch: Long, candidateName: String, failure: CandidateBringUpResult.Failure) {
        LogRepository.append("Failover: $candidateName failed to come up: ${failure.diagnostic()}")
        rotateTunnel(sessionEpoch, RotationRequest.CandidateRetry)
    }

    override fun commitRotation(sessionEpoch: Long): RotationCommitResult = synchronized(lock) {
        if (!running) return@synchronized RotationCommitResult.StoppedSession
        if (activeSessionEpoch != sessionEpoch) return@synchronized RotationCommitResult.StaleSession
        if (sessionTunnelState != SessionTunnelState.ROTATING)
            return@synchronized RotationCommitResult.TransitionNotOwned(sessionTunnelState)
        sessionTunnelState = SessionTunnelState.CONNECTED
        afterCommittedRotationStep("clearing the give-up line") { clearGiveUpLineLocked() }
        afterCommittedRotationStep("publishing the connected state") { LogRepository.setConnectionState(VpnConnectionState.CONNECTED) }
        giveUpOutcome = null
        unprotectedRetryConsumed = false
        unprotectedEpisodeSinceMs = null
        afterCommittedRotationStep("retracting the give-up alert") { VpnNotifications.cancelFailoverBlackholed(this@XrayVpnService) }
        afterCommittedRotationStep("refreshing the ongoing notification") { updateNotification(localizedString(R.string.vpn_status_connected)) }
        RotationCommitResult.Committed(if (pendingKillLabel != null) DeferredKillReplay.PENDING else DeferredKillReplay.NOT_PENDING)
    }

    private fun committedEffect(sessionEpoch: Long, step: String, effect: () -> Unit): CommittedSessionEffectResult = synchronized(lock) {
        if (!running) return@synchronized CommittedSessionEffectResult.StoppedSession
        if (activeSessionEpoch != sessionEpoch) return@synchronized CommittedSessionEffectResult.StaleSession
        try {
            effect()
            CommittedSessionEffectResult.Applied
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Throwable) {
            bestEffortRotationDiagnostic { "Failover rotation: $step failed after the transition committed: ${error.message}" }
            CommittedSessionEffectResult.Failed(error)
        }
    }
    override fun recordCommittedActiveProfile(sessionEpoch: Long, profileId: Long): CommittedSessionEffectResult =
        committedEffect(sessionEpoch, "recording the new active profile") {
            ActiveProfileRepository.setActiveProfileId(this@XrayVpnService, profileId)
        }
    override fun postCommittedSwitchNotice(sessionEpoch: Long, previousProfileName: String, candidateProfileName: String): CommittedSessionEffectResult =
        committedEffect(sessionEpoch, "posting the switched-server notice") {
            VpnNotifications.postFailover(this@XrayVpnService, previousProfileName, candidateProfileName)
        }
    override fun enqueueDeferredKillReplay(sessionEpoch: Long): DeferredKillEnqueueResult = try {
        replayDeferredKill(sessionEpoch)
        DeferredKillEnqueueResult.Enqueued
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Throwable) {
        bestEffortRotationDiagnostic { "Failover rotation: replaying the deferred kill failed after the transition committed: ${error.message}" }
        DeferredKillEnqueueResult.Failed(error)
    }
    override fun failRotation(sessionEpoch: Long, logMessage: String): RotationFailureResult = synchronized(lock) {
        if (!running) return@synchronized RotationFailureResult.StoppedSession
        if (activeSessionEpoch != sessionEpoch) return@synchronized RotationFailureResult.StaleSession
        LogRepository.append(logMessage)
        when (val settlement = giveUpRotationLocked(sessionEpoch, "rotation error")) {
            is GiveUpSettlement.StoodDown -> RotationFailureResult.StoodDown(settlement.actualState)
            is GiveUpSettlement.Completed -> RotationFailureResult.GaveUp(settlement.disposition)
        }
    }
    override fun admitFailoverRearm(sessionEpoch: Long): FailoverRearmAdmission =
        admitFailoverRearmFromService(sessionEpoch)
    override fun handleUnprotectedRearm(sessionEpoch: Long, unprotectedSinceMs: Long): UnprotectedRearmResult =
        handleUnprotectedRearmFromService(sessionEpoch, unprotectedSinceMs)
    override fun settleHealthyProbe(sessionEpoch: Long): HealthyProbeResult =
        clearGiveUpStateOnRecovery(sessionEpoch)
    override fun reconcileFailoverSettings(sessionEpoch: Long, settings: FailoverSettings): SettingsReconciliationResult =
        applyFailoverPreferences(settings, sessionEpoch)
    override fun reconcileAuthoritativeFailoverSettings(sessionEpoch: Long): SettingsReconciliationResult = try {
        applyFailoverPreferences(authoritativeFailoverSettings(), sessionEpoch)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Throwable) {
        bestEffortRotationDiagnostic { "Failover: settings reconciliation failed: ${error.message}" }
        throw error
    }
}
```

The declaration includes a private adapter helper `committedEffect`, not an eighteenth interface member. Remove the obsolete private `failRotation` once all rotation paths call the adapter; retain `afterTransitionCommitted`/`afterReviveCommitted` for revive. Remove `afterRotationCommitted` only after its rotation uses are replaced. Preserve helper diagnostics from the separately reviewed settle-up prerequisite; the new line-clear step label must match that reviewed correction rather than introduce a second diagnostic spelling.

The compatibility `handleUnprotectedRearmFromService` initially retains today's stop-effect ordering and is not called via the engine timer until Phase 2's separate timeout correction passes. Its explicit final-contract gap is listed in that plan. Do not claim the full target host is verified merely because all 17 methods compile. All methods exercised by rotation must already satisfy their final approved contract.

- [ ] **Step 5: Apply typed private helper material and audit every changed caller**

Add or replace the private helpers below as part of this task. Preserve their explanatory source comments when moving statements. These bodies belong to Phase 1 and make every adapter member real; the service timer and healthy/settings callers still invoke these helpers directly until Phases 2 and 3. Do not copy logic into both a helper and its old inline caller.

**5a — Expose the actual give-up settlement result.** Callers already hold a validated lifecycle lock. Structural owner helpers are existing Phase-0 code, not reimplemented here. The completed result follows required publication, scheduling, and dropped-kill handling; an exception cannot fabricate completion.

```kotlin
private fun giveUpRotationLocked(sessionEpoch: Long, reason: String): GiveUpSettlement {
    LogRepository.append("Failover: giving up ($reason)")
    val deferredKillLabel = pendingKillLabel.also { pendingKillLabel = null }
    if (sessionTunnelState == SessionTunnelState.ROTATING) {
        sessionTunnelState = SessionTunnelState.CONNECTED
    }
    episodeFailedIds = emptySet()
    stopFailoverMonitorLocked()
    reconcileScreenReceiverLocked(sessionEpoch)
    if (sessionTunnelState != SessionTunnelState.CONNECTED) {
        val actualState = sessionTunnelState
        LogRepository.append("Failover: tunnel is $sessionTunnelState; leaving it to its owner")
        releaseRotationBridgeLocked("standing down to the $sessionTunnelState tunnel's owner")
        scheduleFailoverRearmLocked(sessionEpoch, retryByRotation = false)
        announceDroppedDeferredKillLocked(deferredKillLabel)
        return GiveUpSettlement.StoodDown(actualState)
    }
    when (containmentForGiveUp(tunnelOwner.toRole(), sessionTunnelState)) {
        GiveUpContainment.ADOPT_ROTATION_BRIDGE -> adoptRotationBridgeLocked()
        GiveUpContainment.ESTABLISH_BLACKHOLE -> establishBlackholeTunnelLocked()
        GiveUpContainment.NONE -> Unit
    }
    val outcome = classifyGiveUpOutcome(tunnelOwner.toRole())
    if (shouldStopServiceOnGiveUp(outcome, unprotectedRetryConsumed)) {
        LogRepository.append("Failover: recovery attempt also failed to bring up a tunnel; stopping the VPN")
        giveUpOutcome = null
        unprotectedRetryConsumed = false
        unprotectedEpisodeSinceMs = null
        LogRepository.emitError(R.string.vpn_failover_stopped_error)
        postErrorNotification(R.string.vpn_failover_stopped_error)
        stopVpn(expectedSessionEpoch = sessionEpoch)
        announceDroppedDeferredKillLocked(deferredKillLabel)
        return GiveUpSettlement.Completed(GiveUpDisposition.STOPPED_AFTER_UNPROTECTED_RETRY)
    }
    giveUpOutcome = outcome
    giveUpLine = giveUpOngoingLine(outcome)
    LogRepository.setGiveUpLine(giveUpLine)
    LogRepository.setConnectionState(connectionStateForGiveUp(outcome))
    when (outcome) {
        FailoverGiveUpOutcome.CONTAINED_BY_LIVE_TUNNEL -> {
            LogRepository.append("Failover: no server to switch to; the current tunnel is still up and traffic stays inside it")
            updateNotification(localizedString(R.string.vpn_status_no_response))
            VpnNotifications.postFailoverNoResponse(this)
        }
        FailoverGiveUpOutcome.CONTAINED_BY_BLACKHOLE -> {
            LogRepository.append("Failover: traffic is held in an unread tunnel; nothing leaks to the open network")
            updateNotification(localizedString(R.string.vpn_status_blackholed))
            VpnNotifications.postFailoverBlackholed(this)
        }
        FailoverGiveUpOutcome.UNPROTECTED -> {
            LogRepository.append("Failover: WARNING - no tunnel could be established, traffic is NOT contained")
            LogRepository.emitError(R.string.vpn_failover_unprotected_error)
            updateNotification(localizedString(R.string.vpn_status_unprotected))
            VpnNotifications.postFailoverUnprotected(this)
        }
    }
    val retryByRotation = outcome == FailoverGiveUpOutcome.UNPROTECTED
    val since = System.currentTimeMillis()
    if (retryByRotation) unprotectedEpisodeSinceMs = since
    scheduleFailoverRearmLocked(sessionEpoch, retryByRotation, since)
    announceDroppedDeferredKillLocked(deferredKillLabel)
    return GiveUpSettlement.Completed(when (outcome) {
        FailoverGiveUpOutcome.CONTAINED_BY_LIVE_TUNNEL -> GiveUpDisposition.LIVE_TUNNEL_REARMED
        FailoverGiveUpOutcome.CONTAINED_BY_BLACKHOLE -> GiveUpDisposition.BLACKHOLE_REARMED
        FailoverGiveUpOutcome.UNPROTECTED -> GiveUpDisposition.UNPROTECTED_RECOVERY_REARMED
    })
}
```

**5b — Consolidate the timer's existing admission and action into typed private helpers.** They remain service-owned and non-suspending. The STOP_SERVICE arm shown here is explicitly the inspected Phase-1 baseline, not the approved final effect-time boundary. If the separate timeout correction already landed, preserve its locked effect block rather than regressing to this baseline. Otherwise the Phase-2 gate requires that correction before its timer migration. No engine timer callback is installed by this step.

```kotlin
private fun admitFailoverRearmFromService(sessionEpoch: Long): FailoverRearmAdmission {
    val result = synchronized(lock) {
        when {
            !running -> FailoverRearmAdmission.StoppedSession
            activeSessionEpoch != sessionEpoch -> FailoverRearmAdmission.StaleSession
            !shouldFireFailoverRetry(authoritativeFailoverSettings().enabled, isCurrentSessionLocked(sessionEpoch)) ->
                FailoverRearmAdmission.FeatureDisabled
            else -> {
                rotationAttempts = emptyList()
                FailoverRearmAdmission.Admitted
            }
        }
    }
    if (result != FailoverRearmAdmission.Admitted) {
        LogRepository.append("Failover: retry timer stood down (feature disabled or session ended)")
    }
    return result
}

private fun handleUnprotectedRearmFromService(
    sessionEpoch: Long,
    unprotectedSinceMs: Long,
): UnprotectedRearmResult {
    val action = synchronized(lock) {
        if (!running) return UnprotectedRearmResult.StoppedSession
        if (activeSessionEpoch != sessionEpoch) return UnprotectedRearmResult.StaleSession
        val decided = unprotectedRetryAction(
            tunnelState = sessionTunnelState,
            unprotectedSinceMs = unprotectedSinceMs,
            now = System.currentTimeMillis(),
            rotationWindowMs = authoritativeFailoverSettings().rotationWindowMs,
        )
        if (decided == UnprotectedRetryAction.DEFER) {
            LogRepository.append("Failover: recovery rotation cannot be attempted yet (tunnel is " +
                "$sessionTunnelState); re-arming without spending the retry")
            scheduleFailoverRearmLocked(sessionEpoch, retryByRotation = true,
                unprotectedSinceMs = unprotectedSinceMs)
        }
        decided
    }
    return when (action) {
        UnprotectedRetryAction.ATTEMPT -> {
            rotateTunnel(sessionEpoch, RotationRequest.UnprotectedRecovery(unprotectedSinceMs))
            UnprotectedRearmResult.RotationEnqueued
        }
        UnprotectedRetryAction.DEFER -> UnprotectedRearmResult.RecoveryDeferred
        UnprotectedRetryAction.STOP_SERVICE -> {
            // Baseline only. Preserve an already-landed timeout correction; Phase 2 requires it.
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

**5c — Keep a single direct service timer in Phase 1.** Retain the Boolean/default-time signature until Phase 2 converts every caller. Replace its old duplicated inline decisions with the two helpers above; do not retain both paths. Cancellation, parent job, delay source, and caller context stay unchanged.

```kotlin
private fun scheduleFailoverRearmLocked(
    sessionEpoch: Long,
    retryByRotation: Boolean,
    unprotectedSinceMs: Long = System.currentTimeMillis(),
) {
    val windowMs = authoritativeFailoverSettings().rotationWindowMs
    failoverRearmJob?.cancel()
    failoverRearmJob = serviceScope.launch {
        delay(windowMs)
        if (admitFailoverRearmFromService(sessionEpoch) != FailoverRearmAdmission.Admitted) return@launch
        if (retryByRotation) {
            handleUnprotectedRearmFromService(sessionEpoch, unprotectedSinceMs)
        } else {
            applyFailoverPreferences(authoritativeFailoverSettings(), sessionEpoch)
        }
    }
}
```

**5d — Expose the healthy helper's actual locked exits.** Failed IDs clear before marker/owner checks. Retain the existing comment explaining the accepted healthy-probe thrash-window reset. The monitor still calls this helper directly in Phase 1; no new exception handler, failRotation call, monitor rebuild, or live-owner-only rule.

```kotlin
private fun clearGiveUpStateOnRecovery(sessionEpoch: Long): HealthyProbeResult = synchronized(lock) {
    if (!running) return@synchronized HealthyProbeResult.StoppedSession
    if (activeSessionEpoch != sessionEpoch) return@synchronized HealthyProbeResult.StaleSession
    if (sessionTunnelState != SessionTunnelState.CONNECTED)
        return@synchronized HealthyProbeResult.IgnoredOutsideConnected(sessionTunnelState)
    episodeFailedIds = FailoverEpisodeDecision.clearOnHealthyProbe(episodeFailedIds)
    if (giveUpOutcome == null) return@synchronized HealthyProbeResult.EpisodeFailuresCleared
    if (tunnelOwner.toRole() == TunnelRole.NONE) return@synchronized HealthyProbeResult.IgnoredWithoutTunnel
    LogRepository.append("Failover: tunnel is passing traffic again; clearing the give-up state")
    giveUpOutcome = null
    unprotectedRetryConsumed = false
    unprotectedEpisodeSinceMs = null
    rotationAttempts = emptyList()
    clearGiveUpLineLocked()
    LogRepository.setConnectionState(VpnConnectionState.CONNECTED)
    updateNotification(localizedString(R.string.vpn_status_connected))
    VpnNotifications.cancelFailoverBlackholed(this)
    HealthyProbeResult.GiveUpCleared
}
```

**5e — Expose settings outcomes from the same existing transaction.** Capture monitor presence on entry, not after a stop. Keep the captured-emission parameter, restoration timing, old direct monitor callbacks, and current Boolean schedule signature in this phase. Later phases replace only their named caller sites, not this helper's policy.

```kotlin
private fun applyFailoverPreferences(
    settings: FailoverSettings,
    sessionEpoch: Long,
): SettingsReconciliationResult = synchronized(lock) {
    if (!running) return@synchronized SettingsReconciliationResult.StoppedSession
    if (activeSessionEpoch != sessionEpoch) return@synchronized SettingsReconciliationResult.StaleSession
    val hadMonitor = failoverMonitor != null
    var giveUpEffect: DisableGiveUpEffect = DisableGiveUpEffect.None
    if (!settings.enabled) {
        failoverRearmJob?.cancel()
        failoverRearmJob = null
        rotationAttempts = emptyList()
        if (failoverMonitor != null) {
            stopFailoverMonitorLocked()
            LogRepository.append("Failover monitor stopped (feature disabled)")
        }
        val releasedOutcome = giveUpOutcome
        if (shouldReleaseGiveUpOnDisable(settings.enabled, releasedOutcome)) {
            giveUpOutcome = null
            unprotectedRetryConsumed = false
            unprotectedEpisodeSinceMs = null
            VpnNotifications.cancelFailoverBlackholed(this)
            when (releasedOutcome) {
                FailoverGiveUpOutcome.CONTAINED_BY_BLACKHOLE ->
                    LogRepository.emitError(R.string.vpn_failover_disabled_while_blackholed)
                FailoverGiveUpOutcome.CONTAINED_BY_LIVE_TUNNEL ->
                    LogRepository.emitError(R.string.vpn_failover_disabled_while_degraded)
                FailoverGiveUpOutcome.UNPROTECTED, null -> Unit
            }
            giveUpEffect = DisableGiveUpEffect.ContainedReleased(checkNotNull(releasedOutcome))
        } else if (releasedOutcome == FailoverGiveUpOutcome.UNPROTECTED) {
            giveUpEffect = DisableGiveUpEffect.UnprotectedRetained
        }
    }
    var restoration = UnprotectedRearmRestoration.NOT_NEEDED
    if (shouldRestoreUnprotectedRearm(
            failoverEnabled = settings.enabled,
            giveUpOutcome = giveUpOutcome,
            tunnelRole = tunnelOwner.toRole(),
            rearmJobActive = failoverRearmJob?.isActive == true,
        )) {
        val since = unprotectedEpisodeSinceMs ?: System.currentTimeMillis()
        unprotectedEpisodeSinceMs = since
        LogRepository.append("Failover: restoring UNPROTECTED recovery re-arm after re-enable")
        scheduleFailoverRearmLocked(sessionEpoch, retryByRotation = true, unprotectedSinceMs = since)
        restoration = UnprotectedRearmRestoration.RESTORED
    }
    if (!shouldRunFailoverMonitor(settings.enabled, running, sessionTunnelState)) {
        stopFailoverMonitorLocked()
        reconcileScreenReceiverLocked(sessionEpoch)
        val change = if (hadMonitor) MonitorChange.STOPPED else MonitorChange.ABSENT
        return@synchronized if (!settings.enabled) {
            SettingsReconciliationResult.Disabled(change, giveUpEffect)
        } else {
            SettingsReconciliationResult.NotMonitoring(change, restoration)
        }
    }
    if (failoverMonitor != null) {
        if (!failoverMonitorNeedsRebuild(failoverMonitorSettings, settings)) {
            return@synchronized SettingsReconciliationResult.Monitoring(MonitorChange.UNCHANGED, restoration)
        }
        LogRepository.append("Failover: rebuilding monitor for updated probe timings")
        stopFailoverMonitorLocked()
    }
    failoverMonitorSettings = settings
    failoverMonitor = TunnelHealthMonitor(
        probe = Http204HealthProbe(ConfigBuilder.HEALTH_PROBE_TARGET_URL, settings.probeTimeoutMs),
        availability = AndroidNetworkAvailability(applicationContext),
        intervalMs = settings.probeIntervalMs,
        failureThreshold = settings.failureThreshold,
    ).also { monitor ->
        monitor.start(
            onHealthy = { clearGiveUpStateOnRecovery(sessionEpoch) },
            onUnhealthy = { rotateTunnel(sessionEpoch) },
        )
    }
    LogRepository.append("Failover monitor started (interval=${settings.probeIntervalMs}ms, " +
        "threshold=${settings.failureThreshold})")
    reconcileScreenReceiverLocked(sessionEpoch)
    SettingsReconciliationResult.Monitoring(
        if (hadMonitor) MonitorChange.REBUILT else MonitorChange.STARTED,
        restoration,
    )
}
```

The unchanged-monitor return skips stop/start/receiver reconciliation but not earlier restoration. Construction/logging/notification/scheduling exceptions do not return completed reconciliation. The actual helper, not the fake fixture, must satisfy the spec's legal result table.

**5f — Audit the entire resulting caller map.** Steps 1–5 form one coherent compiling integration; the service must contain one copy of each decision/effect path.

| Caller | Phase-1 behavior |
|---|---|
| Unhealthy monitor | Existing callback still `rotateTunnel(sessionEpoch)`, default unhealthy request |
| Candidate failure | Adapter tail passes `CandidateRetry` |
| Unprotected timer | Existing service timer decision passes `UnprotectedRecovery`; no engine timer call yet |
| Healthy monitor | Calls typed private `clearGiveUpStateOnRecovery` directly, ignores return |
| Startup seed/collector | Calls typed private `applyFailoverPreferences` directly, ignores return |
| Revive | Retains `afterReviveCommitted` around direct typed private reconciliation |
| Rotation post-commit | Adapter authoritative reconciliation uses approved context-neutral diagnostic |
| Stop/revoke/destroy, screen receiver, kill-switch settings | Unchanged service-owned paths |

Search all callers by exact symbol. No overload accepting the old nullable recovery timestamp may remain after its only timer call is mechanically remapped. Give-up helper callers ignoring its new result are valid only if their epoch/lock preconditions remain checked.

- [ ] **Step 6: Compile and run regression tasks serially**

```powershell
./gradlew.bat :app:compileDebugKotlin
./gradlew.bat :app:testDebugUnitTest --tests "*.FailoverEngineTest"
./gradlew.bat :app:testDebugUnitTest
./gradlew.bat :app:lintDebug
```

Expect zero failures. These commands are for later authorized execution, not plan writing. Fix compile/test errors before proceeding; do not change signatures, owner policy, or expected behavior to obtain green. Existing `SessionLifecycleDecisionTest`, `SessionLifecycleRotationTest`, `TunnelOwnerTransitionsTest`, resolver, episode, and monitor tests must remain in the full suite.

- [ ] **Step 7: Human-review the real boundaries and execute Phase-1 device QA**

Review the `vpn/` diff against the approved spec, not only the fake:

- Every state-changing transaction checks running/epoch/state under lock; failed-ID snapshot comes after DAO and before unlock. Only documented diagnostic/enqueue exceptions permit stale work.
- Reservation cleanup cannot affect a new session; consumption precedes thrash admission but never occurs at refusal/schedule/defer.
- Completed give-up types follow actual settlement; the wrapper covers only helper entry and never converts cancellation. All give-up-triggering host branches get an injected settlement throw without a second give-up.
- Establish-first null/throw keeps bridge identity; installation precedes one bounded close; close and four protected appends cannot skip operational startup; protector/start/final-core-log faults enter typed partial-live rollback. No generic teardown touches a retained bridge.
- Commit is outside engine failure handling. Line clearing and diagnostic reporting have independent isolation; a pause in the same epoch still permits committed persistence/notice. No old-session notice and no stale marker consumption.

Use existing `docs/qa/auto-failover-qa.md`: Test 1 Basic rotation, Test 4 Thrash cap, Test 14 T10b's rotation-in-flight residual, Test 23a routine switch, 23b exhausting pool, 23d Stop during switch, Test 24a and Test 10's kill-during-rotation paragraph. Run `:app:connectedDebugAndroidTest` only on a consented prepared device, preserving app data.

For fault cases not triggerable with ordinary configurations, use the debugger on a local non-shipping test build to force the named host/Builder operation to return null/throw or pause before its locked effect; record exactly which boundary was exercised. Do not persist production fault hooks or settings without a separately approved test seam. If the debugger/device cannot exercise a case reliably, record it as missing and leave its acceptance gate open—do not replace it with a fake or claim a reproduction.

Capture the **underlying interface for the full switch**, filtered to device IP and excluding proxy endpoint A/B traffic. Generate traffic to a tester-chosen plain HTTP destination, never the health-probe target. Any such packet escaping during replacement establish or old-bridge cleanup fails. Diagnostic order is rotation → bridge established → replacement established → optional close warning → exact release audit → geofiles. On null/throw, no replacement-established/release audit; the bridge stays stable and give-up adopts it instead of opening another blackhole.

- [ ] **Step 8: Record stop/go and conditional signed commit boundary**

Gate stays open for any unreviewed code, missing structural/correction evidence, failing test/lint, missing required device/fault evidence, or packet escape. Do not start Phase 2 from an open gate. Record test commands/results, reviewer decision, device/build, capture span and verdict; never include private endpoints or raw unredacted core logs.

If—and only if—the maintainer later explicitly requested commits, stage only this phase's three production Kotlin files, two test files, and `XrayVpnService.kt`:

```powershell
git add -- app/src/main/java/com/justme/xtls_core_proxy/vpn/FailoverContract.kt app/src/main/java/com/justme/xtls_core_proxy/vpn/TunnelHost.kt app/src/main/java/com/justme/xtls_core_proxy/vpn/FailoverEngine.kt app/src/main/java/com/justme/xtls_core_proxy/vpn/XrayVpnService.kt app/src/test/java/com/justme/xtls_core_proxy/vpn/RecordingTunnelHost.kt app/src/test/java/com/justme/xtls_core_proxy/vpn/FailoverEngineTest.kt
git commit -S -m "Extract failover rotation behind semantic host" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

Do not stage unrelated existing changes or a spec. Signing failure stops here; do not modify external config, amend, or fall back unsigned.
