package com.hismith.session

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.hismith.session.core.CommandArbiter
import com.hismith.session.core.DoseKind
import com.hismith.session.core.QuickAction
import com.hismith.session.core.Source
import com.hismith.session.remote.RemoteHost
import com.hismith.session.ui.BrowserExtras
import com.hismith.session.ui.ChallengeTab
import com.hismith.session.ui.DevicePicker
import com.hismith.session.ui.LubeTab
import com.hismith.session.ui.RecipeTab
import com.hismith.session.ui.RemoteTab
import com.hismith.session.ui.SessionTab
import com.hismith.session.ui.Ui
import kotlin.math.hypot

@SuppressLint("MissingPermission")
class MainActivity : Activity() {

    companion object {
        private const val REQ_PERMS = 1001
        private const val HOME_URL = "https://www.pornhub.com"
        const val EXTRA_TAB = "tab"
        const val TAB_SESSION = 0
        const val TAB_LUBE = 1
        const val TAB_CHALLENGE = 2
        const val TAB_REMOTE = 3
        const val TAB_RECIPE = 4

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
            "SpankBang" to "https://spankbang.com",
        )
    }

    lateinit var settings: AppSettings
        private set
    var service: SessionService? = null
        private set
    var state: UiState? = null
        private set
    lateinit var devicePicker: DevicePicker
        private set

    private var bound = false
    private var serviceStarted = false
    private var currentTab = TAB_SESSION
    private var resumed = false

    private lateinit var sessionTab: SessionTab
    private lateinit var lubeTab: LubeTab
    private lateinit var challengeTab: ChallengeTab
    private lateinit var remoteTab: RemoteTab
    private lateinit var recipeTab: RecipeTab

    private lateinit var rootContainer: FrameLayout
    private lateinit var mainColumn: View
    private lateinit var pages: List<View>
    private lateinit var tabs: List<TextView>
    private lateinit var headerStatus: TextView
    private lateinit var btnEmergency: Button
    private lateinit var stoppedBanner: TextView

    private lateinit var browserPanel: View
    private lateinit var browserTopBar: View
    private lateinit var webView: WebView
    private lateinit var urlBar: EditText
    private lateinit var browserSpeed: TextView
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var btnOverlayStop: Button
    private lateinit var btnOverlayMega: Button
    private lateinit var btnOverlayBarToggle: Button
    private lateinit var miniPanel: View
    private lateinit var miniSpeed: TextView
    private lateinit var miniPause: Button
    private lateinit var browserExtras: BrowserExtras

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var isTopBarHiddenInBrowser = false
    private var overlayButtonsRequested = false
    private var overlayMenuToggleRequested = false
    private var lastUserTouchMs = 0L
    private var lastBlockToastMs = 0L
    private var approvalDialog: AlertDialog? = null

    private val stateListener: (UiState) -> Unit = { st -> onState(st) }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as SessionService.LocalBinder).service()
            service = s
            bound = true
            s.startForegroundProtected()
            s.settingsChanged()
            if (hasBtPermissions()) s.reconnectSaved()
            s.addListener(stateListener)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            service = null
        }
    }

    /**
     * Chrome client kept as a field so the app can leave fullscreen video itself.
     *
     * `onShowCustomView` must NOT hide the WebView and must NOT force an orientation: both make
     * Chromium tear down the compositor surface, which cancels the `<video>` fullscreen request.
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
                fullscreenContainer.addView(it, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            }
            setImmersiveMode()
            overlayButtonsRequested = true
            overlayMenuToggleRequested = false
            refreshOverlays()
            rootContainer.post { restoreOverlayPositions() }
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
            setImmersiveMode()
            refreshOverlays()
            rootContainer.post { restoreOverlayPositions() }
        }
    }

    // ================================================================== lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setImmersiveMode()

        bindViews()
        devicePicker = DevicePicker(this)
        sessionTab = SessionTab(this)
        lubeTab = LubeTab(this)
        challengeTab = ChallengeTab(this)
        remoteTab = RemoteTab(this)
        recipeTab = RecipeTab(this)
        setupTabs()
        setupHeader()
        setupWebView()
        browserExtras = BrowserExtras(
            this, webView, findViewById(R.id.quickLinksRow), findViewById(R.id.btnFavorite),
            findViewById(R.id.btnBrowserMenu),
        ) { loadUrlFromUser(it) }
        setupBrowserClicks()
        setupMiniPanel()
        setupDraggableOverlays()
        applyCutoutInsets()

        showTab(intent?.getIntExtra(EXTRA_TAB, TAB_SESSION) ?: TAB_SESSION)
        showControlMode()

        ensureServiceStarted()
        if (hasBtPermissions()) askIgnoreBatteryOptimizations() else requestBtPermissions()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasExtra(EXTRA_TAB)) {
            showControlMode()
            showTab(intent.getIntExtra(EXTRA_TAB, TAB_SESSION))
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        setImmersiveMode()
        applyDiscreet()
        browserExtras.startVideoPoll()
        challengeTab.refresh()
        state?.let { onState(it) }
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        sessionTab.saveTimes()
        browserExtras.stopVideoPoll()
        if (!settings.privateBrowsing) webView.url?.let { settings.prefs.edit().putString("last_url", it).apply() }
    }

    override fun onDestroy() {
        devicePicker.release()
        approvalDialog?.dismiss()
        service?.removeListener(stateListener)
        if (bound) {
            try { unbindService(connection) } catch (_: Exception) {}
            bound = false
        }
        service = null
        if (settings.privateBrowsing) browserExtras.clearBrowsingData()
        try {
            webView.stopLoading()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        } catch (_: Exception) {}
        super.onDestroy()
    }

    /** Start + bind the foreground service (it falls back to a plain FGS until BT is granted). */
    private fun ensureServiceStarted() {
        if (serviceStarted) return
        serviceStarted = true
        val svc = Intent(this, SessionService::class.java)
        startForegroundService(svc)
        bindService(svc, connection, Context.BIND_AUTO_CREATE)
    }

    // ================================================================== permissions

    fun hasBtPermissions(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
    } else {
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    fun requestBtPermissions() {
        val perms = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += Manifest.permission.BLUETOOTH_CONNECT
            perms += Manifest.permission.BLUETOOTH_SCAN
        } else {
            perms += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(perms.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        if (hasBtPermissions()) {
            service?.startForegroundProtected()
            service?.reconnectSaved()
            askIgnoreBatteryOptimizations()
        } else {
            Ui.toast(this, "Utan Bluetooth-behörighet kan appen inte styra maskiner")
        }
    }

    private fun askIgnoreBatteryOptimizations() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply { data = Uri.parse("package:$packageName") })
            } catch (_: Exception) {}
        }
    }

    // ================================================================== views / tabs

    private fun bindViews() {
        rootContainer = findViewById(R.id.rootContainer)
        mainColumn = findViewById(R.id.mainColumn)
        pages = listOf(
            findViewById(R.id.pageSession), findViewById(R.id.pageLube), findViewById(R.id.pageChallenge),
            findViewById(R.id.pageRemote), findViewById(R.id.pageRecipe),
        )
        tabs = listOf(
            findViewById(R.id.tabSession), findViewById(R.id.tabLube), findViewById(R.id.tabChallenge),
            findViewById(R.id.tabRemote), findViewById(R.id.tabRecipe),
        )
        headerStatus = findViewById(R.id.headerStatus)
        btnEmergency = findViewById(R.id.btnEmergency)
        stoppedBanner = findViewById(R.id.stoppedBanner)
        browserPanel = findViewById(R.id.browserPanel)
        browserTopBar = findViewById(R.id.browserTopBar)
        webView = findViewById(R.id.webView)
        urlBar = findViewById(R.id.urlBar)
        browserSpeed = findViewById(R.id.browserSpeed)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)
        btnOverlayStop = findViewById(R.id.btnOverlayStop)
        btnOverlayMega = findViewById(R.id.btnOverlayMega)
        btnOverlayBarToggle = findViewById(R.id.btnOverlayBarToggle)
        miniPanel = findViewById(R.id.miniPanel)
        miniSpeed = findViewById(R.id.miniSpeed)
        miniPause = findViewById(R.id.miniPause)
    }

    /** "Diskret läge": hidden in recent apps and no screenshots. */
    fun applyDiscreet() {
        if (settings.discreet) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        try {
            getSystemService(ActivityManager::class.java)?.appTasks?.forEach { it.setExcludeFromRecents(settings.discreet) }
        } catch (_: Exception) {}
    }

    fun isBrowserVisible(): Boolean = ::browserPanel.isInitialized && browserPanel.visibility == View.VISIBLE

    fun refreshOverlaysFromExtras() = refreshOverlays()

    /** Floating mini control in the browser: speed, intensity/speed ±, quick actions, lube, pause. */
    private fun setupMiniPanel() {
        fun step(delta: Int) {
            val st = state ?: return
            when {
                st.session.running -> service?.setIntensity((st.session.intensity + delta).coerceIn(0, 100))
                st.control == CommandArbiter.Control.MANUAL && st.owner == Source.LOCAL ->
                    service?.manualSpeed((st.manualTarget + delta).coerceIn(0, 100))
            }
        }
        findViewById<Button>(R.id.miniMinus).setOnClickListener { step(-10) }
        findViewById<Button>(R.id.miniPlus).setOnClickListener { step(10) }
        findViewById<Button>(R.id.miniBoost).setOnClickListener { service?.quickAction(QuickAction.BOOST) }
        findViewById<Button>(R.id.miniEdge).setOnClickListener { service?.quickAction(QuickAction.EDGE) }
        findViewById<Button>(R.id.miniRest).setOnClickListener { service?.quickAction(QuickAction.REST) }
        findViewById<Button>(R.id.miniExtra).setOnClickListener {
            service?.dose(DoseKind.EXTRA, null)
            Toast.makeText(this, "EXTRA SPRUT", Toast.LENGTH_SHORT).show()
        }
        miniPause.setOnClickListener {
            val st = state ?: return@setOnClickListener
            if (st.session.paused) service?.resumeSession() else service?.pauseSession()
        }
    }

    private fun setupTabs() {
        tabs.forEachIndexed { i, t -> t.setOnClickListener { showTab(i) } }
    }

    fun showTab(index: Int) {
        currentTab = index.coerceIn(0, pages.size - 1)
        pages.forEachIndexed { i, p -> p.visibility = if (i == currentTab) View.VISIBLE else View.GONE }
        tabs.forEachIndexed { i, t -> t.setTextColor(getColor(if (i == currentTab) R.color.gold else R.color.dim)) }
        if (currentTab == TAB_CHALLENGE) challengeTab.refresh()
        state?.let { renderTab(it) }
    }

    private fun setupHeader() {
        btnEmergency.setOnClickListener { emergencyStop() }
        stoppedBanner.setOnClickListener { service?.clearStop() }
        findViewById<Button>(R.id.btnModeBrowser).setOnClickListener { showBrowserMode() }
    }

    private fun emergencyStop() {
        service?.emergencyStop(Source.LOCAL)
            ?: startService(Intent(this, SessionService::class.java).setAction(SessionService.ACTION_STOP))
        Toast.makeText(this, "STOPP", Toast.LENGTH_SHORT).show()
    }

    // ================================================================== state

    private fun onState(st: UiState) {
        state = st
        if (!::sessionTab.isInitialized) return
        val machines = st.devices.count { it.role.isMotion && it.connected }
        headerStatus.text = buildString {
            append("v2.1 · $machines maskin${if (machines == 1) "" else "er"}")
            append(if (st.lube.connected) " · lube ✓" else " · ingen lube")
            if (st.remote.status != RemoteHost.Status.OFF) append(" · länk: ${if (st.remote.controllerActive) "aktiv" else "på"}")
            append(" · ${st.mode.label}")
        }
        btnEmergency.visibility = if (st.stopRelevant) View.VISIBLE else View.GONE
        stoppedBanner.visibility = if (st.safety == CommandArbiter.Safety.STOPPED) View.VISIBLE else View.GONE
        if (st.safety == CommandArbiter.Safety.STOPPED) stoppedBanner.text = "STOPPAD (${st.safetyReason}) – tryck för att kvittera"
        browserSpeed.text = if (st.session.running || st.control != CommandArbiter.Control.NONE) "${st.speed} %" else ""
        miniSpeed.text = "${st.speed} %"
        miniPause.text = if (st.session.paused) "▶" else "⏸"
        refreshOverlays()
        renderTab(st)
        handleApproval(st)
    }

    private fun renderTab(st: UiState) {
        if (browserPanel.visibility == View.VISIBLE) return
        when (currentTab) {
            TAB_SESSION -> sessionTab.render(st)
            TAB_LUBE -> lubeTab.render(st)
            TAB_CHALLENGE -> challengeTab.render(st)
            TAB_REMOTE -> remoteTab.render(st)
        }
    }

    private fun handleApproval(st: UiState) {
        if (!st.remote.pendingApproval) {
            approvalDialog?.dismiss()
            approvalDialog = null
            return
        }
        if (approvalDialog != null || !resumed) return
        approvalDialog = Ui.dialog(this)
            .setTitle("Mottagare vill ansluta")
            .setMessage("Någon har öppnat din engångslänk och vill styra. Godkänn bara om du väntar dig det.\n\nSTOPP på telefonen fungerar alltid.")
            .setCancelable(false)
            .setPositiveButton("Godkänn") { _, _ -> service?.remoteApprove() }
            .setNegativeButton("Neka") { _, _ -> service?.remoteDeny() }
            .setOnDismissListener { approvalDialog = null }
            .show()
    }

    // ================================================================== browser

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun setupWebView() {
        // Debug builds only: chrome://inspect for checking which elements on a site are ads.
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) WebView.setWebContentsDebuggingEnabled(true)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(false)
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            userAgentString = userAgentString.replace("; wv", "").replace("Version/4.0", "")
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
        webView.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_UP) {
                lastUserTouchMs = SystemClock.uptimeMillis()
            }
            false
        }
        webView.setDownloadListener { _, _, _, mimeType, _ -> showBlockedToast("Nedladdning blockerad ($mimeType)") }
        webView.webViewClient = AdBlockWebViewClient(
            onPageStarted = { url ->
                runOnUiThread {
                    urlBar.setText(url ?: "")
                    if (::browserExtras.isInitialized) browserExtras.onPageStarted(url)
                }
            },
            onBlocked = { reason -> runOnUiThread { showBlockedToast("Blockerade: $reason") } },
            hasRecentUserTouch = { SystemClock.uptimeMillis() - lastUserTouchMs < USER_TOUCH_WINDOW_MS },
        )
        webView.webChromeClient = chromeClient
        webView.loadUrl(if (settings.privateBrowsing) HOME_URL else settings.prefs.getString("last_url", HOME_URL) ?: HOME_URL)
    }

    private fun setupBrowserClicks() {
        findViewById<Button>(R.id.btnModeControlFromBrowser).setOnClickListener { showControlMode() }
        findViewById<Button>(R.id.btnBrowserBack).setOnClickListener { if (webView.canGoBack()) webView.goBack() }
        findViewById<Button>(R.id.btnBrowserForward).setOnClickListener { if (webView.canGoForward()) webView.goForward() }
        findViewById<Button>(R.id.btnBrowserHome).setOnClickListener { loadUrlFromUser(HOME_URL) }
        findViewById<Button>(R.id.btnBrowserRefresh).setOnClickListener { webView.reload() }
        findViewById<Button>(R.id.btnGoUrl).setOnClickListener { navigateUrlBar() }
        urlBar.setOnEditorActionListener { _, _, _ -> navigateUrlBar(); true }
        findViewById<Button>(R.id.btnHideTopBar).setOnClickListener {
            isTopBarHiddenInBrowser = true
            browserTopBar.visibility = View.GONE
            overlayButtonsRequested = true
            overlayMenuToggleRequested = true
            refreshOverlays()
            setImmersiveMode()
        }
        btnOverlayStop.setOnClickListener { emergencyStop() }
        btnOverlayMega.setOnClickListener {
            service?.dose(DoseKind.SUPER, null)
            Toast.makeText(this, "SUPER SPRUT", Toast.LENGTH_SHORT).show()
        }
        btnOverlayBarToggle.setOnClickListener {
            if (customView != null) {
                chromeClient.onHideCustomView()
            } else {
                isTopBarHiddenInBrowser = false
                browserTopBar.visibility = View.VISIBLE
                overlayMenuToggleRequested = false
                refreshOverlays()
            }
        }
    }

    private fun showBlockedToast(message: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastBlockToastMs < 4000) return
        lastBlockToastMs = now
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun navigateUrlBar() {
        var url = urlBar.text.toString().trim()
        if (url.isEmpty()) return
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = if (url.contains(".") && !url.contains(" ")) "https://$url" else "https://duckduckgo.com/?q=${Uri.encode(url)}"
        }
        loadUrlFromUser(url)
    }

    private fun loadUrlFromUser(url: String) {
        urlBar.setText(url)
        urlBar.clearFocus()
        webView.loadUrl(url)
        if (!settings.privateBrowsing) settings.prefs.edit().putString("last_url", url).apply()
    }

    private fun showControlMode() {
        if (customView != null) chromeClient.onHideCustomView()
        mainColumn.visibility = View.VISIBLE
        browserPanel.visibility = View.GONE
        fullscreenContainer.visibility = View.GONE
        overlayButtonsRequested = false
        overlayMenuToggleRequested = false
        refreshOverlays()
        setImmersiveMode()
        state?.let { renderTab(it) }
    }

    private fun showBrowserMode() {
        mainColumn.visibility = View.GONE
        browserPanel.visibility = View.VISIBLE
        browserTopBar.visibility = if (isTopBarHiddenInBrowser) View.GONE else View.VISIBLE
        overlayButtonsRequested = true
        overlayMenuToggleRequested = isTopBarHiddenInBrowser
        refreshOverlays()
        setImmersiveMode()
    }

    // ================================================================== overlays

    /**
     * Single source of truth for the floating buttons: STOPP and SUPER SPRUT only while something
     * can actually move or pump, the "Meny" toggle whenever the address bar is hidden.
     */
    private fun refreshOverlays() {
        if (!::rootContainer.isInitialized) return
        val st = state
        val stopVis = if (st?.stopRelevant == true && overlayButtonsRequested) View.VISIBLE else View.GONE
        val megaVis = if (stopVis == View.VISIBLE && st?.lube?.connected == true && st.mode.lubeAllowed) View.VISIBLE else View.GONE
        val menuVis = if (overlayButtonsRequested && overlayMenuToggleRequested) View.VISIBLE else View.GONE
        val steering = st != null && (st.session.running || (st.control == CommandArbiter.Control.MANUAL && st.owner == Source.LOCAL))
        val miniVis = if (overlayButtonsRequested && settings.miniPanel && steering) View.VISIBLE else View.GONE
        if (btnOverlayStop.visibility == stopVis && btnOverlayMega.visibility == megaVis &&
            btnOverlayBarToggle.visibility == menuVis && miniPanel.visibility == miniVis
        ) return
        btnOverlayStop.visibility = stopVis
        btnOverlayMega.visibility = megaVis
        btnOverlayBarToggle.visibility = menuVis
        miniPanel.visibility = miniVis
        if (miniVis == View.VISIBLE) miniPanel.bringToFront()
        if (stopVis == View.VISIBLE) btnOverlayStop.bringToFront()
        if (megaVis == View.VISIBLE) btnOverlayMega.bringToFront()
        if (menuVis == View.VISIBLE) btnOverlayBarToggle.bringToFront()
        rootContainer.post { restoreOverlayPositions() }
    }

    /**
     * Draggable overlay buttons. No `bringToFront()` on ACTION_DOWN: reordering views mid-gesture
     * makes Chromium drop the touch stream and leave fullscreen video.
     */
    private fun setupDraggableOverlays() {
        makeDraggable(btnOverlayStop, "overlay_stop")
        makeDraggable(btnOverlayMega, "overlay_mega")
        makeDraggable(btnOverlayBarToggle, "overlay_menu")
        makeDraggable(findViewById(R.id.miniHandle), "overlay_mini", target = miniPanel)
        rootContainer.post { restoreOverlayPositions() }
    }

    /** Drag [target] by touching [view] (its handle); a tap on [view] is still a click. */
    @SuppressLint("ClickableViewAccessibility")
    private fun makeDraggable(view: View, key: String, target: View = view) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0f
        var startY = 0f
        var dragging = false
        view.setOnTouchListener { handle, e ->
            val v = target
            val parent = v.parent as? View ?: return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = v.x; startY = v.y; dragging = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && hypot(dx.toDouble(), dy.toDouble()) > touchSlop) dragging = true
                    if (dragging) {
                        v.x = (startX + dx).coerceIn(0f, (parent.width - v.width).coerceAtLeast(0).toFloat())
                        v.y = (startY + dy).coerceIn(0f, (parent.height - v.height).coerceAtLeast(0).toFloat())
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        handle.performClick()
                    } else {
                        val maxX = (parent.width - v.width).coerceAtLeast(1).toFloat()
                        val maxY = (parent.height - v.height).coerceAtLeast(1).toFloat()
                        settings.prefs.edit()
                            .putFloat("${key}_rx", (v.x / maxX).coerceIn(0f, 1f))
                            .putFloat("${key}_ry", (v.y / maxY).coerceIn(0f, 1f))
                            .apply()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> { dragging = false; true }
                else -> false
            }
        }
    }

    private fun restoreOverlayPositions() {
        place(btnOverlayStop, "overlay_stop")
        place(btnOverlayMega, "overlay_mega")
        place(btnOverlayBarToggle, "overlay_menu")
        place(miniPanel, "overlay_mini")
    }

    private fun place(view: View, key: String) {
        val p = rootContainer
        if (p.width <= 0 || p.height <= 0 || view.width <= 0 || view.height <= 0) return
        val maxX = (p.width - view.width).coerceAtLeast(0).toFloat()
        val maxY = (p.height - view.height).coerceAtLeast(0).toFloat()
        val prefs = settings.prefs
        if (prefs.contains("${key}_rx") && prefs.contains("${key}_ry")) {
            view.x = prefs.getFloat("${key}_rx", 0f).coerceIn(0f, 1f) * maxX
            view.y = prefs.getFloat("${key}_ry", 1f).coerceIn(0f, 1f) * maxY
        } else {
            view.x = view.x.coerceIn(0f, maxX)
            view.y = view.y.coerceIn(0f, maxY)
        }
    }

    // ================================================================== immersive

    /**
     * Hides status and navigation bars everywhere in the app (control panel, browser, video).
     * Bars can be swiped in temporarily.
     */
    @Suppress("DEPRECATION")
    private fun setImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        // No FLAG_FULLSCREEN: it stops the keyboard from resizing the window. The keyboard is
        // handled through window insets instead (see applyCutoutInsets).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                it.hide(WindowInsets.Type.systemBars())
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }

    /**
     * Keep the header and the browser bar out of the camera cut-out, and lift the content above
     * the keyboard (the app draws edge-to-edge, so the window itself is not resized on API 30+).
     */
    private fun applyCutoutInsets() {
        rootContainer.setOnApplyWindowInsetsListener { _, insets ->
            var top = 0
            var left = 0
            var right = 0
            var keyboard = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                insets.displayCutout?.let {
                    top = it.safeInsetTop
                    left = it.safeInsetLeft
                    right = it.safeInsetRight
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                keyboard = insets.getInsets(WindowInsets.Type.ime()).bottom
            }
            mainColumn.setPadding(left, top, right, keyboard)
            browserPanel.setPadding(0, 0, 0, keyboard)
            browserTopBar.setPadding(Ui.dp(this, 6) + left, Ui.dp(this, 4) + top, Ui.dp(this, 6) + right, Ui.dp(this, 2))
            insets
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) setImmersiveMode()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        setImmersiveMode()
        rootContainer.postDelayed({ restoreOverlayPositions() }, 100)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            customView != null -> chromeClient.onHideCustomView()
            browserPanel.visibility == View.VISIBLE && webView.canGoBack() -> webView.goBack()
            browserPanel.visibility == View.VISIBLE -> showControlMode()
            currentTab != TAB_SESSION -> showTab(TAB_SESSION)
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }
}
