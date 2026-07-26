package com.justme.xtls_core_proxy.privacy

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DeviceIdentityRepositoryTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var context: Context

    @Before
    fun setUp() {
        editor = mock {
            on { putBoolean(any(), any()) } doReturn it
            on { putString(any(), any()) } doReturn it
        }
        prefs = mock {
            on { edit() } doReturn editor
        }
        context = mock {
            on { getSharedPreferences(eq("xray_prefs"), eq(Context.MODE_PRIVATE)) } doReturn prefs
        }
    }

    @Test
    fun formatHwid_isSixteenLowercaseHex() {
        val re = Regex("^[0-9a-f]{16}$")
        assertTrue(re.matches(DeviceIdentityRepository.formatHwid(0L)))
        assertTrue(re.matches(DeviceIdentityRepository.formatHwid(-1L)))
        assertTrue(re.matches(DeviceIdentityRepository.formatHwid(1234567890123456789L)))
        assertEquals("0000000000000000", DeviceIdentityRepository.formatHwid(0L))
        assertEquals("ffffffffffffffff", DeviceIdentityRepository.formatHwid(-1L))
    }

    @Test
    fun load_mintsAndPersistsHwid_whenAbsent() {
        // No stored hwid -> repository mints one and writes it back.
        whenever(prefs.getString(eq("hwid_value"), eq(null))).thenReturn(null)
        whenever(prefs.getBoolean(eq("hwid_send"), any())).thenReturn(true)
        whenever(prefs.getString(eq("hwid_mode"), eq(null))).thenReturn("REAL_DEVICE")
        whenever(prefs.getBoolean(eq("hwid_custom_enabled"), any())).thenReturn(false)
        whenever(prefs.getString(eq("hwid_ua_mode"), eq(null))).thenReturn("DEFAULT")

        val settings = DeviceIdentityRepository.load(context)

        assertTrue(Regex("^[0-9a-f]{16}$").matches(settings.hwid))
        verify(editor).putString(eq("hwid_value"), eq(settings.hwid))
        verify(editor).apply()
    }

    @Test
    fun load_returnsStoredHwid_whenPresent() {
        whenever(prefs.getString(eq("hwid_value"), eq(null))).thenReturn("a983997074675192")
        whenever(prefs.getBoolean(eq("hwid_send"), any())).thenReturn(true)
        whenever(prefs.getString(eq("hwid_mode"), eq(null))).thenReturn("ANDROID")
        whenever(prefs.getString(eq("hwid_android_version"), eq(null))).thenReturn("16")
        whenever(prefs.getString(eq("hwid_android_model"), eq(null))).thenReturn(null)
        whenever(prefs.getBoolean(eq("hwid_custom_enabled"), any())).thenReturn(false)
        whenever(prefs.getString(eq("hwid_ua_mode"), eq(null))).thenReturn("HAPP_LIKE")

        val settings = DeviceIdentityRepository.load(context)

        assertEquals("a983997074675192", settings.hwid)
        assertEquals(IdentityMode.ANDROID, settings.identityMode)
        assertEquals("16", settings.androidVersionPin)
        assertEquals(UserAgentMode.HAPP_LIKE, settings.userAgentMode)
    }

    @Test
    fun resetHwid_writesFreshSixteenHex() {
        val fresh = DeviceIdentityRepository.resetHwid(context)
        assertTrue(Regex("^[0-9a-f]{16}$").matches(fresh))
        verify(editor).putString(eq("hwid_value"), eq(fresh))
        verify(editor).apply()
    }

    @Test
    fun resetHwid_mintsFresh_evenWhenOneIsAlreadyStored() {
        // getOrMintHwid re-reads under the lock; resetHwid must NOT, or "Reset HWID" would no-op.
        whenever(prefs.getString(eq("hwid_value"), eq(null))).thenReturn("a983997074675192")

        val fresh = DeviceIdentityRepository.resetHwid(context)

        assertNotEquals("a983997074675192", fresh)
        verify(editor).putString(eq("hwid_value"), eq(fresh))
    }

    @Test
    fun load_mintsExactlyOneHwid_whenFirstReadsRaceAcrossThreads() {
        // refreshAllStaleSubscriptions launches one IO coroutine per stale subscription and each
        // calls load() as its first step. On the first launch after the feature ships, none of them
        // finds a stored HWID — a check-then-act mint would hand each a different one and burn a
        // panel device slot apiece. Needs a stateful prefs stand-in: the shared mock above always
        // answers null, which cannot observe another thread's write.
        val store = ConcurrentHashMap<String, String>()
        val statefulEditor: SharedPreferences.Editor = mock()
        whenever(statefulEditor.putString(any(), any())).thenAnswer { call ->
            store[call.getArgument<String>(0)] = call.getArgument(1)
            statefulEditor
        }
        val statefulPrefs: SharedPreferences = mock()
        whenever(statefulPrefs.edit()).thenReturn(statefulEditor)
        whenever(statefulPrefs.getString(any(), anyOrNull())).thenAnswer { call ->
            store[call.getArgument<String>(0)]
        }
        whenever(statefulPrefs.getBoolean(any(), any())).thenAnswer { call ->
            call.getArgument<Boolean>(1)
        }
        val statefulContext: Context = mock()
        whenever(statefulContext.getSharedPreferences(eq("xray_prefs"), eq(Context.MODE_PRIVATE)))
            .thenReturn(statefulPrefs)

        val racers = 16
        val startGate = CountDownLatch(1)
        val finished = CountDownLatch(racers)
        val minted = ConcurrentHashMap.newKeySet<String>()
        val pool = Executors.newFixedThreadPool(racers)
        try {
            repeat(racers) {
                pool.execute {
                    startGate.await()
                    minted.add(DeviceIdentityRepository.load(statefulContext).hwid)
                    finished.countDown()
                }
            }
            startGate.countDown()
            assertTrue("racing load() calls did not finish", finished.await(10, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }

        assertEquals("concurrent first reads must agree on one HWID", 1, minted.size)
    }
}
