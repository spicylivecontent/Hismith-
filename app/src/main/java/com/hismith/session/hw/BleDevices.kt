package com.hismith.session.hw

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import com.hismith.session.DeviceKind
import com.hismith.session.DeviceRole
import com.hismith.session.HismithProtocol
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One BLE device (Hismith machine, Thrusting Pocket or the original Hismith lube launcher), or a
 * simulated one. Writes are serialised through [queue]: Android's GATT stack only allows a single
 * outstanding write per connection.
 */
class BleDevice(
    val address: String,
    val name: String,
    val role: DeviceRole,
    val kind: DeviceKind,
) {
    var gatt: BluetoothGatt? = null
    var tx: BluetoothGattCharacteristic? = null
    @Volatile var connected: Boolean = false
    /** Set when the user removed the device: no more reconnect attempts. */
    @Volatile var removed: Boolean = false
    val queue = ConcurrentLinkedQueue<ByteArray>()
    val writeInFlight = AtomicBoolean(false)
    @Volatile var lastWriteStartedMs = 0L
    /** Last speed written – shown for simulated machines. */
    @Volatile var lastSpeed = 0
    /**
     * Connected (or reconnected) while a session was running: kept at 0 until the user explicitly
     * resumes or starts again. A reconnect must never restart motion by itself.
     */
    @Volatile var held = false

    fun enqueue(data: ByteArray, maxQueued: Int = 12) {
        while (queue.size >= maxQueued) queue.poll()
        queue.add(data)
    }
}

/**
 * BLE side of the hardware: connects Hismith machines / pockets / the BLE lube launcher, keeps a
 * per-device write queue and reconnects dropped devices. All callbacks are posted to [handler]
 * (the service's control thread).
 */
