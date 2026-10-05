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
    private fun waitUntil(predicate: ()->Boolean) { val end=SystemClock.uptimeMillis()+16000;while(!predicate()) { if(SystemClock.uptimeMillis()>end)throw AssertionError("交互条件超时");Thread.sleep(20) } }
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
        val installed=EpubAuxiliary.directory(repository.db.epubDirectory(id),first.docKey)
        assertTrue(installed.deleteRecursively())
        val prepared=runBlocking { ReaderControllerFactory(factory,store).open(id,2) }
        try {
            assertTrue(installed.isDirectory);assertEquals(first,prepared.owner.snapshot.current);assertTrue(prepared.session.document.auxiliary)
            assertEquals(EpubReadingExtent(prepared.owner.opened.revision,0,8),prepared.owner.snapshot.furthest)
            assertEquals(2,prepared.owner.snapshot.bookmarks.size);assertNull(repository.db.content(id)?.finishedAt)
        } finally { prepared.owner.closeSource() }
    }

    @Test fun trustedWebViewNoteJumpStableSurfaceBackAndExternalCancelUseProductionController() {
        val id=fixture();val store=SqliteReadingStore(repository.db);val prepared=runBlocking { ReaderControllerFactory(LibraryOpenedBookFactory(repository),store).open(id,3) }
        val owner=prepared.owner;val navigation=AtomicReference<Locator?>();lateinit var surface: ReaderSurface
        val controller=ReaderController(3,{ owner.bind(it) },{ event -> if(event is RendererEvent.Position)owner.position(event.locator,event.intent==IntentCause.UserNavigation,SystemClock.uptimeMillis()) else true },ReaderActions(goTo={navigation.set(it)}),interactions=owner.opened.interactions)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var lastTouch: String?=null
            fun eval(script: String): String { val result=AtomicReference<String?>();scenario.onActivity { (surface.getChildAt(0) as WebView).evaluateJavascript(script) { result.set(it) } };waitUntil { result.get()!=null };return requireNotNull(result.get()) }
            fun diagnostic() {
                val native=JSONObject().put("lastTouch",lastTouch)
                scenario.onActivity {
                    val web=surface.getChildAt(0) as WebView;val screen=IntArray(2);web.getLocationOnScreen(screen)
                    native.put("webIdentity",System.identityHashCode(web)).put("screenX",screen[0]).put("screenY",screen[1])
                        .put("width",web.width).put("height",web.height).put("shown",web.isShown).put("windowFocus",web.hasWindowFocus())
                        .put("phase",controller.state.value.phase).put("position",controller.state.value.position.toString())
                        .put("message",controller.state.value.message).put("uiEvent",controller.uiEvent.value.toString())
                }
                android.util.Log.i("ReaderInteractionProbe","failure-native $native")
                val host=eval("JSON.stringify(readerHost.diagnostics())")
                host.chunked(2800).forEachIndexed { index,chunk -> android.util.Log.i("ReaderInteractionProbe","failure-host ${index+1} $chunk") }
            }
            fun ready() { waitUntil { controller.state.value.phase in setOf(ReaderPhase.READY,ReaderPhase.FAILED) };assertEquals(controller.state.value.message,ReaderPhase.READY,controller.state.value.phase);assertEquals("true",eval("readerHost.diagnostics().anchorGeometry.visible")) }
            fun awaitDrawnContent() {
                val drawn=java.util.concurrent.atomic.AtomicBoolean(false)
                val cancelled=java.util.concurrent.atomic.AtomicBoolean(false)
                val expected=AtomicReference<WebView>()
                val listener=AtomicReference<android.view.ViewTreeObserver.OnDrawListener?>()
                scenario.onActivity {
                    val web=surface.getChildAt(0) as WebView;expected.set(web)
                    assertTrue(web.isAttachedToWindow);assertTrue(web.isShown)
                    web.postVisualStateCallback(SystemClock.uptimeMillis(),object: WebView.VisualStateCallback() {
                        override fun onComplete(requestId: Long) {
                            if(cancelled.get() || surface.getChildAt(0)!==web || !web.isAttachedToWindow || !web.isShown)return
                            val onDraw=object: android.view.ViewTreeObserver.OnDrawListener {
                                override fun onDraw() {
                                    // A posted completion runs after this actual draw, not at its entry.
                                    web.post {
                                        web.viewTreeObserver.takeIf { it.isAlive }?.removeOnDrawListener(this)
                                        if(!cancelled.get() && surface.getChildAt(0)===web && web.isAttachedToWindow && web.isShown)drawn.set(true)
                                    }
                                }
                            }
                            listener.set(onDraw);web.viewTreeObserver.addOnDrawListener(onDraw);web.invalidate()
                        }
                    })
                }
                try { waitUntil { drawn.get() } }
                finally { cancelled.set(true);scenario.onActivity { listener.get()?.let { registered -> expected.get()?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnDrawListener(registered) } } }
            }
            fun tap(id: String) {
                awaitDrawnContent()
                val raw=eval("JSON.stringify((()=>{const c=document.getElementById('renderer').getContents().find(c=>c.doc.getElementById('$id'));const r=c.doc.getElementById('$id').getBoundingClientRect(),f=c.doc.defaultView.frameElement.getBoundingClientRect();return {x:f.left+r.left+r.width/2,y:f.top+r.top+r.height/2,w:innerWidth}})())")
                val value=JSONObject(org.json.JSONTokener(raw).nextValue() as String);var x=0f;var y=0f
                scenario.onActivity { val web=surface.getChildAt(0) as WebView;val location=IntArray(2);web.getLocationOnScreen(location);val scale=web.width/value.getDouble("w");x=(location[0]+value.getDouble("x")*scale).toFloat();y=(location[1]+value.getDouble("y")*scale).toFloat() }
                lastTouch=JSONObject().put("id",id).put("x",x).put("y",y).put("dom",value).toString()
                val instrumentation=InstrumentationRegistry.getInstrumentation();val time=SystemClock.uptimeMillis()
                listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP).forEachIndexed { index,action -> val event=MotionEvent.obtain(time,time+index*60,action,x,y,0);try { instrumentation.sendPointerSync(event) } finally { event.recycle() } }
            }
            try {
                scenario.onActivity { surface=ReaderSurface(it);it.setContentView(surface);surface.bind(controller);surface.present(prepared.session) };ready()
                val main=controller.state.value.position!!;val originalView=AtomicReference<WebView>();scenario.onActivity { originalView.set(surface.getChildAt(0) as WebView) }
                tap("note");waitUntil { controller.uiEvent.value!=null };assertTrue(controller.uiEvent.value.toString(),controller.uiEvent.value is ReaderUiEvent.Note)
                scenario.onActivity { controller.jumpToNote() };waitUntil { navigation.get()!=null }
                val target=owner.opened.prepareNavigation(navigation.get()!!)
                val screen=owner.state(target,1,presentation=ReadingPresentation.prepare(owner.opened,owner.snapshot.copy(current=target)))
                scenario.onActivity { surface.present(screen) };ready()
                assertTrue(controller.state.value.position is Locator.EpubResource);assertFalse(controller.state.value.viewport!!.atEnd);assertNull(controller.state.value.progress)
                assertEquals(main,(controller.state.value.position as Locator.EpubResource).returnTo)
                scenario.onActivity { assertSame(originalView.get(),surface.getChildAt(0));assertTrue(controller.returnToMain()) }
                val back=navigation.get()!!;assertEquals(main,back)
                scenario.onActivity { surface.present(owner.state(back,2,presentation=ReadingPresentation.prepare(owner.opened,owner.snapshot.copy(current=back)))) };ready()
                tap("external");waitUntil { controller.uiEvent.value!=null };assertEquals(ReaderUiEvent.ExternalLink("https://example.com/read"),controller.uiEvent.value)
                scenario.onActivity { controller.dismissInteraction();assertNull(controller.confirmExternal()) }
                assertEquals(main,controller.state.value.position)
            } catch(error: Throwable) {
                try { diagnostic() } catch(diagnosticError: Throwable) { error.addSuppressed(diagnosticError) }
                throw error
            } finally { scenario.onActivity { surface.close();controller.detach() };owner.closeSource() }
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
