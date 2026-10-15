package com.localreader.app

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.SystemClock
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.localreader.app.core.*
import com.localreader.app.data.*
import com.localreader.app.formats.epub.MixedOpenedBook
import com.localreader.app.formats.epub.PackageHref
import com.localreader.app.formats.epub.PackagePath
import com.localreader.app.ui.ReaderSurface
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** R-01 crosses authored layout overrides with real auxiliary persistence and WebView acceptance. */
@RunWith(AndroidJUnit4::class)
class MixedAuxiliaryPersistenceTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var repository: LibraryRepository
    private val uris=mutableListOf<Uri>()
    @Before fun setup() {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        root=File(target.cacheDir,"mixed-auxiliary-${UUID.randomUUID()}").apply { check(mkdirs()) }
        context=object: ContextWrapper(target) {
            override fun getFilesDir()=File(root,"files").apply { mkdirs() }
            override fun getDatabasePath(name: String)=File(File(root,"databases").apply { mkdirs() },name)
            override fun openOrCreateDatabase(name: String,mode: Int,factory: SQLiteDatabase.CursorFactory?)=SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory)
            override fun openOrCreateDatabase(name: String,mode: Int,factory: SQLiteDatabase.CursorFactory?,handler: DatabaseErrorHandler?)=SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path,factory,handler)
        }
        repository=LibraryRepository(context);repository.recover()
    }
    @After fun cleanup() { repository.db.close();check(root.deleteRecursively());uris.forEach { context.contentResolver.delete(it,null,null) } }
    private fun fixture(global: String): String {
        val bytes=ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            fun entry(name: String,value: String) {
                val data=value.toByteArray();zip.putNextEntry(ZipEntry(name).apply { time=315532800000L;method=ZipEntry.STORED;size=data.size.toLong();compressedSize=size;crc=CRC32().apply { update(data) }.value });zip.write(data);zip.closeEntry()
            }
            fun html(body: String,head: String="")="<html xmlns='http://www.w3.org/1999/xhtml' xmlns:epub='http://www.idpf.org/2007/ops'><head>$head</head><body>$body</body></html>"
            entry("mimetype","application/epub+zip")
            entry("META-INF/container.xml","<container xmlns='urn:oasis:names:tc:opendocument:xmlns:container'><rootfiles><rootfile full-path='OPS/book.opf' media-type='application/oebps-package+xml'/></rootfiles></container>")
            entry("OPS/book.opf","""<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">${UUID.randomUUID()}</dc:identifier><dc:title>R01 混排附属测试</dc:title><dc:language>zh</dc:language><meta property="rendition:layout">$global</meta></metadata><manifest><item id="a" href="a.xhtml" media-type="application/xhtml+xml"/><item id="b" href="b.xhtml" media-type="application/xhtml+xml"/><item id="n" href="note.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="a" properties="rendition:layout-reflowable"/><itemref idref="b" properties="rendition:layout-pre-paginated"/></spine></package>""")
            entry("OPS/a.xhtml",html("<p id='origin'>REFLOW 主文中文😀</p><p><a epub:type='noteref' href='note.xhtml#fn'>[1]</a></p>"))
            entry("OPS/b.xhtml",html("<p id='origin'>FIXED 主文中文😀 <a epub:type='noteref' href='note.xhtml#fn'>[1]</a></p>","<meta name='viewport' content='width=600,height=800'/><style>html,body{margin:0;width:600px;height:800px}p{position:absolute;left:120px;top:240px}</style>"))
            entry("OPS/note.xhtml",html("<aside id='fn' epub:type='footnote'><p>R01 NOTE 中文😀</p><p>附属正文第二段。</p></aside>"))
        }
        val uri=Uri.parse("content://com.localreader.app.test.fixtures/mixed-aux-${UUID.randomUUID()}.epub");uris.add(uri)
        context.contentResolver.openOutputStream(uri,"wt")!!.use { it.write(bytes.toByteArray()) }
        val result=repository.import(uri);assertNotNull(result.status,result.bookId);return requireNotNull(result.bookId)
    }
    private fun waitUntil(predicate: ()->Boolean) { val deadline=SystemClock.uptimeMillis()+20000;while(!predicate()) { check(SystemClock.uptimeMillis()<deadline) { "混排附属正文等待超时" };Thread.sleep(20) } }
    private fun hash(file: File)=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun runCase(global: String,fixed: Boolean) {
        val id=fixture(global);val store=SqliteReadingStore(repository.db);val factory=LibraryOpenedBookFactory(repository)
        var prepared=runBlocking { ReaderControllerFactory(factory,store).open(id,1) }
        assertTrue(prepared.owner.opened is MixedOpenedBook)
        val original=repository.original(requireNotNull(repository.content(id)));val originalHash=hash(original)
        var origin: Locator=if(fixed)Locator.Page(prepared.owner.opened.revision,1,0.0,0.0) else Locator.Epub(prepared.owner.opened.revision,0,0)
        val navigation=AtomicReference<Locator?>()
        var controller: ReaderController?=null
        var surface: ReaderSurface?=null
        var stage="main READY"
        fun awaitStage(predicate: ()->Boolean) {
            try { waitUntil(predicate) } catch(error: Throwable) { throw AssertionError("$global fixed=$fixed stage=$stage controller=${controller?.state?.value} owner=${prepared.owner.snapshot.current}",error) }
        }
        if(InstrumentationRegistry.getArguments().getString("readerShellForeground")=="true")waitUntil {
            var resumed=false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                resumed=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).any { it is MainActivity }
            }
            resumed
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun stopRenderer() { scenario.onActivity { surface?.close();controller?.detach();surface=null;controller=null } }
            fun show(session: ReadingSession) {
                val owner=prepared.owner
                scenario.onActivity { activity ->
                    val active=ReaderController(owner.generation,{owner.bind(it)},{event -> if(event is RendererEvent.Position)owner.position(event.locator,event.intent==IntentCause.UserNavigation,SystemClock.uptimeMillis()) else true },ReaderActions(goTo={navigation.set(it)}),interactions=owner.opened.interactions)
                    activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    controller=active;surface=ReaderSurface(activity).also { it.bind(active);activity.setContentView(it);it.present(session) }
                }
                awaitStage { controller!!.state.value.phase in setOf(ReaderPhase.READY,ReaderPhase.FAILED) }
                assertEquals(controller!!.state.value.message,ReaderPhase.READY,controller!!.state.value.phase)
                assertEquals(controller!!.state.value.position,owner.snapshot.current)
                val visible=AtomicReference<String?>()
                scenario.onActivity { (surface!!.getChildAt(0) as WebView).evaluateJavascript("readerHost.diagnostics().anchorGeometry.visible") {visible.set(it)} }
                waitUntil { visible.get()!=null };assertEquals("true",visible.get())
            }
            try {
                show(prepared.owner.state(jump=origin))
                assertTrue(ReaderOperationLedger.sameCoordinate(origin,prepared.owner.snapshot.current))
                origin=prepared.owner.snapshot.current
                val mainExtent=prepared.owner.snapshot.furthest
                val note=prepared.owner.opened.interactions!!.resolve(prepared.owner.opened.publication,ContentInteraction.Link(if(fixed)1 else 0,PackageHref(PackagePath.entry("OPS/note.xhtml"),"fn").identity),origin,{}) as InteractionResult.Note
                val target=prepared.owner.opened.prepareNavigation(note.content.jump,origin) as Locator.EpubResource
                assertEquals(origin,target.returnTo);assertTrue(prepared.owner.opened.accepts(target))
                assertTrue(prepared.owner.opened.excerpt(target,120).contains("R01 NOTE"))
                val auxiliary=prepared.owner.opened.publicationFor(target);assertTrue(auxiliary.auxiliary)
                stage="auxiliary READY"
                scenario.onActivity { surface!!.present(prepared.owner.state(jump=target,serial=1,presentation=ReadingPresentation.prepare(prepared.owner.opened,prepared.owner.snapshot.copy(current=target)))) }
                awaitStage { controller!!.state.value.phase==ReaderPhase.FAILED || controller!!.state.value.position is Locator.EpubResource }
                assertEquals(controller!!.state.value.message,ReaderPhase.READY,controller!!.state.value.phase)
                val accepted=prepared.owner.snapshot.current as Locator.EpubResource
                assertEquals(origin,accepted.returnTo);assertEquals(accepted,controller!!.state.value.position)
                assertEquals(mainExtent,prepared.owner.snapshot.furthest)
                prepared.owner.flush();assertEquals(accepted,store.loadSnapshot(id).current)
                assertEquals(mainExtent,store.loadSnapshot(id).furthest)
                assertTrue(store.addBookmark(id,prepared.owner.opened.revision,accepted,prepared.owner.opened.excerpt(accepted,120)))
                assertEquals(accepted,store.bookmarks(id).single().locator)
                stopRenderer();prepared.owner.closeSource()
                prepared=runBlocking { ReaderControllerFactory(factory,store).open(id,2) }
                assertEquals(accepted,prepared.owner.snapshot.current);assertTrue(prepared.session.document.auxiliary)
                assertEquals(accepted,prepared.owner.snapshot.bookmarks.single().locator);assertEquals(mainExtent,prepared.owner.snapshot.furthest)
                stage="reopened auxiliary READY"
                show(prepared.session)
                assertEquals(accepted,prepared.owner.snapshot.current)
                scenario.onActivity { assertTrue(controller!!.returnToMain()) };assertEquals(origin,navigation.get())
                stage="return main READY"
                val returned=prepared.owner.opened.prepareNavigation(requireNotNull(navigation.get()),accepted)
                assertEquals(origin,returned)
                scenario.onActivity { surface!!.present(prepared.owner.state(jump=returned,serial=2,presentation=ReadingPresentation.prepare(prepared.owner.opened,prepared.owner.snapshot.copy(current=returned)))) }
                awaitStage { controller!!.state.value.phase==ReaderPhase.FAILED || controller!!.state.value.position==origin }
                assertEquals(controller!!.state.value.message,ReaderPhase.READY,controller!!.state.value.phase)
                assertEquals(origin,prepared.owner.snapshot.current);prepared.owner.flush();assertEquals(origin,store.loadSnapshot(id).current)
                assertEquals(accepted,store.bookmarks(id).single().locator)
                stage="bookmark auxiliary READY"
                navigation.set(null)
                scenario.onActivity { controller!!.goTo(store.bookmarks(id).single().locator) }
                assertEquals(accepted,navigation.get())
                val bookmarked=prepared.owner.opened.prepareNavigation(requireNotNull(navigation.get()),origin)
                assertEquals(accepted,bookmarked)
                scenario.onActivity { surface!!.present(prepared.owner.state(jump=bookmarked,serial=3,presentation=ReadingPresentation.prepare(prepared.owner.opened,prepared.owner.snapshot.copy(current=bookmarked)))) }
                awaitStage { controller!!.state.value.phase==ReaderPhase.FAILED || controller!!.state.value.position==accepted }
                assertEquals(controller!!.state.value.message,ReaderPhase.READY,controller!!.state.value.phase)
                assertEquals(accepted,prepared.owner.snapshot.current)
                navigation.set(null);scenario.onActivity { assertTrue(controller!!.returnToMain()) };assertEquals(origin,navigation.get())
                assertEquals(originalHash,hash(original))
            } finally { stopRenderer();prepared.owner.closeSource() }
        }
    }
    @Test fun reflowGlobalReflowReturn()=runCase("reflowable",false)
    @Test fun reflowGlobalFixedReturn()=runCase("reflowable",true)
    @Test fun fixedGlobalReflowReturn()=runCase("pre-paginated",false)
    @Test fun fixedGlobalFixedReturn()=runCase("pre-paginated",true)
}
