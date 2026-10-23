package com.hismith.session

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.hismith.session.core.AutoEngine
import com.hismith.session.core.Challenge
import com.hismith.session.core.CommandArbiter
import com.hismith.session.core.DayStatus
import com.hismith.session.core.DoseController
import com.hismith.session.core.DoseKind
import com.hismith.session.core.DoseResult
import com.hismith.session.core.FlowModel
import com.hismith.session.core.LubeDriver
import com.hismith.session.core.MotionOutputs
import com.hismith.session.core.RemoteCommand
import com.hismith.session.core.RemoteWatchdog
import com.hismith.session.core.SessionConfig
import com.hismith.session.core.SessionMode
import com.hismith.session.core.Source
import com.hismith.session.hw.BleDevice
import com.hismith.session.hw.BleDevices
import com.hismith.session.hw.HismithBleLube
import com.hismith.session.hw.MegaLubeLink
import com.hismith.session.hw.SimulatedLube
import com.hismith.session.remote.KeystoreBox
import com.hismith.session.remote.RemoteHost
import org.json.JSONObject
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Foreground service that owns all hardware and runs the control loop on its own thread.
 *
 * Every physical command – automatic engine, phone UI, remote link, challenge – goes through
 * [CommandArbiter]; lube goes through [DoseController]. Public methods may be called from any
 * thread: they post to [control]. The UI gets immutable [UiState] snapshots on the main thread.
 */
@SuppressLint("MissingPermission")
class SessionService : Service(), MotionOutputs, BleDevices.Listener, MegaLubeLink.Listener, RemoteHost.Listener {

    companion object {
        private const val TAG = "SessionService"
        const val CHANNEL_ID = "hismith_session"
        const val ALERT_CHANNEL_ID = "hismith_alerts"
        const val NOTIF_ID = 42
        const val APPROVAL_NOTIF_ID = 43
        const val ACTION_STOP = "com.hismith.session.STOP"
        const val ACTION_SUPER = "com.hismith.session.SUPER"
        const val ACTION_REMOTE_APPROVE = "com.hismith.session.REMOTE_APPROVE"
        const val ACTION_REMOTE_DENY = "com.hismith.session.REMOTE_DENY"
        private const val TICK_MS = 50L
        private const val PUBLISH_MS = 100L
        private const val REMOTE_STATE_MS = 250L
        private const val HISTORY_SAMPLE_MS = 250L
        const val HISTORY_SIZE = 480
        private const val MEGA_RECONNECT_MS = 5_000L
    }

    inner class LocalBinder : Binder() {
        fun service(): SessionService = this@SessionService
    }

    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    lateinit var settings: AppSettings
        private set
    private lateinit var controlThread: HandlerThread
    private lateinit var control: Handler
    private val main = Handler(Looper.getMainLooper())

    private lateinit var ble: BleDevices
    private lateinit var doses: DoseController
    private lateinit var arbiter: CommandArbiter
    private lateinit var remote: RemoteHost
    private val watchdog = RemoteWatchdog()

    private val megaLinks = LinkedHashMap<String, MegaLubeLink>()
    private val megaWanted = LinkedHashMap<String, BluetoothDevice>()
    private val megaConnecting = HashSet<String>()
    private var lastMegaReconnect = 0L
    private var simLube: SimulatedLube? = null
    private val hismithLubes = LinkedHashMap<String, HismithBleLube>()
    private var activeLubeRole: DeviceRole? = null
    private var activeLube: LubeDriver? = null

    private var engine: AutoEngine? = null
    private var config: SessionConfig? = null
    private var sessionLabel = "Redo"
    private var history = com.hismith.session.core.MotionHistory()
    private var remoteDoseActive = false
    private var pendingApproval = false

    private val events = ArrayDeque<String>()
    private val speedHistory = IntArray(HISTORY_SIZE)
    private var historyPos = 0
    private var lastHistorySample = 0L
    private var lastPublish = 0L
    private var lastRemoteState = 0L
    private var lastRemoteJson = ""
    private var lastNotif = 0L
    private var lastNotifText = ""
    private var foregroundStarted = false
    private var wakeLock: PowerManager.WakeLock? = null
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val listeners = CopyOnWriteArrayList<(UiState) -> Unit>()
    @Volatile var latest: UiState? = null
        private set

    private val tick = object : Runnable {
        override fun run() {
            try {
                loop(SystemClock.elapsedRealtime())
            } catch (e: Exception) {
                Log.e(TAG, "control loop", e)
            }
            control.postDelayed(this, TICK_MS)
        }
    }

