# Auto-Failover Engine Migration: Contract Recovery Plan

> **DOCUMENTATION-ONLY APPROVAL RECORD — do not execute implementation from this plan.** The maintainer approved the amended written engine spec on 2026-09-23 and requested plan authoring only. This plan and the engine index are synchronized to that contract. The four phase plans may be authored and reviewed, not executed; production code, tests, builds, device QA, and commits remain unauthorized. Executing any resulting engine phase later requires separate authorization plus completion of the structural-foundations index, owner, capture, and verification stop/go gates and the separately reviewed correctness prerequisites. Checkboxes below record planning verification, not implementation progress.

**Goal:** Preserve the source inventory and verify the exact approved 2026-09-23 draft `TunnelHost`/`FailoverEngine` contract as the sole input to four phase-sized implementation plans. This plan neither invents the contract nor executes it.

**Architecture:** The engine design contains the complete written contract approved by the maintainer in this conversation on 2026-09-23 for planning only. Task 5 Step 6 synchronizes this recovery plan and the engine index: status, phase paths, exact 17-member host/four-entry engine seam, result ledger, and structural execution gates are recorded without erasing the operative inventory or historical change control. Plan authoring is now allowed; implementation remains blocked.

**Tech Stack:** Kotlin source inspection, existing JVM decision tests, Android service lifecycle, Markdown design/change control

**Spec:** `docs/superpowers/specs/2026-09-04-auto-failover-engine-migration-design.md`

**Historical reference:** `docs/superpowers/specs/2026-07-27-auto-failover-core-design.md` is an earlier approved design for the original feature. It is not the engine contract: it predates the sealed owner, unread blackhole containment, `BLACKHOLED`, authoritative settings, unprotected retry, and the current terminal health-monitor behavior.

## Global Constraints

- Do not create or modify production `TunnelHost` or `FailoverEngine` code during contract recovery.
- Do not treat the previous incomplete plan's invented interface as an approved design.
- Preserve service ownership of Android resources, locks, session epochs, descriptor ownership, notifications, and serialized `tunnelOpScope` scheduling.
- Preserve service ownership of episode bookkeeping: rotation timestamps, failed-profile IDs, give-up/retry markers, unprotected episode timing, and deferred-kill markers.
- Preserve pure-policy ownership in top-level `SessionLifecycleDecision`, `FailoverDecision`, `FailoverEpisodeDecision`, and `FailoverPoolResolver`.
- The eventual engine must not receive `ParcelFileDescriptor`, `VpnService.Builder`, `TunnelInterfaceOwner`, Android `Context`, a Room DAO, or mutable service internals.
- The eventual engine must have no coroutine scope, dispatcher, timer, or internal session state.
- Every eventual host transaction must define stale-epoch behavior and must not mutate a newer session.
- Contract changes require an additive dated change-control entry in the engine spec.
- This recovery plan records written-spec approval and ends at plan derivation; it does not authorize implementation by itself.

## Files

**Read:**
- `docs/superpowers/specs/2026-09-04-auto-failover-engine-migration-design.md`
- `docs/superpowers/specs/2026-07-27-auto-failover-core-design.md`
- `docs/features/auto-failover.md`
- `AGENTS-DEVELOPMENT.md`
- `app/src/main/java/com/justme/xtls_core_proxy/vpn/XrayVpnService.kt`
- `app/src/main/java/com/justme/xtls_core_proxy/vpn/SessionLifecycleDecision.kt`
- `app/src/main/java/com/justme/xtls_core_proxy/failover/FailoverDecision.kt` (`FailoverDecision` and the separate top-level `FailoverEpisodeDecision` object at lines 48-59: `recordProbeFailedCurrent`, `recordBringUpFailure`, `clearOnHealthyProbe`)
- `app/src/main/java/com/justme/xtls_core_proxy/failover/TunnelHealthMonitor.kt`
- `app/src/main/java/com/justme/xtls_core_proxy/failover/FailoverPoolResolver.kt`

**Approved contract and synchronized plans — read only during plan derivation:**
- `docs/superpowers/specs/2026-09-04-auto-failover-engine-migration-design.md`
- `docs/superpowers/plans/2026-09-04-auto-failover-engine-migration-plan.md`
- this contract-recovery plan

The source-inventory steps below remain as historical/verification references; they do not authorize code changes.

**Create as separate planning deliverables after synchronization, never by executing this recovery inventory:**
- `docs/superpowers/plans/2026-09-04-auto-failover-engine-migration-rotation-plan.md`
- `docs/superpowers/plans/2026-09-04-auto-failover-engine-migration-giveup-rearm-plan.md`
- `docs/superpowers/plans/2026-09-04-auto-failover-engine-migration-monitor-plan.md`
- `docs/superpowers/plans/2026-09-04-auto-failover-engine-migration-verification-plan.md`

---

### Task 1: Verify the complete draft and approval boundary

**Files:**
- Read: `docs/superpowers/specs/2026-09-04-auto-failover-engine-migration-design.md`
- Read: `docs/superpowers/specs/2026-09-03-auto-failover-structural-foundations-design.md`
- Search: repository history and tracked files

