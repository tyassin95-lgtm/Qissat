package tv.qissat.app.input

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.webkit.WebView

/**
 * Turns D-pad / remote keys into a mouse-like pointer over the WebView:
 * arrows move it (accelerating while held), OK clicks (hold + arrows drags, which scrubs
 * player sliders), pushing against the top/bottom edge scrolls the page, and page/channel
 * keys scroll by a screenful. Hover events are sent so that menus and player controls that
 * react to a mouse behave.
 */
class CursorController(
    private val cursor: CursorView,
    private val webView: () -> WebView?,
    private val activeTarget: () -> View?,
) {
    /** The user's choice (menu toggle / TV default). */
    var userEnabled = false
        set(value) {
            field = value
            refresh()
        }

    /** Temporarily off while an overlay with its own focus navigation is showing. */
    var suspended = false
        set(value) {
            field = value
            refresh()
        }

    private val enabled: Boolean get() = userEnabled && !suspended

    private fun refresh() {
        if (!enabled) {
            releasePress()
            stopLoop()
            cursor.visibility = View.INVISIBLE
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val density = cursor.resources.displayMetrics.density

    private var held = 0 // bitmask of directions
    private var heldSince = 0L
    private var lastFrame = 0L
    private var loopRunning = false
    private var pressed = false
    private var downTime = 0L
    private var lastHover = 0L

    private val hideRunnable = Runnable { if (!pressed) cursor.visibility = View.INVISIBLE }

    private val frame = object : Runnable {
        override fun run() {
            if (held == 0) {
                loopRunning = false
                return
            }
            val now = SystemClock.uptimeMillis()
            val dt = ((now - lastFrame).coerceIn(1, 50)) / 1000f
            lastFrame = now
            val speed = currentSpeed(now - heldSince)
            val dx = (if (held and RIGHT != 0) 1 else 0) - (if (held and LEFT != 0) 1 else 0)
            val dy = (if (held and DOWN != 0) 1 else 0) - (if (held and UP != 0) 1 else 0)
            step(dx * speed * dt, dy * speed * dt, scrollAtEdges = true)
            handler.postDelayed(this, FRAME_MS)
        }
    }

    // ------------------------------------------------------------------ key handling

    /** @return true when the event was consumed. */
    fun handleKey(event: KeyEvent): Boolean {
        if (!enabled) return false
        val code = event.keyCode
        val dir = directionOf(code)
        if (dir != 0) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) {
                    ensureVisible()
                    // Immediate small nudge so a short tap gives precise positioning.
                    step(
                        (if (dir == RIGHT) 1 else if (dir == LEFT) -1 else 0) * TAP_STEP_DP * density,
                        (if (dir == DOWN) 1 else if (dir == UP) -1 else 0) * TAP_STEP_DP * density,
                        scrollAtEdges = false,
                    )
                    if (held == 0) heldSince = SystemClock.uptimeMillis() + TAP_DELAY_MS
                    held = held or dir
                    startLoop()
                }
            } else if (event.action == KeyEvent.ACTION_UP) {
                held = held and dir.inv()
                if (held == 0) stopLoop()
            }
            return true
        }
        if (isSelectKey(code)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) press()
            else if (event.action == KeyEvent.ACTION_UP) release()
            return true
        }
        if (code == KeyEvent.KEYCODE_PAGE_DOWN || code == KeyEvent.KEYCODE_CHANNEL_DOWN ||
            code == KeyEvent.KEYCODE_PAGE_UP || code == KeyEvent.KEYCODE_CHANNEL_UP
        ) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val down = code == KeyEvent.KEYCODE_PAGE_DOWN || code == KeyEvent.KEYCODE_CHANNEL_DOWN
                val wv = webView() ?: return true
                wv.scrollBy(0, (if (down) 1 else -1) * (wv.height * 0.8f).toInt())
            }
            return true
        }
        return false
    }

    private fun directionOf(code: Int) = when (code) {
        KeyEvent.KEYCODE_DPAD_UP -> UP
        KeyEvent.KEYCODE_DPAD_DOWN -> DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> RIGHT
        else -> 0
    }

    private fun isSelectKey(code: Int) =
        code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_BUTTON_A

    // ------------------------------------------------------------------ movement

    private fun currentSpeed(heldMs: Long): Float {
        if (heldMs <= 0) return 0f
        val t = (heldMs / RAMP_MS.toFloat()).coerceIn(0f, 1f)
        val eased = ACCEL.getInterpolation(t)
        val base = BASE_DP_PER_S * density
        val max = MAX_DP_PER_S * density
        return base + (max - base) * eased
    }

    private fun step(dx: Float, dy: Float, scrollAtEdges: Boolean) {
        val w = cursor.width.toFloat()
        val h = cursor.height.toFloat()
        if (w <= 0f || h <= 0f) return
        var nx = cursor.pointerX + dx
        var ny = cursor.pointerY + dy

        if (scrollAtEdges && !pressed) {
            val edge = EDGE_DP * density
            val wv = webView()
            if (wv != null && activeTarget() === wv) {
                if (dy > 0 && ny >= h - edge) {
                    wv.scrollBy(0, dy.toInt().coerceAtLeast(1) * 2)
                } else if (dy < 0 && ny <= edge) {
                    wv.scrollBy(0, dy.toInt().coerceAtMost(-1) * 2)
                }
            }
        }
        nx = nx.coerceIn(0f, w - 1)
        ny = ny.coerceIn(0f, h - 1)
        if (nx == cursor.pointerX && ny == cursor.pointerY) return
        cursor.moveTo(nx, ny)
        ensureVisible()
        if (pressed) sendTouch(MotionEvent.ACTION_MOVE) else sendHover()
    }

    fun centerIfNeeded() {
        if (!cursor.placed && cursor.width > 0) {
            cursor.moveTo(cursor.width / 2f, cursor.height / 2f)
        }
    }

    private fun ensureVisible() {
        centerIfNeeded()
        cursor.visibility = View.VISIBLE
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, HIDE_AFTER_MS)
    }

    private fun startLoop() {
        lastFrame = SystemClock.uptimeMillis()
        if (!loopRunning) {
            loopRunning = true
            handler.postDelayed(frame, FRAME_MS)
        }
    }

    private fun stopLoop() {
        held = 0
        loopRunning = false
        handler.removeCallbacks(frame)
    }

    // ------------------------------------------------------------------ synthesised input

    private fun press() {
        ensureVisible()
        pressed = true
        cursor.clicking = true
        downTime = SystemClock.uptimeMillis()
        sendTouch(MotionEvent.ACTION_DOWN)
    }

    private fun release() {
        if (!pressed) return
        sendTouch(MotionEvent.ACTION_UP)
        pressed = false
        cursor.clicking = false
        ensureVisible()
    }

    private fun releasePress() {
        if (pressed) {
            sendTouch(MotionEvent.ACTION_CANCEL)
            pressed = false
            cursor.clicking = false
        }
    }

    private fun sendTouch(action: Int) {
        val target = activeTarget() ?: return
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(downTime, now, action, cursor.pointerX, cursor.pointerY, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        target.dispatchTouchEvent(event)
        event.recycle()
    }

    private fun sendHover() {
        val now = SystemClock.uptimeMillis()
        if (now - lastHover < HOVER_INTERVAL_MS) return
        lastHover = now
        val target = activeTarget() ?: return
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            x = cursor.pointerX
            y = cursor.pointerY
            pressure = 0f
            size = 1f
        })
        val event = MotionEvent.obtain(
            now, now, MotionEvent.ACTION_HOVER_MOVE, 1, props, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0,
        )
        target.dispatchGenericMotionEvent(event)
        event.recycle()
    }

    fun onDestroy() {
        stopLoop()
        handler.removeCallbacksAndMessages(null)
    }

    private companion object {
        const val UP = 1
        const val DOWN = 2
        const val LEFT = 4
        const val RIGHT = 8

        const val FRAME_MS = 16L
        const val TAP_STEP_DP = 7f
        const val TAP_DELAY_MS = 140L
        const val RAMP_MS = 1100L
        const val BASE_DP_PER_S = 230f
        const val MAX_DP_PER_S = 1050f
        const val EDGE_DP = 56f
        const val HIDE_AFTER_MS = 5_000L
        const val HOVER_INTERVAL_MS = 40L
        val ACCEL = AccelerateInterpolator(1.4f)
    }
}
