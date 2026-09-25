# Auto-Failover Structural Foundations: Capture Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make live and unread TUN establishment use one immutable, per-establishment capture recipe and one shared Builder applicator without changing preference timing or fail-closed outcomes outside the approved establish-first handover. Diagnostics keep their existing text, but the close warning and release audit move to after the replacement `TUN established` marker. Production is still release-first; this plan is the target.

**Architecture:** `TunCapturePlanFactory` receives already-resolved session name, MTU, IPv6 setting, and `SplitTunnelPlan`; it does not read preferences. `TunCapturePlanApplicator` translates the plan through a minimal `TunBuilderTarget` that deliberately excludes `establish()`. The service keeps explicit control of `Builder.establish()`, runtime config, geo assets, protector registration, Xray startup, ownership transitions, and caller-specific logging.

**Tech Stack:** Kotlin data classes, Android `VpnService.Builder`, `PackageManager.NameNotFoundException`, JUnit4 recording fakes, existing split-tunnel preferences

**Spec:** `docs/superpowers/specs/2026-09-03-auto-failover-structural-foundations-design.md`

**Prerequisite:** The owner plan `docs/superpowers/plans/2026-09-03-auto-failover-structural-foundations-owner-plan.md` must pass its Phase 2 stop/go gate.

## Global Constraints

- Build a new plan for every establishment. Do not cache split preferences by session or rotation episode.
- `SplitTunnelPlan.allowedPackages` and `.disallowedPackages` are `List<String>`; preserve their order.
- Fixed capture values remain exactly: `10.7.0.1/32`, `0.0.0.0/0`, `1.1.1.1`, `fd00:1:fd00:1::1/128`, and `::/0`.
- IPv6 address and route are unconditional. `2606:4700:4700::1111` is the only conditional capture value and appears only when `sessionTuning.core.ipv6` is true.
- The live path retains the empty allow-only warning and exact `LogRepository.append` text.
- The live callback text remains `Split tunnel skipped missing package: <package>`.
- The unread callback text remains `Failover: <label> TUN skipped missing package: <package>`.
- The unread path remains protected by its existing try/catch and returns `null` on any establishment/configuration failure.
- `TunBuilderTarget` must not expose `establish()`.
- All preparation remains before the lifecycle-lock handover. Under that lock, candidate handover is establish-first: `UnreadRotationBridge` stays authoritative through `Builder.establish()`. `null` or a throw leaves the bridge unchanged, with no release audit, close, detach, or live owner. A non-null candidate installs `LiveProxy` immediately. The old bridge is only a bounded local cleanup handle, closed once, never re-adopted, detached, or retried by raw fd. Close failure is non-fatal deferred diagnostic state. No log, repository read, suspension, config work, notification, or other unrelated operation is inserted between non-null establishment, live-owner installation, and that close. After it, append `TUN established with fd=…`, then the optional exact close warning, then the exact `Failover: rotation bridge released (handing over to the newly established tunnel)` audit, then the existing geofile/protector/Xray diagnostics. A post-establishment diagnostic append failure must not enter the bring-up failure funnel or tear down/reclassify `LiveProxy`. Initial start and revive have no bridge and emit no release audit. Geo-asset preparation stays before `establish()`; `Using geofiles from …` stays post-establishment. Packet capture across the whole rotation is the product-policy oracle; logs are diagnostic evidence only. Current production still releases the bridge before `establish()`; do not preserve that order.
- Do not move `ConfigBuilder`, geo preparation, split preference reads, or descriptor ownership into the new types.
- Heavy Gradle commands run serially. Human review covers `XrayVpnService.kt`.
- Commits, if made, use `git commit -S`; signing failure is reported rather than worked around by changing external configuration.

## Files

