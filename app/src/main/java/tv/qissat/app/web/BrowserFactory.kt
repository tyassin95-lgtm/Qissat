package tv.qissat.app.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView

/** Creates the one WebView the app uses, tuned for site-only browsing and low-end devices. */
object BrowserFactory {

    @SuppressLint("SetJavaScriptEnabled")
    fun create(context: Context): WebView {
        val webView = WebView(context)
        webView.setBackgroundColor(Color.parseColor("#0B0B10"))
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.overScrollMode = WebView.OVER_SCROLL_NEVER

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Only a real tap/click may start playback: stops hidden autoplaying ad videos.
            mediaPlaybackRequiresUserGesture = true
            // Windows are routed to PopupSink instead of being opened or silently allowed.
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false

            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            setGeolocationEnabled(false)
            saveFormData = false

            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT

            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = false
            textZoom = 100

            userAgentString = cleanUserAgent(userAgentString)
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            // Players are cross-site iframes and keep their session state in cookies.
            setAcceptThirdPartyCookies(webView, true)
        }
        return webView
    }

    /**
     * Presents as plain Chrome: several embed hosts degrade or refuse "; wv" WebViews.
     */
    fun cleanUserAgent(ua: String): String =
        ua.replace("; wv)", ")").replace(Regex(" Version/\\d+(\\.\\d+)*"), "")

    fun destroy(webView: WebView) {
        webView.stopLoading()
        webView.loadUrl("about:blank")
        webView.webViewClient = android.webkit.WebViewClient()
        webView.webChromeClient = null
        (webView.parent as? android.view.ViewGroup)?.removeView(webView)
        webView.removeAllViews()
        webView.destroy()
    }

}
