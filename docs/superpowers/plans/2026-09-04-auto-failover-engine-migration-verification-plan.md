# Auto-Failover Engine Cross-Phase Verification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **Not authorized for execution now.** This is the final verification plan, not permission to run tests, change a device, implement code, or publish a release.

**Goal:** Verify the completed engine migration across JVM dispatch, actual service boundaries, Android lifecycle, packet capture, release runtime, and maintainer documentation without introducing new behavior.

**Architecture:** Reuse the exact engine/interface/fake established by rotation and extended through give-up/re-arm and monitor/settings phases. Test the interactions across those phases, audit every integration point, and assemble evidence from existing component tests plus real service/device review. This phase cannot retroactively waive a missing earlier phase gate or introduce the first engine test/fake.

**Tech Stack:** Kotlin, JUnit4, AndroidJUnit4, Gradle debug/release/lint tasks, ADB/device QA, packet capture, Markdown maintainer docs.

**Spec:** `docs/superpowers/specs/2026-09-04-auto-failover-engine-migration-design.md`. Read the synchronized engine index and all three implementation plans at their established `2026-09-04-auto-failover-engine-migration-*-plan.md` paths.

## Global Constraints

- Written-spec approval permits plan authoring only. Execute this plan only after separate authorization and completed structural-foundations index/owner/capture/verification plus engine rotation/give-up-rearm/monitor gates.
- Require the independently reviewed stale-reservation cleanup, stale notice, post-commit settlement isolation, and timeout publication corrections. Record source review versus device reproduction separately; an approved target is not implementation evidence.
- No new engine/interface/fake, policy, scheduler, owner, descriptor, probe target, connection state, or retry rule. A new correctness finding stops the affected gate and returns to the maintainer; do not silently expand the refactor.
- Engine-order scripts do not prove host locking, resource ownership, notification truth, monitor rebuild, receiver lifetime, or packet safety. Source review and actual service/device evidence cover those facts.
- Full-switch underlying-interface packet capture is the packet-policy oracle. Logs corroborate diagnostic order only. Exclude proxy-server endpoint traffic; test a tester-chosen plain HTTP destination, never the watchdog target.
- Unavailable device/release/fault-injection evidence is an open gate, not a pass. No final completion claim with skipped mandatory scenarios.
- Execute heavyweight Gradle operations serially, one command after the prior one completes. Do not run `gomobile bind` or replace the generated AAR to work around a failure without approval.
- Preserve device app/data; never use `-PuninstallAfterTest`, clear app data, or uninstall a data-bearing installation. Obtain approval for any device configuration changes and restore them afterwards as agreed.
- No generated `app/libs/*.aar`, geo assets, `local.properties`, `.idea`, credentials, endpoints, GPG/global Git/shell/IDE/user-level configuration changes. Do not copy unredacted core logs into reports.
- Release build/runtime verification is not deploy/release publication authorization. Do not upload artifacts, push, or publish a release.
- Commits require a separate explicit request, in-place non-main branch, and `git commit -S`. Stop on signing failure; do not amend, bypass hooks/signing, change external settings, or retry unsigned.

## Review Focus

1. **Phase transitions leave an old callback or duplicate scheduler:** audit final caller map and combine rotation/re-arm/healthy/settings calls on one engine below.
2. **Fixture success hides a missing production effect:** classify every claim by evidence layer; inspect actual host and stop at missing device/fault evidence.
3. **Old epoch survives commit or timer decision while a new session starts:** cross-phase fake checks original arguments; real-host/device pause points check no new-session mutation or error surface.
4. **Release minification or bridge invocation fails although debug is green:** require actual release build, installation without data loss, and affected-path runtime exercise.
5. **Docs imply all TUNs forward traffic or a fake proves no escape:** update explicit ownership/evidence and outcome wording only after real execution; never collapse blackhole, live, and UNPROTECTED.

## File structure

