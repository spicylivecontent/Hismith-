package com.hismith.session

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.*
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.math.hypot

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_PERMS = 1001
        private const val HOME_URL = "https://www.pornhub.com"
    }

    private var serviceStarted = false
    private var pendingScanRole: DeviceRole? = null

    private var service: SessionService? = null
    private var bound = false
    private var isImmersiveActive = false
    private var isTopBarHiddenInBrowser = false

    private lateinit var rootContainer: FrameLayout
    private lateinit var controlPanel: View
    private lateinit var browserPanel: View
    private lateinit var webView: WebView
    private lateinit var browserTopBar: View
    private lateinit var fullscreenContainer: FrameLayout

    private lateinit var btnAddDevice: MaterialButton
    private lateinit var btnStart: MaterialButton
    private lateinit var btnModeControl: MaterialButton
    private lateinit var btnModeBrowser: MaterialButton
    private lateinit var statusText: TextView
    private lateinit var devicesText: TextView
    private lateinit var phaseText: TextView
    private lateinit var speedText: TextView
    private lateinit var timeText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var minTimeInput: EditText
    private lateinit var maxTimeInput: EditText
    private lateinit var autoLubeCheck: CheckBox
    private lateinit var smartStrokeCheck: CheckBox
    private lateinit var urlBar: EditText
    private lateinit var btnOverlayStop: MaterialButton
    private lateinit var btnOverlayMega: MaterialButton
    private lateinit var btnOverlayBarToggle: MaterialButton
    private lateinit var btnHideTopBar: MaterialButton

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private val prefs by lazy { getSharedPreferences("hismith_prefs", MODE_PRIVATE) }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as SessionService.LocalBinder).getService()
            bound = true
            service?.startForegroundProtected()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            service = null
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != SessionService.BROADCAST_STATUS) return
            val running = intent.getBooleanExtra(SessionService.EXTRA_RUNNING, false)
            val speed = intent.getIntExtra(SessionService.EXTRA_SPEED, -1)
            val phase = intent.getStringExtra(SessionService.EXTRA_PHASE)
            val elapsed = intent.getLongExtra(SessionService.EXTRA_ELAPSED, -1)
            val progress = intent.getFloatExtra(SessionService.EXTRA_PROGRESS, -1f)
            val summary = intent.getStringExtra(SessionService.EXTRA_DEVICE_SUMMARY) ?: ""

            runOnUiThread {
                val hasMachine = summary.split(";").any {
                    val p = it.split(":")
                    p.size >= 2 && p[0] != "LUBE_LAUNCHER" && p[1] == "1"
                }
                btnStart.isEnabled = hasMachine && !running
                btnStart.alpha = if (btnStart.isEnabled) 1f else 0.45f
                if (speed >= 0) speedText.text = "$speed %"
                phase?.let { phaseText.text = it }
                if (elapsed >= 0) timeText.text = "%02d:%02d".format(elapsed / 60, elapsed % 60)
                if (progress >= 0f) progressBar.progress = (progress * 100).toInt()

                val lines = summary.split(";").filter { it.isNotBlank() }.mapNotNull { entry ->
                    val p = entry.split(":")
                    if (p.size >= 3) {
                        val role = try { DeviceRole.valueOf(p[0]).label } catch (_: Exception) { p[0] }
                        val ok = if (p[1] == "1") "✓" else "…"
                        "$ok $role (${p[2]})"
                    } else null
                }
                devicesText.text = if (lines.isEmpty()) "Inga enheter anslutna (tryck för att koppla från)" else lines.joinToString("\n")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        bindViews()
        setupWebView()
        setupClicks()
        setupDraggableOverlays()
        loadSettings()

        val filter = IntentFilter(SessionService.BROADCAST_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(statusReceiver, filter)
        }
        showControlMode()

        if (hasBtPermissions()) {
            ensureServiceStarted()
            askIgnoreBatteryOptimizations()
        } else {
            statusText.text = "Ge appen Bluetooth-behörighet för att börja"
            requestPermissions()
        }
    }

    /**
     * Start + bind the foreground service. Only called once Bluetooth permissions are
     * granted – on Android 14+ a connectedDevice foreground service is not allowed to
     * start before BLUETOOTH_CONNECT has been granted.
     */
    private fun ensureServiceStarted() {
        if (serviceStarted) return
        serviceStarted = true
        val svc = Intent(this, SessionService::class.java)
        ContextCompat.startForegroundService(this, svc)
        bindService(svc, connection, Context.BIND_AUTO_CREATE)
        statusText.text = "Redo – lägg till en enhet"
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        if (hasBtPermissions()) {
            ensureServiceStarted()
            service?.startForegroundProtected()
            askIgnoreBatteryOptimizations()
            pendingScanRole?.let { role -> pendingScanRole = null; startScanForRole(role) }
        } else {
            statusText.text = "Bluetooth-behörighet nekad – appen kan inte styra maskiner"
        }
    }

    private fun bindViews() {
        rootContainer = findViewById(R.id.rootContainer)
        controlPanel = findViewById(R.id.controlPanel)
        browserPanel = findViewById(R.id.browserPanel)
        webView = findViewById(R.id.webView)
        browserTopBar = findViewById(R.id.browserTopBar)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)
        btnAddDevice = findViewById(R.id.btnAddDevice)
        btnStart = findViewById(R.id.btnStart)
        btnModeControl = findViewById(R.id.btnModeControl)
        btnModeBrowser = findViewById(R.id.btnModeBrowser)
        statusText = findViewById(R.id.statusText)
        devicesText = findViewById(R.id.devicesText)
        phaseText = findViewById(R.id.phaseText)
        speedText = findViewById(R.id.speedText)
        timeText = findViewById(R.id.timeText)
        progressBar = findViewById(R.id.progressBar)
        minTimeInput = findViewById(R.id.minTimeInput)
        maxTimeInput = findViewById(R.id.maxTimeInput)
        autoLubeCheck = findViewById(R.id.autoLubeCheck)
        smartStrokeCheck = findViewById(R.id.smartStrokeCheck)
        urlBar = findViewById(R.id.urlBar)
        btnOverlayStop = findViewById(R.id.btnOverlayStop)
        btnOverlayMega = findViewById(R.id.btnOverlayMega)
        btnOverlayBarToggle = findViewById(R.id.btnOverlayBarToggle)
        btnHideTopBar = findViewById(R.id.btnHideTopBar)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            userAgentString = userAgentString.replace("; wv", "").replace("Version/4.0", "")
        }
        webView.webViewClient = AdBlockWebViewClient(
            onPageStarted = { url -> runOnUiThread { urlBar.setText(url ?: "") } }
        )
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                browserTopBar.visibility = View.GONE
                webView.visibility = View.GONE
                fullscreenContainer.visibility = View.VISIBLE
                if (view != null) {
                    fullscreenContainer.addView(
                        view,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                    )
                }
                // Rotate to sensor landscape for fullscreen video & activate immersive mode
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                setImmersiveMode(true)
                // Keep draggable overlay buttons visible on top of fullscreen video
                showOverlayButtons(showMenuToggle = false)
                rootContainer.post { clampAllOverlaysToBounds() }
            }

            override fun onHideCustomView() {
                customView?.let { fullscreenContainer.removeView(it) }
                customView = null
                fullscreenContainer.visibility = View.GONE
                webView.visibility = View.VISIBLE
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

                if (browserPanel.visibility == View.VISIBLE) {
                    browserTopBar.visibility = if (isTopBarHiddenInBrowser) View.GONE else View.VISIBLE
                    setImmersiveMode(true)
                    showOverlayButtons(showMenuToggle = isTopBarHiddenInBrowser)
                } else {
                    setImmersiveMode(false)
                    hideOverlayButtons()
                }
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
                rootContainer.post { clampAllOverlaysToBounds() }
            }
        }
        webView.loadUrl(prefs.getString("last_url", HOME_URL) ?: HOME_URL)
    }

    private fun setupClicks() {
        btnAddDevice.setOnClickListener { showRoleThenScan() }
        devicesText.setOnClickListener { showRemoveDeviceDialog() }

        btnStart.setOnClickListener {
            saveSettings()
            if (service == null) {
                Toast.makeText(this, "Tjänsten är inte igång ännu", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startService(Intent(this, SessionService::class.java).apply {
                action = SessionService.ACTION_START_SESSION
                putExtra("min", minTimeInput.text.toString().toIntOrNull() ?: 20)
                putExtra("max", maxTimeInput.text.toString().toIntOrNull() ?: 45)
                putExtra("autoLube", autoLubeCheck.isChecked)
                putExtra("smartStroke", smartStrokeCheck.isChecked)
            })
            statusText.text = "Session startad"
        }

        findViewById<MaterialButton>(R.id.btnEndSession).setOnClickListener {
            startService(Intent(this, SessionService::class.java).setAction(SessionService.ACTION_END_SESSION))
        }
        findViewById<MaterialButton>(R.id.btnSpray).setOnClickListener {
            startService(Intent(this, SessionService::class.java).setAction(SessionService.ACTION_SHORT_SPRAY))
        }
        findViewById<MaterialButton>(R.id.btnMegaInControl).setOnClickListener {
            startService(Intent(this, SessionService::class.java).setAction(SessionService.ACTION_MEGA_SPRAY))
        }
        findViewById<MaterialButton>(R.id.btnEmergency).setOnClickListener {
            startService(Intent(this, SessionService::class.java).setAction(SessionService.ACTION_STOP))
            Toast.makeText(this, "NÖDSTOPP", Toast.LENGTH_SHORT).show()
        }
        btnOverlayStop.setOnClickListener {
            startService(Intent(this, SessionService::class.java).setAction(SessionService.ACTION_STOP))
            Toast.makeText(this, "NÖDSTOPP", Toast.LENGTH_SHORT).show()
        }
        btnOverlayMega.setOnClickListener {
            startService(Intent(this, SessionService::class.java).setAction(SessionService.ACTION_MEGA_SPRAY))
            Toast.makeText(this, "SUPER SPRUT", Toast.LENGTH_SHORT).show()
        }
        btnHideTopBar.setOnClickListener {
            isTopBarHiddenInBrowser = true
            browserTopBar.visibility = View.GONE
            showOverlayButtons(showMenuToggle = true)
            setImmersiveMode(true)
        }
        btnOverlayBarToggle.setOnClickListener {
            if (customView != null) {
                (webView.webChromeClient as? WebChromeClient)?.onHideCustomView()
            } else {
                isTopBarHiddenInBrowser = false
                browserTopBar.visibility = View.VISIBLE
                btnOverlayBarToggle.visibility = View.GONE
            }
        }

        btnModeControl.setOnClickListener { showControlMode() }
        btnModeBrowser.setOnClickListener { showBrowserMode() }
        findViewById<MaterialButton>(R.id.btnModeControlFromBrowser).setOnClickListener { showControlMode() }
        findViewById<MaterialButton>(R.id.btnBrowserBack).setOnClickListener { if (webView.canGoBack()) webView.goBack() }
        findViewById<MaterialButton>(R.id.btnBrowserForward).setOnClickListener { if (webView.canGoForward()) webView.goForward() }
        findViewById<MaterialButton>(R.id.btnBrowserHome).setOnClickListener { webView.loadUrl(HOME_URL) }
        findViewById<MaterialButton>(R.id.btnBrowserRefresh).setOnClickListener { webView.reload() }
        findViewById<MaterialButton>(R.id.btnGoUrl).setOnClickListener { navigateUrlBar() }
        urlBar.setOnEditorActionListener { _, _, _ -> navigateUrlBar(); true }
    }

    /**
     * Makes floating overlay buttons freely draggable across the screen while preserving click events.
     */
    private fun setupDraggableOverlays() {
        makeDraggable(btnOverlayStop, "overlay_stop")
        makeDraggable(btnOverlayMega, "overlay_mega")
        makeDraggable(btnOverlayBarToggle, "overlay_menu")
        rootContainer.post { restoreOverlayPositions() }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeDraggable(view: View, prefKeyPrefix: String) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0f
        var startY = 0f
        var isDragging = false

        view.setOnTouchListener { v, event ->
            val parent = v.parent as? View ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = v.x
                    startY = v.y
                    isDragging = false
                    v.bringToFront()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!isDragging && hypot(dx.toDouble(), dy.toDouble()) > touchSlop) {
                        isDragging = true
                    }
                    if (isDragging) {
                        val maxX = (parent.width - v.width).coerceAtLeast(0).toFloat()
                        val maxY = (parent.height - v.height).coerceAtLeast(0).toFloat()
                        v.x = (startX + dx).coerceIn(0f, maxX)
                        v.y = (startY + dy).coerceIn(0f, maxY)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        v.performClick()
                    } else {
                        val maxX = (parent.width - v.width).coerceAtLeast(1).toFloat()
                        val maxY = (parent.height - v.height).coerceAtLeast(1).toFloat()
                        prefs.edit()
                            .putFloat("${prefKeyPrefix}_rx", (v.x / maxX).coerceIn(0f, 1f))
                            .putFloat("${prefKeyPrefix}_ry", (v.y / maxY).coerceIn(0f, 1f))
                            .apply()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    isDragging = false
                    true
                }
                else -> false
            }
        }
    }

    private fun restoreOverlayPositions() {
        applySavedPositionOrClamp(btnOverlayStop, "overlay_stop")
        applySavedPositionOrClamp(btnOverlayMega, "overlay_mega")
        applySavedPositionOrClamp(btnOverlayBarToggle, "overlay_menu")
    }

    private fun applySavedPositionOrClamp(view: View, prefKeyPrefix: String) {
        val parent = rootContainer
        if (parent.width <= 0 || parent.height <= 0 || view.width <= 0 || view.height <= 0) return
        val maxX = (parent.width - view.width).coerceAtLeast(0).toFloat()
        val maxY = (parent.height - view.height).coerceAtLeast(0).toFloat()
        if (prefs.contains("${prefKeyPrefix}_rx") && prefs.contains("${prefKeyPrefix}_ry")) {
            val rx = prefs.getFloat("${prefKeyPrefix}_rx", 0f).coerceIn(0f, 1f)
            val ry = prefs.getFloat("${prefKeyPrefix}_ry", 1f).coerceIn(0f, 1f)
            view.x = rx * maxX
            view.y = ry * maxY
        } else {
            view.x = view.x.coerceIn(0f, maxX)
            view.y = view.y.coerceIn(0f, maxY)
        }
    }

    private fun clampAllOverlaysToBounds() {
        restoreOverlayPositions()
    }

    private fun showOverlayButtons(showMenuToggle: Boolean) {
        btnOverlayStop.visibility = View.VISIBLE
        btnOverlayMega.visibility = View.VISIBLE
        btnOverlayBarToggle.visibility = if (showMenuToggle) View.VISIBLE else View.GONE
        btnOverlayStop.bringToFront()
        btnOverlayMega.bringToFront()
        if (showMenuToggle) btnOverlayBarToggle.bringToFront()
        rootContainer.post { restoreOverlayPositions() }
    }

    private fun hideOverlayButtons() {
        btnOverlayStop.visibility = View.GONE
        btnOverlayMega.visibility = View.GONE
        btnOverlayBarToggle.visibility = View.GONE
    }

    /**
     * Enables or disables Android edge-to-edge immersive mode (hiding status + navigation bars).
     */
    private fun setImmersiveMode(enabled: Boolean) {
        isImmersiveActive = enabled
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (enabled) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && isImmersiveActive) {
            setImmersiveMode(true)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        rootContainer.postDelayed({ clampAllOverlaysToBounds() }, 100)
    }

    private fun showRemoveDeviceDialog() {
        val list = service?.getDevices().orEmpty()
        if (list.isEmpty()) return
        val labels = list.map { "${it.role.label}\n${it.name} (${it.address})" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Ta bort enhet?")
            .setItems(labels) { _, which -> service?.removeDevice(list[which].address) }
            .setNegativeButton("Avbryt", null)
            .show()
    }

    /** Step 1: choose role, Step 2: scan & pick device */
    private fun showRoleThenScan() {
        val roles = DeviceRole.allSelectable()
        val labels = roles.map { it.label }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Vilken typ av enhet?")
            .setItems(labels) { _, which ->
                startScanForRole(roles[which])
            }
            .setNegativeButton("Avbryt", null)
            .show()
    }

    private var scanCallback: ScanCallback? = null
    private val found = linkedMapOf<String, BluetoothDevice>()

    private fun startScanForRole(role: DeviceRole) {
        if (!hasBtPermissions()) { pendingScanRole = role; requestPermissions(); return }
        if (service == null) { ensureServiceStarted(); statusText.text = "Startar tjänsten – försök igen om en sekund"; return }
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            statusText.text = "Slå på Bluetooth först"
            return
        }
        found.clear()
        statusText.text = "Söker (${role.label})…"
        val scanner = adapter.bluetoothLeScanner ?: return
        scanCallback?.let { try { scanner.stopScan(it) } catch (_: Exception) {} }

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName
                    ?: (try { result.device.name } catch (_: SecurityException) { null })
                    ?: return
                val ok = name.contains("HISMITH", true) ||
                        name.contains("Hismith", true) ||
                        name.contains("Wildolo", true) ||
                        name.contains("Piupiu", true) ||
                        name.contains("piupiu", true)
                if (ok) found[result.device.address] = result.device
            }
        }
        scanCallback = cb
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)

        Handler(Looper.getMainLooper()).postDelayed({
            try { scanner.stopScan(cb) } catch (_: Exception) {}
            if (found.isEmpty()) statusText.text = "Ingen enhet hittad"
            else {
                val list = found.values.toList()
                val labels = list.map { "${it.name ?: "?"}\n${it.address}" }.toTypedArray()
                MaterialAlertDialogBuilder(this)
                    .setTitle("Välj enhet för: ${role.label}")
                    .setItems(labels) { _, which ->
                        statusText.text = "Ansluter…"
                        service?.connectDevice(list[which], role)
                    }
                    .setNegativeButton("Avbryt", null)
                    .show()
            }
        }, 5500)
    }

    private fun navigateUrlBar() {
        var url = urlBar.text.toString().trim()
        if (url.isEmpty()) return
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = if (url.contains(".") && !url.contains(" ")) "https://$url"
            else "https://duckduckgo.com/?q=${Uri.encode(url)}"
        }
        webView.loadUrl(url)
        prefs.edit().putString("last_url", url).apply()
    }

    private fun showControlMode() {
        if (customView != null) (webView.webChromeClient as? WebChromeClient)?.onHideCustomView()
        controlPanel.visibility = View.VISIBLE
        browserPanel.visibility = View.GONE
        hideOverlayButtons()
        setImmersiveMode(false)
        btnModeControl.alpha = 1f
        btnModeBrowser.alpha = 0.55f
    }

    private fun showBrowserMode() {
        controlPanel.visibility = View.GONE
        browserPanel.visibility = View.VISIBLE
        browserTopBar.visibility = if (isTopBarHiddenInBrowser) View.GONE else View.VISIBLE
        setImmersiveMode(true)
        showOverlayButtons(showMenuToggle = isTopBarHiddenInBrowser)
        btnModeControl.alpha = 0.55f
        btnModeBrowser.alpha = 1f
    }

    private fun hasBtPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += Manifest.permission.BLUETOOTH_CONNECT
            perms += Manifest.permission.BLUETOOTH_SCAN
        } else perms += Manifest.permission.ACCESS_FINE_LOCATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms += Manifest.permission.POST_NOTIFICATIONS
        if (perms.isNotEmpty()) ActivityCompat.requestPermissions(this, perms.toTypedArray(), REQ_PERMS)
    }

    private fun askIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    })
                } catch (_: Exception) {}
            }
        }
    }

    private fun loadSettings() {
        minTimeInput.setText(prefs.getInt("min_time", 20).toString())
        maxTimeInput.setText(prefs.getInt("max_time", 45).toString())
        autoLubeCheck.isChecked = prefs.getBoolean("auto_lube", true)
        smartStrokeCheck.isChecked = prefs.getBoolean("smart_stroke", false)
    }

    private fun saveSettings() {
        prefs.edit()
            .putInt("min_time", minTimeInput.text.toString().toIntOrNull() ?: 20)
            .putInt("max_time", maxTimeInput.text.toString().toIntOrNull() ?: 45)
            .putBoolean("auto_lube", autoLubeCheck.isChecked)
            .putBoolean("smart_stroke", smartStrokeCheck.isChecked)
            .apply()
    }

    override fun onPause() {
        super.onPause()
        saveSettings()
        webView.url?.let { prefs.edit().putString("last_url", it).apply() }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
        if (bound) { unbindService(connection); bound = false }
        webView.destroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            customView != null -> (webView.webChromeClient as? WebChromeClient)?.onHideCustomView()
            browserPanel.visibility == View.VISIBLE && webView.canGoBack() -> webView.goBack()
            browserPanel.visibility == View.VISIBLE -> showControlMode()
            else -> super.onBackPressed()
        }
    }
}
