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
import com.localreader.app.formats.epub.MixedOpenedBook
import com.localreader.app.ui.ReaderSurface
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * OA-P2-02: in continuous scrolling, the surface's vertical-drag watcher (which hides the toolbar) reacts on the reflowable
 * group of a mixed EPUB but stays out of its fixed-layout group, whose drags pan the page. The gesture always reaches the page.
 */
@RunWith(AndroidJUnit4::class)
class MixedScrollWatchTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var repository: LibraryRepository
    private val uris=mutableListOf<Uri>()
    @Before fun setup() {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        root=File(target.cacheDir,"mixed-scroll-${UUID.randomUUID()}").apply { check(mkdirs()) }
        context=object: ContextWrapper(target) {
            override fun getFilesDir()=File(root,"files").apply { mkdirs() }
            override fun getDatabasePath(name: String)=File(File(root,"databases").apply { mkdirs() },name)
            override fun openOrCreateDatabase(name: String,mode: Int,factory: SQLiteDatabase.CursorFactory?)=SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory)
            override fun openOrCreateDatabase(name: String,mode: Int,factory: SQLiteDatabase.CursorFactory?,handler: DatabaseErrorHandler?)=SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path,factory,handler)
        }
        repository=LibraryRepository(context);repository.recover()
    }
    @After fun cleanup() { repository.db.close();check(root.deleteRecursively());uris.forEach { context.contentResolver.delete(it,null,null) } }
    private fun fixture(): String {
        val bytes=ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            fun entry(name: String,value: String) {
                val data=value.toByteArray();zip.putNextEntry(ZipEntry(name).apply { time=315532800000L;method=ZipEntry.STORED;size=data.size.toLong();compressedSize=size;crc=CRC32().apply { update(data) }.value });zip.write(data);zip.closeEntry()
            }
            fun html(body: String,head: String="")="<html xmlns='http://www.w3.org/1999/xhtml'><head>$head</head><body>$body</body></html>"
            entry("mimetype","application/epub+zip")
            entry("META-INF/container.xml","<container xmlns='urn:oasis:names:tc:opendocument:xmlns:container'><rootfiles><rootfile full-path='OPS/book.opf' media-type='application/oebps-package+xml'/></rootfiles></container>")
            entry("OPS/book.opf","""<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">${UUID.randomUUID()}</dc:identifier><dc:title>OA-P2-02 混排滚动</dc:title><dc:language>zh</dc:language><meta property="rendition:layout">reflowable</meta></metadata><manifest><item id="a" href="a.xhtml" media-type="application/xhtml+xml"/><item id="b" href="b.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="a" properties="rendition:layout-reflowable"/><itemref idref="b" properties="rendition:layout-pre-paginated"/></spine></package>""")
            entry("OPS/a.xhtml",html((1..120).joinToString("") { "<p>REFLOW 第${it}段，用于连续滚动。</p>" }))
            entry("OPS/b.xhtml",html("<p>FIXED 固定页</p>","<meta name='viewport' content='width=600,height=800'/><style>html,body{margin:0;width:600px;height:800px}p{position:absolute;left:120px;top:240px}</style>"))
        }
        val uri=Uri.parse("content://com.localreader.app.test.fixtures/mixed-scroll-${UUID.randomUUID()}.epub");uris.add(uri)
        context.contentResolver.openOutputStream(uri,"wt")!!.use { it.write(bytes.toByteArray()) }
        val result=repository.import(uri);assertNotNull(result.status,result.bookId);return requireNotNull(result.bookId)
    }
    private fun waitUntil(predicate: ()->Boolean) { val deadline=SystemClock.uptimeMillis()+20000;while(!predicate()) { check(SystemClock.uptimeMillis()<deadline) { "混排滚动等待超时" };Thread.sleep(20) } }

    @Test fun verticalDragIsWatchedOnTheReflowableGroupOnlyAndAlwaysReachesThePage() {
        val id=fixture();val store=SqliteReadingStore(repository.db)
        store.patchRules(RulesPatch(flow=FieldUpdate.Set(ReaderFlow.SCROLLED)))
        val prepared=runBlocking { ReaderControllerFactory(LibraryOpenedBookFactory(repository),store).open(id,1) }
        assertTrue(prepared.owner.opened is MixedOpenedBook)
        assertEquals(WebContentKind.EPUB,prepared.session.document.kind)
        val revision=prepared.owner.opened.revision
        val watched=AtomicInteger();val pageDowns=AtomicInteger()
        var controller: ReaderController?=null;var surface: ReaderSurface?=null
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun present(jump: Locator,serial: Int,ready: (Locator?)->Boolean) {
                scenario.onActivity { activity ->
                    if(surface==null) {
                        val owner=prepared.owner
                        val active=ReaderController(owner.generation,{owner.bind(it)},{event -> if(event is RendererEvent.Position)owner.position(event.locator,event.intent==IntentCause.UserNavigation,SystemClock.uptimeMillis()) else true },ReaderActions(),interactions=owner.opened.interactions)
                        controller=active;surface=ReaderSurface(activity).also { it.bind(active);it.onUserScroll={ watched.incrementAndGet() };activity.setContentView(it) }
                    }
                    surface!!.present(prepared.owner.state(jump=jump,serial=serial,presentation=ReadingPresentation.prepare(prepared.owner.opened,prepared.owner.snapshot.copy(current=jump))))
                }
                waitUntil { controller!!.state.value.phase==ReaderPhase.FAILED || controller!!.state.value.phase==ReaderPhase.READY && ready(controller!!.state.value.position) }
                assertEquals(controller!!.state.value.message,ReaderPhase.READY,controller!!.state.value.phase)
                scenario.onActivity { (surface!!.getChildAt(0) as WebView).setOnTouchListener { _,event -> if(event.actionMasked==MotionEvent.ACTION_DOWN)pageDowns.incrementAndGet();false } }
            }
            fun drag() {
                var x=0f;var y=0f;var height=0
                scenario.onActivity { val location=IntArray(2);surface!!.getLocationOnScreen(location);x=location[0]+surface!!.width*.5f;y=location[1]+surface!!.height*.7f;height=surface!!.height }
                val down=SystemClock.uptimeMillis();val distance=height*.4f
                fun send(action: Int,dy: Float) { val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y-dy,0);try { instrumentation.sendPointerSync(event) } finally { event.recycle() } }
                send(MotionEvent.ACTION_DOWN,0f)
                for(step in 1..20) { Thread.sleep(16);send(MotionEvent.ACTION_MOVE,distance*step/20) }
                send(MotionEvent.ACTION_UP,distance);instrumentation.waitForIdleSync()
            }
            try {
                // Reflowable group in continuous scrolling: one drag, one watcher call.
                present(Locator.Epub(revision,0,0),0) { it is Locator.Epub }
                drag();assertEquals("text group",1,watched.get());assertEquals(1,pageDowns.get())
                // Fixed-layout group of the same book: the drag pans the page and the text-scroll watcher stays out.
                present(Locator.Page(revision,1,0.0,0.0),1) { it is Locator.Page }
                drag();assertEquals("fixed group",1,watched.get());assertEquals("the page still receives the gesture",2,pageDowns.get())
                // And back on the text group the watcher works again.
                present(Locator.Epub(revision,0,0),2) { it is Locator.Epub }
                drag();assertEquals("text group again",2,watched.get());assertEquals(3,pageDowns.get())
            } finally { scenario.onActivity { surface?.close();controller?.detach() };prepared.owner.closeSource() }
        }
    }
}
