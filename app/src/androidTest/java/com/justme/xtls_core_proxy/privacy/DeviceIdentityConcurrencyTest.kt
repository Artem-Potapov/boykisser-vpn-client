package com.justme.xtls_core_proxy.privacy

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Real-SharedPreferences companion to the mock-based race test in DeviceIdentityRepositoryTest
 * (JVM). The unit test proves the double-checked lock against a stateful mock; this proves the same
 * one-stable-HWID invariant against the platform SharedPreferences implementation actually used at
 * runtime — the store whose in-memory map is what concurrent load() calls read from and mint into.
 *
 * The race it guards: on the first launch after this feature ships, no HWID is stored and
 * VpnViewModel.refreshAllStaleSubscriptions launches one IO coroutine per stale subscription, each
 * calling DeviceIdentityRepository.load() first. A check-then-act mint would hand several of them
 * different HWIDs in one round and burn a panel device slot apiece.
 *
 * Non-destructive by design: the whole xray_prefs store is snapshotted in @Before and restored in
 * @After, so a real device's HWID and every other setting survive the run. This is deliberately
 * stricter than the clear()-in-@Before convention some sibling suites use — leaving a throwaway
 * minted HWID behind is exactly the harm this feature exists to prevent.
 */
@RunWith(AndroidJUnit4::class)
class DeviceIdentityConcurrencyTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = context.getSharedPreferences("xray_prefs", Context.MODE_PRIVATE)
    private lateinit var snapshot: Map<String, Any?>

    @Before
    fun snapshotAndSimulateFirstLaunch() {
        snapshot = prefs.all.toMap()
        // The race only exists before any HWID is stored — reproduce that first-launch state.
        prefs.edit { remove("hwid_value") }
    }

    @After
    fun restorePrefs() {
        prefs.edit {
            clear()
            for ((k, v) in snapshot) when (v) {
                is Boolean -> putBoolean(k, v)
                is Int -> putInt(k, v)
                is Long -> putLong(k, v)
                is Float -> putFloat(k, v)
                is String -> putString(k, v)
                is Set<*> -> @Suppress("UNCHECKED_CAST") putStringSet(k, v as Set<String>)
            }
        }
    }

    @Test
    fun concurrentFirstLoads_mintExactlyOneHwid_onRealPrefs() {
        val racers = 16
        val startGate = CountDownLatch(1)
        val finished = CountDownLatch(racers)
        val minted = ConcurrentHashMap.newKeySet<String>()
        val pool = Executors.newFixedThreadPool(racers)
        try {
            repeat(racers) {
                pool.execute {
                    startGate.await()
                    minted.add(DeviceIdentityRepository.load(context).hwid)
                    finished.countDown()
                }
            }
            startGate.countDown() // release all racers at once to maximize the overlap window
            assertTrue("racing load() calls did not finish", finished.await(10, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }

        assertEquals("concurrent first reads must agree on one HWID", 1, minted.size)
        assertEquals(
            "the agreed HWID must be the one persisted",
            minted.single(),
            prefs.getString("hwid_value", null),
        )
    }

    @Test
    fun resetHwid_mintsFresh_evenWithStoredValue_onRealPrefs() {
        // resetHwid must NOT share load()'s double-checked read, or "Reset HWID" would no-op whenever
        // a value already exists — which, after the first load, is always.
        val first = DeviceIdentityRepository.load(context).hwid
        val reset = DeviceIdentityRepository.resetHwid(context)

        assertNotEquals(first, reset)
        assertEquals(reset, prefs.getString("hwid_value", null))
    }
}
