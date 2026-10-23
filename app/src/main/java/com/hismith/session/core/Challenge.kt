package com.hismith.session.core

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

enum class Progression(val label: String) {
    LINEAR("Linjär"),
    SOFT_START("Mjuk start"),
    SOFT_END("Mjuk avslutning"),
    MANUAL("Manuellt schema");
}

enum class MissedPolicy(val label: String) {
    MARK_MISSED("Markera som missad"),
    SHIFT("Flytta resten av schemat"),
    CATCH_UP("Lämna kalendern oförändrad");
}

enum class DayStatus(val label: String) {
    PENDING("Väntar"),
    DONE("Klar"),
    PARTIAL("Delvis"),
    MISSED("Missad");
}

/** What one challenge day asks for. */
data class DayTarget(val minutes: Int, val intensity: Int, val finalMl: Int) {
    fun sanitized() = DayTarget(minutes.coerceIn(1, 240), intensity.coerceIn(0, 100), finalMl.coerceIn(0, Limits.FINAL_MAX_ML.toInt()))
}

data class DayRecord(val status: DayStatus, val progressPct: Int = 0, val epochDay: Long = 0)

/**
 * A multi-day challenge. It never talks to Bluetooth: each day just produces a [SessionConfig]
 * for the normal automatic engine. "Nonstop on the last day" is a goal for that day's config,
 * never a technical lock – STOP always works.
 */
