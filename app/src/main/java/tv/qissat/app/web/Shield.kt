package tv.qissat.app.web

import android.content.Context
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import tv.qissat.app.Config
import java.util.UUID

/**
 * Loads `assets/shield.js`, personalises it with a per-process nonce, and installs it in every
 * frame (document-start where the WebView supports it, otherwise page-start/finish of the main
 * frame).
 */
class Shield(context: Context) {

    /** Secret shared by the script and the app; a page cannot forge play beacons without it. */
    val nonce: String = UUID.randomUUID().toString().replace("-", "")

    private val source: String = context.assets.open("shield.js").bufferedReader().use { it.readText() }
        .replace("__QSH_NONCE__", nonce)
        .replace("__QSH_BRIDGE__", Config.BRIDGE_HOST)

    /** True once the script is registered to run in all frames before page scripts. */
    var documentStartInstalled = false
        private set

    fun install(webView: WebView) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try {
                WebViewCompat.addDocumentStartJavaScript(webView, source, setOf("*"))
                documentStartInstalled = true
            } catch (_: Exception) {
                documentStartInstalled = false
            }
        }
    }

    /** Fallback for WebViews without document-start scripts (idempotent). */
    fun injectIfNeeded(webView: WebView) {
        if (!documentStartInstalled) webView.evaluateJavascript(source, null)
    }

    fun command(webView: WebView, cmd: String, value: Int = 0) {
        webView.evaluateJavascript("window.__qshCmd&&window.__qshCmd('$cmd',$value)", null)
    }
}
