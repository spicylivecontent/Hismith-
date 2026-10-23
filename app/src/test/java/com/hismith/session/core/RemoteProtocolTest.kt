package com.hismith.session.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteProtocolTest {

    private fun parse(s: String) = RemoteCommand.parse(JSONObject(s))

    @Test
    fun parsesValidCommands() {
        assertEquals(RemoteCommand.Stop, parse("""{"k":"stop"}"""))
        assertEquals(RemoteCommand.Speed(40), parse("""{"k":"speed","v":40}"""))
        assertEquals(RemoteCommand.AutoStart(10, 20, 70), parse("""{"k":"auto_start","min":10,"max":20,"intensity":70}"""))
        assertEquals(RemoteCommand.AutoStart(30, 30, 50), parse("""{"k":"auto_start","min":30,"max":5}"""))
        assertEquals(RemoteCommand.Lube(DoseKind.SUPER), parse("""{"k":"lube","kind":"super"}"""))
    }

    @Test
    fun rejectsInvalidInput() {
        assertNull(parse("""{"k":"speed","v":101}"""))
        assertNull(parse("""{"k":"speed","v":"50"}"""))
        assertNull(parse("""{"k":"speed","v":12.5}"""))
        assertNull(parse("""{"k":"speed"}"""))
        assertNull(parse("""{"k":"lube","kind":"manual"}"""))
        assertNull(parse("""{"k":"format_disk"}"""))
        assertNull(parse("""{"k":"auto_start","min":0,"max":10}"""))
        assertNull(RemoteCommand.parse(null))
    }

    @Test
    fun watchdogStopsThenRevokes() {
        val w = RemoteWatchdog(stopAfterMs = 4_000, revokeAfterMs = 8_000)
        w.start(0)
        w.heard(1_000)
        assertNull(w.check(2_000))
        w.lost(2_000)
        assertNull(w.check(4_900))
        assertEquals(RemoteWatchdog.Action.STOP, w.check(5_000))
        assertNull(w.check(6_000)) // only once
        assertEquals(RemoteWatchdog.Action.REVOKE, w.check(9_000))
        assertNull(w.check(20_000))
        // A revoked controller cannot come back by itself.
        w.heard(21_000)
        assertNull(w.check(40_000))
        assertTrue(w.wasRevoked)
    }

    @Test
    fun watchdogCatchesSilentConnections() {
        val w = RemoteWatchdog(4_000, 8_000)
        w.start(0)
        for (t in 0L..10_000L step 1_000) {
            w.heard(t)
            assertNull(w.check(t + 500))
        }
        // Heartbeats stop without any close event (half-open connection).
        assertEquals(RemoteWatchdog.Action.STOP, w.check(14_000))
    }

    @Test
    fun comingBackInTimeAvoidsStop() {
        val w = RemoteWatchdog(4_000, 8_000)
        w.start(0)
        w.lost(1_000)
        w.heard(3_000)
        assertNull(w.check(6_000))
    }
}
