package com.hismith.session.hw

import com.hismith.session.HismithProtocol
import com.hismith.session.core.LubeDriver

/**
 * Simulated MegaLube: behaves like the firmware (lease, 1 s watchdog, 120 s continuous-run lock,
 * reverse capped at 60 %) so doses, calibration runs and the remote link can be tried without
 * hardware. [onEvent] gets the same `EVT:` lines the real board would send.
 */
class SimulatedLube(private val clock: () -> Long, private val onEvent: (String) -> Unit) : LubeDriver {
    override val displayName = "MegaLube (simulator)"
    override val supportsSpeed = true
    override val supportsReverse = true
    override val isReady = true

    var speed = 0
        private set
    private var leaseEnd = 0L
    private var lastCommand = 0L
    private var runStart = 0L
    private var locked = false

    override fun run(speedPct: Int, leaseMs: Long) {
        val now = clock()
        if (locked) {
            onEvent("ERR:LOCKED")
            return
        }
        if (speed == 0) runStart = now
        speed = speedPct.coerceIn(-60, 100)
        leaseEnd = now + leaseMs
        lastCommand = now
    }

    override fun stop() {
        speed = 0
        locked = false
        leaseEnd = 0
    }

    fun tick(now: Long) {
        if (speed == 0) return
        when {
            now - lastCommand > 1_000 -> { speed = 0; onEvent("EVT:WDT") }
            now >= leaseEnd -> { speed = 0; onEvent("EVT:DONE") }
            now - runStart > 120_000 -> { speed = 0; locked = true; onEvent("EVT:MAXTIME") }
        }
    }

    fun stateLine(now: Long): String =
        "STATE:${if (speed != 0) "RUN" else "IDLE"}:$speed:$speed:${(leaseEnd - now).coerceAtLeast(0)}:1000"
}

/**
 * The original Hismith lube launcher over BLE. It only knows "squirt" (`CC 0B 01 0C`), repeated
 * every 280 ms while a dose runs, so speed is ignored and its calibration is a single flow value.
 */
class HismithBleLube(
    private val ble: BleDevices,
    private val dev: BleDevice,
    private val clock: () -> Long,
) : LubeDriver {
    override val displayName: String get() = dev.name
    override val supportsSpeed = false
    override val supportsReverse = false
    override val isReady: Boolean get() = dev.connected && !dev.removed

    private var activeUntil = 0L
    private var lastPulse = 0L

    val address: String get() = dev.address

    override fun run(speedPct: Int, leaseMs: Long) {
        if (speedPct <= 0) return
        activeUntil = clock() + leaseMs
    }

    override fun stop() {
        activeUntil = 0
    }

    fun tick(nowMs: Long) {
        if (nowMs < activeUntil && nowMs - lastPulse >= 280) {
            lastPulse = nowMs
            ble.write(dev, HismithProtocol.LUBE_SQUIRT_SHORT)
        }
    }
}
