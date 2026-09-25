# Auto-Failover Engine Migration Corrections Plan

> **DOCUMENTATION-ONLY CORRECTIONS PLAN — do not execute implementation from this plan.** Written-spec approval was recorded on 2026-09-23 for planning only. Current planning date is 2026-09-24. This plan authors two separately reviewed corrections and their gates; it creates no production code/tests, runs no builds/device QA, and authorizes no commit. Later execution of any correction requires separate maintainer authorization plus the structural-foundations and phase-specific gates named below.

**Goal:** Record two findings as separately reviewed prerequisites with exact placement, bounded change shape, evidence limits, and stop/go conditions: (A) the unprotected-timeout STOP_SERVICE effect-time lock+epoch correction between Phase 1 and Phase 2; (B) the minimal bounded status/acceptance synchronization for the index and recovery plan now that the four phase plans exist.

**Architecture:** Correction A is a service-host effect-boundary correction inside the existing Phase-1 typed helper, reviewed and verified before the Phase-2 timer migration consumes it. Correction B is additive documentation status only, preserving historical truth. Neither correction redesigns the approved 2026-09-23 contract, and neither satisfies structural execution gates by itself.

**Tech Stack:** Kotlin, `XrayVpnService` lifecycle lock, session epochs, JUnit4, human `vpn/` review, debugger/device fault-injection gates, Markdown change control.

**Spec:** `docs/superpowers/specs/2026-09-04-auto-failover-engine-migration-design.md` (Timer admission and unprotected recovery; Separate Correctness Findings item 3; Written-review disclosure).

## Global Constraints

- Planning approval (2026-09-23) is not execution permission. Require a separate implementation request and maintainer review of this corrections plan before touching production.
- Do not implement, test, build, commit, push, alter device state, or touch files/config outside the repository (including `~/.gnupg`, global git config, shell profiles, IDE user settings). Do not create a worktree; document authoring uses the existing working tree.
- Do not edit any file other than this corrections plan in this task. In particular, do not silently modify the index, recovery plan, rotation plan, or give-up/re-arm plan now; Correction B plans those edits only.
- Preserve the approved contract: no new engine member, result variant, policy veto, timing constant, clock migration, scheduler shape, or containment rule. A signature/policy change returns to the spec/recovery gate.
- `stopVpn` stays synchronous with no await, dispatcher handoff, or suspend. Do not add anything that awaits to it (RISK-1/main-thread hazard documented in `SessionLifecycleDecision.kt`).
- Preserve ATTEMPT/DEFER policy, original `unprotectedSinceMs` propagation, cancellation propagation, and existing `CONNECTED`/enable boundaries. Add no new CONNECTED/enable recheck at effect time.
- If a correction needs a test seam and none exists, do not invent one or add a production fault hook; specify a concrete review/device fault-injection gate instead and leave the gate open when undrivable.
- Run heavyweight Gradle commands serially when execution is later authorized. Keep device app data; never opt into uninstall or clear-data for a data-bearing installation.
- No generated AAR/geo assets, local properties, IDE state, personal endpoints, secrets, or user-level configuration may be changed by these corrections.
- Commits require a later explicit request and `git commit -S`. On signing failure stop and report; do not change external configuration, amend, or retry unsigned.

## Review Focus

1. **Stop/start between decision and effect:** an old STOP_SERVICE callback must emit no warning, error surface, or stop against the new session.
2. **ATTEMPT/DEFER untouched:** the correction narrows only the STOP_SERVICE effect boundary; budget, timestamp, and deadline policy stay exactly as specified.
3. **Two-lock shape:** decision lock, unlock, then a second effect-time lock covering check plus all stop effects together with no unlocked gap.
4. **Historical truth preserved:** index/recovery status updates are additive and dated; synchronization-time absence statements remain labeled historical.

## Source baseline vs approved target — stated separately

Record both layers; never present one as the other.

**Current inspected source baseline (pre-Phase-0/Phase-1, as read in this task):**