**Interfaces:**
- Consumes: the complete 2026-09-23 draft, approved structural target, and repository history
- Produces: verification that the 2026-09-23 contract was complete as a draft before approval; see the approval record above and the new dated change-control entry for its present approved status

- [ ] **Step 1: Confirm the current draft boundary**

Run:

```powershell
(Get-Content docs/superpowers/specs/2026-09-04-auto-failover-engine-migration-design.md).Count
Get-Content docs/superpowers/specs/2026-09-04-auto-failover-engine-migration-design.md -Tail 12
```

Expected historical verification: the file contains the complete contract, phase gates, verification limits, separate correctness findings, and a 2026-09-23 contract change-control entry. At the time of this inventory it awaited review; the 2026-09-23 written approval recorded above now permits phase-plan authoring only.

- [ ] **Step 2: Search all artifacts for competing contracts**

Run:

```powershell
rg -n --hidden --glob '!**/.git/**' "TunnelHost|FailoverEngine|Current Sequencing and Ownership" .
git log --all --oneline -- docs/superpowers/specs/2026-09-04-auto-failover-engine-migration-design.md
```

Classify each match as the complete draft, the synchronized index/recovery inventory, historical deferred prose, the discarded incomplete plan, or an actual competing contract. The 2026-07 spec remains historical feature design, not a substitute.

- [ ] **Step 3: Preserve the approval gate**

Written approval is recorded above and in the engine spec. This recovery plan and index are synchronized to the exact approved seam and result ledger. The four phase plans may be derived from them, but must not be executed merely because planning is permitted. Structural-foundations execution is a prerequisite to executing engine plans, recorded in Phase 0; it does not authorize engine implementation or inventing APIs.

---

### Task 2: Inventory the service-owned sequencing

**Files:**
- Read/search: `app/src/main/java/com/justme/xtls_core_proxy/vpn/XrayVpnService.kt`

**Interfaces:**
- Consumes: the inspected current `XrayVpnService` source as it exists today, plus the approved structural-foundations target and the engine spec as **target references**. Structural foundations are not implemented in the inspected source, and this inventory does not require, assume, or perform execution of the structural-foundations plans. Where current source and the approved target differ, record both and label which is which. Do not invent, assume, or describe source that would exist only after the structural plans execute.
- Produces: an exact sequencing inventory that separates current-source behavior from the approved target contract, retained as a reference for phase-plan derivation.

- [ ] **Step 1: Inventory rotation admission and reservation**

Document the current order and lock boundary around:

- `rotateTunnel(sessionEpoch, unprotectedRecoverySinceMs)`;
- `canReserveRotationFromAuthoritativeState`;
- `shouldFunnelRotationReservationRefusal`;
- `FailoverDecision.admitRotation`;
- `unprotectedRetryConsumed` mutation;
- `sessionTunnelState = ROTATING`;
- `episodeFailedIds = recordProbeFailedCurrent(...)`;
- `stopFailoverMonitorLocked()` before off-lock work.

Rotation admission has two distinct exits when `canReserveRotationFromAuthoritativeState` refuses, followed by a separate path when reservation succeeds. Do not collapse them into one "reservation refusal":

1. `shouldFunnelRotationReservationRefusal(...) == true` — current session, `CONNECTED`, no live TUN, unread bridge held. Call `giveUpRotationLocked` while retaining/adopting that bridge. This block does not re-arm; it returns immediately. Give-up itself may schedule re-arm later. The future contract rejects stopped/stale callbacks before any cleanup; the current unguarded release at `XrayVpnService.kt:1134` remains a separately disclosed correction prerequisite, not a behavior to preserve.
2. Refusal without that funnel — after the current-session/epoch check, attempt bridge-only cleanup, then re-arm only when `unprotectedRecoverySinceMs != null`, `isCurrentSessionLocked(sessionEpoch)`, and `authoritativeFailoverSettings().enabled`. The re-arm passes `retryByRotation = true` and the original `unprotectedRecoverySinceMs`, and does not spend `unprotectedRetryConsumed`. A stale or stopped callback returns before cleanup or re-arming in the target contract.
3. Only after reservation succeeds — if `unprotectedRecoverySinceMs != null`, mark `unprotectedRetryConsumed = true`, then run `FailoverDecision.admitRotation`. `RotationAdmission.Denied` is a separate thrash-cap give-up (`giveUpRotationLocked(sessionEpoch, "thrash cap reached")`), not a reservation refusal. The budget is marked before admission because that denied path must see it as spent.

The contract must also distinguish all of those exits from a candidate bring-up failure. They cannot share one result merely because some of them eventually reach give-up.

- [ ] **Step 2: Inventory candidate selection and pool resolution**

Document that current code performs these operations off the lifecycle lock:

```kotlin
val dao = AppDatabase.get(this@XrayVpnService).profileDao()
val current = dao.getById(session.profileId)
val pool = FailoverPoolResolver.resolve(dao, current)
val failed = synchronized(lock) { episodeFailedIds }
val next = FailoverDecision.nextCandidate(pool, current.id, failed)
```

The contract must preserve:

- `FailoverPoolResolver.resolve(dao, current: Profile)` as the sole pool seam;
- `FailoverDecision.nextCandidate` list order and failed-ID filtering;
- current-profile disappearance as a distinct failure path;
- epoch/state re-check before give-up or mutation after off-lock reads.

- [ ] **Step 3: Inventory bridge and handover sequencing**

Document the exact rotation order:

1. under lock, tear down the live owner;
2. establish or retain exactly one unread rotation bridge;
3. if the required bridge is missing, abort in place while still `ROTATING` (the post-teardown site below, lines 1248-1258) — do not return to `CONNECTED` and do not recurse;
4. update the candidate/profile presentation state;
5. perform off-lock `bringUpTunnel` work;
6. keep the unread rotation bridge authoritative while calling `Builder.establish()` — do not release it first (current production at `XrayVpnService.kt` still releases immediately before `establish()`; that baseline is superseded by the approved establish-first target and must not be inventoried as the contract);
7. on `null` or throw, leave the bridge unchanged: no release audit, no close/detach, and no live owner, so the existing retry/give-up path still has that bridge;
8. on a non-null candidate, install the live owner immediately, then close the old bridge once as a bounded local cleanup handle — never re-adopt it, detach it, or retry its raw fd; close failure is non-fatal deferred diagnostic state, and the existing close warning plus `Failover: rotation bridge released (handing over to the newly established tunnel)` are appended only after `TUN established with fd=…`;
9. on candidate failure, the rollback, failure recording, teardown, and bridge re-establishment are guarded by `ownsTunnelTransitionLocked(session.epoch, SessionTunnelState.ROTATING)`. The failure log and the recursive `rotateTunnel(session.epoch)` enqueue that follow `onFailure` are unconditional. A superseded failure's enqueue is intentionally refused later by reservation, not by skipping the enqueue.

Two missing-bridge aborts are distinct and both abort in place while still `ROTATING`. Neither returns to `CONNECTED` nor recurses:

- post-teardown abort in `XrayVpnService.kt` lines 1248-1258, after the live owner is torn down and `shouldEstablishRotationBridge` / `establishRotationBridgeLocked` fail to leave a bridge;
- post-failure re-establish abort at lines 1458-1471, after a failed bring-up tears down a partial owner and cannot re-establish the bridge for the next candidate.

Only a failure that still owns `ROTATING` records `recordBringUpFailure` and attempts to restore the bridge. If the required bridge is missing, it aborts into give-up in place; otherwise it returns to `CONNECTED`, rolls `currentProfileId` back, and then reaches the unconditional enqueue. A superseded `onFailure` does none of that guarded work, but still logs and enqueues; the later reservation refuses that enqueue.

The eventual host seam must express this handover semantically — bridge retained through establishment, live owner only after a non-null result, prior bridge closed only as bounded cleanup — without exposing `ParcelFileDescriptor`, Builder, or the sealed owner to the engine. `bringUpRotationCandidate` is rotation-only; shared initial start/revive keep their `None` owner precondition and may share private Builder helpers but not the rotation bridge precondition. This inventory line does not authorize `FailoverEngine` or `TunnelHost` implementation. The contract-recovery gate in this plan remains binding.

Android network availability construction remains host-owned as `AndroidNetworkAvailability(applicationContext)`; the engine never receives `Context`. A `getById` null is `MissingCurrentProfile`, while a DAO/resolver throw remains a pre-commit exception. Candidate-failure reporting preserves `Failover: ${candidateName} failed to come up: …` through the explicit sealed-variant diagnostic mapping rather than a nonexistent `Failure.message` property: ownership rejection (`StoppedSession`, `StaleSession`, and `TransitionNotOwned`) uses `VPN session is no longer active`; `EstablishReturnedNull` uses `VpnService.establish() returned null`; and `PreparationFailed`, `EstablishThrew`, and `FailedAfterLiveInstallation` use their nullable `cause.message` rendered with current Kotlin interpolation. Deferred-kill replay delegates to existing `replayDeferredKill(sessionEpoch)`/`killTunnel(sessionEpoch, null)` and leaves the label unread until the queued kill runs. The engine fake lives in the production `vpn` test package, and later plans must name exact serial Gradle tasks and existing QA scenarios rather than invent test IDs.

- [ ] **Step 4: Inventory commit and post-commit behavior — current source and approved target are different**

Record two layers and never present one as the other. Current source does **not** already have the approved target's settlement hardening.

**Current source** (`XrayVpnService.kt`, `rotateTunnel`, the `onSuccess` block beginning at the `// ---- COMMIT ----` comment): the commit point is the `sessionTunnelState = SessionTunnelState.CONNECTED` write inside the lock. The post-commit steps that follow are:

- `clearGiveUpLineLocked()` — a bare call **outside** `afterRotationCommitted`, so a throw from it escapes the guard and reaches the outer `catch (Throwable) -> failRotation`;
- `afterRotationCommitted("publishing the connected state")` around `LogRepository.setConnectionState(CONNECTED)`;
- bare unguarded assignments of `giveUpOutcome = null`, `unprotectedRetryConsumed = false`, and `unprotectedEpisodeSinceMs = null`;
- `afterRotationCommitted("retracting the give-up alert")` around `VpnNotifications.cancelFailoverBlackholed`;
- `afterRotationCommitted("refreshing the ongoing notification")` around `updateNotification(...)`;
- observation, not consumption, of `pendingKillLabel` (the lock block returns `pendingKillLabel != null`);
- off the lock, `afterRotationCommitted("recording the new active profile")` around `ActiveProfileRepository.setActiveProfileId(next.id)` under a current-session re-check (`isCurrentSessionLocked(session.epoch)`), with the superseded path logging `Failover: superseded rotation is not recording ${next.name} as the active profile`;
- `afterRotationCommitted("posting the switched-server notice")` around `VpnNotifications.postFailover` — this step has **no** current-session re-check of its own;
- `afterRotationCommitted("restarting the health monitor")` around `applyFailoverPreferences(authoritativeFailoverSettings(), session.epoch)`;
- when the observed marker is pending, `afterRotationCommitted("replaying the deferred kill")` around `replayDeferredKill(session.epoch)`.

`afterRotationCommitted` is a per-step `try/catch` (via `afterTransitionCommitted`) that converts a non-cancellation throw into a `Failover rotation: <step> failed after the transition committed: …` log line; it answers "did this throw", never "does this coroutine still own the session", and it does not itself continue later steps when an earlier unguarded step throws. The outer `catch (Throwable) -> failRotation` remains armed over the whole method body, which is exactly why each guarded step exists. The stale switched-server notice and the post-commit settlement hardening are separately disclosed correction prerequisites in the spec's "Separate Correctness Findings and Stop/Go Prerequisites" and "Written-review disclosure" sections; do not inventory them as behavior current source already has.

**Approved target contract** (spec, "Rotation commit and post-commit boundary"): `commitRotation` writes mechanical `CONNECTED` and preserves the settle-up order — clear the give-up line, publish `CONNECTED`, clear give-up/retry markers, cancel the failover alert, refresh the ongoing notification, observe but do not consume `pendingKillLabel` — with **each** fallible effect after the commit write, including clearing the paired line and publishing state, independently isolated in place under that same lock, and with a failure of a step's own diagnostic reporting required not to escape or skip later steps. Post-commit follow-ups (`recordCommittedActiveProfile`, `postCommittedSwitchNotice`, `reconcileAuthoritativeFailoverSettings`, and conditional `enqueueDeferredKillReplay`) are separate attempts whose failures never reach `failRotation`. This independent-isolation hardening is a separate source-correction gate that must be reviewed and verified in the real host before behavior parity is claimed; it is not evidence about current source and must not be copied into the inventory as if it were already implemented. The two diagnostic-only differences (context-neutral reconciliation text for the engine/timer contexts, and the omitted name-bearing stale-persistence log) remain explicit written-review disclosures, not accepted behavior.

**Shared requirement, both layers:** no post-commit exception may flow back into pre-commit `failRotation`. In current source this is achieved only for the steps actually wrapped in `afterRotationCommitted`; the target extends it to every fallible effect and to diagnostic reporting itself.

- [ ] **Step 5: Inventory `failRotation` separately**

Record the current function:

```kotlin
private fun failRotation(sessionEpoch: Long, logMessage: String) {
    synchronized(lock) {
        if (!isCurrentSessionLocked(sessionEpoch)) return
        LogRepository.append(logMessage)
        giveUpRotationLocked(sessionEpoch, "rotation error")
    }
}
```

The current function above is the source inventory. The drafted contract already settles its shape — do not reopen it: `TunnelHost.failRotation(sessionEpoch, logMessage): RotationFailureResult` is a semantic host transaction the engine invokes, returning `StoppedSession`, `StaleSession`, `StoodDown`, or `GaveUp`, preserving the epoch guard and the `rotation error` give-up reason. It is not an ordinary candidate failure, which travels the separate rollback/retry path. If the inventory and the draft disagree, report the discrepancy at the written-spec gate rather than redesigning either.

---

### Task 3: Inventory give-up, re-arm, and recovery sequencing

**Files:**
- Read/search: `XrayVpnService.kt`
- Read/search: `SessionLifecycleDecision.kt`
- Read: `FailoverDecision.kt` — `FailoverEpisodeDecision` is the top-level object at lines 48-59 (`recordProbeFailedCurrent`, `recordBringUpFailure`, `clearOnHealthyProbe`), not a member of `FailoverDecision`.

**Interfaces:**
- Consumes: current containment and recovery code
- Produces: exact give-up/re-arm contract requirements

- [ ] **Step 1: Inventory `giveUpRotationLocked` exits**

Record all three physical outcomes and the stand-down path:

- another lifecycle owner/state: release any bridge, schedule the appropriate re-arm, announce any dropped deferred kill, and do not establish containment;
- `LIVE_PROXY`: retain the live tunnel and classify `CONTAINED_BY_LIVE_TUNNEL`;
- `UnreadRotationBridge`: adopt the same handle as `UnreadBlackhole` and classify `CONTAINED_BY_BLACKHOLE`;
- `None`: attempt a fresh unread blackhole and classify success/failure as blackhole/unprotected.