| File | Responsibility |
|---|---|
| Extend `app/src/test/java/com/justme/xtls_core_proxy/vpn/FailoverEngineTest.kt` | Cross-phase call/epoch regression using existing fake |
| Read `app/src/main/java/com/justme/xtls_core_proxy/vpn/FailoverEngine.kt`, `TunnelHost.kt`, `FailoverContract.kt`, `XrayVpnService.kt` | Exact seam/caller/exception audit; no new production feature |
| Modify `docs/features/auto-failover.md` | Final engine ownership and evidence limits; accurate implemented status |
| Modify `AGENTS-DEVELOPMENT.md` | Extension-point and testing guidance for the implemented engine |
| Modify `docs/qa/auto-failover-qa.md` | Existing QA result evidence and accurate target wording, no invented scenario IDs |

Keep production correction work outside this verification plan. If the final audit finds a real defect, identify its owning phase/prerequisite and obtain a reviewed correction rather than hiding it in a docs or test commit.

---

### Task 1: Check the complete seam and add one cross-phase regression

**Files:** Extend `app/src/test/java/com/justme/xtls_core_proxy/vpn/FailoverEngineTest.kt`; read the production files above and `app/src/test/java/com/justme/xtls_core_proxy/vpn/RecordingTunnelHost.kt`.

**Interfaces:** Consumes all four exact engine entries and the strict `RecordingTunnelHost` from rotation. Produces cross-phase argument/order coverage and an audited final caller map, not a new service simulator.

- [ ] **Step 1: Verify earlier completion records before running anything**

Read the per-phase review/test/device records, including full-switch establish-first evidence and independently approved source corrections. Do not treat unchecked planning boxes, proposed code, or an agent summary as proof of execution. If any earlier gate is open, stop here and name it.

- [ ] **Step 2: Add this test to the existing engine test class**

Imports already used by earlier plans: JUnit4 assertions/`@Test`, `kotlinx.coroutines.test.runTest`, `db.Profile`, and `failover.FailoverSettings`. The test intentionally scripts semantic results rather than inventing a parallel owner implementation.

```kotlin
@Test fun recoverySettingsAndNewSessionReuseOnlyTheSuppliedEpoch() = runTest {
    val host = RecordingTunnelHost()
    val engine = FailoverEngine(host)
    val settings = FailoverSettings(true, 15000L, 5000L, 2, 3, 600000L)
    val firstEpoch = 300L
    val newEpoch = 301L
    val current = Profile(id = 10L, name = "current", config = "{}")
    val candidate = Profile(id = 20L, name = "candidate", config = "{}")

    host.responds("beginRotation", RotationStartResult.ThrashCapDenied(GiveUpDisposition.BLACKHOLE_REARMED))
    engine.rotate(firstEpoch, RotationRequest.UnhealthyProbe)
    host.responds("admitFailoverRearm", FailoverRearmAdmission.Admitted)
    host.responds("reconcileAuthoritativeFailoverSettings", SettingsReconciliationResult.Monitoring(
        MonitorChange.STARTED, UnprotectedRearmRestoration.NOT_NEEDED,
    ))
    engine.onRearmTimer(firstEpoch, FailoverRearmRequest.RecheckMonitor)
    host.responds("settleHealthyProbe", HealthyProbeResult.GiveUpCleared)
    assertEquals(HealthyProbeResult.GiveUpCleared, engine.onHealthyProbe(firstEpoch))
    host.responds("reconcileFailoverSettings", SettingsReconciliationResult.Disabled(
        MonitorChange.STOPPED, DisableGiveUpEffect.None,
    ))
    engine.onFailoverSettings(firstEpoch, settings.copy(enabled = false))

    host.responds("beginRotation", RotationStartResult.Reserved(current.id))
    host.responds("loadRotationPool", RotationPoolResult.Available(current, listOf(candidate), emptySet()))
    host.responds("prepareRotationCandidate", CandidatePreparationResult.Prepared(RotationBridgePreparation.ESTABLISHED))
    host.responds("bringUpRotationCandidate", CandidateBringUpResult.Started)
    host.responds("commitRotation", RotationCommitResult.Committed(DeferredKillReplay.PENDING))
    host.responds("recordCommittedActiveProfile", CommittedSessionEffectResult.Applied)
    // Fixture assumes the session was superseded between these independent effects.
    host.responds("postCommittedSwitchNotice", CommittedSessionEffectResult.StaleSession)
    host.responds("reconcileAuthoritativeFailoverSettings", SettingsReconciliationResult.StaleSession)
    host.responds("enqueueDeferredKillReplay", DeferredKillEnqueueResult.Enqueued)
    engine.rotate(firstEpoch, RotationRequest.CandidateRetry)
    host.responds("beginRotation", RotationStartResult.StaleSession)
    engine.rotate(firstEpoch, RotationRequest.CandidateRetry)
    val oldCalls = host.calls.size

    host.responds("settleHealthyProbe", HealthyProbeResult.EpisodeFailuresCleared)
    host.responds("reconcileFailoverSettings", SettingsReconciliationResult.Monitoring(
        MonitorChange.UNCHANGED, UnprotectedRearmRestoration.NOT_NEEDED,
    ))
    assertEquals(HealthyProbeResult.EpisodeFailuresCleared, engine.onHealthyProbe(newEpoch))
    engine.onFailoverSettings(newEpoch, settings)
    assertEquals(listOf(
        "beginRotation", "admitFailoverRearm", "reconcileAuthoritativeFailoverSettings",
        "settleHealthyProbe", "reconcileFailoverSettings",
        "beginRotation", "loadRotationPool", "prepareRotationCandidate", "bringUpRotationCandidate",
        "commitRotation", "recordCommittedActiveProfile", "postCommittedSwitchNotice",
        "reconcileAuthoritativeFailoverSettings", "enqueueDeferredKillReplay", "beginRotation",
        "settleHealthyProbe", "reconcileFailoverSettings",
    ), host.calls.map { it.name })
    assertTrue(host.calls.take(oldCalls).all { it.epoch == firstEpoch })
    assertTrue(host.calls.drop(oldCalls).all { it.epoch == newEpoch })
    assertEquals(listOf(candidate.id), host.calls.single { it.name == "recordCommittedActiveProfile" }.args)
    assertEquals(listOf(settings), host.calls.last().args)
    assertFalse(host.calls.any { it.name == "failRotation" })
    host.assertConsumed()
}
```