- `app/src/main/java/com/justme/xtls_core_proxy/vpn/XrayVpnService.kt` uses three owner fields (`tunInterface`, `tunInterfaceKind`, `rotationBridgeInterface`) with boolean `hasTunnel`/`hasRotationBridge` policy inputs, and release-first establishment. This is the superseded structural baseline.
- `scheduleFailoverRearmLocked(sessionEpoch, retryByRotation: Boolean, unprotectedSinceMs: Long)` owns the service timer on `serviceScope`, uses `authoritativeFailoverSettings().rotationWindowMs`, cancels/replaces the prior job, re-checks `shouldFireFailoverRetry` at fire time, and dispatches the UNPROTECTED recovery inline (decision under `lock` at approximately `XrayVpnService.kt:1798-1821`, effect `when (action)` outside that lock at approximately `:1822-1845`).
- The STOP_SERVICE arm in that baseline appends `Failover: no tunnel, and no chance to retry for $UNPROTECTED_UNATTEMPTED_RETRY_WINDOWS rotation windows; stopping the VPN`, emits `R.string.vpn_failover_stopped_error` in-app and as the error notification, then calls synchronous `stopVpn(expectedSessionEpoch = sessionEpoch)` — all after the decision lock has been released. Only `stopVpn` rechecks the epoch. This unlocked warning/error/stop sequence is the defect Correction A addresses.
- `stopVpn(expectedSessionEpoch: Long? = null, stopService: Boolean = true)` is synchronous, checks `isCurrentSessionLocked` under `lock` when an epoch is supplied, and performs teardown inline. No `FailoverEngine`, `TunnelHost`, `RecordingTunnelHost`, `GiveUpSettlement`, `FailoverRearmRequest`, or `UnprotectedRearmResult` symbols exist in `app/src` (verified by symbol search in this task).
- Pure policy already exists and is tested: `unprotectedRetryAction`, `UNPROTECTED_UNATTEMPTED_RETRY_WINDOWS = 3`, `shouldFireFailoverRetry`, `shouldStopServiceOnGiveUp` in `vpn/SessionLifecycleDecision.kt`, covered by `SessionLifecycleDecisionTest`.

**Approved post-Phase-0 structural target (prerequisite, not yet evidenced in source):**

- Single sealed owner, `TunnelRole` projection, role-based predicates (`containmentForGiveUp`, `shouldEstablishRotationBridge`, `shouldAbortRotationForMissingBridge`, `shouldFunnelRotationReservationRefusal`, `classifyGiveUpOutcome`, `shouldRestoreUnprotectedRearm`), shared capture applicator, and establish-first handover with full-switch packet evidence. Engine phases must not execute against the three-field/release-first baseline.

**Approved post-Phase-1 helper target (incoming baseline for Correction A):**

- Rotation Task 3 Step 5 owns the complete typed helper bodies, including `admitFailoverRearmFromService` and `handleUnprotectedRearmFromService`. Phase 1 keeps Boolean scheduling and direct service-timer dispatch. Its Step 5b STOP_SERVICE arm explicitly retains the inspected unlocked baseline and labels it as the incoming gap; Phase 2 explicitly excludes making the correction and gates on it having landed separately.

**Approved final effect-time target (Correction A output, consumed by Phase 2):**

- After policy chooses STOP_SERVICE and exits the decision lock, reacquire the same lifecycle lock, reject `!running` or epoch mismatch with typed stopped/stale result and no warning/error/stop, otherwise emit the existing warning, in-app error, error notification, and synchronous stop inside that same lock. Give-up/re-arm Task 1 Step 3 records this exact block as its required reviewed target.

## File structure

| File | Role in these corrections |
|---|---|
| This plan only (now) | Correction definitions, placement, bounded change shape, evidence limits, gates |
| `app/src/main/java/com/justme/xtls_core_proxy/vpn/XrayVpnService.kt` (later, Correction A) | Single STOP_SERVICE arm of `handleUnprotectedRearmFromService` |
| `app/src/test/java/com/justme/xtls_core_proxy/vpn/SessionLifecycleDecisionTest.kt` (later, reference only) | Existing policy-boundary characterization; no new policy test invented here |
| `docs/superpowers/plans/2026-09-04-auto-failover-engine-migration-plan.md` (later, Correction B) | Bounded status/acceptance + additive change-control update |
| `docs/superpowers/plans/2026-09-04-auto-failover-engine-migration-contract-recovery-plan.md` (later, Correction B) | Bounded status + additive change-control update |

No new contract file, fake, engine entry, or production test seam is introduced by either correction.

---

### Task 1: Correction A — unprotected-timeout STOP_SERVICE effect-time lock+epoch prerequisite

