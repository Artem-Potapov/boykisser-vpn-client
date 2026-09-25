# Auto-Failover Structural Foundations Design

**Date:** 2026-09-03  
**Status:** Establish-first handover amendment approved in discussion on 2026-09-22; amended written spec awaiting maintainer review  
**Scope:** First of two architecture/QoL refactors, with the explicitly approved handover behavior change below  
**Follow-up:** A separate, incomplete design will cover `FailoverEngine` and `TunnelHost`

**Implementation status:** This is the approved target, not a description of an implemented change. Production still releases the bridge before replacement establishment. The four structural plans, authoritative documentation, and the handover inventory in the 2026-09-04 engine contract-recovery plan await alignment after maintainer review of this written amendment. Do not execute any of those documents from their older handover instructions. This documentation task authorizes no production/test edits, plan execution, engine implementation, or commits. The engine contract-recovery gate itself remains binding.

## Summary

This design removes structural ambiguity around TUN ownership and configuration. The 2026-09-22 amendment expressly changes candidate handover from release-first to establish-first and isolates its post-establishment diagnostics from bring-up failure handling. This is a bounded security-motivated behavior change, not a claim of literal behavior preservation. Other lifecycle policies remain unchanged. The design also consolidates one genuinely shared connection-state predicate and removes duplicated manual-profile SQL.

The chosen design:

1. Replaces `tunInterface`, `tunInterfaceKind`, and `rotationBridgeInterface` with one generic sealed owner for the current service TUN role, plus a bounded local cleanup reference during successful replacement.
2. Introduces an immutable, per-establishment `TunCapturePlan` and one shared `VpnService.Builder` applicator for real and unread tunnels.
3. Centralizes only the exact active-session membership set shared by Main UI highlighting, Quick Settings rendering, and the tile Stop wrapper.
4. Retains both manual-profile DAO API shapes while sharing their compile-time SQL constant.
5. Preserves current locking, session epochs, containment outcomes, user-visible states, and split-preference timing while changing rotation handover to establish the replacement before releasing the bridge.
6. Retains the existing close-warning and release-audit diagnostics, but publishes them after the replacement TUN is established rather than in the establish boundary.

This is a structural prerequisite for a later, independently designed behavior-preserving `FailoverEngine` migration.

## Problem Statement

Auto-failover behavior is protected by substantial lifecycle logic, but several representations make that logic harder to inspect and safely evolve:

- `XrayVpnService` stores TUN ownership across two nullable descriptor fields plus a separately maintained kind tag.
- Real and unread TUNs duplicate capture-affecting Builder configuration.
- Several callers manually repeat the same four-state “active session” set, while nearby callers intentionally use different state sets.
- `ProfileDao` repeats identical manual-profile SQL for reactive and snapshot APIs.
- The future `FailoverEngine` extraction needs a stable structural boundary before service sequencing can move behind a semantic host seam.

The refactor must reduce those risks while preserving all behavior outside the explicitly approved candidate-handover and diagnostic-failure changes. No packet escape is product security policy, not a negotiable test criterion. Shortening an interface-free interval is not equivalent to maintaining containment; nor can a green log sequence excuse a packet observed outside the selected proxy path.

## Scope

### In scope

- Replace the current parallel interface fields and stored kind tag with one sealed owner.
- Represent these roles explicitly:
  - no interface;
  - live proxy tunnel;
  - unread rotation bridge;
  - unread blackhole containment.
- Expose a derived, unstored pure role to lifecycle decision code.
- Establish a rotation candidate before releasing its unread bridge; retain the bridge on unsuccessful replacement creation.
- Represent the successful handover's temporary old-descriptor cleanup responsibility locally, without a second owner field or new role.
- Defer existing close/release diagnostics until after the new TUN-established marker and isolate post-establishment diagnostic failures from bring-up failure handling.
- Extract immutable, per-establishment capture-plan construction.
- Apply the plan through one shared Builder configuration path.
- Centralize the exact `{CONNECTING, CONNECTED, PAUSED, BLACKHOLED}` membership set shared by active-profile highlighting, tile rendering, and tile Stop eligibility.
- Keep intentionally different connect, Disconnect, tile-label, notification, and lifecycle semantics separate.
- Deduplicate manual-profile SQL while retaining both `Flow` and suspend snapshot DAO APIs.
- Add focused JVM, instrumented, and device verification.
- Correct every tracked authoritative document and current planning inventory made stale by the approved handover target.

### Out of scope

- Creating or migrating sequencing into `FailoverEngine`.
- Defining the complete future `TunnelHost` interface.
- Moving lock ownership, session-epoch checks, dispatcher boundaries, or serialized operation-lane ownership.
- Changing kill, revive, stop, reconnect, monitor, re-arm, candidate selection, episode accounting, or give-up sequencing.
- Making `stopVpn` suspend or awaiting asynchronous work from it.
- Widening `shouldRestartForRecovery` beyond `UNPROTECTED`.
- Changing `BLACKHOLED` live/stoppable semantics or `ReconnectFlow` coordination.
- Replacing `FailoverPoolResolver` as the only pool-derivation seam.
- Release-before-establish candidate handover; the approved rotation handover establishes the replacement while retaining the bridge and releases the bridge only after successful replacement establishment.
- Session- or rotation-scoped capture-plan caching.
- Routing, DNS, ConfigBuilder, or split-tunnel policy changes.
- Latency-ordered failover, curated pools, passive byte-counter detection, network-change probes, routing-precedence UI, or other deferred features.
- Retrofitting old historical specs, plans, or raw briefs.

If implementation uncovers a correctness defect, it must be reported and designed separately rather than silently expanding this refactor.

## Current Ownership and Data Flow

### Interface ownership

`XrayVpnService` currently has three related pieces of mutable state:

- `tunInterface`: the live proxy TUN or adopted/established unread containment TUN;
- `tunInterfaceKind`: `NONE`, `LIVE_PROXY`, or `UNREAD_CONTAINMENT`;
- `rotationBridgeInterface`: an unread bridge held during a reserved rotation.

The kind describes only `tunInterface`; the bridge remains a separate ownership channel. Correctness therefore depends on synchronized updates across nullable fields and the kind tag.

Current high-level transitions are:

