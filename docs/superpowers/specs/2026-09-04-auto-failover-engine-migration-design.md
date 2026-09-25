# Auto-Failover Engine Migration Design

**Date:** 2026-09-04
**Status:** Amended written spec approved by the maintainer on 2026-09-23 for implementation planning only
**Amended:** 2026-09-23
**Implementation status:** Not started. Structural foundations are also not implemented in the inspected source. Approval of this written spec permits implementation planning only, not execution.
**Scope:** Second of two architecture/QoL refactors
**Prerequisite:** [2026-09-03-auto-failover-structural-foundations-design.md](2026-09-03-auto-failover-structural-foundations-design.md)

## Summary

This design extracts automatic failover episode sequencing from `XrayVpnService` into a JVM-testable `FailoverEngine` behind a narrow `TunnelHost` seam, without changing intended failover behavior or user-visible states. The extraction is an orchestration refactor after the approved sealed-owner/capture-plan migration; it does not authorize either structural migration or engine implementation in this document task.

The chosen design:

1. Introduces `TunnelHost` with semantic, epoch-checked transactions. `XrayVpnService` implements the host and keeps all Android resources, lifecycle locks, descriptor ownership, notifications, and serialized operation scheduling.
2. Creates one reusable `FailoverEngine` object whose only identity input is the `sessionEpoch` passed to each entry point. It has no coroutine scope, dispatcher, timer, collector, clock, or session/episode fields.
3. Moves rotation admission, candidate lookup/selection orchestration, give-up/re-arm dispatch, healthy recovery, and monitor/settings coordination into engine sequencing across independently reviewable phases. The service still performs the semantic host transactions and owns the side effects.
4. Preserves current reservation rules, thrash caps, pool resolution, bridge ownership, containment outcomes, recovery timing, kill/Stop/disable interleaving, shared screen-receiver ownership, cancellation behavior, and packet-level fail-closed behavior, except where the approved structural-foundations target already changes candidate handover from release-first to establish-first.
5. Makes every meaningful branch explicit in result types. Reservation refusal, thrash denial, missing profile, pool exhaustion, both missing-bridge aborts, candidate failure, post-commit failure, give-up containment, re-arm action, stale callback, and monitor change are never represented by one Boolean or generic failure result.

Episode bookkeeping (thrash window, per-episode failed servers, give-up/retry markers, unprotected episode timing, and deferred-kill markers) remains service-owned. Pure lifecycle policies remain in the existing top-level functions in `SessionLifecycleDecision`; candidate policy remains in `FailoverDecision` and `FailoverEpisodeDecision`; pool derivation remains in `FailoverPoolResolver`; health polling remains in `TunnelHealthMonitor`.

This is the structural follow-up to the sealed-owner/capture-plan refactor and completes the originally requested architecture QoL work only after that prerequisite has passed its own gates.

## Problem Statement

Auto-failover sequencing is embedded in `XrayVpnService` across rotation, give-up, re-arm, recovery, and monitor control. The current service has important ordering boundaries that no JVM test can currently reach as one sequence. Extraction must:

- make the ordering between rotation/give-up/re-arm/monitor decisions JVM-testable with fakes;
- preserve current reservation, thrash-cap, pool-resolution, bridge-ownership, containment, recovery, shared-receiver, and interleaving behavior;
- preserve the approved establish-first owner handover rather than encode today's release-first baseline;
- avoid widening the Android service's unsafe surface or exposing internals that compromise lock, epoch, descriptor, or packet-containment ownership;
- keep cancellation and serialized `tunnelOpScope` behavior observable and unchanged.

The refactor must be incrementally landable with human review at every `vpn/` phase. A correctness defect discovered during implementation is a separate change: it must be reported and approved independently rather than silently expanded into this migration.

## Scope

### In scope

- Define the exact passive `FailoverEngine` and semantic `TunnelHost` contract below.
- Move rotation attempt sequencing (`rotateTunnel`, candidate traversal, `failRotation`, and host-enqueued candidate retries) into the engine while leaving resource transactions in the service.
- Move give-up/re-arm dispatch and healthy recovery sequencing into the engine while leaving containment and timer ownership in the service.
- Move monitor/settings reconciliation sequencing into the engine while leaving `TunnelHealthMonitor`, the settings collector, and the shared screen receiver in the service.
- Add JVM engine tests with a fake host; retain and extend existing pure decision, component, and regression tests.
- Complete mandatory device QA after every VPN phase.
- Update affected `docs/features/auto-failover.md`, `AGENTS-DEVELOPMENT.md`, and `docs/qa/auto-failover-qa.md` only in the later verification plan, after implementation is approved.

### Out of scope

- Changing structural ownership, capture-plan behavior, or establish-first handover. Those belong to the approved structural-foundations plans and must complete first.
- Changing connection-state semantics, reservation rules, thrash caps, candidate-pick logic, pool resolution, containment outcomes, recovery timing, kill/Stop/disable interleaving, shared receiver policy, or packet-level fail-closed guarantees.
- Moving or wrapping the existing top-level `SessionLifecycleDecision`, `FailoverDecision`, `FailoverEpisodeDecision`, or `FailoverPoolResolver` into a class. `FailoverEpisodeDecision` remains the top-level object in `FailoverDecision.kt`.
- Moving `TunnelHealthMonitor` internals or giving the engine a monitor, receiver, settings collector, coroutine scope, timer, dispatcher, or clock.
- Passing `ParcelFileDescriptor`, `VpnService.Builder`, `TunnelInterfaceOwner`, `Android Context`, `ProfileDao`, Room entities as mutable service state, or any mutable service internals into the engine.
- Making episode state engine-owned.
- Latency-ordered failover, curated pools, passive detection, network-change probes, routing UI, or other deferred features.

## Current Sequencing and Ownership

The current implementation is in `app/src/main/java/com/justme/xtls_core_proxy/vpn/XrayVpnService.kt`. `rotateTunnel(sessionEpoch, unprotectedRecoverySinceMs)` is launched on the serialized `tunnelOpScope`, a `Dispatchers.IO.limitedParallelism(1)` lane. The current production handover in `bringUpTunnel` is release-first; the approved structural target is establish-first and supersedes that baseline.

The engine migration must preserve these ownership boundaries:

- `XrayVpnService` owns the lifecycle lock, `running`, session epochs, `SessionTunnelState`, sealed tunnel owner after structural migration, descriptor close/release, `VpnService.Builder`, `Builder.establish()`, config/geo/split preparation, protector/Xray startup, Android notifications, `LogRepository` state publication, `serviceScope`, `tunnelOpScope`, settings collection, re-arm timer, and shared screen receiver.
- `XrayVpnService` owns episode fields: `rotationAttempts`, `episodeFailedIds`, `giveUpOutcome`, `giveUpLine`, `unprotectedRetryConsumed`, `unprotectedEpisodeSinceMs`, and `pendingKillLabel`.
- `SessionLifecycleDecision.kt` remains a file of top-level `internal` declarations, not a `SessionLifecycleDecision` object. Its existing pure functions remain the policy source.
- `FailoverDecision.nextCandidate` and `FailoverDecision.admitRotation` remain the only candidate and sliding-window admission decisions. `FailoverEpisodeDecision.recordProbeFailedCurrent`, `recordBringUpFailure`, and `clearOnHealthyProbe` remain the episode-set decisions.
- `FailoverPoolResolver.resolve(dao: ProfileDao, current: Profile)` remains the sole pool seam. The engine must not derive a second pool.
- `TunnelHealthMonitor.start(onHealthy, onUnhealthy)`, `stop`, `pausePolling`, and `resumePolling` remain the monitor API. Its terminal unhealthy callback and one-shot healthy callback behavior remain unchanged.
- `stopVpn` remains synchronous and must not gain an await, dispatcher handoff, or other operation that waits. `CancellationException` from serialized operations propagates.

### Approved structural handover prerequisite

The structural-foundations target defines one authoritative owner:

```kotlin
internal sealed interface TunnelInterfaceOwner<out Handle> {
    data object None : TunnelInterfaceOwner<Nothing>
    class LiveProxy<Handle>(val handle: Handle) : TunnelInterfaceOwner<Handle>
    class UnreadRotationBridge<Handle>(val handle: Handle) : TunnelInterfaceOwner<Handle>
    class UnreadBlackhole<Handle>(val handle: Handle) : TunnelInterfaceOwner<Handle>
}
```

`TunnelRole` is a derived projection (`NONE`, `LIVE_PROXY`, `UNREAD_ROTATION_BRIDGE`, `UNREAD_BLACKHOLE`), never a second stored owner. During `ROTATING`, the unread bridge remains authoritative through `Builder.establish()`; a null/throw leaves it unchanged; a non-null result installs `LiveProxy` immediately, then closes the prior bridge once as a bounded local cleanup handle. The close warning and exact release audit follow `TUN established with fd=…`; diagnostic append failures cannot tear down or reclassify the new live owner. Packet capture across the whole switch, not log order, is the no-underlying-packet-escape oracle.

