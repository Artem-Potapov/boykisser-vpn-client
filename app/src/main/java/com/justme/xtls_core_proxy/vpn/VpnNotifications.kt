package com.justme.xtls_core_proxy.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.justme.xtls_core_proxy.MainActivity
import com.justme.xtls_core_proxy.R
import com.justme.xtls_core_proxy.i18n.SupportedLanguage

/**
 * Exposure-notification surface, extracted from [XrayVpnService] so the new
 * high-importance "VPN off for all apps" channel + notification can be exercised
 * in an instrumented test without standing up the service (services can't be
 * instantiated directly). The connected/error notifications stay in the service
 * unchanged — this is presentation-only and additive.
 */
internal object VpnNotifications {
    const val EXPOSED_CHANNEL_ID = "xray_vpn_exposed_channel"

    /**
     * Shared with [XrayVpnService] so the exposed notification REPLACES the
     * ongoing foreground-service notification in place (same id) rather than
     * stacking a second one.
     */
    const val NOTIFICATION_ID = 1101

    private fun localized(context: Context, @StringRes resId: Int, vararg args: Any): String =
        SupportedLanguage.localize(context).getString(resId, *args)

    /**
     * Creates the dedicated high-importance exposure channel. A new channel id is
     * required because Android ignores app-side importance increases on an
     * existing channel — the silent low channel can't be promoted to heads-up.
     */
    fun createExposedChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            EXPOSED_CHANNEL_ID,
            localized(context, R.string.vpn_exposed_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply { description = localized(context, R.string.vpn_exposed_channel_desc) }
        manager.createNotificationChannel(channel)
    }

    /** Builds the alarming exposure notification (red accent, BigText, trigger label). */
    fun buildExposed(context: Context, triggerLabel: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(context, EXPOSED_CHANNEL_ID)
            .setSmallIcon(R.drawable.boykisser_notification_icon)
            .setColor(ContextCompat.getColor(context, R.color.warning_red))
            .setContentTitle(localized(context, R.string.vpn_exposed_title))
            .setContentText(localized(context, R.string.vpn_exposed_text, triggerLabel))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(localized(context, R.string.vpn_exposed_big, triggerLabel))
            )
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }

    /**
     * Posts the exposure notification under [NOTIFICATION_ID] (in place).
     * `NotificationManager.notify()` is a silent no-op (does not throw) when
     * POST_NOTIFICATIONS is denied, so this never stalls the caller.
     */
    fun postExposed(context: Context, triggerLabel: String) {
        context.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildExposed(context, triggerLabel))
    }
}
