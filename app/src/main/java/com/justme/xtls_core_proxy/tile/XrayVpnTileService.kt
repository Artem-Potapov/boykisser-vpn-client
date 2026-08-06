package com.justme.xtls_core_proxy.tile

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.justme.xtls_core_proxy.MainActivity
import com.justme.xtls_core_proxy.R
import com.justme.xtls_core_proxy.db.AppDatabase
import com.justme.xtls_core_proxy.i18n.SupportedLanguage
import com.justme.xtls_core_proxy.log.GiveUpOngoingLine
import com.justme.xtls_core_proxy.log.LogRepository
import com.justme.xtls_core_proxy.log.VpnConnectionState
import com.justme.xtls_core_proxy.log.vpnConnectionStateLabelRes
import com.justme.xtls_core_proxy.state.ActiveProfileRepository
import com.justme.xtls_core_proxy.vpn.XrayVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class XrayVpnTileService : TileService() {

    private val serviceScope = MainScope()
    private var listenJob: Job? = null
    private var clickJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        listenJob?.cancel()
        listenJob = serviceScope.launch {
            combine(
                LogRepository.connectionState,
                LogRepository.giveUpLine,
            ) { state, line -> state to line }
                .collect { (state, line) -> updateTile(state, line) }
        }
    }

    override fun onStopListening() {
        listenJob?.cancel()
        listenJob = null
        clickJob?.cancel()
        clickJob = null
        super.onStopListening()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        handleClick()
    }

    private fun handleClick() {
        val state = LogRepository.connectionState.value
        // THE SAME function decideTileClick's Stop gate calls, not a copy of it: this fast path used
        // to re-state the four live states by hand, and that duplicate drifted once and shipped a
        // tile that rendered as an active Stop control and did nothing. Sharing the predicate is
        // what puts this path under TileClickDecisionTest's whole-enum sweep.
        if (shouldStopOnTileClick(state)) {
            // Stop path needs no IO; only the dispatch waits for unlock.
            runOrDeferUnlock { executeDecision(TileClickDecision.Stop) }
            return
        }

        // Start path: do the DB lookup on IO immediately (it does not require
        // an unlocked device), then wrap only the Main-thread permission
        // decision + dispatch in unlockAndRun. This shrinks the time spent
        // inside the unlock callback to the minimum and avoids the case where
        // the device re-locks while we are still resolving the active profile.
        clickJob?.cancel()
        clickJob = serviceScope.launch(Dispatchers.IO) {
            val appCtx = applicationContext
            val profileId = ActiveProfileRepository.pickOrPersistActive(appCtx)

            withContext(Dispatchers.Main) {
                runOrDeferUnlock {
                    // Short-circuits via decideTileClick when profileId is
                    // null, so VpnService.prepare runs only when needed.
                    val needsVpn = profileId != null &&
                        VpnService.prepare(this@XrayVpnTileService) != null
                    val needsNotif = needsNotificationPermission()
                    executeDecision(
                        decideTileClick(state, profileId, needsVpn, needsNotif)
                    )
                }
            }
        }
    }

    private fun executeDecision(decision: TileClickDecision) {
        when (decision) {
            TileClickDecision.Stop -> sendStopIntent()
            TileClickDecision.NoProfileToast -> showNoProfileToast()
            is TileClickDecision.Start -> sendStartIntent(decision.profileId)
            is TileClickDecision.HandoffToMainActivity ->
                launchActivityForAutoConnect(decision.profileId)
        }
    }

    private fun showNoProfileToast() {
        val appCtx = applicationContext
        val toastText = SupportedLanguage.localize(appCtx)
            .getString(R.string.tile_toast_no_profiles)
        Toast.makeText(appCtx, toastText, Toast.LENGTH_LONG).show()
    }

    private fun runOrDeferUnlock(block: () -> Unit) {
        if (isLocked) unlockAndRun { block() } else block()
    }

    private fun needsNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) != PackageManager.PERMISSION_GRANTED
    }

    private fun sendStartIntent(profileId: Long) {
        val intent = Intent(this, XrayVpnService::class.java).apply {
            action = XrayVpnService.ACTION_START
            putExtra(XrayVpnService.EXTRA_PROFILE_ID, profileId)
        }
        startForegroundService(intent)
    }

    private fun sendStopIntent() {
        // XrayVpnService is a foreground service, and onClick() only reaches
        // this method when the tile observed a state in which the service is
        // running (CONNECTING / CONNECTED / PAUSED / BLACKHOLED). Using
        // startForegroundService avoids the API 31+ background-start
        // restriction that can deny plain startService() if the tile's
        // foreground grant has already elapsed by the time we dispatch.
        val intent = Intent(this, XrayVpnService::class.java).apply {
            action = XrayVpnService.ACTION_STOP
            // Distinguishes this Off from ReconnectFlow's own settle stop — see
            // EXTRA_USER_INITIATED_STOP / LogRepository.signalUserStopRequested.
            putExtra(XrayVpnService.EXTRA_USER_INITIATED_STOP, true)
        }
        startForegroundService(intent)
    }

    private fun launchActivityForAutoConnect(profileId: Long) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_TILE_AUTOCONNECT, true)
            putExtra(MainActivity.EXTRA_TILE_PROFILE_ID, profileId)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            @SuppressLint("StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    private suspend fun updateTile(
        state: VpnConnectionState,
        giveUpLine: GiveUpOngoingLine? = null,
    ) {
        val tile = qsTile ?: return
        val ctx = SupportedLanguage.localize(applicationContext)
        when (state) {
            VpnConnectionState.DISCONNECTED -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = ctx.getString(vpnConnectionStateLabelRes(state))
            }
            VpnConnectionState.CONNECTING -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = ctx.getString(vpnConnectionStateLabelRes(state))
            }
            VpnConnectionState.CONNECTED -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = resolveConnectedLabel(ctx)
            }
            VpnConnectionState.PAUSED -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = ctx.getString(vpnConnectionStateLabelRes(state))
            }
            VpnConnectionState.BLACKHOLED -> {
                // ACTIVE, exactly like PAUSED: the service is still running and still owns a TUN,
                // so the tile must stay a working Stop control. Mapping it to INACTIVE (as ERROR
                // is) would dispatch ACTION_START on tap, which startVpn no-ops with "VPN already
                // running" — a dead control in the one state where the user most needs a live one.
                // Label distinguishes the two contained outcomes via the recorded giveUpLine.
                tile.state = Tile.STATE_ACTIVE
                tile.label = ctx.getString(vpnConnectionStateLabelRes(state, giveUpLine))
            }
            VpnConnectionState.ERROR -> {
                // INACTIVE is unchanged and still an accepted compromise (see
                // docs/features/qs-tile-vpn-toggle.md): ERROR conflates a dying session with an
                // UNPROTECTED give-up, and one binary action cannot serve both.
                //
                // The LABEL is not forced into that compromise. It takes the recorded give-up line,
                // so an uncontained give-up reads "Not protected" instead of the same generic
                // "Error" a failed connection shows — an ordinary error has no line and keeps the
                // ordinary string.
                tile.state = Tile.STATE_INACTIVE
                tile.label = ctx.getString(vpnConnectionStateLabelRes(state, giveUpLine))
            }
        }
        // Explicit null clears any subtitle from older builds that wrote state
        // text into the subtitle field — without this the system-cached
        // subtitle can survive an app upgrade and render alongside the new
        // label.
        tile.subtitle = null
        tile.updateTile()
    }

    /**
     * When connected, render the active profile's name as the tile label
     * instead of the literal "Connected" string — gives the user immediate
     * confirmation of *which* profile is active. Falls back to the generic
     * `main_state_connected` string if the active profile id is missing or
     * the row has been deleted (race: profile deleted while connected) or
     * its name is blank.
     */
    private suspend fun resolveConnectedLabel(localized: android.content.Context): String {
        val appCtx = applicationContext
        val profileId = ActiveProfileRepository.getActiveProfileId(appCtx)
            ?: return localized.getString(R.string.main_state_connected)
        val name = withContext(Dispatchers.IO) {
            AppDatabase.get(appCtx).profileDao().getById(profileId)?.name
        }
        return name?.takeIf { it.isNotBlank() }
            ?: localized.getString(R.string.main_state_connected)
    }
}