The engine never receives this owner or its handle. It sees only semantic host results such as bridge preparation, candidate bring-up, rollback, containment, and give-up dispositions.

The service-private role predicates consumed by the engine adapter are the exact post-structural signatures below, taken from the approved structural owner plan. Planning starts from these `TunnelRole` forms, not today's boolean `hasTunnel`/`hasRotationBridge` inputs; do not invent compatibility overloads. Keep `deferredKillNoticeLabel(tunnelStillUp: Boolean)` unchanged.

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

These predicates remain pure policy; the host derives the role from the one structural owner and retains all resource and epoch checks.

## Contract Package and General Rules

All new contract declarations below are `internal` and live in `com.justme.xtls_core_proxy.vpn`. Existing `Profile` means the immutable `com.justme.xtls_core_proxy.db.Profile`; existing `FailoverSettings` means `com.justme.xtls_core_proxy.failover.FailoverSettings` (declared in `FailoverPreferences.kt`). Existing `SessionTunnelState` and `FailoverGiveUpOutcome` are top-level declarations in `vpn/SessionLifecycleDecision.kt`.

The exact signatures below are the contract. The names are not placeholders and must not be replaced with one-for-one private-method mirrors during implementation. Per-section `TunnelHost` blocks are interface excerpts, not independently implemented types; the full member ledger is the single authoritative interface. Signature-only class blocks describe declarations, not implementation bodies. A function without an explicit return annotation in those blocks returns `Unit`.

Implementation files are `vpn/FailoverEngine.kt` for the engine, `vpn/TunnelHost.kt` for the interface, and `vpn/FailoverContract.kt` for the new request/result types. `XrayVpnService` supplies the implementation through a private inner `ServiceTunnelHost : TunnelHost`; it must not expose internal contract return types as public members on the Android service. The adapter delegates to private service helpers using the existing lock, scopes, and fields. The engine constructor stores only its immutable host reference; constructing either object performs no lifecycle action.

```kotlin
internal interface TunnelHost

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

`FailoverEngine` is passive. It does not launch, delay, collect, read a clock, own mutable session/episode fields, or retain a captured epoch. A single instance may serve successive sessions only because every entry and every epoch-sensitive host operation carries an explicit `sessionEpoch`.

Every state-changing host transaction checks, under the service lifecycle lock, in this order:

1. current service/running state;
2. callback epoch against the active epoch;
3. required mechanical state (`ROTATING`, `CONNECTED`, or other operation-specific state).

A stopped or stale result must not mutate a newer session. Off-lock DAO reads and an earlier transaction result never substitute for the next ownership check. Host transactions that intentionally perform only an obsolete diagnostic append and enqueue are marked as the exception below; they may not touch resources, episode fields, connection state, notifications, or current-session persistence.

## Exact Request, Result, and Host Signatures

### Rotation requests and admission

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

internal interface TunnelHost {
    fun beginRotation(
        sessionEpoch: Long,
        request: RotationRequest,
    ): RotationStartResult
}
```

`beginRotation` is non-suspending and atomic under the service lock. It preserves the current order:

1. Reject a stopped session with `StoppedSession`, or a different active epoch with `StaleSession`, before any bridge cleanup, diagnostic, or episode mutation. A current-session reservation refusal is **not** this no-op: it follows steps 2–3.
2. Evaluate the existing authoritative reservation rule. On refusal, apply the role-based `shouldFunnelRotationReservationRefusal`. For current `CONNECTED` plus `UnreadRotationBridge`, complete `giveUpRotationLocked(sessionEpoch, "rotation reservation refused while holding the containment bridge")` before unlocking and return `RefusedWithBridgeAdopted`. This keeps the same handle and implies completed blackhole settlement. The refusal itself neither releases nor independently re-arms; give-up schedules its own timer, even when enablement caused refusal.
3. On refusal without that funnel, run the bridge-only cleanup with reason `rotation reservation refused`. For `RotationRequest.UnprotectedRecovery`, re-arm only when the session is current and authoritative failover is enabled; preserve the original `unprotectedSinceMs` and leave the retry unspent. Return the corresponding `RefusedAfterCleanup` variant. The four cleanup values record whether bridge cleanup was needed/attempted (a current `UnreadRotationBridge` was handed to the release helper) and whether a recovery timer was scheduled; `BRIDGE_CLEANUP_ATTEMPTED_*` does not claim that `close()` succeeded, or even that a descriptor was physically closed after a helper warning. The exact current diagnostic `Failover: recovery rotation could not reserve the transition (tunnel is $sessionTunnelState); re-arming without spending the retry` remains inside the two `*_RECOVERY_REARMED` host transactions. A close warning is an internal diagnostic, does not restore the released owner, and does not create a fifth result.
4. After reservation succeeds, mark only an `UnprotectedRecovery` request as consumed **before** admission. Read one `authoritativeFailoverSettings()` tuple for both `maxRotations` and `rotationWindowMs`; call existing `FailoverDecision.admitRotation(rotationAttempts, now, maxRotations, windowMs)` with a service-supplied current time.
5. On `RotationAdmission.Denied`, complete give-up with `thrash cap reached` under that same lock and return `ThrashCapDenied(disposition)`. This is not reservation refusal. Do not set `ROTATING`, append a new attempt, or record the current profile as failed on this exit.
6. On `RotationAdmission.Admitted`, store `attempts`, enter `ROTATING`, capture `currentProfileId`, record it through `FailoverEpisodeDecision.recordProbeFailedCurrent`, stop/forget the terminal monitor, and return `Reserved(currentProfileId)`. Do not reconcile the shared screen receiver during this transient state.

`UnhealthyProbe` and `CandidateRetry` share admission policy but retain their distinct request provenance. Each retry reserves/admit-counts one candidate; the engine must not flatten queued retries into an in-call loop. A give-up or cleanup exception does not fabricate any completed admission result.

### Candidate lookup and selection

```kotlin
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

internal interface TunnelHost {
    suspend fun loadRotationPool(
        sessionEpoch: Long,
        currentProfileId: Long,
    ): RotationPoolResult
}
```

`loadRotationPool` reads `ProfileDao.getById(currentProfileId)` off-lock. Only if a profile exists does it call exactly `FailoverPoolResolver.resolve(dao, current)`, also off-lock. A `null` profile is the distinct `MissingCurrentProfile(currentProfileId)` result; an exception from `getById` or the resolver is a pre-commit exception, not that result. After lookup/resolution it acquires the lifecycle lock, checks running and epoch **before reading episode state**, and returns stopped/stale without reading the newer session's failed IDs. For a current session, presence returns `Available(current, pool.toList(), episodeFailedIds.toSet())`. These detached collections are not mutation handles. Capture failed IDs after pool resolution to preserve ordering.

This transaction deliberately does not require `ROTATING`: disappearance of the current profile still enters the current-session `failRotation` funnel if another lifecycle state intervened. Preparation and pool-exhaustion transactions subsequently recheck `ROTATING` before mutation. DAO/resolver exceptions are pre-commit errors; `CancellationException` propagates. Missing current profile is distinct from an exhausted pool.

The engine applies the existing pure selector exactly:

```kotlin
FailoverDecision.nextCandidate(
    pool = result.pool,
    currentId = result.current.id,
    recentlyFailed = result.recentlyFailed,
)
```

A null candidate means pool exhaustion, not lookup failure.

### Pool exhaustion and candidate preparation

```kotlin
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

internal interface TunnelHost {
    fun giveUpExhaustedPool(
        sessionEpoch: Long,
    ): ExhaustedPoolResult

    fun prepareRotationCandidate(
        sessionEpoch: Long,
        current: Profile,
        candidate: Profile,
    ): CandidatePreparationResult
}
```

`giveUpExhaustedPool` checks running/epoch/`ROTATING` under one lock, then completes give-up with the existing reason `no healthy candidate left in pool` before unlocking. A current but displaced transition returns `TransitionNotOwned(actualState)` with no mutation; stopped and stale are their own results.

`prepareRotationCandidate` first preserves the pre-lock `Failover: rotating ${current.name} -> ${candidate.name}` diagnostic, which is historical attempt information, not a claim of ownership. Under the lock it checks running/epoch/`ROTATING`. It tears down a prior live/blackhole owner as required by the approved structural target, preserves an existing `UnreadRotationBridge`, and uses the existing role-based bridge predicates to establish containment when required. It must not use a generic owner-release helper on an already-held bridge. A newly established bridge yields `ESTABLISHED`; the same retained bridge yields `RETAINED`.

If required containment is missing, append `Failover: rotation bridge could not be established after teardown; aborting the uncovered rebuild into give-up`, then complete `giveUpRotationLocked(sessionEpoch, "rotation bridge could not be established")` under the same lock. Return `MissingBridgeAfterTeardown(disposition)` and end the invocation. The caller does **not** first write `CONNECTED` or enqueue a candidate retry; give-up itself may normalize `ROTATING` to mechanical `CONNECTED`, attempt blackhole containment, and schedule recovery. A missing-bridge abort and its eventual `UNPROTECTED` disposition are distinct facts.