1. `bringUpTunnel` establishes a real TUN and, after the approved handover, stores the candidate in the service owner as `LiveProxy`, registers socket protection, and starts Xray.
2. Rotation tears down the live TUN and calls `establishRotationBridgeLocked`, storing an unread descriptor in `rotationBridgeInterface`.
3. During candidate handover, the bridge remains authoritative while `Builder.establish()` is attempted. A successful replacement installs the new live owner first, then closes the old bridge through a bounded local cleanup handle; a null/throw leaves the bridge owner unchanged.
4. The close warning and release audit are emitted after the replacement `TUN established` log, before protector registration and Xray startup. They are deferred diagnostics, not prerequisites for replacement establishment or containment recovery.
5. If candidate establishment fails, failover retains or restores unread bridge containment before continuing the episode.
6. Give-up either:
   - keeps a live proxy TUN;
   - adopts the existing bridge as unread blackhole containment;
   - establishes a new unread blackhole when no bridge exists; or
   - reports `UNPROTECTED` when containment cannot be established.
7. Kill, stop, revoke, destruction, and other terminal exits release every authoritative owner and any pending cleanup handle according to the settled handover outcome.

### Builder construction

There are two capture configuration paths:

- `XrayVpnService.bringUpTunnel` for a real proxying tunnel;
- `XrayVpnService.establishUnreadTunnelLocked` for rotation bridge and blackhole containment.

Both apply:

- application-name session;
- configured MTU;
- IPv4 address `10.7.0.1/32`;
- default IPv4 route;
- DNS server `1.1.1.1`;
- IPv6 address `fd00:1:fd00:1::1/128` and default route `::/0` unconditionally, including when Xray IPv6 is disabled, so IPv6 cannot escape the VPN;
- IPv6 DNS server `2606:4700:4700::1111` only when Xray IPv6 is enabled;
- the current split-tunnel plan;
- current missing-package skip-and-log behavior.

The live path additionally builds runtime config and geo assets, registers socket protection, and starts Xray. The unread path intentionally performs none of those operations.

### Connection-state predicates

The following state set is repeated:

- `CONNECTING`
- `CONNECTED`
- `PAUSED`
- `BLACKHOLED`

It currently drives active-profile presentation, tile Stop eligibility, and tile active rendering.

Nearby state sets are intentionally different:

- `connectAction` permits ordinary Connect from `DISCONNECTED` and `ERROR`, Reconnect from `BLACKHOLED`, and no start action from live transition states.
- Main UI Disconnect visibility also includes `ERROR`.
- CONNECTING indicators, notification lines, labels, reconnect sequencing, and lifecycle decisions have narrower or richer state-specific behavior.

There is no single truthful `isServiceRunning` predicate because `ERROR` can represent a still-live unprotected service or a service already stopping.

### Manual-profile DAO APIs

`ProfileDao.getManual()` and `ProfileDao.getManualList()` both use:

```sql
SELECT * FROM profiles WHERE subscriptionId IS NULL ORDER BY id ASC
```

The APIs serve distinct contracts:

- `getManual()` returns `Flow<List<Profile>>`;
- `getManualList()` returns a suspend snapshot used by `FailoverPoolResolver`.

The duplicated SQL is debt; the API distinction is intentional.

## Chosen Architecture

### Generic sealed TUN owner

Introduce this Android-independent sealed owner:

```kotlin
internal sealed interface TunnelInterfaceOwner<out Handle> {
    data object None : TunnelInterfaceOwner<Nothing>
    class LiveProxy<Handle>(val handle: Handle) : TunnelInterfaceOwner<Handle>
    class UnreadRotationBridge<Handle>(val handle: Handle) : TunnelInterfaceOwner<Handle>
    class UnreadBlackhole<Handle>(val handle: Handle) : TunnelInterfaceOwner<Handle>
}
```

Production specializes the handle to `ParcelFileDescriptor`. JVM tests use token handles.

`XrayVpnService` stores exactly one field:

```kotlin
private var tunnelOwner: TunnelInterfaceOwner<ParcelFileDescriptor> =
    TunnelInterfaceOwner.None
```

These names are part of the design: `TunnelInterfaceOwner`, `None`, `LiveProxy`, `UnreadRotationBridge`, `UnreadBlackhole`, and `tunnelOwner`. A later rename is a design change and must follow the change-control rule.

### Derived role

Policy code consumes an exhaustive `TunnelRole` derived with a `when` over the sealed owner:

- `NONE`
- `LIVE_PROXY`
- `UNREAD_ROTATION_BRIDGE`
- `UNREAD_BLACKHOLE`

The role is never stored. It cannot drift from the descriptor owner.

The complete owner-dependent `SessionLifecycleDecision` migration is:

- `shouldFunnelRotationReservationRefusal` replaces `hasTunnel` and `hasRotationBridge` with `tunnelRole`;
- `containmentForGiveUp` replaces both presence booleans with `tunnelRole`;
- `shouldEstablishRotationBridge` replaces both presence booleans with `tunnelRole`;
- `shouldAbortRotationForMissingBridge` replaces `bridgeHeld` with the resulting `tunnelRole` while retaining `bridgeRequired`;
- `classifyGiveUpOutcome` classifies the resulting `tunnelRole` after the containment action, replacing both the pre-action `TunInterfaceKind` and `blackholeEstablished`;
- `shouldRestoreUnprotectedRearm` replaces `hasTunnel` with `tunnelRole`.

Post-containment outcome classification is exhaustive:

- `LIVE_PROXY -> CONTAINED_BY_LIVE_TUNNEL`;
- `UNREAD_ROTATION_BRIDGE` or `UNREAD_BLACKHOLE -> CONTAINED_BY_BLACKHOLE`;
- `NONE -> UNPROTECTED`.

Seeing `UNREAD_ROTATION_BRIDGE` after give-up still reports packet truth—an unread owned TUN contains traffic—but the ownership invariant and tests require the service to relabel it to `UnreadBlackhole` by adoption.

`deferredKillNoticeLabel` retains its semantic `tunnelStillUp` Boolean because it intentionally asks only whether any TUN still contains the app, not which owner role provides containment. Decision functions that do not inspect interface ownership retain their existing inputs. Pure decisions remain in `SessionLifecycleDecision`; Android descriptor mutations remain in the service.

### Production transition seam

