package com.justme.xtls_core_proxy.nametheft

import com.justme.xtls_core_proxy.nametheft.NameTheftWarning.Outcome
import com.justme.xtls_core_proxy.nametheft.NameTheftWarning.Signal
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class NameTheftWarningTest {

    private val before = LocalDate.of(2026, 7, 31)
    private val onDate = LocalDate.of(2026, 8, 1)
    private val after = LocalDate.of(2026, 8, 2)

    @Test fun signalFor_maps418ToFire() = assertEquals(Signal.FIRE, NameTheftWarning.signalFor(418))
    @Test fun signalFor_maps409ToDisarm() = assertEquals(Signal.DISARM, NameTheftWarning.signalFor(409))
    @Test fun signalFor_maps451ToRearm() = assertEquals(Signal.REARM, NameTheftWarning.signalFor(451))
    @Test fun signalFor_mapsNullToUnknown() = assertEquals(Signal.UNKNOWN, NameTheftWarning.signalFor(null))
    @Test fun signalFor_maps200ToUnknown() = assertEquals(Signal.UNKNOWN, NameTheftWarning.signalFor(200))
    @Test fun signalFor_maps404ToUnknown() = assertEquals(Signal.UNKNOWN, NameTheftWarning.signalFor(404))
    @Test fun signalFor_maps500ToUnknown() = assertEquals(Signal.UNKNOWN, NameTheftWarning.signalFor(500))

    @Test fun fire_showsAlways_andPreservesLease() {
        assertEquals(Outcome(show = true, disarmed = false),
            NameTheftWarning.evaluate(Signal.FIRE, wasDisarmed = false, today = before))
        assertEquals(Outcome(show = true, disarmed = true),
            NameTheftWarning.evaluate(Signal.FIRE, wasDisarmed = true, today = before))
    }

    @Test fun disarm_hidesAndSetsLease() {
        assertEquals(Outcome(show = false, disarmed = true),
            NameTheftWarning.evaluate(Signal.DISARM, wasDisarmed = false, today = after))
        assertEquals(Outcome(show = false, disarmed = true),
            NameTheftWarning.evaluate(Signal.DISARM, wasDisarmed = true, today = before))
    }

    @Test fun rearm_clearsLease_andDateGates() {
        assertEquals(Outcome(show = false, disarmed = false),
            NameTheftWarning.evaluate(Signal.REARM, wasDisarmed = true, today = before))
        assertEquals(Outcome(show = true, disarmed = false),
            NameTheftWarning.evaluate(Signal.REARM, wasDisarmed = true, today = onDate))
        assertEquals(Outcome(show = true, disarmed = false),
            NameTheftWarning.evaluate(Signal.REARM, wasDisarmed = false, today = after))
    }

    @Test fun unknown_respectsLease_thenDateGates() {
        assertEquals(Outcome(show = false, disarmed = true),
            NameTheftWarning.evaluate(Signal.UNKNOWN, wasDisarmed = true, today = after))
        assertEquals(Outcome(show = false, disarmed = false),
            NameTheftWarning.evaluate(Signal.UNKNOWN, wasDisarmed = false, today = before))
        assertEquals(Outcome(show = true, disarmed = false),
            NameTheftWarning.evaluate(Signal.UNKNOWN, wasDisarmed = false, today = onDate))
        assertEquals(Outcome(show = true, disarmed = false),
            NameTheftWarning.evaluate(Signal.UNKNOWN, wasDisarmed = false, today = after))
    }
}
