package com.hismith.session

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.*
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
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
        private const val SCAN_TIMEOUT_MS = 5500L

        /** A tap counts as a user gesture for this long (used by the popup/redirect guard). */
        private const val USER_TOUCH_WINDOW_MS = 1500L

        /** Snabblänkar i webbläsarens verktygsfält (label → URL). */
        val QUICK_LINKS = listOf(
            "Pornhub" to "https://www.pornhub.com",
            "XVideos" to "https://www.xvideos.com",
            "XNXX" to "https://www.xnxx.com",
            "xHamster" to "https://xhamster.com",
            "RedTube" to "https://www.redtube.com",
            "YouPorn" to "https://www.youporn.com",
            "SpankBang" to "https://spankbang.com"
        )
    }

    /** Timestamp of the last finger-down/up inside the WebView (see [AdBlockWebViewClient]). */
    private var lastUserTouchMs = 0L
    private var lastBlockToastMs = 0L

    private var serviceStarted = false
    private var pendingScanRole: DeviceRole? = null

    private var service: SessionService? = null
    private var bound = false
    private var isImmersiveActive = false
    private var isTopBarHiddenInBrowser = false

    /**
     * Live state mirrored from [SessionService]. The stop controls (emergency button +
     * floating overlay buttons) are only shown while a machine is connected AND a session
     * is actually running – a red STOPP button when nothing runs is just confusing.
     */
    private var isSessionRunning = false
    private var hasMachineConnected = false

    /** Whether the current mode (browser / fullscreen video) wants the floating buttons. */
    private var overlayButtonsRequested = false
    private var overlayMenuToggleRequested = false

    private lateinit var rootContainer: FrameLayout
    private lateinit var controlPanel: View
    private lateinit var browserPanel: View
    private lateinit var webView: WebView
    private lateinit var browserTopBar: View
    private lateinit var fullscreenContainer: FrameLayout

    private lateinit var btnAddDevice: MaterialButton
    private lateinit var btnStart: MaterialButton
    private lateinit var btnEndSession: MaterialButton
    private lateinit var btnSpray: MaterialButton
    private lateinit var btnMegaInControl: MaterialButton
    private lateinit var btnEmergency: MaterialButton
    private lateinit var btnModeBrowser: MaterialButton
    private lateinit var btnModeControlFromBrowser: MaterialButton
    private lateinit var quickLinksRow: LinearLayout
    private lateinit var btnBrowserBack: MaterialButton
    private lateinit var btnBrowserForward: MaterialButton
    private lateinit var btnBrowserHome: MaterialButton
    private lateinit var btnBrowserRefresh: MaterialButton
    private lateinit var btnGoUrl: MaterialButton
    private lateinit var btnHideTopBar: MaterialButton
    private lateinit var btnOverlayStop: MaterialButton
    private lateinit var btnOverlayMega: MaterialButton
    private lateinit var btnOverlayBarToggle: MaterialButton
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

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private val prefs by lazy { getSharedPreferences("hismith_prefs", MODE_PRIVATE) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var scanTimeoutRunnable: Runnable? = null
    private var scanCallback: ScanCallback? = null
    /** Written by the BLE scan callback (binder thread), read on the UI thread – must be synchronised. */
    private val found: MutableMap<String, BluetoothDevice> =
        java.util.Collections.synchronizedMap(linkedMapOf<String, BluetoothDevice>())

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as SessionService.LocalBinder).getService()
            bound = true
            service?.startForegroundProtected()
            syncStateFromService()
            refreshSessionControls()
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
                hasMachineConnected = summary.split(";").any { entry ->
                    val p = entry.split(":")
                    p.size >= 2 && p[0] != DeviceRole.LUBE_LAUNCHER.name && p[1] == "1"
                }
                isSessionRunning = running

                btnStart.isEnabled = hasMachineConnected && !running
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
                devicesText.text =
                    if (lines.isEmpty()) "Inga enheter anslutna (tryck för att koppla från)"
                    else lines.joinToString("\n")

                refreshSessionControls()
            }
        }
    }

    /**
     * Chrome client kept as a field so the app can leave fullscreen video itself
     * (overlay "Meny" button / back press) without casting around.
     *
     * IMPORTANT: `onShowCustomView` must NOT hide the WebView and must NOT force a screen
     * orientation. Both actions make Chromium tear down / relayout the compositor surface,
     * which cancels the `<video>` fullscreen request – the fullscreen button then appears
     * to "bounce back" and nothing happens. The fullscreen container is declared after the
     * browser panel in the layout, so it already covers the WebView on its own.
     */
    private val chromeClient = object : WebChromeClient() {

        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
            if (customView != null) {
                callback?.onCustomViewHidden()
                return
            }
            customView = view
            customViewCallback = callback

            browserTopBar.visibility = View.GONE
            fullscreenContainer.visibility = View.VISIBLE
            view?.let {
                fullscreenContainer.addView(
                    it,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
            }

            setImmersiveMode(true)
            overlayButtonsRequested = true
            overlayMenuToggleRequested = false
            refreshSessionControls()
            rootContainer.post { clampAllOverlaysToBounds() }
        }

        override fun onHideCustomView() {
            customView?.let { fullscreenContainer.removeView(it) }
            customView = null
            customViewCallback?.onCustomViewHidden()
            customViewCallback = null
            fullscreenContainer.visibility = View.GONE

            if (browserPanel.visibility == View.VISIBLE) {
                browserTopBar.visibility = if (isTopBarHiddenInBrowser) View.GONE else View.VISIBLE
                overlayButtonsRequested = true
                overlayMenuToggleRequested = isTopBarHiddenInBrowser
            } else {
                overlayButtonsRequested = false
                overlayMenuToggleRequested = false
            }

            setImmersiveMode(true)
            refreshSessionControls()
            rootContainer.post { clampAllOverlaysToBounds() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Immersive everywhere – also in the control panel (clock/battery always hidden).
        setImmersiveMode(true)

        bindViews()
        refreshSessionControls()
        setupWebView()
        setupQuickLinks()
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

    override fun onResume() {
        super.onResume()
        // The system UI can reappear while we were in the background (another activity,
        // the permission dialogs, a phone call) – hide it again on every resume.
        setImmersiveMode(true)
        syncStateFromService()
        refreshSessionControls()
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
        btnEndSession = findViewById(R.id.btnEndSession)
        btnSpray = findViewById(R.id.btnSpray)
        btnMegaInControl = findViewById(R.id.btnMegaInControl)
        btnEmergency = findViewById(R.id.btnEmergency)
        btnModeBrowser = findViewById(R.id.btnModeBrowser)
        btnModeControlFromBrowser = findViewById(R.id.btnModeControlFromBrowser)
        quickLinksRow = findViewById(R.id.quickLinksRow)
        btnBrowserBack = findViewById(R.id.btnBrowserBack)
        btnBrowserForward = findViewById(R.id.btnBrowserForward)
        btnBrowserHome = findViewById(R.id.btnBrowserHome)
        btnBrowserRefresh = findViewById(R.id.btnBrowserRefresh)
        btnGoUrl = findViewById(R.id.btnGoUrl)
        btnHideTopBar = findViewById(R.id.btnHideTopBar)
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
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        // Debug builds only: lets you inspect the browser via chrome://inspect on a PC when
        // checking which elements on a site are ads. Release builds are never debuggable.
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            // Plain http inside https pages is a classic injection path for ad / redirect scripts.
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(false)
            // No popup windows: window.open() is refused, so popunders cannot open new tabs.
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            userAgentString = userAgentString.replace("; wv", "").replace("Version/4.0", "")
        }
        // Third-party cookies (ad networks / trackers) are off; the site's own cookies still work.
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)

        // Record real finger events so the navigation guard can tell a click from a script redirect.
        // Returning false keeps normal WebView handling (scrolling, video controls, links).
        webView.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_UP) {
                lastUserTouchMs = SystemClock.uptimeMillis()
            }
            false
        }

        // Nothing is downloaded or installed from the browser. "Uppdatera spelaren" / APK-bluffar
        // are delivered as downloads, so refuse them instead of letting WebView save them.
        webView.setDownloadListener { _, _, _, mimeType, _ ->
            showBlockedToast("Nedladdning blockerad ($mimeType)")
        }

        webView.webViewClient = AdBlockWebViewClient(
            onPageStarted = { url -> runOnUiThread { urlBar.setText(url ?: "") } },
            onBlocked = { reason -> runOnUiThread { showBlockedToast("Blockerade: $reason") } },
            hasRecentUserTouch = { SystemClock.uptimeMillis() - lastUserTouchMs < USER_TOUCH_WINDOW_MS }
        )
        webView.webChromeClient = chromeClient
        webView.loadUrl(prefs.getString("last_url", HOME_URL) ?: HOME_URL)
    }

    /** One button per site in [QUICK_LINKS], shown in the browser toolbar. */
    private fun setupQuickLinks() {
        QUICK_LINKS.forEach { (label, url) ->
            val button = MaterialButton(
                this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = label
                isAllCaps = false
                textSize = 12f
                setTextColor(0xFFE5C158.toInt())
                backgroundTintList = ColorStateList.valueOf(0x00000000)
                strokeColor = ColorStateList.valueOf(0xFF6E5A28.toInt())
                strokeWidth = dp(1)
                cornerRadius = dp(14)
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                insetTop = 0
                insetBottom = 0
                setPadding(dp(12), 0, dp(12), 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(30)
                ).apply { marginEnd = dp(6) }
                setOnClickListener { loadUrlFromUser(url) }
            }
            quickLinksRow.addView(button)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** Shows a message, but at most once every few seconds – popunder attempts come in bursts. */
    private fun showBlockedToast(message: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastBlockToastMs < 4000) return
        lastBlockToastMs = now
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
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

        btnEndSession.setOnClickListener {
            startService(Intent(this, SessionService::class.java).setAction(SessionService.ACTION_END_SESSION))
        }
        btnSpray.setOnClickListener {
            startService(Intent(this, SessionService::class.java).setAction(SessionService.ACTION_SHORT_SPRAY))
        }
        btnMegaInControl.setOnClickListener {
            startService(Intent(this, SessionService::class.java).setAction(SessionService.ACTION_MEGA_SPRAY))
        }
        btnEmergency.setOnClickListener { emergencyStop() }
        btnOverlayStop.setOnClickListener { emergencyStop() }
        btnOverlayMega.setOnClickListener {
            startService(Intent(this, SessionService::class.java).setAction(SessionService.ACTION_MEGA_SPRAY))
            Toast.makeText(this, "SUPER SPRUT", Toast.LENGTH_SHORT).show()
        }
        btnHideTopBar.setOnClickListener {
            isTopBarHiddenInBrowser = true
            browserTopBar.visibility = View.GONE
            overlayButtonsRequested = true
            overlayMenuToggleRequested = true
            refreshSessionControls()
            setImmersiveMode(true)
        }
        btnOverlayBarToggle.setOnClickListener {
            if (customView != null) {
                chromeClient.onHideCustomView()
            } else {
                isTopBarHiddenInBrowser = false
                browserTopBar.visibility = View.VISIBLE
                overlayMenuToggleRequested = false
                refreshSessionControls()
            }
        }

        btnModeBrowser.setOnClickListener { showBrowserMode() }
        btnModeControlFromBrowser.setOnClickListener { showControlMode() }
        btnBrowserBack.setOnClickListener { if (webView.canGoBack()) webView.goBack() }
        btnBrowserForward.setOnClickListener { if (webView.canGoForward()) webView.goForward() }
        btnBrowserHome.setOnClickListener { webView.loadUrl(HOME_URL) }
        btnBrowserRefresh.setOnClickListener { webView.reload() }
        btnGoUrl.setOnClickListener { navigateUrlBar() }
        urlBar.setOnEditorActionListener { _, _, _ -> navigateUrlBar(); true }
    }

    /** Hard stop: kills the session and every connected machine immediately. */
    private fun emergencyStop() {
        startService(Intent(this, SessionService::class.java).setAction(SessionService.ACTION_STOP))
        Toast.makeText(this, "NÖDSTOPP", Toast.LENGTH_SHORT).show()
    }

    /**
     * Makes floating overlay buttons freely draggable across the screen while preserving click events.
     *
     * No `bringToFront()` on ACTION_DOWN: re-parenting/reordering the view mid-gesture makes
     * Chromium drop the touch stream, which in fullscreen video ends the fullscreen session.
     * The buttons are already raised (elevation + [refreshSessionControls]) above the video.
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

    /**
     * Single source of truth for the stop controls.
     *
     * [btnEmergency], [btnOverlayStop] and [btnOverlayMega] are visible only when a machine
     * is connected and a session is actually running ([isSessionRunning]). The "Meny" toggle
     * is not session dependent – it is the only way back to the address bar once it is hidden.
     */
    private fun refreshSessionControls() {
        if (!::rootContainer.isInitialized) return
        val sessionActive = hasMachineConnected && isSessionRunning

        val emergencyVisibility = if (sessionActive) View.VISIBLE else View.GONE
        val overlaysVisible = sessionActive && overlayButtonsRequested
        val overlayVisibility = if (overlaysVisible) View.VISIBLE else View.GONE
        val menuVisible = overlayButtonsRequested && overlayMenuToggleRequested
        val menuVisibility = if (menuVisible) View.VISIBLE else View.GONE

        // Status broadcasts arrive ~12 times per second while a session runs: bail out when
        // nothing actually changed so we don't re-layout / re-order the views every tick.
        if (btnEmergency.visibility == emergencyVisibility &&
            btnOverlayStop.visibility == overlayVisibility &&
            btnOverlayMega.visibility == overlayVisibility &&
            btnOverlayBarToggle.visibility == menuVisibility
        ) return

        btnEmergency.visibility = emergencyVisibility
        btnOverlayStop.visibility = overlayVisibility
        btnOverlayMega.visibility = overlayVisibility
        btnOverlayBarToggle.visibility = menuVisibility

        if (overlaysVisible) {
            btnOverlayStop.bringToFront()
            btnOverlayMega.bringToFront()
        }
        if (menuVisible) btnOverlayBarToggle.bringToFront()

        rootContainer.post { restoreOverlayPositions() }
    }

    /** Pull the authoritative state from the service (after binding / on resume). */
    private fun syncStateFromService() {
        service?.let {
            isSessionRunning = it.isSessionRunning()
            hasMachineConnected = it.hasAnyMachine()
        }
    }

    /**
     * Hides the Android system UI for the WHOLE app – status bar (clock + battery) as well as
     * the navigation bar – in the control panel, the browser and fullscreen video alike.
     *
     * Both mechanisms are applied on purpose: the modern [WindowInsetsControllerCompat] plus
     * the legacy `FLAG_FULLSCREEN` window flag and `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`.
     * WebView / custom-view transitions occasionally reset the compat controller, and the
     * window flags keep the status bar hidden regardless (and let content use the notch area).
     */
    @Suppress("DEPRECATION")
    private fun setImmersiveMode(enabled: Boolean) {
        isImmersiveActive = enabled

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (enabled) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                }
            }
        }

        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }

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
        setImmersiveMode(true)
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

    private fun startScanForRole(role: DeviceRole) {
        if (!hasBtPermissions()) { pendingScanRole = role; requestPermissions(); return }
        if (service == null) { ensureServiceStarted(); statusText.text = "Startar tjänsten – försök igen om en sekund"; return }
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            statusText.text = "Slå på Bluetooth först"
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            statusText.text = "Bluetooth-skanner är inte tillgänglig"
            return
        }

        found.clear()
        statusText.text = "Söker (${role.label})…"
        stopBleScan()

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName
                    ?: (try { result.device.name } catch (_: SecurityException) { null })
                    ?: return
                val ok = HismithProtocol.MACHINE_NAME_PREFIXES.any { name.contains(it, true) } ||
                        HismithProtocol.LUBE_NAME_PREFIXES.any { name.contains(it, true) }
                if (ok) found[result.device.address] = result.device
            }

            override fun onScanFailed(errorCode: Int) {
                runOnUiThread { statusText.text = "Sökning misslyckades (fel $errorCode)" }
            }
        }
        scanCallback = cb

        try {
            scanner.startScan(
                null,
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                cb
            )
        } catch (e: Exception) {
            statusText.text = "Kunde inte starta sökning: ${e.message}"
            return
        }

        cancelScanTimeout()
        val timeout = Runnable {
            scanTimeoutRunnable = null
            stopBleScan()
            if (isFinishing || isDestroyed) return@Runnable
            if (found.isEmpty()) {
                statusText.text = "Ingen enhet hittad"
            } else {
                val list = synchronized(found) { found.values.toList() }
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
        }
        scanTimeoutRunnable = timeout
        mainHandler.postDelayed(timeout, SCAN_TIMEOUT_MS)
    }

    private fun stopBleScan() {
        val cb = scanCallback ?: return
        scanCallback = null
        try {
            (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                ?.bluetoothLeScanner?.stopScan(cb)
        } catch (_: Exception) {}
    }

    private fun cancelScanTimeout() {
        scanTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        scanTimeoutRunnable = null
    }

    private fun navigateUrlBar() {
        var url = urlBar.text.toString().trim()
        if (url.isEmpty()) return
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = if (url.contains(".") && !url.contains(" ")) "https://$url"
            else "https://duckduckgo.com/?q=${Uri.encode(url)}"
        }
        loadUrlFromUser(url)
    }

    /** Navigation the user asked for (address bar or quick link). */
    private fun loadUrlFromUser(url: String) {
        urlBar.setText(url)
        urlBar.clearFocus()
        webView.loadUrl(url)
        prefs.edit().putString("last_url", url).apply()
    }

    private fun showControlMode() {
        if (customView != null) chromeClient.onHideCustomView()
        controlPanel.visibility = View.VISIBLE
        browserPanel.visibility = View.GONE
        fullscreenContainer.visibility = View.GONE

        overlayButtonsRequested = false
        overlayMenuToggleRequested = false
        refreshSessionControls()

        // Immersive in the whole app: clock + battery stay hidden in the control panel too.
        setImmersiveMode(true)
        btnModeBrowser.alpha = 0.55f
    }

    private fun showBrowserMode() {
        controlPanel.visibility = View.GONE
        browserPanel.visibility = View.VISIBLE
        browserTopBar.visibility = if (isTopBarHiddenInBrowser) View.GONE else View.VISIBLE

        overlayButtonsRequested = true
        overlayMenuToggleRequested = isTopBarHiddenInBrowser
        refreshSessionControls()

        setImmersiveMode(true)
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
            val pm = getSystemService(PowerManager::class.java) ?: return
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
        cancelScanTimeout()
        stopBleScan()
        mainHandler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
        if (bound) {
            try { unbindService(connection) } catch (_: Exception) {}
            bound = false
        }
        service = null
        try {
            webView.stopLoading()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        } catch (_: Exception) {}
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            customView != null -> chromeClient.onHideCustomView()
            browserPanel.visibility == View.VISIBLE && webView.canGoBack() -> webView.goBack()
            browserPanel.visibility == View.VISIBLE -> showControlMode()
            else -> super.onBackPressed()
        }
    }
}
