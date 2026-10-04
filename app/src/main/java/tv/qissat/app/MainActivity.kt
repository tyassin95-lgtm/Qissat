package tv.qissat.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import tv.qissat.app.adblock.Rules
import tv.qissat.app.adblock.UrlPolicy
import tv.qissat.app.input.CursorController
import tv.qissat.app.input.CursorView
import tv.qissat.app.input.DeviceType
import tv.qissat.app.player.PlayRequest
import tv.qissat.app.player.PlaybackStore
import tv.qissat.app.player.PlayerActivity
import tv.qissat.app.ui.MenuDialog
import tv.qissat.app.web.BrowserCallbacks
import tv.qissat.app.web.BrowserFactory
import tv.qissat.app.web.LoadError
import tv.qissat.app.web.MediaSniffer
import tv.qissat.app.web.PlayBeacon
import tv.qissat.app.web.PopupSink
import tv.qissat.app.web.QissatChromeClient
import tv.qissat.app.web.QissatWebViewClient
import tv.qissat.app.web.Shield

/**
 * Hosts the single WebView that shows qisat.tv. Everything else in this class exists to make
 * that WebView comfortable on a phone, an Android TV and a Fire TV remote: cursor, back
 * handling, fullscreen video, error recovery and the hand-off to the native player.
 */
@OptIn(markerClass = [UnstableApi::class])
class MainActivity : ComponentActivity(), BrowserCallbacks {

    private lateinit var root: FrameLayout
    private lateinit var webContainer: FrameLayout
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var progress: ProgressBar
    private lateinit var splash: View
    private lateinit var errorPanel: View
    private lateinit var errorTitle: TextView
    private lateinit var errorMessage: TextView
    private lateinit var cursorView: CursorView
    private lateinit var cursor: CursorController

    private lateinit var policy: UrlPolicy
    private lateinit var shield: Shield
    private lateinit var popupSink: PopupSink
    private lateinit var store: PlaybackStore
    private lateinit var prefs: SharedPreferences
    private val sniffer = MediaSniffer()
    private val menu by lazy { MenuDialog(this) }
    private val handler = Handler(Looper.getMainLooper())

    private var webView: WebView? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private var isTv = false
    private var nativePlayerEnabled = true
    private var nativeSuppressedForPage = false
    private var playerActive = false
    private var lastPlayLaunch = 0L
    private var loadFailed = false
    private var lastBackPress = 0L
    private var pendingUrl: String? = null
    private var currentPageKey: String? = null

    private var connectivity: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var lastProgress = 0
    private var lastError: LoadError? = null

    /**
     * A stalled third-party resource can keep the load event from firing on a page that is
     * already perfectly usable; only treat the load as failed if little of it arrived.
     */
    private val timeoutRunnable = Runnable {
        if (lastProgress >= USABLE_PROGRESS) {
            progress.visibility = View.GONE
            hideSplash()
        } else {
            onMainFrameError(webView?.url, LoadError.TIMEOUT)
        }
    }

    private val playerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        onPlayerResult(it.resultCode)
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isTv = DeviceType.isTv(this)
        prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        nativePlayerEnabled = prefs.getBoolean(PREF_NATIVE, true)

        requestedOrientation = if (isTv) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        bindViews()
        applyInsets()

        policy = UrlPolicy(Rules.load(this))
        shield = Shield(this)
        store = PlaybackStore(this)
        popupSink = PopupSink(this, policy) { url -> webView?.loadUrl(url) }