The script's supersession is a fixture assumption, not a reproduction of Android interleaving. It proves one reusable engine cannot cache a prior epoch or suppress the deliberately stale deferred-kill enqueue after a rejected notice. The queued service kill remains responsible for actual epoch checking.

- [ ] **Step 3: Run the cross-phase regression and focused component suite serially**

```powershell
./gradlew.bat :app:testDebugUnitTest --tests "*.FailoverEngineTest"
./gradlew.bat :app:testDebugUnitTest --tests "*.SessionLifecycleDecisionTest" --tests "*.SessionLifecycleRotationTest" --tests "*.TunnelOwnerTransitionsTest" --tests "*.TunCapturePlanTest" --tests "*.TunCapturePlanApplicatorTest"
./gradlew.bat :app:testDebugUnitTest --tests "*.FailoverDecisionTest" --tests "*.FailoverEpisodeDecisionTest" --tests "*.FailoverPoolResolverDispatchTest" --tests "*.TunnelHealthMonitorTest" --tests "*.HealthProbeSchemeTest"
```

Expected PASS. The cross-phase test is regression coverage for completed work, not a claim a new behavior was implemented RED/GREEN. If any structural test class is missing, the Phase-0 prerequisite is not proven: stop rather than removing its filter to obtain green.

- [ ] **Step 4: Audit the final production seam and every caller**

Use a read-only File Searcher agent for repository-wide indexing, then inspect the located specific symbols. Required audit matrix:

