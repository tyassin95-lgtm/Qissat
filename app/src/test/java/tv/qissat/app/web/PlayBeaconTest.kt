package tv.qissat.app.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayBeaconTest {
    private val good = "https://bridge.qissat.invalid/b?k=n0nce&e=play&t=12.5&d=2400&w=960&h=540&m=1" +
        "&s=https%3A%2F%2Fcdn.example%2Fv.mp4&o=https%3A%2F%2Fcdnplus.sbs%2Fembed-x.html&r=abc"

    @Test fun parsesAllFields() {
        val b = PlayBeacon.parse(good, "n0nce")
        assertNotNull(b)
        b!!
        assertEquals(12.5f, b.positionSec, 0.001f)
        assertEquals(2400, b.durationSec)
        assertEquals(960, b.widthPx)
        assertEquals(540, b.heightPx)
        assertTrue(b.muted)
        assertEquals("https://cdn.example/v.mp4", b.currentSrc)
        assertEquals("https://cdnplus.sbs/embed-x.html", b.frameUrl)
        assertTrue(b.looksLikeMainVideo)
    }

    @Test fun forgedBeaconsAreRejected() {
        assertNull(PlayBeacon.parse(good, "wrong"))
        assertNull(PlayBeacon.parse(good.replace("k=n0nce&", ""), "n0nce"))
        assertNull(PlayBeacon.parse(good.replace("e=play", "e=other"), "n0nce"))
    }

    @Test fun smallOrShortVideosAreNotMainVideos() {
        fun mk(d: Int, w: Int, h: Int) = PlayBeacon.parse(
            "https://bridge.qissat.invalid/b?k=n&e=play&d=$d&w=$w&h=$h", "n",
        )!!
        assertTrue(mk(2400, 960, 540).looksLikeMainVideo)
        assertTrue(mk(0, 960, 540).looksLikeMainVideo)   // unknown duration
        assertTrue(mk(-1, 960, 540).looksLikeMainVideo)  // live
        assertFalse(mk(30, 960, 540).looksLikeMainVideo) // bumper
        assertFalse(mk(2400, 120, 60).looksLikeMainVideo) // thumbnail
    }
}