**Create:**
- `app/src/main/java/com/justme/xtls_core_proxy/vpn/TunCapturePlan.kt`
- `app/src/main/java/com/justme/xtls_core_proxy/vpn/TunCapturePlanFactory.kt`
- `app/src/main/java/com/justme/xtls_core_proxy/vpn/TunBuilderTarget.kt`
- `app/src/main/java/com/justme/xtls_core_proxy/vpn/VpnServiceTunBuilderTarget.kt`
- `app/src/main/java/com/justme/xtls_core_proxy/vpn/TunCapturePlanApplicator.kt`
- `app/src/test/java/com/justme/xtls_core_proxy/vpn/TunCapturePlanTest.kt`
- `app/src/test/java/com/justme/xtls_core_proxy/vpn/TunCapturePlanApplicatorTest.kt`

**Modify:**
- `app/src/main/java/com/justme/xtls_core_proxy/vpn/XrayVpnService.kt`

---

### Task 1: Define the immutable capture plan and factory

**Files:**
- Create: `app/src/main/java/com/justme/xtls_core_proxy/vpn/TunCapturePlan.kt`
- Create: `app/src/main/java/com/justme/xtls_core_proxy/vpn/TunCapturePlanFactory.kt`
- Test: `app/src/test/java/com/justme/xtls_core_proxy/vpn/TunCapturePlanTest.kt`

**Interfaces:**
- Consumes: `com.justme.xtls_core_proxy.split.SplitTunnelPlan`
- Produces:

```kotlin
internal data class TunCapturePlan(
    val sessionName: String,
    val mtu: Int,
    val addresses: List<Pair<String, Int>>,
    val routes: List<Pair<String, Int>>,
    val dnsServers: List<String>,
    val splitPlan: SplitTunnelPlan,
)

internal object TunCapturePlanFactory {
    fun create(
        sessionName: String,
        mtu: Int,
        ipv6Enabled: Boolean,
        splitPlan: SplitTunnelPlan,
    ): TunCapturePlan
}
```

- [ ] **Step 1: Write factory tests before implementation**

Use real `List<String>` values, not sets:

```kotlin
val split = SplitTunnelPlan(
    allowedPackages = listOf("com.example.allowed"),
    disallowedPackages = listOf("com.example.blocked"),
)
```

Cover these cases:

1. IPv6 enabled produces both IPv4 and IPv6 addresses/routes and both DNS servers.
2. IPv6 disabled still produces the IPv6 address and `::/0`, but produces only `1.1.1.1` as DNS.
3. Two plans with identical inputs except `ipv6Enabled` have identical session name, MTU, addresses, routes, and split plan; only `dnsServers` differs.
4. The factory retains the exact list order supplied by `SplitTunnelPlan`.
5. The factory does not mutate the supplied split lists after construction.

- [ ] **Step 2: Implement `TunCapturePlan`**

Create the immutable data class with the exact fields above. Keep it Android-independent and free of preference, repository, Builder, or descriptor dependencies.

- [ ] **Step 3: Implement `TunCapturePlanFactory.create`**

Use one source of truth for these values:

```kotlin
addresses = listOf(
    "10.7.0.1" to 32,
    "fd00:1:fd00:1::1" to 128,
)
routes = listOf(
    "0.0.0.0" to 0,
    "::" to 0,
)
dnsServers = buildList {
    add("1.1.1.1")
    if (ipv6Enabled) add("2606:4700:4700::1111")
}
```

Return the supplied `splitPlan` unchanged. Do not read `SplitTunnelRepository` inside the factory.

- [ ] **Step 4: Run the focused factory tests**

```powershell
./gradlew.bat :app:testDebugUnitTest --tests "*.TunCapturePlanTest"
```

Expected: PASS.

- [ ] **Step 5: Commit the plan types**

```bash
git add app/src/main/java/com/justme/xtls_core_proxy/vpn/TunCapturePlan.kt app/src/main/java/com/justme/xtls_core_proxy/vpn/TunCapturePlanFactory.kt app/src/test/java/com/justme/xtls_core_proxy/vpn/TunCapturePlanTest.kt
git commit -S -m "feat(vpn): add immutable TUN capture plan"
```

---

