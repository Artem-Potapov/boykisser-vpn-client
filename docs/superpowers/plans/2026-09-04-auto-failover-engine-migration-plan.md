# Auto-Failover Engine Migration Implementation Plan

> **DOCUMENTATION-ONLY INDEX.** The maintainer approved the amended written spec on 2026-09-23 and requested plan authoring, not implementation. This index records that approval and the synchronized contract. It is not an instruction to execute any plan, create production code/tests, run builds/device QA, or commit. Later execution requires separate authorization, structural-foundations completion, and the phase-specific correctness gates below.

**Goal:** Plan extraction of automatic failover sequencing from `XrayVpnService` into the approved passive `FailoverEngine`, without changing ownership, policy, or packet guarantees.

**Architecture:** The complete written contract was approved by the maintainer in this conversation on 2026-09-23. This index and the contract-recovery plan are synchronized under recovery Task 5 Step 6; the exact contract reference below is a transcription, not a new design. Four phase-sized plans may now be authored and reviewed; approval of the spec does not authorize their execution.

**Tech Stack:** Kotlin, coroutines, `Dispatchers.IO.limitedParallelism(1)`, JUnit4, Android instrumentation, `XrayVpnService`, `TunnelHealthMonitor`, existing failover/lifecycle policy.

**Spec:** `docs/superpowers/specs/2026-09-04-auto-failover-engine-migration-design.md` — see `2026-09-23 — Written-spec approval for planning`.

## Approval and synchronization record

- **Approver:** The maintainer, by the explicit message approving the written spec and requesting `superpowers:writing-plans`.
- **Date:** 2026-09-23.
- **Approved scope:** The written contract, including its two disclosed diagnostic-only differences; synchronize these two gate documents and author four plans.
- **Not approved here:** Implementation, prerequisite bug fixes, plan execution, builds, tests, device changes, commits, pushes, or external configuration changes.
- **Synchronization:** Architecture/goal and Task 1/5/6 gate language now describe the approved contract. Operative source inventory and historical change control are retained. The authoritative 17-member interface, four engine entries, and request/result declarations are reproduced below.
- **At synchronization:** All four phase-plan files were absent. Their authoring is a separate next task, not part of claiming synchronization complete. Their current delivery status is recorded in the Plan Set.

## Execution prerequisites — not satisfied by plan authoring

All four structural documents must have completed their execution stop/go gates:

1. `docs/superpowers/plans/2026-09-03-auto-failover-structural-foundations-plan.md`
2. `docs/superpowers/plans/2026-09-03-auto-failover-structural-foundations-owner-plan.md`
3. `docs/superpowers/plans/2026-09-03-auto-failover-structural-foundations-capture-plan.md`
4. `docs/superpowers/plans/2026-09-03-auto-failover-structural-foundations-verification-plan.md`

Current inspected production still uses three owner fields and release-first establishment. Do not execute the engine plans against that baseline. Require the single sealed owner, exact role-based predicates, capture applicator, and full-switch establish-first packet evidence first. Do not add compatibility overloads or implement structural work inside an engine task.

Separate reviewed corrections remain gates, not extraction tasks:

| Gate | Required before | Evidence required |
|---|---|---|
| Stale reservation cleanup | Rotation service integration | Running/epoch rejection precedes bridge cleanup and mutation in the actual service |
| Stale switched-server notice | Rotation service integration | Current-session check and notification under the same lock |
| Post-commit settlement isolation | Rotation service integration | Every fallible settle-up effect and its error reporting is isolated; later effects continue; cancellation propagates |
| Unprotected timeout publication | Give-up/re-arm callback migration | Effect-time epoch check covers warning, error surfaces, and synchronous stop atomically |

The one-settlement exception boundary is explicitly specified target hardening. Review its adapter implementation separately from ordinary extraction and test both sides of the boundary. An approved target is not evidence that current production already satisfies it. If any prerequisite lacks approval or evidence, stop the affected phase; do not fix it opportunistically.

## Global Constraints

