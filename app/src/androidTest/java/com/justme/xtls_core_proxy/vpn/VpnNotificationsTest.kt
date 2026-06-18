package com.justme.xtls_core_proxy.vpn

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VpnNotificationsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

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
}
