package com.hismith.session.core

import org.json.JSONArray
import org.json.JSONObject

/** One finished (or interrupted) session. Kept on the phone only. */
data class SessionRecord(
    /** Wall-clock start, ms since epoch. */
    val startedAt: Long,
    /** Local epoch day the session started on. */
    val epochDay: Long,
    val durationSec: Long,
    val mode: SessionMode,
    val style: SessionStyle,
    val lubeMl: Double,
    val completed: Boolean,
    val challengeDay: Int? = null,
    val surprises: Int = 0,
    val boosts: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("t", startedAt)
        .put("d", epochDay)
        .put("s", durationSec)
        .put("m", mode.name)
        .put("st", style.name)
        .put("ml", lubeMl)
        .put("c", completed)
        .put("sp", surprises)
        .put("b", boosts)
        .apply { challengeDay?.let { put("cd", it) } }

    companion object {
        fun fromJson(o: JSONObject): SessionRecord? = try {
            SessionRecord(
                startedAt = o.getLong("t"),
                epochDay = o.getLong("d"),
                durationSec = o.getLong("s"),
                mode = SessionMode.values().firstOrNull { it.name == o.optString("m") } ?: SessionMode.ANAL,
                style = SessionStyle.parse(o.optString("st")) ?: SessionStyle.BALANCED,
                lubeMl = o.optDouble("ml", 0.0),
                completed = o.optBoolean("c"),
                challengeDay = if (o.has("cd")) o.getInt("cd") else null,
                surprises = o.optInt("sp"),
                boosts = o.optInt("b"),
            )
        } catch (_: Exception) {
            null
        }
    }
}

/** Totals and fun facts over the session history. */
data class SessionStats(
    val sessions: Int,
    val completed: Int,
    val totalMinutes: Long,
    val totalMl: Double,
    val longestMinutes: Long,
    val averageMinutes: Long,
    /** Days in a row with at least one session, ending today or yesterday. */
    val streakDays: Int,
    val bestStreakDays: Int,
    val favoriteStyle: SessionStyle?,
    val thisWeek: Int,
) {
    companion object {
        const val MAX_RECORDS = 200

        fun of(records: List<SessionRecord>, today: Long): SessionStats {
            if (records.isEmpty()) return SessionStats(0, 0, 0, 0.0, 0, 0, 0, 0, null, 0)
            val days = records.map { it.epochDay }.toSortedSet()
            var best = 0
            var run = 0
            var prev = Long.MIN_VALUE
            for (d in days) {
                run = if (d == prev + 1) run + 1 else 1
                best = maxOf(best, run)
                prev = d
            }
            var streak = 0
            var cursor = if (today in days) today else today - 1
            while (cursor in days) {
                streak++
                cursor--
            }
            val total = records.sumOf { it.durationSec }
            return SessionStats(
                sessions = records.size,
                completed = records.count { it.completed },
                totalMinutes = total / 60,
                totalMl = records.sumOf { it.lubeMl },
                longestMinutes = records.maxOf { it.durationSec } / 60,
                averageMinutes = total / records.size / 60,
                streakDays = streak,
                bestStreakDays = best,
                favoriteStyle = records.groupingBy { it.style }.eachCount().maxByOrNull { it.value }?.key,
                thisWeek = records.count { today - it.epochDay in 0..6 },
            )
        }

        fun parse(raw: String?): List<SessionRecord> {
            if (raw.isNullOrBlank()) return emptyList()
            return try {
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull { SessionRecord.fromJson(arr.getJSONObject(it)) }
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun serialize(records: List<SessionRecord>): String {
            val arr = JSONArray()
            records.takeLast(MAX_RECORDS).forEach { arr.put(it.toJson()) }
            return arr.toString()
        }
    }
}