- The engine stores only its immutable `TunnelHost` reference. No scope, dispatcher, timer, collector, clock, captured epoch, mutable session/episode state, Android resource, DAO, or owner enters it.
- The service retains `rotationAttempts`, `episodeFailedIds`, `giveUpOutcome`, `giveUpLine`, `unprotectedRetryConsumed`, `unprotectedEpisodeSinceMs`, and `pendingKillLabel`.
- Preserve existing pure policy in top-level `SessionLifecycleDecision.kt`, `FailoverDecision`, `FailoverEpisodeDecision`, and `FailoverPoolResolver.resolve(dao, current)`.
- Rotation alone enters through the existing serialized `tunnelOpScope` launcher. Re-arm remains a direct `serviceScope` callback, healthy remains a direct monitor callback, and settings remains a startup/collector call. No extra launcher, timer generation, or callback-liveness veto.
- One candidate per rotation invocation; retries are epoch-tagged service enqueues, not an engine loop. `bringUpRotationCandidate` is non-suspending; only `loadRotationPool` and `rotate` suspend.
- State-changing host operations check running, epoch, then the required mechanical state under the service lock. Preserve only the expressly permitted historical diagnostic/enqueue exceptions.
- `CONNECTED` is the mechanical commit. The generic pre-commit catch ends before it. Post-commit failures do not become give-up; cancellation always propagates.
- Preserve establish-first and the one authoritative owner. Full-switch underlying-interface packet capture, not log ordering or a fake result, proves no packet escape.
- Preserve contained/live/unprotected distinctions and synchronous `stopVpn`; never add an await.
- Every VPN phase requires focused JVM evidence, human `vpn/` review, device QA, and its own stop/go boundary. Final verification cannot retroactively substitute for a skipped phase gate.
- Run heavyweight Gradle commands serially. Keep device app data; never opt into uninstall or clear-data for a data-bearing installation.
- No worktree is needed for document authoring. If execution is later authorized, use the existing working tree on an in-place non-main branch so local AAR/signing inputs remain available.
- Commits require a later explicit request and `git commit -S`. On signing failure stop and report; do not change external configuration or retry unsigned.
- No generated AAR/geo assets, local properties, IDE state, personal endpoints, or user-level configuration may be changed by these plans.

## Plan Set

| Phase / path under `docs/superpowers/plans/` | Responsibility | Delivery status |
|---|---|---|
| `2026-09-04-auto-failover-engine-migration-contract-recovery-plan.md` | Approved-contract verification and synchronization record; not an execution plan | Synchronized |
| `2026-09-04-auto-failover-engine-migration-rotation-plan.md` | Exact contract, passive engine, full recording fake, rotation adapter and sole launcher | Authorized to author; absent at synchronization |
| `2026-09-04-auto-failover-engine-migration-giveup-rearm-plan.md` | Typed private scheduling and timer dispatch; give-up/recovery evidence | Authorized to author; absent at synchronization |
| `2026-09-04-auto-failover-engine-migration-monitor-plan.md` | Healthy/settings callback wiring, monitor and shared-receiver verification | Authorized to author; absent at synchronization |
| `2026-09-04-auto-failover-engine-migration-verification-plan.md` | Cross-phase tests, instrumentation, packet/device matrix, docs, release evidence | Author after the three phase plans; absent at synchronization |

## File ownership and phased wiring

| Plan | Owns | Must not own |
|---|---|---|
| Contract recovery | Approval record, exact seam reference, inventory/target distinction | Production implementation or redesign |
| Rotation | `FailoverContract.kt`, `TunnelHost.kt`, `FailoverEngine.kt`, full test-only `RecordingTunnelHost`, `FailoverEngineTest`; rotation integration in `XrayVpnService` | Descriptor policy, episode-state ownership, pool derivation, timer/collector ownership |
| Give-up/re-arm | Extensions to the existing engine tests; private timer request conversion and timer callback switch | A second fake/engine, containment policy, asynchronous stop |
| Monitor | Extensions to the existing engine tests; healthy/settings/startup/revive call-site migration | Health-probe internals, screen receiver or kill-switch ownership |
| Verification | Aggregate/re-run existing tests, final device/release evidence, three specified maintainer docs | Introducing the first engine fake/test, new behavior, signature changes |

