# Auto-Failover Structural Foundations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land the approved structural foundations refactor without changing containment outcomes or user-visible state semantics. The one approved lifecycle change is the 2026-09-22 establish-first candidate handover: during `ROTATING`, keep `UnreadRotationBridge` authoritative through `Builder.establish()`, install `LiveProxy` only on a non-null result, then close the old bridge as a bounded local cleanup handle. Production is still release-first; this plan set is the target, not a description of current code.

**Architecture:** The work is split into three independently reviewable plans. The owner plan replaces the three descriptor fields with one sealed owner and migrates every lifecycle decision and mutation site. The capture plan extracts one immutable per-establishment capture recipe and one Builder applicator. The verification plan handles the shared state helper, DAO SQL deduplication, documentation, and the required stop/go gates.

**Tech Stack:** Kotlin, Android `VpnService.Builder`, Room, Jetpack Compose state consumers, JUnit4, Android instrumented tests, Gradle, device packet capture

**Spec:** `docs/superpowers/specs/2026-09-03-auto-failover-structural-foundations-design.md`

## Global Constraints

- Preserve reservation rules, thrash caps, pool resolution, bridge ownership, containment outcomes, recovery timing, kill/Stop/disable interleaving, and packet-level fail-closed behavior.
- `TunnelInterfaceOwner` is the only authoritative descriptor owner; no shadow fields or stored kind tags remain after the owner phase.
- `TunnelRole` is derived with an exhaustive `when`; it is never stored.
- `SessionLifecycleDecision` functions remain top-level functions in the existing file; do not invent a `SessionLifecycleDecision` object or nested enums.
- `Profile` is `com.justme.xtls_core_proxy.db.Profile`; `SplitTunnelPlan` contains `List<String>` fields.
- Service logging continues through `LogRepository.append`; preserve exact existing log text and caller-specific prefixes.
- IPv6 address `fd00:1:fd00:1::1/128` and route `::/0` remain unconditional; only IPv6 DNS is conditional.
- Split preferences are read for every establishment, not cached for a session or rotation episode.
- Candidate handover is establish-first, per the amended spec. During `ROTATING`, `UnreadRotationBridge` stays authoritative while `Builder.establish()` runs. `null` or a throw leaves that owner unchanged: no release audit, no close/detach, and no live owner. A non-null result installs `LiveProxy(candidate)` immediately. The old bridge is only a bounded local cleanup handle: close it once, never re-adopt it, detach it, or retry its raw fd. Close failure is non-fatal and is retained as deferred diagnostic state. Append `TUN established with fd=…`, then the optional exact close warning, then the exact release audit `Failover: rotation bridge released (handing over to the newly established tunnel)`, then the existing geofile, protector, and Xray diagnostics. A post-establishment diagnostic append failure must not enter the bring-up failure funnel or tear down/reclassify `LiveProxy`. Initial start and revive have no bridge and emit no release audit. No log, repository read, suspension, config work, notification, or other unrelated operation is inserted between non-null establishment, live-owner installation, and the one close attempt. Packet capture across the whole rotation is the product-policy oracle; logs are diagnostic evidence only. Current production still releases the bridge before `establish()`; do not copy that baseline into the implementation.
- `stopVpn` remains synchronous; `shouldRestartForRecovery` remains `UNPROTECTED`-only; `BLACKHOLED` remains live and stoppable.
- `onDestroy`, `onRevoke`, and notification `ACTION_STOP` retain their current ordering and dispatcher boundaries.
- Heavy Gradle commands run serially. Human review is mandatory for every `vpn/` change.
- Commits, if made by the executor, use `git commit -S`; signing failure is reported, never worked around by changing external configuration.

## Plan Set and Order

1. `docs/superpowers/plans/2026-09-03-auto-failover-structural-foundations-owner-plan.md`
   - Characterization baseline and complete sealed-owner/lifecycle migration.
   - Stop/go gate before capture work.
