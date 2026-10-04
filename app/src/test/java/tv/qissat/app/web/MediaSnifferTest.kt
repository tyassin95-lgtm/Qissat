package tv.qissat.app.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import tv.qissat.app.web.MediaSniffer.Kind

class MediaSnifferTest {
    private val master =
        "https://store1-mvn.brqz.online/hls2/04/00004/uu39_,l,n,h,x,.urlset/master.m3u8?t=abc&s=1&e=43200&i=0.0&sp=0"
    private val variant = "https://store1-mvn.brqz.online/hls2/04/00004/uu39_l/index-v1-a1.m3u8?t=abc"
    private val ref = mapOf("Referer" to "https://cdnplus.sbs/")

    @Test fun classify() {
        assertEquals(Kind.HLS, MediaSniffer.classify(master))
        assertEquals(Kind.DASH, MediaSniffer.classify("https://a.com/x/manifest.mpd?x=1"))
        assertEquals(Kind.PROGRESSIVE, MediaSniffer.classify("https://aflm.cdnz.quest/abc/v.mp4"))
        assertNull(MediaSniffer.classify("https://a.com/seg-1.ts"))
        assertNull(MediaSniffer.classify("https://a.com/poster.jpg"))
        assertNull(MediaSniffer.classify("https://a.com/index.html"))
    }

    @Test fun prefersMasterOverVariantEvenWhenVariantIsNewer() {
        val s = MediaSniffer()
        s.record(master, ref)
        s.record(variant, ref)
        val c = s.pick("https://cdnplus.sbs/embed-x.html", null)
        assertNotNull(c)
        assertEquals(master, c!!.url)
        assertEquals(Kind.HLS, c.kind)
        assertEquals("https://cdnplus.sbs/", c.referer)
    }

    @Test fun fallsBackToFirstHlsWhenNoMasterNamed() {
        val s = MediaSniffer()
        val a = "https://c.example/x/stream.m3u8"
        val b = "https://c.example/x/720/stream.m3u8"
        s.record(a, ref); s.record(b, ref)
        assertEquals(a, s.pick(null, null)!!.url)
    }

    @Test fun currentSrcWins() {
        val s = MediaSniffer()
        s.record(master, ref)
        val c = s.pick("https://mp4plus.cyou/embed-x.html", "https://aflm.cdnz.quest/abc/v.mp4")!!
        assertEquals("https://aflm.cdnz.quest/abc/v.mp4", c.url)
        assertEquals(Kind.PROGRESSIVE, c.kind)
    }

    @Test fun blobSourcesAreNotUsed() {
        val s = MediaSniffer()
        assertNull(s.pick("https://x.example/", "blob:https://x.example/uuid"))
    }

    @Test fun matchesTheFrameThatIsPlaying() {
        val s = MediaSniffer()
        val other = "https://ad.example/promo/master.m3u8"
        s.record(other, mapOf("Referer" to "https://ad.example/"))
        s.record(master, ref)
        assertEquals(master, s.pick("https://cdnplus.sbs/embed-x.html", null)!!.url)
    }

    @Test fun clearForgetsEverything() {
        val s = MediaSniffer()
        s.record(master, ref)
        s.clear()
        assertNull(s.pick(null, null))
    }

    @Test fun duplicatesAreIgnoredAndCapped() {
        val s = MediaSniffer()
        repeat(5) { s.record(master, ref) }
        for (i in 0 until 100) s.record("https://c.example/$i/v.mp4", null)
        assertNotNull(s.pick(null, null))
    }
}