    // ================================================================== lifecycle

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        createChannels()
        controlThread = HandlerThread("hismith-control", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY).also { it.start() }
        control = Handler(controlThread.looper)
        ble = BleDevices(this, control, this)
        doses = DoseController(
            settings = { settings.lubeSettings },
            flowModel = { activeLubeRole?.let { settings.calibration(it) } ?: FlowModel.DEFAULT },
            onEvent = { log(it) },
            onDelivered = { kind, ml -> onLubeDelivered(kind, ml) },
        )
        arbiter = CommandArbiter(this, doses, settings.motionLimits)
        arbiter.setMode(settings.mode)
        remote = RemoteHost(control, this)
        history = settings.motionHistory
        control.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundProtected()
        when (intent?.action) {
            ACTION_STOP -> emergencyStop(Source.LOCAL)
            ACTION_SUPER -> dose(DoseKind.SUPER, null)
            ACTION_REMOTE_APPROVE -> remoteApprove()
            ACTION_REMOTE_DENY -> remoteDeny()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        control.removeCallbacksAndMessages(null)
        try {
            arbiter.stop(Source.SYSTEM, "Tjänsten avslutas")
            remote.stop()
        } catch (_: Exception) {}
        megaLinks.values.forEach { it.close() }
        ble.closeAll()
        releaseWakeLock()
        controlThread.quitSafely()
        super.onDestroy()
    }