### Task 2: Add the narrow Builder adapter and applicator

**Files:**
- Create: `app/src/main/java/com/justme/xtls_core_proxy/vpn/TunBuilderTarget.kt`
- Create: `app/src/main/java/com/justme/xtls_core_proxy/vpn/VpnServiceTunBuilderTarget.kt`
- Create: `app/src/main/java/com/justme/xtls_core_proxy/vpn/TunCapturePlanApplicator.kt`
- Test: `app/src/test/java/com/justme/xtls_core_proxy/vpn/TunCapturePlanApplicatorTest.kt`

**Interfaces:**
- Consumes: `TunCapturePlan`
- Produces:

```kotlin
internal interface TunBuilderTarget {
    fun setSession(name: String)
    fun setMtu(mtu: Int)
    fun addAddress(address: String, prefixLength: Int)
    fun addRoute(destination: String, prefixLength: Int)
    fun addDnsServer(address: String)
    fun addAllowedApplication(packageName: String)
    fun addDisallowedApplication(packageName: String)
}

internal object TunCapturePlanApplicator {
    fun apply(
        plan: TunCapturePlan,
        target: TunBuilderTarget,
        onMissingPackage: (String) -> Unit,
    )
}
```

- [ ] **Step 1: Write a recording target and ordered-operation tests**

The recording target must record typed operations, not rendered strings only, so the test can assert exact values and order. Assert the applicator calls:

1. `setSession`;
2. `setMtu`;
3. addresses in plan order;
4. routes in plan order;
5. DNS servers in plan order;
6. allowed applications in list order;
7. disallowed applications in list order.

Assert no operation named `establish` exists on the target interface or recording fake.

- [ ] **Step 2: Test both missing-package callbacks**

Create a recording fake that throws `PackageManager.NameNotFoundException` for one allowed package and one disallowed package. Assert `onMissingPackage` receives each package exactly once and iteration continues to later packages. The applicator must catch only `NameNotFoundException`; other exceptions must propagate to the service caller's existing failure path.

- [ ] **Step 3: Implement `TunBuilderTarget`**

Keep the interface limited to session, MTU, address, route, DNS, and application operations. Do not add `establish`, `setBlocking`, or unrelated Builder methods.

- [ ] **Step 4: Implement `VpnServiceTunBuilderTarget`**

Wrap `android.net.VpnService.Builder` and delegate each interface method directly. This adapter is the only new type that knows the Android Builder. It must not expose the wrapped Builder or an establishment method.

- [ ] **Step 5: Implement `TunCapturePlanApplicator.apply`**

Apply the plan in the exact order above. Iterate `plan.splitPlan.allowedPackages` and then `disallowedPackages`. Catch `PackageManager.NameNotFoundException` around each application call and invoke the callback with that package. Leave all message formatting to the service call sites.

- [ ] **Step 6: Run the applicator tests**

```powershell
./gradlew.bat :app:testDebugUnitTest --tests "*.TunCapturePlanApplicatorTest"
```

Expected: PASS.

- [ ] **Step 7: Commit the adapter seam**

```bash
git add app/src/main/java/com/justme/xtls_core_proxy/vpn/TunBuilderTarget.kt app/src/main/java/com/justme/xtls_core_proxy/vpn/VpnServiceTunBuilderTarget.kt app/src/main/java/com/justme/xtls_core_proxy/vpn/TunCapturePlanApplicator.kt app/src/test/java/com/justme/xtls_core_proxy/vpn/TunCapturePlanApplicatorTest.kt
git commit -S -m "feat(vpn): add shared TUN Builder applicator"
```

---

### Task 3: Migrate the two service Builder paths

**Files:**
- Modify: `app/src/main/java/com/justme/xtls_core_proxy/vpn/XrayVpnService.kt`

**Interfaces:**
- Consumes: `SplitTunnelRepository`, `SplitTunnelPlanner`, `TunCapturePlanFactory`, `TunCapturePlanApplicator`, `VpnServiceTunBuilderTarget`
- Produces: live and unread establishment paths with shared capture configuration and unchanged lifecycle ownership

