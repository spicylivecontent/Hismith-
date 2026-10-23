package com.hismith.session.core

import org.junit.Assert.assertEquals
import org.junit.Test

class StatsTest {

    private fun rec(day: Long, min: Long, ml: Double = 10.0, style: SessionStyle = SessionStyle.BALANCED, done: Boolean = true) =
        SessionRecord(day * 86_400_000L, day, min * 60, SessionMode.ANAL, style, ml, done)

    @Test
    fun totalsAndStreaks() {
        val records = listOf(
            rec(100, 20), rec(101, 30, style = SessionStyle.EDGING), rec(102, 10, style = SessionStyle.EDGING),
            rec(105, 40, done = false), rec(106, 25, style = SessionStyle.EDGING), rec(107, 15, style = SessionStyle.EDGING),
        )
        val s = SessionStats.of(records, today = 107)
        assertEquals(6, s.sessions)
        assertEquals(5, s.completed)
        assertEquals(140, s.totalMinutes)
        assertEquals(60.0, s.totalMl, 1e-9)
        assertEquals(40, s.longestMinutes)
        assertEquals(3, s.streakDays)
        assertEquals(3, s.bestStreakDays)
        assertEquals(SessionStyle.EDGING, s.favoriteStyle)
        assertEquals(5, s.thisWeek) // days 101–107
        // Yesterday still counts for the current streak, two days ago does not.
        assertEquals(3, SessionStats.of(records, today = 108).streakDays)
        assertEquals(0, SessionStats.of(records, today = 109).streakDays)
    }

    @Test
    fun jsonRoundTripAndCap() {
        val many = (1..250).map { rec(it.toLong(), 5) }
        val restored = SessionStats.parse(SessionStats.serialize(many))
        assertEquals(SessionStats.MAX_RECORDS, restored.size)
        assertEquals(many.last(), restored.last())
        assertEquals(0, SessionStats.of(emptyList(), 1).sessions)
    }
}