The current-source stale reservation release at `XrayVpnService.kt:1134` is a separate correctness finding, not an approved behavior to preserve. The target contract rejects stopped/stale callbacks before cleanup. `ABSENT`/`STOPPED` monitor results remain measured relative to monitor presence on transaction entry, and `ServiceStopRequested` records that the guarded stop call was requested, not that Android completed service destruction.

Record the required order: capture the pre-action role when needed, choose containment, adopt/install owner, classify resulting packet truth, publish give-up line/state/notifications, schedule re-arm last, then discharge the deferred-kill marker.

- [ ] **Step 2: Inventory re-arm modes**

Document `scheduleFailoverRearmLocked(sessionEpoch, retryByRotation, unprotectedSinceMs)`:

- it owns the service timer and uses `authoritativeFailoverSettings().rotationWindowMs`;
- it cancels/replaces the prior timer;
- it re-checks authoritative enablement and current epoch when the timer fires;
- normal contained give-up re-applies monitor preferences;
- `UNPROTECTED` uses `unprotectedRetryAction` and directly attempts rotation only from `CONNECTED`;
- `DEFER` re-arms without spending the retry;
- `STOP_SERVICE` calls synchronous `stopVpn` after the deferral deadline;
- the original `unprotectedSinceMs` is carried through all deferrals.

The contract must not replace this with a generic `scheduleRearm(delayMs)` operation that loses retry mode or episode timing.

- [ ] **Step 3: Inventory recovery clearing**

Document `clearGiveUpStateOnRecovery(sessionEpoch)` and its healthy-monitor callback:

- current epoch and `CONNECTED` state are required;
- failed IDs are cleared on a healthy probe;
- when current and mechanically `CONNECTED`, failed IDs are cleared before inspecting give-up/owner state;
- with no give-up marker, return `EpisodeFailuresCleared` regardless of owner;
- with a give-up marker and no owner, return `IgnoredWithoutTunnel` after the clear and never publish `CONNECTED`;
- a contained give-up clears outcome/markers, resets the episode budget, clears the give-up line, publishes `CONNECTED`, updates notification, and cancels the blackhole notification.

The eventual contract must preserve the distinction between healthy-probe recovery and clear-network false success.

---

### Task 4: Inventory monitor and settings ownership

**Files:**
- Read/search: `XrayVpnService.kt`
- Read: `failover/TunnelHealthMonitor.kt`
- Read: `failover/FailoverPreferences.kt`

**Interfaces:**
- Consumes: current monitor/settings lifecycle
- Produces: exact monitor coordination contract requirements

- [ ] **Step 1: Inventory `applyFailoverPreferences`**

Record that it executes under `lock` and owns:

- current-session validation;
- disable cancellation of the re-arm timer and rotation-attempt reset;
- contained give-up release on disable while preserving `UNPROTECTED` marker/alert;
- unprotected re-arm restoration on re-enable;
- `shouldRunFailoverMonitor` gating on enabled/running/`CONNECTED`;
- `failoverMonitorNeedsRebuild` comparison of probe interval/timeout/failure threshold only;
- stopping/replacing the monitor;
- constructing `TunnelHealthMonitor` with fixed probe target and the two callbacks;
- reconciling the shared screen receiver.

The eventual engine must not independently manage the screen receiver or kill-switch monitor.

- [ ] **Step 2: Inventory monitor callback ownership**

Record the exact callback order and meaning:

```kotlin
monitor.start(
    onHealthy = { clearGiveUpStateOnRecovery(sessionEpoch) },
    onUnhealthy = { rotateTunnel(sessionEpoch) },
)
```

`TunnelHealthMonitor` is terminal after firing unhealthy and invokes each listener at most once per start. The eventual engine contract uses the same direct healthy callback and service-owned unhealthy launcher; no extra launch is added inside the engine. The contract must not add a duplicate callback or liveness gate that swallows a callback after cancellation.

- [ ] **Step 3: Inventory shared screen receiver behavior**

Document that one receiver pauses/resumes both kill-switch and failover monitors, and that `reconcileScreenReceiverLocked` registers while either monitor is live. Any host transaction that stops or rebuilds failover must leave kill-switch receiver ownership intact.

- [ ] **Step 4: Inventory settings observer and session identity**

Document that the service seeds `FailoverPreferences.load(...)` on connect, then observes `FailoverPreferences.state` asynchronously on `serviceScope`, passing the captured session epoch to `applyFailoverPreferences`. The eventual engine must not capture an epoch beyond the session lifetime or own the settings collector.

---

### Task 5: Verify the approved contract and its synchronized plan reference

**Files:**
- Read: `docs/superpowers/specs/2026-09-04-auto-failover-engine-migration-design.md`
- Read: this recovery plan and `docs/superpowers/plans/2026-09-04-auto-failover-engine-migration-plan.md`

**Interfaces:**
- Consumes: the complete written contract approved by the maintainer on 2026-09-23, plus Tasks 1–4 source inventory
- Produces: verification that the index transcribes the exact 17-member host, four engine entries, requests/results, phase paths, and execution gates without redefining them

The approved spec already fixes exact packages, the 17-member `TunnelHost`, every result type, epoch rules, and phase gates. This task never authors or reopens that contract. A discrepancy between source inventory and approved spec is reported and returned to the spec/recovery gate; neither a phase plan nor this document may silently redesign the seam.