        cursor = CursorController(cursorView, { webView }, { customView ?: webView })
        cursor.userEnabled = prefs.getBoolean(PREF_CURSOR, isTv)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = onBack()
        })

        createWebView()
        val wv = webView!!
        if (savedInstanceState != null && wv.restoreState(savedInstanceState) != null) {
            // Restored: the client callbacks will hide the splash screen.
        } else {
            wv.loadUrl(startUrl(intent))
        }

        registerNetworkCallback()
        if (isTv && !prefs.getBoolean(PREF_HINT_SHOWN, false)) {
            prefs.edit().putBoolean(PREF_HINT_SHOWN, true).apply()
            handler.postDelayed({ toast(R.string.hint_tv, long = true) }, 2500)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val url = startUrl(intent)
        if (intent.data != null) webView?.loadUrl(url)
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
        webView?.resumeTimers()
        if (customView != null) hideSystemBars()
        webView?.requestFocus()
    }

    override fun onPause() {
        webView?.onPause()
        webView?.pauseTimers()
        menu.dismiss()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView?.saveState(outState)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The activity handles rotation itself so the page and any playing video keep going.
        cursorView.post { cursor.centerIfNeeded() }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        unregisterNetworkCallback()
        cursor.onDestroy()
        popupSink.release()
        menu.dismiss()
        customViewCallback?.onCustomViewHidden()
        webView?.let(BrowserFactory::destroy)
        webView = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ setup helpers

    private fun bindViews() {
        root = findViewById(R.id.root)
        webContainer = findViewById(R.id.web_container)
        fullscreenContainer = findViewById(R.id.fullscreen_container)
        progress = findViewById(R.id.progress)
        splash = findViewById(R.id.splash)
        errorPanel = findViewById(R.id.error_panel)
        errorTitle = findViewById(R.id.error_title)
        errorMessage = findViewById(R.id.error_message)
        cursorView = findViewById(R.id.cursor)
        findViewById<View>(R.id.btn_retry).setOnClickListener { retry() }
        findViewById<View>(R.id.btn_home).setOnClickListener { goHome() }
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.updatePadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createWebView() {
        val wv = BrowserFactory.create(this)
        shield.install(wv)
        wv.webViewClient = QissatWebViewClient(policy, shield, sniffer, this)
        wv.webChromeClient = QissatChromeClient(this, policy, popupSink, this)
        // Downloads and long-press menus would open external handlers: disabled.
        wv.setDownloadListener { _, _, _, _, _ -> }
        wv.setOnLongClickListener { true }
        wv.isLongClickable = false
        webContainer.addView(
            wv,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        webView = wv
    }

    private fun startUrl(intent: Intent?): String {
        val data = intent?.data?.toString()
        if (data != null && policy.navigation(data) != UrlPolicy.Nav.BLOCK) {
            return if (data.startsWith("http://")) "https://" + data.removePrefix("http://") else data
        }
        return Config.HOME_URL
    }

    // ------------------------------------------------------------------ BrowserCallbacks

    override fun onPageStarted(url: String) {
        loadFailed = false
        lastProgress = 0
        val key = pageKey(url)
        if (key != currentPageKey) {
            currentPageKey = key
            nativeSuppressedForPage = false
        }
        progress.progress = 0
        progress.visibility = View.VISIBLE
        handler.removeCallbacks(timeoutRunnable)
        handler.postDelayed(timeoutRunnable, Config.PAGE_TIMEOUT_MS)
    }

    override fun onProgress(percent: Int) {
        lastProgress = percent
        progress.progress = percent
        if (percent >= 70) hideSplash()
        if (percent >= 100) progress.visibility = View.GONE
    }

    override fun onPageFinished(url: String) {
        handler.removeCallbacks(timeoutRunnable)
        progress.visibility = View.GONE
        // A timeout overlay is dismissed if the page does eventually load; real failures stay
        // until the user retries.
        if (!loadFailed || lastError == LoadError.TIMEOUT) {
            loadFailed = false
            hideError()
            hideSplash()
        }
    }

    override fun onMainFrameError(url: String?, error: LoadError) {
        handler.removeCallbacks(timeoutRunnable)
        if (loadFailed && errorPanel.visibility == View.VISIBLE) return
        loadFailed = true
        lastError = error
        pendingUrl = url?.takeIf { policy.navigation(it) != UrlPolicy.Nav.BLOCK }
        progress.visibility = View.GONE
        val (title, message) = when (error) {
            LoadError.OFFLINE -> R.string.error_offline_title to R.string.error_offline_message
            LoadError.TIMEOUT -> R.string.error_timeout_title to R.string.error_timeout_message
            LoadError.SSL -> R.string.error_ssl_title to R.string.error_ssl_message
            LoadError.SERVER -> R.string.error_server_title to R.string.error_server_message
            LoadError.OTHER -> R.string.error_other_title to R.string.error_other_message
        }
        errorTitle.setText(title)
        errorMessage.setText(message)
        showError()
    }

    override fun onExternalLinkBlocked() = toast(R.string.toast_external_blocked)

    override fun onEscapeBlocked() {
        // A script tried to drag the page away from the site. The navigation was cancelled;
        // nothing else to do.
    }

    override fun onPlayBeacon(beacon: PlayBeacon) {
        runOnUiThread { handlePlayBeacon(beacon) }
    }

    override fun onRenderProcessGone(didCrash: Boolean) {
        runOnUiThread {
            val url = webView?.url?.takeIf { policy.navigation(it) == UrlPolicy.Nav.ALLOW } ?: Config.HOME_URL
            hideCustomViewInternal()
            webView?.let(BrowserFactory::destroy)
            webView = null
            createWebView()
            webView?.loadUrl(url)
        }
    }

    override fun openInMainView(url: String) {
        webView?.loadUrl(url)
    }

    override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            callback.onCustomViewHidden()
            return
        }
        customView = view
        customViewCallback = callback
        fullscreenContainer.addView(
            view,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        fullscreenContainer.visibility = View.VISIBLE
        progress.visibility = View.GONE
        hideSystemBars()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!isTv) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    override fun onHideCustomView() = hideCustomViewInternal()

    private fun hideCustomViewInternal() {
        val view = customView ?: return
        customView = null
        fullscreenContainer.removeView(view)
        fullscreenContainer.visibility = View.GONE
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showSystemBars()
        requestedOrientation = if (isTv) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        webView?.requestFocus()
    }

    // ------------------------------------------------------------------ native player hand-off

    private fun handlePlayBeacon(beacon: PlayBeacon) {
        val wv = webView ?: return
        val now = System.currentTimeMillis()
        if (!nativePlayerEnabled || nativeSuppressedForPage || playerActive) return
        if (now - lastPlayLaunch < 2_000) return
        if (!beacon.looksLikeMainVideo) return
        val candidate = sniffer.pick(beacon.frameUrl, beacon.currentSrc) ?: return

        lastPlayLaunch = now
        shield.command(wv, "pause")
        val pageUrl = wv.url
        val webPositionMs = (beacon.positionSec * 1000).toLong()
        val startMs = if (webPositionMs > 5_000) webPositionMs else store.position(pageUrl)
        val request = PlayRequest(
            url = candidate.url,
            kind = candidate.kind,
            referer = candidate.referer ?: beacon.frameUrl,
            userAgent = wv.settings.userAgentString,
            cookie = CookieManager.getInstance().getCookie(candidate.url),
            title = wv.title.orEmpty().trim(),
            pageUrl = pageUrl,
            startMs = startMs,
        )
        hideCustomViewInternal()
        playerActive = true
        playerLauncher.launch(request.toIntent(this))
    }

    private fun onPlayerResult(resultCode: Int) {
        playerActive = false
        if (resultCode == PlayerActivity.RESULT_FALLBACK) {
            nativeSuppressedForPage = true
            toast(R.string.toast_web_player)
            webView?.let { shield.command(it, "play") }
        }
    }

    // ------------------------------------------------------------------ error & splash UI

    private fun showError() {
        hideSplash()
        errorPanel.visibility = View.VISIBLE
        cursor.suspended = true
        findViewById<View>(R.id.btn_retry).requestFocus()
    }

    private fun hideError() {
        if (errorPanel.visibility == View.VISIBLE) {
            errorPanel.visibility = View.GONE
            cursor.suspended = false
            webView?.requestFocus()
        }
    }

    private fun hideSplash() {
        if (splash.visibility == View.VISIBLE) {
            splash.animate().alpha(0f).setDuration(180).withEndAction {
                splash.visibility = View.GONE
                splash.alpha = 1f
            }.start()
        }
    }

    private fun retry() {
        val wv = webView ?: return
        loadFailed = false
        errorPanel.visibility = View.GONE
        cursor.suspended = false
        progress.visibility = View.VISIBLE
        val target = pendingUrl ?: Config.HOME_URL
        pendingUrl = null
        wv.stopLoading()
        wv.loadUrl(target)
        wv.requestFocus()
    }

    private fun goHome() {
        pendingUrl = null
        loadFailed = false
        errorPanel.visibility = View.GONE
        cursor.suspended = false
        webView?.loadUrl(Config.HOME_URL)
    }

    // ------------------------------------------------------------------ network recovery

    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        connectivity = cm
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread { if (errorPanel.visibility == View.VISIBLE) retry() }
            }
        }
        try {
            cm.registerNetworkCallback(
                NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                callback,
            )
            networkCallback = callback
        } catch (_: Exception) {
            // Missing permission or restricted profile: recovery just stays manual.
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            networkCallback?.let { connectivity?.unregisterNetworkCallback(it) }
        } catch (_: Exception) {
        }
        networkCallback = null
    }

    // ------------------------------------------------------------------ keys & back

    @SuppressLint("RestrictedApi") // false positive: super.dispatchKeyEvent is public Activity API
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        // Overlays that use normal focus navigation get the keys untouched.
        if (!menu.isShowing && errorPanel.visibility != View.VISIBLE) {
            if (isMenuKey(code)) {
                if (event.action == KeyEvent.ACTION_UP) showMenu()
                return true
            }
            if (handleMediaKey(event)) return true
            if (cursor.handleKey(event)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isMenuKey(code: Int) = code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_INFO ||
        code == KeyEvent.KEYCODE_SETTINGS || code == KeyEvent.KEYCODE_GUIDE ||
        code == KeyEvent.KEYCODE_BUTTON_START

    /** Transport keys control the web player's <video>, however deeply it is framed. */
    private fun handleMediaKey(event: KeyEvent): Boolean {
        val wv = webView ?: return false
        val down = event.action == KeyEvent.ACTION_DOWN
        val first = down && event.repeatCount == 0
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> if (first) shield.command(wv, "toggle")
            KeyEvent.KEYCODE_MEDIA_PLAY -> if (first) shield.command(wv, "play")
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> if (first) shield.command(wv, "pause")
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> if (down) shield.command(wv, "seek", SEEK_SHORT_S)
            KeyEvent.KEYCODE_MEDIA_REWIND -> if (down) shield.command(wv, "seek", -SEEK_SHORT_S)
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD, KeyEvent.KEYCODE_MEDIA_STEP_FORWARD ->
                if (first) shield.command(wv, "seek", SEEK_LONG_S)
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD, KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD ->
                if (first) shield.command(wv, "seek", -SEEK_LONG_S)
            else -> return false
        }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && !menu.isShowing) event.startTracking()
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            showMenu()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    private fun onBack() {
        val wv = webView
        when {
            customView != null -> hideCustomViewInternal()
            errorPanel.visibility == View.VISIBLE -> {
                if (wv?.canGoBack() == true) {
                    hideErrorAndGoBack(wv)
                } else {
                    confirmExit()
                }
            }
            wv != null && wv.canGoBack() -> wv.goBack()
            wv != null && !isHome(wv.url) -> wv.loadUrl(Config.HOME_URL)
            else -> confirmExit()
        }
    }

    private fun hideErrorAndGoBack(wv: WebView) {
        loadFailed = false
        errorPanel.visibility = View.GONE
        cursor.suspended = false
        wv.goBack()
    }

    private fun isHome(url: String?): Boolean {
        if (url == null) return true
        val trimmed = url.substringBefore('#').substringBefore('?').trimEnd('/')
        return trimmed.equals(Config.HOME_URL.trimEnd('/'), ignoreCase = true) ||
            trimmed.equals("https://qisat.tv", ignoreCase = true)
    }

    private fun confirmExit() {
        val now = System.currentTimeMillis()
        if (now - lastBackPress < Config.EXIT_CONFIRM_MS) {
            finish()
        } else {
            lastBackPress = now
            toast(R.string.toast_back_exit)
        }
    }

    // ------------------------------------------------------------------ menu

    private fun showMenu() {
        val items = listOf(
            MenuDialog.Item(getString(R.string.menu_home)) { goHome() },
            MenuDialog.Item(getString(R.string.menu_reload)) { webView?.reload() },
            MenuDialog.Item(getString(if (cursor.userEnabled) R.string.menu_cursor_on else R.string.menu_cursor_off)) {
                toggleCursor()
            },
            MenuDialog.Item(getString(if (nativePlayerEnabled) R.string.menu_native_on else R.string.menu_native_off)) {
                nativePlayerEnabled = !nativePlayerEnabled
                prefs.edit().putBoolean(PREF_NATIVE, nativePlayerEnabled).apply()
            },
            MenuDialog.Item(getString(R.string.menu_mute)) { webView?.let { shield.command(it, "mute") } },
            MenuDialog.Item(getString(R.string.menu_exit)) { finishAndRemoveTask() },
        )
        menu.show(items)
    }

    private fun toggleCursor() {
        val on = !cursor.userEnabled
        cursor.userEnabled = on
        prefs.edit().putBoolean(PREF_CURSOR, on).apply()
        toast(if (on) R.string.toast_cursor_on else R.string.toast_cursor_off)
        if (on) cursorView.post { cursor.centerIfNeeded() }
        webView?.requestFocus()
    }

    // ------------------------------------------------------------------ misc

    private fun hideSystemBars() {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun showSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
    }

    private fun toast(resId: Int, long: Boolean = false) {
        Toast.makeText(this, resId, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    private fun pageKey(url: String): String = url.substringBefore('#').substringBefore('?')

    private companion object {
        const val PREF_CURSOR = "cursor"
        const val PREF_NATIVE = "native_player"
        const val PREF_HINT_SHOWN = "hint_shown"
        const val USABLE_PROGRESS = 60
        const val SEEK_SHORT_S = 10
        const val SEEK_LONG_S = 60
    }
}
