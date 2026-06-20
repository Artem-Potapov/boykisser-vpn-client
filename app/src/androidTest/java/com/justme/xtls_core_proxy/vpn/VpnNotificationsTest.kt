package com.justme.xtls_core_proxy.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VpnNotificationsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun grantPostNotifications() {
        // Fresh installs/reinstalls default POST_NOTIFICATIONS to denied on API 33+ (One UI
        // resets it on reinstall), which makes NotificationManager.notify() a silent no-op.
        // Grant it via UiAutomation so the read-back assertions see the posted notifications.
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, "android.permission.POST_NOTIFICATIONS")
    }

    @Test
    fun createExposedChannel_isHighImportance() {
        VpnNotifications.createExposedChannel(context)

        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(VpnNotifications.EXPOSED_CHANNEL_ID)
        requireNotNull(channel) { "exposed channel was not created" }
        // Assumes a clean install (CI/emulator): Android won't lower an
        // app-requested importance, and the user hasn't manually changed it.
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
    }

    @Test
    fun postExposed_doesNotThrow() {
        // Degradation invariant: posting must never throw, so the state machine
        // cannot stall when POST_NOTIFICATIONS is denied (notify() is a silent
        // no-op when denied). Passes whether or not the permission is granted.
        VpnNotifications.createExposedChannel(context)
        VpnNotifications.postExposed(context, "Test App")
    }

    @Test
    fun postExposed_usesSeparateId_landingOnHighChannel_notWeldedToOngoingId() {
        // Regression for the "exposed alert never heads-up" bug. A notification's channel
        // is fixed at its FIRST post; id NOTIFICATION_ID (1101) is first posted as the
        // ongoing FGS notification on the LOW channel, so re-posting the exposed alert on
        // that same id keeps it LOW and silent. The fix posts the alert under a SEPARATE
        // id (EXPOSED_NOTIFICATION_ID), which is a fresh post and therefore adopts the
        // HIGH exposed channel and can heads-up.
        val manager = context.getSystemService(NotificationManager::class.java)

        assertNotEquals(VpnNotifications.NOTIFICATION_ID, VpnNotifications.EXPOSED_NOTIFICATION_ID)

        // Simulate the ongoing FGS notification already occupying id 1101 on the LOW channel.
        manager.createNotificationChannel(
            NotificationChannel("xray_vpn_channel", "ongoing", NotificationManager.IMPORTANCE_LOW)
        )
        manager.notify(
            VpnNotifications.NOTIFICATION_ID,
            NotificationCompat.Builder(context, "xray_vpn_channel")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Connected")
                .build()
        )
        VpnNotifications.createExposedChannel(context)

        VpnNotifications.postExposed(context, "Firefox")

        val exposed = manager.activeNotifications
            .firstOrNull { it.id == VpnNotifications.EXPOSED_NOTIFICATION_ID }
        requireNotNull(exposed) {
            "exposed alert not posted under EXPOSED_NOTIFICATION_ID (POST_NOTIFICATIONS denied?)"
        }
        // The crux: a fresh id => fresh post => the alert adopts the HIGH exposed channel.
        assertEquals(VpnNotifications.EXPOSED_CHANNEL_ID, exposed.notification.channelId)

        manager.cancel(VpnNotifications.NOTIFICATION_ID)
        VpnNotifications.cancelExposed(context)
    }

    @Test
    fun buildExposed_attachesDeleteIntent() {
        // The exposed notification must carry a deleteIntent so the service can re-post it
        // when the user swipes it away (Android 14+ makes ongoing FGS notifications
        // user-dismissable, with no flag to prevent it).
        val deleteIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent("com.justme.xtls_core_proxy.test.DISMISS"),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = VpnNotifications.buildExposed(context, "Test App", deleteIntent)
        assertEquals(deleteIntent, notification.deleteIntent)
    }
}