**Files (later execution only):** Modify `app/src/main/java/com/justme/xtls_core_proxy/vpn/XrayVpnService.kt` and nothing else. Read `vpn/SessionLifecycleDecision.kt` (`unprotectedRetryAction`, `UNPROTECTED_UNATTEMPTED_RETRY_WINDOWS`, `shouldFireFailoverRetry`) and the rotation plan Task 3 Step 5b plus give-up/re-arm Task 1 Step 3 as target references.

**Interfaces:** Consumes the landed Phase-1 `handleUnprotectedRearmFromService` baseline and the existing synchronous `stopVpn`. Produces the effect-time boundary only; ATTEMPT/DEFER arms, scheduler signature, timer dispatch, policy functions, and constants are unchanged.

**Placement:** After Phase 1 (rotation integration, including its Step 5b helper bodies) is reviewed and landed, and before Phase 2 (give-up/re-arm Task 2 timer migration). Give-up/re-arm Task 1 Step 3 stops until this correction is separately approved and verified; Phase 2 must preserve an already-landed correction and never regress it to the unlocked baseline. If Phase 1 has not landed its helper, stop here; do not implement the correction against the pre-Phase-1 inline timer.

- [ ] **Step 1: Confirm the Phase-1 incoming baseline by symbol, not by stale line number**

  Locate `handleUnprotectedRearmFromService` in `XrayVpnService.kt` by symbol search. Confirm it has the Phase-1 shape: decision `val action = synchronized(lock) { ... unprotectedRetryAction(...) ... }` with DEFER scheduling inside that lock using the unchanged input timestamp, followed by `return when (action)` outside the lock whose STOP_SERVICE arm performs the unlocked warning/error/stop sequence. Confirm ATTEMPT enqueues exactly one `RotationRequest.UnprotectedRecovery(unprotectedSinceMs)` (Phase-1 caller shape) and does not spend the retry, and that DEFER appends the existing `Failover: recovery rotation cannot be attempted yet (tunnel is $sessionTunnelState); re-arming without spending the retry` diagnostic. If the helper is absent or its STOP_SERVICE arm already has the effect-time lock, stop and reconcile (absent = Phase 1 not landed; present-with-lock = correction already landed, record that fact instead of re-applying).

