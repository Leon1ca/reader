package com.localreader.app

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.localreader.app.core.*
import com.localreader.app.data.*
import com.localreader.app.formats.epub.*
import com.localreader.app.ui.ReaderImageDecoder
import com.localreader.app.ui.ReaderSurface
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Bounded, read-only routing evidence for genuine instrumentation touches. */
internal object NativeInputProbe {
    fun windows(automation: android.app.UiAutomation): JSONObject {
        val original=automation.serviceInfo.flags
        val needsLease=original and android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS==0
        return try {
            if(needsLease)automation.serviceInfo=automation.serviceInfo.apply { flags=original or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
            JSONObject().put("windows",org.json.JSONArray(automation.windows.map { window ->
                val bounds=android.graphics.Rect();window.getBoundsInScreen(bounds)
                JSONObject().put("id",window.id).put("type",window.type).put("layer",window.layer).put("active",window.isActive).put("focused",window.isFocused)
                    .put("bounds",bounds.toShortString()).put("rootPackage",window.root?.packageName?.toString()).put("rootClass",window.root?.className?.toString())
            }))
        } catch(error: Exception) { JSONObject().put("error",error.toString()) }
        finally { if(needsLease)automation.serviceInfo=automation.serviceInfo.apply { flags=original } }
    }
    val install="""(()=>{
        window.__nativeInputEvents=[];
        const docs=[document,...document.getElementById('renderer').getContents().map(c=>c.doc)];
        for(const doc of docs){
            if(doc.__nativeInputObserved)continue;
            doc.__nativeInputObserved=true;
            for(const type of ['pointerdown','pointerup','pointercancel','touchstart','touchend','touchcancel','click'])doc.addEventListener(type,e=>{
                const a=window.__nativeInputEvents;if(a.length>=24)a.shift();
                const t=e.changedTouches?.[0]??e,x=t.clientX,y=t.clientY;
                a.push({type,trusted:e.isTrusted,detail:e.detail,pointerType:e.pointerType,x,y,target:e.target?.outerHTML?.slice(0,180),
                    hit:Number.isFinite(x)&&Number.isFinite(y)?doc.elementFromPoint(x,y)?.outerHTML?.slice(0,180):null,
                    prevented:e.defaultPrevented,paragraph:e.target?.closest('p')?.outerHTML?.slice(0,180),doc:doc===document?'shell':doc.URL.slice(-120)});
            },{capture:true,passive:true});
        }
        return true;
    })()""".trimIndent()
    val snapshot="""JSON.stringify({events:window.__nativeInputEvents??[],host:readerHost.diagnostics(),
        shell:{width:innerWidth,height:innerHeight,visualWidth:visualViewport?.width,visualHeight:visualViewport?.height},
        docs:document.getElementById('renderer').getContents().map(c=>{const f=c.doc.defaultView.frameElement,r=f.getBoundingClientRect();return {
            section:c.index,url:c.doc.URL.slice(-160),selection:c.doc.getSelection()?.toString().slice(0,100),
            frame:{left:r.left,top:r.top,width:r.width,height:r.height,clientWidth:f.clientWidth,clientHeight:f.clientHeight}}})})""".trimIndent()
}

@RunWith(AndroidJUnit4::class)
class EpubInteractionTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var repository: LibraryRepository
    private val uris=mutableListOf<Uri>()
    @Before fun setup() {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        root=File(target.cacheDir,"interactions-${UUID.randomUUID()}").apply { mkdirs() }
        context=object: ContextWrapper(target) {
            override fun getFilesDir()=File(root,"files").apply { mkdirs() }
            override fun getDatabasePath(name: String)=File(File(root,"databases").apply { mkdirs() },name)
            override fun openOrCreateDatabase(name: String,mode: Int,factory: SQLiteDatabase.CursorFactory?)=SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory)
            override fun openOrCreateDatabase(name: String,mode: Int,factory: SQLiteDatabase.CursorFactory?,handler: DatabaseErrorHandler?)=SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path,factory,handler)
        }
        repository=LibraryRepository(context);repository.recover()
    }
    @After fun cleanup() { repository.db.close();root.deleteRecursively();uris.forEach { context.contentResolver.delete(it,null,null) } }
    private fun fixture(): String {
        val bytes=ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            fun entry(name: String,data: ByteArray) { val crc=CRC32().apply { update(data) };zip.putNextEntry(ZipEntry(name).apply { time=315532800000L;method=ZipEntry.STORED;size=data.size.toLong();compressedSize=size;this.crc=crc.value });zip.write(data);zip.closeEntry() }
            fun text(name: String,text: String)=entry(name,text.toByteArray())
            fun html(body: String)="<html xmlns='http://www.w3.org/1999/xhtml' xmlns:epub='http://www.idpf.org/2007/ops'><head><style>p{margin:1em 0}</style></head><body>$body</body></html>"
            text("mimetype","application/epub+zip")
            text("META-INF/container.xml","<container xmlns='urn:oasis:names:tc:opendocument:xmlns:container'><rootfiles><rootfile full-path='OPS/book.opf' media-type='application/oebps-package+xml'/></rootfiles></container>")
            text("OPS/book.opf","""<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="u"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="u">${UUID.randomUUID()}</dc:identifier><dc:title>原创交互测试</dc:title></metadata><manifest><item id="main" href="main.xhtml" media-type="application/xhtml+xml"/><item id="next" href="next.xhtml" media-type="application/xhtml+xml"/><item id="a" href="notes-a.xhtml" media-type="application/xhtml+xml"/><item id="b" href="notes-b.xhtml" media-type="application/xhtml+xml"/><item id="image" href="image.png" media-type="image/png"/><item id="svg" href="image.svg" media-type="image/svg+xml"/></manifest><spine><itemref idref="main"/><itemref idref="next"/></spine></package>""")
            text("OPS/main.xhtml",html("<p id='origin'>MAIN ORIGIN 中文😀</p><p><a id='note' epub:type='noteref' href='notes-a.xhtml#fn1'>[1]</a> <a id='other' epub:type='noteref' href='notes-b.xhtml#fn1'>[2]</a> <a id='external' href='https://example.com/read'>外链</a></p><p><img id='picture' alt='原创光栅' src='image.png' width='80' height='120'/><img id='vector' src='image.svg' width='40' height='40'/></p><p id='selection' style='text-transform:uppercase'>visible<span style='display:none'>hidden</span> end</p><p>主文末段。可用于不同返回位置。可用于不同返回位置。可用于不同返回位置。可用于不同返回位置。可用于不同返回位置。可用于不同返回位置。可用于不同返回位置。可用于不同返回位置。</p>"))
            text("OPS/next.xhtml",html("<p>SECOND MAIN</p>"))
            text("OPS/notes-a.xhtml",html("<aside id='fn1' epub:type='footnote' hidden='hidden'><p><a epub:type='backlink' href='main.xhtml#origin'>↩</a>ALPHA 第一行😀</p><p>第二行文本 <a epub:type='backlink' href='main.xhtml#origin'>返回主文</a> <a href='notes-b.xhtml#fn1'>另一个附属文档</a></p></aside>"))
            text("OPS/notes-b.xhtml",html("<aside id='fn1' epub:type='footnote'><p>BRAVO 同名不同文档😀</p></aside>"))
            text("OPS/image.svg","<svg xmlns='http://www.w3.org/2000/svg' width='40' height='40'><rect width='40' height='40' fill='blue'/></svg>")
            val bitmap=android.graphics.Bitmap.createBitmap(320,480,android.graphics.Bitmap.Config.ARGB_8888)
            val png=ByteArrayOutputStream();try { bitmap.eraseColor(0xff335577.toInt());check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,png));entry("OPS/image.png",png.toByteArray()) } finally { bitmap.recycle() }
        }
        val uri=Uri.parse("content://com.localreader.app.test.fixtures/interaction-${UUID.randomUUID()}.epub");uris.add(uri)
        context.contentResolver.openOutputStream(uri,"wt")!!.use { it.write(bytes.toByteArray()) }
        val imported=repository.import(uri);assertNotNull(imported.status,imported.bookId);return requireNotNull(imported.bookId)
    }
    private fun waitUntil(timeoutMillis: Long=16000,predicate: ()->Boolean) { val end=SystemClock.uptimeMillis()+timeoutMillis;while(!predicate()) { if(SystemClock.uptimeMillis()>end)throw AssertionError("交互条件超时");Thread.sleep(20) } }
    private fun identity(path: String,fragment: String?=null)=PackageHref(PackagePath.entry("OPS/$path"),fragment).identity

    @Test fun actualManifestNotesKeepNamespaceBacklinkAndUncommittedMainExtentAcrossRestart() {
        val id=fixture();val store=SqliteReadingStore(repository.db);val factory=LibraryOpenedBookFactory(repository)
        val book=runBlocking { factory.open(id) }
        val owner=ReadingSessionOwner(book,1,store,store.loadSnapshot(id));val service=requireNotNull(book.interactions)
        val auxiliaryRoot=File(repository.db.epubDirectory(id),"auxiliary-1").apply { mkdirs() }
        val abandoned=File(auxiliaryRoot,".stage-${UUID.randomUUID()}").apply { mkdirs();File(this,"partial").writeText("unfinished") }
        val foreignFolder=File(auxiliaryRoot,".stage-not-owned").apply { mkdirs() }
        val origin=book.publication.locator(RendererAnchor.Text(0,8)) as Locator.Epub
        // No flush before the note: this is the real debounce replacement boundary.
        assertTrue(owner.position(origin,true,1))
        val alpha=service.resolve(book.publication,ContentInteraction.Link(0,identity("notes-a.xhtml","fn1")),origin,{}) as InteractionResult.Note
        val bravo=service.resolve(book.publication,ContentInteraction.Link(0,identity("notes-b.xhtml","fn1")),origin,{}) as InteractionResult.Note
        assertFalse(abandoned.exists());assertTrue(foreignFolder.isDirectory)
        assertTrue(alpha.content.text.startsWith("ALPHA"));assertTrue(alpha.content.text.contains("\n第二行"));assertTrue(alpha.content.text.contains("返回主文"));assertFalse(alpha.content.text.startsWith("↩"))
        assertTrue(bravo.content.text.startsWith("BRAVO"));assertNotEquals((alpha.content.jump as Locator.EpubResource).docKey,(bravo.content.jump as Locator.EpubResource).docKey)
        val target=book.prepareNavigation(alpha.content.jump);assertTrue(book.accepts(target));assertTrue(book.publicationFor(target).auxiliary)
        val first=target as Locator.EpubResource
        assertEquals(origin,first.returnTo);assertEquals(book.fractionOf(origin),book.fractionOf(first),0.0)
        service.activate(book.publicationFor(first))
        repeat(40) { at ->
            val repeated=service.resolve(book.publication,ContentInteraction.Link(0,identity("notes-a.xhtml","fn1")),origin.copy(offset=at),{})
            assertTrue(repeated is InteractionResult.Note)
        }
        assertTrue(book.accepts(first))
        assertTrue(owner.position(first,true,2));owner.flush()
        assertEquals(EpubReadingExtent(book.revision,0,8),store.loadSnapshot(id).furthest)
        assertTrue(store.addBookmark(id,book.revision,first,"ALPHA"));assertFalse(store.addBookmark(id,book.revision,first.copy(returnTo=origin.copy(offset=9)),"duplicate"))
        assertTrue(store.addBookmark(id,book.revision,bravo.content.jump,"BRAVO"))
        val payload=store.bookmarks(id).associate { it.id to LocatorCodec.encode(it.locator) }
        val old=first.copy(version="previous",returnTo=origin.copy(version="previous"))
        assertTrue(book.prepareNavigation(old) is Locator.Epub);assertEquals(payload,store.bookmarks(id).associate { it.id to LocatorCodec.encode(it.locator) })
        assertThrows(IllegalStateException::class.java) { service.resolve(book.publication,ContentInteraction.Link(0,identity("not-listed.xhtml","fn1")),origin,{}) }
        owner.closeSource()
        // Reopening rebuilds a missing disposable auxiliary cache from the verified original.
        val installed=EpubAuxiliary.directory(repository.db.epubLogicalDirectory(id),first.docKey)
        assertTrue(installed.deleteRecursively())
        val prepared=runBlocking { ReaderControllerFactory(factory,store).open(id,2) }
        try {
            assertTrue(installed.isDirectory);assertEquals(first,prepared.owner.snapshot.current);assertTrue(prepared.session.document.auxiliary)
            assertEquals(EpubReadingExtent(prepared.owner.opened.revision,0,8),prepared.owner.snapshot.furthest)
            assertEquals(2,prepared.owner.snapshot.bookmarks.size);assertNull(repository.db.content(id)?.finishedAt)
        } finally { prepared.owner.closeSource() }
    }

    @Test fun trustedWebViewNoteJumpStableSurfaceBackAndExternalCancelUseProductionController() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val automation=instrumentation.uiAutomation
        val arguments=InstrumentationRegistry.getArguments()
        val inputDriver=arguments.getString("readerNativeInputDriver") ?: "uaCli"
        require(inputDriver in setOf("uaCli","externalAdb"))
        val rawRunId=arguments.getString("readerExternalRunId")
        require((inputDriver=="externalAdb")== (rawRunId!=null))
        val externalRunId=rawRunId?.let { value ->
            require(value.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")))
            UUID.fromString(value).toString()
        }
        val externalDirectory=externalRunId?.let { File(instrumentation.targetContext.cacheDir,"native-input-probe/$it") }
        var externalDirectoryOwned=false
        var externalSequence=0
        fun revokeExternalRequest() {
            if(externalDirectoryOwned) {
                val pending=File(checkNotNull(externalDirectory),"pending.json")
                check(!pending.exists() || pending.delete()) { "Cannot revoke the external pending request" }
            }
        }
        val originalServiceFlags=automation.serviceInfo.flags
        var methodFailure: Throwable?=null
        try {
            if(externalDirectory!=null) {
                check(!externalDirectory.exists()) { "The external run must use a fresh owned UUID" }
                check(externalDirectory.mkdirs()) { "Cannot create the external request directory" }
                externalDirectoryOwned=true
            }
            // Keep the test's accessibility window observation stable for the
            // entire actual gesture chain; restore it independently on every exit.
            automation.serviceInfo=automation.serviceInfo.apply {
                flags=originalServiceFlags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            val id=fixture();val store=SqliteReadingStore(repository.db);val prepared=runBlocking { ReaderControllerFactory(LibraryOpenedBookFactory(repository),store).open(id,3) }
            val owner=prepared.owner;val navigation=AtomicReference<Locator?>();lateinit var surface: ReaderSurface
            val presentedSession=AtomicReference(prepared.session)
            val controller=ReaderController(3,{ owner.bind(it) },{ event -> if(event is RendererEvent.Position)owner.position(event.locator,event.intent==IntentCause.UserNavigation,SystemClock.uptimeMillis()) else true },ReaderActions(goTo={navigation.set(it)}),interactions=owner.opened.interactions)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var lastTouch: String?=null
                val nativeTouches=org.json.JSONArray()
                val originalCallback=AtomicReference<android.view.Window.Callback>()
                fun eval(script: String): String { val result=AtomicReference<String?>();scenario.onActivity { (surface.getChildAt(0) as WebView).evaluateJavascript(script) { result.set(it) } };waitUntil { result.get()!=null };return requireNotNull(result.get()) }
                fun diagnostic() {
                    val native=JSONObject().put("lastTouch",lastTouch).put("inputDriver",inputDriver).put("externalRunId",externalRunId)
                        .put("originalServiceFlags",originalServiceFlags).put("currentServiceFlags",automation.serviceInfo.flags)
                    scenario.onActivity {
                        val web=surface.getChildAt(0) as WebView;val screen=IntArray(2);web.getLocationOnScreen(screen)
                        native.put("webIdentity",System.identityHashCode(web)).put("screenX",screen[0]).put("screenY",screen[1])
                            .put("width",web.width).put("height",web.height).put("shown",web.isShown).put("windowFocus",web.hasWindowFocus())
                            .put("phase",controller.state.value.phase).put("position",controller.state.value.position.toString())
                            .put("message",controller.state.value.message).put("uiEvent",controller.uiEvent.value.toString())
                            .put("selection",controller.selectionState.value.toString()).put("rules",owner.snapshot.rules.toString())
                            .put("nativeDispatch",nativeTouches)
                            .put("webAccessibilityWindowId",web.createAccessibilityNodeInfo().windowId)
                            .put("decorAccessibilityWindowId",it.window.decorView.createAccessibilityNodeInfo().windowId)
                            .put("webTokenIdentity",System.identityHashCode(web.windowToken))
                            .put("decorTokenIdentity",System.identityHashCode(it.window.decorView.windowToken))
                            .put("tokensEqual",web.windowToken!=null && web.windowToken==it.window.decorView.windowToken)
                            .put("windowFlags",it.window.attributes.flags)
                            .put("callbackIdentity",System.identityHashCode(it.window.callback))
                    }
                    native.put("windows",NativeInputProbe.windows(InstrumentationRegistry.getInstrumentation().uiAutomation))
                    android.util.Log.i("ReaderInteractionProbe","failure-native $native")
                    val host=eval(NativeInputProbe.snapshot)
                    host.chunked(2800).forEachIndexed { index,chunk -> android.util.Log.i("ReaderInteractionProbe","failure-host ${index+1} $chunk") }
                }
                fun ready() { waitUntil { controller.state.value.phase in setOf(ReaderPhase.READY,ReaderPhase.FAILED) };assertEquals(controller.state.value.message,ReaderPhase.READY,controller.state.value.phase);assertEquals("true",eval("readerHost.diagnostics().anchorGeometry.visible")) }
                fun awaitDrawnContent() {
                    val deadline=SystemClock.uptimeMillis()+16000
                    val drawn=java.util.concurrent.atomic.AtomicBoolean(false)
                    val committed=java.util.concurrent.atomic.AtomicBoolean(false)
                    val cancelled=java.util.concurrent.atomic.AtomicBoolean(false)
                    val drawPosted=java.util.concurrent.atomic.AtomicBoolean(false)
                    val drawObserver=AtomicReference<android.view.ViewTreeObserver>()
                    val commitObserver=AtomicReference<android.view.ViewTreeObserver>()
                    val listener=AtomicReference<android.view.ViewTreeObserver.OnDrawListener?>()
                    val commitCallback=AtomicReference<Runnable?>()
                    val session=presentedSession.get()
                    val position=controller.state.value.position
                    var failure: Throwable?=null
                    try {
                        scenario.onActivity { activity ->
                            val window=activity.window;val decor=window.decorView
                            val web=surface.getChildAt(0) as WebView
                            val token=requireNotNull(web.windowToken);val url=requireNotNull(web.url)
                            assertTrue(web.isAttachedToWindow);assertTrue(web.isShown)
                            assertTrue(decor.isHardwareAccelerated)
                            assertEquals(token,decor.windowToken)
                            fun current()= !cancelled.get() && activity.window===window && window.decorView===decor &&
                                surface.getChildAt(0)===web && web.isAttachedToWindow && web.isShown &&
                                decor.isAttachedToWindow && web.windowToken==token && decor.windowToken==token && web.url==url &&
                                presentedSession.get()===session && owner.generation==session.generation &&
                                controller.state.value.phase==ReaderPhase.READY && controller.state.value.position==position
                            web.postVisualStateCallback(SystemClock.uptimeMillis(),object: WebView.VisualStateCallback() {
                                override fun onComplete(requestId: Long) {
                                    if(!current())return
                                    // This public callback proves submission of the original own
                                    // Window's rendered frame, not InputDispatcher readiness.
                                    val onCommit=Runnable {
                                        val at=SystemClock.uptimeMillis()
                                        decor.post {
                                            if(current()) {
                                                committed.set(true)
                                                android.util.Log.i("ReaderInputProbe","frame-commit uptime=$at generation=${session.generation}")
                                            }
                                        }
                                    }
                                    commitCallback.set(onCommit);commitObserver.set(decor.viewTreeObserver)
                                    commitObserver.get().registerFrameCommitCallback(onCommit)
                                    val onDraw=object: android.view.ViewTreeObserver.OnDrawListener {
                                        override fun onDraw() {
                                            if(!drawPosted.compareAndSet(false,true))return
                                            // A posted completion runs after this actual draw.
                                            web.post {
                                                drawObserver.get().takeIf { it.isAlive }?.removeOnDrawListener(this)
                                                if(current())drawn.set(true)
                                            }
                                        }
                                    }
                                    listener.set(onDraw);drawObserver.set(web.viewTreeObserver)
                                    drawObserver.get().addOnDrawListener(onDraw)
                                    web.invalidate();decor.invalidate()
                                }
                            })
                        }
                        waitUntil((deadline-SystemClock.uptimeMillis()).coerceAtLeast(0L)) { drawn.get() && committed.get() }
                    } catch(error: Throwable) {
                        failure=error;throw error
                    } finally {
                        cancelled.set(true)
                        val originalFailure=failure
                        fun clean(action: ()->Unit) {
                            try { action() } catch(error: Throwable) { failure?.addSuppressed(error) ?: run { failure=error } }
                        }
                        clean { scenario.onActivity { listener.get()?.let { registered -> drawObserver.get()?.takeIf { it.isAlive }?.removeOnDrawListener(registered) } } }
                        clean { scenario.onActivity { commitCallback.get()?.let { registered -> commitObserver.get()?.takeIf { it.isAlive }?.unregisterFrameCommitCallback(registered) } } }
                        if(originalFailure==null)failure?.let { throw it }
                    }
                }
                fun tap(id: String) {
                    awaitDrawnContent()
                    eval(NativeInputProbe.install)
                    val raw=eval("JSON.stringify((()=>{const c=document.getElementById('renderer').getContents().find(c=>c.doc.getElementById('$id'));const el=c.doc.getElementById('$id'),r=el.getBoundingClientRect(),f=c.doc.defaultView.frameElement.getBoundingClientRect();return {x:f.left+r.left+r.width/2,y:f.top+r.top+r.height/2,w:innerWidth,rects:[...el.getClientRects()].map(r=>({left:r.left,top:r.top,width:r.width,height:r.height})),hit:c.doc.elementFromPoint(r.left+r.width/2,r.top+r.height/2)?.outerHTML.slice(0,180),frame:{left:f.left,top:f.top,width:f.width,height:f.height}}})())")
                    val value=JSONObject(org.json.JSONTokener(raw).nextValue() as String);var x=0f;var y=0f
                    val native=JSONObject()
                    scenario.onActivity { val web=surface.getChildAt(0) as WebView;val location=IntArray(2);web.getLocationOnScreen(location);val scale=web.width/value.getDouble("w");x=(location[0]+value.getDouble("x")*scale).toFloat();y=(location[1]+value.getDouble("y")*scale).toFloat()
                        native.put("focus",web.hasWindowFocus()).put("activityFocus",it.hasWindowFocus()).put("shown",web.isShown).put("attached",web.isAttachedToWindow)
                            .put("webIdentity",System.identityHashCode(web)).put("width",web.width).put("height",web.height).put("screenX",location[0]).put("screenY",location[1])
                    }
                    native.put("windows",NativeInputProbe.windows(InstrumentationRegistry.getInstrumentation().uiAutomation))
                    lastTouch=JSONObject().put("id",id).put("x",x).put("y",y).put("dom",value).put("nativeBefore",native).toString()
                    val target=instrumentation.targetContext
                    val sourceViewState=AtomicReference<JSONObject>()
                    fun sourceViewReady(): Boolean {
                        var valid=false
                        scenario.onActivity { activity ->
                            val web=surface.getChildAt(0) as WebView;val decor=activity.window.decorView;val bounds=android.graphics.Rect()
                            val visible=web.getGlobalVisibleRect(bounds)
                            val webId=web.createAccessibilityNodeInfo().windowId
                            val decorId=decor.createAccessibilityNodeInfo().windowId
                            val tokensEqual=web.windowToken!=null && web.windowToken==decor.windowToken
                            sourceViewState.set(JSONObject().put("webWindowId",webId).put("decorWindowId",decorId)
                                .put("webTokenIdentity",System.identityHashCode(web.windowToken))
                                .put("decorTokenIdentity",System.identityHashCode(decor.windowToken)).put("tokensEqual",tokensEqual)
                                .put("processUid",android.os.Process.myUid()).put("activityUid",activity.applicationInfo.uid)
                                .put("targetUid",target.applicationInfo.uid).put("webUid",web.context.applicationInfo.uid)
                                .put("activityFocus",activity.hasWindowFocus()).put("webFocus",web.hasWindowFocus())
                                .put("decorFocus",decor.hasWindowFocus()).put("shown",web.isShown).put("attached",web.isAttachedToWindow)
                                .put("bounds",bounds.toShortString()).put("windowFlags",activity.window.attributes.flags)
                                .put("callbackIdentity",System.identityHashCode(activity.window.callback)))
                            valid=target.applicationInfo.uid==android.os.Process.myUid() && target.applicationInfo.uid==activity.applicationInfo.uid &&
                                target.applicationInfo.uid==web.context.applicationInfo.uid && target.packageName==activity.packageName &&
                                activity.hasWindowFocus() && web.hasWindowFocus() && decor.hasWindowFocus() && web.isShown &&
                                web.isAttachedToWindow && tokensEqual && webId>=0 && webId==decorId && visible && x.isFinite() && y.isFinite() &&
                                x>=bounds.left && x<bounds.right && y>=bounds.top && y<bounds.bottom
                        }
                        return valid
                    }
                    var focusedWindowId=-1
                    fun sourceWindowReady(): Boolean {
                        val validView=sourceViewReady()
                        val focused=automation.windows.filter { it.isFocused }.maxByOrNull { it.layer }
                        val bounds=android.graphics.Rect();focused?.getBoundsInScreen(bounds)
                        val windowState=JSONObject().put("id",focused?.id).put("type",focused?.type).put("bounds",bounds.toShortString())
                            .put("rootPackage",focused?.root?.packageName?.toString())
                        sourceViewState.get().put("focusedWindow",windowState)
                        if(!validView || focused==null)return false
                        val nativeId=sourceViewState.get().getInt("webWindowId")
                        val validWindow=focused.type==android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION &&
                            focused.root?.packageName?.toString()==target.packageName && focused.id==nativeId &&
                            x>=bounds.left && x<bounds.right && y>=bounds.top && y<bounds.bottom
                        if(validWindow)focusedWindowId=focused.id
                        return validWindow
                    }
                    try {
                        waitUntil(20000) { sourceWindowReady() }
                    } finally {
                        lastTouch=JSONObject(requireNotNull(lastTouch)).put("sourceWindow",sourceViewState.get()).toString()
                    }
                    assertTrue("The original own source View must still be focused and visible",sourceViewReady())
                    assertEquals("The source window identity must still match",focusedWindowId,sourceViewState.get().getInt("webWindowId"))
                    var before=0
                    scenario.onActivity { before=nativeTouches.length() }
                    val startedElapsed=SystemClock.elapsedRealtime()
                    val started=SystemClock.uptimeMillis();val deadline=started+16000
                    android.util.Log.i("ReaderInputProbe","input-start uptime=$started id=$id generation=${presentedSession.get().generation}")
                    lastTouch=JSONObject(requireNotNull(lastTouch)).put("inputDriver","platform-cli")
                        .put("shellStarted",started).put("stdoutEofObserved",false).toString()
                    if(inputDriver=="externalAdb") {
                        val directory=checkNotNull(externalDirectory)
                        val sequence=++externalSequence
                        check(sequence==when(id) { "note" -> 1;"external" -> 2;else -> error("Unexpected external tap element") })
                        scenario.onActivity { assertEquals("This bounded probe targets the own default display",0,(surface.getChildAt(0) as WebView).display?.displayId) }
                        val pending=File(directory,"pending.json")
                        check(!pending.exists()) { "Only one external request may be pending" }
                        val request=JSONObject().put("version",1).put("runId",externalRunId).put("sequence",sequence)
                            .put("kind","tap").put("elementId",id).put("rawX",x).put("rawY",y)
                            .put("pid",android.os.Process.myPid()).put("uid",android.os.Process.myUid()).put("displayId",0)
                            .put("generation",presentedSession.get().generation).put("sourceWindowId",focusedWindowId)
                            .put("startedUptimeMs",started).put("deadlineUptimeMs",deadline).put("active",true)
                            .put("startedElapsedRealtimeMs",startedElapsed)
                            .put("deadlineElapsedRealtimeMs",startedElapsed+(deadline-started))
                            .put("method","com.localreader.app.EpubInteractionTest#trustedWebViewNoteJumpStableSurfaceBackAndExternalCancelUseProductionController")
                            .put("windowTokenIdentity",sourceViewState.get().getInt("webTokenIdentity"))
                        val staging=File(directory,".pending-$sequence.json")
                        java.io.FileOutputStream(staging).use { stream -> stream.write(request.toString().toByteArray(Charsets.UTF_8));stream.fd.sync() }
                        android.system.Os.rename(staging.path,pending.path)
                        lastTouch=JSONObject(requireNotNull(lastTouch)).put("inputDriver",inputDriver).put("request",request).toString()
                        val ackFile=File(directory,"ack-$sequence.json")
                        var acknowledged: JSONObject?=null
                        waitUntil((deadline-SystemClock.uptimeMillis()).coerceAtLeast(0L)) {
                            if(!ackFile.isFile)false else {
                                check(ackFile.length() in 1..4096) { "External acknowledgment exceeds the bounded schema" }
                                val ackText=ackFile.inputStream().use { stream ->
                                    val bytes=ByteArray(4097);var size=0
                                    while(size<bytes.size) {
                                        val count=stream.read(bytes,size,bytes.size-size)
                                        if(count<0)break
                                        size+=count
                                    }
                                    check(size in 1..4096) { "External acknowledgment exceeds the bounded schema" }
                                    String(bytes,0,size,Charsets.UTF_8)
                                }
                                val ack=JSONObject(ackText)
                                assertEquals(1,ack.getInt("version"));assertEquals(externalRunId,ack.getString("runId"))
                                assertEquals(sequence,ack.getInt("sequence"));assertEquals("The one external CLI command must succeed",0,ack.getInt("exitCode"))
                                acknowledged=ack;true
                            }
                        }
                        lastTouch=JSONObject(requireNotNull(lastTouch)).put("externalAck",acknowledged).toString()
                        check(pending.delete()) { "Cannot retire the acknowledged external request" }
                    } else {
                        val descriptor=requireNotNull(automation.executeShellCommand("input touchscreen tap $x $y"))
                        val executor=java.util.concurrent.Executors.newSingleThreadExecutor()
                        var pipeFailure: Throwable?=null
                        try {
                            val output=executor.submit<String> {
                                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
                                    val captured=ByteArrayOutputStream();val bytes=ByteArray(256)
                                    while(true) {
                                        val count=stream.read(bytes)
                                        if(count<0)break
                                        check(captured.size()+count<=1024) { "Unexpected input command output exceeds the test budget" }
                                        captured.write(bytes,0,count)
                                    }
                                    captured.toByteArray().toString(Charsets.UTF_8)
                                }
                            }.get((deadline-SystemClock.uptimeMillis()).coerceAtLeast(0L),java.util.concurrent.TimeUnit.MILLISECONDS)
                            lastTouch=JSONObject(requireNotNull(lastTouch)).put("shellOutput",output).put("stdoutEofObserved",true).toString()
                        } catch(error: Throwable) {
                            pipeFailure=error;throw error
                        } finally {
                            // The public stdout pipe is bounded and owned by this test.
                            // EOF/close is not proof of CLI process termination or input delivery.
                            try { descriptor.close() } catch(error: Throwable) { pipeFailure?.addSuppressed(error) ?: throw error }
                            finally { executor.shutdownNow() }
                        }
                    }
                    // Command completion is not delivery. Observe this single
                    // gesture within the same original 16s budget, without retrying it.
                    waitUntil((deadline-SystemClock.uptimeMillis()).coerceAtLeast(0L)) {
                        var delivered=false
                        scenario.onActivity {
                            val actions=(before until nativeTouches.length()).map { nativeTouches.getJSONObject(it).getInt("action") }
                            delivered=MotionEvent.ACTION_DOWN in actions && MotionEvent.ACTION_UP in actions
                        }
                        delivered
                    }
                    val completed=SystemClock.uptimeMillis()
                    scenario.onActivity {
                        val arrivals=(before until nativeTouches.length()).map { nativeTouches.getJSONObject(it) }
                        val downs=arrivals.filter { it.getInt("action")==MotionEvent.ACTION_DOWN }
                        val ups=arrivals.filter { it.getInt("action")==MotionEvent.ACTION_UP }
                        assertEquals("The actual own Window must receive exactly one CLI DOWN",1,downs.size)
                        assertEquals("The actual own Window must receive exactly one CLI UP",1,ups.size)
                        assertEquals("A CLI tap must be a single DOWN/UP gesture",2,arrivals.size)
                        assertEquals(MotionEvent.ACTION_DOWN,arrivals[0].getInt("action"))
                        assertEquals(MotionEvent.ACTION_UP,arrivals[1].getInt("action"))
                        assertEquals(downs.single().getLong("downTime"),ups.single().getLong("downTime"))
                        assertTrue(ups.single().getLong("time")>=downs.single().getLong("time"))
                        // Android 10/11 use the public simple MotionEvent factory;
                        // Android 12+ CLI explicitly supplies a touchscreen finger.
                        val expectedTool=if(android.os.Build.VERSION.SDK_INT<31)MotionEvent.TOOL_TYPE_UNKNOWN else MotionEvent.TOOL_TYPE_FINGER
                        for(received in listOf(downs.single(),ups.single())) {
                            assertTrue("The CLI event must describe the actual current gesture",received.getLong("time") in started..completed)
                            assertTrue(received.getLong("downTime") in started..received.getLong("time"))
                            assertEquals(x.toDouble(),received.getDouble("rawX"),1.0)
                            assertEquals(y.toDouble(),received.getDouble("rawY"),1.0)
                            assertEquals(android.view.InputDevice.SOURCE_TOUCHSCREEN,received.getInt("source"))
                            assertEquals(expectedTool,received.getInt("tool"))
                            assertEquals(1,received.getInt("pointerCount"));assertEquals(0,received.getInt("pointerId"))
                            assertTrue("The original Window delegate must handle the CLI event",received.getBoolean("handled"))
                        }
                    }
                    // CLI stdout EOF proves injection completed, not that WebView's
                    // renderer has dispatched its asynchronous trusted click yet.
                    waitUntil { eval("window.__nativeInputEvents.some(e=>e.type==='click'&&e.trusted)")=="true" }
                    assertEquals("true",eval("window.__nativeInputEvents.some(e=>e.type==='click'&&e.trusted)"))
                }
                try {
                    scenario.onActivity { activity ->
                        surface=ReaderSurface(activity);activity.setContentView(surface);surface.bind(controller);surface.present(prepared.session)
                        val delegate=requireNotNull(activity.window.callback);originalCallback.set(delegate)
                        activity.window.callback=object: android.view.Window.Callback by delegate {
                            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                                // Observe the actual Window route without synthesizing a
                                // second event, consuming it, or changing its destination.
                                val evidence=JSONObject().put("action",event.actionMasked).put("source",event.source).put("tool",event.getToolType(0))
                                    .put("x",event.x).put("y",event.y).put("rawX",event.rawX).put("rawY",event.rawY).put("time",event.eventTime).put("downTime",event.downTime)
                                    .put("pointerCount",event.pointerCount).put("pointerId",event.getPointerId(0))
                                val web=surface.getChildAt(0) as? WebView
                                val bounds=android.graphics.Rect();web?.getGlobalVisibleRect(bounds)
                                evidence.put("webVisibleBounds",bounds.toShortString()).put("focus",web?.hasWindowFocus())
                                val handled=delegate.dispatchTouchEvent(event)
                                evidence.put("handled",handled)
                                if(nativeTouches.length()<24)nativeTouches.put(evidence)
                                return handled
                            }
                        }
                    };ready()
                    val main=controller.state.value.position!!;val originalView=AtomicReference<WebView>();scenario.onActivity { originalView.set(surface.getChildAt(0) as WebView) }
                    tap("note");waitUntil { controller.uiEvent.value!=null };assertTrue(controller.uiEvent.value.toString(),controller.uiEvent.value is ReaderUiEvent.Note)
                    scenario.onActivity { controller.jumpToNote() };waitUntil { navigation.get()!=null }
                    val target=owner.opened.prepareNavigation(navigation.get()!!)
                    val screen=owner.state(target,1,presentation=ReadingPresentation.prepare(owner.opened,owner.snapshot.copy(current=target)))
                    scenario.onActivity { presentedSession.set(screen);surface.present(screen) };ready()
                    assertTrue(controller.state.value.position is Locator.EpubResource);assertFalse(controller.state.value.viewport!!.atEnd);assertNull(controller.state.value.progress)
                    assertEquals(main,(controller.state.value.position as Locator.EpubResource).returnTo)
                    scenario.onActivity { assertSame(originalView.get(),surface.getChildAt(0));assertTrue(controller.returnToMain()) }
                    val back=navigation.get()!!;assertEquals(main,back)
                    scenario.onActivity {
                        val mainSession=owner.state(back,2,presentation=ReadingPresentation.prepare(owner.opened,owner.snapshot.copy(current=back)))
                        presentedSession.set(mainSession);surface.present(mainSession)
                    };ready()
                    tap("external");waitUntil { controller.uiEvent.value!=null };assertEquals(ReaderUiEvent.ExternalLink("https://example.com/read"),controller.uiEvent.value)
                    scenario.onActivity { controller.dismissInteraction();assertNull(controller.confirmExternal()) }
                    assertEquals(main,controller.state.value.position)
                } catch(error: Throwable) {
                    methodFailure=error
                    try { diagnostic() } catch(diagnosticError: Throwable) { error.addSuppressed(diagnosticError) }
                    throw error
                } finally {
                    val previousFailure=methodFailure
                    fun clean(action: ()->Unit) {
                        try { action() } catch(error: Throwable) {
                            methodFailure?.addSuppressed(error) ?: run { methodFailure=error }
                        }
                    }
                    clean { revokeExternalRequest() }
                    clean { scenario.onActivity { originalCallback.get()?.let { callback -> it.window.callback=callback } } }
                    clean { scenario.onActivity { surface.close() } }
                    clean { scenario.onActivity { controller.detach() } }
                    clean { owner.closeSource() }
                    if(previousFailure==null)methodFailure?.let { throw it }
                }
            }
        } catch(error: Throwable) {
            methodFailure=error;throw error
        } finally {
            val originalFailure=methodFailure
            fun clean(action: ()->Unit) {
                try { action() } catch(error: Throwable) { methodFailure?.addSuppressed(error) ?: run { methodFailure=error } }
            }
            // Existence + active=true is the receiver's lease. Revoke it before
            // changing window observation or cleaning any other owned files.
            clean { revokeExternalRequest() }
            clean { automation.serviceInfo=automation.serviceInfo.apply { flags=originalServiceFlags } }
            clean {
                if(externalDirectoryOwned) {
                    val directory=checkNotNull(externalDirectory)
                    val files=checkNotNull(directory.listFiles())
                    check(files.size<=8 && files.all { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) }) { "Unexpected owned diagnostic directory entries" }
                    files.forEach { check(it.delete()) { "Cannot delete the owned external diagnostic file" } }
                    check(directory.delete()) { "Cannot delete the owned external diagnostic directory" }
                }
            }
            if(originalFailure==null)methodFailure?.let { throw it }
        }
    }


    @Test fun selectedVisibleRunsExcludeHiddenSourceAndRetainCanonicalCoordinates() {
        val id=fixture();val book=runBlocking { LibraryOpenedBookFactory(repository).open(id) }
        try {
            val service=requireNotNull(book.interactions);val record=repository.db.epubCache(id)
            val text=EpubSource(repository.db.epubDirectory(id),record).use { it.textSlice(0,0,record.sections[0].characters) }
            val start=text.indexOf("visiblehidden end");assertTrue(start>=0)
            val ranges=listOf(VisibleRangeCodec.Interval(start,start+7),VisibleRangeCodec.Interval(start+13,start+17))
            val request=ContentInteraction.Selection(0,RendererAnchor.Text(0,start),RendererAnchor.Text(0,start+17),"VISIBLE END",ranges)
            val origin=book.publication.locator(RendererAnchor.Text(0,0))!!
            val selected=service.resolve(book.publication,request,origin,{}) as InteractionResult.Selection
            assertEquals("VISIBLE END",selected.text);assertEquals(start,(selected.start as Locator.Epub).offset);assertEquals(start+17,(selected.end as Locator.Epub).offset)
            assertThrows(IllegalArgumentException::class.java) { service.resolve(book.publication,request.copy(text="VISIBLEHIDDEN END"),origin,{}) }
            assertThrows(IllegalArgumentException::class.java) { service.resolve(book.publication,request.copy(ranges=ranges.reversed()),origin,{}) }
            assertEquals(text,EpubSource(repository.db.epubDirectory(id),record).use { it.textSlice(0,0,record.sections[0].characters) })
        } finally { book.close() }
    }

    @Test fun cancelledImageResultNeverSurvivesReplacementOrReaderClose() {
        val id=fixture();val prepared=runBlocking { ReaderControllerFactory(LibraryOpenedBookFactory(repository),SqliteReadingStore(repository.db)).open(id,5) }
        val opened=prepared.owner.opened;val actual=requireNotNull(opened.interactions)
        val imageId=org.jsoup.Jsoup.parse(opened.publication.section(0).html).selectFirst("img[data-reader-element]")!!.attr("data-reader-element")
        val resolved=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1);val returned=java.util.concurrent.CountDownLatch(1)
        val image=AtomicReference<ImageSource?>()
        val delayed=object: BookInteractions by actual {
            override fun resolve(document: ReaderPublication,request: ContentInteraction,origin: Locator,checkpoint: ()->Unit): InteractionResult {
                val result=actual.resolve(document,request,origin,checkpoint)
                image.set((result as InteractionResult.Image).source);resolved.countDown()
                check(release.await(5,java.util.concurrent.TimeUnit.SECONDS));returned.countDown();return result
            }
        }
        val backend=object: RendererBackend {
            override fun open(document: ReaderPublication,locator: Locator,rules: ReaderRules)=Unit
            override fun goTo(locator: Locator)=Unit
            override fun setRules(rules: ReaderRules,locator: Locator)=Unit
            override fun next()=Unit;override fun prev()=Unit;override fun close()=Unit
        }
        val controller=ReaderController(5,{ {} },{true},interactions=delayed)
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        try {
            instrumentation.runOnMainSync {
                controller.attach(backend);controller.present(prepared.session)
                controller.receive(backend,RendererEvent.Position(prepared.session.jump,"anchor",IntentCause.Restore))
                controller.receive(backend,RendererEvent.Interaction(ContentInteraction.Image(0,imageId)))
            }
            assertTrue(resolved.await(5,java.util.concurrent.TimeUnit.SECONDS))
            instrumentation.runOnMainSync { controller.receive(backend,RendererEvent.Loading);controller.detach() }
            release.countDown();assertTrue(returned.await(5,java.util.concurrent.TimeUnit.SECONDS))
            waitUntil { runCatching { image.get()!!.open().use { } }.isFailure }
            assertNull(controller.uiEvent.value)
        } finally { release.countDown();instrumentation.runOnMainSync { controller.detach() };prepared.owner.closeSource() }
    }

    private fun largeRaster(): File {
        val file=File(root,"original-4000x6000.png")
        val packed=ByteArrayOutputStream()
        java.util.zip.DeflaterOutputStream(packed).use { deflate ->
            val row=ByteArray(1+4000*3)
            for(at in 1 until row.size step 3) { row[at]=0x33;row[at+1]=0x55;row[at+2]=0x77 }
            repeat(6000) { deflate.write(row) }
        }
        java.io.DataOutputStream(file.outputStream()).use { output ->
            output.write(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))
            fun chunk(name: String,data: ByteArray) {
                val type=name.toByteArray(Charsets.US_ASCII);output.writeInt(data.size);output.write(type);output.write(data)
                val crc=CRC32().apply { update(type);update(data) };output.writeInt(crc.value.toInt())
            }
            val header=ByteArrayOutputStream();java.io.DataOutputStream(header).use { it.writeInt(4000);it.writeInt(6000);it.write(byteArrayOf(8,2,0,0,0)) }
            chunk("IHDR",header.toByteArray());chunk("IDAT",packed.toByteArray());chunk("IEND",byteArrayOf())
        }
        return file
    }

    @Test fun imageCapabilitiesBoundDecodeAndAreRevokedOnViewerOrBookClose() {
        val id=fixture();val book=runBlocking { LibraryOpenedBookFactory(repository).open(id) };val service=requireNotNull(book.interactions)
        val origin=book.publication.locator(RendererAnchor.Text(0,0))!!
        val doc=org.jsoup.Jsoup.parse(book.publication.section(0).html)
        val pictures=doc.select("img[data-reader-element]")
        val image=(service.resolve(book.publication,ContentInteraction.Image(0,pictures[0].attr("data-reader-element")),origin,{}) as InteractionResult.Image).source
        val bitmap=ReaderImageDecoder.decode(image,{})
        assertEquals(320,image.width);assertEquals(480,image.height);assertEquals(320,bitmap.width);assertEquals(480,bitmap.height);assertTrue(bitmap.allocationByteCount<=4*1024*1024);bitmap.recycle();image.close()
        assertThrows(IllegalStateException::class.java) { image.open() }
        val svg=(service.resolve(book.publication,ContentInteraction.Image(0,pictures[1].attr("data-reader-element")),origin,{}) as InteractionResult.Image).source
        assertThrows(IllegalArgumentException::class.java) { ReaderImageDecoder.decode(svg,{}) }
        val lease=(service.resolve(book.publication,ContentInteraction.Image(0,pictures[0].attr("data-reader-element")),origin,{}) as InteractionResult.Image).source
        val stream=lease.open();book.close()
        assertThrows(IllegalStateException::class.java) { lease.open() };assertThrows(java.io.IOException::class.java) { stream.read() }
        svg.close();lease.close()
        val foreign=object: ImageSource {
            override val mime="image/png";override val bytes=1L;override val alt="";override val index: Int?=null;override val count: Int?=null
            override fun open()=ByteArrayInputStream(byteArrayOf(0));override fun close()=Unit
        }
        assertThrows(IllegalArgumentException::class.java) { ReaderImageDecoder.decode(foreign,{}) }
        val original=largeRaster();val sha=java.security.MessageDigest.getInstance("SHA-256").digest(original.readBytes()).toList()
        val large=object: ImageSource {
            override val mime="image/png";override val bytes=original.length();override val alt="24MP original";override val index: Int?=null;override val count: Int?=null
            override fun open()=original.inputStream();override fun close()=Unit
        }
        val sampled=ReaderImageDecoder.decode(large,{})
        try { assertEquals(500,sampled.width);assertEquals(750,sampled.height);assertTrue(sampled.allocationByteCount<=4*1024*1024) } finally { sampled.recycle() }
        assertEquals(sha,java.security.MessageDigest.getInstance("SHA-256").digest(original.readBytes()).toList())
    }
}
