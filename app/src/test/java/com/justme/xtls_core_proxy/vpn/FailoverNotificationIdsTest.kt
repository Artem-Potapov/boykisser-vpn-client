package com.justme.xtls_core_proxy.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Welded-channel regression guard for the notification identity constants.
 *
 * A notification's channel is fixed at its FIRST post, so two notices that share an id also
 * share whatever channel won the race — that already caused one real bug (the exposed alert
 * re-posted on 1101 stayed on the silent channel and could never heads-up; see
 * docs/features/kill-on-foreground.md). Sharing a *channel* is just as load-bearing here:
 * the routine "switched server" notice and the "traffic is blackholed" alert must be mutable
 * independently, and Android ignores app-side importance increases on an existing channel.
 *
 * These are all `const val`, so they inline at this call site and no Android class is loaded —
 * which is why this guard can live in the plain-JVM suite that actually runs in CI, unlike the
 * device-only [VpnNotificationsTest] / FailoverNotificationTest.
 */
class FailoverNotificationIdsTest {

    @Test
    fun allNotificationIdsAreMutuallyDistinct() {
        val ids = mapOf(
            "NOTIFICATION_ID" to VpnNotifications.NOTIFICATION_ID,
            "EXPOSED_NOTIFICATION_ID" to VpnNotifications.EXPOSED_NOTIFICATION_ID,
            "FAILOVER_NOTIFICATION_ID" to VpnNotifications.FAILOVER_NOTIFICATION_ID,
            "FAILOVER_BLACKHOLE_NOTIFICATION_ID" to VpnNotifications.FAILOVER_BLACKHOLE_NOTIFICATION_ID,
            "KILL_SWITCH_NOT_APPLIED_NOTIFICATION_ID" to
                VpnNotifications.KILL_SWITCH_NOT_APPLIED_NOTIFICATION_ID,
        )

        assertEquals(
            "notification ids must be mutually distinct, got $ids",
            ids.size,
            ids.values.toSet().size,
        )
    }

    @Test
    fun allChannelIdsAreMutuallyDistinct() {
        val channels = mapOf(
            "EXPOSED_CHANNEL_ID" to VpnNotifications.EXPOSED_CHANNEL_ID,
            "FAILOVER_CHANNEL_ID" to VpnNotifications.FAILOVER_CHANNEL_ID,
            "FAILOVER_BLACKHOLE_CHANNEL_ID" to VpnNotifications.FAILOVER_BLACKHOLE_CHANNEL_ID,
        )

        assertEquals(
            "channel ids must be mutually distinct, got $channels",
            channels.size,
            channels.values.toSet().size,
        )
    }

    @Test
    fun failoverIdsArePinnedToTheirAllocatedValues() {
        // 1101 = ongoing FGS, 1102 = service error notification (private to XrayVpnService),
        // 1103 = exposure alert. Failover takes the next two slots and must not drift back
        // onto an allocated one.
        assertEquals(1104, VpnNotifications.FAILOVER_NOTIFICATION_ID)
        assertEquals(1105, VpnNotifications.FAILOVER_BLACKHOLE_NOTIFICATION_ID)
        // 1106 = "the kill-switch could not act". It shares the EXPOSED channel deliberately (ids
        // and channels are independent) but must never share an id, or it would replace 1103.
        assertEquals(1106, VpnNotifications.KILL_SWITCH_NOT_APPLIED_NOTIFICATION_ID)
    }
}