data class Challenge(
    val name: String,
    val days: Int,
    val startEpochDay: Long,
    val progression: Progression,
    val start: DayTarget,
    val end: DayTarget,
    val lastDayNonstop: Boolean,
    val missedPolicy: MissedPolicy,
    val mode: SessionMode,
    val reminderEnabled: Boolean = false,
    /** Minutes after midnight for the optional reminder. */
    val reminderMinuteOfDay: Int = 20 * 60,
    /** Days the remaining schedule has been moved (SHIFT policy and pauses). */
    val shiftDays: Int = 0,
    val pausedSinceEpochDay: Long? = null,
    val manual: Map<Int, DayTarget> = emptyMap(),
    val records: Map<Int, DayRecord> = emptyMap(),
) {
    val isPaused: Boolean get() = pausedSinceEpochDay != null

    fun status(day: Int): DayStatus = records[day]?.status ?: DayStatus.PENDING

    fun dateOf(day: Int): Long = startEpochDay + shiftDays + (day - 1)

    /** Progress 0..1 along the challenge for [day] after the progression curve. */
    fun curve(day: Int): Double {
        if (days <= 1) return 1.0
        val t = ((day - 1).toDouble() / (days - 1)).coerceIn(0.0, 1.0)
        return when (progression) {
            Progression.LINEAR, Progression.MANUAL -> t
            Progression.SOFT_START -> t * t
            Progression.SOFT_END -> 1 - (1 - t) * (1 - t)
        }
    }

    fun target(day: Int): DayTarget {
        manual[day]?.let { return it.sanitized() }
        val c = curve(day)
        fun lerp(a: Int, b: Int) = (a + (b - a) * c).roundToInt()
        return DayTarget(
            minutes = lerp(start.minutes, end.minutes),
            intensity = lerp(start.intensity, end.intensity),
            finalMl = lerp(start.finalMl, end.finalMl),
        ).sanitized()
    }

    fun isNonstopDay(day: Int) = lastDayNonstop && day == days

    /**
     * Today's day number. With [MissedPolicy.CATCH_UP] the earliest unfinished day that is due
     * comes first. Null before the start, after the end, or while paused.
     */
    fun currentDay(today: Long): Int? {
        if (isPaused) return null
        if (missedPolicy == MissedPolicy.CATCH_UP) {
            (1..days).firstOrNull { status(it) == DayStatus.PENDING && dateOf(it) <= today }?.let { return it }
        }
        val d = (today - dateOf(1) + 1).toInt()
        return if (d in 1..days) d else null
    }

    fun isFinished(today: Long): Boolean =
        (1..days).none { status(it) == DayStatus.PENDING || status(it) == DayStatus.PARTIAL } || today > dateOf(days) + 30

    val doneCount: Int get() = records.values.count { it.status == DayStatus.DONE }

    /** Applies the missed-day policy to days that passed without being done. */
    fun rollover(today: Long): Challenge {
        if (isPaused) return this
        val firstMissed = (1..days).firstOrNull {
            (status(it) == DayStatus.PENDING || status(it) == DayStatus.PARTIAL) && dateOf(it) < today
        } ?: return this
        return when (missedPolicy) {
            MissedPolicy.MARK_MISSED -> {
                val updated = records.toMutableMap()
                for (d in firstMissed..days) {
                    if (dateOf(d) >= today) break
                    if (status(d) == DayStatus.PENDING) updated[d] = DayRecord(DayStatus.MISSED, 0, dateOf(d))
                }
                copy(records = updated)
            }
            MissedPolicy.SHIFT -> copy(shiftDays = shiftDays + (today - dateOf(firstMissed)).toInt())
            MissedPolicy.CATCH_UP -> this
        }
    }

    fun pause(today: Long): Challenge = if (isPaused) this else copy(pausedSinceEpochDay = today)

    fun resume(today: Long): Challenge {
        val since = pausedSinceEpochDay ?: return this
        return copy(pausedSinceEpochDay = null, shiftDays = shiftDays + (today - since).toInt().coerceAtLeast(0))
    }

    fun record(day: Int, status: DayStatus, progressPct: Int, today: Long): Challenge {
        if (day !in 1..days) return this
        // A PARTIAL never overwrites a DONE.
        if (status == DayStatus.PARTIAL && status(day) == DayStatus.DONE) return this
        return copy(records = records + (day to DayRecord(status, progressPct.coerceIn(0, 100), today)))
    }

    fun withManualTarget(day: Int, t: DayTarget): Challenge = copy(manual = manual + (day to t.sanitized()))

    /** The session for [day], built on the user's normal lube/stroke settings. */
    fun sessionConfig(day: Int, lube: LubeSettings, smartStroke: Boolean, autoLube: Boolean): SessionConfig {
        val t = target(day)
        val finalMl = t.finalMl.toDouble()
        return SessionConfig(
            mode = mode,
            minMinutes = t.minutes,
            maxMinutes = t.minutes + maxOf(1, t.minutes / 10),
            intensity = t.intensity,
            autoLube = autoLube,
            finalEnabled = finalMl > 0,
            finalMinMl = finalMl.coerceAtLeast(1.0),
            finalMaxMl = finalMl.coerceAtLeast(1.0),
            microDose = lube.microFor(mode),
            smartStroke = smartStroke,
            nonstop = isNonstopDay(day),
            challengeDay = day,
        )
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("v", 1)
        put("name", name)
        put("days", days)
        put("start", startEpochDay)
        put("progression", progression.name)
        put("from", start.toJson())
        put("to", end.toJson())
        put("lastNonstop", lastDayNonstop)
        put("missed", missedPolicy.name)
        put("mode", mode.name)
        put("reminder", reminderEnabled)
        put("reminderAt", reminderMinuteOfDay)
        put("shift", shiftDays)
        pausedSinceEpochDay?.let { put("pausedSince", it) }
        put("manual", JSONArray().apply {
            manual.forEach { (d, t) -> put(t.toJson().put("day", d)) }
        })
        put("records", JSONArray().apply {
            records.forEach { (d, r) ->
                put(JSONObject().put("day", d).put("s", r.status.name).put("p", r.progressPct).put("e", r.epochDay))
            }
        })
    }

    companion object {
        private fun DayTarget.toJson() = JSONObject().put("min", minutes).put("int", intensity).put("ml", finalMl)

        private fun JSONObject.toTarget() = DayTarget(optInt("min", 20), optInt("int", 50), optInt("ml", 20)).sanitized()

        private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
            enumValues<E>().firstOrNull { it.name == name } ?: default

        fun fromJson(o: JSONObject): Challenge? = try {
            val manual = HashMap<Int, DayTarget>()
            o.optJSONArray("manual")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val m = arr.getJSONObject(i)
                    manual[m.getInt("day")] = m.toTarget()
                }
            }
            val records = HashMap<Int, DayRecord>()
            o.optJSONArray("records")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val r = arr.getJSONObject(i)
                    records[r.getInt("day")] = DayRecord(enumOr(r.optString("s"), DayStatus.PENDING), r.optInt("p"), r.optLong("e"))
                }
            }
            Challenge(
                name = o.optString("name", "Utmaning"),
                days = o.getInt("days").coerceIn(1, 365),
                startEpochDay = o.getLong("start"),
                progression = enumOr(o.optString("progression"), Progression.LINEAR),
                start = o.getJSONObject("from").toTarget(),
                end = o.getJSONObject("to").toTarget(),
                lastDayNonstop = o.optBoolean("lastNonstop"),
                missedPolicy = enumOr(o.optString("missed"), MissedPolicy.MARK_MISSED),
                mode = enumOr(o.optString("mode"), SessionMode.ANAL),
                reminderEnabled = o.optBoolean("reminder"),
                reminderMinuteOfDay = o.optInt("reminderAt", 20 * 60).coerceIn(0, 24 * 60 - 1),
                shiftDays = o.optInt("shift"),
                pausedSinceEpochDay = if (o.has("pausedSince")) o.getLong("pausedSince") else null,
                manual = manual,
                records = records,
            )
        } catch (e: Exception) {
            null
        }
    }
}