| Item | Expected final state |
|---|---|
| Contract | Exactly one internal `TunnelHost`, 17 members; four exact engine entries; all request/result names/fields match approved spec |
| Engine fields/imports | Only immutable host reference; no Android, DAO, owner, clock, scope, timer, cache, or captured epoch |
| Rotation | Exactly one service launcher on existing lane; unhealthy/candidate retry/unprotected recovery use distinct request types; one candidate per invocation |
| Pool | Only `FailoverPoolResolver.resolve(dao, current)` derives pool; lookup null differs from throw; locked identity check precedes failed-ID snapshot |
| Resource authority | One `tunnelOwner`; no `tunInterface`, `tunInterfaceKind`, `rotationBridgeInterface`, or `TunInterfaceKind` shadow fields/types in active production |
| Establish-first | Bridge retained on null/throw; install before one bounded old close; protected diagnostics cannot skip operational startup |
| Failure/commit | Actual give-up invocation only is marker-wrapped; original cause propagates; no `failRotation` catch around commit/follow-ups |
| Post-commit | Real effect-time epoch check for persistence/notice; independent effects/reporting; optional stale replay enqueue leaves label unread |
| Scheduler | One typed private scheduler and one delayed engine timer callback; no Boolean overload or duplicate inline action path |
| Healthy/settings | Startup/collector/revive/monitor call engine once in their original contexts; private helper implementations are used only through adapter |
| Receiver/kill-switch | Still service-owned; one screen receiver, OR lifetime, main-thread hint plus locked check; no engine screen entry |
| Stop/restart | Synchronous stop; UNPROTECTED-only recovery restart gate; contained Reconnect still uses `state/ReconnectFlow` |
| Tests | One recording fake, strict scripted answers; engine tests never claim descriptor/packet evidence; fixture result guard is not a production policy simulator |

Search leftovers by symbol as data, not automatic deletion candidates. Old names in historical change control, source-baseline descriptions, or explicit negative checks are legitimate. Old active call sites or competing policies are not. Do not use declaration-column anchors that miss internal/private/top-level declarations. Do not add new source-file-reading JVM tests just to grep these facts; if a later approved test reads files, `app/build.gradle.kts` must declare those files as inputs.

---

### Task 2: Complete debug, instrumentation, packet, and release evidence

**Files:** No production implementation edits. Record final outcomes only in the existing Result log in `docs/qa/auto-failover-qa.md` during Task 3.

**Interfaces:** Consumes completed earlier phase gates, focused tests, reviewed final adapter, connected consented device, and existing QA scenarios. Produces actual test/build/device evidence with no skipped mandatory acceptance claims.

- [ ] **Step 1: Run full debug verification serially**

```powershell
./gradlew.bat :app:testDebugUnitTest
./gradlew.bat :app:lintDebug
./gradlew.bat :app:assembleDebug
./gradlew.bat :app:check
```

Wait for each command to finish; read complete output and report exact failing task/test if any. `UP-TO-DATE` is not new execution evidence for a file-driven guard unless its task inputs include the changed file. Do not alter local signing, SDK, generated AAR, or global caches to work around a blocked run. Report the blocker.

- [ ] **Step 2: Prepare the consented device without losing app data**

Run `adb devices -l` to identify the agreed device. Confirm the installed app/signature and data-preserving connected-test setting. Do not uninstall to solve a signature mismatch. On a Samsung/One UI device, obtain permission for awake/animation settings and record existing values before changing them:

```powershell
adb shell settings get global window_animation_scale
adb shell settings get global transition_animation_scale
adb shell settings get global animator_duration_scale
adb shell settings get global stay_on_while_plugged_in
```

After device-setting approval, use the existing One UI test preparation (`KEYCODE_WAKEUP`, `svc power stayon true`, zero animation scales), then restore the recorded settings after testing. Do not overwrite a device setting without permission merely to avoid a flake. If the device is not available or changes are not authorized, record the exact missing instrumentation gate.

- [ ] **Step 3: Run instrumentation with app preservation**

```powershell
./gradlew.bat :app:connectedDebugAndroidTest
```

Do not add uninstall flags. Relevant existing coverage includes Room `FailoverPoolResolverTest`, settings persistence, `FailoverNotificationTest`, `VpnNotificationsTest`, and the structural `ProfileDaoManualQueryTest`; these are not replacements for packet/lifecycle QA. To rerun one failing device class use `-Pandroid.testInstrumentationRunnerArguments.class=<actual FQN from that file>`, never JVM `--tests`. Record the actual class selected rather than claiming a whole-suite pass from one class.

- [ ] **Step 4: Execute the final combined device and packet matrix**

Use the existing `docs/qa/auto-failover-qa.md` scenario names and result log, not newly invented numbered tests:

- Tests 1–6: rotation, fail-closed/live give-up, thrash cap, all UNPROTECTED recovery/disable cases and notification Stop.
- Test 7 QS tile from BLACKHOLED; Tests 8–11 startup/monitor/kill-switch/live settings; Test 14 T10b residual; Test 16 Connect from UNPROTECTED; Test 17 offline guard; Test 18 teardown hygiene.
- Tests 19a–19c contained Reconnect; Tests 20–22 Reconnect interleaving; Test 24a–24d foreground/leave during switch or revive.
- Test 23 in full: routine and exhausting pool, explicit Stop, replacement establishment and old-bridge cleanup, all supported fault boundaries from earlier phases.

For every required race/fault, record whether it was actually driven. Use a debugger/local non-shipping test build where needed; no persistent production fault toggles without a separately approved seam. A missing reproduction keeps its gate open.

Packet capture must span the full operation on the underlying interface. Filter device traffic and exclude server A/B endpoints. Generate traffic to a tester-selected plain HTTP destination other than `ConfigBuilder.HEALTH_PROBE_TARGET_URL`. Pass only if no destination packet escapes during replacement establishment and old-bridge cleanup. Do not infer safety from bridge/live log adjacency, an adopted owner in a fake, or HTTP failure alone.

Verify the diagnostic sequence and physical owner outcomes separately: stable bridge identity across null/throw; no false live-established or release-audit on failure; one install then bounded old close; optional close warning; protected append failure continues operational startup; protector/start/final-core-log failure rolls back the partial live owner; give-up adopts or establishes containment honestly. User-visible live/blackhole/unprotected copy must match actual packet truth.

- [ ] **Step 5: Build and exercise release runtime**

```powershell
./gradlew.bat :app:assembleRelease
```

Require success before full-feature completion. Inspect the emitted APK names under `app/build/outputs/apk/release/`; do not assume a filename or expose signing material. Install the matching signed release APK on the consented compatible test device without uninstall/data loss; if its signature differs from the data-bearing installation, stop and arrange a separate consented test installation rather than wiping data. Execute existing QA Test 12 Release APK plus routine rotation, exhausting pool, recovery timer, monitor/receiver pairings, notification/tile Stop, and full-switch capture on that actual release build.

R8/reflection and native bridge behavior are runtime facts: a green release build alone does not prove them. Do not regenerate the AAR or add broad keep rules without a concrete reviewed failure. Release upload/publication is outside this plan.

- [ ] **Step 6: Adjudicate completion honestly**

Record command outcomes, actual build/device, reviewer evidence, scenario observations, packet capture span/result, and every missing gate. A source-reviewed race is not a device reproduction; a scripted fake result is not a source guard; a release APK that was not exercised is not release-runtime verification. Keep the feature open if any mandatory gate is unavailable.

---

### Task 3: Update maintainer references and perform final scope review

**Files:** Modify only `docs/features/auto-failover.md`, `AGENTS-DEVELOPMENT.md`, and `docs/qa/auto-failover-qa.md`; extend the existing test file only as Task 1 specified.

**Interfaces:** Consumes actual implemented architecture and the evidence from Tasks 1–2. Produces accurate maintained documentation and QA result records; no changes to the approved spec's contract or production behavior.

- [ ] **Step 1: Update the feature document's engine ownership section with this substance**

Use the existing auto-failover sections, replacing the deferred-engine wording only after implementation is verified. Preserve accepted-policy notes and historical source/target chronology.

```markdown
### Passive engine and semantic host

`vpn/FailoverEngine` sequences one candidate per rotation invocation through the exact internal 17-member `TunnelHost`. `XrayVpnService` implements it with a private `ServiceTunnelHost`; the service retains lifecycle locking, epochs, the single tunnel owner, Android resources, notifications, episode fields, timer, settings collector, monitor, and shared screen receiver.

The engine stores only its immutable host reference. Rotation uses the existing serialized `tunnelOpScope` launcher; re-arm remains a direct delayed `serviceScope` callback, healthy remains a direct monitor callback, and startup/settings observation retains its existing caller context. Requests carry explicit epochs. Candidate retry is enqueued once by the host, not walked in an engine loop.

The mechanical CONNECTED write is the rotation commit. Pre-commit failure handling ends before it. Post-commit effects are independent, current-session-checked where required, and cannot reclassify a successful rotation through give-up. Cancellation propagates. A failure after entry into give-up propagates without a second settlement; returned completion means required effects finished.

JVM recording-host tests verify engine order, arguments, results, and exceptions. They do not prove service locks, descriptor lifetime, monitor/receiver effects, notifications, or packet safety. Those require real-host review, component/device evidence, and full-switch underlying-interface packet capture. Live, unread-blackhole, and UNPROTECTED outcomes remain distinct on every user-facing surface.
```

