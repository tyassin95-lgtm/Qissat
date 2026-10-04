package tv.qissat.app.adblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.qissat.app.adblock.UrlPolicy.Nav

class UrlPolicyTest {
    private val policy = TestRules.policy()

    // ------------------------------------------------------------ top-level navigation

    @Test fun qisatPagesAreAllowed() {
        assertEquals(Nav.ALLOW, policy.navigation("https://www.qisat.tv/"))
        assertEquals(Nav.ALLOW, policy.navigation("https://qisat.tv/turkish-series/"))
        assertEquals(
            Nav.ALLOW,
            policy.navigation("https://www.qisat.tv/episode/%d9%85%d8%b3%d9%84%d8%b3%d9%84-%d8%a7%d9%84%d9%82%d8%b6%d8%a7%d8%a1-%d8%a7%d9%84%d8%ad%d9%84%d9%82%d8%a9-48/"),
        )
        assertEquals(Nav.ALLOW, policy.navigation("https://www.qisat.tv/?s=yargi"))
    }

    @Test fun httpIsUpgraded() {
        assertEquals(Nav.UPGRADE_TO_HTTPS, policy.navigation("http://www.qisat.tv/"))
    }

    @Test fun externalSitesAreBlocked() {
        listOf(
            "https://x.com/qisat", "https://www.facebook.com/sharer?u=1", "https://t.me/qisat",
            "https://wa.me/123", "https://www.youtube.com/watch?v=1", "https://www.instagram.com/x",
            "https://aarems.org/1/abc", "https://evil.com/?r=qisat.tv", "https://qisat.tv.evil.com/",
            "https://qisat.tv@evil.com/", "https://w.anaplayer.online/albaplayer/x",
            "https://cdnplus.sbs/embed-x.html",
        ).forEach { assertEquals(it, Nav.BLOCK, policy.navigation(it)) }
    }

    @Test fun nonWebSchemesAreBlocked() {
        listOf(
            "intent://scan/#Intent;scheme=zxing;end", "market://details?id=x", "mailto:a@b.c",
            "tel:123", "file:///sdcard/x.html", "javascript:alert(1)", "data:text/html,<b>x</b>",
            "blob:https://www.qisat.tv/uuid", "ftp://qisat.tv/x",
        ).forEach { assertEquals(it, Nav.BLOCK, policy.navigation(it)) }
        assertEquals(Nav.ALLOW, policy.navigation("about:blank"))
        assertTrue(policy.isExternalScheme("market://details?id=x"))
        assertFalse(policy.isExternalScheme("https://qisat.tv/"))
    }

    // ------------------------------------------------------------ requests: ads

    private fun allowed(url: String, doc: Boolean = false, referer: String? = null) =
        policy.allowRequest(url, doc, referer)

    @Test fun adNetworksAreBlockedEverywhere() {
        listOf(
            "https://aarems.org/1/c9274ce6966ff7830745cfa778a71694",
            "https://llvpn.com/tag.min.js",
            "https://www.googletagmanager.com/gtag/js?id=G-1",
            "https://static.cloudflareinsights.com/beacon.min.js/abc",
            "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js",
            "https://a.exoclick.com/x", "https://s.propellerads.com/x", "https://sub.adsterra.com/x",
            "https://www.google-analytics.com/g/collect?v=2",
        ).forEach { assertFalse(it, allowed(it)) }
    }

    @Test fun adBlockingAppliesToFramesToo() {
        assertFalse(allowed("https://aarems.org/frame", doc = true, referer = "cdnplus.sbs"))
        assertFalse(allowed("https://ads.exoclick.com/iframe", doc = true, referer = "www.qisat.tv"))
    }

    @Test fun popunderPathSignaturesAreBlockedOnPlayerHosts() {
        assertFalse(allowed("https://cdnplus.sbs/js/popunder.js"))
        assertFalse(allowed("https://vidoba.cyou/assets/pop-under.min.js"))
        assertFalse(allowed("https://x.example/ads/vast.xml"))
        assertFalse(allowed("https://x.example/js/adsbygoogle.js"))
    }

    @Test fun inertBaitScriptIsLeftAlone() {
        // /js/pop.js on the players only defines a flag the anti-adblock check reads.
        assertTrue(allowed("https://cdnplus.sbs/js/pop.js"))
    }

    @Test fun firstPartyIsNeverFilteredByPathRules() {
        assertTrue(allowed("https://www.qisat.tv/wp-content/themes/qisat/assets/js/ads.js"))
    }

    // ------------------------------------------------------------ requests: legit playback

