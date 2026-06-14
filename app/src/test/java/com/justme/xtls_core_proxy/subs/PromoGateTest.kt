package com.justme.xtls_core_proxy.subs

import com.justme.xtls_core_proxy.subs.PromoGate.Outcome
import com.justme.xtls_core_proxy.subs.PromoGate.Signal
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class PromoGateTest {

    private val before = LocalDate.of(2026, 7, 31)
    private val onDate = LocalDate.of(2026, 8, 1)
    private val after = LocalDate.of(2026, 8, 2)

    @Test fun signalFor_maps418ToFire() = assertEquals(Signal.FIRE, PromoGate.signalFor(418))
    @Test fun signalFor_maps409ToDisarm() = assertEquals(Signal.DISARM, PromoGate.signalFor(409))
    @Test fun signalFor_maps451ToRearm() = assertEquals(Signal.REARM, PromoGate.signalFor(451))
    @Test fun signalFor_mapsNullToUnknown() = assertEquals(Signal.UNKNOWN, PromoGate.signalFor(null))
    @Test fun signalFor_maps200ToUnknown() = assertEquals(Signal.UNKNOWN, PromoGate.signalFor(200))
    @Test fun signalFor_maps404ToUnknown() = assertEquals(Signal.UNKNOWN, PromoGate.signalFor(404))
    @Test fun signalFor_maps500ToUnknown() = assertEquals(Signal.UNKNOWN, PromoGate.signalFor(500))

    @Test fun fire_showsAlways_andPreservesLease() {
        assertEquals(Outcome(show = true, disarmed = false),
            PromoGate.evaluate(Signal.FIRE, wasDisarmed = false, today = before))
        assertEquals(Outcome(show = true, disarmed = true),
            PromoGate.evaluate(Signal.FIRE, wasDisarmed = true, today = before))
    }

    @Test fun fire_ignoresDateGate() {
        assertEquals(Outcome(show = true, disarmed = false),
            PromoGate.evaluate(Signal.FIRE, wasDisarmed = false, today = after))
        assertEquals(Outcome(show = true, disarmed = true),
            PromoGate.evaluate(Signal.FIRE, wasDisarmed = true, today = after))
    }

    @Test fun disarm_hidesAndSetsLease() {
        assertEquals(Outcome(show = false, disarmed = true),
            PromoGate.evaluate(Signal.DISARM, wasDisarmed = false, today = after))
        assertEquals(Outcome(show = false, disarmed = true),
            PromoGate.evaluate(Signal.DISARM, wasDisarmed = true, today = before))
    }

    @Test fun rearm_clearsLease_andDateGates() {
        assertEquals(Outcome(show = false, disarmed = false),
            PromoGate.evaluate(Signal.REARM, wasDisarmed = true, today = before))
        assertEquals(Outcome(show = true, disarmed = false),
            PromoGate.evaluate(Signal.REARM, wasDisarmed = true, today = onDate))
        assertEquals(Outcome(show = true, disarmed = false),
            PromoGate.evaluate(Signal.REARM, wasDisarmed = false, today = after))
    }

    @Test fun unknown_respectsLease_thenDateGates() {
        assertEquals(Outcome(show = false, disarmed = true),
            PromoGate.evaluate(Signal.UNKNOWN, wasDisarmed = true, today = after))
        assertEquals(Outcome(show = false, disarmed = false),
            PromoGate.evaluate(Signal.UNKNOWN, wasDisarmed = false, today = before))
        assertEquals(Outcome(show = true, disarmed = false),
            PromoGate.evaluate(Signal.UNKNOWN, wasDisarmed = false, today = onDate))
        assertEquals(Outcome(show = true, disarmed = false),
            PromoGate.evaluate(Signal.UNKNOWN, wasDisarmed = false, today = after))
    }
}
