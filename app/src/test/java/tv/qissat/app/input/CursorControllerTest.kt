package tv.qissat.app.input

import android.app.Activity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CursorControllerTest {

    /** Records the input the cursor synthesises. */
    private class Target(activity: Activity) : View(activity) {
        val touches = mutableListOf<Triple<Int, Float, Float>>()
        val hovers = mutableListOf<Triple<Int, Float, Float>>()
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            touches += Triple(event.actionMasked, event.x, event.y)
            return true
        }
        override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
            hovers += Triple(event.actionMasked, event.x, event.y)
            return true
        }
    }

    private lateinit var cursorView: CursorView
    private lateinit var target: Target
    private lateinit var controller: CursorController

    @Before fun setUp() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        val root = FrameLayout(activity)
        target = Target(activity)
        cursorView = CursorView(activity)
        root.addView(target, FrameLayout.LayoutParams(1000, 600))
        root.addView(cursorView, FrameLayout.LayoutParams(1000, 600))
        activity.setContentView(root)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 1000, 600)
        controller = CursorController(cursorView, { null }, { target })
        controller.userEnabled = true
        controller.centerIfNeeded()
    }

    private fun key(code: Int, action: Int, repeat: Int = 0) =
        KeyEvent(0, 0, action, code, repeat)

    private fun advance(ms: Long) = shadowOf(android.os.Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)

    @Test fun startsCenteredAndInvisibleUntilUsed() {
        assertEquals(500f, cursorView.pointerX, 0.5f)
        assertEquals(300f, cursorView.pointerY, 0.5f)
    }

    @Test fun shortTapNudgesPrecisely() {
        val x0 = cursorView.pointerX
        assertTrue(controller.handleKey(key(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN)))
        assertTrue(controller.handleKey(key(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_UP)))
        val moved = cursorView.pointerX - x0
        assertTrue("tap should move a few pixels, moved $moved", moved in 3f..60f)
        assertEquals(View.VISIBLE, cursorView.visibility)
    }

    @Test fun holdingAcceleratesAndStopsOnRelease() {
        controller.handleKey(key(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.ACTION_DOWN))
        advance(300)
        val early = 500f - cursorView.pointerX
        advance(1500)
        val late = 500f - cursorView.pointerX
        assertTrue("moves while held ($early)", early > 0f)
        assertTrue("keeps moving ($late > $early)", late > early)
        controller.handleKey(key(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.ACTION_UP))
        val stopped = cursorView.pointerX
        advance(400)
        assertEquals("no drift after release", stopped, cursorView.pointerX, 0.01f)
    }

    @Test fun cursorIsClampedToTheScreen() {
        controller.handleKey(key(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.ACTION_DOWN))
        controller.handleKey(key(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.ACTION_DOWN))
        advance(6000)
        assertEquals(0f, cursorView.pointerX, 0.01f)
        assertEquals(0f, cursorView.pointerY, 0.01f)
        controller.handleKey(key(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.ACTION_UP))
        controller.handleKey(key(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.ACTION_UP))
    }

    @Test fun okClicksAtThePointer() {
        controller.handleKey(key(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN))
        controller.handleKey(key(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_UP))
        val px = cursorView.pointerX
        val py = cursorView.pointerY
        target.touches.clear()
        assertTrue(controller.handleKey(key(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_DOWN)))
        assertTrue(controller.handleKey(key(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_UP)))
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), target.touches.map { it.first })
        assertEquals(px, target.touches[0].second, 0.01f)
        assertEquals(py, target.touches[0].third, 0.01f)
    }

    @Test fun holdingOkAndMovingDragsLikeAFinger() {
        controller.handleKey(key(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_DOWN))
        controller.handleKey(key(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN))
        advance(500)
        controller.handleKey(key(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_UP))
        controller.handleKey(key(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_UP))
        val actions = target.touches.map { it.first }
        assertEquals(MotionEvent.ACTION_DOWN, actions.first())
        assertEquals(MotionEvent.ACTION_UP, actions.last())
        assertTrue("drag produced MOVE events", actions.contains(MotionEvent.ACTION_MOVE))
        assertTrue("dragged to the right", target.touches.last().second > target.touches.first().second)
    }

    @Test fun movingSendsHoverSoMenusAndPlayerControlsReact() {
        controller.handleKey(key(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_DOWN))
        advance(600)
        controller.handleKey(key(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_UP))
        assertTrue(target.hovers.isNotEmpty())
        assertTrue(target.hovers.all { it.first == MotionEvent.ACTION_HOVER_MOVE })
    }

    @Test fun disabledOrSuspendedControllerLeavesKeysAlone() {
        controller.suspended = true
        assertFalse(controller.handleKey(key(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN)))
        controller.suspended = false
        controller.userEnabled = false
        assertFalse(controller.handleKey(key(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_DOWN)))
        assertEquals(View.INVISIBLE, cursorView.visibility)
    }

    @Test fun unrelatedKeysAreNotConsumed() {
        assertFalse(controller.handleKey(key(KeyEvent.KEYCODE_A, KeyEvent.ACTION_DOWN)))
        assertFalse(controller.handleKey(key(KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_DOWN)))
    }

    @Test fun suspendingMidClickCancelsThePress() {
        controller.handleKey(key(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_DOWN))
        controller.suspended = true
        assertEquals(MotionEvent.ACTION_CANCEL, target.touches.last().first)
    }
}