- [ ] **Step 1: Confirm the draft already classifies the host transaction boundary**

Verify — do not decide — that the draft explicitly places each item as a passive engine entry point, a semantic `TunnelHost` transaction, a service-owned callback/timer/helper, or an existing pure decision outside both:

- rotation reservation — `TunnelHost.beginRotation` (`RotationStartResult`);
- candidate retrieval/selection — `TunnelHost.loadRotationPool` (`RotationPoolResult`) plus the existing pure `FailoverDecision.nextCandidate` outside both;
- candidate bring-up attempt — `TunnelHost.bringUpRotationCandidate` (`CandidateBringUpResult`);
- pre-commit rotation failure — `TunnelHost.failRotation` (`RotationFailureResult`);
- post-commit settle-up — `TunnelHost.commitRotation`, `recordCommittedActiveProfile`, `postCommittedSwitchNotice`, `enqueueDeferredKillReplay`, and `reconcileAuthoritativeFailoverSettings`;
- give-up containment delegation — service-private `giveUpRotationLocked` returning `GiveUpSettlement`, called only under the already-validated lock and **not** a `TunnelHost` member;
- normal re-arm — service-owned timer invoking engine `onRearmTimer`, with `TunnelHost.admitFailoverRearm`;
- unprotected retry/defer/stop — `TunnelHost.handleUnprotectedRearm` (`UnprotectedRearmResult`);
- healthy recovery clearing — engine `onHealthyProbe` delegating to `TunnelHost.settleHealthyProbe` (`HealthyProbeResult`);
- settings reconciliation — engine `onFailoverSettings` delegating to `TunnelHost.reconcileFailoverSettings` and `reconcileAuthoritativeFailoverSettings` (`SettingsReconciliationResult`);
- monitor stop/rebuild — expressed by `MonitorChange` inside `SettingsReconciliationResult`; the service owns `stopFailoverMonitorLocked`;
- shared receiver reconciliation — service-private `reconcileScreenReceiverLocked`, never an engine entry point.

- [ ] **Step 2: Confirm the draft's exact signatures and result coverage**

Verify the drafted contract already names exact Kotlin packages, top-level versus nested types, parameters, return types, and suspension behavior, and that its result types already represent at least:

- success;
- the two reservation-refusal exits and the separate successful-reservation admission path (funnel into give-up while retaining/adopting the bridge, with no re-arm in that block; refusal without funnel releases the bridge and re-arms only when `unprotectedRecoverySinceMs != null`, the callback is the current session, and authoritative failover is enabled; after reservation succeeds the retry is marked consumed before thrash-cap admission);
- thrash-cap denial after reservation succeeds (`RotationAdmission.Denied`), distinct from reservation refusal;
- stale epoch/session;
- missing current profile;
- candidate bring-up failure whose guarded `ROTATING` path records the failure, restores the bridge, returns to `CONNECTED`, rolls back `currentProfileId`, then unconditionally logs and enqueues the next `rotateTunnel` (a superseded enqueue is refused later by reservation);
- the two in-place missing-bridge aborts, both still `ROTATING` and neither returning to `CONNECTED` nor recursing: post-teardown (`XrayVpnService.kt` 1248-1258) and post-failure re-establish (1458-1471);
- containment failure after give-up (`UNPROTECTED`), distinct from those missing-bridge aborts;
- give-up outcomes;
- re-arm `ATTEMPT`, `DEFER`, and `STOP_SERVICE`;
- monitor stop/rebuild/no-op.

If a current branch is not expressible by the drafted result types, stop and report the gap at the written-spec gate. Do not add or alter a result type in either plan or in the spec.

- [ ] **Step 3: Confirm the draft's engine lifetime and epoch handling**

Verify the draft states the engine is a reusable object constructed with only its immutable host reference, receiving the `sessionEpoch` on every entry point and every epoch-sensitive host operation, with no captured epoch and no session or episode fields. A process-lifetime engine holding one captured `sessionEpoch` is forbidden because stop/restart changes the epoch. This is a check against the draft, not a design choice.

- [ ] **Step 4: Confirm the draft's fake-host observability**

Verify the draft's fake-host contract can assert operation order without Android resources and exposes controlled outcomes for every branch listed in the spec's acceptance criteria, while episode state stays in service-side test state or explicit host test fixtures rather than hidden engine fields. The fake surface is specified as `FailoverEngineTest` with a test-only `RecordingTunnelHost` in the production `vpn` package, implementing the exact 17-member interface. This task does not create that test or that fake.

- [ ] **Step 5: Confirm the draft's change-control record**

Verify the engine spec carries both its dated 2026-09-23 contract-recovery entry and the later written-approval-for-planning entry. The original entry states the previous truncated decision, replacement contract, reason, and affected sections; the new entry records the maintainer's approval. Keep the surviving summary and historical decisions intact. Any later signature/policy change returns to a fresh written-spec review rather than being edited into these plans.

- [x] **Step 6: Post-approval synchronization checklist**