Only after containment is present does preparation set `currentProfileId = candidate.id`, publish `CONNECTING`, update the switching notification, and return `Prepared(bridge)`. The returned result permits the next host call but is not durable authority; bring-up rechecks ownership. Unexpected preparation exceptions remain pre-commit failures and cancellation propagates.

### Candidate bring-up and establish-first handover

```kotlin
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

internal interface TunnelHost {
    fun bringUpRotationCandidate(
        sessionEpoch: Long,
        candidate: Profile,
    ): CandidateBringUpResult
}
```

`bringUpRotationCandidate` is the rotation-only host transaction. It does not replace the shared initial-start/revive `bringUpTunnel` contract: those callers retain a `None` owner precondition in `STARTING`/`REVIVING`, while this transaction requires `UnreadRotationBridge` in `ROTATING`. The adapter may share private Builder/config helpers, but it must not impose the rotation bridge precondition on initial start or revive. It remains non-suspending. It first checks running/epoch/`ROTATING` under the service lock and obtains the existing session-captured inputs for this call. No tuning, log settings, descriptor, or resource owner passes into the engine. It performs the existing config, geofile, split-plan, and Builder preparation off-lock. A non-cancellation failure in that region returns `PreparationFailed(cause)`; its failure fact does not assert that the invocation still owns the session when the result is consumed.

Before resource establishment it rechecks running/epoch/`ROTATING` under the lifecycle lock. A stopped, stale, or displaced call returns its ownership result without changing resources. The required structural owner is `UnreadRotationBridge`; absence or a different role is an invariant violation, not authorization to establish uncovered. The post-structural implementation must preserve that precondition.

Under the approved structural target, `UnreadRotationBridge` remains authoritative through `Builder.establish()`. A null descriptor returns `EstablishReturnedNull`; a non-cancellation throw returns `EstablishThrew(cause)`. Either leaves that owner and handle unchanged and emits no release audit, close, detach, or live owner. A non-null descriptor installs `LiveProxy` immediately, then attempts one close of the old bridge as a local cleanup handle; the bridge is never re-adopted, detached, or retried by raw fd. No diagnostic, I/O, or unrelated work intervenes between non-null establishment, installation, and that one close attempt.

The four handover diagnostics are isolated and best-effort in this order: `TUN established with fd=…`; optional `Failover: rotation bridge close warning: ${error.message}`; the exact release audit `Failover: rotation bridge released (handing over to the newly established tunnel)` when a prior bridge existed; and `Using geofiles from …`. A failure of one of those appends cannot enter the bring-up failure funnel or tear down/reclassify `LiveProxy`; it must continue the remaining handover diagnostics and then continue the operational protector registration, Xray start, and `Xray core started` steps. It must never return `Started` early merely because a protected diagnostic append threw. Those operational steps retain their existing failure behavior. Initial start and revive have no bridge and emit no release audit.

Operational post-establishment failures return `FailedAfterLiveInstallation` with the stage recorded at the actual `registerProtector`, `startXray`, or final `Xray core started` append boundary. Do not infer stages by parsing exception text or by inspecting the owner's later value. Stage tracking is invocation-local, not persistent state. Close failure and the four protected diagnostic failures produce no `Failure` result. An invariant violation outside the specified operational result boundaries escapes into the pre-commit exception funnel; do not manufacture an unrelated stage.

The diagnostic message for `StoppedSession`, `StaleSession`, and `TransitionNotOwned` is `VPN session is no longer active`; for `EstablishReturnedNull` it is `VpnService.establish() returned null`; for the three cause-bearing failure forms it is the nullable `cause.message` rendered exactly as the current Kotlin interpolation renders it. Do not re-log profile config or sensitive endpoint data to classify a result.

`Started` means the candidate bring-up finished but does not commit the rotation. Commit has a separate epoch/state transaction. Every `Failure`, including ownership rejection, enters the rollback/failure-tail path. `CancellationException` is rethrown before any broad failure conversion, including around the non-suspending bridge calls. Result types must never carry cancellation as an ordinary failure.

### Candidate rollback and retry boundary

```kotlin
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

internal interface TunnelHost {
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
}
```

`rollBackFailedCandidate` checks running/epoch/`ROTATING` under one lock. Rejection returns its distinct result without recording failure or touching an owner/profile/state. When it owns the transition it records `FailoverEpisodeDecision.recordBringUpFailure`, then inspects the authoritative structural owner, not the earlier failure result. An `UnreadRotationBridge` is retained unchanged (`RETAINED`); it must not be passed to generic teardown. A newly installed `LiveProxy` left by a failed operational step, including the final core-started append, is torn down and a bridge is re-established only as authorized (`ESTABLISHED`). Do not re-adopt the retired predecessor of that live candidate.

If the required bridge is missing, preserve the exact log `Failover: rotation bridge could not be re-established; aborting the uncovered rebuild into give-up`, then complete give-up with reason `rotation bridge could not be established` while still in the reserved transaction. Return `MissingBridgeAfterCandidateFailure(disposition)`; this site is separate from the preparation abort. Otherwise restore mechanical `CONNECTED` and `previousProfileId` from the reservation, returning `RolledBack(bridge)`. Give-up itself normalizes state on the abort, but the caller must not roll back to `CONNECTED` and enqueue an uncovered rebuild first.

After rollback, the engine invokes `reportCandidateFailureAndEnqueueRetry` outside that ownership guard. It appends the existing `Failover: ${candidateName} failed to come up: …` diagnostic, using the exact variant-to-diagnostic mapping above rather than a nonexistent `Failure.message` property: ownership rejections use `VPN session is no longer active`; `EstablishReturnedNull` uses `VpnService.establish() returned null`; and `PreparationFailed`, `EstablishThrew`, and `FailedAfterLiveInstallation` use their nullable `cause.message` rendered by current Kotlin interpolation. It then enqueues exactly one `RotationRequest.CandidateRetry` carrying the same epoch. The enqueue is intentionally unconditional even for an obsolete failure; the next admission rejects its stale epoch. This method may not mutate resources, episode fields, connection state, notifications, or active-profile persistence. It does not carry or spend the unprotected-recovery allowance. A missing-bridge abort suppresses this tail. `CancellationException` propagates.

### Rotation commit and post-commit boundary

```kotlin
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

internal interface TunnelHost {
    fun commitRotation(sessionEpoch: Long): RotationCommitResult
}
```

`commitRotation` checks running/epoch/`ROTATING` under one lock, writes mechanical `CONNECTED`, and preserves the current settle-up order: clear the give-up line, publish `CONNECTED`, clear give-up/retry markers, cancel the failover alert, refresh the ongoing notification, and observe—but do not consume—`pendingKillLabel`. This write is the rotation commit point. It does not clear `episodeFailedIds` or `rotationAttempts`; those retain their existing reset rules.

Each fallible effect after the commit write is independently isolated in place under that same lock, including clearing the paired line and publishing state. Bare marker assignments remain ordered between effects. A non-cancellation exception produces only best-effort deferred diagnostic reporting; a failure of that diagnostic must not escape or skip later settle-up steps. Cancellation propagates. The engine's pre-commit catch ends before the call, so even an unexpected host escape cannot reclassify a committed live tunnel through `failRotation`.

Post-commit effects have independent result types:

```kotlin
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

internal interface TunnelHost {
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
}
```

After `Committed`, the engine invokes active-profile persistence, switched-server notice, authoritative failover reconciliation, and—when observed pending—deferred-kill enqueue in order. Persistence and notice each validate running/epoch under the same lock as the effect. They do not require `CONNECTED`: a same-session pause after commit does not invalidate the committed profile. Persistence delegates to `ActiveProfileRepository.setActiveProfileId`; the notice uses the existing switched-server notification. On rejection neither operation writes current-session state or posts a notice. Stale notice suppression is a separate prerequisite listed below. The approved persistence signature carries only `profileId`, not the candidate name: rejection returns its typed result without the old name-bearing `superseded rotation is not recording ...` log. This is an explicit diagnostic-only difference, not permission to add a DAO read, mutable per-operation host cache, or new parameter to reconstruct that name.

`Applied` means that effect returned normally, not that all later effects ran. `Failed(cause)` can follow partial effects; it does not authorize compensation, a second attempt, or rollback. The adapter catches non-cancellation effect failures and emits the existing `Failover rotation: <step> failed after the transition committed: <message>` diagnostic best-effort, with exact step labels `recording the new active profile`, `posting the switched-server notice`, and `replaying the deferred kill`. A reporting failure is isolated too. `CancellationException` propagates and is never returned as `Failed`.