Add an Android-independent `TunnelOwnerTransitions` API and use it for every production owner mutation. It contains no lock, close, Builder, epoch, session-state publication, or Android behavior. Its operations are:

- prepare real establishment from an existing owner and expected `SessionTunnelState`, retaining a rotation bridge until replacement establishment succeeds;
- install a new live proxy handle after a non-null real establishment result, while returning the prior bridge handle as a bounded cleanup handle;
- install a new rotation-bridge handle when the owner is `None`;
- install a new blackhole handle when the owner is `None`;
- adopt `UnreadRotationBridge` as `UnreadBlackhole` while retaining the same handle;
- release any open owner into `None`, returning the handle that the service must close.

Real-establishment preparation has exact role preconditions:

- `STARTING` and `REVIVING` require `None`;
- `ROTATING` requires `UnreadRotationBridge`;
- every other state/role combination is rejected as a lifecycle contract violation.

Preparation keeps `UnreadRotationBridge` authoritative while `Builder.establish()` is attempted. If establishment returns `null` or throws, the transition returns without changing the owner; Android's replacement contract leaves the existing interface and descriptor intact on unsuccessful creation. If establishment returns a non-null candidate, the transition installs `LiveProxy(candidate)` immediately and returns the old bridge only as a local cleanup handle; the old handle is never re-adopted, detached, or retried by raw integer fd. The service closes that handle once, records any close failure, and emits the existing close-warning and release-audit messages only after the `TUN established` log. Initial start and revive still require `None` and must not own a bridge.

The temporary cleanup handle is not a second authoritative owner. During successful Android replacement, two descriptors may be valid briefly, but only the newly installed `LiveProxy` receives service ownership. A close failure is non-fatal to the established replacement; deferred diagnostic failure must not enter the bring-up failure funnel or reclassify the live owner.

### Ownership invariants

- At most one TUN descriptor is the authoritative service owner at a time.
- Every descriptor held as authoritative ownership is classified by its sealed owner variant; the old bridge during successful replacement is a bounded local cleanup handle, not an owner variant.
- Only `LiveProxy` may be supplied to a running Xray instance.
- Protector registration is process-global, may remain installed in every owner state, and is not an owner-state invariant.
- Only a reserved rotation may create `UnreadRotationBridge`.
- Give-up adopts an existing bridge instead of creating a second unread TUN.
- Bridge adoption moves the same handle into `UnreadBlackhole` without closing it.
- Every bridge exit either adopts the handle, retains it through an unsuccessful replacement attempt, or installs the replacement owner before closing it through the local cleanup path.
- `PAUSED` and a fully stopped session own no authoritative TUN descriptor and retain no pending cleanup handle after terminal cleanup.
- `BLACKHOLED` may own `LiveProxy` or `UnreadBlackhole`, matching the existing contained give-up outcomes.
- `UNPROTECTED` owns `None`.
- Field replacement, candidate cleanup, role checks, and lifecycle mutations remain under the existing service lock.
- The rotation bridge remains authoritative until non-null replacement establishment; after promotion, the old bridge is never re-adopted, detached, or retried by raw integer fd.
- `onDestroy` retains its order: cancel `serviceScope`, cancel `tunnelOpScope`, synchronously call `stopVpn`, then call `super.onDestroy`.
- `onRevoke` synchronously calls `stopVpn` before `super.onRevoke`.
- notification/user `ACTION_STOP` remains serialized on `tunnelOpScope`; it does not move onto the main thread.
- `stopVpn` exhaustively releases every authoritative owner and any pending cleanup handle before its no-live-session early return. This is the terminal cleanup when cancellation prevents a queued rotation from resuming.

### Required transitions

- Normal start: `None -> LiveProxy`.
- Rotation reservation: `LiveProxy -> None -> UnreadRotationBridge`.
- Candidate handover success: `UnreadRotationBridge --establish--> UnreadRotationBridge; install LiveProxy(candidate); close old bridge via local cleanup handle`.
- Candidate handover failure: `UnreadRotationBridge --null/throw--> UnreadRotationBridge`, preserving the authoritative bridge and current recovery funnel.
- Give-up with bridge: `UnreadRotationBridge -> UnreadBlackhole` using the same handle.
- Give-up without bridge or live TUN: `None -> UnreadBlackhole` when establishment succeeds.
- Give-up retaining live capture: `LiveProxy -> LiveProxy`.
- Pause, stop, revoke, or terminal cleanup: any authoritative owner -> `None`, with the existing role-specific teardown and no pending cleanup handle left behind.

These transitions describe ownership only. They do not replace session-state, epoch, or sequencing decisions.

## Shared TUN Capture Plan

### Plan contents

Add an immutable `TunCapturePlan` containing every capture-affecting input:

- session name;
- MTU;
- addresses and prefix lengths;
- routes and prefix lengths;
- DNS servers;
- resolved `SplitTunnelPlan`.

The fixed addresses, routes, and DNS values must have one source of truth.

### Capture timing

A new plan is constructed for each establishment at the same points where the current paths read their inputs.

This deliberately preserves current behavior:

- split-tunnel preferences are not frozen for a session;
- they are not frozen for a rotation episode;
- a bridge and a later real candidate TUN may use different snapshots if the user changes preferences between establishments.

The structural guarantee is that both paths use the same plan schema and applicator. It is not a new timing guarantee.

Extraction must not move existing lock, suspension, or I/O boundaries except for the approved handover change. In particular, rotation must establish the candidate while the unread bridge remains authoritative, install the live owner before releasing the bridge, and emit the retained close/release diagnostics only after the replacement `TUN established` marker.

### Builder applicator

`TunCapturePlanFactory` creates the immutable plan from the session name, MTU, IPv6 setting, and resolved `SplitTunnelPlan`. It always includes both IPv6 capture entries and conditionally includes only the IPv6 DNS entry. `TunCapturePlanApplicator` configures a fresh Builder through `TunBuilderTarget`, whose complete surface is:

- session and MTU setters;
- address, route, and DNS additions;
- allowed-application and disallowed-application additions.

`VpnServiceTunBuilderTarget` is the Android adapter around `VpnService.Builder`. `TunBuilderTarget` must not expose `establish()`. The service retains explicit control of establishment so the security-critical handover remains visible and reviewable.

The same applicator is used by live and unread paths. This removes separate capture configuration bodies.

### Intentional differences outside the plan

The live path still:

