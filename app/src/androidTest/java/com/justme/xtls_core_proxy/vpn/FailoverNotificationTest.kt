package com.justme.xtls_core_proxy.vpn

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-side counterpart of [FailoverNotificationIdsTest]: the JVM guard pins the constants,
 * this one pins the channel *importances* and the never-throws property, which need a real
 * NotificationManager.
 */
@RunWith(AndroidJUnit4::class)
class FailoverNotificationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun failoverNoticesUseTheirOwnIdsAndChannels() {
        // Regression guard: a notification's channel is welded at first post, so reusing 1101/1103
        // would inherit the wrong importance and the notice could never be muted independently.
        // The two failover notices must also be distinct from EACH OTHER — the routine switch is
        // informational, the give-up alert says traffic is deliberately blocked.
        assertNotEquals(VpnNotifications.NOTIFICATION_ID, VpnNotifications.FAILOVER_NOTIFICATION_ID)
        assertNotEquals(
            VpnNotifications.EXPOSED_NOTIFICATION_ID,
            VpnNotifications.FAILOVER_NOTIFICATION_ID
        )
        assertNotEquals(
            VpnNotifications.NOTIFICATION_ID,
            VpnNotifications.FAILOVER_BLACKHOLE_NOTIFICATION_ID
        )
        assertNotEquals(
            VpnNotifications.EXPOSED_NOTIFICATION_ID,
            VpnNotifications.FAILOVER_BLACKHOLE_NOTIFICATION_ID
        )
        assertNotEquals(
            VpnNotifications.FAILOVER_NOTIFICATION_ID,
            VpnNotifications.FAILOVER_BLACKHOLE_NOTIFICATION_ID
        )
        assertNotEquals(VpnNotifications.EXPOSED_CHANNEL_ID, VpnNotifications.FAILOVER_CHANNEL_ID)
        assertNotEquals(
            VpnNotifications.EXPOSED_CHANNEL_ID,
            VpnNotifications.FAILOVER_BLACKHOLE_CHANNEL_ID
        )
        assertNotEquals(
            VpnNotifications.FAILOVER_CHANNEL_ID,
            VpnNotifications.FAILOVER_BLACKHOLE_CHANNEL_ID
        )
        assertEquals(1104, VpnNotifications.FAILOVER_NOTIFICATION_ID)
        assertEquals(1105, VpnNotifications.FAILOVER_BLACKHOLE_NOTIFICATION_ID)
    }

    @Test
    fun failoverChannelIsDefaultImportance() {
        VpnNotifications.createFailoverChannel(context)

        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(VpnNotifications.FAILOVER_CHANNEL_ID)
        requireNotNull(channel) { "failover channel was not created" }
        // Informational, not a security exposure: it must not heads-up like the exposed alert.
        // Assumes a clean install: Android won't lower an app-requested importance and the user
        // hasn't changed it by hand.
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
    }

    @Test
    fun failoverBlackholeChannelIsHighImportance() {
        VpnNotifications.createFailoverBlackholeChannel(context)

        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(VpnNotifications.FAILOVER_BLACKHOLE_CHANNEL_ID)
        requireNotNull(channel) { "failover blackhole channel was not created" }
        // Giving up re-establishes a blackhole tunnel — the user's internet is deliberately off
        // until they act — so this one MUST be able to heads-up.
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
    }

    @Test
    fun postFailoverNeverThrows_evenWithoutPostNotificationsPermission() {
        VpnNotifications.postFailover(context, "NL-01", "DE-03")
    }

    @Test
    fun postFailoverBlackholedNeverThrows_evenWithoutPostNotificationsPermission() {
        VpnNotifications.postFailoverBlackholed(context)
    }
}
