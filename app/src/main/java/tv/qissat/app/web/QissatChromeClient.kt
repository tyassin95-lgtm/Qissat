package tv.qissat.app.web

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import tv.qissat.app.R
import tv.qissat.app.adblock.UrlPolicy

/** Fullscreen video, pop-up handling and JS dialogs; denies every permission request. */
class QissatChromeClient(
    private val context: Context,
    private val policy: UrlPolicy,
    private val popupSink: PopupSink,
    private val callbacks: BrowserCallbacks,
) : WebChromeClient() {

    override fun onProgressChanged(view: WebView, newProgress: Int) = callbacks.onProgress(newProgress)

    // ------------------------------------------------------------------ fullscreen video

    override fun onShowCustomView(view: View, callback: CustomViewCallback) =
        callbacks.onShowCustomView(view, callback)

    override fun onHideCustomView() = callbacks.onHideCustomView()

    /** Avoids the grey default poster (and a null-bitmap crash on some WebViews). */
    override fun getDefaultVideoPoster(): Bitmap =
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

    override fun getVideoLoadingProgressView(): View = View(context)

    // ------------------------------------------------------------------ pop-ups

    override fun onCreateWindow(
        view: WebView,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message,
    ): Boolean {
        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
        if (!popupSink.attach(transport, isUserGesture)) return false
        resultMsg.sendToTarget()
        return true
    }

    // ------------------------------------------------------------------ dialogs

    private fun isTrusted(url: String?) = policy.isFirstPartyUrl(url)

    override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
        if (!isTrusted(url)) {
            result.cancel()
            return true
        }
        return show(result, message, positive = true, negative = false)
    }

    override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
        if (!isTrusted(url)) {
            result.cancel()
            return true
        }
        return show(result, message, positive = true, negative = true)
    }

    override fun onJsPrompt(
        view: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult,
    ): Boolean {
        result.cancel()
        return true
    }

    /** "Are you sure you want to leave?" traps are never shown. */
    override fun onJsBeforeUnload(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
        result.confirm()
        return true
    }

    private fun show(result: JsResult, message: String?, positive: Boolean, negative: Boolean): Boolean {
        val builder = AlertDialog.Builder(context, R.style.Theme_Qissat_Dialog)
            .setMessage(message)
            .setOnCancelListener { result.cancel() }
        if (positive) builder.setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
        if (negative) builder.setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
        builder.show()
        return true
    }

    // ------------------------------------------------------------------ permissions

    override fun onPermissionRequest(request: PermissionRequest) = request.deny()

    override fun onGeolocationPermissionsShowPrompt(
        origin: String, callback: GeolocationPermissions.Callback,
    ) = callback.invoke(origin, false, false)

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams,
    ): Boolean {
        filePathCallback.onReceiveValue(null)
        return true
    }
}
