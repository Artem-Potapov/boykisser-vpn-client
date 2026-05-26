package com.justme.xtls_core_proxy.state

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import com.justme.xtls_core_proxy.db.AppDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ActiveProfileRepository {
    private const val PREFS_NAME = "vpn_prefs"
    private const val KEY_ACTIVE_PROFILE_ID = "active_profile_id"
    private const val SENTINEL = -1L

    @Volatile private var initialized = false
    private val _activeProfileIdFlow = MutableStateFlow<Long?>(null)

    /**
     * Hot StateFlow of the active profile id. Updated by every successful
     * setActiveProfileId / pickOrPersistActive write. Lazy-initialized from
     * SharedPreferences on the first call to any accessor.
     *
     * Consumers (e.g. VpnViewModel) should observe this rather than reading
     * getActiveProfileId at construction time — tile-initiated writes from
     * another component propagate via this flow.
     *
     * Precondition: every writer of vpn_prefs.active_profile_id goes through
     * setActiveProfileId(). Bypassing this API (e.g. editing prefs directly)
     * will desync the flow from disk. Enforced by code review, not by hook.
     */
    val activeProfileIdFlow: StateFlow<Long?> = _activeProfileIdFlow.asStateFlow()

    fun getActiveProfileId(context: Context): Long? {
        ensureInitialized(context)
        return _activeProfileIdFlow.value
    }

    fun setActiveProfileId(context: Context, id: Long?) {
        ensureInitialized(context)
        val appCtx = context.applicationContext
        appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            if (id != null) putLong(KEY_ACTIVE_PROFILE_ID, id)
            else remove(KEY_ACTIVE_PROFILE_ID)
        }
        _activeProfileIdFlow.value = id
    }

    suspend fun pickOrPersistActive(context: Context): Long? {
        val appCtx = context.applicationContext
        val dao = AppDatabase.get(appCtx).profileDao()

        val stored = getActiveProfileId(appCtx)
        if (stored != null && dao.getById(stored) != null) return stored

        val first = dao.getFirst()
        if (first == null) {
            if (stored != null) setActiveProfileId(appCtx, null)
            return null
        }
        setActiveProfileId(appCtx, first.id)
        return first.id
    }

    private fun ensureInitialized(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val stored = prefs.getLong(KEY_ACTIVE_PROFILE_ID, SENTINEL)
                .takeIf { it != SENTINEL }
            _activeProfileIdFlow.value = stored
            initialized = true
        }
    }

    /**
     * Test-only: clear the in-memory cache + flow so the next accessor
     * re-reads SharedPreferences. Tests should call this in @Before after
     * clearing the prefs file (or building a fresh AppDatabase) so the flow
     * doesn't carry state across tests.
     */
    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    internal fun resetForTests() {
        synchronized(this) {
            _activeProfileIdFlow.value = null
            initialized = false
        }
    }
}
