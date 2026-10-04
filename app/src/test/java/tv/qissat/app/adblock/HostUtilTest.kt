package tv.qissat.app.adblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostUtilTest {
    @Test fun hostParsing() {
        assertEquals("www.qisat.tv", HostUtil.hostOf("https://www.qisat.tv/episode/x/?a=1#f"))
        assertEquals("qisat.tv", HostUtil.hostOf("http://QISAT.tv:8080/"))
        assertEquals("evil.com", HostUtil.hostOf("https://www.qisat.tv@evil.com/path"))
        assertEquals("evil.com", HostUtil.hostOf("https://evil.com.?x=1"))
        assertEquals("a.b.c", HostUtil.hostOf("https://a.b.c?x"))
        assertNull(HostUtil.hostOf("about:blank"))
        assertNull(HostUtil.hostOf(""))
        assertNull(HostUtil.hostOf(null))
    }

    @Test fun userinfoTrickDoesNotFoolFirstPartyCheck() {
        val p = TestRules.policy()
        assertFalse(p.isFirstPartyUrl("https://qisat.tv@evil.com/"))
        assertFalse(p.isFirstPartyUrl("https://qisat.tv.evil.com/"))
        assertFalse(p.isFirstPartyUrl("https://evilqisat.tv/"))
        assertTrue(p.isFirstPartyUrl("https://qisat.tv/"))
        assertTrue(p.isFirstPartyUrl("https://cdn.www.qisat.tv/x"))
    }

    @Test fun pathAndQuery() {
        assertEquals("/a/b.m3u8", HostUtil.pathOf("https://x.com/A/B.m3u8?t=1#h"))
        assertEquals("/a?t=1", HostUtil.pathAndQuery("https://x.com/A?T=1#h"))
        assertEquals("", HostUtil.pathOf("https://x.com"))
        assertEquals("?q=1", HostUtil.pathAndQuery("https://x.com?q=1"))
    }

    @Test fun queryParams() {
        assertEquals("a b&c", HostUtil.queryParam("https://x/?k=1&s=a%20b%26c&z", "s"))
        assertEquals("", HostUtil.queryParam("https://x/?k=1&z", "z"))
        assertNull(HostUtil.queryParam("https://x/?k=1", "s"))
        assertNull(HostUtil.queryParam("https://x/", "s"))
    }

    @Test fun brandLabelsAndSites() {
        assertEquals("cdnplus", HostUtil.brandLabel("cdnplus.sbs"))
        assertEquals("cdnplus", HostUtil.brandLabel("w.cdnplus.space"))
        assertEquals("cdnplus", HostUtil.brandLabel("a.b.cdnplus.co.uk"))
        assertEquals("anaplayer", HostUtil.brandLabel("w.anaplayer.online"))
        assertNull(HostUtil.brandLabel("localhost"))
        assertNull(HostUtil.brandLabel("10.0.0.1"))
        assertEquals("cdnplus.sbs", HostUtil.siteOf("w.cdnplus.sbs"))
        assertEquals("foo.co.uk", HostUtil.siteOf("a.foo.co.uk"))
    }

    @Test fun subdomainMatching() {
        assertTrue(HostUtil.isSameOrSubdomain("a.b.com", "b.com"))
        assertTrue(HostUtil.isSameOrSubdomain("b.com", "b.com"))
        assertFalse(HostUtil.isSameOrSubdomain("ab.com", "b.com"))
        assertFalse(HostUtil.isSameOrSubdomain("b.com.evil.org", "b.com"))
    }
}