- [ ] **Step 1: Migrate live `bringUpTunnel` preparation**

Keep this existing ordering and ownership:

1. current-session/expected-state check;
2. runtime config construction;
3. geo asset preparation;
4. `SplitTunnelRepository.load(this@XrayVpnService)`;
5. live-only empty allow-only warning through:

```kotlin
LogRepository.append("Split tunnel allow-only mode enabled with no selected apps")
```

6. `SplitTunnelPlanner.plan(splitPrefs.mode, splitPrefs.packages, packageName)`;
7. `TunCapturePlanFactory.create(localizedString(...), sessionTuning.core.mtu, sessionTuning.core.ipv6, plan)`;
8. apply the plan to a fresh `Builder` through `VpnServiceTunBuilderTarget`;
9. preserve live missing-package callback text through `LogRepository.append`.

Do not move runtime config or geo work into the factory/applicator.

- [ ] **Step 2: Migrate unread `establishUnreadTunnelLocked` preparation**

Keep the existing precondition inside the existing `try` block and under the existing lock. Load split preferences at this establishment, create a fresh plan, and apply it to a fresh Builder. Preserve unread callback text exactly:

```kotlin
LogRepository.append("Failover: $label TUN skipped missing package: $pkg")
```

Preserve unread `null` return behavior for `establish()` returning null or any caught `Throwable`. Do not add the live empty allow-only warning to this path.

- [ ] **Step 3: Preserve the live-establishment precondition and establish-first handover in `bringUpTunnel`**

This step migrates the current under-lock body at `XrayVpnService.kt:644-683` (the `synchronized` block continues through line 684). The current comment at lines 648-664 and the `releaseRotationBridgeLocked` call at line 665 are the superseded production baseline (release-then-establish). Replace that order. Do not delete the active-transition precondition. Do not install `LiveProxy` before `builder.establish()` returns a non-null descriptor, and do not clear the bridge before that call.

After all off-lock preparation, under the existing lock, use this sequence:

1. Re-check epoch and ownership with `ownsTunnelTransitionLocked(sessionEpoch, expectedState)`. A failed re-check still throws `StaleSessionException` and must not establish or install an owner.
2. Call `TunnelOwnerTransitions.prepareRealEstablishment(tunnelOwner, expectedState)`. Do not replace this with a generic release. `STARTING` and `REVIVING` require `None` and reject an already-owned TUN, including a live proxy. `ROTATING` requires `UnreadRotationBridge` and **keeps that owner**. Any other state/role combination remains a lifecycle contract violation. A rejected preparation must not close a live handle and must not call `establish()`.
3. Call `builder.establish()` while the prepared owner is still authoritative. For a rotation, that owner is still `UnreadRotationBridge`. Do not assign `None` and do not close the bridge before this call. Do not emit the release audit before it.
4. If `establish()` throws or returns `null`, leave the bridge owner unchanged. Do not close or detach it, do not install `LiveProxy`, and do not emit a release audit. A null result remains an establishment failure (`throw IllegalStateException("VpnService.establish() returned null")`). The existing rotation failure funnel decides bridge retention/restoration, retry, give-up, or `UNPROTECTED` handling.
5. If `establish()` returns a non-null candidate, install `LiveProxy(candidate)` immediately via `installLiveProxy`. The old bridge becomes only the returned local cleanup handle. It is not a second owner.
6. Attempt one close of that local handle, and only that handle, using the existing try/catch warning text. A null handle (initial start and kill-switch revive) is a no-op close and emits no warning and no release audit. Never close a live-proxy or unread-blackhole handle on this path. Never re-adopt, detach, or retry the raw fd:

```kotlin
try {
    priorBridgeToClose?.close()
} catch (error: Throwable) {
    // Retain the message; append it only after the TUN-established marker below.
    closeWarning = "Failover: rotation bridge close warning: ${error.message}"
}
```

