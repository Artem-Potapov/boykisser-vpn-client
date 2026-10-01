# Implementation Plan — 2A Fail-Closed: `protect()`, Whole-App Tunneling, Resilient Startup

**Goal:** Move loop-avoidance from whole-app exclusion to socket-level `VpnService.protect()` so the app's own traffic is tunneled (not leaked), and make the service resurrect itself on always-on/boot starts and after a crash.
**Tech Stack:** Kotlin + Jetpack; Go 1.26 bridge built with gomobile into `app/libs/xray.aar`; Xray-core `v1.260327.1-0.20260609205355-da21a8f77f31` (`transport/internet.RegisterDialerController`); JUnit4 + mockito-kotlin 6.3.0; minSdk 29. No product flavors — unit test task is `:app:testDebugUnitTest`.
**Prerequisites:** Go + gomobile + Android SDK/NDK installed (the `.aar` is rebuilt from source, not checked in). The repo builds: `./scripts/build-xray-aar.bash` then `./gradlew :app:assembleDebug` succeed, and `./gradlew :app:testDebugUnitTest` passes, before starting.

## Coordination with 2B (do these hand in hand)

The dial controller this plan installs covers **all** Xray outbound sockets, including the 2B Cloudflare-DoH resolver query and the proxy link — so 2B's secure DNS stays reachable on the `protect()` bypass path once the whole app is tunneled. Do not register the protector in any narrower, per-socket way that would miss the DNS module. Sequencing is 2B first (done), then 2A.

## File Structure

- `xray-go/xray_bridge.go` — **Modify**: add the `Protector` interface, `RegisterProtector`, and a once-only install of the Xray dial controller.
- `app/src/main/java/com/justme/xtls_core_proxy/bridge/XrayBridge.kt` — **Modify**: add `registerProtector(vpnService)` (reflective; dynamic `Proxy` implementing the gomobile protector interface).
- `app/src/main/java/com/justme/xtls_core_proxy/split/SplitTunnelPlanner.kt` — **Create**: pure plan of allowed/disallowed packages with whole-app tunneling.
- `app/src/test/java/com/justme/xtls_core_proxy/split/SplitTunnelPlannerTest.kt` — **Create**: unit tests for the planner.
- `app/src/main/java/com/justme/xtls_core_proxy/vpn/StartCommandDecision.kt` — **Create**: pure `onStartCommand` decision (sealed).
- `app/src/test/java/com/justme/xtls_core_proxy/vpn/StartCommandDecisionTest.kt` — **Create**: unit tests for the decision.
- `app/src/main/java/com/justme/xtls_core_proxy/vpn/XrayVpnService.kt` — **Modify**: register the protector before `startXray`; apply `SplitTunnelPlanner` in `bringUpTunnel`; rewrite `onStartCommand` with `StartCommandDecision` + `START_REDELIVER_INTENT`; add `resolveActiveAndStart()`.

---

### Task 1: The `protect()` mechanism (Go dial controller + Kotlin protector facade)

