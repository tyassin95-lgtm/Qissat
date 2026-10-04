package tv.qissat.app.player

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import tv.qissat.app.R
import tv.qissat.app.adblock.HostUtil
import tv.qissat.app.input.DeviceType
import tv.qissat.app.web.MediaSniffer
import kotlin.math.abs

/**
 * Full-screen native player (Media3 / ExoPlayer) for the stream the website's own player was
 * about to play. Comfortable on a TV remote and on touch screens. If the stream cannot be
 * played natively it hands control back to the web player ([RESULT_FALLBACK]).
 */
@OptIn(markerClass = [UnstableApi::class])
class PlayerActivity : ComponentActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var seekHint: TextView
    private lateinit var titleView: TextView
    private lateinit var errorPanel: View
    private lateinit var errorText: TextView
    private lateinit var muteButton: ImageButton

    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private lateinit var request: PlayRequest
    private lateinit var store: PlaybackStore

    private val handler = Handler(Looper.getMainLooper())
    private var everReady = false
    private var retries = 0
    private var lastSeekAt = 0L
    private var resizeIndex = 0
    private var isTv = false
    private var finishing = false

    private val hideSeekHint = Runnable { seekHint.visibility = View.GONE }
    private val saveTick = object : Runnable {
        override fun run() {
            savePosition()
            handler.postDelayed(this, SAVE_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val req = PlayRequest.from(intent)
        if (req == null) {
            finish()
            return
        }
        request = req
        store = PlaybackStore(this)
        isTv = DeviceType.isTv(this)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_player)

        playerView = findViewById(R.id.player_view)
        seekHint = findViewById(R.id.seek_hint)
        titleView = findViewById(R.id.title)
        errorPanel = findViewById(R.id.error_panel)
        errorText = findViewById(R.id.error_text)
        titleView.text = request.title

        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        playerView.controllerShowTimeoutMs = CONTROLLER_TIMEOUT_MS
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            titleView.visibility = if (visibility == View.VISIBLE) View.VISIBLE else View.GONE
        })
        addExtraButtons()

        findViewById<View>(R.id.btn_retry).setOnClickListener { retryNow() }
        findViewById<View>(R.id.btn_web).setOnClickListener { finishWithFallback() }
        findViewById<View>(R.id.btn_close).setOnClickListener { closePlayer() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isTv && playerView.isControllerFullyVisible && errorPanel.visibility != View.VISIBLE) {
                    playerView.hideController()
                } else {
                    closePlayer()
                }
            }
        })

        hideSystemBars()
        startPlayback(request.startMs)
    }

    // ------------------------------------------------------------------ player setup

    private fun startPlayback(startMs: Long) {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(request.userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setDefaultRequestProperties(requestHeaders())

        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val lowMemory = am.isLowRamDevice || am.memoryClass <= 128
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                if (lowMemory) 12_000 else 20_000,
                if (lowMemory) 30_000 else 50_000,
                1_500,
                3_000,
            )
            .setTargetBufferBytes(if (lowMemory) 16 * 1024 * 1024 else C.LENGTH_UNSET)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()

        val exo = ExoPlayer.Builder(this, DefaultRenderersFactory(this).setEnableDecoderFallback(true))
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, httpFactory)))
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(SEEK_STEP_MS)
            .setSeekForwardIncrementMs(SEEK_STEP_MS)
            .build()

        exo.addListener(playerListener)
        playerView.player = exo
        player = exo
        // Unique id: a quick Back-then-Play can start a new player before the old one is destroyed.
        session = MediaSession.Builder(this, exo).setId("qissat-" + System.nanoTime()).build()

        val mime = when (request.kind) {
            MediaSniffer.Kind.HLS -> MimeTypes.APPLICATION_M3U8
            MediaSniffer.Kind.DASH -> MimeTypes.APPLICATION_MPD
            MediaSniffer.Kind.PROGRESSIVE -> null
        }
        val item = MediaItem.Builder()
            .setUri(request.url)
            .setMimeType(mime)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(request.title).build())
            .build()
        exo.setMediaItem(item, startMs.coerceAtLeast(0L))
        exo.playWhenReady = true
        exo.prepare()
        handler.postDelayed(saveTick, SAVE_INTERVAL_MS)
    }

    private fun requestHeaders(): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        val referer = request.referer
        if (!referer.isNullOrEmpty()) {
            headers["Referer"] = referer
            val scheme = HostUtil.schemeOf(referer)
            val host = HostUtil.hostOf(referer)
            if (scheme != null && host != null) headers["Origin"] = "$scheme://$host"
        }
        if (!request.cookie.isNullOrEmpty()) headers["Cookie"] = request.cookie!!
        return headers
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_IDLE, Player.STATE_BUFFERING -> Unit
                Player.STATE_READY -> {
                    everReady = true
                    retries = 0
                    errorPanel.visibility = View.GONE
                }
                Player.STATE_ENDED -> {
                    store.save(request.pageUrl, 0, 0)
                    finishWith(RESULT_OK)
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            handlePlayerError(error)
        }
    }

    // ------------------------------------------------------------------ error recovery

    private fun handlePlayerError(error: PlaybackException) {
        val exo = player ?: return
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            exo.seekToDefaultPosition()
            exo.prepare()
            return
        }
        if (!everReady) {
            // Never started: the stream is not playable natively (blocked, unsupported, expired).
            if (retries < 1) {
                retries++
                exo.prepare()
            } else {
                finishWithFallback()
            }
            return
        }
        if (retries < MAX_RETRIES) {
            retries++
            val delay = 1_500L * retries
            handler.postDelayed({ if (!finishing) player?.prepare() }, delay)
        } else {
            errorText.text = getString(R.string.player_error)
            errorPanel.visibility = View.VISIBLE
            findViewById<View>(R.id.btn_retry).requestFocus()
        }
    }

    private fun retryNow() {
        retries = 0
        errorPanel.visibility = View.GONE
        player?.prepare()
        player?.play()
    }

    // ------------------------------------------------------------------ extra controls

    private fun addExtraButtons() {
        val container = playerView.findViewById<LinearLayout?>(androidx.media3.ui.R.id.exo_basic_controls) ?: return
        muteButton = makeButton(R.drawable.ic_volume_up, R.string.player_mute) { toggleMute() }
        val aspect = makeButton(R.drawable.ic_aspect, R.string.player_aspect) { cycleResize() }
        container.addView(muteButton, 0)
        container.addView(aspect, 1)
    }

    private fun makeButton(icon: Int, description: Int, onClick: () -> Unit): ImageButton {
        val button = ImageButton(this, null, 0, R.style.Widget_Qissat_PlayerButton)
        button.setImageResource(icon)
        button.contentDescription = getString(description)
        button.setOnClickListener { onClick() }
        return button
    }

    private fun toggleMute() {
        val exo = player ?: return
        val muted = exo.volume > 0f
        exo.volume = if (muted) 0f else 1f
        muteButton.setImageResource(if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up)
    }

    private fun cycleResize() {
        resizeIndex = (resizeIndex + 1) % RESIZE_MODES.size
        playerView.resizeMode = RESIZE_MODES[resizeIndex]
        showHint(getString(RESIZE_LABELS[resizeIndex]))
    }

    // ------------------------------------------------------------------ remote / keyboard

    @SuppressLint("RestrictedApi") // false positive: super.dispatchKeyEvent is public Activity API
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val exo = player
        if (exo != null && errorPanel.visibility != View.VISIBLE && !playerView.isControllerFullyVisible) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        val now = System.currentTimeMillis()
                        if (event.repeatCount == 0 || now - lastSeekAt > SEEK_REPEAT_MS) {
                            lastSeekAt = now
                            val dir = if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                            seekBy(dir * SEEK_STEP_MS)
                        }
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_BUTTON_A -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                        if (exo.isPlaying) exo.pause() else exo.play()
                        playerView.showController()
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (event.action == KeyEvent.ACTION_DOWN) playerView.showController()
                    return true
                }
            }
        }
        if (event.keyCode == KeyEvent.KEYCODE_M && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            toggleMute()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun seekBy(deltaMs: Long) {
        val exo = player ?: return
        val duration = exo.duration
        var target = exo.currentPosition + deltaMs
        target = if (duration != C.TIME_UNSET) target.coerceIn(0, duration) else target.coerceAtLeast(0)
        exo.seekTo(target)
        showHint((if (deltaMs > 0) "+" else "-") + abs(deltaMs / 1000) + " s")
    }

    private fun showHint(text: String) {
        seekHint.text = text
        seekHint.visibility = View.VISIBLE
        handler.removeCallbacks(hideSeekHint)
        handler.postDelayed(hideSeekHint, 900)
    }

    // ------------------------------------------------------------------ lifecycle & results

    private fun savePosition() {
        val exo = player ?: return
        if (!everReady) return
        store.save(request.pageUrl, exo.currentPosition, exo.duration.takeIf { it != C.TIME_UNSET } ?: 0L)
    }

    private fun closePlayer() = finishWith(RESULT_CANCELED)

    private fun finishWithFallback() = finishWith(RESULT_FALLBACK)

    private fun finishWith(code: Int) {
        if (finishing) return
        finishing = true
        savePosition()
        setResult(code, Intent())
        finish()
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
        savePosition()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        session?.release()
        session = null
        playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun hideSystemBars() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    companion object {
        /** Result code: playback failed, the web player should take over. */
        const val RESULT_FALLBACK = RESULT_FIRST_USER + 1

        private const val SEEK_STEP_MS = 10_000L
        private const val SEEK_REPEAT_MS = 220L
        private const val SAVE_INTERVAL_MS = 15_000L
        private const val CONTROLLER_TIMEOUT_MS = 4_500
        private const val MAX_RETRIES = 3

        private val RESIZE_MODES = intArrayOf(
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
            AspectRatioFrameLayout.RESIZE_MODE_FILL,
        )
        private val RESIZE_LABELS = intArrayOf(
            R.string.aspect_fit, R.string.aspect_zoom, R.string.aspect_stretch,
        )
    }
}
