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
class WebReaderAdapter(context: Context, private val generation: Long=0, private val onEvent: (RendererEvent) -> Unit) : RendererBackend {
    val view = object: WebView(context) {
        override fun onApplyWindowInsets(insets: android.view.WindowInsets): android.view.WindowInsets {
            // The editor lives in a native foreground sheet. Modern WebView
            // otherwise applies its IME to this document's visual viewport too.
            // Forward a real update to the provider, preserving bars and cutouts.
            val documentInsets=androidx.core.view.WindowInsetsCompat.Builder(
                androidx.core.view.WindowInsetsCompat.toWindowInsetsCompat(insets,this)
            ).setInsets(androidx.core.view.WindowInsetsCompat.Type.ime(),androidx.core.graphics.Insets.NONE)
                .setVisible(androidx.core.view.WindowInsetsCompat.Type.ime(),false).build()
            return super.onApplyWindowInsets(requireNotNull(documentInsets.toWindowInsets()))
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var active: HostSession? = null
    private var key: String? = null
    private var work: Job? = null
    private var watchdog: Job? = null
    private var dispatchLayout: android.view.View.OnLayoutChangeListener?=null
    private var destroyed = false
    private val bridgeSupported = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
    private data class QueuedPage(val type: String,val operation: ReaderOperationToken?)
    private var preparedOperation: ReaderOperationToken?=null
    override fun setOperation(operation: ReaderOperationToken?) { if(operation!=null && preparedOperation!==operation) { preparedOperation?.let {AndroidReadingTimings.ledger.finish(it,ReaderOperationOutcome.CANCELED)};preparedOperation=operation } }
    private class HostSession(val publication: ReaderPublication, val resources: SessionResources, val gate: RendererEventGate, var locator: Locator, var rules: ReaderRules,val rootPublication: ReaderPublication=publication,val initialIntent: IntentCause=IntentCause.Restore) {
        var atStart=false
        var atEnd=false
        /** Page progression the engine is actually using now, which may come from the document's CSS. */
        var pageRtl=false
        var operation: ReaderOperationToken?=null
        var booted = false
        /** True until the first Ready, and again after a failure: the next command must rebuild the engine. */
        var needsOpen = true
        var pending: JSONObject? = null
        var intent=IntentCause.Restore
        var intentToken=""
        var userIntentToken=""
        var positionValidated=false
        var paginationBase=""
        val pageQueue=java.util.ArrayDeque<QueuedPage>()
    }
    /** Current page turns right-to-left, as reported by the engine with the last position. */
    val pageRtl: Boolean get() = active?.pageRtl == true
    /** In a mixed EPUB a fixed-layout page is current: it pans and zooms instead of turning with animation. */
    val onFixedPage: Boolean get() = active?.locator is Locator.Page
    val currentUrl: String? get() = active?.resources?.mainUrl
    val cachedResourceBytes: Int get() = active?.resources?.cachedBytes ?: 0
    init {
        view.keepScreenOn = false
        view.setBackgroundColor(Color.TRANSPARENT)
        view.settings.apply {
            javaScriptEnabled = true; allowFileAccess = false; allowContentAccess = false
            // HTTPS-only documents; file/content access is disabled above. Deprecated
            // file-origin cross-access switches already default to false for target >=16.
            // Every request is intercepted locally; HTTP caching only lets immutable bundled fonts be reused across documents.
            blockNetworkLoads = true; blockNetworkImage = false; cacheMode = WebSettings.LOAD_DEFAULT
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
            if (raw.length <= 65536) receive(raw)
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
                view?.post { if (active === session) fail(UiStrings.text(com.localreader.app.R.string.body_open_failed)+"："+UiStrings.safeMessage(e.message.orEmpty())) }; denied()
            }
        }
        override fun onReceivedError(view: WebView?, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame && request.url.toString() == active?.resources?.mainUrl) fail(UiStrings.text(com.localreader.app.R.string.body_open_failed)+"："+UiStrings.safeMessage(error.description.toString()))
        }
        override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest, errorResponse: WebResourceResponse) {
            if (request.isForMainFrame && request.url.toString() == active?.resources?.mainUrl) fail(UiStrings.text(com.localreader.app.R.string.body_open_failed)+"：${errorResponse.statusCode}")
        }
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail) {
            if (!destroyed) { cancelOperation(active,ReaderOperationOutcome.FAILED);active?.gate?.fail(); watchdog?.cancel(); onEvent(RendererEvent.Crashed(UiStrings.text(com.localreader.app.R.string.renderer_crashed))) }
            close()
        }
    }
    private fun receive(raw: String) {
        val session = active ?: return
        try {
            val event = JSONObject(raw)
            if (event.optInt("protocol") != session.publication.protocolVersion || event.optString("sessionId") != session.gate.sessionId || event.optString("revision") != session.publication.revision) return
            if (event.optString("type") == "booted" && !session.booted) {
                session.booted = true; session.pending?.let { dispatch(session,it) }; return
            }
            fun integer(name: String): Int? = (event.opt(name) as? Number)?.toDouble()?.let { if (it.isFinite() && it == it.toInt().toDouble()) it.toInt() else null }
            val identity = RendererIdentity(event.getString("sessionId"),event.getString("revision"),integer("layoutEpoch") ?: return,integer("commandId") ?: return)
            if (!session.gate.accepts(identity)) return
            if(session.publication.protocolVersion==2 && event.optString("intentToken")!=session.intentToken)return
            when (event.optString("type")) {
                "ready" -> {
                    val count = integer("activeViews") ?: return
                    if (count !in 1..(if(session.publication.kind==WebContentKind.FIXED)4 else 8) || !session.gate.ready(identity)) return
                    session.needsOpen = false
                    onEvent(RendererEvent.Ready(event.optString("profileId"),count,event.optBoolean("nearby")))
                    session.publication.compatibilityNotices.take(3).takeIf { it.isNotEmpty() }?.let { messages -> onEvent(RendererEvent.Notice(messages.joinToString("\n") { LocatorCodec.safePrefix(UiStrings.safeMessage(it),180) })) }
                }
                "relocated" -> {
                    if (!session.gate.ready) return
                    val target = event.optJSONObject("target") ?: return
                    fun targetInteger(name: String): Int? = (target.opt(name) as? Number)?.toDouble()?.let { if(it.isFinite() && it==it.toInt().toDouble())it.toInt() else null }
                    val section=targetInteger("section") ?: return
                    if(section !in 0 until session.publication.sectionCount)return
                    val anchor=if(session.publication.protocolVersion==1) {
                        val block=target.optString("blockId");if(block.length !in 1..128)return
                        RendererAnchor.Block(section,block,targetInteger("character") ?: return)
                    } else when(target.optString("kind")) {
                        "TEXT" -> RendererAnchor.Text(section,targetInteger("offset") ?: return)
                        "PAGE" -> {
                            val x=(target.opt("x") as? Number)?.toDouble() ?: return;val y=(target.opt("y") as? Number)?.toDouble() ?: return
                            if(!x.isFinite() || !y.isFinite() || x !in 0.0..1.0 || y !in 0.0..1.0 || session.publication.kind!=WebContentKind.FIXED)return
                            RendererAnchor.Page(section,x,y)
                        }
                        "ELEMENT" -> RendererAnchor.Element(section,target.optString("elementId").takeIf { it.length in 1..128 } ?: return)
                        else -> return
                    }
                    var intent=session.intent
                    val reason=if(session.publication.protocolVersion==2) {
                        val reported=IntentCause.entries.firstOrNull { it.name==event.optString("intentCause") } ?: return
                        if(reported!=session.intent && !(reported==IntentCause.UserNavigation && event.optBoolean("trustedInput") && session.userIntentToken.isNotEmpty() && event.optString("userIntentToken")==session.userIntentToken))return
                        intent=reported
                        if(reported==IntentCause.UserNavigation)event.optString("reason","scroll").takeIf { it in setOf("page","scroll") } ?: return else "anchor"
                    } else when(event.optString("reason","scroll")) {
                        // Foliate reports swipes as "snap" and boundary crossings as "navigation".
                        "scroll","container-scroll" -> "scroll"
                        "page","snap","navigation" -> "page"
                        "anchor","selection" -> "anchor"
                        else -> return
                    }
                    if(session.publication.protocolVersion==1)intent=if(reason=="anchor")IntentCause.ProgrammaticJump else IntentCause.UserNavigation
                    val positionSerial = session.gate.beginPosition(identity) ?: return
                    scope.launch {
                        try {
                            val locator = withContext(Dispatchers.IO) { session.publication.locator(anchor) }
                            if (active === session && session.gate.acceptsPosition(identity,positionSerial) && locator != null) {
                                val viewport=withContext(Dispatchers.IO) { decodeViewport(session,event.optJSONObject("viewport")) }
                                if(active!==session || !session.gate.acceptsPosition(identity,positionSerial))return@launch
                                session.locator=locator;session.positionValidated=true;session.atStart=event.optBoolean("atStart");session.atEnd=event.optBoolean("atEnd");session.pageRtl=event.optString("pageDirection")=="rtl";watchdog?.cancel()
                                onEvent(RendererEvent.Position(locator, reason,intent,viewport,session.operation,identity))
                                turnHook?.settle()
                                if(session.pageQueue.isNotEmpty())session.pageQueue.removeFirst().let {page(it.type,it.operation)}
                            } else if(active===session && session.gate.acceptsPosition(identity,positionSerial) && locator==null)fail(UiStrings.text(com.localreader.app.R.string.body_open_failed))
                        } catch(e: Exception) { if(active===session && session.gate.acceptsPosition(identity,positionSerial))fail(UiStrings.text(com.localreader.app.R.string.body_open_failed)+"："+UiStrings.safeMessage(e.message.orEmpty())) }
                    }
                }
                "tapRequested" -> {
                    if(session.gate.ready && session.positionValidated && event.optBoolean("trustedInput")) {
                        TapRegion.entries.firstOrNull { it.name==event.optString("region") }?.let { onEvent(RendererEvent.Tap(it,event.optString("direction")=="rtl")) }
                    }
                }
                "selectionCleared" -> { if(session.gate.ready && session.positionValidated) { session.gate.clearSelection();onEvent(RendererEvent.Selection(null)) } }
                "blockSelectionRequested" -> {
                    if(!session.gate.ready || !session.positionValidated || session.publication.protocolVersion!=1)return
                    fun anchor(key: String): RendererAnchor.Block {
                        val a=event.getJSONObject(key);val section=a.getInt("section");val character=a.getInt("character")
                        require(section in 0 until session.publication.sectionCount && character>=0)
                        return RendererAnchor.Block(section,a.getString("blockId").also { require(it.length in 1..128) },character)
                    }
                    val start=anchor("start");val end=anchor("end");val text=event.getString("text");val serial=session.gate.beginSelection(identity) ?: return
                    require(text.length in 1..2000 && LocatorCodec.wellFormed(text))
                    scope.launch {
                        try {
                            val mapped=withContext(Dispatchers.IO) { val a=session.publication.locator(start);val b=session.publication.locator(end);if(a!=null && b!=null)ReaderSelection(text,a,b) else null }
                            if(active===session && session.gate.acceptsSelection(identity,serial) && session.positionValidated && mapped!=null)onEvent(RendererEvent.Selection(mapped))
                        } catch(cancel: CancellationException) { throw cancel }
                        catch(_: Exception) { /* A retired or invalid source selection cannot crash the host. */ }
                    }
                }
                "pageRequested" -> {
                    if(session.gate.ready && session.publication.kind==WebContentKind.FIXED && session.userIntentToken.isNotEmpty() && event.optString("userIntentToken")==session.userIntentToken) {
                        event.optString("direction").takeIf { it in setOf("next","prev") }?.let(::page)
                    }
                }
                "zoomRequested" -> {
                    if(!session.gate.ready || session.publication.kind!=WebContentKind.FIXED || session.userIntentToken.isEmpty() || event.optString("userIntentToken")!=session.userIntentToken)return
                    val zoom=event.opt("zoom")?.toString()?.takeIf { it=="fit-page" || it.toDoubleOrNull()?.let { n -> n.isFinite() && n in 1.0..5.0 }==true } ?: return
                    val target=event.optJSONObject("target") ?: return
                    val section=(target.opt("section") as? Number)?.toDouble()?.takeIf { it.isFinite() && it==it.toInt().toDouble() && it.toInt() in 0 until session.publication.sectionCount }?.toInt() ?: return
                    val x=(target.opt("x") as? Number)?.toDouble()?.takeIf { it.isFinite() && it in 0.0..1.0 } ?: return
                    val y=(target.opt("y") as? Number)?.toDouble()?.takeIf { it.isFinite() && it in 0.0..1.0 } ?: return
                    val serial=session.gate.beginPosition(identity) ?: return
                    scope.launch {
                        val locator=withContext(Dispatchers.IO) { session.publication.locator(RendererAnchor.Page(section,x,y)) as? Locator.Page }
                        if(active===session && session.gate.acceptsPosition(identity,serial) && locator!=null) {
                            session.locator=locator
                            onEvent(RendererEvent.Zoom(locator,zoom))
                        }
                    }
                }
                "userIntent" -> {
                    if(session.gate.ready && event.optString("inputType") in setOf("wheel","touchmove","pointerdown","keydown")) {
                        val token=event.optString("userIntentToken")
                        if(token.matches(Regex("[a-zA-Z0-9-]{1,64}")))session.userIntentToken=token
                    }
                }
                "linkRequested","externalRequested","imageRequested","selectionRequested" -> {
                    if(!session.gate.ready || !session.positionValidated)return
                    val section=(event.opt("section") as? Number)?.toDouble()?.takeIf { it.isFinite() && it==it.toInt().toDouble() && it.toInt() in 0 until session.publication.sectionCount }?.toInt() ?: return
                    val sourceSection=section+session.publication.sectionOffset
                    val request=when(event.getString("type")) {
                        "linkRequested","externalRequested" -> {
                            val href=event.optString("href");if(href.length !in 1..4096)return
                            ContentInteraction.Link(sourceSection,href,event.getString("type")=="externalRequested")
                        }
                        "imageRequested" -> {
                            if(session.publication.kind==WebContentKind.FIXED)return
                            val id=event.optString("elementId");if(id.length !in 1..128)return
                            ContentInteraction.Image(sourceSection,id)
                        }
                        else -> {
                            fun offset(key: String): Int {
                                val value=(event.get(key) as? Number)?.toDouble() ?: error("Invalid selection offset")
                                require(value.isFinite() && value>=0 && value<=Int.MAX_VALUE && value==value.toInt().toDouble());return value.toInt()
                            }
                            val start=offset("start");val end=offset("end");val text=event.getString("text")
                            require(end>start && end-start<=4096 && text.length in 1..2000 && LocatorCodec.wellFormed(text))
                            val ranges=VisibleRangeCodec.decode(event.getString("selectionRanges"))
                            require(ranges.isNotEmpty() && ranges.size<=4096 && ranges.all { it.start>=start && it.end<=end })
                            val rect=event.getJSONObject("rect")
                            listOf("left","top","width","height").forEach { key -> require(rect.getDouble(key).isFinite() && kotlin.math.abs(rect.getDouble(key))<=1000000) }
                            require(rect.getDouble("width")>0 && rect.getDouble("height")>0)
                            ContentInteraction.Selection(sourceSection,RendererAnchor.Text(sourceSection,start),RendererAnchor.Text(sourceSection,end),text,ranges)
                        }
                    }
                    onEvent(RendererEvent.Interaction(request))
                }
                "notice" -> onEvent(RendererEvent.Notice(event.optString("message").take(300)))
                "error" -> fail(UiStrings.text(com.localreader.app.R.string.page_parse_error)+"："+UiStrings.safeMessage(event.optString("message").take(300)),event.optBoolean("retryable",true))
                "layoutInvalidated" -> if (session.gate.ready) command(session,"setRules",session.locator,session.rules)
            }
        } catch (_: Exception) { /* malformed event is rejected */ }
    }
    private fun decodeViewport(session: HostSession,json: JSONObject?): ReadingViewport? {
        if(json==null)return null
        require(json.getString("base")==session.paginationBase)
        val signature=json.getString("signature");require(signature.matches(Regex("[0-9a-f]{1,8}-[0-9a-f]{1,8}")))
        fun integer(value: JSONObject,key: String): Int {
            val number=(value.get(key) as Number).toDouble();require(number.isFinite() && number==number.toInt().toDouble());return number.toInt()
        }
        fun anchor(value: JSONObject): RendererAnchor {
            val section=integer(value,"section");require(section in 0 until session.publication.sectionCount)
            if(session.publication.protocolVersion==1)return RendererAnchor.Block(section,value.getString("blockId").also { require(it.length in 1..128) },integer(value,"character"))
            return when(value.getString("kind")) {
                "TEXT" -> RendererAnchor.Text(section,integer(value,"offset"))
                "ELEMENT" -> RendererAnchor.Element(section,value.getString("elementId").also { require(it.length in 1..128) })
                else -> error("Invalid visible source anchor")
            }
        }
        val current=integer(json,"currentSection");require(current in 0 until session.publication.sectionCount)
        val rawSamples=json.getJSONArray("samples");require(rawSamples.length()<=8)
        val samples=(0 until rawSamples.length()).map { i -> val row=rawSamples.getJSONObject(i);val part=integer(row,"section");val page=integer(row,"page");val pages=integer(row,"pages");require(part in 0 until session.publication.sectionCount && page in 1..pages && pages in 1..1000000);val local=row.getString("signature");require(local.matches(Regex("[0-9a-f]{1,8}-[0-9a-f]{1,8}")))
            val units=row.optDouble("units",pages.toDouble());val offset=row.optDouble("offset",(page-1).toDouble());require(units.isFinite() && units>0 && units<=1000000 && offset.isFinite() && offset>=0 && offset<=units)
            PaginationSample(part,page,pages,row.getBoolean("estimated"),digest(session.paginationBase+":"+local),units,offset,row.optBoolean("scrolled")) }
        require(samples.map { it.section }.distinct().size==samples.size && samples.any { it.section==current })
        val rawRanges=json.getJSONArray("ranges");require(rawRanges.length()<=256)
        fun mappedRange(start: RendererAnchor,end: RendererAnchor): VisibleSourceRange {
            require(start.section==end.section && (session.publication.protocolVersion==1 || start is RendererAnchor.Text && end is RendererAnchor.Text))
            val first=requireNotNull(session.publication.locator(start));val last=requireNotNull(session.publication.locator(end))
            require(when(first) { is Locator.Text -> last is Locator.Text && first.sourceOffset<last.sourceOffset;is Locator.Epub -> last is Locator.Epub && first.spine==last.spine && first.offset<last.offset;is Locator.EpubResource -> last is Locator.EpubResource && first.docKey==last.docKey && first.offset<last.offset;is Locator.Article -> !first.element && last is Locator.Article && !last.element && first.blockId==last.blockId && first.textOffset<last.textOffset;else -> false });return VisibleSourceRange(first,last)
        }
        val ranges=(0 until rawRanges.length()).map { i -> val row=rawRanges.getJSONObject(i);mappedRange(anchor(row.getJSONObject("start")),anchor(row.getJSONObject("end"))) }.toMutableList()
        json.optJSONArray("rangeGroups")?.let { groups ->
            require(rawRanges.length()==0 && groups.length() in 1..256 && session.publication.protocolVersion in 1..2)
            var totalBytes=0;var totalIntervals=0
            val seen=HashSet<Pair<Int,String?>>()
            for(i in 0 until groups.length()) {
                val row=groups.getJSONObject(i);val section=integer(row,"section");require(section in 0 until session.publication.sectionCount)
                val block=if(session.publication.protocolVersion==1)row.getString("blockId").also { require(it.length in 1..128) } else null
                require(seen.add(section to block) && (block!=null || !row.has("blockId")))
                val encoded=row.getString("encoded");require(encoded.length%4==0)
                totalBytes+=encoded.length/4*3-(if(encoded.endsWith("=="))2 else if(encoded.endsWith("="))1 else 0)
                require(totalBytes<=VisibleRangeCodec.MAX_BYTES)
                val intervals=VisibleRangeCodec.decode(encoded);require(intervals.isNotEmpty())
                totalIntervals+=intervals.size;require(totalIntervals<=VisibleRangeCodec.MAX_INTERVALS)
                for(interval in intervals) {
                    val start=if(block==null)RendererAnchor.Text(section,interval.start) else RendererAnchor.Block(section,block,interval.start)
                    val end=if(block==null)RendererAnchor.Text(section,interval.end) else RendererAnchor.Block(section,block,interval.end)
                    ranges.add(mappedRange(start,end))
                }
            }
        }
        val rawElements=json.getJSONArray("elements");require(rawElements.length()<=256)
        fun mappedElement(value: RendererAnchor): Locator {
            val mapped=requireNotNull(session.publication.locator(value))
            require(mapped is Locator.EpubResource && mapped.elementId!=null || mapped is Locator.Epub && mapped.elementId!=null || mapped is Locator.Article && mapped.element);return mapped
        }
        val elements=(0 until rawElements.length()).map {
            mappedElement(anchor(rawElements.getJSONObject(it)))
        }.toMutableList()
        json.optJSONArray("elementGroups")?.let { groups ->
            require(rawElements.length()==0 && groups.length() in 1..8 && session.publication.protocolVersion in 1..2)
            val seen=HashSet<Int>();var count=0;var encodedUnits=0
            for(i in 0 until groups.length()) {
                val row=groups.getJSONObject(i);val section=integer(row,"section");require(section in 0 until session.publication.sectionCount && seen.add(section))
                val ids=row.getJSONArray("ids");require(ids.length() in 1..16384);count+=ids.length();require(count<=16384)
                val unique=HashSet<String>()
                for(n in 0 until ids.length()) {
                    val id=ids.getString(n);require(id.length in 1..128 && unique.add(id));encodedUnits+=JSONObject.quote(id).length+1;require(encodedUnits<=40960)
                    elements.add(mappedElement(if(session.publication.protocolVersion==1)RendererAnchor.Block(section,id,0) else RendererAnchor.Element(section,id)))
                }
            }
        }
        val rawPoints=json.getJSONArray("points");require(rawPoints.length()<=256)
        val points=(0 until rawPoints.length()).mapNotNull {
            val point=anchor(rawPoints.getJSONObject(it));val mapped=requireNotNull(session.publication.locator(point))
            require(mapped is Locator.Text || mapped is Locator.EpubResource && mapped.elementId==null || mapped is Locator.Epub && mapped.elementId==null || mapped is Locator.Article && !mapped.element)
            session.publication.visiblePoint(point)
        }
        val rawPages=json.getJSONArray("visiblePages");require(rawPages.length()<=4)
        val pages=(0 until rawPages.length()).map { i -> val value=rawPages.get(i) as Number;val page=value.toInt();require(value.toDouble()==page.toDouble() && page in 0 until session.publication.sectionCount);page }
        require(pages.distinct().size==pages.size && (session.publication.kind==WebContentKind.FIXED || pages.isEmpty()))
        val atEnd=json.getBoolean("atEnd")
        require(!atEnd || if(session.publication.kind==WebContentKind.FIXED)session.publication.sectionCount-1 in pages else samples.any { it.section==session.publication.sectionCount-1 })
        val fixed=json.optJSONObject("fixed")?.let { value ->
            require(session.publication.kind==WebContentKind.FIXED)
            FxlViewState(value.getDouble("zoom"),value.getString("spread"),pages,value.getBoolean("overflowX"),value.getBoolean("overflowY"))
        }
        val offset=session.publication.sectionOffset
        val sourcePages=pages.map { page -> (session.publication.locator(RendererAnchor.Page(page,0.0,0.0)) as? Locator.Page)?.page ?: page+offset }
        return ReadingViewport(session.publication.sourceRevision,current+offset,digest(session.paginationBase+":"+signature),samples.map { it.copy(section=it.section+offset) },ranges,elements,sourcePages,atEnd && !session.publication.auxiliary && session.publication.finalRenderingGroup,points,fixed?.copy(visiblePages=sourcePages))
    }
    private fun digest(value: String)=java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    companion object {
        @Volatile private var cachedFaces: String?=null
        private fun fontFaces(context: Context)=cachedFaces ?: context.assets.open("fonts/readest.css").bufferedReader().use { it.readText() }.also { cachedFaces=it }
        // Bundled fonts only change with an app update.
        private val fontSignature="${com.localreader.app.BuildConfig.VERSION_CODE}:${com.localreader.app.BuildConfig.VERSION_NAME}:${BundledFonts.files.sorted().joinToString(",")}"
    }
    /** Compose updates adapt into typed commands rather than document reloads. */
    fun render(document: ReaderPublication, anchor: Locator, jumpSerial: Int, rules: ReaderRules) {
        if (destroyed) return
        val nextKey = "${document.revision}:$jumpSerial:$rules"
        if (key == nextKey && active?.rootPublication === document) return
        key = nextKey
        if (active?.rootPublication !== document) open(document,anchor,rules)
        else if (rules != currentRules) setRules(rules,anchor)
        else goTo(anchor)
    }
    private var insets=ViewportInsets()
    override fun setViewportInsets(insets: ViewportInsets) {
        if(destroyed || this.insets==insets)return
        this.insets=insets
        active?.let { command(it,"setRules",it.locator,it.rules) }
    }
    private var currentRules = ReaderRules()
    override fun open(document: ReaderPublication, locator: Locator, rules: ReaderRules) = openRendering(document,locator,rules,IntentCause.Restore)
    private fun openRendering(root: ReaderPublication,locator: Locator,rules: ReaderRules,cause: IntentCause,operation: ReaderOperationToken?=null) {
        val document=root.renderingPublication(locator)
        if (destroyed) return
        if (!bridgeSupported) { fail(UiStrings.text(com.localreader.app.R.string.renderer_bridge_missing),false); return }
        clearPages(active);cancelOperation(active,ReaderOperationOutcome.CANCELED);cancelDeferredDispatch();work?.cancel(); watchdog?.cancel(); active?.resources?.close(); active = null; view.stopLoading()
        val id = UUID.randomUUID().toString()
        document.configure(rules)
        val resources = SessionResources(id,document) { view.context.assets.open(it) }
        val session = HostSession(document,resources,RendererEventGate(id,document.revision),locator,rules,root,cause)
        active = session; currentRules = rules
        command(session,"open",locator,rules,operation)
        view.loadUrl(resources.mainUrl)
    }
    override fun goTo(locator: Locator) { active?.let {
        if(it.rootPublication.renderingPublication(locator)!==it.publication)openRendering(it.rootPublication,locator,it.rules,IntentCause.ProgrammaticJump)
        else { clearPages(it);command(it,"goTo",locator,null) }
    } }
    override fun setRules(rules: ReaderRules, locator: Locator) {
        val session=active ?: return
        if(session.publication.protocolVersion==2 && session.rules.epubStyleMode!=rules.epubStyleMode) {
            openRendering(session.rootPublication,locator,rules,IntentCause.Reflow) // revoke old HTML/resources before changing the rendering copy
        } else { currentRules=rules;clearPages(session);command(session,"setRules",locator,rules) }
    }
    // Foreground governs the screen-on lease. A backend embedded by a core
    // consumer must still draw before that consumer declares lifecycle state.
    override fun setForeground(active: Boolean) { if(!destroyed)view.keepScreenOn=active }
    override fun next() = page("next")
    override fun prev() = page("prev")
    override fun next(operation: ReaderOperationToken?) = page("next",operation)
    override fun prev(operation: ReaderOperationToken?) = page("prev",operation)
    /** Set by the surface; drawn before and after every turn so the animation covers taps, keys and swipes alike. */
    var turnHook: PageTurnHook?=null
    private fun page(type: String,operation: ReaderOperationToken?=null) {
        val session=active ?: run {operation?.let {AndroidReadingTimings.ledger.finish(it,ReaderOperationOutcome.CANCELED)};return}
        turnHook?.begin(type=="next")
        if(session.gate.ready && (if(type=="next")session.atEnd else session.atStart)) {
            val adjacent=session.rootPublication.adjacentRenderingLocator(session.locator,type=="next")
            if(adjacent!=null) { openRendering(session.rootPublication,adjacent,session.rules,IntentCause.UserNavigation,operation);return }
        }
        val accepted=operation ?: AndroidReadingTimings.ledger.begin(ReaderOperationKind.PAGE,generation,origin=session.locator)
        if(session.publication.protocolVersion==2 && !session.gate.ready) {
            if(session.pageQueue.size<20)session.pageQueue.addLast(QueuedPage(type,accepted))
            else AndroidReadingTimings.ledger.finish(accepted,ReaderOperationOutcome.FAILED)
        } else command(session,type,null,null,accepted)
    }
    private fun command(session: HostSession, type: String, locator: Locator?, rules: ReaderRules?, operation: ReaderOperationToken?=null) {
        if (destroyed) return
        cancelDeferredDispatch();session.pending=null;work?.cancel(); watchdog?.cancel()
        val previousLocator=session.locator
        if (locator != null) session.locator = locator
        // A flow change replaces the host engine, so it is rebuilt like an open.
        if (session.publication.protocolVersion==1 && rules != null && rules.flow != session.rules.flow) session.needsOpen = true
        if (rules != null) session.rules = rules
        // Before the open barrier, subsequent reflow/jumps update the initial open payload.
        // The host supersedes an in-flight command by id; only a missing or failed engine needs a full open.
        val effectiveType = if (session.needsOpen) "open" else type
        val targetLocator = if (effectiveType == "open") session.locator else locator
        val targetRules = if (effectiveType == "open") session.rules else rules
        session.positionValidated=false
        session.intent=when(type) { "next","prev" -> IntentCause.UserNavigation;"goTo" -> IntentCause.ProgrammaticJump;"setRules" -> IntentCause.Reflow;else -> session.initialIntent }
        session.intentToken=UUID.randomUUID().toString()
        session.userIntentToken=""
        cancelOperation(session,ReaderOperationOutcome.CANCELED)
        val identity = session.gate.command(session.publication.protocolVersion==1 || effectiveType !in setOf("next","prev"))
        session.operation=operation ?: preparedOperation?.also {preparedOperation=null} ?: AndroidReadingTimings.ledger.begin(when(type) {
            "next","prev" -> ReaderOperationKind.PAGE
            "setRules" -> ReaderOperationKind.REFLOW
            "goTo" -> ReaderOperationKind.JUMP
            else -> ReaderOperationKind.RENDER
        },generation,identity,previousLocator,locator)
        session.operation?.let {AndroidReadingTimings.ledger.bind(it,generation,identity,targetLocator,previousLocator)}
        onEvent(RendererEvent.Loading)
        work = scope.launch {
            try {
                val payload = JSONObject()
                if (targetLocator != null) {
                    val target = withContext(Dispatchers.IO) { session.publication.target(targetLocator) }
                    val value=JSONObject().put("section",target.section).put("nearby",target.nearby)
                    when(val anchor=target.anchor) {
                        is RendererAnchor.Block -> value.put("blockId",anchor.blockId).put("character",anchor.character)
                        is RendererAnchor.Text -> value.put("kind","TEXT").put("offset",anchor.offset)
                        is RendererAnchor.Element -> value.put("kind","ELEMENT").put("elementId",anchor.elementId)
                        is RendererAnchor.Page -> value.put("kind","PAGE").put("x",anchor.x).put("y",anchor.y)
                    }
                    payload.put("target",value)
                }
                if (targetRules != null) {
                    val faces = withContext(Dispatchers.IO) { fontFaces(view.context) }
                    val compiled = RuleCompiler.compile(session.publication.kind,targetRules,SessionResources.FONT_CSS_URL,faces)
                    val metadata=session.publication.resourceManifest.entries.sortedBy { it.key }.joinToString { "${it.key}:${it.value.length}:${it.value.mime}" }
                    session.paginationBase=digest("${session.publication.sourceRevision}|${session.publication.revision}|$targetRules|${compiled.beforeAuthor}|${compiled.afterAuthor}|$fontSignature|$metadata|${view.settings.textZoom}|${session.publication.direction}|$insets")
                    payload.put("paginationBase",session.paginationBase)
                    payload.put("zoom",targetRules.fxlZoom).put("spread",targetRules.spreadPreference).put("theme",targetRules.theme)
                    payload.put("flow",targetRules.flow.wire).put("profileId",compiled.profileId).put("beforeAuthor",compiled.beforeAuthor).put("afterAuthor",compiled.afterAuthor)
                    // Width is supplied only at dispatch, after actual native
                    // layout. An unmeasured width is never a one-pixel viewport.
                    payload.put("nativeInsets",JSONObject())
                    val g = compiled.geometry
                    payload.put("geometry",JSONObject().put("gap",g.gap).put("margin-top",g.marginTop).put("margin-bottom",g.marginBottom)
                        .put("margin-left",g.marginLeft).put("margin-right",g.marginRight).put("max-column-count",g.maxColumns))
                }
                if (effectiveType == "open") {
                    payload.put("sectionCount",session.publication.sectionCount).put("direction",session.publication.direction)
                    session.publication.fixedLayout?.let { fixed -> payload.put("fixedMixed",fixed.mixed).put("fixedPages",org.json.JSONArray(fixed.pages.map { page -> JSONObject().put("index",page.index).put("width",page.width).put("height",page.height).put("pageSpread",page.pageSpread ?: "").put("spread",page.spread) })) }
                }
                val value = JSONObject().put("protocol",session.publication.protocolVersion).put("sessionId",identity.sessionId).put("revision",identity.revision)
                    .put("layoutEpoch",identity.layoutEpoch).put("commandId",identity.commandId).put("type",effectiveType).put("payload",payload)
                    .also { if(session.publication.protocolVersion==2)it.put("intentCause",session.intent.name).put("intentToken",session.intentToken) }
                if (active !== session || !session.gate.accepts(identity)) return@launch
                session.pending = value
                if (session.booted) dispatch(session,value)
                watchdog = scope.launch { delay(15000); if (active === session && session.gate.accepts(identity) && !session.positionValidated) fail(UiStrings.text(com.localreader.app.R.string.renderer_timeout)) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (active === session) fail(UiStrings.text(com.localreader.app.R.string.body_open_failed)+"："+UiStrings.safeMessage(e.message.orEmpty())) }
        }
    }
    private fun cancelDeferredDispatch() {
        dispatchLayout?.let(view::removeOnLayoutChangeListener)
        dispatchLayout=null
    }
    private fun dispatch(session: HostSession, value: JSONObject) {
        val identity=RendererIdentity(value.getString("sessionId"),value.getString("revision"),value.getInt("layoutEpoch"),value.getInt("commandId"))
        if(active!==session || destroyed || !session.booted || session.pending!==value || !session.gate.accepts(identity))return
        if(view.width<=0 || view.height<=0) {
            cancelDeferredDispatch()
            val listener=object: android.view.View.OnLayoutChangeListener {
                override fun onLayoutChange(changed: android.view.View,left: Int,top: Int,right: Int,bottom: Int,
                    oldLeft: Int,oldTop: Int,oldRight: Int,oldBottom: Int) {
                    if(dispatchLayout===this && view.width>0 && view.height>0) {
                        cancelDeferredDispatch()
                        dispatch(session,value)
                    }
                }
            }
            dispatchLayout=listener
            view.addOnLayoutChangeListener(listener)
            // The existing command watchdog also bounds this layout wait.
            return
        }
        cancelDeferredDispatch()
        val payload=value.getJSONObject("payload")
        if(payload.has("nativeInsets"))payload.put("nativeInsets",JSONObject().put("left",insets.left).put("top",insets.top)
            .put("right",insets.right).put("bottom",insets.bottom).put("width",view.width))
        session.pending=null
        view.evaluateJavascript("window.readerHost?.command($value);void 0",null)
    }
    private fun clearPages(session: HostSession?) {
        session?.pageQueue?.forEach {it.operation?.let {token -> AndroidReadingTimings.ledger.finish(token,ReaderOperationOutcome.CANCELED)}}
        session?.pageQueue?.clear()
    }
    private fun cancelOperation(session: HostSession?,outcome: ReaderOperationOutcome) {
        session?.operation?.let {AndroidReadingTimings.ledger.finish(it,outcome)}
        session?.operation=null
    }
    private fun fail(message: String,retryable: Boolean=true) { if (!destroyed) { active?.needsOpen=true;cancelOperation(active,ReaderOperationOutcome.FAILED);cancelDeferredDispatch();active?.pending=null;active?.gate?.fail(); clearPages(active);preparedOperation?.let {AndroidReadingTimings.ledger.finish(it,ReaderOperationOutcome.FAILED)};preparedOperation=null;watchdog?.cancel(); onEvent(RendererEvent.Error(message,retryable)) } }
    override fun close() {
        if (destroyed) return
        destroyed = true;clearPages(active);preparedOperation?.let {AndroidReadingTimings.ledger.finish(it,ReaderOperationOutcome.CANCELED)};preparedOperation=null;cancelOperation(active,ReaderOperationOutcome.CANCELED);cancelDeferredDispatch(); active?.gate?.clearSelection();active?.resources?.close(); active = null; work?.cancel(); watchdog?.cancel(); scope.cancel()
        view.keepScreenOn = false
        (view.parent as? android.view.ViewGroup)?.removeView(view)
        view.stopLoading()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) WebViewCompat.removeWebMessageListener(view,"ReaderEvents")
        view.destroy()
    }
    fun destroy() = close()
    private fun denied() = WebResourceResponse("text/plain","UTF-8",403,"Forbidden",emptyMap(),ByteArrayInputStream(ByteArray(0)))
}
