package tv.qissat.app.web

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import tv.qissat.app.adblock.UrlPolicy
import java.io.ByteArrayInputStream

/**
 * Absorbs `window.open()` / `target=_blank` windows. A throw-away, network-less WebView is
 * handed to the engine as the popup's browsing context; we only look at where it wants to go.
 * Same-site targets from a real user gesture are re-opened in the main view; everything else
 * is dropped, and the sink is destroyed.
 */
class PopupSink(
    private val context: Context,
    private val policy: UrlPolicy,
    private val onAllowedTarget: (String) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val sinks = mutableListOf<WebView>()
    private val active get() = sinks.size

    /** @return false when too many sinks are alive (popup flood) and the window should just be refused. */
    @SuppressLint("SetJavaScriptEnabled")
    fun attach(transport: WebView.WebViewTransport, userGesture: Boolean): Boolean {
        if (active >= MAX_ACTIVE) return false
        val sink = WebView(context)
        sinks += sink
        sink.settings.javaScriptEnabled = false
        sink.settings.blockNetworkLoads = true
        sink.settings.allowFileAccess = false
        var resolved = false

        fun resolve(url: String?) {
            if (resolved || url.isNullOrEmpty() || url == "about:blank") return
            resolved = true
            if (userGesture && policy.navigation(url) != UrlPolicy.Nav.BLOCK) {
                val target = if (url.startsWith("http://")) "https://" + url.removePrefix("http://") else url
                handler.post { onAllowedTarget(target) }
            }
        }

        sink.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                resolve(request.url.toString())
                return true
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                resolve(url)
                return true
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                resolve(request.url.toString())
                return WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }
        }
        transport.webView = sink
        handler.postDelayed({ destroy(sink) }, LIFETIME_MS)
        return true
    }

    private fun destroy(sink: WebView) {
        try {
            sink.stopLoading()
            sink.webViewClient = WebViewClient()
            sink.destroy()
        } catch (_: Exception) {
        }
        sinks.remove(sink)
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        sinks.toList().forEach(::destroy)
    }

    private companion object {
        const val MAX_ACTIVE = 3
        const val LIFETIME_MS = 4_000L
    }
}