- prepares Xray runtime config;
- prepares required assets;
- emits `Split tunnel allow-only mode enabled with no selected apps` only when the live caller observes empty allow-only preferences;
- keeps an existing rotation bridge authoritative while attempting real establishment, then installs the live owner before closing the prior bridge through a local cleanup handle;
- registers socket protection;
- starts Xray.

The unread path still:

- avoids runtime config and asset work;
- avoids socket protection and Xray startup;
- leaves the established descriptor unread so captured traffic stalls or drops.

These are lifecycle differences, not capture-plan differences.

`TunCapturePlanApplicator.apply` receives an `onMissingPackage(packageName)` callback. The live caller preserves `Split tunnel skipped missing package: <package>`; the unread caller preserves `Failover: <label> TUN skipped missing package: <package>`. The applicator owns package iteration and invokes the caller-owned callback when `TunBuilderTarget` reports `NameNotFoundException`.

### Handover ordering

The approved candidate handover is **establish-first**. All config, geo-asset, split-plan, and Builder preparation completes before the lifecycle-lock handover section. Geo-asset preparation remains before `Builder.establish()`; its existing `Using geofiles from …` log remains post-establishment.

Under the existing lifecycle lock and epoch checks:

1. Verify that the transition is `ROTATING` and the authoritative owner is `UnreadRotationBridge`. Initial start and revive retain their existing `None` precondition and do not enter this handover.
2. Keep the bridge owner and descriptor authoritative while calling `Builder.establish()`.
3. If `establish()` throws or returns `null`, leave the bridge owner unchanged. Do not emit a release audit, close or detach the bridge, or install a live owner. The existing rotation failure funnel decides bridge retention/restoration, retry, give-up, or `UNPROTECTED` handling.
4. If `establish()` returns a non-null candidate, install `LiveProxy(candidate)` immediately. The old bridge becomes a local cleanup handle only; it is not a second service owner, is never re-adopted, and its raw integer fd is never saved or retried.
5. Attempt one `close()` on the old bridge handle. Close failure remains non-fatal to the established replacement and is retained as deferred diagnostic state. No log, repository read, suspension, config work, notification, or other unrelated operation is inserted between candidate establishment, live-owner installation, and this close attempt.
6. Append `TUN established with fd=…` for the new live descriptor. Then append the existing close-warning text if needed and the exact existing release audit: `Failover: rotation bridge released (handing over to the newly established tunnel)`. Then append `Using geofiles from …` as its current diagnostic, register the protector, start Xray, and append `Xray core started`.
7. Each deferred diagnostic append is best-effort and isolated from the bring-up failure funnel. If one throws, the service records no replacement-failure state, does not tear down or reclassify the installed `LiveProxy`, and continues the remaining post-establishment steps. Losing a diagnostic is acceptable; losing owner truth or packet containment is not.

This replaces the earlier release-first wording. The reason is policy-level: Android's `VpnService.Builder.establish()` contract documents that a successful replacement deactivates the old interface and that an unsuccessful replacement leaves the existing interface and descriptor untouched. Keeping the unread bridge until successful replacement avoids the intentional interface-free interval created by the old ordering; it does not replace device proof of the platform's packet-routing behavior. Device packet capture remains the authority for the no-underlying-packet-escape policy; log order is diagnostic evidence only.

#### Test 23a observable sequence

The routine-switch log oracle must be updated to expect:

1. `Failover: rotating A -> B`;
2. `Failover: rotation bridge TUN established with fd=…`;
3. `TUN established with fd=…` for the replacement descriptor;
4. optionally, `Failover: rotation bridge close warning: …` when the old bridge close reports an error;
5. `Failover: rotation bridge released (handing over to the newly established tunnel)`;
6. `Using geofiles from …`, followed by the existing protector and Xray-start diagnostics.

The close-warning line is conditional; the release audit is the mandatory successful-handover marker. Test 23a must not require a close warning on a successful ordinary close, and must not treat any log ordering as proof of the packet policy. The capture must continue across the whole rotation, including replacement establishment and old-bridge cleanup, and must show no packet to the tester-chosen plain-HTTP destination on the underlying interface. Traffic to the proxy server addresses remains expected and excluded from that verdict. A packet-capture failure is a product-policy failure even when the logs appear correct.

### Capture error handling

- Missing packages continue to be skipped and logged.
- Live-only empty allow-only warning behavior and caller-specific missing-package text remain unchanged.
- Capture-plan creation or application failures use the existing caller failure paths.
- A null descriptor from `Builder.establish()` remains an establishment failure.
- No fallback may omit addresses, routes, DNS servers, or split policy.
- Failed real establishment during rotation continues to restore containment according to current sequencing.
- Failed unread establishment continues to produce the existing truthful containment or `UNPROTECTED` result.

## Component Responsibilities

- `TunnelInterfaceOwner<Handle>` binds one handle to one exhaustive ownership role. It has no Android, close, lock, or lifecycle behavior.
- `TunnelRole` is a pure projection used only by policy code; it is never another source of state.
- `TunnelOwnerTransitions` is the production-used, Android-independent mutation seam; it enforces role preconditions and returns next owners/handles without performing side effects.
- `TunCapturePlan` is immutable establishment input and has no repository or Builder dependency.
- `TunCapturePlanFactory` converts already-resolved session, IPv6, and split-plan inputs into the complete plan. It does not read preferences itself.
- `TunCapturePlanApplicator` translates one plan into `TunBuilderTarget` operations and invokes the caller-provided missing-package callback.
- `TunBuilderTarget` is the minimal recording seam for Builder configuration and excludes establishment.
- `VpnServiceTunBuilderTarget` is the only adapter that knows `VpnService.Builder`.
- `XrayVpnService` remains responsible for preference reads, the lifecycle lock, session epochs, serialized operations, descriptor ownership, release/close, `establish()`, protector registration, Xray, errors, state publication, and notifications.
- `SessionLifecycleDecision` remains responsible for pure role- and state-based policy.
- `FailoverPoolResolver` remains responsible for pool derivation.

No component introduced by this spec owns failover sequencing.

## Connection-State Semantics

Add one exhaustive helper named `isActiveSessionState` adjacent to `VpnConnectionState`:

