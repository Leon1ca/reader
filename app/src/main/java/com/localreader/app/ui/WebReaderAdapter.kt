package com.localreader.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.webkit.*
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.localreader.app.core.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.UUID

/** Android capability host; Foliate implementation details never escape this backend. */
@SuppressLint("SetJavaScriptEnabled")
class WebReaderAdapter(context: Context, private val onEvent: (RendererEvent) -> Unit) : RendererBackend {
    val view = WebView(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var active: HostSession? = null
    private var key: String? = null
    private var work: Job? = null
    private var watchdog: Job? = null
    private var destroyed = false
    private val bridgeSupported = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
    private class HostSession(val publication: ReaderPublication, val resources: SessionResources, val gate: RendererEventGate, var locator: Locator, var rules: ReaderRules) {
        var booted = false
        var opened = false
        var pending: JSONObject? = null
    }
    val runtimeTextZoom: Int get() = view.settings.textZoom
    val currentUrl: String? get() = active?.resources?.mainUrl
    val cachedResourceBytes: Int get() = active?.resources?.cachedBytes ?: 0
    init {
        view.keepScreenOn = true
        view.setBackgroundColor(Color.TRANSPARENT)
        view.settings.apply {
            javaScriptEnabled = true; allowFileAccess = false; allowContentAccess = false
            // HTTPS-only documents; file/content access is disabled above. Deprecated
            // file-origin cross-access switches already default to false for target >=16.
            blockNetworkLoads = true; blockNetworkImage = false; cacheMode = WebSettings.LOAD_NO_CACHE
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // WebSQL remains disabled by its platform default; the retired setter is unnecessary.
            domStorageEnabled = false; javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false); setSupportZoom(false); builtInZoomControls = false; displayZoomControls = false
            // Preserve platform fontScale-derived textZoom, as in the Android shell baseline.
            setGeolocationEnabled(false); safeBrowsingEnabled = false // only bundled local content is loaded; no remote URL lookups
        }
        view.webViewClient = LocalWebViewClient(LocalWebViewPolicy())
        view.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?) = false
            override fun onPermissionRequest(request: PermissionRequest?) { request?.deny() }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) WebViewCompat.addWebMessageListener(view,"ReaderEvents",setOf(ReaderOrigin.URL)) { _, message, origin, mainFrame, _ ->
            if (!mainFrame || origin.toString().trimEnd('/') != ReaderOrigin.URL) return@addWebMessageListener
            val raw = message.data ?: return@addWebMessageListener
            if (raw.length <= 16384) receive(raw)
        }
        WebView.setWebContentsDebuggingEnabled(context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
    }
    private inner class LocalWebViewPolicy : LocalWebViewClient.Policy {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
            val session = active ?: return true
            return request.isForMainFrame || !request.url.toString().matches(Regex(Regex.escape(session.resources.base) + "sections/[0-9]+\\.html"))
        }
        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse {
            if (request.method != "GET") return denied()
            val session = active ?: return denied()
            return try {
                val resource = session.resources.resolve(request.url.toString(),request.isForMainFrame) ?: return denied()
                WebResourceResponse(resource.mime,if (resource.mime.startsWith("text/")) "UTF-8" else null,200,"OK",resource.headers,resource.open())
            } catch (e: Exception) {
                view?.post { if (active === session) fail("正文资源读取失败：${e.message}") }; denied()
            }
        }
        override fun onReceivedError(view: WebView?, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame && request.url.toString() == active?.resources?.mainUrl) fail("正文组件加载失败：${error.description}")
        }
        override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest, errorResponse: WebResourceResponse) {
            if (request.isForMainFrame && request.url.toString() == active?.resources?.mainUrl) fail("正文组件返回错误：${errorResponse.statusCode}")
        }
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail) {
            if (!destroyed) { active?.gate?.fail(); watchdog?.cancel(); onEvent(RendererEvent.Crashed("系统正文组件已停止，正在恢复。")) }
            close()
        }
    }
    private fun receive(raw: String) {
        val session = active ?: return
        try {
            val event = JSONObject(raw)
            if (event.optInt("protocol") != 1 || event.optString("sessionId") != session.gate.sessionId || event.optString("revision") != session.publication.revision) return
            if (event.optString("type") == "booted" && !session.booted) {
                session.booted = true; session.pending?.let { dispatch(session,it) }; return
            }
            fun integer(name: String): Int? = (event.opt(name) as? Number)?.toDouble()?.let { if (it.isFinite() && it == it.toInt().toDouble()) it.toInt() else null }
            val identity = RendererIdentity(event.getString("sessionId"),event.getString("revision"),integer("layoutEpoch") ?: return,integer("commandId") ?: return)
            if (!session.gate.accepts(identity)) return
            when (event.optString("type")) {
                "ready" -> {
                    val count = integer("activeViews") ?: return
                    if (count !in 1..8 || !session.gate.ready(identity)) return
                    session.opened = true
                    watchdog?.cancel(); onEvent(RendererEvent.Ready(event.optString("profileId"),count,event.optBoolean("nearby")))
                }
                "relocated" -> {
                    if (!session.gate.ready) return
                    val target = event.optJSONObject("target") ?: return
                    val indexValue = (target.opt("section") as? Number)?.toDouble() ?: return
                    val charValue = (target.opt("character") as? Number)?.toDouble() ?: return
                    if (!indexValue.isFinite() || indexValue != indexValue.toInt().toDouble() || !charValue.isFinite() || charValue != charValue.toInt().toDouble()) return
                    val reason = event.optString("reason", "scroll")
                    val block = target.optString("blockId")
                    if (block.length !in 1..128) return
                    val positionSerial = session.gate.beginPosition(identity) ?: return
                    scope.launch {
                        try {
                            val locator = withContext(Dispatchers.IO) { session.publication.locator(indexValue.toInt(),block,charValue.toInt()) }
                            if (active === session && session.gate.acceptsPosition(identity,positionSerial) && locator != null) {
                                session.locator = locator
                                onEvent(RendererEvent.Position(locator, reason))
                            }
                        } catch (_: Exception) { /* closed or malformed content capability */ }
                    }
                }
                "error" -> fail("正文排版失败：${event.optString("message").take(300)}")
                "layoutInvalidated" -> if (session.gate.ready) command(session,"setRules",session.locator,session.rules)
            }
        } catch (_: Exception) { /* malformed event is rejected */ }
    }
    /** Compose updates adapt into typed commands rather than document reloads. */
    fun render(document: ReaderPublication, anchor: Locator, jumpSerial: Int, rules: ReaderRules) {
        if (destroyed) return
        val nextKey = "${document.revision}:$jumpSerial:$rules"
        if (key == nextKey && active?.publication === document) return
        key = nextKey
        if (active?.publication !== document) open(document,anchor,rules)
        else if (rules != currentRules) setRules(rules,anchor)
        else goTo(anchor)
    }
    private var currentRules = ReaderRules()
    override fun open(document: ReaderPublication, locator: Locator, rules: ReaderRules) {
        if (destroyed) return
        if (!bridgeSupported) { fail("系统 WebView 缺少安全正文通信功能，请更新系统 WebView。"); return }
        work?.cancel(); watchdog?.cancel(); active?.resources?.close(); active = null; view.stopLoading()
        val id = UUID.randomUUID().toString()
        val resources = SessionResources(id,document) { view.context.assets.open(it) }
        val session = HostSession(document,resources,RendererEventGate(id,document.revision),locator,rules)
        active = session; currentRules = rules
        command(session,"open",locator,rules)
        view.loadUrl(resources.mainUrl)
    }
    override fun goTo(locator: Locator) { active?.let { command(it,"goTo",locator,null) } }
    override fun setRules(rules: ReaderRules, locator: Locator) { currentRules = rules; active?.let { command(it,"setRules",locator,rules) } }
    override fun next() { active?.let { command(it,"next",null,null) } }
    override fun prev() { active?.let { command(it,"prev",null,null) } }
    private fun command(session: HostSession, type: String, locator: Locator?, rules: ReaderRules?) {
        if (destroyed) return
        work?.cancel(); watchdog?.cancel()
        if (locator != null) session.locator = locator
        if (rules != null) session.rules = rules
        // Before the open barrier, subsequent reflow/jumps update the initial open payload.
        val effectiveType = if (!session.opened || !session.gate.ready) "open" else type
        val targetLocator = if (effectiveType == "open") session.locator else locator
        val targetRules = if (effectiveType == "open") session.rules else rules
        val identity = session.gate.command()
        onEvent(RendererEvent.Loading)
        work = scope.launch {
            try {
                val payload = JSONObject()
                if (targetLocator != null) {
                    val target = withContext(Dispatchers.IO) { session.publication.target(targetLocator) }
                    payload.put("target",JSONObject().put("section",target.section).put("blockId",target.blockId).put("character",target.character).put("nearby",target.nearby))
                }
                if (targetRules != null) {
                    val faces = withContext(Dispatchers.IO) { view.context.assets.open("fonts/readest.css").bufferedReader().use { it.readText() } }
                    val compiled = RuleCompiler.compile(session.publication.kind,targetRules,"${session.resources.base}fonts/readest.css",faces)
                    payload.put("flow",targetRules.flow.wire).put("profileId",compiled.profileId).put("beforeAuthor",compiled.beforeAuthor).put("afterAuthor",compiled.afterAuthor)
                    val g = compiled.geometry
                    payload.put("geometry",JSONObject().put("gap",g.gap).put("margin-top",g.marginTop).put("margin-bottom",g.marginBottom)
                        .put("margin-left",g.marginLeft).put("margin-right",g.marginRight).put("max-column-count",g.maxColumns))
                }
                if (effectiveType == "open") payload.put("sectionCount",session.publication.sectionCount)
                val value = JSONObject().put("protocol",1).put("sessionId",identity.sessionId).put("revision",identity.revision)
                    .put("layoutEpoch",identity.layoutEpoch).put("commandId",identity.commandId).put("type",effectiveType).put("payload",payload)
                if (active !== session || !session.gate.accepts(identity)) return@launch
                session.pending = value
                if (session.booted) dispatch(session,value)
                watchdog = scope.launch { delay(15000); if (active === session && session.gate.accepts(identity) && !session.gate.ready) fail("正文加载或重排超时，请返回书架重试。") }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (active === session) fail("正文定位失败：${e.message}") }
        }
    }
    private fun dispatch(session: HostSession, value: JSONObject) {
        if (active === session && !destroyed) view.evaluateJavascript("window.readerHost?.command($value);void 0",null)
    }
    private fun fail(message: String) { if (!destroyed) { active?.gate?.fail(); watchdog?.cancel(); onEvent(RendererEvent.Error(message)) } }
    override fun close() {
        if (destroyed) return
        destroyed = true; active?.resources?.close(); active = null; work?.cancel(); watchdog?.cancel(); scope.cancel()
        view.keepScreenOn = false
        (view.parent as? android.view.ViewGroup)?.removeView(view)
        view.stopLoading()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) WebViewCompat.removeWebMessageListener(view,"ReaderEvents")
        view.destroy()
    }
    fun destroy() = close()
    private fun denied() = WebResourceResponse("text/plain","UTF-8",403,"Forbidden",emptyMap(),ByteArrayInputStream(ByteArray(0)))
}