Replay enqueues the captured epoch without consuming or copying the label by delegating to the existing `replayDeferredKill(sessionEpoch)`, which queues `killTunnel(sessionEpoch, null)`. The label remains unread until that queued kill actually runs. It deliberately permits obsolete enqueue; the existing queued `killTunnel`/replay path rechecks the epoch and resolves/discharges the marker when it runs. `Enqueued` asserts only scheduling completed, not that a kill occurred; withdrawal by an earlier leave-foreground event remains possible. No second launch or marker cache is introduced.

`Authoritative monitor reconciliation` is the one post-commit call without a `Failed` return variant: it also serves the timer, where exceptions currently propagate. For the engine's rotation post-commit and timer paths, the host preserves the required context-neutral diagnostic by catching a non-cancellation reconciliation failure, appending `Failover: settings reconciliation failed: ${error.message}` best-effort, then rethrowing the original exception. The engine's **rotation-only** per-step guard catches it and proceeds to replay; the timer does not. A failure of the diagnostic append is discarded without replacing the original exception. Cancellation is rethrown before logging. This context-neutral text does not change the existing kill-switch revive path: revive retains its `Kill-switch revive: restarting the health monitor failed: ...` diagnostic unless a separate approved change alters it. The two diagnostic-only differences (this text for the specified engine/timer contexts and the omitted name-bearing stale-persistence log) are called out for written-spec review below, not presented as unchanged source behavior.

### Pre-commit failure funnel

```kotlin
internal sealed interface RotationFailureResult {
    data object StoppedSession : RotationFailureResult
    data object StaleSession : RotationFailureResult
    data class StoodDown(val actualState: SessionTunnelState) : RotationFailureResult
    data class GaveUp(val disposition: GiveUpDisposition) : RotationFailureResult
}

internal interface TunnelHost {
    fun failRotation(
        sessionEpoch: Long,
        logMessage: String,
    ): RotationFailureResult
}
```

`failRotation` checks only current running/session identity, not `ROTATING`, because an unexpected error can arrive after another lifecycle owner has taken over. A current callback appends the supplied diagnostic and enters give-up with exact reason `rotation error` under the same lock. Missing current profile uses `rotateTunnel: current profile <id> not found`; unexpected pre-commit exceptions use `rotateTunnel failed: <error.message>`. Candidate failures use the separate rollback/retry path. Post-commit work cannot reach this funnel. A stopped/stale callback returns before both the diagnostic append and give-up, matching the current `failRotation` epoch guard; only the separately specified candidate-failure tail is permitted to append an obsolete historical diagnostic and enqueue a stale retry. `CancellationException` never invokes it.

### Give-up settlement and re-arm ownership

```kotlin
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

// Private member of XrayVpnService; caller already holds its validated lock.
private fun giveUpRotationLocked(
    sessionEpoch: Long,
    reason: String,
): GiveUpSettlement
```

`giveUpRotationLocked` is a service-private semantic transaction, called only while the caller holds the already-validated lifecycle lock. It is not a `TunnelHost` member or a second public scheduling entry. It preserves the exact order:

1. Append `Failover: giving up ($reason)`. Capture `pendingKillLabel` into a local and clear the field; never enqueue it for later recovery. Normalize `ROTATING` to mechanical `CONNECTED`, clear `episodeFailedIds`, stop/forget the monitor, and reconcile the shared receiver.
2. If another lifecycle state owns the session, preserve `Failover: tunnel is $sessionTunnelState; leaving it to its owner`, release only a rotation bridge with reason `standing down to the $sessionTunnelState tunnel's owner`, schedule `RecheckMonitor`, announce any applicable dropped kill, and return `StoodDown(actualState)`. Do not install containment, overwrite that state's presentation, or write a new give-up outcome.
3. Otherwise call the existing role-based `containmentForGiveUp` and perform the chosen owner action. Retain `LiveProxy`; adopt the same `UnreadRotationBridge` handle as `UnreadBlackhole` without a close/re-establish; retain an existing `UnreadBlackhole`; from `None`, attempt fresh unread containment. Classify **after** the action through `classifyGiveUpOutcome(resultingRole)`. A not-yet-relabeled bridge defensively classifies as blackhole, but the normal adoption path must end labeled `UnreadBlackhole`.
4. If `shouldStopServiceOnGiveUp(outcome, unprotectedRetryConsumed)` is true, clear outcome/retry/timestamp markers, emit the existing in-app and error-notification surfaces, call synchronous `stopVpn(expectedSessionEpoch = sessionEpoch)`, then announce the dropped kill if applicable. Return `Completed(STOPPED_AFTER_UNPROTECTED_RETRY)` and schedule no timer. No contained outcome can take this stop branch.
5. Otherwise store `giveUpOutcome`, derive/store `giveUpLine = giveUpOngoingLine(outcome)`, publish the line before `connectionStateForGiveUp(outcome)`, and emit outcome-specific log and notifications. Both contained outcomes render `BLACKHOLED` but retain different line/notification truth; `UNPROTECTED` renders `ERROR` with the unprotected line, warning, and in-app error. Never borrow containment copy for `UNPROTECTED`.
6. Schedule recovery last, before announcing any dropped kill. Contained outcomes schedule `RecheckMonitor`; `UNPROTECTED` captures one current time, stores that exact `unprotectedEpisodeSinceMs`, and schedules `UnprotectedRecovery` with the same timestamp. Do not set `unprotectedRetryConsumed` here. Return `Completed` with the disposition in the table below only after all required non-best-effort settlement steps return.

| Resulting packet truth / stop decision | Settlement disposition | Scheduled request |
|---|---|---|
| Still-proxying live owner | `LIVE_TUNNEL_REARMED` | `RecheckMonitor` |
| Adopted, existing, or freshly established unread owner | `BLACKHOLE_REARMED` | `RecheckMonitor` |
| No containment, retry not consumed | `UNPROTECTED_RECOVERY_REARMED` | `UnprotectedRecovery` |
| No containment, retry already consumed | `STOPPED_AFTER_UNPROTECTED_RETRY` | None |

`REARMED` means a timer was scheduled, not that its future action is authorized; firing-time enablement/epoch checks still apply. `StoodDown` also implies its normal re-arm was scheduled, but represents a different lifecycle owner, not a containment disposition. `failRotation` maps both settlement forms; the other callers hold `CONNECTED`/`ROTATING` and map `Completed` only. `RefusedWithBridgeAdopted` implies `Completed(BLACKHOLE_REARMED)`. `STOPPED_AFTER_UNPROTECTED_RETRY` identifies the packet truth and settlement disposition, not which user-facing stop surface initiated it. Give-up's `Failover: recovery attempt also failed...` diagnostic and the re-arm timer's `Failover: no tunnel, and no chance to retry...; stopping the VPN` diagnostic remain owned by their separate host transactions; tests must not treat the disposition as collapsing those messages or call paths.

The dropped-kill helper stays service-private and delegates to `deferredKillNoticeLabel`, computing actual tunnel presence from the current structural owner. It runs after outcome publication/scheduling (or synchronous stop), so it cannot announce a tunnel that no longer exists. A logging, containment, or notification throw must not fabricate a successful settlement. This contract does not broaden best-effort behavior to the ordinary give-up path.

The service owns:

```kotlin
private fun scheduleFailoverRearmLocked(
    sessionEpoch: Long,
    request: FailoverRearmRequest,
)
```

It reads the authoritative rotation window, cancels/replaces the existing `failoverRearmJob`, and delays on `serviceScope`. It captures epoch/request only. Scheduling never spends the unprotected retry. The original `unprotectedSinceMs` survives every deferral. Normal contained recovery re-applies monitor settings; unprotected recovery uses the direct rotation path because no live tunnel exists for a valid watchdog probe.

### Timer admission and unprotected recovery

```kotlin
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

internal interface TunnelHost {
    fun admitFailoverRearm(sessionEpoch: Long): FailoverRearmAdmission

    fun handleUnprotectedRearm(
        sessionEpoch: Long,
        unprotectedSinceMs: Long,
    ): UnprotectedRearmResult
}
```

The engine entry is:

```kotlin
fun onRearmTimer(
    sessionEpoch: Long,
    request: FailoverRearmRequest,
)
```

The service timer invokes the engine directly on its existing `serviceScope` callback path after the existing delay. `admitFailoverRearm` checks running/epoch and authoritative enablement under one lock through `shouldFireFailoverRetry`. Rejection leaves all episode fields unchanged; admission resets only `rotationAttempts`, not the unprotected budget or original timestamp. After unlocking, rejection appends exactly `Failover: retry timer stood down (feature disabled or session ended)` and returns its result; that historical diagnostic may describe an old timer but publishes no state or notification.

Only `Admitted` dispatches: `RecheckMonitor` calls authoritative settings reconciliation with its own current-session check; `UnprotectedRecovery` passes the original timestamp to `handleUnprotectedRearm`. The admission result does not extend lock ownership or authorize a later effect indefinitely. No extra launch, callback-liveness check, or timer-generation field is introduced.

