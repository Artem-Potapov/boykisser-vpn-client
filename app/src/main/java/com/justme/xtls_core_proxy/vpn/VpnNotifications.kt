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
     * Id of the ongoing foreground-service status notification (connecting / connected /
     * paused). The exposed heads-up alert uses [EXPOSED_NOTIFICATION_ID] instead — see there.
     */
    const val NOTIFICATION_ID = 1101

    /**
     * The exposed heads-up alert is posted under its OWN id, NOT [NOTIFICATION_ID].
     *
     * A notification's channel is fixed at its first post. [NOTIFICATION_ID] is first
     * posted as the ongoing FGS notification on the low-importance channel, so re-posting
     * the exposed alert on that same id keeps it low and silent — it can never heads-up.
     * A fresh id is a fresh post, so the alert adopts the high-importance
     * [EXPOSED_CHANNEL_ID] and alerts as intended. (1102 is the error notification.)
     */
    const val EXPOSED_NOTIFICATION_ID = 1103

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
    fun buildExposed(
        context: Context,
        triggerLabel: String,
        deleteIntent: PendingIntent? = null,
    ): Notification {
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
            .setOnlyAlertOnce(true)
            .setDeleteIntent(deleteIntent)
            .build()
    }

    /**
     * Posts the exposure heads-up alert under [EXPOSED_NOTIFICATION_ID] — a separate id
     * from the ongoing FGS notification, so it lands on the high-importance channel and
     * can alert. `NotificationManager.notify()` is a silent no-op (does not throw) when
     * POST_NOTIFICATIONS is denied, so this never stalls the caller.
     */
    fun postExposed(context: Context, triggerLabel: String, deleteIntent: PendingIntent? = null) {
        context.getSystemService(NotificationManager::class.java)
            .notify(EXPOSED_NOTIFICATION_ID, buildExposed(context, triggerLabel, deleteIntent))
    }

    /**
     * Removes the exposed alert (on revive or stop). The ongoing FGS notification lives
     * under a different id ([NOTIFICATION_ID]) and is managed separately, so
     * `stopForeground` does not clear this one.
     */
    fun cancelExposed(context: Context) {
        context.getSystemService(NotificationManager::class.java)
            .cancel(EXPOSED_NOTIFICATION_ID)
    }
}
