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

    /** Channel for the routine, informational "switched server" notice. Default importance. */
    const val FAILOVER_CHANNEL_ID = "xray_vpn_failover_channel"

    /**
     * Channel for the give-up alert, separate from [FAILOVER_CHANNEL_ID] by design.
     *
     * Giving up re-establishes a blackhole tunnel — the user's traffic is deliberately dropped
     * until they act — so this must be able to heads-up, and a user who mutes the routine switch
     * notice must NOT thereby mute this one. Android also ignores app-side importance increases
     * on an existing channel, so a shared channel could never be promoted after the fact.
     */
    const val FAILOVER_BLACKHOLE_CHANNEL_ID = "xray_vpn_failover_blackhole_channel"

    /**
     * The "switched server" notice gets its OWN id, NOT 1101/1102/1103. A notification's channel
     * is fixed at its first post (see [EXPOSED_NOTIFICATION_ID]), so reusing an allocated id would
     * weld this notice to that channel's importance.
     */
    const val FAILOVER_NOTIFICATION_ID = 1104

    /**
     * The give-up alert gets its own id too, distinct from [FAILOVER_NOTIFICATION_ID]: it lands on
     * the high-importance [FAILOVER_BLACKHOLE_CHANNEL_ID], and a fresh id is what makes a fresh
     * post adopt that channel instead of inheriting the routine notice's default one.
     */
    const val FAILOVER_BLACKHOLE_NOTIFICATION_ID = 1105

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

    /**
     * Creates the informational failover channel. Default importance on purpose: an automatic
     * server switch is routine housekeeping and must not heads-up like the exposure alert.
     */
    fun createFailoverChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            FAILOVER_CHANNEL_ID,
            localized(context, R.string.failover_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = localized(context, R.string.failover_channel_description) }
        manager.createNotificationChannel(channel)
    }

    /**
     * Creates the high-importance channel for the give-up alert. It is a separate channel — not a
     * higher importance on [FAILOVER_CHANNEL_ID] — because Android ignores app-side importance
     * increases on an existing channel, and because muting routine switch notices must not also
     * mute "your traffic is blocked".
     */
    fun createFailoverBlackholeChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            FAILOVER_BLACKHOLE_CHANNEL_ID,
            localized(context, R.string.failover_blackhole_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = localized(context, R.string.failover_blackhole_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * Tap-intent for the failover notices, matching [buildExposed]'s inlined shape. There is no
     * shared mainActivityIntent() helper in this file and this does not add one — extracting it
     * would pull the request-code collision surface across ids 1101/1102/1103/1104/1105 into scope.
     */
    private fun failoverContentIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    /**
     * Posts the "switched server" notice under [FAILOVER_NOTIFICATION_ID] on the default-importance
     * [FAILOVER_CHANNEL_ID]. `NotificationManager.notify()` is a silent no-op (does not throw) when
     * POST_NOTIFICATIONS is denied, so this never stalls the rotation that called it.
     */
    fun postFailover(context: Context, fromName: String, toName: String) {
        createFailoverChannel(context)
        val body = localized(context, R.string.failover_notification_body, fromName, toName)
        val notification = NotificationCompat.Builder(context, FAILOVER_CHANNEL_ID)
            .setSmallIcon(R.drawable.boykisser_notification_icon)
            .setContentTitle(localized(context, R.string.failover_notification_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(failoverContentIntent(context))
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(FAILOVER_NOTIFICATION_ID, notification)
    }

    /**
     * Posts the give-up alert under [FAILOVER_BLACKHOLE_NOTIFICATION_ID] on the high-importance
     * [FAILOVER_BLACKHOLE_CHANNEL_ID]. Failover has re-established a blackhole tunnel, so the
     * user's traffic is deliberately dropped until they pick a server or stop the VPN — hence the
     * separate, alerting channel. Like [postFailover], notify() is a silent no-op when
     * POST_NOTIFICATIONS is denied, so this never stalls the caller.
     */
    fun postFailoverBlackholed(context: Context) {
        createFailoverBlackholeChannel(context)
        val body = localized(context, R.string.failover_blackhole_body)
        val notification = NotificationCompat.Builder(context, FAILOVER_BLACKHOLE_CHANNEL_ID)
            .setSmallIcon(R.drawable.boykisser_notification_icon)
            .setColor(ContextCompat.getColor(context, R.color.warning_red))
            .setContentTitle(localized(context, R.string.failover_blackhole_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(failoverContentIntent(context))
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(FAILOVER_BLACKHOLE_NOTIFICATION_ID, notification)
    }

    /**
     * Removes the give-up alert once traffic is no longer blackholed — the user reconnected or
     * picked another server, a later rotation succeeded, or the VPN stopped. The alert announces a
     * transient state, so leaving it up would actively mislead: it would claim the internet is off
     * while it is working.
     *
     * A dedicated cancel is required for the same reason [cancelExposed] is: this alert lives under
     * its own id ([FAILOVER_BLACKHOLE_NOTIFICATION_ID]), separate from the ongoing FGS notification
     * ([NOTIFICATION_ID]), so `stopForeground` does not clear it.
     *
     * The routine "switched server" notice ([FAILOVER_NOTIFICATION_ID]) needs no counterpart: it is
     * `setAutoCancel(true)` and reports a completed event rather than an ongoing state.
     */
    fun cancelFailoverBlackholed(context: Context) {
        context.getSystemService(NotificationManager::class.java)
            .cancel(FAILOVER_BLACKHOLE_NOTIFICATION_ID)
    }
}