Close failure is non-fatal to the established replacement. No log, repository read, suspension, config work, notification, or other unrelated operation is inserted between the non-null `establish()`, the `LiveProxy` install, and this close.
7. After that close attempt, append diagnostics in this order. The release audit is mandatory on a successful rotation handover and forbidden when there was no prior bridge. The close warning is optional and appears only when step 6 recorded one. QA Test 23a (`docs/qa/auto-failover-qa.md`) requires the release audit **after** `TUN established with fd=…`, not before it:

```kotlin
LogRepository.append("TUN established with fd=$fd")
closeWarning?.let { LogRepository.append(it) }
if (priorBridgeToClose != null) {
    LogRepository.append("Failover: rotation bridge released (handing over to the newly established tunnel)")
}
LogRepository.append("Using geofiles from ${geoAssetDir.absolutePath}")
XrayBridge.registerProtector(this@XrayVpnService).getOrThrow()
XrayBridge.startXray(configJson, fd, geoAssetDir.absolutePath).getOrThrow()
LogRepository.append("Xray core started")
```

8. Each deferred diagnostic append (`TUN established`, the optional close warning, the release audit, and `Using geofiles from …`) is best-effort and isolated from the bring-up failure funnel. If one throws, record no replacement-failure state, do not tear down or reclassify the installed `LiveProxy`, and continue the remaining post-establishment steps. A later `registerProtector` or `startXray` failure keeps the current failure behavior for those calls. Do not special-case a failed `startXray` by withholding or rolling back `LiveProxy` inside this block, and do not let a diagnostic-append failure take that path.

Do not keep the handover comment at `XrayVpnService.kt:648-664`. Its release-then-establish instruction is the superseded baseline. The replacement comment must say establish-first: retain the bridge through `establish()`, install `LiveProxy` on a non-null result, then close the old bridge once as a local cleanup handle.

- [ ] **Step 4: Preserve live and unread differences outside the plan**

Review the diff and confirm:

- live still prepares runtime config, geo assets, protector, and Xray;
- unread still performs none of those operations;
- live and unread retain their distinct failure return behavior;
- split preferences are loaded independently for every establishment;
- the service remains the only owner of descriptor installation and `establish()`.

- [ ] **Step 5: Compile and run JVM tests**

```powershell
./gradlew.bat :app:compileDebugKotlin
./gradlew.bat :app:testDebugUnitTest
```

Expected: both commands pass.

- [ ] **Step 6: Commit the service migration**

```bash
git add app/src/main/java/com/justme/xtls_core_proxy/vpn/XrayVpnService.kt
git commit -S -m "refactor(vpn): share capture configuration for live and unread TUNs"
```

---

### Task 4: Phase 3 stop/go gate

**Files:**
- No production files; verification and human review

**Interfaces:**
- Consumes: completed Tasks 1-3
- Produces: authorization to begin state/helper/DAO/documentation work

- [ ] **Step 1: Run capture JVM tests**

```powershell
./gradlew.bat :app:testDebugUnitTest --tests "*.TunCapturePlanTest"
./gradlew.bat :app:testDebugUnitTest --tests "*.TunCapturePlanApplicatorTest"
```

- [ ] **Step 2: Review the plan invariants**

Confirm from tests and code that IPv6-enabled and disabled plans differ only by IPv6 DNS, both paths use the same plan/applicator, and no applicator API can establish a TUN.

- [ ] **Step 3: Verify exact diagnostics**

Use tests or a recording log sink to verify all three exact strings:

```text
Split tunnel allow-only mode enabled with no selected apps
Split tunnel skipped missing package: <package>
Failover: <label> TUN skipped missing package: <package>
```

- [ ] **Step 4: Device verification**

On a device, run all of these before approving the gate:

1. split allow-only mode with selected apps;
2. split exclude mode;
3. IPv6 enabled;
4. IPv6 disabled, confirming IPv6 address/route capture remains present;
5. successful rotation handover;
6. candidate bring-up failure and bridge restoration;
7. direct unread blackhole establishment;
8. forced real and unread establishment failures;
9. packet capture through bridge, handover, and blackhole states.