The rotation phase introduces a complete 17-member interface and fake, not success-returning stubs. Its own plan contains the concrete Phase-1 typed helper bodies needed by all adapter members; later plans consume those helpers but do not supply implementation back to rotation. The engine's four entries are implemented/tested in rotation before the timer and monitor production callers switch. Service helper result annotations needed by the adapter expose real existing exits, not permission to move later-phase callback scheduling early. Each later plan names its caller changes and verifies every affected integration.

Rotation cannot pass without focused `FailoverEngineTest`/`RecordingTunnelHost` evidence in `app/src/test/java/com/justme/xtls_core_proxy/vpn/`. Verification aggregates these tests; it does not first introduce them.

## Exact approved contract reference

All declarations are `internal` in `com.justme.xtls_core_proxy.vpn`. `Profile` is `com.justme.xtls_core_proxy.db.Profile`; `FailoverSettings` is `com.justme.xtls_core_proxy.failover.FailoverSettings`. Existing `SessionTunnelState` and `FailoverGiveUpOutcome` remain in `vpn/SessionLifecycleDecision.kt`. Code below describes declarations, not runnable production added by this index.

### Four engine entries

```kotlin
internal class FailoverEngine(
    private val host: TunnelHost,
) {
    suspend fun rotate(
        sessionEpoch: Long,
        request: RotationRequest,
    )

    fun onRearmTimer(
        sessionEpoch: Long,
        request: FailoverRearmRequest,
    )

    fun onHealthyProbe(sessionEpoch: Long): HealthyProbeResult

    fun onFailoverSettings(
        sessionEpoch: Long,
        settings: FailoverSettings,
    ): SettingsReconciliationResult
}
```

### Single 17-member host

```kotlin
internal interface TunnelHost {
    fun beginRotation(sessionEpoch: Long, request: RotationRequest): RotationStartResult
    suspend fun loadRotationPool(sessionEpoch: Long, currentProfileId: Long): RotationPoolResult
    fun giveUpExhaustedPool(sessionEpoch: Long): ExhaustedPoolResult
    fun prepareRotationCandidate(
        sessionEpoch: Long,
        current: Profile,
        candidate: Profile,
    ): CandidatePreparationResult
    fun bringUpRotationCandidate(
        sessionEpoch: Long,
        candidate: Profile,
    ): CandidateBringUpResult
    fun rollBackFailedCandidate(
        sessionEpoch: Long,
        candidateId: Long,
        previousProfileId: Long,
    ): CandidateRollbackResult
    fun reportCandidateFailureAndEnqueueRetry(
        sessionEpoch: Long,
        candidateName: String,
        failure: CandidateBringUpResult.Failure,
    )
    fun commitRotation(sessionEpoch: Long): RotationCommitResult
    fun recordCommittedActiveProfile(
        sessionEpoch: Long,
        profileId: Long,
    ): CommittedSessionEffectResult
    fun postCommittedSwitchNotice(
        sessionEpoch: Long,
        previousProfileName: String,
        candidateProfileName: String,
    ): CommittedSessionEffectResult
    fun enqueueDeferredKillReplay(sessionEpoch: Long): DeferredKillEnqueueResult
    fun failRotation(sessionEpoch: Long, logMessage: String): RotationFailureResult
    fun admitFailoverRearm(sessionEpoch: Long): FailoverRearmAdmission
    fun handleUnprotectedRearm(
        sessionEpoch: Long,
        unprotectedSinceMs: Long,
    ): UnprotectedRearmResult
    fun settleHealthyProbe(sessionEpoch: Long): HealthyProbeResult
    fun reconcileFailoverSettings(
        sessionEpoch: Long,
        settings: FailoverSettings,
    ): SettingsReconciliationResult
    fun reconcileAuthoritativeFailoverSettings(
        sessionEpoch: Long,
    ): SettingsReconciliationResult
}
```

### Requests and result ledger

