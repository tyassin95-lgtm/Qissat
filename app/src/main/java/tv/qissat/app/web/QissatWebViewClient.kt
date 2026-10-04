package tv.qissat.app.web

import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.net.http.SslError
import tv.qissat.app.BuildConfig
import tv.qissat.app.Config
import tv.qissat.app.adblock.HostUtil
import tv.qissat.app.adblock.UrlPolicy
import java.io.ByteArrayInputStream

/** Enforces the site-only policy, blocks ads at the network layer, and reports load state. */
class QissatWebViewClient(
    private val policy: UrlPolicy,
    private val shield: Shield,
    private val sniffer: MediaSniffer,
    private val callbacks: BrowserCallbacks,
) : WebViewClient() {

    // ------------------------------------------------------------------ navigation

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (!request.isForMainFrame) return false // frames are filtered in shouldInterceptRequest
        return handleNavigation(view, request.url.toString(), request.hasGesture())
    }

    @Deprecated("Deprecated in Java")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
        handleNavigation(view, url, hasGesture = true)

    private fun handleNavigation(view: WebView, url: String, hasGesture: Boolean): Boolean {
        return when (policy.navigation(url)) {
            UrlPolicy.Nav.ALLOW -> false
            UrlPolicy.Nav.UPGRADE_TO_HTTPS -> {
                view.loadUrl("https://" + url.removePrefix("http://"))
                true
            }
            UrlPolicy.Nav.BLOCK -> {
                if (hasGesture) callbacks.onExternalLinkBlocked() else callbacks.onEscapeBlocked()
                if (BuildConfig.DEBUG) Log.d(TAG, "blocked navigation: $url")
                true
            }
        }
    }

    // ------------------------------------------------------------------ network filtering

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url
        val url = uri.toString()
        val host = uri.host?.lowercase()

        if (host == Config.BRIDGE_HOST) {
            PlayBeacon.parse(url, shield.nonce)?.let(callbacks::onPlayBeacon)
            return emptyResponse("image/gif")
        }

        val headers = request.requestHeaders
        if (request.isForMainFrame) {
            // Safety net for redirects that slipped past shouldOverrideUrlLoading.
            return if (policy.navigation(url) == UrlPolicy.Nav.BLOCK) emptyResponse("text/html") else null
        }

        val accept = headers?.entries?.firstOrNull { it.key.equals("Accept", true) }?.value
        val fetchDest = headers?.entries?.firstOrNull { it.key.equals("Sec-Fetch-Dest", true) }?.value
        val isDocument = (accept != null && accept.contains("text/html")) ||
            fetchDest == "iframe" || fetchDest == "frame" || fetchDest == "document"
        val refererHost = HostUtil.hostOf(
            headers?.entries?.firstOrNull { it.key.equals("Referer", true) }?.value,
        )

        if (!policy.allowRequest(url, isDocument, refererHost)) {
            if (BuildConfig.DEBUG) Log.d(TAG, "blocked ${if (isDocument) "frame" else "request"}: $url")
            return emptyResponse(mimeFor(url, isDocument))
        }
        sniffer.record(url, headers)
        return null
    }

    private fun emptyResponse(mime: String): WebResourceResponse =
        WebResourceResponse(mime, "utf-8", 200, "OK", NO_CACHE_HEADERS, ByteArrayInputStream(ByteArray(0)))

    private fun mimeFor(url: String, isDocument: Boolean): String {
        if (isDocument) return "text/html"
        val path = HostUtil.pathOf(url)
        return when {
            path.endsWith(".js") || path.endsWith(".mjs") -> "application/javascript"
            path.endsWith(".css") -> "text/css"
            path.endsWith(".json") -> "application/json"
            path.endsWith(".png") || path.endsWith(".gif") || path.endsWith(".jpg") ||
                path.endsWith(".jpeg") || path.endsWith(".webp") -> "image/gif"
            path.endsWith(".html") || path.endsWith(".htm") -> "text/html"
            else -> "text/plain"
        }
    }

    // ------------------------------------------------------------------ load state

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        if (policy.navigation(url) == UrlPolicy.Nav.BLOCK) {
            view.stopLoading()
            callbacks.onEscapeBlocked()
            return
        }
        sniffer.clear()
        shield.injectIfNeeded(view)
        callbacks.onPageStarted(url)
    }

    override fun onPageFinished(view: WebView, url: String) {
        shield.injectIfNeeded(view)
        callbacks.onPageFinished(url)
    }

    // ------------------------------------------------------------------ errors

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (!request.isForMainFrame) return
        val code = if (Build.VERSION.SDK_INT >= 23) error.errorCode else ERROR_UNKNOWN
        val desc = if (Build.VERSION.SDK_INT >= 23) error.description?.toString() else null
        reportMainFrameError(request.url.toString(), code, desc)
    }

    @Deprecated("Deprecated in Java")
    override fun onReceivedError(view: WebView, errorCode: Int, description: String?, failingUrl: String?) {
        // API 21-22 only (newer versions call the overload above). Only report the page itself.
        if (Build.VERSION.SDK_INT >= 23) return
        if (failingUrl != null && (failingUrl == view.url || failingUrl == Config.HOME_URL)) {
            reportMainFrameError(failingUrl, errorCode, description)
        }
    }

    private fun reportMainFrameError(url: String?, code: Int, description: String?) {
        if (description?.contains("ERR_ABORTED") == true) return
        val kind = when (code) {
            ERROR_HOST_LOOKUP, ERROR_CONNECT, ERROR_IO -> LoadError.OFFLINE
            ERROR_TIMEOUT -> LoadError.TIMEOUT
            ERROR_FAILED_SSL_HANDSHAKE -> LoadError.SSL
            else -> LoadError.OTHER
        }
        callbacks.onMainFrameError(url, kind)
    }

    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
        if (request.isForMainFrame && errorResponse.statusCode >= 500) {
            callbacks.onMainFrameError(request.url.toString(), LoadError.SERVER)
        }
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        handler.cancel() // never proceed past certificate problems
        callbacks.onMainFrameError(error.url, LoadError.SSL)
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        callbacks.onRenderProcessGone(if (Build.VERSION.SDK_INT >= 26) detail.didCrash() else true)
        return true
    }

    private companion object {
        const val TAG = "QissatWeb"
        val NO_CACHE_HEADERS = mapOf("Cache-Control" to "no-store", "Access-Control-Allow-Origin" to "*")
    }
}