```kotlin
internal fun VpnConnectionState.isActiveSessionState(): Boolean = when (this) {
    VpnConnectionState.CONNECTING,
    VpnConnectionState.CONNECTED,
    VpnConnectionState.PAUSED,
    VpnConnectionState.BLACKHOLED -> true

    VpnConnectionState.DISCONNECTED,
    VpnConnectionState.ERROR -> false
}
```

Its KDoc must say that this is the shared four-state membership invariant currently used by three distinct semantics: active-profile highlighting, tile active rendering, and tile Stop eligibility. It is not a general service-process, connectability, disconnectability, or recovery predicate. Semantic wrappers remain named for their caller-specific meaning even when they delegate to this set.

### Callers to migrate

- `MainActivity.isActive`: retain the active-profile ID comparison and delegate only state classification.
- `TileClickDecision.shouldStopOnTileClick`: retain this tile-specific seam and delegate to the helper.
- `XrayVpnTileService.updateTile`: use the helper only for active/inactive tile state.

### Semantics to keep distinct

- `connectAction`, including `BLACKHOLED -> RECONNECT`.
- Main UI Disconnect visibility, which includes `ERROR`.
- CONNECTING-only progress and retained-error behavior.
- Exhaustive tile labels.
- Ongoing notification presentation.
- `VpnConnectionLabels`.
- `ReconnectFlow` transition handling.
- `SessionLifecycleDecision` state and containment mappings.
- Kill-switch and service lifecycle checks.

The implementation must not replace these with the shared helper merely because their current state sets overlap in part.

## Manual-Profile SQL

Define one file-private compile-time constant:

```kotlin
private const val MANUAL_PROFILES_SQL =
    "SELECT * FROM profiles WHERE subscriptionId IS NULL ORDER BY id ASC"
```

Use it in both `@Query` annotations.

Retain:

- `getManual(): Flow<List<Profile>>`;
- `suspend getManualList(): List<Profile>`.

Do not implement the snapshot API by collecting `getManual().first()`. That would change generated-query shape, scheduling, cancellation, fakes, and test behavior for no functional gain.

`FailoverPoolResolver` remains the only pool-derivation seam and continues using the snapshot method.

## Incremental Migration

### Phase 1: Characterization baseline

- Confirm exhaustive current role/containment decision coverage.
- Add or retain full-enum state-semantic coverage for every approved state set.
- Confirm `SplitTunnelPlanner` coverage for both modes, package deduplication, and the self-package rule. IPv6 capture is Builder behavior and becomes JVM-observable only in Phase 3.
- Add a real Room equivalence test for the two manual-profile APIs.

This phase changes no production behavior.

### Phase 2: Single-owner migration

- Add the generic sealed owner and derived role.
- Add the production-used `TunnelOwnerTransitions` seam, including the retained-bridge candidate-establishment result and bounded prior-bridge cleanup result.
- Replace all reads and writes of `tunInterface`, `tunInterfaceKind`, and `rotationBridgeInterface` in one compile-safe caller sweep.
- Update affected `SessionLifecycleDecision` inputs and tests in the same phase.
- Preserve existing service method boundaries, lock regions, epoch checks, and serialized-lane ownership.
- Verify that no old field or kind references remain.

Do not retain shadow fields or dual-write compatibility after this phase.

Phase 2 stop/go gate:

- run the owner, lifecycle-decision, and existing JVM regression suites;
- compile the app and complete human review of every owner mutation;
- on device, exercise normal start/Stop, establish-first successful rotation handover, replacement-establishment failure with bridge retention, candidate-failure bridge restoration, give-up adoption, kill pause, revoke, and destruction during rotation;
- confirm every terminal path leaves no authoritative descriptor or pending cleanup handle and every give-up bridge is adopted rather than duplicated.

The gate must separately verify that the successful handover temporarily has a local prior-bridge cleanup handle without creating a second authoritative owner. A failed replacement must leave the original unread bridge available to the existing retry/give-up path.

Phase 3 must not begin until this gate passes. Revert Phase 2 as a unit if owner cleanup or transition ordering fails.

### Phase 3: Capture-plan extraction

- Add `TunCapturePlan`, its factory, the narrow Builder adapter, and the shared applicator.
- Migrate both real and unread Builder configuration to the shared path.
- Keep `establish()` at its existing service call sites, but change rotation candidate handover to establish-first: retain the bridge through the call, install the live owner on non-null return, then close the prior bridge through the bounded local cleanup path.
- Make the establish-first ownership and cleanup order a mandatory human-review checklist item. Automated tests verify transition results and plan/application behavior, while device packet capture remains the proof of the no-underlying-packet-escape policy across the handover.
- Confirm that split settings are still loaded per establishment.
- Preserve the live-only empty allow-only warning and both missing-package message formats.

Phase 3 stop/go gate:

- run JVM capture-plan and recording-target tests;
- verify that IPv6-enabled and IPv6-disabled plans differ only by the conditional IPv6 DNS entry when all other inputs match;
- on device, repeat split allow-only/exclude capture checks and packet capture with Xray IPv6 enabled and disabled;
- verify live and unread diagnostics and the revised Test 23a order: `TUN established` before the retained close-warning/release diagnostics, with the complete packet-capture policy unchanged.

Later phases must not begin until this gate passes. Revert Phase 3 independently if capture parity, IPv6 containment, diagnostics, or handover ordering fails.

### Phase 4: State-helper migration

- Add the exhaustive helper.
- Migrate only the approved caller families.
- Preserve all intentionally distinct state sets and presentation mappings.

### Phase 5: DAO SQL deduplication

- Add the compile-time SQL constant.
- Point both existing annotations to it.
- Retain both API signatures and caller behavior.

This phase is independent of the VPN changes and can be reviewed or reverted separately.

### Phase 6: Documentation and full verification

- Correct every authoritative document listed below.
- Run stale-reference searches.
- Run JVM, instrumented, lint, and release verification.
- Complete mandatory device QA.

Every phase must leave a compiling intermediate state and preserve behavior outside the explicitly approved establish-first handover and deferred-diagnostic changes. Phases 2 and 3 are separate security boundaries with explicit stop/go gates and independent reverts; a later cleanup phase must not be used to repair a failed gate.

## Test Strategy

### JVM tests

Add or retain coverage for:

- all sealed owner variants using token handles;
- exhaustive owner-to-role projection;
- production `TunnelOwnerTransitions`, including same-handle bridge-to-blackhole adoption;
- initial/revive real-establishment acceptance from `None`;
- rotation real-establishment acceptance from `UnreadRotationBridge` while retaining the bridge until a non-null candidate exists;
- null/throw replacement results that leave the bridge owner unchanged;
- successful candidate promotion to `LiveProxy` before prior-bridge cleanup;
- deferred close-warning/release diagnostics that cannot re-enter pre-commit failure handling;
- refusal of every invalid state/role establishment combination;
- clear-before-close transition results for every terminal owner release;
- all affected `SessionLifecycleDecision` outcomes after role-input migration;
- capture-plan values with IPv6 enabled and disabled, proving that both plans always contain the IPv6 address and `::/0` route and differ only by IPv6 DNS when other inputs match;
- every split-tunnel mode and self-package rule;
- exact ordered Builder operations through a recording adapter;
- live-only empty allow-only warning behavior;
- both live and unread missing-package callbacks and exact message formats;
- all six `VpnConnectionState` values for:
  - active-session presentation;
  - `connectAction`;
  - Disconnect visibility;
  - tile Stop eligibility;
- unchanged `FailoverPoolResolver` snapshot dispatch.

Relevant regression suites include:

- `SessionLifecycleDecisionTest`;
- `SessionLifecycleRotationTest`;
- `SplitTunnelPlannerTest`;
- `TileClickDecisionTest`;
- `MainActivityStateTest`;
- `ConnectActionTest`;
- `VpnConnectionLabelsTest`;
- `FailoverPoolResolverDispatchTest`.

### Instrumented tests

- Verify `getManual().first()` and `getManualList()` return the same ordered manual-only rows and exclude subscription rows.
- Retain real Room coverage through `FailoverPoolResolverTest`.
- Retain the existing failover notification instrumentation coverage.

No new instrumentation-only abstraction test is required for the sealed owner or Builder adapter. Generic-owner and recording-target behavior is deterministic on the JVM; actual descriptor closure, Builder capture, and packet behavior are mandatory device-QA responsibilities below.

### Mandatory device QA

- Normal connection and Stop.
- Successful automatic rotation with establish-first replacement and bridge release after live-owner promotion.
- Candidate bring-up failure followed by another candidate while the bridge remains retained/restored.
- Forced replacement `establish()` null/throw with truthful bridge retention and eventual retry/give-up handling.
- All-server give-up contained by a live TUN.
- All-server give-up adopting the unread rotation bridge.
- Direct unread blackhole establishment when no bridge exists.
- Forced real and unread establishment failures with truthful `UNPROTECTED` behavior.
- Kill, user Stop, revoke, and destruction during rotation.
- Split-tunnel allow-only and exclude modes.
- IPv6 enabled and disabled.
- Packet capture proving no cleartext escapes during bridge, handover, or blackhole states.
- Confirmation that only one bridge is established per episode and that give-up adopts rather than duplicates it.
- Confirmation that no descriptor remains after pause or full stop.
- Main UI and Quick Settings behavior for every connection state, especially `BLACKHOLED` and `ERROR`.

Engine sequencing tests through a fake `TunnelHost` belong to the follow-up engine-migration spec, not this structural spec.

## Verification Commands

Implementation verification must include, at minimum:

```powershell
./gradlew.bat :app:testDebugUnitTest
./gradlew.bat :app:connectedDebugAndroidTest
./gradlew.bat :app:lintDebug
./gradlew.bat :app:assembleRelease
```

Heavyweight Gradle commands must run serially. VPN-package changes require human review.

## Documentation Changes

Implementation must update all six tracked authoritative documents below.

### `docs/features/auto-failover.md`

- Replace the separate `tunInterface` / `tunInterfaceKind` / `rotationBridgeInterface` ownership description.
- Document all four sealed owner variants and the derived unstored role.
- Update rotation bridge, adoption, give-up, components, testing, and known-limitations sections.
- Replace duplicated-Builder descriptions with per-establishment `TunCapturePlan` and the shared applicator.
- Document the approved establish-first candidate handover, why it replaces release-first under the no-packet-escape policy, retained-bridge failure behavior, bounded prior-bridge cleanup, and deferred diagnostics.
- Document that the exact existing close-warning and release-audit strings are retained but no longer emitted in the establish boundary.
- Document `isActiveSessionState` while preserving the distinct connect, Disconnect, tile, notification, and lifecycle state semantics.
- Keep `FailoverEngine` / `TunnelHost` explicitly deferred.

### `AGENTS-ARCHITECTURE.md`

- Update the fourth fail-closed guarantee from independent bridge-field ownership to the sealed owner.
- Explain the establish-first handover and why retaining the bridge until successful replacement is the policy-preserving choice.
- Document the bounded prior-bridge cleanup handle and deferred diagnostics without implying two authoritative owners.
- Clarify BLACKHOLED and active-session presentation semantics without implying that all state consumers share one predicate.
- Preserve packet-capture policy claims and distinguish them from log-order diagnostics.

### `AGENTS-DEVELOPMENT.md`

- Replace `TunInterfaceKind` references in auto-failover extension points and lifecycle constraints.
- Document `TunCapturePlan` and the shared Builder applicator as the capture-configuration seam.
- Document establish-first rotation handover, retained bridge on null/throw, bounded prior-bridge cleanup, and post-establishment deferred diagnostics.
- Preserve the one-lock, epoch, and transition-discipline requirements.
- Keep the engine extraction listed as a later migration.

### `docs/features/qs-tile-vpn-toggle.md`

- Replace the duplicated active-state set with `isActiveSessionState`.
- State its exact four-state set.
- Keep tile Stop, ERROR Disconnect, `connectAction`, and state-specific labels semantically distinct.

### `docs/features/logs-screen.md`

- Replace the `tunInterface == null` establishment precondition description with `TunnelInterfaceOwner.None` or the final equivalent predicate.
- Preserve epoch, `ROTATING`, and log-capture behavior descriptions.

### `docs/qa/auto-failover-qa.md`