```kotlin
internal sealed interface RotationRequest {
    data object UnhealthyProbe : RotationRequest
    data object CandidateRetry : RotationRequest
    data class UnprotectedRecovery(
        val unprotectedSinceMs: Long,
    ) : RotationRequest
}

internal enum class RotationRefusalCleanup {
    BRIDGE_CLEANUP_NOT_NEEDED_NO_REARM,
    BRIDGE_CLEANUP_ATTEMPTED_NO_REARM,
    BRIDGE_CLEANUP_NOT_NEEDED_RECOVERY_REARMED,
    BRIDGE_CLEANUP_ATTEMPTED_RECOVERY_REARMED,
}

internal enum class GiveUpDisposition {
    LIVE_TUNNEL_REARMED,
    BLACKHOLE_REARMED,
    UNPROTECTED_RECOVERY_REARMED,
    STOPPED_AFTER_UNPROTECTED_RETRY,
}

internal sealed interface RotationStartResult {
    data object StoppedSession : RotationStartResult
    data object StaleSession : RotationStartResult
    data object RefusedWithBridgeAdopted : RotationStartResult
    data class RefusedAfterCleanup(
        val cleanup: RotationRefusalCleanup,
    ) : RotationStartResult
    data class ThrashCapDenied(
        val disposition: GiveUpDisposition,
    ) : RotationStartResult
    data class Reserved(
        val currentProfileId: Long,
    ) : RotationStartResult
}

internal sealed interface RotationPoolResult {
    data object StoppedSession : RotationPoolResult
    data object StaleSession : RotationPoolResult
    data class MissingCurrentProfile(
        val profileId: Long,
    ) : RotationPoolResult
    data class Available(
        val current: Profile,
        val pool: List<Profile>,
        val recentlyFailed: Set<Long>,
    ) : RotationPoolResult
}

internal sealed interface ExhaustedPoolResult {
    data object StoppedSession : ExhaustedPoolResult
    data object StaleSession : ExhaustedPoolResult
    data class TransitionNotOwned(
        val actualState: SessionTunnelState,
    ) : ExhaustedPoolResult
    data class GaveUp(
        val disposition: GiveUpDisposition,
    ) : ExhaustedPoolResult
}

internal enum class RotationBridgePreparation {
    RETAINED,
    ESTABLISHED,
}

internal sealed interface CandidatePreparationResult {
    data object StoppedSession : CandidatePreparationResult
    data object StaleSession : CandidatePreparationResult
    data class TransitionNotOwned(
        val actualState: SessionTunnelState,
    ) : CandidatePreparationResult
    data class Prepared(
        val bridge: RotationBridgePreparation,
    ) : CandidatePreparationResult
    data class MissingBridgeAfterTeardown(
        val disposition: GiveUpDisposition,
    ) : CandidatePreparationResult
}

internal enum class CandidatePostEstablishmentStage {
    REGISTER_PROTECTOR,
    START_XRAY,
    APPEND_CORE_STARTED_LOG,
}

internal sealed interface CandidateBringUpResult {
    data object Started : CandidateBringUpResult
    sealed interface Failure : CandidateBringUpResult
    data object StoppedSession : Failure
    data object StaleSession : Failure
    data class TransitionNotOwned(
        val actualState: SessionTunnelState,
    ) : Failure
    data class PreparationFailed(val cause: Throwable) : Failure
    data object EstablishReturnedNull : Failure
    data class EstablishThrew(val cause: Throwable) : Failure
    data class FailedAfterLiveInstallation(
        val stage: CandidatePostEstablishmentStage,
        val cause: Throwable,
    ) : Failure
}

internal sealed interface CandidateRollbackResult {
    data object StoppedSession : CandidateRollbackResult
    data object StaleSession : CandidateRollbackResult
    data class TransitionNotOwned(
        val actualState: SessionTunnelState,
    ) : CandidateRollbackResult
    data class RolledBack(
        val bridge: RotationBridgePreparation,
    ) : CandidateRollbackResult
    data class MissingBridgeAfterCandidateFailure(
        val disposition: GiveUpDisposition,
    ) : CandidateRollbackResult
}

internal enum class DeferredKillReplay {
    NOT_PENDING,
    PENDING,
}

internal sealed interface RotationCommitResult {
    data object StoppedSession : RotationCommitResult
    data object StaleSession : RotationCommitResult
    data class TransitionNotOwned(
        val actualState: SessionTunnelState,
    ) : RotationCommitResult
    data class Committed(
        val deferredKillReplay: DeferredKillReplay,
    ) : RotationCommitResult
}

internal sealed interface CommittedSessionEffectResult {
    data object Applied : CommittedSessionEffectResult
    data object StoppedSession : CommittedSessionEffectResult
    data object StaleSession : CommittedSessionEffectResult
    data class Failed(val cause: Throwable) : CommittedSessionEffectResult
}

internal sealed interface DeferredKillEnqueueResult {
    data object Enqueued : DeferredKillEnqueueResult
    data class Failed(val cause: Throwable) : DeferredKillEnqueueResult
}

internal sealed interface RotationFailureResult {
    data object StoppedSession : RotationFailureResult
    data object StaleSession : RotationFailureResult
    data class StoodDown(val actualState: SessionTunnelState) : RotationFailureResult
    data class GaveUp(val disposition: GiveUpDisposition) : RotationFailureResult
}

internal sealed interface GiveUpSettlement {
    data class StoodDown(val actualState: SessionTunnelState) : GiveUpSettlement
    data class Completed(val disposition: GiveUpDisposition) : GiveUpSettlement
}

internal sealed interface FailoverRearmRequest {
    data object RecheckMonitor : FailoverRearmRequest
    data class UnprotectedRecovery(
        val unprotectedSinceMs: Long,
    ) : FailoverRearmRequest
}

internal sealed interface FailoverRearmAdmission {
    data object StoppedSession : FailoverRearmAdmission
    data object StaleSession : FailoverRearmAdmission
    data object FeatureDisabled : FailoverRearmAdmission
    data object Admitted : FailoverRearmAdmission
}

internal sealed interface UnprotectedRearmResult {
    data object StoppedSession : UnprotectedRearmResult
    data object StaleSession : UnprotectedRearmResult
    data object RotationEnqueued : UnprotectedRearmResult
    data object RecoveryDeferred : UnprotectedRearmResult
    data object ServiceStopRequested : UnprotectedRearmResult
}

internal sealed interface HealthyProbeResult {
    data object StoppedSession : HealthyProbeResult
    data object StaleSession : HealthyProbeResult
    data class IgnoredOutsideConnected(
        val actualState: SessionTunnelState,
    ) : HealthyProbeResult
    data object EpisodeFailuresCleared : HealthyProbeResult
    data object IgnoredWithoutTunnel : HealthyProbeResult
    data object GiveUpCleared : HealthyProbeResult
}

internal enum class MonitorChange {
    ABSENT,
    UNCHANGED,
    STOPPED,
    STARTED,
    REBUILT,
}

internal sealed interface DisableGiveUpEffect {
    data object None : DisableGiveUpEffect
    data object UnprotectedRetained : DisableGiveUpEffect
    data class ContainedReleased(
        val outcome: FailoverGiveUpOutcome,
    ) : DisableGiveUpEffect
}

internal enum class UnprotectedRearmRestoration {
    NOT_NEEDED,
    RESTORED,
}

internal sealed interface SettingsReconciliationResult {
    data object StoppedSession : SettingsReconciliationResult
    data object StaleSession : SettingsReconciliationResult
    data class Disabled(
        val monitor: MonitorChange,
        val giveUp: DisableGiveUpEffect,
    ) : SettingsReconciliationResult
    data class NotMonitoring(
        val monitor: MonitorChange,
        val unprotectedRearm: UnprotectedRearmRestoration,
    ) : SettingsReconciliationResult
    data class Monitoring(
        val monitor: MonitorChange,
        val unprotectedRearm: UnprotectedRearmRestoration,
    ) : SettingsReconciliationResult
}
```