@SuppressLint("MissingPermission")
class BleDevices(
    private val context: Context,
    private val handler: Handler,
    private val listener: Listener,
) {
    interface Listener {
        fun onBleConnected(dev: BleDevice)
        fun onBleLost(dev: BleDevice)
        fun onBleChanged()
    }

    companion object {
        private const val TAG = "BleDevices"
        private const val WRITE_TIMEOUT_MS = 600L
        private const val RECONNECT_MS = 4_000L
    }

    private val devices = LinkedHashMap<String, BleDevice>()
    private var lastReconnect = 0L

    fun all(): List<BleDevice> = devices.values.toList()

    fun motionDevices(): List<BleDevice> = devices.values.filter { it.role.isMotion }

    fun anyMotionConnected(): Boolean = devices.values.any { it.role.isMotion && it.connected }

    fun get(address: String): BleDevice? = devices[address]

    fun connect(device: BluetoothDevice, role: DeviceRole) {
        devices[device.address]?.let { old ->
            old.removed = true
            try { old.gatt?.close() } catch (_: Exception) {}
        }
        val displayName = try { device.name } catch (_: SecurityException) { null } ?: role.label
        val dev = BleDevice(device.address, displayName, role, DeviceKind.BLE)
        devices[device.address] = dev
        dev.gatt = try {
            device.connectGatt(context, false, callbackFor(dev), BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            Log.w(TAG, "connectGatt failed: ${e.message}")
            null
        }
        listener.onBleChanged()
    }

    /** A machine that only exists in the app – for trying sessions, the link and the UI. */
    fun addSimulated(role: DeviceRole): BleDevice {
        val n = devices.values.count { it.kind == DeviceKind.SIMULATED } + 1
        val dev = BleDevice("SIM-${role.name}-$n", "Simulator $n", role, DeviceKind.SIMULATED)
        dev.connected = true
        devices[dev.address] = dev
        listener.onBleConnected(dev)
        listener.onBleChanged()
        return dev
    }

    fun remove(address: String) {
        val dev = devices.remove(address) ?: return
        dev.removed = true
        if (dev.connected && dev.role.isMotion) {
            // Best effort: never leave a machine running when it is removed.
            dev.queue.clear()
            write(dev, HismithProtocol.setSpeedAA(0))
            write(dev, HismithProtocol.setSpeedFF(0))
        }
        handler.postDelayed({
            try { dev.gatt?.disconnect(); dev.gatt?.close() } catch (_: Exception) {}
        }, 300)
        dev.connected = false
        listener.onBleLost(dev)
        listener.onBleChanged()
    }

    private fun callbackFor(dev: BleDevice) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (dev.removed) return@post
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    try { gatt.discoverServices() } catch (_: Exception) {}
                } else {
                    val was = dev.connected
                    dev.connected = false
                    dev.tx = null
                    dev.queue.clear()
                    dev.writeInFlight.set(false)
                    if (was) listener.onBleLost(dev)
                    listener.onBleChanged()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            handler.post {
                if (dev.removed) return@post
                val service = gatt.getService(UUID.fromString(HismithProtocol.SERVICE_UUID))
                val ch = service?.getCharacteristic(UUID.fromString(HismithProtocol.TX_UUID))
                dev.tx = ch
                dev.queue.clear()
                dev.writeInFlight.set(false)
                dev.connected = ch != null
                if (dev.connected) {
                    if (dev.role.isMotion) {
                        // Power on in manual mode at speed 0 – a reconnect never restarts motion.
                        write(dev, HismithProtocol.powerOn())
                        write(dev, HismithProtocol.manualMode())
                        write(dev, HismithProtocol.setSpeedAA(0))
                        write(dev, HismithProtocol.setSpeedFF(0))
                    }
                    listener.onBleConnected(dev)
                }
                listener.onBleChanged()
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            handler.post {
                dev.writeInFlight.set(false)
                pump(dev)
            }
        }
    }

    /** Queue a packet and kick the writer. Simulated devices just record the speed. */
    fun write(dev: BleDevice, data: ByteArray) {
        if (dev.kind == DeviceKind.SIMULATED) {
            if (data.size >= 3 && (data[0] == 0xAA.toByte() && data[1] == 0x04.toByte())) dev.lastSpeed = data[2].toInt() and 0xFF
            return
        }
        if (!dev.connected || dev.tx == null || dev.gatt == null) return
        dev.enqueue(data)
        pump(dev)
    }

    private fun pump(dev: BleDevice) {
        val gatt = dev.gatt ?: return
        val ch = dev.tx ?: return
        val now = SystemClock.elapsedRealtime()
        if (dev.writeInFlight.get() && now - dev.lastWriteStartedMs < WRITE_TIMEOUT_MS) return
        val data = dev.queue.poll() ?: run { dev.writeInFlight.set(false); return }
        dev.writeInFlight.set(true)
        dev.lastWriteStartedMs = now
        val ok = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(ch, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    ch.value = data
                    gatt.writeCharacteristic(ch)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "write failed: ${e.message}")
            false
        }
        if (!ok) {
            dev.writeInFlight.set(false)
            dev.queue.add(data)
            handler.postDelayed({ pump(dev) }, 60)
        }
    }

    /** Periodic work from the control loop: write timeouts and reconnects. */
    fun tick(now: Long) {
        for (dev in devices.values) {
            if (dev.writeInFlight.get() && now - dev.lastWriteStartedMs >= WRITE_TIMEOUT_MS) pump(dev)
        }
        if (now - lastReconnect >= RECONNECT_MS) {
            lastReconnect = now
            devices.values.filter { it.kind == DeviceKind.BLE && !it.connected && !it.removed && it.gatt != null }.forEach {
                try { it.gatt?.connect() } catch (_: Exception) {}
            }
        }
    }

    // ------------------------------------------------------------------ motion helpers

    fun setSpeedAll(percent: Int) {
        val p = percent.coerceIn(0, 100)
        for (dev in devices.values) {
            if (!dev.connected || !dev.role.isMotion || dev.held) continue
            write(dev, HismithProtocol.setSpeedAA(p))
            write(dev, HismithProtocol.setSpeedFF(p))
            if (dev.role == DeviceRole.POCKET_STROKER) {
                write(dev, if (p > 5) HismithProtocol.makeAA(0x06, (p / 2).coerceIn(0, 100)) else HismithProtocol.makeAA(0x06, 0xF0))
            }
        }
    }

    fun setStrokeAll(start: Int, stop: Int) {
        for (dev in devices.values) {
            if (!dev.connected || dev.role != DeviceRole.MACHINE || dev.held) continue
            write(dev, HismithProtocol.setStrokeStart(start))
            write(dev, HismithProtocol.setStrokeStop(stop))
            write(dev, HismithProtocol.setSmoothness(7))
            write(dev, HismithProtocol.servoMinLength(start))
            write(dev, HismithProtocol.servoMaxLength(stop))
            write(dev, HismithProtocol.servoSmoothness(7))
        }
    }

    /** Explicit start/resume by the user: held devices may move again. */
    fun releaseHolds() {
        devices.values.forEach { it.held = false }
    }

    /** Emergency: drop everything queued so the stop packets go out first. */
    fun stopAll() {
        for (dev in devices.values) {
            if (!dev.role.isMotion) continue
            dev.queue.clear()
            dev.lastSpeed = 0
            if (!dev.connected) continue
            write(dev, HismithProtocol.setSpeedAA(0))
            write(dev, HismithProtocol.setSpeedFF(0))
            write(dev, HismithProtocol.stopFF())
            write(dev, HismithProtocol.powerOff())
        }
    }

    /** Last resort when the service dies: synchronous best-effort speed 0. */
    fun closeAll() {
        for (dev in devices.values) {
            dev.removed = true
            try {
                if (dev.connected && dev.role.isMotion) {
                    val g = dev.gatt
                    val c = dev.tx
                    if (g != null && c != null) {
                        @Suppress("DEPRECATION")
                        run { c.value = HismithProtocol.setSpeedAA(0); g.writeCharacteristic(c) }
                    }
                }
                dev.gatt?.close()
            } catch (_: Exception) {}
        }
        devices.clear()
    }
}