For item 9, follow QA Test 23 (`docs/qa/auto-failover-qa.md:816-855` for 23a; 23b/23c/23d continue through line 902). Do not treat the fixed health-probe URL as the capture target.

Test 23a procedure and verdict:

1. Capture on the underlying interface (wlan). Filter to the device IP and exclude VPN server A and server B addresses. Traffic to those server addresses is the proxy itself and is not a failure.
2. Generate continuous, easily identified traffic to a tester-chosen plain-HTTP destination whose address is legible in the capture, for example `http://example.com` (`curl http://example.com` from Termux, or an auto-refreshing page). This destination is the capture target. It is not `ConfigBuilder.HEALTH_PROBE_TARGET_URL` and not the health-probe host.
3. Pass only if no packet to that plain-HTTP destination appears on the underlying interface at any point during the whole rotation, including replacement establishment and old-bridge cleanup. The former release-audit-before-`TUN established` window is not the target oracle: current production still emits the audit before `TUN established`, but the approved target does not. Logs do not prove the policy. The step-3 traffic must stall during the switch and resume after Connected.
4. Fail immediately if any packet addressed to that destination appears on the underlying interface during the switch, even when the log order looks correct.

Expected successful-rotation log order, as diagnostic evidence only:

1. `Failover: rotating A -> B`;
2. `Failover: rotation bridge TUN established with fd=…`;
3. `TUN established with fd=…` for the replacement descriptor;
4. optionally, `Failover: rotation bridge close warning: …` when the old bridge close reports an error;
5. `Failover: rotation bridge released (handing over to the newly established tunnel)`;
6. `Using geofiles from …`, followed by the existing protector and Xray-start diagnostics.

Do not require the close warning on an ordinary successful close. Do not require the release audit before `TUN established`.

Apply the same destination rule to bridge, handover, and blackhole capture in 23b: the capture stays clean for that destination across the whole episode. Do not replace this with an unbounded generic packet-capture instruction.

- [ ] **Step 5: Human review establish-first handover**

A reviewer must inspect `bringUpTunnel` against the superseded baseline at `XrayVpnService.kt:644-683`. Required target order: epoch/ownership re-check, `prepareRealEstablishment` leaving `UnreadRotationBridge` authoritative, `builder.establish()` before any bridge close, unchanged bridge on `null`/throw, `LiveProxy` installed immediately on a non-null result, one close of only the local prior-bridge handle, then `TUN established with fd=…`, the optional close warning, the exact release audit when a bridge existed, then geofile, `registerProtector`, `startXray`, and `Xray core started`. Reject a diff that releases or audits the bridge before `establish()`, drops the active-transition precondition, installs `LiveProxy` before a non-null result, re-adopts or retries the old fd, or lets a diagnostic-append failure tear down `LiveProxy`. Log order alone is insufficient. Packet capture across the whole rotation is the policy proof.

- [ ] **Step 6: Record the gate result**

A failed packet, missing IPv6 route/address, wrong diagnostic, duplicate bridge, release-before-establish handover, or a diagnostic failure that reclassifies `LiveProxy` blocks the verification plan. Record the failing scenario and design a separate correction rather than weakening this gate.

## Capture Plan Self-Review

- [ ] No nonexistent `db.entity.Profile`, nested decision types, set/list mismatch, or `android.util.Log` API appears.
- [ ] Both Builder bodies are actually migrated; neither remains a second source of capture values.
- [ ] Runtime config and geo preparation remain live-only.
- [ ] The unread path remains catch-all and returns `null` on failure.
- [ ] Split preferences remain per-establishment.
- [ ] Exact diagnostic strings are tested.
- [ ] Establish-first handover is both code-reviewed and device-tested. The review checks retained bridge through `establish()`, `LiveProxy` before the one prior-bridge close, and the release audit after `TUN established`. Packet capture uses the Test 23a plain-HTTP destination (not the health-probe URL) across the whole rotation. Logs are not the policy oracle.

