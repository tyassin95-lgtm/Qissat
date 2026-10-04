package tv.qissat.app.web

import android.view.View
import android.webkit.WebChromeClient

enum class LoadError { OFFLINE, TIMEOUT, SSL, SERVER, OTHER }

/** Everything the WebView clients need from the hosting activity. */
interface BrowserCallbacks {
    fun onPageStarted(url: String)
    fun onProgress(percent: Int)
    fun onPageFinished(url: String)
    fun onMainFrameError(url: String?, error: LoadError)
    /** A user-initiated navigation to somewhere outside qisat.tv was refused. */
    fun onExternalLinkBlocked()
    /** The page tried to escape to a disallowed URL without user action (redirect). */
    fun onEscapeBlocked()
    fun onPlayBeacon(beacon: PlayBeacon)
    fun onRenderProcessGone(didCrash: Boolean)
    /** A pop-up window resolved to a qisat.tv page; open it in the main view. */
    fun openInMainView(url: String)
    fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback)
    fun onHideCustomView()
}
