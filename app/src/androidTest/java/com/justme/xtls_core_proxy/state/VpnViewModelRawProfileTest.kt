package com.justme.xtls_core_proxy.state

import android.content.Context
import androidx.core.content.edit
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.justme.xtls_core_proxy.db.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * addRawProfile is the DEBUG-only "unrestricted" insert: it must store the config byte-for-byte
 * (no makeSecureDns / toProfileStorageConfig) and activate the new row via the sanctioned
 * ActiveProfileRepository writer. Mirrors ActiveProfileRepositoryTest's shared-global-state setup
 * (in-memory DB + cleared vpn_prefs + resetForTests); assumes sequential execution.
 */
@RunWith(AndroidJUnit4::class)
class VpnViewModelRawProfileTest {

    private lateinit var context: Context
    private lateinit var testDb: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        testDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        AppDatabase.setInstanceForTests(testDb)
        clearPrefs()
        ActiveProfileRepository.resetForTests()
    }

    @After
    fun tearDown() {
        ActiveProfileRepository.resetForTests()
        clearPrefs()
        testDb.close()
        AppDatabase.setInstanceForTests(null)
    }

    private fun clearPrefs() {
        context.getSharedPreferences("vpn_prefs", Context.MODE_PRIVATE).edit { clear() }
    }

    @Test
    fun addRawProfile_storesConfigVerbatimAndActivatesIt() = runBlocking {
        val vm = VpnViewModel(ApplicationProvider.getApplicationContext())
        val raw = "not json — this is deliberately unbuildable"

        vm.addRawProfile("DEBUG raw", raw).join()

        val dao = AppDatabase.get(context).profileDao()
        val stored = dao.getFirst()!!
        assertEquals("config must be stored byte-for-byte, no ConfigBuilder mutation", raw, stored.config)
        assertEquals("DEBUG raw", stored.name)
        assertEquals("the new row must be the active profile", stored.id, ActiveProfileRepository.getActiveProfileId(context))
    }
}