- [ ] **Step 2: Apply the bounded STOP_SERVICE effect-time correction and nothing else**

  Before (Phase-1 baseline shape, abbreviated; preserve surrounding decision/ATTEMPT/DEFER code verbatim):

  ```kotlin
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
              // BASELINE GAP: unlocked warning/error/stop.
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

  After (required reviewed target; only the STOP_SERVICE arm changes):

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

  Bounded-change rules for this step:

  - Rejected (stopped/stale) callbacks return their typed result with no warning appended, no in-app error, no error notification, and no stop.
  - The accepted path keeps the exact existing warning string, both existing `vpn_failover_stopped_error` surfaces, and synchronous `stopVpn(expectedSessionEpoch = sessionEpoch)` — all inside the same second `synchronized(lock)` with no unlocked gap between the effect-time check and the effects.
  - Preserve ATTEMPT and DEFER arms verbatim, including the original `unprotectedSinceMs` carried through DEFER and the ATTEMPT enqueue shape. Preserve `System.currentTimeMillis()` call sites, `authoritativeFailoverSettings().rotationWindowMs` reads, and the `UNPROTECTED_UNATTEMPTED_RETRY_WINDOWS = 3` constant.
  - Add no CONNECTED/enable recheck at effect time, no new settings veto, no deadline snapshot, no monotonic-clock change, no overflow policy, and no new parameter/result variant.
  - `stopVpn` gains no await, dispatch, or suspension; it is called inside the effect lock exactly as the baseline calls it, only now under the re-acquired lock.
  - `CancellationException` propagates unchanged; no new catch converts it into a result.
  - Do not convert the Boolean scheduler, switch the timer callback, or move containment/timer ownership. Those belong to Phase 2 and consume this correction.

- [ ] **Step 3: Verify with truthful limits — no invented seam, no fake-host substitute**

  Test and review evidence for later execution (not performed by this plan):

  1. Re-run the existing policy characterization in `SessionLifecycleDecisionTest` covering `unprotectedRetryAction` ATTEMPT/DEFER/STOP boundaries, the three-window bound, and `shouldStopServiceOnGiveUp` UNPROTECTED-only stop. Expected PASS unchanged; this pins policy, not the new effect-time lock.
  2. Human `vpn/` review traces the two-lock shape: decision lock, unlock, then the effect-time `synchronized(lock)` covering running/epoch check plus warning, both error surfaces, and synchronous stop together. Review confirms no warning/error/stop on the stopped/stale paths, no moved scheduling, no spent retry, and no `stopVpn` signature/behavior change beyond being called under the effect lock.
  3. Device fault-injection gate (no permanent production fault hook): on a consented debug/device setup, use the debugger to pause after the STOP_SERVICE decision, stop/start a new session, then release the old callback. Required observation: the old session emits no warning, no in-app error, no error notification, and performs no stop against the new session; the new session is unaffected. Also observe the positive path: an unpaused STOP_SERVICE still stops with the existing warning and both error surfaces.
  4. Do not claim JVM fake-host tests prove effect-time epoch safety. No new unit test is specified here because no production seam exists for the timer effect boundary; if the debugger/device case cannot be driven reliably, record it as missing evidence and keep the gate open.

- [ ] **Step 4: Record stop/go for Correction A**

  Correction A stays open when any of these holds: Phase 1 helper baseline absent; STOP_SERVICE arm differs from both the baseline and target shapes above; ATTEMPT/DEFER text, timestamp propagation, constants, or cancellation behavior changed; human `vpn/` review missing; positive-path or stale-callback device evidence missing; or `stopVpn` gained an await/dispatch. Phase 2 (timer migration) must not start from an open Correction-A gate. Record exact test commands/results, reviewer decision, device/build, debugger pause point, and observations with no private endpoints or unredacted logs.

---

### Task 2: Correction B — index and recovery-plan status synchronization (docs only)

**Files (later execution only):** Modify only `docs/superpowers/plans/2026-09-04-auto-failover-engine-migration-plan.md` (index) and `docs/superpowers/plans/2026-09-04-auto-failover-engine-migration-contract-recovery-plan.md` (recovery plan). Do not modify the spec, phase plans, production code, or tests. All edits are bounded status/acceptance plus additive dated change control preserving historical truth.

**Interfaces:** Consumes the four existing phase-plan paths below and the 2026-09-23 written-spec approval record. Produces accurate delivery status only; authoring the phase plans does not authorize their execution and does not satisfy structural or source-correction gates.

- [ ] **Step 1: Run the exact four path checks and record their outcomes**

  Run (paths relative to repository root):

  ```powershell
  Test-Path docs/superpowers/plans/2026-09-04-auto-failover-engine-migration-rotation-plan.md
  Test-Path docs/superpowers/plans/2026-09-04-auto-failover-engine-migration-giveup-rearm-plan.md
  Test-Path docs/superpowers/plans/2026-09-04-auto-failover-engine-migration-monitor-plan.md
  Test-Path docs/superpowers/plans/2026-09-04-auto-failover-engine-migration-verification-plan.md
  ```

  For each present file, confirm by inspection (not by build) that it addresses its indexed responsibility and does not invent a competing contract: rotation owns the exact 17-member interface/four entries/full recording fake and rotation adapter; give-up/re-arm owns typed scheduling and timer dispatch with the Correction-A gate stated; monitor owns healthy/settings caller wiring with service-owned receiver/monitor; verification aggregates tests and device/release evidence without introducing the first fake. Record any missing or off-scope file as an open docs gate; do not mark authored what is absent or mismatched.

- [ ] **Step 2: Apply the bounded index edits (planned wording, not applied by this plan)**

  1. In the index Plan Set table, replace each `Authorized to author; absent at synchronization` status with an authored status that preserves the historical note, e.g. `Authored 2026-09-24; reviewable, not executed (was absent at 2026-09-23 synchronization)`. Change the verification row's `Author after the three phase plans; absent at synchronization` equivalently once its path check passes.
  2. In the index acceptance checklist, check the four phase-plan authoring rows only to the extent Step 1 verified them, keeping the planning-only scope: e.g. `Four independently reviewable phase plans are authored ... (planning only; execution unauthorized)`. Leave every execution/device/commit row unchecked.
  3. Keep the `At synchronization: All four phase-plan files were absent` sentence labeled historical; add a dated follow-on sentence rather than rewriting it, e.g. `On 2026-09-24 the four paths above were verified present for review; this does not authorize execution.`
  4. Append an additive dated `### 2026-09-24 — Phase-plan delivery status synchronized` change-control entry to the index recording: previous decision (status text recorded absence at synchronization), replacement decision (status now records authored-but-unexecuted delivery), reason (historical absence text would otherwise read as current fact), and affected sections (Plan Set, acceptance checklist, change control). No production behavior changes.