## Change Control

### 2026-09-21 — Capture plan split and correction

- **Previous decision:** The monolithic plan combined capture extraction with owner migration and used `Set`-based fixtures and Android logging snippets.
- **Replacement decision:** Capture work is isolated behind a list-preserving, Android-independent plan/factory/applicator, while the service keeps its existing logging and lifecycle responsibilities.
- **Reason:** Review found `SplitTunnelPlan` type mismatches, wrong logging APIs, missing diagnostic tests, and insufficient handover verification.
- **Affected sections:** Capture types, applicator, service Builder migration, tests, and Phase 3 gate.

### 2026-09-21 — Handover sequence and Test 23 capture target

Superseded for candidate-handover order by the 2026-09-22 entry below. Retained as history. Do not implement its clear-then-close-then-audit-then-`establish()` sentence. The plain-HTTP capture destination in that entry remains valid.

- **Previous decision:** Task 3 Step 3 said to close the bridge and call `establish()` next, and forbade every `LogRepository.append` between close and establish. Task 4 Step 4 said to use "the plain-HTTP probe destination" without naming the QA target.
- **Replacement decision:** The under-lock sequence keeps the epoch re-check, `prepareRealEstablishment` role preconditions, clear-then-close of only the returned bridge handle, the existing exact rotation-bridge release audit log, then `establish()` with no new I/O after that log. `LiveProxy` is installed only after non-null `establish()`, and the current TUN/geofile/protector/`startXray`/`Xray core started` order is unchanged. Device packet capture follows QA Test 23a: exclude server A/B, generate traffic to a tester-chosen plain-HTTP destination such as `http://example.com`, and pass only when no packet to that destination appears on the underlying interface.
- **Reason:** Review found the step could be read as deleting the active-transition precondition, installing `LiveProxy` before establishment, dropping the QA-required release log, or capturing the fixed health-probe URL instead of the tester-chosen plain-HTTP destination.
- **Affected sections:** Global Constraints, Task 3 Step 3, Task 4 Steps 4 and 5, Capture Plan Self-Review.

### 2026-09-22 — Establish-first handover alignment

- **Previous decision:** The under-lock sequence cleared the bridge owner, closed the handle, emitted `Failover: rotation bridge released (handing over to the newly established tunnel)`, and called `Builder.establish()` next, with no new I/O after that audit. Test 23a's packet window was specifically the interval between that audit and `TUN established with fd=…`. The handover comment was required to stay release-then-establish. That order is still the current production baseline.
- **Replacement decision:** Preparation stays before the lock. `UnreadRotationBridge` stays authoritative through `establish()`. `null`/throw leaves it unchanged, with no release audit, close, detach, or live owner. A non-null candidate installs `LiveProxy` immediately; the old bridge is closed once as a bounded local cleanup handle and is never re-adopted, detached, or retried by raw fd. Close failure is non-fatal deferred state. The log order is `TUN established with fd=…`, optional exact close warning, mandatory release audit on a rotation, then the existing geofile/protector/Xray diagnostics. Diagnostic-append failures cannot enter the bring-up failure funnel or reclassify `LiveProxy`. Test 23a packet capture covers the whole rotation, and logs do not prove the policy. Initial start and revive emit no release audit.
- **Reason:** The 2026-09-22 spec amendment replaced release-first because releasing the bridge before `establish()` opens an interface-free interval that Android's documented replacement contract makes unnecessary. This alignment does not implement the change and does not authorize `FailoverEngine`/`TunnelHost` work. The 2026-09-04 engine contract-recovery gate remains binding.
- **Affected sections:** Goal, Global Constraints, Task 3 Step 3, Task 4 Steps 4, 5, and 6, Capture Plan Self-Review, and this Change Control section. The 2026-09-21 handover entry above is retained as history and is superseded for candidate-handover order.