`GiveUpSettlement` is the private service helper's result, not another host method. `GiveUpSettlementFailure` is the private exception marker described in the spec, not another result or member. Its non-public implementation must let the adapter wrap only the actual give-up invocation and the engine propagate the original cause without settling twice.

Result semantics, legal settings-result combinations, diagnostic strings, and exception boundaries remain those of the spec; these declarations alone do not authorize weaker behavior. No result fabricates completed effects after an exception.

## Hard Stop

Do not execute the resulting plans now. Written approval permits planning only. Later execution must stop if structural gates, phase-local correctness prerequisites, review authorization, device evidence, or contract consistency are missing. Any signature or policy change returns to the approved spec and recovery gate; a plan cannot silently amend it.

## Acceptance Criteria

- [x] Maintainer written-spec approval is recorded, with planning-only scope.
- [x] Index and recovery plan are synchronized; the exact contract and phase responsibilities are recorded without deleting history.
- [x] The reference keeps episode/resource state in the service and uses the existing policy/resolver components.
- [ ] Four independently reviewable phase plans are authored with concrete interfaces, test/code steps, and caller wiring.
- [ ] Rotation owns the full fake and focused engine tests; later phases extend them rather than adding a competing fake.
- [ ] Every VPN phase specifies JVM, human review, device QA, and a conditional signed-commit boundary.
- [ ] Final verification specifies instrumentation, lint, release build/device exercise, stale-reference audit, documentation, and full-switch packet capture.
- [ ] The maintainer reviews the derived plans before any execution is requested.