2. `docs/superpowers/plans/2026-09-03-auto-failover-structural-foundations-capture-plan.md`
   - Immutable `TunCapturePlan`, shared Builder adapter/applicator, and both service Builder migrations.
   - Stop/go gate before state/UI/DAO cleanup.
3. `docs/superpowers/plans/2026-09-03-auto-failover-structural-foundations-verification-plan.md`
   - `isActiveSessionState`, DAO SQL constant, documentation, stale-reference audit, full verification, and final device QA.

Do not start a later plan until the preceding stop/go gate passes. Each plan names the exact current source APIs and deliberately avoids the previous monolith's pseudo-code that used nonexistent packages, nested types, or logging APIs.

## File Ownership Map

| Plan | Owns | Must not own |
|---|---|---|
| Owner | `TunnelInterfaceOwner`, `TunnelRole`, `TunnelOwnerTransitions`, `SessionLifecycleDecision` role-input migration, all `XrayVpnService` owner reads/writes | Capture-plan extraction, UI helper, DAO SQL |
| Capture | `TunCapturePlan`, factory, `TunBuilderTarget`, Android adapter, applicator, two Builder call sites | Owner policy, state semantics, DAO |
| Verification | State helper and three approved callers, DAO constant/equivalence test, docs and final gates | New lifecycle behavior |

## Acceptance Checklist

- [ ] Owner plan passes its Phase 2 stop/go gate.
- [ ] Capture plan passes its Phase 3 stop/go gate.
- [ ] Verification plan updates all six authoritative documents and source KDocs.
- [ ] Searches find no stale `tunInterfaceKind`, `TunInterfaceKind`, or `rotationBridgeInterface` references after the owner phase.
- [ ] All six `VpnConnectionState` values retain their distinct connect, Disconnect, tile, notification, and lifecycle semantics.
- [ ] JVM, instrumented, lint, release, and mandatory device verification pass.

## Change Control

This index is a split of the original monolithic plan after review found compile-unsafe pseudo-code and omitted owner call sites. The split is organizational. Any later accepted design change must add a dated entry to the affected plan and preserve the old decision, replacement decision, reason, and affected sections. Alignment with the amended spec is not authorization to execute these plans, edit production or tests, or implement `FailoverEngine`/`TunnelHost`. The 2026-09-04 engine contract-recovery gate remains binding.

### 2026-09-21 — Split and source-grounding correction

- **Previous decision:** One monolithic plan supplied illustrative implementations for every phase.
- **Replacement decision:** Three phase-sized plans own the structural work separately and reference the actual top-level Kotlin APIs, package names, logging surface, and all current owner mutation sites.
- **Reason:** Review found that the monolith contained non-compiling imports and signatures, behavior-changing decision rewrites, and an incomplete caller sweep. Smaller plans make each security boundary independently reviewable.
- **Affected sections:** All task decomposition, interfaces, verification gates, and documentation steps.

### 2026-09-22 — Establish-first handover alignment

- **Previous decision:** The index required release of a rotation bridge to stay immediately adjacent to real `Builder.establish()`, with no intervening I/O. That release-first order is still what production does.
- **Replacement decision:** The index requires the amended spec's establish-first handover: retain `UnreadRotationBridge` through `establish()`, leave it unchanged on `null`/throw, install `LiveProxy` immediately on a non-null result, close the old bridge once as a bounded local cleanup handle, and emit the existing close warning and release audit only after `TUN established with fd=…`. Packet capture across the whole rotation remains the policy oracle; logs do not prove it. Initial start and revive still have no bridge and no release audit.
- **Reason:** The 2026-09-22 spec amendment replaced release-first because Android's documented `Builder.establish()` contract leaves the existing interface untouched on failure, so releasing first opens an avoidable interface-free window. This entry aligns the index with that approved target. It does not claim the target is implemented, and it does not authorize plan execution or engine implementation.
- **Affected sections:** Goal, Global Constraints, and this Change Control section.