- Update Test 23 rationale to describe capture-plan construction before the establish-first handover and the bridge's retained containment during replacement establishment.
- Update Test 23a's expected log sequence to put `TUN established with fd=…` before the deferred close-warning/release diagnostics. Keep the packet-capture window and no-underlying-packet-escape policy unchanged; logs are not the policy oracle.
- Update Test 23's forced-failure checks to verify that null/throw replacement leaves the bridge available to the existing retry/give-up path.
- Update Test 7 rationale: tile rendering and `shouldStopOnTileClick` now delegate to the same `isActiveSessionState` membership set, while the device test still verifies active rendering and functional Stop behavior.
- Preserve one-bridge-per-episode, same-handle adoption, establish-first handover, deferred diagnostics, and packet-capture pass/fail criteria.

### Current planning inventories

After this written spec is reviewed, align these current planning documents with the approved handover target. Preserve their existing gates and do not treat alignment as implementation approval:

- `docs/superpowers/plans/2026-09-03-auto-failover-structural-foundations-plan.md`
- `docs/superpowers/plans/2026-09-03-auto-failover-structural-foundations-owner-plan.md`
- `docs/superpowers/plans/2026-09-03-auto-failover-structural-foundations-capture-plan.md`
- `docs/superpowers/plans/2026-09-03-auto-failover-structural-foundations-verification-plan.md`
- `docs/superpowers/plans/2026-09-04-auto-failover-engine-migration-contract-recovery-plan.md`, specifically its bridge/handover inventory at the current line 158 and any dependent failure-order statements.

The 2026-09-04 engine design remains incomplete and unapproved. Updating the recovery inventory must not complete that design, author engine implementation plans, or bypass maintainer approval. Older historical artifacts remain outside this alignment scope.

### Source documentation

Update affected KDocs and maintainer comments in:

- `XrayVpnService.kt`;
- `SessionLifecycleDecision.kt`;
- `TileClickDecision.kt`;
- any field-name references in `MainActivity.kt`, `VpnViewModel.kt`, or `TunnelHealthMonitor.kt`.

### Documents intentionally unchanged

- Root `AGENTS.md`, unless implementation invalidates a statement not present during this review.
- `README.md`, `CHANGELOG.md`, and behavior-only feature documents.
- `docs/features/xray-settings.md`; its existing claim that IPv6 address/route capture remains active when Xray IPv6 is disabled stays authoritative and must still be true.
- Profile documentation, because DAO behavior and API contracts do not change.
- Older ignored historical specs and plans.
- Untracked raw briefs and raw specs/plans.

Tracked authoritative documentation may be rewritten to current truth because git history preserves prior versions. Historical design artifacts are not authoritative and should not be retrofitted.

### Documentation verification

Search tracked docs and source comments for:

- `tunInterfaceKind`;
- `TunInterfaceKind`;
- `rotationBridgeInterface`;
- stale claims that unread and live Builder configuration have separate bodies;
- stale `tunInterface == null` ownership preconditions;
- duplicated four-state boolean chains described as independent policies.

Then confirm:

- all four owner variants are named consistently;
- the derived role is explicitly unstored;
- establish-first replacement creates no intentional interface-free release window;
- a failed replacement leaves the unread bridge authoritative for the existing retry/give-up path;
- a successful replacement installs `LiveProxy` before closing the old bridge through a bounded local cleanup handle;
- close/release diagnostics are emitted after the new `TUN established` marker and cannot re-enter pre-commit failure handling;
- packet capture proves the no-underlying-packet-escape policy across the entire rotation handover; logs are diagnostic evidence only;
- both IPv6 address and default route are unconditional and only IPv6 DNS is conditional;
- split preferences remain per-establishment;
- the active-session helper has exactly four states;
- ERROR Disconnect, `connectAction`, labels, notifications, and lifecycle rules remain distinct;
- `FailoverEngine` and `TunnelHost` are still described as deferred.

## Future `FailoverEngine` Compatibility

The follow-up spec will design the engine migration. Decisions already established for that work are:

- `FailoverEngine` owns the automatic failover episode: monitor callbacks, rotation reservation, candidate traversal and retries, give-up, and re-arm.
- The service retains kill, revive, stop, Android descriptor operations, the lifecycle lock, session epochs, and the serialized tunnel-operation lane.
- `TunnelHost` exposes semantic, epoch-checked transactions rather than mirroring private service methods.
- The engine never receives `ParcelFileDescriptor`, `VpnService.Builder`, or the mutable sealed owner.
- Pure lifecycle policies remain in `SessionLifecycleDecision`.
- `FailoverPoolResolver` remains the sole pool-derivation seam.

This structural design must not introduce APIs that force the later engine to manipulate fd roles or Builder details.

## Rejected Alternatives

### Expanded enum with separate descriptors

Rejected because role/fd drift and simultaneous-owner combinations remain representable.

### Sealed owner containing capture-plan data

Rejected because descriptor ownership and per-establishment configuration have different lifetimes. Combining them would retain stale plans and complicate adoption.

### Android-aware tunnel lifecycle controller

Rejected for this phase because it risks becoming a second service-shaped manager and prematurely fixes the later host seam.

### Session- or rotation-scoped plan

Rejected because it changes when split-tunnel preferences take effect.

### Release before replacement establishment

Previously chosen to minimize uncertainty about a failed replacement, but superseded on 2026-09-22. Android's documented `Builder.establish()` replacement contract preserves the existing interface and descriptor when new interface creation fails, so releasing first introduces an avoidable interface-free interval. Moving the old audit log out of that interval reduces ordinary work but does not remove the policy exposure. The approved target is establish-first with immediate promotion, bounded old-bridge cleanup, and deferred diagnostics.

### Remove the release diagnostic entirely

Not selected. Moving the diagnostic outside establishment retains useful operational evidence without making it a prerequisite for replacement. The exact current text is retained for now; QA observes its new location rather than dictating the handover order.

### Generic service-running or broad capability helpers

Rejected because `ERROR`, `BLACKHOLED`, connectability, disconnectability, and tile behavior intentionally have different meanings.

### Snapshot query implemented by collecting the Flow

Rejected because it changes execution and test behavior while preserving only the returned rows.

### One combined structural-and-engine spec

Rejected in favor of two independently reviewable designs. Structural invariants land first; sequencing migration follows.

## Acceptance Criteria