The maintainer explicitly approved the written engine spec for planning on 2026-09-23. This step updates this recovery plan and the engine index to describe that approved contract, while preserving the operative Tasks 2–4 and all historical change-control entries. The four phase plans are a separate planning deliverable. Synchronization does not satisfy any structural or source-correction execution gate. The checklist:

1. **Approval status and change-control record** — both files now record the maintainer's 2026-09-23 approval for planning and point to the spec's dated approval entry. The new dated synchronization entries do not rewrite historical 2026-09-21/22/23 notes.
2. **Architecture and goal** — current headings state the contract is approved and synchronized, not missing, truncated, or waiting to be chosen.
3. **Task 1 / Task 5 / Task 6 gate language** — Task 1's draft-boundary verification remains historical; Task 5 Steps 1–5 verify against approved text; Task 6 authorizes deriving the four phase plans, not executing them.
4. **Phase-plan list and original absence** — all four phase-plan paths remain listed. They were absent at synchronization, and creating them is a separate task after this gate.
5. **Structural-foundations execution prerequisite** — both plans continue to require that the structural-foundations index plus owner, capture, and verification plans pass stop/go before any engine plan executes. Synchronization neither executes nor satisfies that prerequisite.
6. **Exact approved seam and result ledger** — the engine index reproduces the spec's 17 `TunnelHost` method signatures, four engine entries (`rotate`, `onRearmTimer`, `onHealthyProbe`, `onFailoverSettings`), and request/result declarations. These are a reference to the approved text, not alternative APIs. Any later mismatch stops plan derivation and returns to the spec/recovery gate.

---

### Task 6: Contract approval and plan-derivation gate

**Files:**
- Read: approved engine spec
- Read: this synchronized recovery plan and the engine index
- Read: the source inventory

**Interfaces:**
- Consumes: the explicitly approved complete engine design and synchronized gate documents
- Produces: permission to **author** the four phase-specific implementation plans, not permission to execute or implement them

- [ ] **Step 1: Reject pseudo-complete contracts**

The gate fails if the design still has any of these properties:

- `TunnelHost` mirrors private service methods one-for-one;
- engine receives Android resources, descriptors, Builder, DAO, mutable owner, or Context;
- engine owns a coroutine scope, timer, dispatcher, settings collector, or episode state;
- `scheduleRearm(delayMs, epoch)` loses `retryByRotation` or `unprotectedSinceMs`;
- candidate traversal bypasses `FailoverDecision.nextCandidate` or service-owned failed IDs;
- give-up is represented only by a Boolean success flag;
- post-commit failures can flow into pre-commit `failRotation`;
- monitor coordination can unregister the shared receiver while kill-switch remains live;
- stale callbacks lack epoch rejection;
- the design does not define the three independent migration phases and their stop/go gates.
- the design leaves a give-up-triggering host transaction's partial settlement ambiguous such that a generic pre-commit catch could invoke a second `failRotation`/give-up;
- the design uses a failure diagnostic property not present on every sealed failure variant, rather than an explicit variant-to-diagnostic mapping.

- [ ] **Step 2: Confirm exact source compatibility**

Check every type and method named by the design against source by name, not by a declaration-column anchor. `internal`, `private`, `public`, `sealed`, and `object` modifiers, plus indentation inside objects, make `^internal ` / `^object ` / `^class ` miss real symbols (`SessionLifecycleDecision.kt` is a file of top-level `internal fun`s, not one class; `admitRotation` is indented inside `object FailoverDecision`; the service methods below are `private fun`).

```powershell
rg -n -e "canReserveRotationFromAuthoritativeState|shouldFunnelRotationReservationRefusal|containmentForGiveUp|shouldEstablishRotationBridge|shouldAbortRotationForMissingBridge|classifyGiveUpOutcome|shouldRestoreUnprotectedRearm|admitRotation|nextCandidate|recordProbeFailedCurrent|recordBringUpFailure|resolve" app/src/main/java/com/justme/xtls_core_proxy/vpn/SessionLifecycleDecision.kt app/src/main/java/com/justme/xtls_core_proxy/failover/FailoverDecision.kt app/src/main/java/com/justme/xtls_core_proxy/failover/FailoverPoolResolver.kt
rg -n -e "rotateTunnel|failRotation|giveUpRotationLocked|scheduleFailoverRearmLocked|clearGiveUpStateOnRecovery|applyFailoverPreferences|stopFailoverMonitorLocked" app/src/main/java/com/justme/xtls_core_proxy/vpn/XrayVpnService.kt
```

A named symbol with no match is a design mismatch. Do not treat a modifier-anchored grep that prints nothing as proof the symbol is absent. Any mismatch is corrected in the design before a plan is authored.

- [ ] **Step 3: Require maintainer approval**

The maintainer responsible for `vpn/` explicitly approved the amended written spec for planning on 2026-09-23. This recovery plan and engine index record that approval and the exact seam, so the four named plans may now be authored from the approved text. This is not authorization to execute them: all structural-foundations index/owner/capture/verification gates and the separate correctness gates remain prerequisites, and implementation still requires its own approval.

## Recovery Plan Self-Review

