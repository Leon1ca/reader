package com.localreader.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.webkit.*
import androidx.webkit.WebViewAssetLoader
import com.localreader.app.core.*
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.util.UUID

/** Trusted generated TXT only. No URI/file/DB capabilities are exposed to JavaScript. */
@SuppressLint("SetJavaScriptEnabled")
class WebReaderAdapter(context: Context, private val onPosition: (Locator.Text) -> Unit,
                       private val onWindow: (WebWindow) -> Unit, private val onError: (String) -> Unit) {
    val view = WebView(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val assets = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context)).build()
    @Volatile private var active: DocumentSession? = null
    private var key: String? = null
    private var job: Job? = null
    private var destroyed = false
    private data class DocumentSession(val gate: WebPositionGate, val html: String)
    val runtimeTextZoom: Int get() = view.settings.textZoom

    init {
        view.keepScreenOn = true
        view.setBackgroundColor(Color.TRANSPARENT)
        view.settings.apply {
            javaScriptEnabled = true; allowFileAccess = false; allowContentAccess = false
            @Suppress("DEPRECATION") allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION") allowUniversalAccessFromFileURLs = false
            blockNetworkLoads = true; blockNetworkImage = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            domStorageEnabled = false; databaseEnabled = false; javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false); setSupportZoom(false); builtInZoomControls = false; displayZoomControls = false
            // Keep the platform's fontScale-derived default, as the Android Readest shell does.
            setGeolocationEnabled(false); safeBrowsingEnabled = true
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
            @Deprecated("Legacy API") override fun shouldOverrideUrlLoading(view: WebView?, url: String?) = true
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse {
                val uri = request.url
                val document = active
                if (request.isForMainFrame && uri.toString() == WebDocument.URL && document != null) {
                    return WebResourceResponse("text/html", "UTF-8", 200, "OK", mapOf("Cache-Control" to "no-store"),
                        ByteArrayInputStream(document.html.toByteArray(Charsets.UTF_8)))
                }
                if (WebResourcePolicy.allowedFont(uri.toString())) {
                    assets.shouldInterceptRequest(uri)?.let { return it }
                }
                return denied()
            }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) onError("正文组件加载失败：${error.description}")
            }
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                active = null; onError("系统正文组件已停止，请返回书架重新打开。")
                destroy()
                return true
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?) = false
            override fun onPermissionRequest(request: PermissionRequest?) { request?.deny() }
        }
        view.addJavascriptInterface(object {
            @JavascriptInterface fun position(token: String, paragraph: Int, character: Int) {
                val document = active ?: return
                val locator = document.gate.position(token, paragraph, character) ?: return
                view.post {
                    if (!destroyed && active === document) onPosition(locator)
                }
            }
        }, "ReaderPosition")
        WebView.setWebContentsDebuggingEnabled(context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
    }
    fun render(engine: TxtEngine, anchor: Locator.Text, jumpSerial: Int, fontSize: Float, theme: String) {
        val nextKey = "${engine.version}:$jumpSerial:$fontSize:$theme"
        if (destroyed || key == nextKey) return
        key = nextKey; active = null; job?.cancel(); view.stopLoading()
        job = scope.launch {
            try {
                val window = withContext(Dispatchers.IO) { WebDocument.window(engine, anchor) }
                val target = withContext(Dispatchers.IO) { engine.resolve(anchor) }
                val token = UUID.randomUUID().toString(); val nonce = UUID.randomUUID().toString()
                val html = WebDocument.html(window, target, token, nonce, WebTypographyProfiles.readestTxtCjk, fontSize, theme)
                val document = DocumentSession(WebPositionGate(token, engine.version, window, engine.index.totalCharacters), html)
                active = document; onWindow(window)
                view.loadUrl(WebDocument.URL)
                if (target.nearby) onError("已恢复至附近文本位置；原锚点仍保留。")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { active = null; onError("正文读取失败：${e.message}") }
        }
    }
    fun destroy() {
        if (destroyed) return
        destroyed = true; active = null; job?.cancel(); scope.cancel()
        view.keepScreenOn = false
        (view.parent as? android.view.ViewGroup)?.removeView(view)
        view.stopLoading(); view.removeJavascriptInterface("ReaderPosition"); view.destroy()
    }
    private fun denied() = WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
}
