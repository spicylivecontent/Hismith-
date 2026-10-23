package com.hismith.session.core

/**
 * Shared, Android-free model types. Everything in `core` is plain Kotlin so it can be unit
 * tested on the JVM with a fake clock and a fixed seed.
 */

/** What the session is for. Decides the lube profile and whether lube is allowed at all. */
enum class SessionMode(val label: String, val lubeAllowed: Boolean) {
    ORAL("Oral", true),
    ANAL("Anal", true),
    POCKET("Pocket", false);
}

/** Who asked for a command. Every physical command goes through [CommandArbiter] with one. */
enum class Source(val label: String) {
    LOCAL("Telefonen"),
    AUTO("Automatiken"),
    REMOTE("Länken"),
    CHALLENGE("Utmaningen"),
    SYSTEM("Säkerhet");
}

/** Small automatic doses during a session. */
data class MicroDoseSettings(
    val ml: Double,
    val intervalMinSec: Int,
    val intervalMaxSec: Int,
) {
    fun sanitized() = MicroDoseSettings(
        ml = ml.coerceIn(Limits.MICRO_MIN_ML, Limits.MICRO_MAX_ML),
        intervalMinSec = intervalMinSec.coerceIn(5, 600),
        intervalMaxSec = intervalMaxSec.coerceIn(intervalMinSec.coerceIn(5, 600), 900),
    )
}

/** Everything the automatic engine needs for one session. Built by the UI or a challenge day. */
data class SessionConfig(
    val mode: SessionMode,
    val minMinutes: Int,
    /** 0 = no end time (no final). */
    val maxMinutes: Int,
    /** 0..100 – shifts speed ranges and how often intense behaviours are picked. */
    val intensity: Int,
    val autoLube: Boolean,
    val finalEnabled: Boolean,
    val finalMinMl: Double,
    val finalMaxMl: Double,
    val microDose: MicroDoseSettings,
    val smartStroke: Boolean,
    /** No resting phases: holds are replaced by slow movement. A challenge goal, never a lock. */
    val nonstop: Boolean,
    /** Fixed seed for reproducible tests; null = fresh entropy. */
    val seed: Long? = null,
    /** Set when the session was started from a challenge day (1-based). */
    val challengeDay: Int? = null,
    /** Character of the session: which behaviours dominate and how fast things move. */
    val style: SessionStyle = SessionStyle.BALANCED,
    /** Random "surprise": a sudden burst together with an EXTRA squirt. */
    val surprise: SurpriseLevel = SurpriseLevel.OFF,
    /** Volume of a surprise squirt (normally the EXTRA volume). */
    val surpriseMl: Double = 15.0,
    /** Give a micro dose right when a burst starts (instead of only on the timer). */
    val microOnBurst: Boolean = true,
)

/** Session styles. Weights multiply the engine's base weights per behaviour. */
enum class SessionStyle(
    val label: String,
    val hint: String,
    val rhythm: Double,
    val wave: Double,
    val burst: Double,
    val hold: Double,
    val tease: Double,
    val edge: Double,
    /** Added to the speed level (percentage points). */
    val shift: Double,
    /** Multiplies ramp times: > 1 = softer transitions. */
    val rampScale: Double,
    /** Multiplies the micro wander. */
    val sigmaScale: Double,
) {
    BALANCED("Balanserad", "Lite av allt", 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 0.0, 1.0, 1.0),
    SENSUAL("Sensuell", "Långa vågor och mjuka övergångar, sällan burst", 1.0, 2.6, 0.3, 0.8, 1.0, 0.4, -8.0, 1.6, 0.7),
    INTENSE("Intensiv", "Mer tempo och burst, korta pauser", 0.8, 0.6, 2.6, 0.35, 0.7, 1.3, 10.0, 0.7, 1.1),
    EDGING("Edging", "Bygger upp mot kanten och stannar – om och om igen", 0.7, 0.8, 0.6, 1.2, 0.8, 4.0, 0.0, 1.0, 1.0),
    TEASE("Tease", "Retas: grunt och djupt, snabbt och långsamt", 0.6, 1.0, 0.4, 0.8, 4.0, 1.0, -2.0, 0.9, 1.0),
    RHYTHMIC("Rytmisk", "Jämn takt som långsamt driver iväg", 3.0, 1.2, 0.5, 0.4, 0.3, 0.3, 0.0, 1.3, 0.5);

    companion object {
        fun parse(name: String?): SessionStyle? = values().firstOrNull { it.name == name }
    }
}

/** How often a surprise (burst + EXTRA squirt) happens. */
enum class SurpriseLevel(val label: String, val minSec: Int, val maxSec: Int) {
    OFF("Av", 0, 0),
    SOMETIMES("Ibland", 240, 480),
    OFTEN("Ofta", 90, 240);

    companion object {
        fun parse(name: String?): SurpriseLevel? = values().firstOrNull { it.name == name }
    }
}

/** One-tap actions that make the engine switch behaviour right now. */
enum class QuickAction(val label: String) {
    BOOST("Boost"),
    WAVE("Våg"),
    EDGE("Edge"),
    REST("Vila");

    companion object {
        fun parse(name: String?): QuickAction? = values().firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

/** Hard limits that no setting, remote command or challenge can exceed. */
object Limits {
    const val MANUAL_MIN_ML = 1.0
    const val MANUAL_MAX_ML = 30.0
    const val EXTRA_MAX_ML = 30.0
    const val SUPER_MAX_ML = 100.0
    const val FINAL_MAX_ML = 100.0
    const val MICRO_MIN_ML = 0.1
    const val MICRO_MAX_ML = 5.0
    const val SESSION_MAX_ML_CAP = 1000.0

    /** MegaLube locks after 120 s of continuous running; stay well below. */
    const val MAX_SEGMENT_MS = 90_000L
    const val SEGMENT_PAUSE_MS = 1_000L
    const val MIN_RUN_MS = 100L

    /** Reverse is capped at 60 % in the firmware too. */
    const val MAX_REVERSE_PCT = 60
}