- One authoritative owner classifies the service's active TUN role; a successful candidate handover may hold one bounded prior-bridge cleanup reference without creating a second owner.
- The four requested interface roles are exhaustive and hard to misclassify.
- No stored kind tag or separate rotation-bridge descriptor remains.
- Real and unread tunnels use one capture-plan factory and one Builder applicator.
- All capture-affecting values have one source of truth.
- IPv6 address and default route remain unconditional; only IPv6 DNS follows the Xray IPv6 setting.
- Split-tunnel preferences are still resolved per establishment.
- Rotation establishes the replacement while retaining the bridge, installs `LiveProxy` before closing the old bridge, and leaves the bridge unchanged on null/throw.
- Deferred close/release diagnostics follow the replacement `TUN established` marker and cannot re-enter pre-commit failure handling.
- Packet capture confirms the no-underlying-packet-escape policy across the complete handover; log order is diagnostic only.
- Lock, epoch, dispatcher, and transition boundaries are unchanged.
- Give-up outcomes and user-visible states are unchanged.
- `stopVpn` remains synchronous.
- `shouldRestartForRecovery` remains `UNPROTECTED`-only.
- `BLACKHOLED` remains live and stoppable.
- `ReconnectFlow` remains the contained-state reconnect coordinator.
- Only the exact approved state predicate is shared.
- Both DAO APIs and their behavior remain intact.
- All required tests, release verification, device QA, and documentation updates pass.
- Human review covers all `vpn/` changes.

## Open Questions

No design questions remain open for this structural-foundations spec.

## Change Control

This file records the approved design and its post-write clarifications.

After this initial write, every accepted change to this design must add a dated entry below. Existing prose may be corrected for consistency, but the entry must record:

- the previous decision;
- the replacement decision;
- the reason;
- the affected sections.

Entries are additive and must not be silently removed. A future implementation plan derived from this spec must maintain its own equivalent post-write change record. Tracked authoritative maintainer docs may be rewritten directly because git history provides their change trace; older historical specs and plans need not be updated.

### 2026-09-22 — Establish-first handover and deferred diagnostics

- **Previous decision:** Candidate rotation released and closed the unread bridge before calling `VpnService.Builder.establish()`. The written invariant forbade logging or other work between release and establishment, while production emitted the close warning and exact release-audit append in that interval. The plans had preserved that append as an undocumented exception, and the source rationale treated replacement failure behavior as unresolved.
- **Replacement decision:** Candidate rotation keeps `UnreadRotationBridge` authoritative while calling `Builder.establish()`. A null or thrown establishment leaves that owner unchanged. A non-null candidate is installed as `LiveProxy` immediately; the old bridge is retained only as a bounded local cleanup handle, closed once without raw-fd retry or re-adoption. The existing close-warning and release-audit messages are emitted after the new `TUN established` marker and are isolated from pre-commit failure handling. Geo-asset preparation remains before establishment; the existing geofile diagnostic remains in the post-establishment sequence. Test 23a must observe the revised marker order, while its complete underlying-interface packet-capture policy remains authoritative and unchanged.
- **Reason:** No packet escape is product security policy. Android's documented `VpnService.Builder.establish()` contract says a successful replacement deactivates the old interface and an unsuccessful replacement leaves the existing interface and descriptor untouched. Establish-first therefore removes the intentional interface-free release window. The earlier strict no-I/O wording was also inconsistent with production's close-warning and release-audit behavior. Moving—not deleting—the diagnostics preserves useful observability without adding their synchronous work to the handover boundary. A close failure remains non-fatal; bookkeeping records the outcome without treating the old descriptor as a second owner or attempting unsafe raw-fd cleanup.
- **Affected sections:** Status and implementation status, Summary, Problem Statement, Scope, Current Ownership and Data Flow, Production transition seam, Ownership invariants, Required transitions, Shared TUN Capture Plan, Handover ordering, Capture error handling, Incremental Migration, Test Strategy, Documentation Changes, current planning inventories, Documentation verification, Rejected Alternatives, Acceptance Criteria, and this Change Control section.

**Decision boundary:** This amendment authorizes documentation target alignment only. It does not authorize production implementation, test edits, plan execution, engine migration, commits, or a claim that device packet capture has already passed. The 2026-09-04 engine contract-recovery gate remains binding and out of scope.



### 2026-09-03 — Self-review precision pass

- **Previous decision:** The initial draft left final owner names refinable, allowed an equivalent application-policy representation in `TunCapturePlan`, described owner-dependent decision migration as a broad rule, and made Android-specific abstraction testing conditional.
- **Replacement decision:** Owner/component/helper names and `SplitTunnelPlan` are explicit; every owner-dependent `SessionLifecycleDecision` caller is inventoried; give-up classification uses the resulting role; JVM tests own generic-owner and recording-target behavior; mandatory device QA owns actual descriptor, Builder, and packet proof.
- **Reason:** Remove implementation ambiguity, catch two additional owner-dependent Boolean callers, and distinguish deterministic JVM assertions from security claims that only device execution can prove.
- **Affected sections:** Generic sealed TUN owner, Derived role, Shared TUN Capture Plan, Connection-State Semantics, Incremental Migration, and Test Strategy.

### 2026-09-03 — Fresh-review corrections

- **Previous decision:** The first reviewed draft grouped all IPv6 Builder entries under the IPv6 setting, kept owner transitions private to service-side mutations while promising JVM transition tests, treated protector registration as owner-scoped, described the shared state set as presentation-only, omitted caller-specific split diagnostics, deferred all security QA to the final phase, and listed only QA Test 23 for revision.
- **Replacement decision:** IPv6 address and `::/0` route are unconditional while only IPv6 DNS is conditional; production uses `TunnelOwnerTransitions` with explicit initial/revive/rotation preconditions; destruction/revoke/Stop cleanup order is explicit; protector registration is process-global; `isActiveSessionState` names shared membership while semantic wrappers remain; diagnostics are caller-owned and tested; Phases 2 and 3 have independent stop/go gates; QA Tests 7 and 23 are both updated.
- **Reason:** Correct a potential IPv6 leak regression, make promised tests execute production rules, preserve load-bearing cleanup and diagnostic behavior, and keep each security-sensitive migration independently verifiable and revertible.
- **Affected sections:** Summary, Current Ownership and Data Flow, Derived role, Production transition seam, Ownership invariants, Shared TUN Capture Plan, Component Responsibilities, Connection-State Semantics, Incremental Migration, Test Strategy, Documentation Changes, Acceptance Criteria, and Change Control.
