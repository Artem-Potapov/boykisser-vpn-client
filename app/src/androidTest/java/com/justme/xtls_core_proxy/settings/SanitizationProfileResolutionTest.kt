package com.justme.xtls_core_proxy.settings

import android.content.Context
import androidx.core.content.edit
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.justme.xtls_core_proxy.db.AppDatabase
import com.justme.xtls_core_proxy.db.Profile
import com.justme.xtls_core_proxy.state.ActiveProfileRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression for the Config Sanitization dead-end empty state: a user with imported profiles but
 * no *persisted* active profile (never connected → `vpn_prefs` has no `active_profile_id`) must
 * still get a subject profile to analyze. Before the fix the screen read `getActiveProfileId`
 * directly, which is null until a Connect/tile/service action persists it, so the screen showed
 * "Select a profile…" forever.
 *
 * Mirrors ActiveProfileRepositoryTest's shared-global-state setup: in-memory DB via
 * setInstanceForTests + cleared vpn_prefs + resetForTests. Assumes sequential execution.
 */
@RunWith(AndroidJUnit4::class)
class SanitizationProfileResolutionTest {

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

    /** The exact reported device state: profiles exist, no active id persisted → resolve first. */
    @Test
    fun noActiveId_butProfileExists_resolvesFirstProfile() = runBlocking {
        val dao = AppDatabase.get(context).profileDao()
        val firstId = dao.insert(Profile(name = "proxy", config = "cfg"))
        dao.insert(Profile(name = "beta", config = "y"))

        val profile = resolveSanitizationSubjectProfile(context)

        assertNotNull("expected a subject profile, got null (the dead-end empty state)", profile)
        assertEquals(firstId, profile!!.id)
    }

    /** Read-only contract: resolving must NOT persist an active id (unlike pickOrPersistActive). */
    @Test
    fun resolving_doesNotPersistActiveId() = runBlocking {
        val dao = AppDatabase.get(context).profileDao()
        dao.insert(Profile(name = "proxy", config = "cfg"))

        resolveSanitizationSubjectProfile(context)

        assertNull(ActiveProfileRepository.getActiveProfileId(context))
    }

    /** A stale persisted id (profile deleted, e.g. by a subscription refresh) falls back to first. */
    @Test
    fun staleActiveId_fallsBackToFirstProfile() = runBlocking {
        val dao = AppDatabase.get(context).profileDao()
        val firstId = dao.insert(Profile(name = "proxy", config = "cfg"))
        ActiveProfileRepository.setActiveProfileId(context, 99_999L) // not in DB

        val profile = resolveSanitizationSubjectProfile(context)

        assertNotNull(profile)
        assertEquals(firstId, profile!!.id)
    }

    /** A valid persisted active id resolves to exactly that profile, not merely the first. */
    @Test
    fun validActiveId_resolvesThatProfile() = runBlocking {
        val dao = AppDatabase.get(context).profileDao()
        dao.insert(Profile(name = "first", config = "a"))
        val secondId = dao.insert(Profile(name = "second", config = "b"))
        ActiveProfileRepository.setActiveProfileId(context, secondId)

        val profile = resolveSanitizationSubjectProfile(context)

        assertNotNull(profile)
        assertEquals(secondId, profile!!.id)
    }

    /** Genuinely empty DB → null → the screen legitimately shows the empty state. */
    @Test
    fun emptyDb_returnsNull() = runBlocking {
        assertNull(resolveSanitizationSubjectProfile(context))
    }
}
