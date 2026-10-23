package com.hismith.session.core

/** Records what the dose controller sends, like the MegaLube firmware would receive it. */
class FakeLubeDriver(
    override var isReady: Boolean = true,
    override val supportsSpeed: Boolean = true,
    override val supportsReverse: Boolean = true,
) : LubeDriver {
    override val displayName = "Fake"
    val commands = ArrayList<String>()

    override fun run(speedPct: Int, leaseMs: Long) {
        commands += "RUN:$speedPct:$leaseMs"
    }

    override fun stop() {
        commands += "STOP"
    }
}

class FakeMotion : MotionOutputs {
    val speeds = ArrayList<Int>()
    val strokes = ArrayList<Pair<Int, Int>>()
    var stops = 0
    val last: Int get() = speeds.lastOrNull() ?: 0

    override fun setSpeed(percent: Int) {
        speeds += percent
    }

    override fun setStroke(startPercent: Int, stopPercent: Int) {
        strokes += startPercent to stopPercent
    }

    override fun stopMotion() {
        stops++
        speeds += 0
    }
}
