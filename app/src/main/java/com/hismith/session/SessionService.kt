package com.hismith.session

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One connected BLE device. Writes are serialised through [queue] because Android's
 * GATT stack only allows a single outstanding write per connection – issuing several
 * writeCharacteristic() calls back-to-back silently drops all but the first.
 */
class ManagedDevice(
    val address: String,
    val name: String,
    var role: DeviceRole,
    var gatt: BluetoothGatt? = null,
    var tx: BluetoothGattCharacteristic? = null,
    @Volatile var connected: Boolean = false
) {
    val queue = ConcurrentLinkedQueue<ByteArray>()
    val writeInFlight = AtomicBoolean(false)
    @Volatile var lastWriteStartedMs = 0L

    /** Keep the queue short: speed updates arrive every 80 ms during ramps. */
    fun enqueue(data: ByteArray, maxQueued: Int = 12) {
        while (queue.size >= maxQueued) queue.poll()
        queue.add(data)
    }
}

@SuppressLint("MissingPermission")
class SessionService : Service() {

    companion object {
        private const val TAG = "SessionService"
        const val CHANNEL_ID = "hismith_session"
        const val NOTIF_ID = 42
        const val ACTION_STOP = "com.hismith.session.STOP"
        const val ACTION_MEGA_SPRAY = "com.hismith.session.MEGA_SPRAY"
        const val ACTION_SHORT_SPRAY = "com.hismith.session.SHORT_SPRAY"
        const val ACTION_START_SESSION = "com.hismith.session.START"
        const val ACTION_END_SESSION = "com.hismith.session.END"
        const val BROADCAST_STATUS = "com.hismith.session.STATUS"
        const val EXTRA_SPEED = "speed"
        const val EXTRA_PHASE = "phase"
        const val EXTRA_ELAPSED = "elapsed"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_RUNNING = "running"
        const val EXTRA_DEVICE_SUMMARY = "devices"

        /** If a write never gets its callback, give up on it after this long. */
        private const val WRITE_TIMEOUT_MS = 600L
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val devices = ConcurrentHashMap<String, ManagedDevice>()
    private lateinit var engine: SessionEngine
    private var tickJob: Job? = null
    private var reconnectJob: Job? = null
    private var sprayJob: Job? = null
    private var foregroundStarted = false

    inner class LocalBinder : Binder() {
        fun getService(): SessionService = this@SessionService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        engine = SessionEngine(
            onSpeed = { speed -> applySpeedToAllMachines(speed) },
            onStroke = { start, stop -> applyStrokeToMachines(start, stop) },
            onPhase = { phase -> broadcastStatus(phase = phase) },
            onSpray = { seconds -> spray(seconds) },
            onFinished = { stopSessionInternal() }
        )
        reconnectJob = scope.launch {
            while (isActive) {
                delay(4000)
                devices.values.filter { !it.connected && it.gatt != null }.forEach { dev ->
                    try { dev.gatt?.connect() } catch (_: Exception) {}
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always (re)enter foreground as soon as we're started so Android never kills us.
        startForegroundProtected()
        when (intent?.action) {
            ACTION_STOP -> emergencyStop()
            ACTION_MEGA_SPRAY -> spray(9.0)
            ACTION_SHORT_SPRAY -> spray(0.5)
            ACTION_END_SESSION -> stopSessionInternal()
            ACTION_START_SESSION -> {
                startSession(
                    intent.getIntExtra("min", 20),
                    intent.getIntExtra("max", 45),
                    intent.getBooleanExtra("autoLube", true),
                    intent.getBooleanExtra("smartStroke", false)
                )
            }
        }
        return START_STICKY
    }

    /**
     * Enter foreground mode. On Android 14+ a "connectedDevice" foreground service
     * requires BLUETOOTH_CONNECT to be granted at runtime, otherwise the system throws.
     * We therefore fall back to a plain foreground notification until the permission
     * has been granted, and MainActivity calls this again after the permission dialog.
     */
    fun startForegroundProtected() {
        val notif = buildNotification(
            if (::engine.isInitialized && engine.isRunning) "Session igång" else "Redo",
            deviceSummary()
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasBtConnectPermission()) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIF_ID, notif)
            }
            foregroundStarted = true
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed: ${e.message}")
            try {
                startForeground(NOTIF_ID, notif)
                foregroundStarted = true
            } catch (e2: Exception) {
                Log.e(TAG, "startForeground fallback failed: ${e2.message}")
            }
        }
    }

    private fun hasBtConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------ BLE

    fun connectDevice(device: BluetoothDevice, role: DeviceRole) {
        devices[device.address]?.gatt?.let { try { it.close() } catch (_: Exception) {} }
        val displayName = try { device.name } catch (_: SecurityException) { null } ?: role.label
        val managed = ManagedDevice(device.address, displayName, role)
        devices[device.address] = managed

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                val dev = devices[gatt.device.address] ?: return
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    gatt.discoverServices()
                } else {
                    dev.connected = false
                    dev.tx = null
                    dev.queue.clear()
                    dev.writeInFlight.set(false)
                    broadcastStatus()
                    updateNotification("Tappade kontakt", deviceSummary())
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val dev = devices[gatt.device.address] ?: return
                val service = gatt.getService(UUID.fromString(HismithProtocol.SERVICE_UUID))
                val char = service?.getCharacteristic(UUID.fromString(HismithProtocol.TX_UUID))
                dev.tx = char
                dev.connected = char != null
                dev.queue.clear()
                dev.writeInFlight.set(false)
                if (dev.connected && dev.role != DeviceRole.LUBE_LAUNCHER) {
                    dev.enqueue(HismithProtocol.powerOn())
                    dev.enqueue(HismithProtocol.manualMode())
                    dev.enqueue(HismithProtocol.setSpeedAA(0))
                    dev.enqueue(HismithProtocol.setSpeedFF(0))
                    pumpQueue(dev)
                }
                broadcastStatus()
                updateNotification("Ansluten", deviceSummary())
            }

            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                val dev = devices[gatt.device.address] ?: return
                dev.writeInFlight.set(false)
                pumpQueue(dev)
            }
        }
        managed.gatt = device.connectGatt(this, false, callback, BluetoothDevice.TRANSPORT_LE)
        broadcastStatus()
    }

    fun removeDevice(address: String) {
        devices.remove(address)?.let { dev ->
            try { dev.gatt?.disconnect(); dev.gatt?.close() } catch (_: Exception) {}
        }
        broadcastStatus()
        updateNotification("Redo", deviceSummary())
    }

    fun getDevices(): List<ManagedDevice> = devices.values.toList()

    fun hasAnyMachine(): Boolean =
        devices.values.any { it.connected && it.role != DeviceRole.LUBE_LAUNCHER }

    /** True only while a session is actually running – the UI shows its stop controls then. */
    fun isSessionRunning(): Boolean = ::engine.isInitialized && engine.isRunning

    /** Queue a packet and kick the writer if idle. */
    private fun send(dev: ManagedDevice, data: ByteArray) {
        if (!dev.connected || dev.tx == null || dev.gatt == null) return
        dev.enqueue(data)
        pumpQueue(dev)
    }

    /** Write the next queued packet if nothing is in flight (or the previous write timed out). */
    private fun pumpQueue(dev: ManagedDevice) {
        val gatt = dev.gatt ?: return
        val char = dev.tx ?: return
        val now = System.currentTimeMillis()
        if (dev.writeInFlight.get() && now - dev.lastWriteStartedMs < WRITE_TIMEOUT_MS) return
        val data = dev.queue.poll() ?: run { dev.writeInFlight.set(false); return }
        dev.writeInFlight.set(true)
        dev.lastWriteStartedMs = now
        val ok = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(char, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    char.value = data
                    gatt.writeCharacteristic(char)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "write failed: ${e.message}")
            false
        }
        if (!ok) {
            // Stack busy – retry shortly instead of losing the packet.
            dev.writeInFlight.set(false)
            dev.queue.add(data)
            scope.launch { delay(60); pumpQueue(dev) }
        }
    }

    private fun applySpeedToAllMachines(percent: Int) {
        val p = percent.coerceIn(0, 100)
        devices.values.forEach { dev ->
            if (!dev.connected || dev.tx == null) return@forEach
            when (dev.role) {
                DeviceRole.LUBE_LAUNCHER -> {}
                DeviceRole.POCKET_STROKER -> {
                    send(dev, HismithProtocol.setSpeedAA(p))
                    send(dev, HismithProtocol.setSpeedFF(p))
                    // Light vibration that follows the stroke speed
                    if (p > 5) send(dev, HismithProtocol.makeAA(0x06, (p / 2).coerceIn(0, 100)))
                    else send(dev, HismithProtocol.makeAA(0x06, 0xF0))
                }
                else -> {
                    send(dev, HismithProtocol.setSpeedAA(p))
                    send(dev, HismithProtocol.setSpeedFF(p))
                }
            }
        }
        broadcastStatus(speed = p)
    }

    private fun applyStrokeToMachines(startPercent: Int, stopPercent: Int) {
        val start = startPercent.coerceIn(0, 90)
        val stop = stopPercent.coerceIn(start + 10, 100)
        devices.values.forEach { dev ->
            if (!dev.connected || dev.tx == null) return@forEach
            if (dev.role == DeviceRole.LUBE_LAUNCHER || dev.role == DeviceRole.POCKET_STROKER) return@forEach
            send(dev, HismithProtocol.setStrokeStart(start))
            send(dev, HismithProtocol.setStrokeStop(stop))
            send(dev, HismithProtocol.setSmoothness(7))
            send(dev, HismithProtocol.servoMinLength(start))
            send(dev, HismithProtocol.servoMaxLength(stop))
            send(dev, HismithProtocol.servoSmoothness(7))
        }
    }

    // ------------------------------------------------------------------ Lube

    /** Run the launcher pump for [seconds]. A new spray extends/replaces the current one. */
    private fun spray(seconds: Double) {
        sprayJob?.cancel()
        sprayJob = scope.launch { doSprayAll(seconds) }
    }

    private suspend fun doSprayAll(seconds: Double) {
        val lubes = devices.values.filter { it.role == DeviceRole.LUBE_LAUNCHER && it.connected }
        if (lubes.isEmpty()) return
        val end = System.currentTimeMillis() + (seconds * 1000).toLong()
        while (System.currentTimeMillis() < end && scope.isActive) {
            lubes.forEach { send(it, HismithProtocol.LUBE_SQUIRT_SHORT) }
            delay(280)
        }
    }

    // ------------------------------------------------------------------ Session

    fun startSession(minMinutes: Int, maxMinutes: Int, autoLube: Boolean, smartStroke: Boolean = false) {
        if (!hasAnyMachine()) {
            broadcastStatus(phase = "Ingen maskin ansluten", running = false)
            return
        }
        if (engine.isRunning) return
        engine.start(minMinutes, maxMinutes, autoLube, smartStroke)
        updateNotification("Session igång", deviceSummary())
        tickJob?.cancel()
        tickJob = scope.launch {
            while (isActive && engine.isRunning) {
                engine.tick()
                broadcastStatus(elapsed = engine.elapsedSeconds(), progress = engine.progressPercent())
                delay(80)
            }
        }
        broadcastStatus(running = true, phase = "Startar…")
    }

    private fun stopSessionInternal() {
        engine.stop()
        tickJob?.cancel()
        sprayJob?.cancel()
        applySpeedToAllMachines(0)
        devices.values.forEach { dev ->
            if (dev.role != DeviceRole.LUBE_LAUNCHER) {
                send(dev, HismithProtocol.stopFF())
                send(dev, HismithProtocol.powerOff())
            }
        }
        updateNotification("Session stoppad", deviceSummary())
        broadcastStatus(running = false, phase = "Stoppad", speed = 0)
    }

    fun emergencyStop() {
        engine.emergencyStop()
        tickJob?.cancel()
        sprayJob?.cancel()
        // Drop anything pending so the stop commands go out first.
        devices.values.forEach { it.queue.clear() }
        devices.values.forEach { dev ->
            if (!dev.connected) return@forEach
            if (dev.role == DeviceRole.LUBE_LAUNCHER) return@forEach
            send(dev, HismithProtocol.setSpeedAA(0))
            send(dev, HismithProtocol.setSpeedFF(0))
            send(dev, HismithProtocol.stopFF())
            send(dev, HismithProtocol.powerOff())
        }
        updateNotification("NÖDSTOPP", "Allt stoppat")
        broadcastStatus(running = false, phase = "NÖDSTOPP", speed = 0)
    }

    // ------------------------------------------------------------------ Notification / status

    private fun deviceSummary(): String {
        val parts = devices.values.filter { it.connected }.map { it.role.label }
        return if (parts.isEmpty()) "Inga enheter" else parts.joinToString(", ")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Hismith Session", NotificationManager.IMPORTANCE_LOW)
            channel.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, SessionService::class.java).setAction(ACTION_STOP), flags
        )
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, MainActivity::class.java)
        val openPi = PendingIntent.getActivity(this, 0, openIntent, flags)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_delete, "STOPP", stopPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(title: String, text: String) {
        if (!foregroundStarted) return
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(title, text))
        } catch (_: Exception) {}
    }

    private fun broadcastStatus(
        speed: Int? = null,
        phase: String? = null,
        elapsed: Long? = null,
        progress: Float? = null,
        running: Boolean? = null
    ) {
        val summary = devices.values.joinToString(";") {
            "${it.role.name}:${if (it.connected) 1 else 0}:${it.name}:${it.address}"
        }
        val isRunning = running ?: (::engine.isInitialized && engine.isRunning)
        sendBroadcast(Intent(BROADCAST_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_RUNNING, isRunning)
            putExtra(EXTRA_DEVICE_SUMMARY, summary)
            speed?.let { putExtra(EXTRA_SPEED, it) }
            phase?.let { putExtra(EXTRA_PHASE, it) }
            elapsed?.let { putExtra(EXTRA_ELAPSED, it) }
            progress?.let { putExtra(EXTRA_PROGRESS, it) }
        })
    }

    override fun onDestroy() {
        tickJob?.cancel(); reconnectJob?.cancel(); sprayJob?.cancel()
        devices.values.forEach { dev ->
            try {
                if (dev.connected && dev.role != DeviceRole.LUBE_LAUNCHER) {
                    // Best effort – make sure nothing keeps running when the service dies.
                    dev.gatt?.let { g ->
                        dev.tx?.let { c ->
                            @Suppress("DEPRECATION")
                            run { c.value = HismithProtocol.setSpeedAA(0); g.writeCharacteristic(c) }
                        }
                    }
                }
                dev.gatt?.close()
            } catch (_: Exception) {}
        }
        devices.clear()
        scope.cancel()
        super.onDestroy()
    }
}
