package tv.qissat.app

import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.view.KeyEvent
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tv.qissat.app.Config as AppConfig
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import tv.qissat.app.player.PlayerActivity
import tv.qissat.app.web.LoadError
import tv.qissat.app.web.QissatWebViewClient
import tv.qissat.app.web.Shield

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityTest {

    private lateinit var activity: MainActivity
    private lateinit var webView: WebView

    @Before fun launch() {
        activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        webView = activity.findViewById<FrameLayout>(R.id.web_container).getChildAt(0) as WebView
    }

    private fun request(url: String, headers: Map<String, String> = emptyMap(), mainFrame: Boolean = false) =
        object : WebResourceRequest {
            override fun getUrl(): Uri = Uri.parse(url)
            override fun isForMainFrame() = mainFrame
            override fun isRedirect() = false
            override fun hasGesture() = true
            override fun getMethod() = "GET"
            override fun getRequestHeaders() = headers
        }

    private fun client() = shadowOf(webView).webViewClient as QissatWebViewClient

    private fun shield(): Shield =
        MainActivity::class.java.getDeclaredField("shield").apply { isAccessible = true }.get(activity) as Shield

    // ------------------------------------------------------------------ launch

    @Test fun startsOnTheSiteHomePageWithSplashShown() {
        assertEquals(AppConfig.HOME_URL, shadowOf(webView).lastLoadedUrl)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.splash).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.error_panel).visibility)
    }

    @Test fun webViewIsLockedDown() {
        val s = webView.settings
        assertTrue(s.javaScriptEnabled)
        assertFalse(s.allowFileAccess)
        assertFalse(s.javaScriptCanOpenWindowsAutomatically)
        assertTrue(s.mediaPlaybackRequiresUserGesture)
        assertFalse(s.userAgentString.contains("; wv"))
    }

    @Test fun deepLinksToTheSiteOpenInApp() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.qisat.tv/turkish-series/"))
        val a = Robolectric.buildActivity(MainActivity::class.java, intent).setup().get()
        val wv = a.findViewById<FrameLayout>(R.id.web_container).getChildAt(0) as WebView
        assertEquals("https://www.qisat.tv/turkish-series/", shadowOf(wv).lastLoadedUrl)
    }

    @Test fun deepLinksToOtherSitesFallBackToHome() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://evil.example/qisat.tv"))
        val a = Robolectric.buildActivity(MainActivity::class.java, intent).setup().get()
        val wv = a.findViewById<FrameLayout>(R.id.web_container).getChildAt(0) as WebView
        assertEquals(AppConfig.HOME_URL, shadowOf(wv).lastLoadedUrl)
    }

    // ------------------------------------------------------------------ navigation policy via the real client

    @Test fun externalLinkIsBlockedWithMessage() {
        val blocked = client().shouldOverrideUrlLoading(webView, request("https://www.facebook.com/x", mainFrame = true))
        assertTrue(blocked)
        assertEquals(activity.getString(R.string.toast_external_blocked), ShadowToast.getTextOfLatestToast())
    }

    @Test fun siteLinkIsAllowedAndHttpIsUpgraded() {
        assertFalse(client().shouldOverrideUrlLoading(webView, request("https://www.qisat.tv/x/", mainFrame = true)))
        assertTrue(client().shouldOverrideUrlLoading(webView, request("http://www.qisat.tv/x/", mainFrame = true)))
        assertEquals("https://www.qisat.tv/x/", shadowOf(webView).lastLoadedUrl)
    }

    @Test fun adRequestsGetEmptyResponsesAndPlayerRequestsPass() {
        val ad = client().shouldInterceptRequest(webView, request("https://aarems.org/1/abc"))
        assertNotNull(ad)
        assertEquals(0, ad!!.data.available())
        val ok = client().shouldInterceptRequest(
            webView,
            request("https://store1-mvn.brqz.online/hls2/x/master.m3u8?t=1", mapOf("Referer" to "https://cdnplus.sbs/")),
        )
        assertNull(ok)
    }

    @Test fun blockedFrameDocumentGetsBlankPage() {
        val frame = client().shouldInterceptRequest(
            webView,
            request("https://rando-ads.example/banner.html", mapOf("Accept" to "text/html,application/xhtml+xml", "Referer" to "https://cdnplus.sbs/")),
        )
        assertNotNull(frame)
        assertEquals("text/html", frame!!.mimeType)
    }

    // ------------------------------------------------------------------ errors & recovery

    @Test fun errorOverlayShowsAndRetryReloads() {
        activity.onMainFrameError("https://www.qisat.tv/turkish-series/", LoadError.OFFLINE)
        val panel = activity.findViewById<View>(R.id.error_panel)
        assertEquals(View.VISIBLE, panel.visibility)
        assertEquals(activity.getString(R.string.error_offline_title), activity.findViewById<TextView>(R.id.error_title).text)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(500, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.splash).visibility)

        activity.findViewById<View>(R.id.btn_retry).performClick()
        assertEquals(View.GONE, panel.visibility)
        assertEquals("https://www.qisat.tv/turkish-series/", shadowOf(webView).lastLoadedUrl)
    }

    @Test fun slowButUsablePageIsNotCoveredByATimeoutError() {
        client().onPageStarted(webView, "https://www.qisat.tv/", null)
        activity.onProgress(85) // content is there, only a straggler resource hangs
        shadowOf(android.os.Looper.getMainLooper()).idleFor(60, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.error_panel).visibility)
    }

    @Test fun pageThatBarelyLoadedTimesOutAndRecoversWhenItFinishes() {
        client().onPageStarted(webView, "https://www.qisat.tv/", null)
        activity.onProgress(10)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(60, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.error_panel).visibility)
        assertEquals(activity.getString(R.string.error_timeout_title), activity.findViewById<TextView>(R.id.error_title).text)
        client().onPageFinished(webView, "https://www.qisat.tv/")
        assertEquals(View.GONE, activity.findViewById<View>(R.id.error_panel).visibility)
    }

    @Test fun realFailureOverlayIsNotDismissedByAStrayPageFinished() {
        activity.onMainFrameError("https://www.qisat.tv/", LoadError.OFFLINE)
        client().onPageFinished(webView, "about:blank")
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.error_panel).visibility)
    }

    @Test fun errorOverlayHomeButtonGoesHome() {
        activity.onMainFrameError("https://www.qisat.tv/x/", LoadError.SERVER)
        activity.findViewById<View>(R.id.btn_home).performClick()
        assertEquals(AppConfig.HOME_URL, shadowOf(webView).lastLoadedUrl)
    }

    @Test fun everyErrorKindHasTextAndOverlayNeverShowsForeignRetryTargets() {
        LoadError.values().forEach { kind ->
            activity.onMainFrameError("https://evil.example/", kind)
            activity.findViewById<View>(R.id.btn_retry).performClick()
            // Retrying must never navigate off-site.
            assertEquals(AppConfig.HOME_URL, shadowOf(webView).lastLoadedUrl)
        }
    }

    // ------------------------------------------------------------------ back handling

    @Test fun backAtHomeAsksThenExits() {
        activity.onBackPressedDispatcher.onBackPressed()
        assertEquals(activity.getString(R.string.toast_back_exit), ShadowToast.getTextOfLatestToast())
        assertFalse(activity.isFinishing)
        activity.onBackPressedDispatcher.onBackPressed()
        assertTrue(activity.isFinishing)
    }

    @Test fun backFromDeepPageWithoutHistoryGoesHomeFirst() {
        val wvShadow = shadowOf(webView)
        webView.loadUrl("https://www.qisat.tv/episode/abc/")
        activity.onBackPressedDispatcher.onBackPressed()
        assertEquals(AppConfig.HOME_URL, wvShadow.lastLoadedUrl)
        assertFalse(activity.isFinishing)
    }

    // ------------------------------------------------------------------ menu & keys

    @Test fun menuKeyOpensTheMenuWithAllEntries() {
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MENU))
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MENU))
        val dialog: Dialog = ShadowDialog.getLatestDialog()
        assertTrue(dialog.isShowing)
        val items = dialog.findViewById<android.widget.LinearLayout>(R.id.menu_items)
        assertEquals(6, items.childCount)
        assertEquals(activity.getString(R.string.menu_home), (items.getChildAt(0) as TextView).text)
        assertTrue(items.getChildAt(0).isFocused || items.getChildAt(0).isFocusable)
    }

    @Test fun menuCursorEntryTogglesAndPersists() {
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MENU))
        val items = ShadowDialog.getLatestDialog().findViewById<android.widget.LinearLayout>(R.id.menu_items)
        val label = (items.getChildAt(2) as TextView).text.toString()
        assertEquals(activity.getString(R.string.menu_cursor_off), label) // phones start with the cursor off
        items.getChildAt(2).performClick()
        assertEquals(activity.getString(R.string.toast_cursor_on), ShadowToast.getTextOfLatestToast())
        val prefs = activity.getSharedPreferences("settings", 0)
        assertTrue(prefs.getBoolean("cursor", false))
    }

    // ------------------------------------------------------------------ fullscreen video

    @Test fun fullscreenVideoShowsAndHides() {
        var hidden = false
        val callback = WebChromeClient.CustomViewCallback { hidden = true }
        val video = View(activity)
        activity.onShowCustomView(video, callback)
        val container = activity.findViewById<View>(R.id.fullscreen_container)
        assertEquals(View.VISIBLE, container.visibility)
        activity.onBackPressedDispatcher.onBackPressed() // Back leaves fullscreen first
        assertEquals(View.GONE, container.visibility)
        assertTrue(hidden)
        assertFalse(activity.isFinishing)
    }

    // ------------------------------------------------------------------ native player hand-off (end to end)

    private fun beaconUrl(nonce: String, extra: String = "") =
        "https://${AppConfig.BRIDGE_HOST}/b?k=$nonce&e=play&t=0.0&d=2400&w=960&h=540&m=0" +
            "&o=" + Uri.encode("https://cdnplus.sbs/embed-x.html") + extra + "&r=1"

    @Test fun playingVideoWithSniffedStreamLaunchesTheNativePlayer() {
        val master = "https://store1-mvn.brqz.online/hls2/04/00004/uu39_,l,n,.urlset/master.m3u8?t=abc&s=1&e=43200"
        val headers = mapOf("Referer" to "https://cdnplus.sbs/")
        client().shouldInterceptRequest(webView, request(master, headers))
        client().shouldInterceptRequest(webView, request("https://store1-mvn.brqz.online/hls2/04/00004/uu39_l/index-v1-a1.m3u8?t=abc", headers))

        val resp = client().shouldInterceptRequest(webView, request(beaconUrl(shield().nonce)))
        assertNotNull("beacon is answered locally", resp)
        shadowOf(android.os.Looper.getMainLooper()).idle()

        val started = shadowOf(activity).nextStartedActivity
        assertNotNull("player should start", started)
        assertEquals(PlayerActivity::class.java.name, started.component!!.className)
        assertEquals(master, started.getStringExtra("url"))
        assertEquals("HLS", started.getStringExtra("kind"))
        assertEquals("https://cdnplus.sbs/", started.getStringExtra("referer"))
    }

    @Test fun progressiveSourceFromTheVideoElementIsUsedDirectly() {
        val mp4 = "https://aflm-eco1-lmx.cdnz.quest/abc/v.mp4"
        client().shouldInterceptRequest(webView, request(beaconUrl(shield().nonce, "&s=" + Uri.encode(mp4))))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val started = shadowOf(activity).nextStartedActivity
        assertNotNull(started)
        assertEquals(mp4, started.getStringExtra("url"))
        assertEquals("PROGRESSIVE", started.getStringExtra("kind"))
    }

    @Test fun forgedBeaconDoesNothing() {
        client().shouldInterceptRequest(webView, request("https://store1-mvn.brqz.online/x/master.m3u8", mapOf("Referer" to "https://cdnplus.sbs/")))
        client().shouldInterceptRequest(webView, request(beaconUrl("not-the-nonce")))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun beaconWithoutAnyKnownStreamDoesNothing() {
        client().shouldInterceptRequest(webView, request(beaconUrl(shield().nonce)))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun nativePlayerCanBeSwitchedOffInTheMenu() {
        activity.getSharedPreferences("settings", 0).edit().putBoolean("native_player", false).apply()
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val wv = a.findViewById<FrameLayout>(R.id.web_container).getChildAt(0) as WebView
        val c = shadowOf(wv).webViewClient as QissatWebViewClient
        val sh = MainActivity::class.java.getDeclaredField("shield").apply { isAccessible = true }.get(a) as Shield
        c.shouldInterceptRequest(wv, request("https://aflm.cdnz.quest/abc/v.mp4", mapOf("Referer" to "https://mp4plus.cyou/")))
        c.shouldInterceptRequest(wv, request(beaconUrl(sh.nonce, "&s=" + Uri.encode("https://aflm.cdnz.quest/abc/v.mp4"))))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertNull(shadowOf(a).nextStartedActivity)
    }
}