`handleUnprotectedRearm` acquires a separate lock, rejects stopped/stale callbacks, and evaluates the existing `unprotectedRetryAction(tunnelState, unprotectedSinceMs, now, rotationWindowMs)` with service-supplied current time and a fresh `authoritativeFailoverSettings().rotationWindowMs`. Keep the existing rule's enablement checks at timer admission and subsequent rotation reservation; do not add a new policy veto or resample the original timestamp.

- `ATTEMPT`: after unlocking, enqueue exactly one `RotationRequest.UnprotectedRecovery(unprotectedSinceMs)` on `tunnelOpScope`. Return `RotationEnqueued` when enqueue returns. Do not spend the retry; `beginRotation` still rechecks and consumes only upon successful reservation. A session/state change in this gap is handled by that admission, not by silently dropping the enqueue.
- `DEFER`: under the decision lock, append `Failover: recovery rotation cannot be attempted yet (tunnel is $sessionTunnelState); re-arming without spending the retry`, then call the private scheduling helper with the unchanged timestamp. Return `RecoveryDeferred`. Scheduling replaces/cancels the current timer job, but performs no suspension after replacement on that callback; the new timer is a child of `serviceScope`, not the cancelled job.
- `STOP_SERVICE`: after the decision lock, acquire the same lifecycle lock again for the effect-time running/epoch check **and** all stop diagnostics, error surfaces, and synchronous `stopVpn(expectedSessionEpoch = sessionEpoch)`. Return stopped/stale with no such effects if ownership changed. Otherwise append the existing `Failover: no tunnel, and no chance to retry for $UNPROTECTED_UNATTEMPTED_RETRY_WINDOWS rotation windows; stopping the VPN`, emit `vpn_failover_stopped_error` in-app and as the existing error notification, synchronously stop, and return `ServiceStopRequested`. Do not put an unlocked gap between this effect-time check and the warning/stop. This result describes the chosen action, not process destruction or an Android acknowledgement. The separate prerequisite below records this approved effect-boundary correction.

`CONNECTED` chooses `ATTEMPT` even beyond the deadline. Every other mechanical state chooses `DEFER` while `now - unprotectedSinceMs < rotationWindowMs * UNPROTECTED_UNATTEMPTED_RETRY_WINDOWS`, and `STOP_SERVICE` otherwise. The existing constant is `3`. Delay is read at scheduling time and the deadline window at decision time; the migration adds no fixed deadline snapshot, monotonic-clock replacement, overflow policy, or timing constant. Exceptions do not fabricate successful action results, and cancellation propagates. Every result ends this timer invocation. No engine clock or generic `scheduleRearm(delayMs)` is allowed.

### Healthy-probe recovery

```kotlin
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

internal interface TunnelHost {
    fun settleHealthyProbe(sessionEpoch: Long): HealthyProbeResult
}
```

`onHealthyProbe` runs directly on the monitor callback thread and returns `host.settleHealthyProbe(sessionEpoch)`. The host performs this exact locked ordering: (1) reject stopped/stale callbacks and callbacks outside mechanical `CONNECTED` before mutating episode state; (2) while current and `CONNECTED`, always clear failed IDs through `FailoverEpisodeDecision.clearOnHealthyProbe`; (3) if `giveUpOutcome == null`, return `EpisodeFailuresCleared` regardless of the owner role; (4) if a give-up marker exists and the owner is `None`, return `IgnoredWithoutTunnel` with those failed IDs already cleared and without publishing `CONNECTED`; (5) only for a live proxy or unread owner, append the existing recovery diagnostic, clear give-up/retry/timestamp markers, reset `rotationAttempts`, clear the paired give-up line, publish `CONNECTED`, refresh the connected notification, cancel the failover alert, and return `GiveUpCleared`. A clear-network probe can therefore never publish `CONNECTED` over no VPN. The role is read after the structural migration; a later owner change is rejected by the same lock. It does not rebuild the monitor or invoke `failRotation`. `TunnelHealthMonitor` retains its callback exception handling and terminal semantics; cancellation propagates.

### Settings, monitor, and shared screen receiver

```kotlin
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

internal interface TunnelHost {
    fun reconcileFailoverSettings(
        sessionEpoch: Long,
        settings: FailoverSettings,
    ): SettingsReconciliationResult

    fun reconcileAuthoritativeFailoverSettings(
        sessionEpoch: Long,
    ): SettingsReconciliationResult
}
```

The settings collector remains service-owned: it seeds `FailoverPreferences.load` on connect, observes `FailoverPreferences.state` on `serviceScope`, and invokes `onFailoverSettings` with its captured epoch. Post-commit and timer paths use `reconcileAuthoritativeFailoverSettings`, not a stale collector snapshot. Decisions continue to read `authoritativeFailoverSettings()` at their existing boundaries; the engine caches neither.

`reconcileFailoverSettings` is one locked transaction with running/epoch rejection before any mutation. It uses the supplied emission for reconciliation, not a replacement cache. Preserve this order:

1. If disabled, cancel/null `failoverRearmJob` and clear `rotationAttempts` unconditionally, even when no give-up exists. Stop/forget the failover monitor when present. Read the prior give-up outcome and use `shouldReleaseGiveUpOnDisable`: for either contained outcome, clear outcome/retry/timestamp markers, cancel the give-up alert, and emit the distinct blackhole-versus-degraded disable error. Do not tear down the owner, clear the recorded give-up line, or replace the existing user-visible state. In the ordinary contained state that means it remains `BLACKHOLED`; if kill-switch already moved the session to `PAUSED`, it stays `PAUSED`. For `UNPROTECTED`, retain its marker, line, warning, and consumed flag. Return the exact `DisableGiveUpEffect` reflecting this action, not a claim that packet containment was released.
2. Apply `shouldRestoreUnprotectedRearm` using the structural role and whether the service's re-arm job is active. If restoration is needed, keep `unprotectedEpisodeSinceMs`, or initialize/store one current timestamp only when it was absent, then schedule `UnprotectedRecovery` with that same value. Return `RESTORED` only when scheduling returns normally. This operation does not spend the retry or restart an existing deadline.
3. Use `shouldRunFailoverMonitor(enabled, running, tunnelState)` unchanged: enabled and mechanically `CONNECTED` are required. An ineligible path calls `stopFailoverMonitorLocked` and reconciles the shared receiver before returning `Disabled` or `NotMonitoring`.
4. For an eligible live monitor, compare its constructed-from `failoverMonitorSettings` with the supplied settings through `failoverMonitorNeedsRebuild`. Only interval, timeout, or threshold change triggers replacement; `maxRotations` and `rotationWindowMs` alone do not. An unchanged live monitor returns `Monitoring(UNCHANGED, restoration)` **without** stop, start, or receiver reconciliation; earlier disable/restoration effects are not retroactively skipped.
5. For start/rebuild, stop/forget a replaced monitor, set its constructed-from settings, construct the real `TunnelHealthMonitor` with `Http204HealthProbe(ConfigBuilder.HEALTH_PROBE_TARGET_URL, settings.probeTimeoutMs)`, `AndroidNetworkAvailability(applicationContext)`, interval, and threshold, and start it once. Preserve the fixed target and first-probe timing. Record the existing start/rebuild diagnostic, then reconcile the receiver.

The valid combinations are exhaustive:

| Reconciliation result | Allowed `MonitorChange` | Other constraints |
|---|---|---|
| `Disabled` | `ABSENT`, `STOPPED` | `None` when no release/retention applied; `UnprotectedRetained` only for that marker; `ContainedReleased.outcome` must be one of the two contained outcomes |
| `NotMonitoring` | `ABSENT`, `STOPPED` | Enabled, current session, but run gate false; restoration can be `NOT_NEEDED` or `RESTORED` |
| `Monitoring` | `UNCHANGED`, `STARTED`, `REBUILT` | Run gate true; restoration can be `NOT_NEEDED` or `RESTORED` |

`ABSENT` means no live monitor on entry and none on return; `STOPPED` means a held monitor was stopped/forgotten. `STARTED` means absent-to-started, `REBUILT` means replaced, and `UNCHANGED` means the existing monitor and polling cycle were left alone. Measure these relative to entry, not a second stop helper call later in the same transaction. Invalid enum/payload combinations must be rejected in tests; do not invent extra outcomes or silently reinterpret them. Construction, logging, and notification exceptions do not fabricate a completed reconciliation result.

The service supplies callbacks with named arguments and captured epoch:

```kotlin
monitor.start(
    onHealthy = { failoverEngine.onHealthyProbe(sessionEpoch) },
    onUnhealthy = { rotateTunnel(sessionEpoch, RotationRequest.UnhealthyProbe) },
)
```

The healthy callback invokes the passive engine directly; the unhealthy callback reaches the single service-owned rotation launcher. Do not add a launch inside either the engine or the monitor, swap callback meanings, or add a liveness gate that discards an already-earned callback. Each listener remains at-most-once per monitor `start`; unhealthy remains terminal before invocation.

The service-private resource helpers retain these exact signatures:

```kotlin
private fun stopFailoverMonitorLocked(): Unit
private fun reconcileScreenReceiverLocked(sessionEpoch: Long): Unit
private fun registerScreenReceiver(sessionEpoch: Long): Unit
private fun unregisterScreenReceiver(): Unit
```

Stopping uses `stop()`, not `pausePolling()`, and nulls both `failoverMonitor` and `failoverMonitorSettings`. The shared receiver remains registered while `shouldHoldScreenReceiver(killSwitchMonitor != null, failoverMonitor != null)` is true; it is unregistered only when neither field is live. Rotation admission stops the terminal monitor **without reconciling** during the transient gap; give-up, monitor-ineligible settings, and completed start/rebuild reconcile. Existing kill-switch callers keep their current ownership behavior and never go through an engine-owned receiver abstraction.

Screen-off/on retains the main-thread epoch hint followed by one existing `tunnelOpScope` launch; under its lock and current-session check, independently pause/resume both non-null monitors. A stale event either is filtered before enqueue or returns before effects under the lock. Screen events, kill-switch settings observation, and receiver registration are not engine entry points.

### Complete engine flow and cancellation

The implementation must use exhaustive handling of the result variants. The following is the exact control-flow contract, not a second implementation or a sketch with omitted exception guards.

#### `rotate`: pre-commit region

1. Call `beginRotation`. `StoppedSession`, `StaleSession`, `RefusedWithBridgeAdopted`, `RefusedAfterCleanup`, and `ThrashCapDenied` end this invocation. Only `Reserved` continues; keep its `currentProfileId` in an invocation-local value.
2. Call `loadRotationPool`. Stopped/stale ends the invocation. `MissingCurrentProfile` calls `failRotation` with its exact diagnostic and then ends the invocation. `Available` supplies the detached input to `FailoverDecision.nextCandidate`.
3. A null candidate calls `giveUpExhaustedPool`; every `ExhaustedPoolResult` ends this invocation. A candidate calls `prepareRotationCandidate`; stopped/stale/transition-not-owned and `MissingBridgeAfterTeardown` end it. Only `Prepared` continues.
4. Call `bringUpRotationCandidate`. Each `Failure` calls `rollBackFailedCandidate` with the candidate ID and **the profile ID captured by `Reserved`**, not a new service read. `MissingBridgeAfterCandidateFailure` ends the invocation with no failure tail. `RolledBack`, `StoppedSession`, `StaleSession`, and `TransitionNotOwned` each call `reportCandidateFailureAndEnqueueRetry`, then end the invocation.
5. Only `Started` exits this pre-commit region with invocation-local current/candidate profiles available for settlement. These locals are not persistent engine state or continuing ownership authority.

The generic pre-commit handler rethrows `CancellationException` first. It catches non-cancellation failures from `beginRotation` before any give-up arm, `loadRotationPool`, `FailoverDecision.nextCandidate`, `bringUpRotationCandidate` before rollback, `prepareRotationCandidate` before its missing-bridge give-up arm, `rollBackFailedCandidate` before its missing-bridge give-up arm, and the ordinary failure-tail call; for each it calls `failRotation(sessionEpoch, "rotateTunnel failed: ${error.message}")` once. A `GiveUpSettlementFailure` is handled by the settlement rule below, not this generic branch. `MissingCurrentProfile` calls `failRotation` with its distinct diagnostic outside that catch. A throw from either `failRotation` call propagates; it is never caught as a reason to invoke `failRotation` again.

For host calls with both ordinary and give-up branches, the adapter supplies an exact exception boundary without changing the 17-member `TunnelHost`: an unexpected non-cancellation exception **before** entering `giveUpRotationLocked` is rethrown unchanged and may enter the generic failure funnel; once the host invokes `giveUpRotationLocked`, any non-cancellation exception from that settlement is wrapped by the adapter in a private `GiveUpSettlementFailure(cause)` marker and rethrown. The marker is not a host result, engine field, or public contract type; it records only that give-up was entered, not whether settlement completed. The engine checks it before its generic catch: unwrap/rethrow the original cause to the existing service coroutine error boundary, never call `failRotation` again, and never fabricate a completed result. Cancellation is always propagated unchanged, without wrapping. This applies to `beginRotation`'s bridge-adoption and thrash-denial give-up, `giveUpExhaustedPool`, and the in-place missing-bridge give-up arms of `prepareRotationCandidate` and `rollBackFailedCandidate`. A throw before their give-up arm (including ordinary preparation or rollback) remains an ordinary pre-commit failure and calls `failRotation` once; the host wraps only throws from inside the actual `giveUpRotationLocked` invocation, so the engine never guesses from a bare throw. A typed `RefusedWithBridgeAdopted`, `ThrashCapDenied`, `GaveUp`, or missing-bridge result asserts completed settlement. This one-settlement marker is target hardening: current `rotateTunnel`'s broad outer catch is not evidence it already prevents a second give-up. Preserve one candidate per invocation and the obsolete historical failure tail for ordinary rollback results; only an in-place missing-bridge abort suppresses that tail.

#### `rotate`: commit and post-commit region

6. **Outside the pre-commit `try/catch`**, call `commitRotation`. Stopped/stale/transition-not-owned ends the invocation. Only `Committed` permits the following steps. The transaction contains no fallible operational work before its commit write other than obtaining the lock and checking identity/state; after the write, each fallible settle-up effect is isolated.
7. Invoke, in order: `recordCommittedActiveProfile`; `postCommittedSwitchNotice`; `reconcileAuthoritativeFailoverSettings`; and, if the commit observed `PENDING`, `enqueueDeferredKillReplay`. These are separate attempts, not one shared catch.
8. A returned `Failed`, a stopped/stale result, or a non-cancellation throw from one follow-up does not suppress a later follow-up. The engine defensively isolates an escaping non-cancellation exception per call as well as accepting the host's ordinary failure results. The host owns best-effort diagnostic reporting; the engine does not gain a logging/resource service. A host operation must guard its own diagnostic so that reporting failure cannot prevent subsequent operations. Fault-injection tests also require continuation when a fake deliberately violates that non-throw promise.
9. Cancellation at commit or any follow-up propagates and ends later work. It is never converted into give-up, a `Failed` result, or a retry. No post-commit path can reach `failRotation`.

A rejected commit does not request rollback or retry: a different lifecycle owner already controls the resources. The engine never compensates by closing an owner or changing the session.

#### Other entries

- `onRearmTimer` first calls `admitFailoverRearm`. Stopped/stale/disabled ends the callback. On `Admitted`, `RecheckMonitor` calls `reconcileAuthoritativeFailoverSettings`; `UnprotectedRecovery` calls `handleUnprotectedRearm` with the unchanged timestamp. Every returned result is terminal; neither enqueue nor re-arm is repeated by the engine. Exceptions retain the service timer's existing propagation; there is no rotation catch on this entry.
- `onHealthyProbe` returns `host.settleHealthyProbe(sessionEpoch)` directly. The existing monitor callback handles exceptions locally without terminating healthy polling; cancellation propagates.
- `onFailoverSettings` returns `host.reconcileFailoverSettings(sessionEpoch, settings)` directly. It adds no collector, launcher, cache, or blanket exception guard. The existing calling context owns exception handling.

### Execution and stale-epoch matrix

| Operation | Caller / execution context | Epoch and mutation rule |
|---|---|---|
| Rotation request | Service `rotateTunnel` launcher on `tunnelOpScope` | Exactly one launch per request; service maps unhealthy, candidate retry, and timer recovery to their distinct request variants |
| `beginRotation` | Rotation lane, one lock transaction | Running/epoch checked before cleanup, budget mutation, or policy-driven give-up |
| `loadRotationPool` | Rotation lane; DAO/resolver may suspend off-lock | Locked running/epoch check before returning or reading episode IDs; no `ROTATING` requirement |
| Exhaust / prepare / rollback / commit | Rotation lane, separate lock transactions | Running/epoch/`ROTATING` required for mutation; admission or lookup results are not authority |
| Bring-up | Non-suspending rotation-lane call | Locked identity/state checks before preparation and immediately before resource establishment; off-lock work does not grant resource authority |
| Candidate-failure diagnostic and enqueue | Rotation lane, outside rollback guard | Obsolete epoch permitted for historical log plus tagged enqueue only; no current-session resource, state, or notification writes |
| `failRotation` | Rotation lane, one lock transaction | Running/epoch required; no `ROTATING` requirement; same lock covers log and give-up |
| Post-commit persistence / switch notice | Rotation lane, after commit lock was released | Each checks running/epoch with its effect under the same lock; does not require `CONNECTED` |
| Deferred-kill enqueue | Rotation lane, post-commit | Captured epoch enqueued even if obsolete; queued kill checks it before consuming the marker or mutating resources |
| Give-up and re-arm scheduling helpers | Existing validated service-lock holder | Private, non-suspending; never callable by an unvalidated engine callback |
| Re-arm timer | `serviceScope`, after existing `delay` | Calls engine directly; separate admission and action locks remain; stop error surfaces recheck epoch atomically with the effect |
| Healthy probe | `TunnelHealthMonitor` callback thread | Calls engine directly; running/epoch/`CONNECTED` lock check before mutation |
| Settings seed / collector | Existing startup thread / `serviceScope` collector | Calls engine directly with captured epoch; entire reconciliation under one lock |
| Screen receiver | Main thread hint, then existing `tunnelOpScope` launch | `activeEpochVolatile` prefilter remains non-authoritative; authoritative check under lock before pausing/resuming both monitors |
| Full stop / revoke / destroy | Existing service entry paths | Not moved into engine; synchronous teardown and scope cancellation remain service-owned |