    /**
     * Enter foreground. On Android 14+ a connectedDevice foreground service needs
     * BLUETOOTH_CONNECT at runtime, so fall back to a plain one until it is granted.
     */
    fun startForegroundProtected() {
        val n = buildNotification("Hismith Smart Session", "Redo")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasBtConnect()) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIF_ID, n)
            }
            foregroundStarted = true
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed: ${e.message}")
            try {
                startForeground(NOTIF_ID, n)
                foregroundStarted = true
            } catch (e2: Exception) {
                Log.e(TAG, "startForeground fallback failed: ${e2.message}")
            }
        }
    }

    private fun hasBtConnect(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    // ================================================================== listeners

    fun addListener(l: (UiState) -> Unit) {
        listeners += l
        latest?.let { s -> main.post { l(s) } }
    }

    fun removeListener(l: (UiState) -> Unit) {
        listeners -= l
    }

    private fun post(block: () -> Unit) {
        control.post {
            try { block() } catch (e: Exception) { Log.e(TAG, "command", e) }
            publish(force = true)
        }
    }

    // ================================================================== control loop

    private fun loop(now: Long) {
        ble.tick(now)
        megaLinks.values.forEach { it.tick(now, doses.isBusy && activeLube === it) }
        simLube?.tick(now)
        hismithLubes.values.forEach { it.tick(now) }
        selectActiveLube()

        val e = engine
        val cfg = config
        if (e != null && cfg != null) {
            val frame = e.tick(now)
            sessionLabel = frame.label
            if (!e.isPaused) {
                arbiter.applyAuto(frame, cfg.smartStroke)
                frame.lube?.let { cue ->
                    when (val r = arbiter.requestDose(Source.AUTO, cue.kind, cue.ml, now)) {
                        is DoseResult.Rejected -> if (cue.kind == DoseKind.FINAL) log("Final-sprut: ${r.reason}")
                        else -> if (cue.kind == DoseKind.FINAL) log("Final-sprut ${"%.0f".format(cue.ml)} ml")
                    }
                }
            }
            if (frame.finished) finishSession(completed = true, why = "Session klar")
        }

        arbiter.tick(now)
        doses.tick(now)
        if (!doses.isBusy) remoteDoseActive = false

        remote.tick(now)
        when (watchdog.check(now)) {
            RemoteWatchdog.Action.STOP -> {
                if (arbiter.owner == Source.REMOTE || remoteDoseActive) {
                    emergencyStopInternal(Source.SYSTEM, "Länken tappade kontakten")
                } else {
                    log("Mottagaren är tyst")
                }
            }
            RemoteWatchdog.Action.REVOKE -> {
                log("Mottagaren återkallad (ingen kontakt)")
                remote.kick()
                pendingApproval = false
            }
            null -> {}
        }

        if (now - lastMegaReconnect >= MEGA_RECONNECT_MS) {
            lastMegaReconnect = now
            for ((addr, dev) in megaWanted) {
                val link = megaLinks[addr]
                if ((link == null || !link.connected) && addr !in megaConnecting) startMegaLink(dev)
            }
        }

        if (now - lastHistorySample >= HISTORY_SAMPLE_MS) {
            lastHistorySample = now
            speedHistory[historyPos] = arbiter.outputSpeed
            historyPos = (historyPos + 1) % HISTORY_SIZE
        }
        if (now - lastRemoteState >= REMOTE_STATE_MS) {
            lastRemoteState = now
            sendRemoteState(now, force = false)
        }
        if (now - lastPublish >= PUBLISH_MS) publish(force = false)
    }

    private fun selectActiveLube() {
        val candidates = ArrayList<Pair<DeviceRole, LubeDriver>>()
        megaLinks.values.filter { it.isReady }.forEach { candidates += DeviceRole.LUBE_MEGALUBE to it }
        simLube?.let { candidates += DeviceRole.LUBE_MEGALUBE to it }
        hismithLubes.values.filter { it.isReady }.forEach { candidates += DeviceRole.LUBE_HISMITH to it }
        val pick = candidates.firstOrNull()
        if (pick?.second !== activeLube) {
            if (doses.isBusy) doses.onDriverLost()
            activeLube = pick?.second
            activeLubeRole = pick?.first
            doses.driver = activeLube
        }
    }

    // ================================================================== MotionOutputs

    private var lastSentSpeed = -1
    private var lastSentAt = 0L

    /**
     * The arbiter reports the speed every tick (20 Hz). Send it to the machines only when it
     * changed noticeably or 100 ms passed, and refresh once a second; 0 always goes out at once.
     */
    override fun setSpeed(percent: Int) {
        val now = SystemClock.elapsedRealtime()
        val changed = percent != lastSentSpeed
        val due = when {
            !changed -> now - lastSentAt >= 1_000
            percent == 0 -> true
            else -> now - lastSentAt >= 100 || kotlin.math.abs(percent - lastSentSpeed) >= 5
        }
        if (!due) return
        lastSentSpeed = percent
        lastSentAt = now
        ble.setSpeedAll(percent)
    }

    override fun setStroke(startPercent: Int, stopPercent: Int) = ble.setStrokeAll(startPercent, stopPercent)

    override fun stopMotion() {
        lastSentSpeed = 0
        lastSentAt = SystemClock.elapsedRealtime()
        ble.stopAll()
    }

    // ================================================================== devices

    fun connectBle(device: BluetoothDevice, role: DeviceRole) = post { ble.connect(device, role) }

    fun addSimulatedMachine() = post { ble.addSimulated(DeviceRole.MACHINE) }

    fun addSimulatedLube() = post {
        if (simLube == null) {
            simLube = SimulatedLube({ SystemClock.elapsedRealtime() }) { line -> if (!line.endsWith("DONE")) log("Simulator: $line") }
            log("Simulerad MegaLube tillagd")
        }
    }

    fun connectMegaLube(device: BluetoothDevice) = post {
        megaWanted[device.address] = device
        startMegaLink(device)
    }

    private fun startMegaLink(device: BluetoothDevice) {
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return
        megaLinks.remove(device.address)?.close()
        megaConnecting += device.address
        val link = MegaLubeLink(adapter, device, control, this)
        megaLinks[device.address] = link
        link.connect()
    }

    fun removeDevice(id: String) = post {
        settings.forgetDevice(id)
        if (id == "SIM-LUBE") {
            simLube?.stop()
            simLube = null
            return@post
        }
        megaWanted.remove(id)
        megaConnecting.remove(id)
        megaLinks.remove(id)?.let { it.close(); log("${it.displayName} bortkopplad") }
        hismithLubes.remove(id)
        ble.remove(id)
    }

    /** Reconnect remembered devices (after the Bluetooth permission was granted). */
    fun reconnectSaved() = post {
        if (!settings.autoReconnect || !hasBtConnect()) return@post
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return@post
        if (!adapter.isEnabled) return@post
        for (d in settings.savedDevices) {
            if (ble.get(d.address) != null || megaWanted.containsKey(d.address)) continue
            val dev = try { adapter.getRemoteDevice(d.address) } catch (_: Exception) { continue }
            when (d.kind) {
                DeviceKind.BLE -> ble.connect(dev, d.role)
                DeviceKind.SPP -> { megaWanted[d.address] = dev; startMegaLink(dev) }
                DeviceKind.SIMULATED -> {}
            }
        }
    }

    override fun onBleConnected(dev: BleDevice) {
        if (dev.kind == DeviceKind.BLE) settings.rememberDevice(SavedDevice(dev.address, dev.name, dev.role, dev.kind))
        if (dev.role.isMotion && arbiter.control != CommandArbiter.Control.NONE) {
            dev.held = true
            log("${dev.name} står still tills du trycker Pausa → Fortsätt")
        }
        if (dev.role == DeviceRole.LUBE_HISMITH && !hismithLubes.containsKey(dev.address)) {
            hismithLubes[dev.address] = HismithBleLube(ble, dev) { SystemClock.elapsedRealtime() }
        }
        log("${dev.name} ansluten")
    }

    override fun onBleLost(dev: BleDevice) {
        log("${dev.name} tappade kontakten")
        if (dev.role.isMotion && arbiter.isMotionActive) {
            // No automatic physical restart: pause until somebody explicitly resumes.
            pauseInternal("${dev.name} tappade kontakten")
        }
    }

    override fun onBleChanged() = publish(force = true)

    override fun onLubeConnected(link: MegaLubeLink) {
        megaConnecting -= link.address
        settings.rememberDevice(SavedDevice(link.address, link.displayName, DeviceRole.LUBE_MEGALUBE, DeviceKind.SPP))
        log("${link.displayName} ansluten (MegaLube)")
        publish(force = true)
    }

    override fun onLubeDisconnected(link: MegaLubeLink, error: String?) {
        megaConnecting -= link.address
        if (megaLinks[link.address] !== link) return
        if (activeLube === link && doses.isBusy) doses.onDriverLost()
        if (link.everConnected) log("${link.displayName}: ${error ?: "frånkopplad"}")
        publish(force = true)
    }

    override fun onLubeLine(link: MegaLubeLink, line: String) {
        when {
            line == "EVT:MAXTIME" || line == "ERR:LOCKED" -> {
                log("MegaLube: $line – låser upp")
                if (!doses.isBusy) link.stop()
            }
            line.startsWith("EVT:WDT") -> if (doses.isBusy) log("MegaLube: watchdog stoppade pumpen")
            line.startsWith("ERR:") -> log("MegaLube: $line")
        }
    }

    // ================================================================== session

    fun setMode(mode: SessionMode) = post {
        if (engine != null) return@post
        settings.mode = mode
        arbiter.setMode(mode)
    }

    /** Re-read limits and lube settings after the user changed them. */
    fun settingsChanged() = post {
        arbiter.limits = settings.motionLimits
        watchdog.stopAfterMs = settings.failsafeStopSec * 1000L
        watchdog.revokeAfterMs = maxOf(settings.failsafeRevokeSec * 1000L, watchdog.stopAfterMs + 1000)
    }

    fun buildConfigFromSettings(): SessionConfig {
        val s = settings
        val lube = s.lubeSettings
        return SessionConfig(
            mode = s.mode,
            minMinutes = s.minMinutes,
            maxMinutes = s.maxMinutes,
            intensity = s.intensity,
            autoLube = s.autoLube,
            finalEnabled = s.finalEnabled,
            finalMinMl = lube.finalMinMl,
            finalMaxMl = lube.finalMaxMl,
            microDose = lube.microFor(s.mode),
            smartStroke = s.smartStroke,
            nonstop = s.nonstop,
        )
    }

    fun startSession(cfg: SessionConfig, source: Source = Source.LOCAL) = post { startSessionInternal(cfg, source) }

    private fun startSessionInternal(cfg: SessionConfig, source: Source): String? {
        if (!ble.anyMotionConnected()) return "Ingen maskin ansluten".also { log(it) }
        if (engine != null) return "En session körs redan"
        if (arbiter.control == CommandArbiter.Control.MANUAL && arbiter.owner != source) return "Manuell styrning pågår"
        val now = SystemClock.elapsedRealtime()
        arbiter.setMode(cfg.mode)
        settings.mode = cfg.mode
        doses.resetSession()
        val seed = cfg.seed ?: (SecureRandom().nextLong() xor System.nanoTime())
        val e = AutoEngine(cfg, seed, history) { ml ->
            if (activeLube != null && cfg.mode.lubeAllowed) doses.estimateMs(DoseKind.FINAL, ml) else 0L
        }
        arbiter.beginAuto(source)
        ble.releaseHolds()
        e.start(now)
        engine = e
        config = cfg
        acquireWakeLock()
        log(buildString {
            append("Session startad (${cfg.mode.label}")
            if (e.durationMs > 0) append(", ${e.durationMs / 60_000} min")
            cfg.challengeDay?.let { append(", utmaning dag $it") }
            append(")")
        })
        return null
    }

    fun startChallengeDay() = post {
        val today = epochDayToday()
        val c = settings.challenge?.rollover(today) ?: return@post
        settings.challenge = c
        val day = c.currentDay(today) ?: run { log("Ingen utmaningsdag i dag"); return@post }
        val cfg = c.sessionConfig(day, settings.lubeSettings, settings.smartStroke, settings.autoLube)
        startSessionInternal(cfg, Source.LOCAL)
    }

    /** "Avsluta": a normal end (not an emergency). Motion and pump stop. */
    fun endSession() = post { finishSession(completed = false, why = "Avslutad") }

    fun finalNow() = post { engine?.finishSoon(SystemClock.elapsedRealtime()) }

    fun setIntensity(v: Int) = post {
        settings.intensity = v
        engine?.setIntensity(v)
    }

    fun pauseSession() = post { pauseInternal("Pausad") }

    fun resumeSession() = post { resumeInternal(Source.LOCAL) }

    private fun pauseInternal(why: String) {
        val now = SystemClock.elapsedRealtime()
        engine?.pause(now)
        arbiter.pause(why)
        log(why)
    }

    private fun resumeInternal(source: Source): Boolean {
        if (!ble.anyMotionConnected()) {
            log("Kan inte fortsätta – ingen maskin ansluten")
            return false
        }
        if (!arbiter.resume(source)) return false
        ble.releaseHolds()
        engine?.resume(SystemClock.elapsedRealtime())
        log("Fortsätter")
        return true
    }

    private fun finishSession(completed: Boolean, why: String) {
        val e = engine
        val cfg = config
        val now = SystemClock.elapsedRealtime()
        if (e != null && cfg != null) {
            // A finished engine records itself; an interrupted session still counts for anti-repeat.
            if (!e.finished) history.recordSession(e.sequence)
            settings.motionHistory = history
            cfg.challengeDay?.let { day -> recordChallenge(day, completed, (e.progress(now) * 100).toInt()) }
        }
        engine = null
        config = null
        sessionLabel = why
        arbiter.endControl(Source.SYSTEM)
        if (!completed) doses.stopAll(why)
        releaseWakeLockIfIdle()
        log(why)
    }

    private fun recordChallenge(day: Int, completed: Boolean, progressPct: Int) {
        val c = settings.challenge ?: return
        val today = epochDayToday()
        settings.challenge = c.record(day, if (completed) DayStatus.DONE else DayStatus.PARTIAL, if (completed) 100 else progressPct, today)
    }

    fun updateChallenge(transform: (Challenge?) -> Challenge?) = post {
        settings.challenge = transform(settings.challenge)
        Reminders.schedule(this, settings.challenge)
    }

    // ================================================================== manual

    fun manualStart() = post {
        if (engine != null) { log("Avsluta automatiken först"); return@post }
        if (!ble.anyMotionConnected()) { log("Ingen maskin ansluten"); return@post }
        if (arbiter.beginManual(Source.LOCAL)) {
            ble.releaseHolds()
            acquireWakeLock()
        }
    }

    fun manualSpeed(v: Int) = control.post { arbiter.manualSpeed(Source.LOCAL, v) }

    fun manualStroke(start: Int, stop: Int) = post { arbiter.manualStroke(Source.LOCAL, start, stop) }

    fun manualEnd() = post {
        arbiter.endControl(Source.LOCAL)
        releaseWakeLockIfIdle()
    }

    // ================================================================== STOP

    fun emergencyStop(source: Source) = post { emergencyStopInternal(source, "STOPP") }

    private fun emergencyStopInternal(source: Source, why: String) {
        val e = engine
        val cfg = config
        if (e != null && cfg != null) {
            cfg.challengeDay?.let { recordChallenge(it, false, (e.progress(SystemClock.elapsedRealtime()) * 100).toInt()) }
            if (!e.finished) history.recordSession(e.sequence)
            settings.motionHistory = history
        }
        engine = null
        config = null
        sessionLabel = "STOPP"
        arbiter.stop(source, why)
        remoteDoseActive = false
        releaseWakeLockIfIdle()
        log(if (source == Source.SYSTEM) "STOPP: $why" else "STOPP (${source.label})")
        remote.sendNotice("STOPP: $why")
        updateNotification(force = true)
    }

    fun clearStop() = post { arbiter.clearStop() }

    // ================================================================== lube

    /** Manual dose from the phone. [ml] null = the configured volume for the kind. */
    fun dose(kind: DoseKind, ml: Double?) = post {
        val s = settings.lubeSettings
        val volume = ml ?: when (kind) {
            DoseKind.EXTRA -> s.extraMl
            DoseKind.SUPER -> s.superMl
            else -> s.manualMl
        }
        reportDose(kind, arbiter.requestDose(Source.LOCAL, kind, volume, SystemClock.elapsedRealtime()))
    }

    fun stopDose() = post { doses.stopAll("avbruten") }

    /** Prime (forward) or empty (reverse) the tube, or run a calibration. */
    fun serviceRun(speed: Int, seconds: Int) = post {
        val r = arbiter.requestService(speed, seconds * 1000L, SystemClock.elapsedRealtime())
        if (r is DoseResult.Rejected) log("Underhåll: ${r.reason}")
        else log(if (speed < 0) "Tömmer slangen ($seconds s)" else "Pumpar $speed % i $seconds s")
    }

    /** Store a calibration point: running [speed] % for [seconds] s gave [measuredMl] ml. */
    fun saveCalibrationPoint(speed: Int, seconds: Int, measuredMl: Double) = post {
        val role = activeLubeRole ?: DeviceRole.LUBE_MEGALUBE
        val effectiveMs = seconds * 1000.0 - FlowModel.rampCompensationMs(speed)
        if (effectiveMs <= 0 || measuredMl <= 0) return@post
        val flow = measuredMl / (effectiveMs / 1000.0)
        val current = settings.calibration(role)
        val base = if (current.calibrated) current.points.filter { it.speedPct != speed } else emptyList()
        settings.setCalibration(role, FlowModel(base + com.hismith.session.core.CalPoint(speed, flow), calibrated = true))
        log("Kalibrering: $speed % = ${"%.2f".format(flow)} ml/s")
    }

    fun resetCalibration() = post {
        settings.setCalibration(activeLubeRole ?: DeviceRole.LUBE_MEGALUBE, null)
        log("Kalibreringen återställd")
    }

    fun setReservoir(ml: Double?) = post { settings.reservoirMl = ml }

    fun calibrationFor(role: DeviceRole): FlowModel = settings.calibration(role)

    private fun onLubeDelivered(kind: DoseKind, ml: Double) {
        if (kind == DoseKind.SERVICE) return
        settings.reservoirMl?.let { settings.reservoirMl = (it - ml).coerceAtLeast(0.0) }
    }

    private fun reportDose(kind: DoseKind, r: DoseResult) {
        when (r) {
            is DoseResult.Started -> log("${kind.label}: ${"%.1f".format(r.plan.ml)} ml @ ${r.plan.speedPct} %")
            is DoseResult.Preempted -> log("${kind.label} ersätter ${r.replaced.label}")
            is DoseResult.Queued -> log("${kind.label} i kö (plats ${r.position})")
            is DoseResult.Rejected -> log("${kind.label}: ${r.reason}")
        }
    }

    // ================================================================== remote

    fun remoteStart() = post {
        val url = settings.serverUrl
        if (url.isBlank()) { log("Ange serveradressen först"); return@post }
        val secret = KeystoreBox.decrypt(settings.hostSecretEncrypted) ?: ""
        watchdog.reset()
        settingsChanged()
        remote.start(url, secret)
    }

    fun remoteNewLink() = post {
        if (remote.isControllerActive) remote.kick()
        watchdog.reset()
        remote.newInvite()
    }

    fun remoteApprove() = post {
        cancelApprovalNotification()
        if (!pendingApproval) return@post
        pendingApproval = false
        remote.approve()
        watchdog.start(SystemClock.elapsedRealtime())
        log("Mottagaren godkänd")
    }

    fun remoteDeny() = post {
        cancelApprovalNotification()
        pendingApproval = false
        remote.deny()
        log("Mottagaren nekad")
    }

    fun remoteKick() = post {
        if (arbiter.owner == Source.REMOTE) emergencyStopInternal(Source.LOCAL, "Länken avslutad")
        pendingApproval = false
        watchdog.reset()
        remote.kick()
    }

    fun remoteStop() = post {
        if (arbiter.owner == Source.REMOTE) emergencyStopInternal(Source.LOCAL, "Länken avslutad")
        pendingApproval = false
        watchdog.reset()
        remote.stop()
    }

    override fun onRemoteChanged() = publish(force = true)

    override fun onControllerJoined(resumed: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (resumed && !watchdog.wasRevoked) {
            watchdog.heard(now)
            log("Mottagaren är tillbaka")
            return
        }
        watchdog.reset()
        if (settings.requireApproval) {
            pendingApproval = true
            showApprovalNotification()
            log("Mottagare vill ansluta – godkänn i appen")
        } else {
            pendingApproval = false
            remote.approve()
            watchdog.start(now)
            log("Mottagare ansluten")
        }
    }

    override fun onControllerLeft() {
        pendingApproval = false
        cancelApprovalNotification()
        watchdog.lost(SystemClock.elapsedRealtime())
        log("Mottagaren kopplade ner")
    }

    override fun onHostLinkLost() {
        watchdog.lost(SystemClock.elapsedRealtime())
        log("Tappade servern – försöker igen")
    }

    override fun onControllerMessage(m: JSONObject) {
        val now = SystemClock.elapsedRealtime()
        if (!remote.isControllerActive) return
        watchdog.heard(now)
        if (m.optString("t") != "cmd") return
        val cmd = RemoteCommand.parse(m.optJSONObject("c")) ?: run {
            remote.sendNotice("Okänt kommando")
            return
        }
        val notice: String? = when (cmd) {
            RemoteCommand.Stop -> { emergencyStopInternal(Source.REMOTE, "STOPP"); null }
            RemoteCommand.ManualStart -> when {
                engine != null -> "Automatiken kör – avsluta den först"
                !ble.anyMotionConnected() -> "Ingen maskin ansluten"
                arbiter.beginManual(Source.REMOTE) -> { ble.releaseHolds(); acquireWakeLock(); log("Länken styr manuellt"); null }
                else -> "Telefonen styr just nu"
            }
            is RemoteCommand.Speed -> if (arbiter.manualSpeed(Source.REMOTE, cmd.percent)) null else "Starta manuell styrning först"
            RemoteCommand.ManualEnd -> { arbiter.endControl(Source.REMOTE); releaseWakeLockIfIdle(); null }
            is RemoteCommand.AutoStart -> when {
                arbiter.control != CommandArbiter.Control.NONE && arbiter.owner != Source.REMOTE -> "Telefonen styr just nu"
                else -> {
                    if (arbiter.control == CommandArbiter.Control.MANUAL) arbiter.endControl(Source.REMOTE)
                    val cfg = buildConfigFromSettings().copy(minMinutes = cmd.minMinutes, maxMinutes = cmd.maxMinutes, intensity = cmd.intensity)
                    startSessionInternal(cfg, Source.REMOTE)
                }
            }
            RemoteCommand.AutoEnd -> if (engine != null) { finishSession(false, "Avslutad via länken"); null } else null
            RemoteCommand.FinalNow -> if (arbiter.owner == Source.REMOTE) { engine?.finishSoon(now); null } else "Bara den som startade sessionen"
            is RemoteCommand.Intensity -> if (arbiter.owner == Source.REMOTE) { engine?.setIntensity(cmd.percent); null } else "Bara den som startade sessionen"
            RemoteCommand.Pause -> { if (arbiter.isMotionActive) pauseInternal("Pausad via länken"); null }
            RemoteCommand.Resume -> if (resumeInternal(Source.REMOTE)) null else "Kan inte fortsätta härifrån"
            is RemoteCommand.Lube -> {
                val s = settings.lubeSettings
                val ml = if (cmd.kind == DoseKind.SUPER) s.superMl else s.extraMl
                val r = arbiter.requestDose(Source.REMOTE, cmd.kind, ml, now)
                reportDose(cmd.kind, r)
                if (r is DoseResult.Rejected) r.reason else { remoteDoseActive = true; null }
            }
        }
        notice?.let { remote.sendNotice(it) }
        sendRemoteState(now, force = true)
    }

    private fun sendRemoteState(now: Long, force: Boolean) {
        if (!remote.isControllerActive) return
        val e = engine
        val lube = settings.lubeSettings
        val limits = arbiter.limits
        val dose = doses.activeInfo(now)
        val s = JSONObject()
            .put("mode", arbiter.mode.name)
            .put("control", arbiter.control.name.lowercase())
            .put("owner", arbiter.owner?.name?.lowercase() ?: JSONObject.NULL)
            .put("safety", arbiter.safety.name.lowercase())
            .put("reason", arbiter.reason)
            .put("speed", arbiter.outputSpeed)
            .put("target", arbiter.manualTargetPercent)
            .put("maxSpeed", minOf(limits.maxFor(arbiter.mode), limits.remoteMaxSpeed))
            .put("phase", if (e != null) sessionLabel else "")
            .put("elapsed", (e?.elapsedMs(now) ?: 0L) / 1000)
            .put("remaining", e?.remainingMs(now)?.let { it / 1000 } ?: JSONObject.NULL)
            .put("intensity", e?.intensityPercent ?: settings.intensity)
            .put("machines", ble.motionDevices().count { it.connected })
            .put("lube", JSONObject()
                .put("available", activeLube != null && arbiter.mode.lubeAllowed)
                .put("extra", limits.remoteAllowExtra)
                .put("super", limits.remoteAllowSuper)
                .put("extraMl", lube.extraMl)
                .put("superMl", lube.superMl)
                .put("busy", dose != null))
        val json = s.toString()
        if (!force && json == lastRemoteJson) return
        lastRemoteJson = json
        remote.sendState(s)
    }

    // ================================================================== state / log

    private fun log(msg: String) {
        events.addFirst("${timeFmt.format(Date())}  $msg")
        while (events.size > 40) events.removeLast()
    }

    private fun publish(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastPublish < PUBLISH_MS) return
        lastPublish = now
        val state = buildState(now)
        latest = state
        if (listeners.isNotEmpty()) main.post { listeners.forEach { it(state) } }
        updateNotification(force = false)
    }

    private fun buildState(now: Long): UiState {
        val e = engine
        val devices = ArrayList<DeviceUi>()
        ble.all().forEach { d ->
            devices += DeviceUi(
                d.address, d.name, d.role, d.kind, d.connected,
                when {
                    d.held && d.connected -> "Står still – tryck Pausa → Fortsätt"
                    d.kind == DeviceKind.SIMULATED -> "Simulator · ${d.lastSpeed} %"
                    d.connected -> "Ansluten"
                    else -> "Ansluter…"
                },
            )
        }
        megaWanted.forEach { (addr, dev) ->
            val link = megaLinks[addr]
            val name = link?.displayName ?: try { dev.name } catch (_: SecurityException) { null } ?: "MegaLube"
            devices += DeviceUi(addr, name, DeviceRole.LUBE_MEGALUBE, DeviceKind.SPP, link?.connected == true,
                if (link?.connected == true) "Ansluten" else if (addr in megaConnecting) "Ansluter…" else "Väntar på kortet…")
        }
        simLube?.let { devices += DeviceUi("SIM-LUBE", it.displayName, DeviceRole.LUBE_MEGALUBE, DeviceKind.SIMULATED, true, "Simulator · ${it.speed} %") }

        val mega = activeLube as? MegaLubeLink
        val firmware = when {
            mega != null -> mega.lastState?.let { "${it.phase} · ${it.currentPct} % (mål ${it.targetPct} %) · WDT ${it.watchdogMs} ms" }
            activeLube is SimulatedLube -> (activeLube as SimulatedLube).stateLine(now)
            else -> null
        }
        val cal = activeLubeRole?.let { settings.calibration(it) }
        val lube = LubeUi(
            connected = activeLube != null,
            name = activeLube?.displayName,
            role = activeLubeRole,
            calibrated = cal?.calibrated == true,
            supportsReverse = activeLube?.supportsReverse == true,
            active = doses.activeInfo(now),
            queued = doses.queuedKinds,
            sessionMl = doses.sessionDeliveredMl,
            reservoirMl = settings.reservoirMl,
            firmware = firmware,
            linkAgeMs = mega?.let { now - it.lastRxMs },
        )
        val session = SessionUi(
            running = e != null,
            paused = arbiter.safety == CommandArbiter.Safety.PAUSED,
            label = if (arbiter.safety == CommandArbiter.Safety.PAUSED) "Pausad – ${arbiter.reason}" else sessionLabel,
            elapsedSec = (e?.elapsedMs(now) ?: 0L) / 1000,
            remainingSec = e?.remainingMs(now)?.let { it / 1000 },
            progress = e?.progress(now) ?: 0f,
            intensity = e?.intensityPercent ?: settings.intensity,
            finalMl = e?.plannedFinalMl ?: 0.0,
            finalStarted = e?.finalStartedFlag == true,
            challengeDay = config?.challengeDay,
        )
        val remoteUi = RemoteUi(
            status = remote.status,
            link = remote.link,
            error = remote.errorMessage,
            pendingApproval = pendingApproval,
            controllerActive = remote.isControllerActive,
            controllerSilent = remote.isControllerActive && watchdog.isSilent,
        )
        val hist = IntArray(HISTORY_SIZE) { speedHistory[(historyPos + it) % HISTORY_SIZE] }
        return UiState(
            devices = devices,
            mode = arbiter.mode,
            control = arbiter.control,
            owner = arbiter.owner,
            safety = arbiter.safety,
            safetyReason = arbiter.reason,
            speed = arbiter.outputSpeed,
            manualTarget = arbiter.manualTargetPercent,
            session = session,
            lube = lube,
            remote = remoteUi,
            events = events.toList(),
            speedHistory = hist,
        )
    }

    // ================================================================== wake lock / notifications

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hismith:session").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLockIfIdle() {
        if (engine == null && arbiter.control == CommandArbiter.Control.NONE) releaseWakeLock()
    }

    private fun releaseWakeLock() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wakeLock = null
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Session", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        nm.createNotificationChannel(NotificationChannel(ALERT_CHANNEL_ID, "Länk och påminnelser", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(Reminders.CHANNEL_ID, "Utmaning", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun servicePending(action: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code, Intent(this, SessionService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun openAppPending(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun buildNotification(title: String, text: String): Notification {
        val b = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openAppPending())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "STOPP", servicePending(ACTION_STOP, 1)).build())
        if (::arbiter.isInitialized && activeLube != null && arbiter.mode.lubeAllowed) {
            b.addAction(Notification.Action.Builder(null, "SUPER SPRUT", servicePending(ACTION_SUPER, 2)).build())
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return b.build()
    }

    private fun updateNotification(force: Boolean) {
        if (!foregroundStarted) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotif < 2_000) return
        val s = latest ?: return
        val title = when {
            s.safety == CommandArbiter.Safety.STOPPED -> "STOPPAD"
            s.session.running -> "Session · ${s.session.label}"
            s.control == CommandArbiter.Control.MANUAL -> "Manuell styrning (${s.owner?.label ?: ""})"
            else -> "Hismith Smart Session"
        }
        val text = buildString {
            val machines = s.devices.count { it.role.isMotion && it.connected }
            append("$machines maskin(er)")
            if (s.lube.connected) append(" · lube")
            if (s.session.running) append(" · ${s.speed} %")
            if (s.remote.controllerActive) append(" · länk aktiv")
        }
        val key = "$title|$text"
        if (!force && key == lastNotifText) return
        lastNotif = now
        lastNotifText = key
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(title, text))
        } catch (_: Exception) {}
    }

    private fun showApprovalNotification() {
        val n = Notification.Builder(this, ALERT_CHANNEL_ID)
            .setContentTitle("Mottagare vill ansluta")
            .setContentText("Någon öppnade din engångslänk. Godkänn bara om du väntar dig det.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openAppPending())
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, "Godkänn", servicePending(ACTION_REMOTE_APPROVE, 3)).build())
            .addAction(Notification.Action.Builder(null, "Neka", servicePending(ACTION_REMOTE_DENY, 4)).build())
            .build()
        try { getSystemService(NotificationManager::class.java).notify(APPROVAL_NOTIF_ID, n) } catch (_: Exception) {}
    }

    private fun cancelApprovalNotification() {
        try { getSystemService(NotificationManager::class.java).cancel(APPROVAL_NOTIF_ID) } catch (_: Exception) {}
    }

    private fun epochDayToday(): Long = Reminders.epochDayToday()
}