This lands the mechanism while self-exclusion is **still in place**, so it changes nothing observable yet (Xray's sockets already bypass via exclusion; the protector is redundant for now). Task 2 removes self-exclusion and makes `protect()` load-bearing. No pure unit test (Go + gomobile + JNI — justified TDD exception); verified by build here and on-device in Task 2.

**Files:**
- Modify: `xray-go/xray_bridge.go`
- Modify: `app/src/main/java/com/justme/xtls_core_proxy/bridge/XrayBridge.kt`
- Modify: `app/src/main/java/com/justme/xtls_core_proxy/vpn/XrayVpnService.kt` (one call site)

**Step 1: Add the Go protector + dial controller**

In `xray-go/xray_bridge.go`, add `syscall` and the Xray `transport/internet` import, and the protector machinery. The updated import block:

```go
import (
	"fmt"
	"os"
	"strings"
	"sync"
	"syscall"

	"github.com/xtls/xray-core/common/platform"
	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/transport/internet"
	_ "github.com/xtls/xray-core/main/distro/all"
)
```

Add (top level, after the existing `var ( mu ... instance ... )` block):

```go
// Protector is implemented on the Android side. Protect must exclude the given
// socket fd from the VPN tun (VpnService.protect(fd)) so Xray's own outbound
// connections — the proxy link and the DoH resolver query — bypass the tun
// instead of looping back into it.
type Protector interface {
	Protect(fd int) bool
}

var (
	protectorMu      sync.Mutex
	currentProtector Protector
	controllerOnce   sync.Once
)

// RegisterProtector sets the protector used for all subsequent Xray dials and
// installs the dial controller exactly once. Call before StartXray; calling it
// again on a later start simply swaps in the new protector (last start wins).
func RegisterProtector(p Protector) {
	protectorMu.Lock()
	currentProtector = p
	protectorMu.Unlock()
	installDialController()
}

func installDialController() {
	controllerOnce.Do(func() {
		_ = internet.RegisterDialerController(func(network, address string, c syscall.RawConn) error {
			protectorMu.Lock()
			p := currentProtector
			protectorMu.Unlock()
			if p == nil {
				return nil
			}
			return c.Control(func(fd uintptr) {
				p.Protect(int(fd))
			})
		})
	})
}
```

**Step 2: Add the Kotlin facade method**

In `bridge/XrayBridge.kt`, add `registerProtector`. It reflectively finds `RegisterProtector` (consistent with the existing name-robust facade) and passes a dynamic `Proxy` implementing the gomobile-generated protector interface — robust to gomobile's package/method-name mangling, since the proxy matches the interface's single fd method by shape:

```kotlin
import android.net.VpnService
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
```

```kotlin
    fun registerProtector(vpnService: VpnService): Result<Unit> {
        return runCatching {
            val clazz = bridgeClass()
            val registerMethod = findMethod(clazz, listOf("RegisterProtector", "registerProtector"), 1)
            val protectorInterface = registerMethod.parameterTypes[0]
            val handler = InvocationHandler { proxy, method, args ->
                when (method.name) {
                    "equals" -> proxy === args?.getOrNull(0)
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "XrayProtectorProxy"
                    else -> {
                        // The sole gomobile interface method: protect(fd) -> Boolean.
                        val fd = (args?.getOrNull(0) as? Number)?.toInt()
                            ?: throw IllegalStateException("Unexpected protector callback: ${method.name}")
                        vpnService.protect(fd)
                    }
                }
            }
            val proxy = Proxy.newProxyInstance(
                protectorInterface.classLoader,
                arrayOf(protectorInterface),
                handler
            )
            registerMethod.invoke(null, proxy)
            Unit
        }
    }
```

**Step 3: Call it before `startXray`**

In `vpn/XrayVpnService.kt`, in `bringUpTunnel`, register the protector immediately before starting the core. Replace:

```kotlin
            XrayBridge.startXray(configJson, fd, geoAssetDir.absolutePath).getOrThrow()
            LogRepository.append("Xray core started")
```

with:

```kotlin
            // Loop-avoidance: Xray's own sockets bypass the tun via protect().
            // Must succeed before Xray dials, or (with self-exclusion removed in
            // Task 2) the proxy socket would route into the tun and loop.
            XrayBridge.registerProtector(this@XrayVpnService).getOrThrow()

            XrayBridge.startXray(configJson, fd, geoAssetDir.absolutePath).getOrThrow()
            LogRepository.append("Xray core started")
```

**Step 4: Build and confirm the reverse binding**

Commands:
```bash
./scripts/build-xray-aar.bash
./gradlew :app:assembleDebug
```
Expected: the script regenerates `app/libs/xray.aar`; `assembleDebug` → `BUILD SUCCESSFUL`.

Confirm the generated protector interface so the `Proxy` binds to it (the facade resolves it by reflection, so no code change is needed, but verify it exists):
```bash
unzip -p app/libs/xray.aar classes.jar > /tmp/xray-classes.jar && javap -cp /tmp/xray-classes.jar 'xraybridge.Protector'
```
Expected: an interface with a single `boolean`-returning method taking one integer/long fd. If gomobile emitted it under a different package (e.g. `go.xraybridge.Protector`), the reflective `registerMethod.parameterTypes[0]` still resolves it at runtime; the `Proxy` is built against whatever that type is, so no change is required. (Fallback if gomobile rejects a `Proxy` for the reverse binding: replace the proxy with a typed class implementing the confirmed FQN/method.)

**Step 5: Commit**

```bash
git add xray-go/xray_bridge.go \
        app/src/main/java/com/justme/xtls_core_proxy/bridge/XrayBridge.kt \
        app/src/main/java/com/justme/xtls_core_proxy/vpn/XrayVpnService.kt
git commit -m "feat(vpn): wire VpnService.protect() into Xray's dialer via a Go dial controller"
```

---

### Task 2: Drop self-exclusion — whole-app tunneling

Now `protect()` becomes load-bearing. A pure planner makes the asymmetric self-handling testable; `bringUpTunnel` applies it.

**Files:**
- Create: `app/src/main/java/com/justme/xtls_core_proxy/split/SplitTunnelPlanner.kt`
- Create: `app/src/test/java/com/justme/xtls_core_proxy/split/SplitTunnelPlannerTest.kt`
- Modify: `app/src/main/java/com/justme/xtls_core_proxy/vpn/XrayVpnService.kt` (the split-tunnel block in `bringUpTunnel`)

**Step 1: Write the failing tests**

Create `app/src/test/java/com/justme/xtls_core_proxy/split/SplitTunnelPlannerTest.kt`:

```kotlin
package com.justme.xtls_core_proxy.split

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitTunnelPlannerTest {

    private val self = "com.justme.xtls_core_proxy"

    @Test
    fun allowOnly_alwaysIncludesSelf() {
        val plan = SplitTunnelPlanner.plan(
            SplitTunnelMode.ALLOW_ONLY, setOf("com.foo", "com.bar"), self
        )
        assertTrue(plan.allowedPackages.contains(self))
        assertTrue(plan.allowedPackages.contains("com.foo"))
        assertTrue(plan.allowedPackages.contains("com.bar"))
        assertTrue(plan.disallowedPackages.isEmpty())
    }

    @Test
    fun allowOnly_doesNotDuplicateSelfIfUserAddedIt() {
        val plan = SplitTunnelPlanner.plan(
            SplitTunnelMode.ALLOW_ONLY, setOf(self, "com.foo"), self
        )
        assertEquals(1, plan.allowedPackages.count { it == self })
    }

    @Test
    fun blockMode_doesNotDisallowSelf() {
        val plan = SplitTunnelPlanner.plan(
            SplitTunnelMode.BLOCK_ALL_EXCEPT_SELECTED, setOf("com.foo", self), self
        )
        assertFalse(plan.disallowedPackages.contains(self))
        assertTrue(plan.disallowedPackages.contains("com.foo"))
        assertTrue(plan.allowedPackages.isEmpty())
    }
}
```

**Step 2: Run the tests to confirm they fail**

Command: `./gradlew :app:testDebugUnitTest --tests "com.justme.xtls_core_proxy.split.SplitTunnelPlannerTest"`
Expected: FAIL — `unresolved reference: SplitTunnelPlanner` / `SplitTunnelPlan`.

**Step 3: Implement the planner**

Create `app/src/main/java/com/justme/xtls_core_proxy/split/SplitTunnelPlanner.kt`:

```kotlin
package com.justme.xtls_core_proxy.split

data class SplitTunnelPlan(
    val allowedPackages: List<String>,
    val disallowedPackages: List<String>
)

object SplitTunnelPlanner {
    /**
     * Whole-app tunneling: the app itself always rides the tunnel (its own
     * non-Xray traffic must be tunneled, not leak direct); Xray's own sockets
     * bypass the tun via VpnService.protect(), not via app exclusion.
     *
     * ALLOW_ONLY               -> tunnel the user's apps PLUS self (deduped).
     * BLOCK_ALL_EXCEPT_SELECTED -> tunnel everything except the user's apps;
     *                              self is never excluded.
     */
    fun plan(
        mode: SplitTunnelMode,
        userPackages: Set<String>,
        selfPackage: String
    ): SplitTunnelPlan = when (mode) {
        SplitTunnelMode.ALLOW_ONLY -> SplitTunnelPlan(
            allowedPackages = (userPackages - selfPackage).toList() + selfPackage,
            disallowedPackages = emptyList()
        )
        SplitTunnelMode.BLOCK_ALL_EXCEPT_SELECTED -> SplitTunnelPlan(
            allowedPackages = emptyList(),
            disallowedPackages = (userPackages - selfPackage).toList()
        )
    }
}
```

**Step 4: Run the tests to confirm they pass**

Command: `./gradlew :app:testDebugUnitTest --tests "com.justme.xtls_core_proxy.split.SplitTunnelPlannerTest"`
Expected: PASS (3 tests).

**Step 5: Apply the plan in `bringUpTunnel`**

In `vpn/XrayVpnService.kt`, replace the whole split-tunnel `when` block:

```kotlin
            val splitPrefs = SplitTunnelRepository.load(this@XrayVpnService)
            when (splitPrefs.mode) {
                SplitTunnelMode.ALLOW_ONLY -> {
                    if (splitPrefs.packages.isEmpty()) {
                        LogRepository.append("Split tunnel allow-only mode enabled with no selected apps")
                    }
                    splitPrefs.packages.forEach { pkg ->
                        if (pkg == packageName) {
                            LogRepository.append("Ignoring self package in allow-only split tunnel mode")
                            return@forEach
                        }
                        try {
                            builder.addAllowedApplication(pkg)
                        } catch (_: PackageManager.NameNotFoundException) {
                            LogRepository.append("Split tunnel skipped missing package: $pkg")
                        }
                    }
                }
                SplitTunnelMode.BLOCK_ALL_EXCEPT_SELECTED -> {
                    val blockedPackages = splitPrefs.packages + packageName
                    blockedPackages.forEach { pkg ->
                        try {
                            builder.addDisallowedApplication(pkg)
                        } catch (_: PackageManager.NameNotFoundException) {
                            LogRepository.append("Split tunnel skipped missing package: $pkg")
                        }
                    }
                }
            }
```

with:

```kotlin
            val splitPrefs = SplitTunnelRepository.load(this@XrayVpnService)
            val plan = SplitTunnelPlanner.plan(splitPrefs.mode, splitPrefs.packages.toSet(), packageName)
            plan.allowedPackages.forEach { pkg ->
                try {
                    builder.addAllowedApplication(pkg)
                } catch (_: PackageManager.NameNotFoundException) {
                    LogRepository.append("Split tunnel skipped missing package: $pkg")
                }
            }
            plan.disallowedPackages.forEach { pkg ->
                try {
                    builder.addDisallowedApplication(pkg)
                } catch (_: PackageManager.NameNotFoundException) {
                    LogRepository.append("Split tunnel skipped missing package: $pkg")
                }
            }
```

(`SplitTunnelMode` is already imported in this file; add the import for `SplitTunnelPlanner` if your IDE doesn't auto-add it — same package root `com.justme.xtls_core_proxy.split`.)

**Step 6: Build + on-device verification (this is where `protect()` is proven)**

Commands:
```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```
Expected: all unit tests pass; `BUILD SUCCESSFUL`.

On-device (the real proof — with self-exclusion gone, connecting at all means `protect()` is carrying the loop):
1. Connect to a working profile. Expected: it reaches CONNECTED and traffic flows. (If `protect()` were not working, Xray's proxy socket would route into the tun and loop — the connection would fail.)
2. Check the app's egress: with the VPN on, the app's perceived public IP should be the proxy exit's, and triggering a subscription refresh should route through the proxy (the fetch no longer goes direct). Confirm via the in-app logs and an IP-echo check.
3. Confirm no routing loop in the Xray logs (no repeated self-dials).

**Step 7: Commit**

```bash
git add app/src/main/java/com/justme/xtls_core_proxy/split/SplitTunnelPlanner.kt \
        app/src/test/java/com/justme/xtls_core_proxy/split/SplitTunnelPlannerTest.kt \
        app/src/main/java/com/justme/xtls_core_proxy/vpn/XrayVpnService.kt
git commit -m "feat(vpn): tunnel the whole app and rely on protect() for loop-avoidance"
```

---

### Task 3: Resilient startup — `START_REDELIVER_INTENT` + always-on/crash recovery

**Files:**
- Create: `app/src/main/java/com/justme/xtls_core_proxy/vpn/StartCommandDecision.kt`
- Create: `app/src/test/java/com/justme/xtls_core_proxy/vpn/StartCommandDecisionTest.kt`
- Modify: `app/src/main/java/com/justme/xtls_core_proxy/vpn/XrayVpnService.kt` (`onStartCommand` + a new helper)

**Step 1: Write the failing tests**

Create `app/src/test/java/com/justme/xtls_core_proxy/vpn/StartCommandDecisionTest.kt`:

```kotlin
package com.justme.xtls_core_proxy.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class StartCommandDecisionTest {

    @Test
    fun actionStart_withProfile_startsThatProfile() {
        assertEquals(
            StartCommandDecision.StartProfile(42L),
            StartCommandDecision.decide(XrayVpnService.ACTION_START, 42L)
        )
    }

    @Test
    fun actionStart_withoutProfile_refuses() {
        assertEquals(
            StartCommandDecision.RefuseNoProfile,
            StartCommandDecision.decide(XrayVpnService.ACTION_START, StartCommandDecision.SENTINEL)
        )
    }

    @Test
    fun actionStop_stops() {
        assertEquals(
            StartCommandDecision.Stop,
            StartCommandDecision.decide(XrayVpnService.ACTION_STOP, StartCommandDecision.SENTINEL)
        )
    }

    @Test
    fun nullAction_resolvesActiveProfile() {
        assertEquals(
            StartCommandDecision.StartActiveProfile,
            StartCommandDecision.decide(null, StartCommandDecision.SENTINEL)
        )
    }
}
```

**Step 2: Run the tests to confirm they fail**

Command: `./gradlew :app:testDebugUnitTest --tests "com.justme.xtls_core_proxy.vpn.StartCommandDecisionTest"`
Expected: FAIL — `unresolved reference: StartCommandDecision`.

**Step 3: Implement the decision**

Create `app/src/main/java/com/justme/xtls_core_proxy/vpn/StartCommandDecision.kt`:

```kotlin
package com.justme.xtls_core_proxy.vpn

sealed interface StartCommandDecision {
    data class StartProfile(val profileId: Long) : StartCommandDecision

    /** Headless start with no explicit profile (cold always-on / boot). Resolve the active profile. */
    data object StartActiveProfile : StartCommandDecision

    data object Stop : StartCommandDecision

    data object RefuseNoProfile : StartCommandDecision

    companion object {
        const val SENTINEL = -1L

        fun decide(action: String?, profileId: Long): StartCommandDecision = when (action) {
            XrayVpnService.ACTION_START ->
                if (profileId != SENTINEL) StartProfile(profileId) else RefuseNoProfile
            XrayVpnService.ACTION_STOP -> Stop
            // null = system-initiated always-on/boot start (no prior intent to redeliver).
            // A START_REDELIVER_INTENT restart re-delivers the last ACTION_START, so it
            // lands in the ACTION_START branch above with the original profile id.
            else -> StartActiveProfile
        }
    }
}
```

**Step 4: Run the tests to confirm they pass**

Command: `./gradlew :app:testDebugUnitTest --tests "com.justme.xtls_core_proxy.vpn.StartCommandDecisionTest"`
Expected: PASS (4 tests).

**Step 5: Rewrite `onStartCommand` and add the resolver**

In `vpn/XrayVpnService.kt`, replace the current `onStartCommand`:

```kotlin
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val profileId = intent.getLongExtra(EXTRA_PROFILE_ID, -1L)
                if (profileId == -1L) {
                    LogRepository.setConnectionState(VpnConnectionState.ERROR)
                    LogRepository.emitError(R.string.vpn_start_failed_error)
                    LogRepository.append("Refused to start: no profile ID provided")
                    stopSelf()
                } else {
                    startVpn(profileId)
                }
            }

            ACTION_STOP -> stopVpn()
        }
        return START_NOT_STICKY
    }
```

with:

```kotlin
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val profileId = intent?.getLongExtra(EXTRA_PROFILE_ID, StartCommandDecision.SENTINEL)
            ?: StartCommandDecision.SENTINEL
        when (val decision = StartCommandDecision.decide(intent?.action, profileId)) {
            is StartCommandDecision.StartProfile -> startVpn(decision.profileId)
            StartCommandDecision.StartActiveProfile -> resolveActiveAndStart()
            StartCommandDecision.Stop -> stopVpn()
            StartCommandDecision.RefuseNoProfile -> {
                LogRepository.setConnectionState(VpnConnectionState.ERROR)
                LogRepository.emitError(R.string.vpn_start_failed_error)
                LogRepository.append("Refused to start: no profile ID provided")
                stopSelf()
            }
        }
        // REDELIVER: an OS kill or process crash re-delivers the last ACTION_START
        // (profile id included) so the same profile reconnects. An explicit stop
        // (which calls stopSelf) is not redelivered.
        return START_REDELIVER_INTENT
    }

    private fun resolveActiveAndStart() {
        serviceScope.launch {
            val id = ActiveProfileRepository.pickOrPersistActive(this@XrayVpnService)
            if (id == null) {
                LogRepository.append("Always-on start: no active profile to bring up")
                stopSelf()
            } else {
                startVpn(id)
            }
        }
    }
```

Add the import if not present:
```kotlin
import com.justme.xtls_core_proxy.state.ActiveProfileRepository
```

> `stopVpn()` already ends with `stopSelf()` (and also `stopSelf()`s on its early-return path), so with `START_REDELIVER_INTENT` an explicit `ACTION_STOP` is not restarted. This is a property to **preserve**, not change — do not remove those `stopSelf()` calls.
>
> Kill-on-foreground edge: if the OS kills the service while it is paused by kill-on-foreground, redelivery reconnects the tun; the kill-switch monitor (started on connect in `applyKillSwitchPreferences`) re-evaluates on restart and re-pauses if the controlled app is still foreground, so this self-heals. No code change required, but confirm the monitor is re-armed after a redelivered start (it runs through the normal `startVpn` → `bringUpTunnel` → success path, so it is).

**Step 6: Build + on-device verification**

Commands:
```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```
Expected: all unit tests pass; `BUILD SUCCESSFUL`.

On-device:
1. **Always-on:** enable Always-on VPN for the app in system Settings → Network → VPN, reboot the device. Expected: the VPN comes up automatically on the active profile (the null-intent → `StartActiveProfile` → `pickOrPersistActive` path).
2. **Crash recovery:** with the VPN connected, kill the app process (`adb shell am kill com.justme.xtls_core_proxy`, or force-stop is too aggressive — use `am kill` which simulates a low-memory kill). Expected: the service restarts and reconnects the same profile (redelivered `ACTION_START`).
3. **Explicit stop stays stopped:** stop the VPN from the app/tile. Expected: it stays down; no auto-restart.

**Step 7: Commit**

```bash
git add app/src/main/java/com/justme/xtls_core_proxy/vpn/StartCommandDecision.kt \
        app/src/test/java/com/justme/xtls_core_proxy/vpn/StartCommandDecisionTest.kt \
        app/src/main/java/com/justme/xtls_core_proxy/vpn/XrayVpnService.kt
git commit -m "feat(vpn): resilient startup via START_REDELIVER_INTENT and always-on active-profile resolution"
```

---

## Final verification

```bash
./gradlew :app:testDebugUnitTest
./scripts/build-xray-aar.bash
./gradlew :app:assembleDebug
```
Expected: all unit tests pass (including `SplitTunnelPlannerTest`, `StartCommandDecisionTest`, and the existing suites); `.aar` builds; `BUILD SUCCESSFUL`.

Then the on-device sweep from Tasks 2 and 3 together: connect (app traffic tunneled, no loop), always-on reboot brings it up, a process kill reconnects, an explicit stop stays stopped.