- [ ] The older 2026-07 design is cited only as historical context, never as the missing contract.
- [ ] Every current sequencing method named by the surviving engine summary is inventoried.
- [ ] Rotation, give-up/re-arm, and monitor/settings remain separate migration phases.
- [ ] Service-owned episode state and pure-policy components remain outside the engine.
- [ ] The approved design has expressive results, explicit epoch lifetime, callback ordering, and shared-receiver safety; check again when deriving each phase plan.
- [x] Written-spec approval and exact seam are recorded before phase-plan derivation; no production implementation is authorized by that approval.
- [ ] No production implementation is authorized by written approval or plan authoring; structural and source-correction gates still control execution.

## Change Control

### 2026-09-23 — Approved contract synchronized for plan authoring

- **Approval:** The maintainer explicitly approved the amended written spec and requested `superpowers:writing-plans` on 2026-09-23, authorizing four phase plans but not their execution.
- **Replacement decision:** The recovery plan and engine index now record the approved 17-member seam, result ledger, four engine entries, plan ownership, and separate structural execution gates. Task 5 Step 6 is the synchronization record; source inventory and historical entries are retained.
- **Reason:** Written approval unblocks exact plan derivation, not production code or independently reviewed correctness prerequisites. The two disclosed diagnostic-only differences are included in the approved planning contract.
- **Affected sections:** Banner, goal/architecture, Files, Task 1 and Tasks 5–6 gate wording, self-review, change control. Tasks 2–4 retain their operative source-versus-target inventory.

### 2026-09-23 — Complete draft awaits written approval

- **Previous decision:** The active recovery gate assumed the engine spec was still the historical 59-line truncation and Task 1 expected no complete contract.
- **Replacement decision:** The 2026-09-23 design contains the proposed full contract, while explicit written-spec approval is still pending. Task 1 verifies that draft and approval boundary. Synchronize this recovery plan and the index after approval and before deriving the four phase plans; structural-foundations execution gates executing those later plans, not authoring them.
- **Reason:** Treating the previous truncation as a current fact obscures the live approval gate and could lead either to premature implementation or to re-designing an already-present proposal.
- **Affected sections:** Architecture, Task 1, Task 6 (the contract approval gate — this plan has no section named "handoff gate"), and change control. Historical 2026-09-21/22 entries remain historical.

### 2026-09-23 — Review clarifications pending written-spec approval

- **Replacement decision:** Record the exact role-based lifecycle signatures, one-settlement boundary for give-up-triggering transactions, healthy-probe ordering, establish-first diagnostic continuation, and planning-only source precisions in the draft/index inventory. Preserve the separately disclosed stale-release, commit-settlement, switched-notice, and timeout-publication corrections as gates; do not treat non-findings as new changes.
- **Approval status:** These corrections do not approve the written spec or authorize implementation/plans.

### 2026-09-21 — Contract recovery plan added

- **Previous decision:** The repaired engine index stopped at the missing-spec gate but had no detailed source-inventory workflow.
- **Replacement decision:** Contract recovery now inventories the current rotation, give-up/re-arm, monitor/settings, epoch, receiver, and post-commit boundaries before requiring exact host signatures and maintainer approval.
- **Reason:** The historical core design is not compatible with the current fail-closed implementation, and the truncated engine spec cannot safely determine the seam.
- **Affected sections:** Contract prerequisites, source inventory, approval criteria, and implementation-plan handoff.

### 2026-09-21 — Reservation and candidate-failure branch recovery

- **Previous decision:** The inventory compressed refusal and thrash-cap admission, implied candidate retry was ownership-guarded, and used declaration-anchored source searches that missed existing symbols.
- **Replacement decision:** The inventory distinguishes two reservation-refusal exits from successful-reservation admission, keeps candidate rollback guarded while logging and retry enqueue remain outside the guard, names both in-place missing-bridge aborts, locates `FailoverEpisodeDecision` in `FailoverDecision.kt`, and checks source compatibility by symbol name.
- **Reason:** Current service control flow depends on these distinctions to adopt a held containment bridge, preserve the unprotected retry budget, and reject stale recursive attempts at reservation without mutating a newer session.
- **Affected sections:** Files read, Task 2 Steps 1 and 3, Task 5 result coverage, Task 6 source-compatibility gate.

### 2026-09-22 — Establish-first handover inventory line

- **Previous decision:** Step 3 item 6 said to release the rotation bridge immediately before `Builder.establish()`, and item 7 said to install a live owner only after establishment succeeds. That release-first order is still what production does.
- **Replacement decision:** The inventory's handover items now record the approved target: keep the unread bridge authoritative through `establish()`, leave it unchanged on `null`/throw, install the live owner immediately on a non-null candidate, and close the old bridge once as a bounded local cleanup handle. The existing close warning and release audit stay after `TUN established with fd=…`. The current production release-first order is named only as superseded baseline.
- **Reason:** The 2026-09-22 structural-foundations amendment replaced release-first so the contract inventory would not preserve an avoidable interface-free window. Android's documented `Builder.establish()` contract leaves the existing interface untouched when replacement creation fails. This entry changes only that handover inventory. It does not complete the engine design, authorize engine implementation, or weaken this plan's contract-recovery gate.
- **Affected sections:** Task 2 Step 3 handover items and the host-seam sentence that follows them. No other inventory step changes.
