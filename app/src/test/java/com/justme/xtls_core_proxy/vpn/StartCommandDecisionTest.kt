package com.justme.xtls_core_proxy.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class StartCommandDecisionTest {

    @Test
    fun actionStart_withProfile_startsThatProfile() {
        assertEquals(
            StartCommandDecision.StartProfile(42L),
            StartCommandDecision.decide(XrayVpnService.ACTION_START, 42L)
        )
    }

    @Test
    fun actionStart_withoutProfile_refuses() {
        assertEquals(
            StartCommandDecision.RefuseNoProfile,
            StartCommandDecision.decide(XrayVpnService.ACTION_START, StartCommandDecision.SENTINEL)
        )
    }

    @Test
    fun actionStop_stops() {
        assertEquals(
            StartCommandDecision.Stop,
            StartCommandDecision.decide(XrayVpnService.ACTION_STOP, StartCommandDecision.SENTINEL)
        )
    }

    @Test
    fun actionNotificationDismissed_repostsNotification() {
        assertEquals(
            StartCommandDecision.RepostNotification,
            StartCommandDecision.decide(
                XrayVpnService.ACTION_NOTIFICATION_DISMISSED,
                StartCommandDecision.SENTINEL
            )
        )
    }

    @Test
    fun nullAction_resolvesActiveProfile() {
        assertEquals(
            StartCommandDecision.StartActiveProfile,
            StartCommandDecision.decide(null, StartCommandDecision.SENTINEL)
        )
    }
}