- [ ] **Step 3: Apply the bounded recovery-plan edits (planned wording, not applied by this plan)**

  1. Update the recovery plan's phase-plan list note and Task 5 Step 6 / Task 6 gate language to state the four plans are authored and reviewable, while retaining the `absent at synchronization` history and the explicit `implementation remains blocked` boundary.
  2. Keep the Tasks 2–4 source-versus-target inventory and all historical 2026-09-21/22/23 entries intact; add only new dated text.
  3. Append an additive dated `### 2026-09-24 — Phase-plan delivery status synchronized` change-control entry mirroring the index entry and naming the four verified paths. State explicitly that authoring does not authorize execution and does not satisfy structural-foundations or separately reviewed source-correction gates (including Correction A).

- [ ] **Step 4: Acceptance language for Correction B**

  Correction B passes only when: all four path checks above are recorded present and in-scope; both documents carry the additive 2026-09-24 entries without deleting or rewriting historical absence text; both documents state that plan authoring authorizes neither execution nor gate satisfaction; and `git diff --check` plus `git status --short` show only the two intended docs files touched. Any missing path, rewritten history, or execution-authorizing wording keeps the gate open.

---

## Conditional signed-commit boundary (only if later separately requested)

Do not commit from plan authoring. If — and only if — the maintainer later explicitly requests commits for a passed correction, stage only that correction's intended files on an in-place non-main branch:

```powershell
# Correction A (only after its stop/go passes):
git add -- app/src/main/java/com/justme/xtls_core_proxy/vpn/XrayVpnService.kt
git commit -S -m "Correct unprotected-timeout stop effect-time boundary" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

```powershell
# Correction B (only after its stop/go passes):
git add -- docs/superpowers/plans/2026-09-04-auto-failover-engine-migration-plan.md docs/superpowers/plans/2026-09-04-auto-failover-engine-migration-contract-recovery-plan.md
git commit -S -m "Synchronize engine migration plan delivery status" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

Stage only the listed files after inspecting them; never include pre-existing user edits, specs, generated artifacts, or unrelated plans. Signing failure stops here; do not modify external configuration, amend, or fall back unsigned. No push, merge, deploy, or publication is implied.

## Acceptance Criteria

- [ ] Correction A placement is after Phase 1 and before Phase 2, with the exact file/function, before/after STOP_SERVICE shape, preservation rules, and review/device gates recorded; no production change is made by this plan.
- [ ] Correction B records the exact four path checks, bounded status/acceptance wording, and additive dated change-control shape with history preserved; no document is modified by this plan.
- [ ] Both corrections state that authoring does not authorize execution and does not satisfy structural-foundations or source-correction gates.
- [ ] No new engine member, result, policy, seam, fault hook, timing rule, or packet-behavior claim is introduced.
- [ ] No Kotlin production or test change is claimed as made or verified.

## Change Control

### 2026-09-24 — Corrections plan authored (planning only)

- **Previous decision:** Phase-1 rotation plan carried the unlocked STOP_SERVICE warning/error/stop baseline with the final effect-time boundary deferred to a separately gated correction, and the index/recovery plan retained `absent at synchronization` status text after the four phase plans were authored.
- **Replacement decision:** This plan records Correction A (effect-time lock+epoch prerequisite between Phase 1 and Phase 2, exact helper arm and evidence limits) and Correction B (bounded additive docs synchronization with four path checks) as separately reviewable gates. No file besides this plan is changed by this task.
- **Reason:** The Phase-2 timer migration explicitly requires the effect-time correction already landed and excludes making it; without a separately reviewed prerequisite the migration would either regress to the unlocked baseline or conceal the correction inside callback migration. The stale absence text would otherwise misreport delivery status while implying nothing about execution authorization.
- **Affected sections:** All sections of this corrections plan. Historical index/recovery entries remain untouched until Correction B executes.