    @Test fun playerChainIsAllowed() {
        // site -> wrapper -> provider, exactly as observed on the live site
        assertTrue(allowed("https://w.anaplayer.online/albaplayer/yargi-s01e48", doc = true, referer = "www.qisat.tv"))
        assertTrue(allowed("https://w.anaplayer.online/albaplayer/yargi-s01e48/?serv=2", doc = true, referer = "w.anaplayer.online"))
        assertTrue(allowed("https://cdnplus.sbs/embed-uu39yzf9efbq.html", doc = true, referer = "w.anaplayer.online"))
        assertTrue(allowed("https://mp4plus.cyou/embed-x0v3th4g4hcb.html", doc = true, referer = "w.anaplayer.online"))
        assertTrue(allowed("https://anafast.cyou/embed-enkwqbmllspf.html", doc = true, referer = "w.anaplayer.online"))
        assertTrue(allowed("https://vidoba.cyou/embed-uv2qpi75f8sy.html", doc = true, referer = "w.anaplayer.online"))
        assertTrue(allowed("https://vidspeed.space/embed-971dv9qsw5i4.html", doc = true, referer = "w.anaplayer.online"))
        // movie page embeds a provider directly, on a rotated TLD
        assertTrue(allowed("https://cdnplus.space/embed-sj6tloq5s0f4.html", doc = true, referer = "www.qisat.tv"))
        // provider helper frames (e.g. caption upload overlay) on the provider's own host
        assertTrue(allowed("https://cdnplus.sbs/?op=upload_srt&file_code=uu39yzf9efbq", doc = true, referer = "cdnplus.sbs"))
    }

    @Test fun rotatedProviderDomainsKeepWorking() {
        assertTrue(allowed("https://cdnplus.xyz/embed-new.html", doc = true, referer = "w.anaplayer.online"))
        assertTrue(allowed("https://w.vidhide.com/v/abc", doc = true, referer = null))
        assertTrue(allowed("https://www.youtube.com/embed/abc", doc = true, referer = "www.qisat.tv"))
    }

    @Test fun mediaAndPlayerSubresourcesAreAllowed() {
        listOf(
            "https://store1-mvn.brqz.online/hls2/04/00004/uu39yzf9efbq_,l,n,h,x,.urlset/master.m3u8?t=abc&s=1&e=43200",
            "https://store1-mvn.brqz.online/hls2/04/00004/uu39yzf9efbq_l/index-v1-a1.m3u8?t=abc",
            "https://store1-mvn.brqz.online/hls2/04/00004/uu39yzf9efbq_l/seg-1-v1-a1.ts?t=abc",
            "https://vroba-eco1-ncs.cdnz.quest/i/01/00057/uv2qpi75f8sy.jpg",
            "https://aflm-eco1-lmx.cdnz.quest/abc/v.mp4",
            "https://ssl.p.jwpcdn.com/player/v/8.34.5/jwplayer.core.controls.js",
            "https://ajax.googleapis.com/ajax/libs/jquery/2.2.4/jquery.min.js",
            "https://cdnplus.sbs/js/jquery.min.js", "https://cdnplus.sbs/player/jw8/jwplayer.js?v=",
            "https://fonts.gstatic.com/s/x.woff2", "https://cdn.jsdelivr.net/npm/hls.js@1/dist/hls.min.js",
        ).forEach { assertTrue(it, allowed(it)) }
    }

    // ------------------------------------------------------------ requests: ad frames

    @Test fun unknownAdIframesAreBlocked() {
        // ad iframe injected by a script inside the provider page
        assertFalse(allowed("https://totally-new-ads.example/serve?zone=1", doc = true, referer = "cdnplus.sbs"))
        // ad iframe straight into the site with a non-embed path
        assertFalse(allowed("https://rando-ads.example/banner.html", doc = true, referer = "www.qisat.tv"))
        // nested deeper than the wrapper
        assertFalse(allowed("https://unknown-player.example/embed/1", doc = true, referer = "cdnplus.sbs"))
        // unknown referer and no embed-looking path
        assertFalse(allowed("https://rando-ads.example/x.html", doc = true, referer = null))
    }

    @Test fun unknownProviderPlacedByWrapperIsAcceptedWhenItLooksLikeAnEmbed() {
        assertTrue(allowed("https://newhost.example/embed-abc.html", doc = true, referer = "w.anaplayer.online"))
        assertTrue(allowed("https://newhost.example/e/abc", doc = true, referer = "www.qisat.tv"))
    }

    @Test fun nonWebSocketAndSchemelessRequestsPassThrough() {
        assertTrue(allowed("data:image/png;base64,AAAA"))
        assertTrue(allowed("blob:https://cdnplus.sbs/uuid"))
    }
}