Verify every sentence against final source. Structural documentation should already state implemented establish-first after Phase 0; do not rewrite structural policy or claim missing packet evidence. Remove only active statements saying engine extraction is still a deferred follow-up once it actually is complete.

- [ ] **Step 2: Update development extension and test guidance**

Add/update the Auto-Failover Extension Points row and its explanation with this text:

```markdown
| **Engine orchestration** | `vpn/FailoverEngine.kt`, `vpn/TunnelHost.kt`, `vpn/FailoverContract.kt` | `rotate`, `onRearmTimer`, `onHealthyProbe`, `onFailoverSettings` | Passive ordering only. Preserve the exact semantic host contract, explicit epochs, one candidate per invocation, pre/post-commit boundary, and service-owned resource/episode/timer/collector/receiver state. Extend the existing `FailoverEngineTest` and `RecordingTunnelHost`; do not create a second service simulator or treat fake results as packet evidence. |
```

Replace the old engine-as-deferred-follow-up sentence with:

```markdown
The engine seam is implemented behind the private service adapter. Policy remains in the existing lifecycle, failover-decision, episode-decision, and pool-resolver components. Changes to the semantic contract require a reviewed spec/plan update; changes to `vpn/` require human review and the affected phase's device/packet checks. `stopVpn` remains synchronous and must never gain an await.
```

Do not alter unrelated extension points, imported routing policy, release signing, or global configuration guidance.

- [ ] **Step 3: Correct the QA target wording and record only observed results**

In existing Test 11, replace the outdated claim that Ping Test's editable URL is the watchdog target with:

```markdown
The watchdog uses the fixed `ConfigBuilder.HEALTH_PROBE_TARGET_URL` with matching routing carve-outs. The user-editable Ping Test target is separate; changing it must not retarget the live-tunnel health monitor.
```

In the existing Result log, record the actual results from this phase and the earlier phase gates. For each scenario, include build/device, date, pass/fail/not-run, reproduction/fault boundary, and sanitized evidence location. These are record fields to populate from observations, not fabricated rows to paste now. A not-run or undrivable mandatory case remains explicitly open. Do not store private endpoints, keys, raw profile JSON, or unredacted core logs.

- [ ] **Step 4: Run final mechanical, stale-reference, and scope checks**

Run `git diff --check` and inspect `git status --short`/`git diff --stat`. Use the read-only File Searcher for a scoped active-reference sweep of the changed feature/development/QA docs and production `vpn/` integration. Expected: no competing engine/interface, old active callback paths, old Boolean timer signature, or active “engine not implemented” claim. Historical change control remains labeled historical.

If any new verification test reads source or resource files, confirm its task-input declaration in `app/build.gradle.kts`; this plan introduces no such new test. Do not change build configuration merely for a prose audit. Re-run `:app:testDebugUnitTest` for test changes, and any other task made stale by actual code changes. Report what actually ran.

- [ ] **Step 5: Human sign-off and conditional signed final commit**

Ask the maintainer to review final evidence and all `vpn/` changes, including the separately reviewed prerequisites. Deliver the three phase completion records and the final release/device/packet status. If any mandatory evidence is missing, state the exact open gate and do not claim full completion or merge readiness.

Only after all gates pass and commits were separately requested:

```powershell
git add -- app/src/test/java/com/justme/xtls_core_proxy/vpn/FailoverEngineTest.kt docs/features/auto-failover.md AGENTS-DEVELOPMENT.md docs/qa/auto-failover-qa.md
git commit -S -m "Verify failover engine migration and update maintainer docs" -m "Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

Stage only this phase's intended changes after inspecting them; never include pre-existing user edits wholesale. Do not commit specs, generated/local artifacts, or unrelated plans. Stop on signing failure and report it. No push, merge, deploy, or publication is implied by finishing this plan.