## Change Control

### 2026-09-23 — Approved contract synchronized for plan authoring

- **Previous decision:** The spec awaited written approval, and the four phase plans could not be derived.
- **Replacement decision:** The maintainer approved the amended written spec and requested plan authoring. Recovery Task 5 Step 6 is applied: approval/status, exact interface/results, phase ownership, and execution gates are synchronized. The four phase plans were absent at synchronization and may now be written as a separate task.
- **Reason:** Written approval resolves the contract gate, not the structural or implementation gates. The two disclosed diagnostic-only differences are part of the approved planning target.
- **Affected sections:** Banner, architecture, approval record, prerequisite table, caller-context constraint, Plan Set, ownership map, contract reference, hard stop, and acceptance checklist. No production behavior changed.

### 2026-09-23 — Review corrections pending written-spec approval

- **Current state:** The engine spec contains the complete proposed contract but remains explicitly unapproved. This index and the recovery plan must be synchronized after written approval and before the four phase plans are authored.
- **Guardrails:** Phase 0 executes the structural-foundations index plus owner, capture, and verification gates before any engine plan is executed. Phases 1–3 consume the approved seam; a signature, policy, or result change returns to the spec/recovery gate. The phase plans remain absent and this task does not authorize implementation.

### 2026-09-23 — Verified review corrections pending written-spec approval

- Corrected the live 59-line hard-stop wording, stale `failRotation` semantics, healthy-probe ordering, give-up settlement boundary, establish-first diagnostic continuation, role-based policy signatures, reservation diagnostic/result semantics, source-vs-target post-commit hardening disclosure, revive diagnostic scope, and planning-only precision.
- The first-review stale-reservation cleanup claim was not adopted: the current defect remains a separately disclosed prerequisite, and the target still rejects stale callbacks before cleanup. Already-correct monitor-result, stop-request, owner/pool/monitor, establish-first, and source-finding material remains unchanged.
- This remains a draft; no phase plans are created or executable.

### 2026-09-21 — Contract guard and plan split

- **Previous decision:** A single plan invented a `TunnelHost` interface and detailed only rotation; give-up, re-arm, monitor, cleanup, and documentation were outlined as if the missing contract were known.
- **Replacement decision:** The engine plan is an index with a mandatory contract-recovery gate. Implementation phases are separate files and may not be authored until the exact approved seam is available.
- **Reason:** The checked-in engine design ends at line 59, while the plan's proposed APIs contradicted current top-level Kotlin declarations, `Profile` package, `FailoverPoolResolver.resolve` signature, service-owned episode state, and existing monitor scheduling. A plausible-looking seam is not evidence of the approved seam.
- **Affected sections:** Architecture, global constraints, file structure, task decomposition, acceptance criteria, and execution handoff.