`limitedParallelism(1)` is not a mutex across suspension. A pool lookup can suspend while another lifecycle operation runs. In contrast, making bring-up suspending would alter the current deferred-kill FIFO assumptions; `bringUpRotationCandidate` must remain non-suspending.

### Full `TunnelHost` member ledger

The implementation must realize one interface containing these members, not multiple interfaces or private-method mirrors:

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

The service adapter may use private locked helpers and existing pure decisions internally, but the engine sees only this semantic surface. No member may accept `ParcelFileDescriptor`, `VpnService.Builder`, `TunnelInterfaceOwner`, `Context`, `ProfileDao`, or mutable service internals.

## Verification Contract and Evidence Limits

### Engine ordering: `FailoverEngineTest`

The engine test is `FailoverEngineTest` in `app/src/test/java/com/justme/xtls_core_proxy/vpn/FailoverEngineTest.kt`, with a test-only `RecordingTunnelHost` in that same production `vpn` package. It is not required to share the package of existing failover-decision tests. The fake implements the exact 17-member interface, supplies scripted result/exception queues, and records method name, epoch, and immutable arguments. Its simulated episode/owner facts belong to the fixture, never to engine fields. Tests explicitly distinguish production facts from fixture assumptions.

Required engine-level assertions:

- Each `RotationStartResult` ends or continues exactly as specified. All four refusal-cleanup results, bridge-adopted refusal, and all thrash-cap dispositions cause no duplicate give-up, re-arm, lookup, or retry from the engine.
- Pool stopped/stale yields no downstream host calls; missing profile calls the exact failure funnel; an empty or fully excluded pool calls exhaustion, not preparation. Exercise the real `FailoverDecision.nextCandidate` with deterministic profile list order and failed IDs. The host—not this fake test—is responsible for actual resolver use.
- Preparation rejection and `MissingBridgeAfterTeardown` prevent bring-up. `Prepared(RETAINED)` and `Prepared(ESTABLISHED)` each permit one non-suspending bring-up call.
- Every candidate `Failure` invokes rollback with the reservation's original profile ID. Each rollback stopped/stale/transition-not-owned result still reaches the diagnostic/retry tail; the distinct `MissingBridgeAfterCandidateFailure` result never does. Returned give-up dispositions do not provoke a second engine settlement.
- Give-up-triggering transactions have an explicit one-settlement boundary: a typed completed settlement ends the invocation, while a post-entry non-cancellation throw propagates without a second generic `failRotation`; ordinary pre-boundary throws alone may use the generic funnel.
- Only `Started` calls commit. Commit rejection triggers neither retry nor post-commit work. `Committed` runs persistence, notice, authoritative reconciliation, and optional replay in order; `NOT_PENDING` omits only replay.
- Inject both returned `Failed` results and non-cancellation throws into individual post-commit operations. Verify subsequent operations still run and `failRotation` never does. Inject cancellation instead and verify propagation with no later calls. Separately inject an unexpected commit throw and verify it cannot enter the pre-commit funnel; do not interpret a fake's invalid throw as proof of real atomic settlement.
- Inject ordinary pre-commit throws from pool lookup/selection, bring-up before rollback, and the ordinary failure tail; verify each reaches the generic failure diagnostic exactly once. Inject a `GiveUpSettlementFailure` from each give-up-triggering host transaction and verify the original cause propagates without a second `failRotation`, retry, or fabricated result. Inject cancellation at both boundaries and verify unchanged propagation. Missing-profile failure stays distinct.
- Re-arm stopped/stale/disabled admission has no second host call. `RecheckMonitor` calls only authoritative reconciliation; `UnprotectedRecovery` calls only its action transaction with the exact original timestamp. Each action result terminates the callback without another enqueue, delay, or reset.
- Healthy entry delegates once and returns every `HealthyProbeResult` unchanged. Settings entry delegates the supplied epoch and emitted tuple once and returns every valid reconciliation result unchanged. Their exceptions follow the existing caller path; neither calls `failRotation`.
- Reuse one engine for multiple fixture epochs; script newer-session state between host calls and verify the engine carries the original request epoch, never substitutes the new epoch, and obeys rejection. The intentionally obsolete failure-tail and deferred-kill enqueues must remain present. This tests dispatch correctness, not the real host's guard implementation.

### Production host and existing components

A fake returning `StoppedSession`, `StaleSession`, `BLACKHOLE_REARMED`, or `REBUILT` **does not prove** the production service checked an epoch, preserved a descriptor, classified containment, or rebuilt a monitor. The approved contract intentionally keeps these actions inside atomic service transactions. Their evidence must come from existing production-used pure seams, focused component tests, service/instrumentation checks where feasible, and human review of the actual service adapter. Do not create a second Kotlin simulator of service internals and report its tests as service coverage.

Required service/component verification:

- Confirm `loadRotationPool` actually delegates to the unchanged resolver and uses `db.Profile`; test detached snapshots and current-epoch admission at the real boundary where possible.
- Reuse `SessionLifecycleDecisionTest` and `SessionLifecycleRotationTest` for policy and the structural `TunnelOwnerTransitions` tests for role/handle transitions. They do not independently prove service call order.
- Verify establish-first null/throw retains the same bridge; successful installation precedes one bounded close; warning/diagnostic failures do not become operational failures; protector/Xray/final-start-log failure goes through partial-live rollback. These are service/structural checks, not engine fake assertions about `Started`.
- Verify atomic `giveUpRotationLocked` maps adopted/existing/fresh blackhole to the right packet truth, never mixes live/blackhole/unprotected copy, and schedules/discharges in order. `BLACKHOLE_REARMED` intentionally does not encode which of those three blackhole sources was used; inspect the production owner seam for that distinction.
- Verify attempt consumption before thrash admission, no consumption at schedule/refusal/defer, original timestamp across deferrals and re-enable, three-window decision using the existing constant, and stale stop rejection before error publication.
- Verify healthy callbacks clear failed IDs even without a marker; no-owner success does not publish recovery; unread-owner acceptance is the existing policy, not proof that an unread descriptor currently forwards packets. The callback can race an owner transition, and this design adds no per-probe owner generation.
- Verify monitor `stop()` versus `pausePolling()`, terminal unhealthy/one-shot healthy behavior, delayed first probe, timing-only rebuild, and unchanged emission's no-op. The screen receiver's OR-ownership, main-thread hint, locked epoch check, and both-monitor pause/resume require service/device evidence. None is visible through a fake `Monitoring(UNCHANGED, ...)` alone.

### Device and packet-policy gates

Each VPN phase needs human review plus device QA of its changed paths. Include routine switch and repeated failed candidates, all-dead pool, both missing-bridge aborts, all three containment outcomes, pause/kill/leave-foreground interleavings, explicit Stop and stop/start, settings disable/re-enable, timer attempt/defer/stop, healthy recovery, monitor rebuild/no-op, and every failover/kill-switch enabled pairing for screen off/on. Use existing `docs/qa/auto-failover-qa.md` and `docs/features/auto-failover.md` as the behavior reference, correcting source/target labels rather than inventing test IDs here.

Rotation packet capture must span the entire switch, including replacement establishment and prior-bridge cleanup. No tester-chosen plain-HTTP destination traffic may escape on the underlying interface; proxy-server endpoint traffic is expected and excluded from that verdict. Logs check diagnostic order only. Fault injection must exercise null/throw, old-bridge close warning, isolated append failure, protector/start failure, and missing containment when supported; if a device scenario cannot be driven, record the missing evidence and keep its acceptance gate open. Never claim fake-host green tests replace it.

- Run heavyweight Gradle/build operations serially during later execution using the named project tasks: `./gradlew.bat :app:testDebugUnitTest`, `./gradlew.bat :app:lintDebug`, `./gradlew.bat :app:connectedDebugAndroidTest` where device evidence is available, `./gradlew.bat :app:check`, and the required `./gradlew.bat :app:assembleRelease`. Retain app data for connected tests; do not opt into uninstall or clear data on a data-bearing install. The full-feature finish requires release build and release-device exercise of affected runtime paths. No build, test, or QA execution is authorized by this document-writing task.

## Migration Phases and Gates

### Phase 0 — prerequisite and seam gate

Before any engine plan is **executed**, the structural-foundations index and all three executable structural plans must complete and pass their stop/go gates:

- `docs/superpowers/plans/2026-09-03-auto-failover-structural-foundations-plan.md` (index and execution prohibition);
- `docs/superpowers/plans/2026-09-03-auto-failover-structural-foundations-owner-plan.md` (sealed owner);
- `docs/superpowers/plans/2026-09-03-auto-failover-structural-foundations-capture-plan.md` (shared capture plan/applicator);
- `docs/superpowers/plans/2026-09-03-auto-failover-structural-foundations-verification-plan.md` (verification and packet evidence).

Confirm no `tunInterface`, `tunInterfaceKind`, or `rotationBridgeInterface` shadow fields remain, and confirm establish-first packet capture across the full switch. Stop execution if the structural target is absent or production remains release-first. This execution prerequisite is distinct from the written-spec gate: the four engine phase plans may be authored only after explicit approval of this written spec and synchronization of the engine index/recovery plan, but authoring those plans does not execute Phase 0 or authorize engine code.

### Phase 1 — rotation

Modify only the rotation engine/service adapter, exact contract types, and focused tests. Preserve service-owned episode fields and pool resolver. Gate on focused JVM tests, full debug unit tests, lint, human VPN-code review, and device packet capture for routine switch, null/throw establish, bridge-missing aborts, candidate rollback, stale callback, and deferred-kill FIFO. Stop on any mismatch in the result ledger or any packet escape.

### Phase 2 — give-up and re-arm

Move only give-up/re-arm orchestration through the approved seam. Keep containment, timer, synchronous stop, and notification effects in the service host. Gate on all three containment outcomes, retry budget/timestamp, disable/re-enable, pause/defer/stop, and no clear-network false recovery. Stop if any result collapses `UNPROTECTED` with containment.

### Phase 3 — monitor/settings

Move settings and monitor reconciliation through the approved seam. Keep collector, monitor, receiver, and Android network/probe construction in service. Gate on monitor no-op/rebuild/stop, one-shot callbacks, screen pause/resume, stale settings emission, and kill-switch ownership. Stop if receiver ownership or terminal monitor semantics change.

### Phase 4 — cross-phase verification

Run focused and complete JVM tests, instrumentation where available, lint, debug verification, and the required release build for the full feature. Update `docs/features/auto-failover.md`, `AGENTS-DEVELOPMENT.md`, and `docs/qa/auto-failover-qa.md`. Execute the mandatory device fail-closed matrix and underlying-interface packet capture across the entire rotation. Do not claim completion if release or device verification is unavailable; report the exact skipped gate.

Each VPN phase has its own human review and signed commit boundary if commits are later requested. This design task creates no commits.

## Separate Correctness Findings and Stop/Go Prerequisites

These are source-level findings, not results from a device reproduction. No correction has been implemented or run in this task. Preserve the distinction between a demonstrated unguarded effect in source and the frequency/reachability of its device interleaving. The engine's required safe boundary is not permission to implement a correction without its own review.

1. **Stale reservation bridge cleanup (rotation prerequisite).** Production `rotateTunnel` calls `releaseRotationBridgeLocked("rotation reservation refused")` at `XrayVpnService.kt:1134` without first rejecting an obsolete epoch, and the helper at `:2040` closes the currently held bridge. If an obsolete request executes while a newer session holds a bridge, it can release the newer session's containment. The structural role-specific release helper alone does not make this epoch-safe. Resolve and verify the epoch guard separately before running the engine rotation phase.
2. **Stale switched-server notice (rotation prerequisite).** The post-commit notice at `XrayVpnService.kt:1398-1400` lacks the current-session check that already protects adjacent active-profile persistence. Define and verify its atomic check/effect boundary separately before claiming post-commit effects are safe for replaced sessions. A `try/catch` protects against throwing, not supersession.
3. **Unprotected timeout error publication (give-up/re-arm prerequisite, approved refinement in conversational Section 15).** At `XrayVpnService.kt:1798-1843`, the action is decided under the lock, but the timeout log/error surfaces are emitted after unlocking. Only the subsequent `stopVpn(expectedSessionEpoch)` is guarded. The approved contract requires an effect-time running/epoch check around both the error surfaces and stop. This is a distinct correctness correction, not evidence that current production already meets that contract. Separately review/verify it before the give-up/re-arm phase; do not conceal it as extraction.

Accepted behavior is not to be fixed in this migration: the consumed retry flag can survive a contained retry and make a later `UNPROTECTED` stop without a new allowance; a healthy recovery can reset the thrash window; disabling during a kill-switch pause can emit the existing Reconnect wording while leaving `PAUSED`; and the containment rule's non-`CONNECTED` arm is a deliberate backstop even though this caller already stood down. Test gaps are evidence limits, not authorization to change those policies.

### Written-review disclosure: diagnostic-only details

The two diagnostic-only differences (the context-neutral reconciliation text for the specified engine/timer contexts and the omitted name-bearing stale-persistence log) were explicit written-review disclosures and are approved for planning with this written spec on 2026-09-23; they are not claims about current source behavior. The approved signatures intentionally omit a candidate name from `recordCommittedActiveProfile` and a caller-context argument from shared authoritative settings reconciliation; the contract does not add a DAO read, mutable per-operation host cache, new parameter, or engine logger to reconstruct them. A later request for byte-for-byte diagnostic preservation returns to the spec/recovery gate rather than changing these signatures in a phase plan. The commit-settlement hardening and separately listed source prerequisites remain review/verification gates, not claims that current production already satisfies them.

The reviewed post-`CONNECTED` settlement hardening is a separate source-correction gate, not a claim about current behavior. Current `clearGiveUpLineLocked` can throw before later settle-up effects, and the existing error-report append can also throw; the target contract requires each fallible effect and its diagnostic reporting to be independently isolated and to continue later effects. That correction must be separately reviewed and verified in the host before claiming behavior parity; it is not silently folded into extraction. The engine's pre-commit catch boundary and the reviewed post-commit isolation contract must be verified against the real host, not inferred from a scripted fake.

## Acceptance Criteria

- This spec defines one complete, exact passive engine and semantic host contract with all result types above.
- Every required branch is represented distinctly: reservation exits, stale/stopped, thrash denial, missing profile, candidate selection, both bridge aborts, establish outcomes, guarded rollback/unconditional enqueue, pre/post-commit, all give-up outcomes, re-arm actions, recovery clearing, monitor changes, and shared receiver ownership.
- Episode fields, Android resources, locks, epochs, timer/collector/receiver scheduling, and pure policies remain owned by their current components.
- The approved establish-first structural target is a prerequisite and is not replaced by today's release-first code.
- Fake-host tests cover engine ordering and stale-result handling; service tests/device QA/packet capture cover inside-transaction and packet-policy facts.
- Rotation, give-up/re-arm, monitor/settings, and verification are separate implementation phases with stop/go gates.
- No incomplete sections, invented source paths, or generic copy-forward instructions remain in the written spec.
- The implementation plans are not authorized until the maintainer approves this written spec.

## Change Control

### 2026-09-23 — Written-spec approval for planning

- **Approval:** The maintainer explicitly approved the amended written spec and requested `superpowers:writing-plans` in this conversation.
- **Authorized next step:** Synchronize the engine index and contract-recovery plan, then author the four phase-sized implementation/verification plans from this exact contract, including the disclosed diagnostic-only differences.
- **Unchanged boundaries:** No signature, result, policy, owner/capture rule, or establish-first handover changes. Structural execution and separately reviewed source-correction gates remain prerequisites; no production implementation, test execution, device QA, commit, push, or external-configuration change is authorized by this approval.
- **Affected sections:** Status, written-review disclosure approval status, and the planning handoff. Historical entries below remain intact.

### 2026-09-23 — Complete engine contract after recovery gate

- **Previous decision:** The checked-in engine design stopped at line 59 after the opening sentence of `Current Sequencing and Ownership`; its header said design approved, but it contained no complete `TunnelHost` contract, result semantics, epoch rules, phase boundaries, fake-host surface, or acceptance criteria. The recovery plan correctly prohibited deriving implementation APIs from that truncated body.
- **Replacement decision:** Add the complete passive `FailoverEngine`/semantic `TunnelHost` contract above. Use explicit `sessionEpoch` on every entry, semantic epoch-checked host transactions, host-enqueued one-candidate retries, service-owned episode/timer/collector/receiver/resource state, existing pure decision components, establish-first structural handover, and branch-specific result types. Make rotation, give-up/re-arm, monitor/settings, and cross-phase verification separate implementation plans.
- **Reason:** Source inventory and contract recovery showed that narrow private-method mirrors would not preserve atomic lock/epoch/descriptor ownership, that generic failure/Boolean results would collapse required fail-closed branches, and that the approved structural target supersedes current release-first production. The contract must make stale callbacks and post-commit boundaries explicit without authorizing production fixes to separately identified defects.
- **Affected sections:** Summary, problem statement, scope, current ownership, structural prerequisite, complete host/engine contract, branch semantics, fake-host tests, migration phases, acceptance criteria, and separate correctness findings.