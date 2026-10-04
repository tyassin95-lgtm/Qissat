package tv.qissat.app.player

import android.app.Activity
import android.content.Intent
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tv.qissat.app.R
import tv.qissat.app.web.MediaSniffer

@OptIn(markerClass = [UnstableApi::class])
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerActivityTest {

    private fun intent(url: String = "http://127.0.0.1:9/never/master.m3u8") = PlayRequest(
        url = url,
        kind = MediaSniffer.Kind.HLS,
        referer = "https://cdnplus.sbs/",
        userAgent = "Mozilla/5.0 test",
        cookie = "a=b",
        title = "Yargı S01E48",
        pageUrl = "https://www.qisat.tv/episode/x/",
        startMs = 0,
    ).toIntent(ApplicationProvider.getApplicationContext())

    private fun launch(i: Intent = intent()) = Robolectric.buildActivity(PlayerActivity::class.java, i).setup().get()

    @Test fun inflatesControlsAndAddsMuteAndPictureSizeButtons() {
        val a = launch()
        val view = a.findViewById<PlayerView>(R.id.player_view)
        assertNotNull(view.player)
        assertEquals("Yargı S01E48", a.findViewById<TextView>(R.id.title).text)
        val basic = view.findViewById<LinearLayout>(androidx.media3.ui.R.id.exo_basic_controls)
        assertTrue("mute + aspect buttons inserted first", basic.childCount >= 2)
        assertEquals(a.getString(R.string.player_mute), basic.getChildAt(0).contentDescription)
        assertEquals(a.getString(R.string.player_aspect), basic.getChildAt(1).contentDescription)
        // clicking them must not crash and must change state
        val before = view.player!!.volume
        basic.getChildAt(0).performClick()
        assertTrue(view.player!!.volume != before)
        basic.getChildAt(0).performClick()
        assertEquals(before, view.player!!.volume, 0.001f)
        val mode = view.resizeMode
        basic.getChildAt(1).performClick()
        assertTrue(view.resizeMode != mode)
    }

    @Test fun missingStreamClosesImmediately() {
        val a = Robolectric.buildActivity(PlayerActivity::class.java, Intent()).setup().get()
        assertTrue(a.isFinishing)
    }

    @Test fun remoteKeysDoNotCrashWhenControlsAreHidden() {
        val a = launch()
        listOf(
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_M,
        ).forEach {
            a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, it))
            a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, it))
        }
    }

    @Test fun backClosesThePlayerWithCancelledResult() {
        val a = launch()
        a.onBackPressedDispatcher.onBackPressed()
        assertTrue(a.isFinishing)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(a).resultCode)
    }

    /** An unplayable stream must hand control back to the web player instead of getting stuck. */
    @Test fun unplayableStreamFallsBackToWebPlayer() {
        val a = launch()
        val deadline = System.currentTimeMillis() + 30_000
        val looper = shadowOf(android.os.Looper.getMainLooper())
        while (!a.isFinishing && System.currentTimeMillis() < deadline) {
            // ExoPlayer's playback thread runs on the (paused) Robolectric clock: advance it.
            looper.idleFor(100, java.util.concurrent.TimeUnit.MILLISECONDS)
            Thread.sleep(20)
        }
        assertTrue("player should give up and finish", a.isFinishing)
        assertEquals(PlayerActivity.RESULT_FALLBACK, shadowOf(a).resultCode)
    }
}
