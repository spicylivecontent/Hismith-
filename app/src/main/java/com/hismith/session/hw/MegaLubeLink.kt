package com.hismith.session.hw

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import com.hismith.session.core.LubeDriver
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Parsed `STATE:<fas>:<nu%>:<mål%>:<kvar_ms>:<wdt_ms>` from the firmware. */
data class MegaLubeState(val phase: String, val currentPct: Int, val targetPct: Int, val leaseLeftMs: Long, val watchdogMs: Long)

/**
 * MegaLube (ESP32 + DRV8871) over Bluetooth Classic SPP. Text protocol, one command per line:
 * `RUN:<v>:<ms>`, `SET_SPEED:<v>`, `STOP`, `PING`, `STATUS`, `WDT:<ms>`.
 *
 * Threads: [connect] and the reader run on their own threads, writes go through a single writer
 * thread with a small queue (`STOP` jumps the queue). Everything reported back is posted to
 * [handler] (the service's control thread).
 */
@SuppressLint("MissingPermission")
class MegaLubeLink(
    private val adapter: BluetoothAdapter,
    val device: BluetoothDevice,
    private val handler: Handler,
    private val listener: Listener,
) : LubeDriver {

    interface Listener {
        fun onLubeConnected(link: MegaLubeLink)
        fun onLubeDisconnected(link: MegaLubeLink, error: String?)
        fun onLubeLine(link: MegaLubeLink, line: String)
    }

    companion object {
        private const val TAG = "MegaLube"
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val STATUS_INTERVAL_MS = 1_000L
        private const val MAX_QUEUE = 8
        private const val POISON = "\u0000"
    }

    override val displayName: String = try { device.name } catch (_: SecurityException) { null } ?: "MegaLube"
    override val supportsSpeed = true
    override val supportsReverse = true

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var out: OutputStream? = null
    @Volatile var connected = false
        private set
    @Volatile private var closing = false
    private val failed = AtomicBoolean(false)
    @Volatile var lastRxMs = 0L
        private set
    @Volatile var lastState: MegaLubeState? = null
        private set
    @Volatile var lastEvent: String? = null
        private set

    private val queue = LinkedBlockingDeque<String>()
    private var writer: Thread? = null
    private var lastStatusSent = 0L

    override val isReady: Boolean get() = connected && !closing

    val address: String get() = device.address

    fun connect() {
        closing = false
        failed.set(false)
        Thread({ doConnect() }, "megalube-connect").start()
    }

    private fun doConnect() {
        try { adapter.cancelDiscovery() } catch (_: Exception) {}
        val attempts = listOf<() -> BluetoothSocket>(
            { device.createRfcommSocketToServiceRecord(SPP_UUID) },
            { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
            {
                // Some phones only connect to ESP32 SPP on channel 1 via the hidden API.
                val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                m.invoke(device, 1) as BluetoothSocket
            },
        )
        var lastError: String? = null
        for (make in attempts) {
            if (closing) return
            val s = try { make() } catch (e: Exception) { lastError = e.message; continue }
            try {
                s.connect()
                socket = s
                out = s.outputStream
                connected = true
                lastRxMs = SystemClock.elapsedRealtime()
                startWriter()
                startReader(s.inputStream)
                handler.post { listener.onLubeConnected(this) }
                return
            } catch (e: IOException) {
                lastError = e.message
                try { s.close() } catch (_: Exception) {}
            }
        }
        handler.post { listener.onLubeDisconnected(this, lastError ?: "Kunde inte ansluta") }
    }

    private fun startWriter() {
        queue.clear()
        writer = Thread({
            try {
                while (!closing) {
                    val line = queue.pollFirst(1, TimeUnit.SECONDS) ?: continue
                    if (line == POISON) break
                    val o = out ?: break
                    o.write((line + "\n").toByteArray(Charsets.US_ASCII))
                    o.flush()
                }
            } catch (e: IOException) {
                fail("Skrivfel: ${e.message}")
            } catch (_: InterruptedException) {
            }
        }, "megalube-writer").also { it.start() }
    }

    private fun startReader(input: InputStream) {
        Thread({
            val sb = StringBuilder()
            val buf = ByteArray(256)
            try {
                while (!closing) {
                    val n = input.read(buf)
                    if (n < 0) break
                    for (i in 0 until n) {
                        val c = buf[i].toInt().toChar()
                        if (c == '\n' || c == '\r') {
                            if (sb.isNotEmpty()) {
                                val line = sb.toString().trim()
                                sb.setLength(0)
                                onLine(line)
                            }
                        } else if (sb.length < 128) {
                            sb.append(c)
                        }
                    }
                }
                fail("Anslutningen stängdes")
            } catch (e: IOException) {
                fail(if (closing) null else "Läsfel: ${e.message}")
            }
        }, "megalube-reader").start()
    }

    private fun onLine(line: String) {
        lastRxMs = SystemClock.elapsedRealtime()
        if (line.startsWith("STATE:")) {
            val p = line.split(':')
            if (p.size >= 6) {
                lastState = MegaLubeState(
                    p[1], p[2].toIntOrNull() ?: 0, p[3].toIntOrNull() ?: 0,
                    p[4].toLongOrNull() ?: 0, p[5].toLongOrNull() ?: 0,
                )
            }
        } else if (line.startsWith("EVT:") || line.startsWith("ERR:")) {
            lastEvent = line
        }
        handler.post { listener.onLubeLine(this, line) }
    }

    /** Reader or writer died. Reported once; an intentional [close] is not reported. */
    private fun fail(error: String?) {
        connected = false
        if (closing || !failed.compareAndSet(false, true)) return
        try { socket?.close() } catch (_: Exception) {}
        queue.offerFirst(POISON)
        handler.post { listener.onLubeDisconnected(this, error) }
    }

    private fun send(line: String, urgent: Boolean = false) {
        if (!connected) return
        if (urgent) {
            // STOP goes first and throws away pending RUNs.
            queue.clear()
            queue.offerFirst(line)
            return
        }
        while (queue.size >= MAX_QUEUE) queue.pollFirst()
        queue.offerLast(line)
    }

    override fun run(speedPct: Int, leaseMs: Long) {
        send("RUN:${speedPct.coerceIn(-60, 100)}:${leaseMs.coerceIn(1, 60_000)}")
    }

    override fun stop() = send("STOP", urgent = true)

    fun ping() = send("PING")

    /** Keepalive / health: STATUS once a second while nothing else is streaming. */
    fun tick(now: Long, streaming: Boolean) {
        if (!connected) return
        if (!streaming && now - lastStatusSent >= STATUS_INTERVAL_MS) {
            lastStatusSent = now
            send("STATUS")
        }
    }

    fun close() {
        closing = true
        if (connected) {
            // Best effort STOP straight on the socket before closing.
            try { out?.write("STOP\n".toByteArray(Charsets.US_ASCII)); out?.flush() } catch (_: Exception) {}
        }
        connected = false
        queue.clear()
        queue.offerFirst(POISON)
        try { socket?.close() } catch (e: Exception) { Log.w(TAG, "close: ${e.message}") }
    }
}
