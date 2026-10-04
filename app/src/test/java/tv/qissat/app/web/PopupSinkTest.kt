package tv.qissat.app.web

import android.app.Activity
import android.webkit.WebView
import org.junit.After
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
import tv.qissat.app.adblock.TestRules
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PopupSinkTest {
    private lateinit var activity: Activity
    private lateinit var sink: PopupSink
    private val opened = mutableListOf<String>()

    @Before fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).create().get()
        sink = PopupSink(activity, TestRules.policy()) { opened += it }
    }

    @After fun tearDown() = sink.release()

    private fun popupWebView(gesture: Boolean): WebView {
        val transport = WebView(activity).WebViewTransport()
        assertTrue(sink.attach(transport, gesture))
        return transport.webView!!
    }

    @Suppress("DEPRECATION")
    private fun navigate(popup: WebView, url: String) {
        val client = shadowOf(popup).webViewClient
        assertTrue("popup navigation is always cancelled", client.shouldOverrideUrlLoading(popup, url))
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test fun adPopupIsDropped() {
        navigate(popupWebView(gesture = true), "https://ad.example/landing?x=1")
        assertTrue(opened.isEmpty())
    }

    @Test fun popunderOnKnownAdNetworkIsDropped() {
        navigate(popupWebView(gesture = true), "https://aarems.org/offer")
        assertTrue(opened.isEmpty())
    }

    @Test fun siteLinkFromRealClickOpensInMainView() {
        navigate(popupWebView(gesture = true), "https://www.qisat.tv/episode/abc/")
        assertEquals(listOf("https://www.qisat.tv/episode/abc/"), opened)
    }

    @Test fun siteLinkWithoutUserGestureIsDropped() {
        navigate(popupWebView(gesture = false), "https://www.qisat.tv/episode/abc/")
        assertTrue(opened.isEmpty())
    }

    @Test fun httpSiteLinkIsUpgraded() {
        navigate(popupWebView(gesture = true), "http://www.qisat.tv/x/")
        assertEquals(listOf("https://www.qisat.tv/x/"), opened)
    }

    @Test fun onlyFirstTargetOfAPopupIsConsidered() {
        val p = popupWebView(gesture = true)
        navigate(p, "https://ad.example/first")
        navigate(p, "https://www.qisat.tv/second/") // popup scripts often bounce: ignored once resolved
        assertTrue(opened.isEmpty())
    }

    @Test fun popupFloodIsRefused() {
        repeat(3) { popupWebView(gesture = true) }
        val transport = WebView(activity).WebViewTransport()
        assertFalse("4th concurrent popup must be refused", sink.attach(transport, true))
    }

    @Test fun sinksAreReleasedAfterTheirLifetime() {
        repeat(3) { popupWebView(gesture = true) }
        shadowOf(android.os.Looper.getMainLooper()).idleFor(5, TimeUnit.SECONDS)
        assertTrue("slots free again", sink.attach(WebView(activity).WebViewTransport(), true))
    }
}
