package com.localreader.app

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.localreader.app.core.*
import com.localreader.app.data.*
import com.localreader.app.formats.epub.*
import com.localreader.app.ui.ReaderSurface
import com.localreader.app.ui.WebReaderAdapter
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class EpubPersistenceTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var repository: LibraryRepository
    private val uris=mutableListOf<Uri>()
    @Before fun setup() {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        root=File(target.cacheDir,"epub-test-${UUID.randomUUID()}").apply { mkdirs() }
        context=object: ContextWrapper(target) {
            override fun getFilesDir()=File(root,"files").apply { mkdirs() }
            override fun getDatabasePath(name: String)=File(File(root,"databases").apply { mkdirs() },name)
            override fun openOrCreateDatabase(name: String,mode: Int,factory: SQLiteDatabase.CursorFactory?)=SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory)
            override fun openOrCreateDatabase(name: String,mode: Int,factory: SQLiteDatabase.CursorFactory?,handler: DatabaseErrorHandler?)=SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path,factory,handler)
        }
        repository=LibraryRepository(context);repository.recover()
    }
    @After fun cleanup() { repository.db.close();root.deleteRecursively();uris.forEach { context.contentResolver.delete(it,null,null) } }
    private fun fixture(bytes: ByteArray,suffix: String="epub"): Uri {
        val uri=Uri.parse("content://com.localreader.app.test.fixtures/epub-${UUID.randomUUID()}.$suffix")
        context.contentResolver.openOutputStream(uri,"wt")!!.use { it.write(bytes) };uris.add(uri);return uri
    }
    private fun import(bytes: ByteArray,suffix: String="epub"): String {
        val result=repository.import(fixture(bytes,suffix));assertNotNull(result.status,result.bookId);return requireNotNull(result.bookId)
    }
    private fun xml(body: String)="<?xml version='1.0' encoding='UTF-8'?><html xmlns='http://www.w3.org/1999/xhtml'><head><link rel='stylesheet' href='book.css'/></head><body>$body</body></html>"
    private fun epub(font: ByteArray?=null,algorithm: String?=null,ncx: Boolean=false,nav: Boolean=true,gallery: Int=0): ByteArray {
        val identifier=if(algorithm?.contains("adobe")==true)"urn:uuid:12345678-1234-5678-1234-567812345678" else "\u00a0 urn:test: raw-key \u00a0"
        val output=ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            fun entry(name: String,bytes: ByteArray,stored: Boolean=false) {
                val item=ZipEntry(name).apply { time=315532800000L;if(stored) { method=ZipEntry.STORED;size=bytes.size.toLong();compressedSize=size;crc=CRC32().apply { update(bytes) }.value } }
                zip.putNextEntry(item);zip.write(bytes);zip.closeEntry()
            }
            fun text(name: String,text: String)=entry(name,text.toByteArray())
            entry("mimetype","application/epub+zip".toByteArray(),true)
            text("META-INF/container.xml","<container xmlns='urn:oasis:names:tc:opendocument:xmlns:container' version='1.0'><rootfiles><rootfile full-path='OPS/book.opf' media-type='application/oebps-package+xml'/></rootfiles></container>")
            text("OPS/book.opf","""<package xmlns="http://www.idpf.org/2007/opf" version="${if(ncx)"2.0" else "3.0"}" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="uid">$identifier</dc:identifier><dc:title>原创 EPUB 闭环</dc:title><dc:creator>测试作者</dc:creator><dc:language>zh-CN</dc:language></metadata><manifest><item id="a" href="first.xhtml" media-type="application/xhtml+xml"/><item id="b" href="second.xhtml" media-type="application/xhtml+xml"/><item id="c" href="images.xhtml" media-type="application/xhtml+xml"/><item id="css" href="book.css" media-type="text/css"/><item id="image" href="image.svg" media-type="image/svg+xml" properties="cover-image"/>${if(nav)"<item id='nav' href='nav.xhtml' media-type='application/xhtml+xml' properties='nav'/>" else ""}${if(ncx)"<item id='ncx' href='toc.ncx' media-type='application/x-dtbncx+xml'/>" else ""}${if(font!=null)"<item id='font' href='font.ttf' media-type='font/ttf'/>" else ""}</manifest><spine ${if(ncx)"toc='ncx'" else ""}><itemref idref="a"/><itemref idref="b"/><itemref idref="c"/></spine></package>""")
            text("OPS/first.xhtml",xml("\n    <p id='same'>中文😀 English 123 <em>italic</em>&#160;尾</p>\n    <p>正文第一章。</p><script src='evil'/><p id='after'>自闭合之后的安全正文</p>"))
            text("OPS/second.xhtml",xml("<h1 id='same'>第二章</h1><p class='semantic-font'>语义<span class='reset-font'>默认</span><span class='angled-font'>倾斜</span><span class='priority-font' style='font:italic 700 18px PackageFont!important;font-weight:400'>重要</span></p>"+"<p>可重排正文 English 123 汉字段落😀。</p>".repeat(80)+"<table><tr><td id='cell'>Table cell</td></tr></table>"))
            text("OPS/images.xhtml",xml(if(gallery>0)(0 until gallery).joinToString("") { "<img id='gallery-$it' src='image.svg' style='width:12px;height:12px'/>" } else "<img id='one' src='image.svg'/><img id='two' src='image.svg'/><a href='second.xhtml#same'>章节链接</a>"))
            text("OPS/image.svg","<s:svg xmlns:s='http://www.w3.org/2000/svg' xmlns:l='http://www.w3.org/1999/xlink' viewBox='0 0 480 240'><s:defs><s:rect id='tile' width='480' height='240' fill='#66a'/></s:defs><s:use l:href='#tile'/><s:text x='20' y='100'>LOCAL IMAGE</s:text></s:svg>")
            text("OPS/book.css",(if(font!=null)"@font-face{font-family:PackageFont;src:local('NoExternal'),url('font.ttf')}body{font-family:PackageFont}" else "")+"p{color:rgb(255 0 0);width:calc(100% - 2em);margin:1em 0}em{font-style:italic}.semantic-font{font:italic 700 18px/1.4 PackageFont}.reset-font{font:18px PackageFont}.angled-font{font:oblique 10deg 600 18px/1.4 PackageFont}")
            if(nav)text("OPS/nav.xhtml","<html xmlns='http://www.w3.org/1999/xhtml' xmlns:ops='http://www.idpf.org/2007/ops'><body><nav ops:type='toc'><ol><li><a href='first.xhtml#same'>第一章</a><ol><li><a href='second.xhtml#same'>第二章</a><ol><li><a href='second.xhtml#cell'>表格</a></li></ol></li></ol></li><li><a href='images.xhtml#one'>图片</a></li></ol></nav></body></html>")
            if(ncx)text("OPS/toc.ncx","<ncx xmlns='http://www.daisy.org/z3986/2005/ncx/'><navMap><navPoint id='n1'><navLabel><text>NCX第一章</text></navLabel><content src='first.xhtml#same'/><navPoint id='n2'><navLabel><text>NCX第二章</text></navLabel><content src='second.xhtml#same'/></navPoint></navPoint></navMap></ncx>")
            if(font!=null)entry("OPS/font.ttf",if(algorithm!=null)FontObfuscation.decode(font,identifier,algorithm) else font)
            if(algorithm!=null)text("META-INF/encryption.xml","<encryption xmlns='urn:oasis:names:tc:opendocument:xmlns:container' xmlns:e='http://www.w3.org/2001/04/xmlenc#'><e:EncryptedData><e:EncryptionMethod Algorithm='$algorithm'/><e:CipherData><e:CipherReference URI='OPS/font.ttf'/></e:CipherData></e:EncryptedData></encryption>")
        }
        return output.toByteArray().also { bytes ->
            fun word(offset: Int)=(bytes[offset].toInt() and 255) or ((bytes[offset+1].toInt() and 255) shl 8)
            // Android adds a timestamp extra for pre-DOS dates. EPUB's first
            // mimetype entry must be stored with no local extra field.
            assertEquals(ZipEntry.STORED,word(8));assertEquals(0,word(28));assertEquals(8,word(26))
            assertEquals("mimetype",bytes.copyOfRange(30,38).toString(Charsets.US_ASCII))
        }
    }
    @Test fun safMagicFactoryCanonicalTocAndSqliteRestartAreReal() {
        val bytes=epub();val id=import(bytes,"txt") // PK magic must win over text/plain/name.
        val record=requireNotNull(repository.db.content(id));assertEquals(ContentFormat.EPUB,record.format);assertEquals("原创 EPUB 闭环",record.title);assertEquals("测试作者",record.author);assertNotNull(record.coverPath)
        assertTrue(File(context.filesDir,"library/$id/original.epub").readBytes().contentEquals(bytes))
        val store=SqliteReadingStore(repository.db)
        val opened=runBlocking { LibraryOpenedBookFactory(repository).open(id) }
        try {
            assertEquals(listOf(0,1,2,0),opened.toc().map { it.depth });assertTrue(opened.excerpt(opened.locatorForToc("epub-toc:0")!!,100).contains("中文😀"))
            val publication=opened.publication as EpubPublication
            val text=EpubSource(repository.directory(record),repository.db.epubCache(id)).use { it.text(0) }
            assertTrue(text.contains("自闭合之后的安全正文"))
            val first=requireNotNull(publication.locator(RendererAnchor.Text(1,3)))
            val far=EpubReadingExtent(opened.revision,1,100)
            store.savePosition(id,opened.revision,first,far)
            assertTrue(store.addBookmark(id,opened.revision,first,opened.excerpt(first,100)))
            val imageSection=publication.record.sections[2]
            val imageLocators=imageSection.elements.keys.take(2).map { requireNotNull(publication.locator(RendererAnchor.Element(2,it))) }
            assertEquals(2,imageLocators.size);assertEquals(imageLocators[0].offset,imageLocators[1].offset)
            imageLocators.forEach { assertTrue(store.addBookmark(id,opened.revision,it,"图片")) }
            store.patchRules(id,opened.revision,RulesPatch(epubStyleMode=FieldUpdate.Set("CUSTOM"),fontSize=FieldUpdate.Set(22f)))
            assertEquals(id,repository.import(fixture(bytes)).bookId)
        } finally { opened.close() }
        repository.db.close();val reopened=LibraryRepository(context);reopened.recover()
        try { val snapshot=SqliteReadingStore(reopened.db).loadSnapshot(id)
            assertEquals(1,(snapshot.current as Locator.Epub).spine);assertEquals(3,(snapshot.current as Locator.Epub).offset);assertEquals(EpubReadingExtent(record.revision,1,100),snapshot.furthest)
            assertEquals(3,snapshot.bookmarks.size);assertEquals("CUSTOM",snapshot.rules.epubStyleMode);assertEquals(22f,snapshot.rules.fontSize)
        } finally { reopened.db.close() }
    }
    @Test fun packagedIdpfAndAdobeFontsDecodeToExactOriginalBytes() {
        val font=context.assets.open("fonts/Bitter-Regular-VF.ttf").use { it.readBytes() }
        listOf("http://www.idpf.org/2008/embedding","http://ns.adobe.com/pdf/enc#RC").forEach { algorithm ->
            val id=import(epub(font,algorithm));val cache=repository.db.epubCache(id)
            val resource=cache.resources.single { it.mime.startsWith("font/") }
            EpubSource(repository.directory(requireNotNull(repository.db.content(id))),cache).use { source -> assertArrayEquals(font,source.open(resource.file,resource.bytes).use { it.readBytes() }) }
        }
    }
    @Test fun epubTwoNcxAndMissingTocFallbackAreConsumed() {
        val ncx=import(epub(ncx=true,nav=false));val fallback=import(epub(nav=false))
        runBlocking { LibraryOpenedBookFactory(repository).open(ncx) }.use { book -> assertEquals(listOf("NCX第一章","NCX第二章"),book.toc().map { it.title });assertEquals(1,(book.locatorForToc(book.toc()[1].id) as Locator.Epub).spine) }
        runBlocking { LibraryOpenedBookFactory(repository).open(fallback) }.use { book -> assertEquals(3,book.toc().size);assertNotNull(book.locatorForToc(book.toc()[2].id)) }
    }
    private fun searchEpub(bodies: List<String>,css: String="",direction: String?=null): ByteArray {
        val output=ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            fun text(name: String,value: String) {
                val bytes=value.toByteArray();val entry=ZipEntry(name).apply { time=315532800000L;method=ZipEntry.STORED;size=bytes.size.toLong();compressedSize=size;crc=CRC32().apply { update(bytes) }.value }
                zip.putNextEntry(entry);zip.write(bytes);zip.closeEntry()
            }
            text("mimetype","application/epub+zip")
            text("META-INF/container.xml","<container><rootfiles><rootfile full-path='book.opf' media-type='application/oebps-package+xml'/></rootfiles></container>")
            val manifest=bodies.indices.joinToString("") { "<item id='s$it' href='s$it.xhtml' media-type='application/xhtml+xml'/>" }
            val spine=bodies.indices.joinToString("") { "<itemref idref='s$it'/>" }
            text("book.opf","<package version='3.0' unique-identifier='id'><metadata xmlns:dc='http://purl.org/dc/elements/1.1/'><dc:identifier id='id'>search-${UUID.randomUUID()}</dc:identifier><dc:title>真实搜索输入</dc:title></metadata><manifest>$manifest</manifest><spine${direction?.let {" page-progression-direction='$it'"} ?: ""}>$spine</spine></package>")
            bodies.forEachIndexed { index,body -> text("s$index.xhtml","<html xmlns='http://www.w3.org/1999/xhtml'><head><style>$css</style></head><body>$body</body></html>") }
        }
        return output.toByteArray()
    }
    @Test fun txtSearchDecodesRealEncodingsPreservesNewlineContextAndLegacyContinuation() {
        val variants=listOf("UTF-8" to "ΟΣ\nΑ", "UTF-16LE" to "ΟΣ\r\nΑ", "UTF-16BE" to "ΟΣ\rΑ", "GB18030" to "中文甲乙", "Big5" to "中文甲乙", "Shift_JIS" to "日本語", "ISO-8859-1" to "café")
        for((encoding,value) in variants) {
            val directory=File(root,"txt-search-${UUID.randomUUID()}").apply { mkdirs() }
            val original=File(directory,"original.txt");original.writeBytes(value.toByteArray(java.nio.charset.Charset.forName(encoding)))
            TxtParser.build(original,directory,encoding)
            com.localreader.app.formats.txt.TxtOpenedBook("own",ContentMetadata("搜索",""),TxtEngine(directory,"v"),original,File(directory,"queries").apply { mkdirs() }).use { opened ->
                val search=requireNotNull(opened.search)
                val query=if(value.startsWith("ΟΣ"))"ς" else value
                val hit=search.scan(query).first.single();assertEquals(if(value.startsWith("ΟΣ"))1L else 0L,(hit.locator as Locator.Text).sourceOffset)
                assertTrue(opened.accepts(hit.locator));assertFalse(search.scan(if(value.startsWith("ΟΣ"))"ΣΑ" else value+"missing").first.isNotEmpty())
                assertTrue(File(directory,"queries").listFiles().orEmpty().isEmpty())
            }
        }
        val directory=File(root,"legacy-search").apply { mkdirs() };val original=File(directory,"original.txt")
        val value="x".repeat(2047)+"BC"+"y".repeat(2048)+"\nBC\n"
        original.writeText(value);TxtParser.build(original,directory)
        java.io.RandomAccessFile(File(directory,"meta.bin"),"rw").use { it.writeInt(1) }
        java.io.RandomAccessFile(File(directory,"index.bin"),"rw").use { file -> var at=24L;while(at<file.length()) { file.seek(at);val flags=file.readInt();file.seek(at);file.writeInt(flags and 1);at+=28 } }
        com.localreader.app.formats.txt.TxtOpenedBook("legacy",ContentMetadata("旧索引",""),TxtEngine(directory,"old"),original,File(directory,"queries").apply { mkdirs() }).use { opened ->
            val hits=requireNotNull(opened.search).scan("BC").first;assertEquals(listOf(2047L,4098L),hits.map { (it.locator as Locator.Text).sourceOffset });assertTrue(hits.all { opened.accepts(it.locator) })
            assertTrue(requireNotNull(opened.search).scan("yBC").first.isEmpty())
        }
    }
    @Test fun epubSearchUsesWholeLogicalContextThenRealParagraphCutsAndExactTwoHundredLimit() {
        val id=import(searchEpub(listOf("前<div>AB<span>C</span><p>D</p>E</div>后<p>ΟΣ</p><p>Α</p><p>e</p><p>&#x301;</p>","<p>"+"甲".repeat(400000)+"跨部分命中"+"乙".repeat(1000)+"</p>")))
        runBlocking { LibraryOpenedBookFactory(repository).open(id) }.use { opened ->
            val search=requireNotNull(opened.search)
            assertEquals(2,(search.scan("BC").first.single().locator as Locator.Epub).offset)
            listOf("CD","DE","é","οσα","ΟΣ").forEach { assertTrue(it,search.scan(it).first.isEmpty()) }
            val greek=search.scan("οσ").first.single();assertEquals(7,(greek.locator as Locator.Epub).offset)
            val long=search.scan("跨部分命中").first.single();val locator=long.locator as Locator.Epub;assertEquals(1,locator.spine);assertEquals(400000,locator.offset);assertTrue(opened.publication.target(locator).section>1)
            assertTrue(opened.accepts(locator));assertEquals("跨部分命中",long.excerpt.text.substring(long.excerpt.matchStart,long.excerpt.matchEnd))
            val full=search.scan("甲");assertEquals(200,full.first.size);assertTrue(full.second)
        }
        val exact=import(searchEpub(listOf("<p>"+"甲".repeat(200)+"</p>","<p>无匹配</p>")))
        runBlocking { LibraryOpenedBookFactory(repository).open(exact) }.use { opened -> val result=requireNotNull(opened.search).scan("甲");assertEquals(200,result.first.size);assertFalse(result.second) }
    }
    @Test fun actualControllerSearchResultNavigatesVisibleGlyphAndCancelsOldGeneration() {
        val id=import(searchEpub(listOf("<p>第一章前言</p>","<p>"+"可见正文段落。".repeat(120)+"独立搜索标记😀结尾</p>")))
        val book=runBlocking { LibraryOpenedBookFactory(repository).open(id) };val search=requireNotNull(book.search)
        val requested=AtomicReference<Locator?>();val position=AtomicReference<Locator?>();val error=AtomicReference<String?>()
        val store=SqliteReadingStore(repository.db);val owner=ReadingSessionOwner(book,1,store,store.loadSnapshot(id))
        val controller=ReaderController(1,owner::bind,{ event -> if(event is RendererEvent.Position)owner.position(event.locator,false,System.currentTimeMillis()) else true },ReaderActions(goTo={requested.set(it)}),search,book::paginationChapterEnd)
        lateinit var adapter: WebReaderAdapter;var created=false
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { controller.search("");assertEquals(com.localreader.app.core.search.SearchPhase.IDLE,controller.searchState.value.phase);controller.search("可见");controller.search("独立搜索标记") }
                waitUntil { controller.searchState.value.phase==com.localreader.app.core.search.SearchPhase.DONE || controller.searchState.value.phase==com.localreader.app.core.search.SearchPhase.FAILED }
                assertEquals(controller.searchState.value.message,com.localreader.app.core.search.SearchPhase.DONE,controller.searchState.value.phase)
                assertEquals("独立搜索标记",controller.searchState.value.query);val hit=controller.searchState.value.hits.single()
                scenario.onActivity { assertTrue(controller.goToSearch(hit.id)) }
                val session=owner.state(requireNotNull(requested.get()),1)
                scenario.onActivity { adapter=WebReaderAdapter(it) { event -> controller.receive(adapter,event);when(event) { is RendererEvent.Position -> position.set(event.locator);is RendererEvent.Error -> error.set(event.message);else -> Unit } };created=true;it.setContentView(adapter.view);controller.attach(adapter);controller.present(session) }
                waitUntil { controller.state.value.phase in listOf(ReaderPhase.READY,ReaderPhase.FAILED) };assertNull(error.get());assertEquals((hit.locator as Locator.Epub).offset,(position.get() as Locator.Epub).offset)
                val estimate=requireNotNull(controller.pageForSearch(hit.id));assertTrue(estimate.estimated);assertTrue(estimate.page in 1..requireNotNull(controller.state.value.progress).pages)
                assertNull(controller.pageForSearch("missing-hit"))
                val firstSignature=requireNotNull(controller.state.value.viewport).layoutSig
                owner.rules(owner.snapshot.rules.copy(fontSize=24f));val reflow=owner.state(hit.locator,1)
                scenario.onActivity { controller.present(reflow);assertNull(controller.pageForSearch(hit.id)) }
                waitUntil { controller.state.value.phase==ReaderPhase.FAILED || controller.state.value.phase==ReaderPhase.READY && controller.state.value.viewport?.layoutSig!=firstSignature }
                assertEquals(controller.state.value.message,ReaderPhase.READY,controller.state.value.phase);assertEquals(hit.locator,controller.state.value.position)
                assertTrue(requireNotNull(controller.pageForSearch(hit.id)).estimated)
                val diagnostic=AtomicReference<String?>();scenario.onActivity { adapter.view.evaluateJavascript("JSON.stringify(readerHost.diagnostics())") { diagnostic.set(it) } };waitUntil { diagnostic.get()!=null }
                assertTrue(JSONObject(org.json.JSONTokener(diagnostic.get()).nextValue() as String).getJSONObject("anchorGeometry").getBoolean("visible"))
                scenario.onActivity { controller.search("甲");controller.cancelSearch();controller.detach() }
                assertEquals(com.localreader.app.core.search.SearchPhase.CANCELED,controller.searchState.value.phase)
                assertFalse(controller.goToSearch(hit.id))
            } finally { scenario.onActivity { if(created)adapter.close();controller.detach();owner.revokeRenderers();owner.closeSource() } }
        }
    }
    @Test fun textViewportMapsVisibleOriginalBlocksEmptyCaretAndLastPageWithoutAdvancingExtent() {
        val text="Chapter 1 Start\n"+"Original English paragraph 中文😀 ends.\n".repeat(100)+"Chapter 2 End\nLast paragraph.\n\n"
        val id=import(text.toByteArray(),"txt");val book=runBlocking { LibraryOpenedBookFactory(repository).open(id) };val store=SqliteReadingStore(repository.db)
        val snapshot=store.loadSnapshot(id);val owner=ReadingSessionOwner(book,1,store,snapshot)
        val controller=ReaderController(1,{ owner.bind(it) },{ event -> if(event is RendererEvent.Position)owner.position(event.locator,event.intent==IntentCause.UserNavigation,System.currentTimeMillis()) else true },chapterEnd=book::paginationChapterEnd)
        lateinit var surface: ReaderSurface
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun located() { waitUntil { controller.state.value.phase in listOf(ReaderPhase.READY,ReaderPhase.FAILED) };assertEquals(controller.state.value.message,ReaderPhase.READY,controller.state.value.phase) }
            try {
                val initial=owner.state()
                scenario.onActivity { surface=ReaderSurface(it);surface.bind(controller);it.setContentView(surface);surface.present(initial) };located()
                val viewport=requireNotNull(controller.state.value.viewport);val position=requireNotNull(controller.state.value.position)
                assertTrue(viewport.contains(position));assertFalse(viewport.atEnd);assertTrue(viewport.visibleRanges.any { it.start is Locator.Text });assertTrue(requireNotNull(controller.state.value.progress).pagesEstimated)
                assertTrue(store.addBookmark(id,book.revision,position,"visible original position"));owner.bookmarks(store.bookmarks(id));val withBookmark=owner.state();scenario.onActivity { surface.present(withBookmark) }
                assertEquals(1,controller.state.value.currentPageBookmarks.size)
                val chapter=book.toc().last();val target=requireNotNull(book.locatorForToc(chapter.id));val jump=owner.state(target,1)
                scenario.onActivity { surface.present(jump) };located()
                assertTrue(requireNotNull(controller.state.value.viewport).atEnd);assertTrue(controller.state.value.endPrompt)
                assertEquals(snapshot.furthest,owner.snapshot.furthest);assertTrue(book.fractionOf(controller.state.value.position!!)<1.0)
                assertTrue(controller.state.value.currentPageBookmarks.isEmpty());controller.dismissEnd()
                owner.rules(owner.snapshot.rules.copy(fontSize=24f,flow=ReaderFlow.SCROLLED));val reflow=owner.state(target,1)
                scenario.onActivity { surface.present(reflow) };located()
                val end=requireNotNull(controller.state.value.viewport);assertTrue(end.atEnd);assertFalse(controller.state.value.endPrompt);assertNotEquals(viewport.layoutSig,end.layoutSig);assertTrue(requireNotNull(controller.state.value.progress).pagesEstimated)
                assertTrue(end.visiblePoints.isNotEmpty());assertTrue(end.visiblePoints.all { end.contains(it) });owner.flush();assertNull(repository.content(id)!!.finishedAt)
            } finally { scenario.onActivity { surface.close();controller.detach();owner.revokeRenderers();owner.closeSource() } }
        }
    }
    @Test fun denseRubyAndHiddenInlineRunsPublishExactCurrentPageMembership() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for(ruby in listOf(true,false)) {
                val unit=if(ruby)"<ruby><span>字</span><rt><span>zi</span></rt></ruby>" else "<span>A</span><span style='display:none'>H</span><span>B</span>"
                val id=import(searchEpub(listOf("<p style='font:8px serif!important;line-height:2'>${unit.repeat(600)}</p>")))
                val book=runBlocking { LibraryOpenedBookFactory(repository).open(id) }
                val viewport=AtomicReference<ReadingViewport?>();val error=AtomicReference<String?>();lateinit var adapter: WebReaderAdapter
                try {
                    scenario.onActivity { activity -> adapter=WebReaderAdapter(activity) { event -> when(event) { is RendererEvent.Position -> viewport.set(event.viewport);is RendererEvent.Error -> error.set(event.message);else -> Unit } };activity.setContentView(adapter.view);adapter.open(book.publication,Locator.Epub(book.revision,0,0),ReaderRules()) }
                    waitUntil { viewport.get()!=null || error.get()!=null };assertNull(error.get())
                    val actual=requireNotNull(viewport.get())
                    for(offset in 0 until 1800)assertEquals("ruby=$ruby source offset=$offset",ruby || offset%3!=1,actual.contains(Locator.Epub(book.revision,0,offset)))
                    if(!ruby)assertTrue(actual.visibleRanges.size>256)
                } finally { scenario.onActivity { adapter.close() };book.close() }
            }
        }
    }
    @Test fun denseImageOnlyPageRetainsEveryDistinctElementBookmark() {
        val id=import(epub(gallery=300));val book=runBlocking { LibraryOpenedBookFactory(repository).open(id) }
        val elements=repository.db.epubCache(id).sections[2].elements.keys.map { requireNotNull(book.publication.locator(RendererAnchor.Element(2,it))) }
        assertEquals(300,elements.size)
        val viewport=AtomicReference<ReadingViewport?>();val error=AtomicReference<String?>();lateinit var adapter: WebReaderAdapter
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { activity -> adapter=WebReaderAdapter(activity) { event -> when(event) { is RendererEvent.Position -> viewport.set(event.viewport);is RendererEvent.Error -> error.set(event.message);else -> Unit } };activity.setContentView(adapter.view);adapter.open(book.publication,elements.first(),ReaderRules()) }
                waitUntil { viewport.get()!=null || error.get()!=null };assertNull(error.get())
                val actual=requireNotNull(viewport.get());assertEquals(300,actual.visibleElements.size);assertTrue(elements.all(actual::contains))
                val store=SqliteReadingStore(repository.db)
                for((index,element) in elements.withIndex())assertTrue(store.addBookmark(id,book.revision,element,"图片 $index"))
                assertEquals(300,store.bookmarks(id).size);assertTrue(store.bookmarks(id).all { actual.contains(it.locator) })
            } finally { scenario.onActivity { adapter.close() };book.close() }
        }
    }
    private fun waitUntil(timeout: Long=16000,predicate: () -> Boolean) {
        val end=System.currentTimeMillis()+timeout
        while(!predicate()) { if(System.currentTimeMillis()>end)throw AssertionError("EPUB runtime condition timed out");Thread.sleep(30) }
    }
    @Test fun txtProjectionFiltersNaturalEmptyRowsAndRestoresRawAnchorsAcrossTechnicalParts() {
        val text="  中文😀  \r\n\n"+" ".repeat(3000)+"源字符英 ".repeat(18000)+" ".repeat(3000)+"\n末尾"
        val id=import(text.toByteArray(),"txt");val record=requireNotNull(repository.db.txt(id))
        val original=requireNotNull(repository.db.content(id)).let(repository::original);val originalBytes=original.readBytes()
        val directory=repository.directory(record)
        val old=listOf("index.bin","text.bin","meta.bin").associateWith {File(directory,it).readBytes()}
        val book=runBlocking {LibraryOpenedBookFactory(repository).open(id)}
        val store=SqliteReadingStore(repository.db);val snapshot=store.loadSnapshot(id)
        val owner=ReadingSessionOwner(book,1,store,snapshot)
        val current=AtomicReference<RendererEvent.Position?>();val failed=AtomicReference<String?>();lateinit var adapter: WebReaderAdapter;var attached=false
        ActivityScenario.launch(MainActivity::class.java).use {scenario ->
            fun command(action: ()->Unit) {current.set(null);scenario.onActivity {action()};waitUntil {current.get()!=null || failed.get()!=null};assertNull(failed.get())}
            fun visible() {
                val result=AtomicReference<String?>();scenario.onActivity {adapter.view.evaluateJavascript("JSON.stringify(readerHost.diagnostics())") {result.set(it)}}
                waitUntil {result.get()!=null};assertTrue(JSONObject(org.json.JSONTokener(result.get()).nextValue() as String).getJSONObject("anchorGeometry").getBoolean("visible"))
            }
            try {
                repository.engine(record).use {source ->
                    assertTrue(book.publication.sectionCount>1)
                    val retained=Locator.Text(source.version,2,0,2)
                    val prefix=book.publication.target(Locator.Text(source.version,0,0,0))
                    assertTrue(prefix.nearby);assertEquals(0,prefix.character)
                    val whitespace=source.block(1);assertTrue(book.publication.target(Locator.Text(source.version,whitespace.sourceOffset,1)).nearby)
                    val nativeTargets=listOf(retained,source.resolve(Locator.Text(source.version,50000,0)).let {resolved ->val block=source.block(resolved.paragraph);Locator.Text(source.version,block.sourceOffset+resolved.character,resolved.paragraph,resolved.character)},source.block(source.index.paragraphs-1).let {Locator.Text(source.version,it.sourceOffset,it.index)})
                    nativeTargets.forEach {store.addBookmark(id,book.revision,it,"owned projected raw anchor")}
                    val saved=store.bookmarks(id)
                    scenario.onActivity {activity ->
                        adapter=WebReaderAdapter(activity) {event ->when(event) {is RendererEvent.Position -> {owner.position(event.locator,false,System.currentTimeMillis());current.set(event)};is RendererEvent.Error ->failed.set(event.message);else ->Unit}}
                        attached=true;activity.setContentView(adapter.view);adapter.open(book.publication,nativeTargets.first(),snapshot.rules)
                    }
                    waitUntil {current.get()!=null || failed.get()!=null};assertNull(failed.get());visible()
                    for(target in nativeTargets) {
                        command {adapter.goTo(target)};assertEquals(target.sourceOffset,(requireNotNull(current.get()).locator as Locator.Text).sourceOffset);visible()
                        command {adapter.setRules(snapshot.rules.copy(fontSize=24f),target)};assertEquals(target.sourceOffset,(requireNotNull(current.get()).locator as Locator.Text).sourceOffset);visible()
                    }
                    val first=org.jsoup.Jsoup.parse(book.publication.section(0).html)
                    assertEquals("中文😀",first.select("[data-block=0]").text())
                    assertTrue(first.select("p[hidden]").isNotEmpty())
                    assertEquals(1,first.select("h2[data-generated]").size)
                    val hits=requireNotNull(book.search).scan("中文😀").first
                    assertEquals(2L,(hits.single().locator as Locator.Text).sourceOffset)
                    assertEquals(saved,store.bookmarks(id));assertEquals(snapshot.furthest,owner.snapshot.furthest)
                }
            } finally {try {if(attached)scenario.onActivity {adapter.close()}} finally {book.close()}}
        }
        assertArrayEquals(originalBytes,original.readBytes());old.forEach {(name,bytes) ->assertArrayEquals(bytes,File(directory,name).readBytes())}
    }
    @Test fun authoredVerticalRootsKeepSourceBookmarksExactPageReturnAndBothFlows() {
        val text="ACTUAL TRUSTED INPUT 中文😀 ".repeat(300)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for(mode in listOf("vertical-rl","vertical-lr"))for(declaration in listOf("html","body","p"))for(ppd in listOf<String?>(null,if(mode=="vertical-rl")"rtl" else "ltr")) {
                val id=import(searchEpub(listOf("<p style='margin:0'>$text</p>"),"$declaration{writing-mode:$mode}",ppd))
                val book=runBlocking {LibraryOpenedBookFactory(repository).open(id)}
                val original=requireNotNull(repository.db.content(id)).let(repository::original).readBytes()
                val store=SqliteReadingStore(repository.db);val snapshot=store.loadSnapshot(id)
                val owner=ReadingSessionOwner(book,1,store,snapshot)
                val controller=ReaderController(1,owner::bind,{event ->if(event is RendererEvent.Position)owner.position(event.locator,false,System.currentTimeMillis()) else true},chapterEnd=book::paginationChapterEnd)
                val events=java.util.concurrent.atomic.AtomicInteger();val failure=AtomicReference<String?>();lateinit var adapter: WebReaderAdapter
                fun located(before: Int) {waitUntil {events.get()>before || failure.get()!=null};assertNull("$mode/$declaration/$ppd",failure.get());assertEquals(ReaderPhase.READY,controller.state.value.phase)}
                fun evaluate(script: String): JSONObject {
                    val value=AtomicReference<String?>();scenario.onActivity {adapter.view.evaluateJavascript(script) {value.set(it)}}
                    waitUntil {value.get()!=null};return JSONObject(org.json.JSONTokener(value.get()).nextValue() as String)
                }
                try {
                    scenario.onActivity {activity ->
                        adapter=WebReaderAdapter(activity) {event ->controller.receive(adapter,event);when(event) {
                            is RendererEvent.Position ->events.incrementAndGet()
                            is RendererEvent.Error ->failure.set(event.message)
                            else ->Unit
                        }}
                        activity.setContentView(adapter.view);adapter.setViewportInsets(ViewportInsets(0,64,0,184));controller.attach(adapter);controller.present(owner.state())
                    }
                    located(0)
                    val first=controller.state.value.position as Locator.Epub;assertEquals(0,first.offset)
                    val initial=evaluate("""JSON.stringify((()=>{const r=document.getElementById('renderer'),d=r.getContents().find(x=>x.index!==undefined).doc;return {writing:d.defaultView.getComputedStyle(d.querySelector('p')).writingMode,columns:r.columnCount,columnWidth:d.defaultView.getComputedStyle(d.documentElement).columnWidth}})())""")
                    assertEquals(mode,initial.getString("writing"));assertTrue(initial.getInt("columns")>=1);assertTrue(initial.getString("columnWidth").removeSuffix("px").toDouble()>0)
                    var before=events.get();scenario.onActivity {controller.next()};located(before)
                    assertTrue((controller.state.value.position as Locator.Epub).offset>first.offset)
                    before=events.get();scenario.onActivity {controller.prev()};located(before)
                    assertEquals(first,controller.state.value.position)
                    var traversed=0;var lastOffset=0
                    while(controller.state.value.viewport?.atEnd!=true && traversed<64) {
                        before=events.get();scenario.onActivity {controller.next()};located(before)
                        val offset=(controller.state.value.position as Locator.Epub).offset
                        assertTrue(offset>lastOffset || controller.state.value.viewport?.atEnd==true);lastOffset=offset;traversed++
                    }
                    assertTrue("Actual authored pages reach End",controller.state.value.viewport?.atEnd==true);assertTrue(traversed<64)
                    val marks=listOf(0,3900,7797).map {offset ->requireNotNull(book.publication.locator(book.publication.target(Locator.Epub(book.revision,0,offset)).anchor))}
                    marks.forEach {locator ->store.addBookmark(id,book.revision,locator,"owned vertical source")}
                    val stored=store.bookmarks(id);assertEquals(3,stored.size)
                    for(locator in marks) {
                        before=events.get();scenario.onActivity {adapter.goTo(locator)};located(before);assertEquals(locator,controller.state.value.position)
                        assertTrue(evaluate("JSON.stringify(readerHost.diagnostics())").getJSONObject("anchorGeometry").getBoolean("visible"))
                    }
                    assertTrue(requireNotNull(controller.state.value.viewport).atEnd);assertTrue(book.fractionOf(requireNotNull(controller.state.value.position))<1.0)
                    assertTrue(controller.state.value.endPrompt)
                    val middle=marks[1]
                    before=events.get();scenario.onActivity {adapter.setRules(snapshot.rules.copy(flow=ReaderFlow.SCROLLED),middle)};located(before)
                    assertEquals(middle,controller.state.value.position)
                    before=events.get();scenario.onActivity {controller.next()};located(before);assertTrue((controller.state.value.position as Locator.Epub).offset>3900)
                    before=events.get();scenario.onActivity {adapter.setRules(snapshot.rules.copy(fontSize=24f),middle)};located(before);assertEquals(middle,controller.state.value.position)
                    assertEquals(snapshot.furthest,owner.snapshot.furthest);assertEquals(stored,store.bookmarks(id))
                    assertArrayEquals(original,requireNotNull(repository.db.content(id)).let(repository::original).readBytes())
                } finally {scenario.onActivity {controller.detach();owner.revokeRenderers();owner.closeSource()}}
            }
        }
    }
    @Test fun unmeasuredNativeViewportDispatchesLatestOnlyAfterLayoutAndKeepsTimeoutAndCloseBarriers() {
        val id=import(epub());val book=runBlocking { LibraryOpenedBookFactory(repository).open(id) }
        val adapters=mutableListOf<WebReaderAdapter>()
        val insets=ViewportInsets(0,259,0,638)
        val initial=Locator.Epub(book.revision,0,0);val latest=Locator.Epub(book.revision,1,0)
        val expectedLatest=requireNotNull(book.publication.locator(book.publication.target(latest).anchor))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun evaluate(adapter: WebReaderAdapter,script: String): String {
                val value=AtomicReference<String?>()
                scenario.onActivity { adapter.view.evaluateJavascript(script) { value.set(it) } }
                waitUntil { value.get()!=null };return requireNotNull(value.get())
            }
            fun host(adapter: WebReaderAdapter)=JSONObject(org.json.JSONTokener(evaluate(adapter,"JSON.stringify(readerHost.diagnostics())")).nextValue() as String)
            fun bootedWithoutLayout(adapter: WebReaderAdapter) {
                waitUntil { evaluate(adapter,"Boolean(window.readerHost)")=="true" }
                scenario.onActivity { assertEquals(0,adapter.view.width);assertEquals(0,adapter.view.height) }
                assertEquals(0,host(adapter).getInt("commandId"))
            }
            try {
                val position=AtomicReference<Locator?>();val error=AtomicReference<String?>()
                lateinit var adapter: WebReaderAdapter
                scenario.onActivity { activity ->
                    activity.setContentView(android.widget.FrameLayout(activity))
                    adapter=WebReaderAdapter(activity) { event -> when(event) {
                        is RendererEvent.Position -> position.set(event.locator)
                        is RendererEvent.Error -> error.set(event.message)
                        else -> Unit
                    } };adapters.add(adapter)
                    adapter.setViewportInsets(insets);adapter.open(book.publication,initial,ReaderRules())
                }
                bootedWithoutLayout(adapter);assertNull(position.get());assertNull(error.get())
                scenario.onActivity { adapter.goTo(latest);it.setContentView(adapter.view) }
                waitUntil { position.get()!=null || error.get()!=null };assertNull(error.get());assertEquals(expectedLatest,position.get())
                val canonical=position.get() as Locator.Epub
                assertEquals(1,canonical.spine);assertEquals(0,canonical.offset)
                val diagnostic=host(adapter);assertTrue(diagnostic.getJSONObject("anchorGeometry").getBoolean("visible"))
                assertEquals(2,diagnostic.getInt("commandId"))
                val margin=JSONObject(org.json.JSONTokener(evaluate(adapter,"""JSON.stringify({width:visualViewport?.width??innerWidth,
                    top:parseFloat(document.getElementById('renderer').getAttribute('margin-top')),
                    bottom:parseFloat(document.getElementById('renderer').getAttribute('margin-bottom'))})""")).nextValue() as String)
                val fontUrl=requireNotNull(adapter.currentUrl).substringBeforeLast('/')+"/fonts/readest.css"
                val geometry=RuleCompiler.compile(book.publication.kind,ReaderRules(),fontUrl,"").geometry
                val scale=margin.getDouble("width")/adapter.view.width
                assertEquals(geometry.marginTop.removeSuffix("px").toDouble()+insets.top*scale,margin.getDouble("top"),.05)
                assertEquals(geometry.marginBottom.removeSuffix("px").toDouble()+insets.bottom*scale,margin.getDouble("bottom"),.05)
                scenario.onActivity { adapter.close();it.setContentView(android.widget.FrameLayout(it)) }

                val closed=java.util.concurrent.atomic.AtomicBoolean(false);val lateEvents=java.util.concurrent.atomic.AtomicInteger()
                lateinit var canceled: WebReaderAdapter
                scenario.onActivity { activity ->
                    canceled=WebReaderAdapter(activity) { event -> if(closed.get() && event !is RendererEvent.Loading)lateEvents.incrementAndGet() }
                    adapters.add(canceled);canceled.open(book.publication,initial,ReaderRules())
                }
                bootedWithoutLayout(canceled)
                scenario.onActivity { closed.set(true);canceled.close() }
                assertEquals(0,lateEvents.get())

                val timeout=AtomicReference<String?>();val timeoutPositions=java.util.concurrent.atomic.AtomicInteger()
                lateinit var unmounted: WebReaderAdapter
                scenario.onActivity { activity ->
                    unmounted=WebReaderAdapter(activity) { event -> when(event) {
                        is RendererEvent.Position -> timeoutPositions.incrementAndGet()
                        is RendererEvent.Error -> timeout.set(event.message)
                        else -> Unit
                    } };adapters.add(unmounted);unmounted.open(book.publication,initial,ReaderRules())
                }
                bootedWithoutLayout(unmounted)
                waitUntil { timeout.get()!=null }
                assertEquals("正文加载或重排超时，请返回书架重试。",timeout.get());assertEquals(0,timeoutPositions.get())
                scenario.onActivity { it.setContentView(unmounted.view) }
                waitUntil { unmounted.view.width>0 && unmounted.view.height>0 }
                assertEquals(0,host(unmounted).getInt("commandId"));assertEquals(0,timeoutPositions.get());assertEquals(0,lateEvents.get())
            } finally { scenario.onActivity { adapters.forEach { it.close() } };book.close() }
        }
    }
    @Test fun protocolTwoRuntimeAuthorCustomReflowElementAndQueuedPageAck() {
        val id=import(epub(context.assets.open("fonts/Bitter-Regular-VF.ttf").use { it.readBytes() }));val book=runBlocking { LibraryOpenedBookFactory(repository).open(id) }
        val position=AtomicReference<RendererEvent.Position?>();val positionCount=java.util.concurrent.atomic.AtomicInteger();val error=AtomicReference<String?>();val notice=AtomicReference<String?>();var badFontBook: OpenedBook?=null;lateinit var adapter: WebReaderAdapter
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun evaluate(script: String): String { val value=AtomicReference<String?>();scenario.onActivity { adapter.view.evaluateJavascript(script) { value.set(it) } };waitUntil { value.get()!=null };return requireNotNull(value.get()) }
            fun diagnostics()=JSONObject(org.json.JSONTokener(evaluate("JSON.stringify(readerHost.diagnostics())")).nextValue() as String)
            fun assertSemanticFont() {
                val probe="(()=>{const d=document.getElementById('renderer').getContents().find(x=>x.index===1).doc;const s=c=>getComputedStyle(d.querySelector(c));return s('.semantic-font').fontStyle==='italic'&&s('.semantic-font').fontWeight==='700'&&s('.reset-font').fontStyle==='normal'&&s('.reset-font').fontWeight==='400'&&s('.angled-font').fontStyle.startsWith('oblique')&&s('.angled-font').fontWeight==='600'&&s('.priority-font').fontStyle==='italic'&&s('.priority-font').fontWeight==='700'})()"
                assertEquals("true",evaluate(probe))
            }
            try {
                scenario.onActivity { activity -> adapter=WebReaderAdapter(activity) { event -> when(event) {
                    is RendererEvent.Position -> {
                        position.set(event);positionCount.incrementAndGet()
                    }
                    is RendererEvent.Tap, is RendererEvent.Selection -> Unit
            is RendererEvent.Interaction -> Unit
                        is RendererEvent.Zoom -> Unit
                        is RendererEvent.Notice -> notice.set(event.message)
                    is RendererEvent.Error -> error.set(event.message)
                    is RendererEvent.Crashed -> error.set(event.message)
                    else -> Unit
                } };activity.setContentView(adapter.view);adapter.open(book.publication,Locator.Epub(book.revision,0,0),ReaderRules(flow=ReaderFlow.SCROLLED)) }
                waitUntil { position.get()!=null || error.get()!=null };assertNull(error.get());assertTrue(diagnostics().getJSONObject("anchorGeometry").getBoolean("visible"));assertTrue((position.get()!!.locator as Locator.Epub).offset>0)
                assertEquals("\"rgb(255, 0, 0)\"",evaluate("getComputedStyle(document.getElementById('renderer').getContents().find(x=>x.index===0).doc.querySelector('p')).color"))
                val target=Locator.Epub(book.revision,1,3)
                position.set(null);scenario.onActivity { adapter.goTo(target) };waitUntil { (position.get()?.locator as? Locator.Epub)?.spine==1 || error.get()!=null };assertNull(error.get());assertEquals(3,(position.get()!!.locator as Locator.Epub).offset);assertSemanticFont()
                val epoch=diagnostics().getInt("layoutEpoch")
                val beforePages=positionCount.get();position.set(null);scenario.onActivity { adapter.next();adapter.next();adapter.prev() };waitUntil { positionCount.get()>=beforePages+3 && diagnostics().getInt("commandId")>=5 || error.get()!=null };assertNull(error.get());assertEquals(epoch,diagnostics().getInt("layoutEpoch"));assertEquals(IntentCause.UserNavigation,position.get()!!.intent)
                val pagedViewport=requireNotNull(position.get()!!.viewport);assertEquals(book.revision,pagedViewport.sourceRevision);assertTrue(pagedViewport.contains(position.get()!!.locator));assertTrue(pagedViewport.samples.isNotEmpty());assertTrue(pagedViewport.visibleRanges.isNotEmpty());assertTrue(pagedViewport.samples.all { it.page in 1..it.pages })
                assertTrue(diagnostics().getJSONObject("anchorGeometry").getBoolean("visible"));assertTrue(diagnostics().getJSONObject("anchorGeometry").getDouble("width")>0.1)
                position.set(null);scenario.onActivity { adapter.setRules(ReaderRules(fontSize=24f,flow=ReaderFlow.PAGINATED),target) };waitUntil { position.get()!=null || error.get()!=null };assertNull(error.get());assertTrue(diagnostics().getJSONObject("anchorGeometry").getBoolean("visible"));assertEquals(IntentCause.Reflow,position.get()!!.intent)
                val beforeCustom=requireNotNull(position.get()!!.viewport);assertTrue(beforeCustom.contains(position.get()!!.locator));assertTrue(beforeCustom.samples.all { !it.estimated })
                val oldUrl=adapter.currentUrl
                position.set(null);scenario.onActivity { adapter.setRules(ReaderRules(fontSize=24f,flow=ReaderFlow.PAGINATED,epubStyleMode="CUSTOM"),target) };waitUntil { position.get()!=null || error.get()!=null };assertNull(error.get());assertNotEquals(oldUrl,adapter.currentUrl);assertSemanticFont()
                assertNotEquals(beforeCustom.layoutSig,requireNotNull(position.get()!!.viewport).layoutSig)
                assertEquals("\"rgb(23, 23, 23)\"",evaluate("getComputedStyle(document.getElementById('renderer').getContents().find(x=>x.index===1).doc.querySelector('p')).color"))
                val image=(book.publication as EpubPublication).record.sections[2].elements.keys.first()
                position.set(null);scenario.onActivity { adapter.goTo(Locator.Epub(book.revision,2,0,elementId=image)) };waitUntil { (position.get()?.locator as? Locator.Epub)?.elementId==image || error.get()!=null };assertNull(error.get());assertTrue(diagnostics().getJSONObject("anchorGeometry").getBoolean("visible"))
                assertTrue(requireNotNull(position.get()!!.viewport).contains(position.get()!!.locator));assertTrue(requireNotNull(position.get()!!.viewport).visibleElements.any { (it as? Locator.Epub)?.elementId==image })
                assertTrue(adapter.cachedResourceBytes<=4*1024*1024)
                val badId=import(epub(byteArrayOf(0,1,0,0,0,0,0,0,0,0,0,0)))
                val bad=runBlocking { LibraryOpenedBookFactory(repository).open(badId) };badFontBook=bad
                position.set(null);notice.set(null)
                scenario.onActivity { adapter.open(bad.publication,Locator.Epub(bad.revision,0,0),ReaderRules()) }
                waitUntil { position.get()!=null && notice.get()?.contains("备用字体")==true || error.get()!=null };assertNull(error.get());assertTrue(diagnostics().getJSONObject("anchorGeometry").getBoolean("visible"))
            } finally { scenario.onActivity { adapter.close() };badFontBook?.close();book.close() }
        }
    }
    @Test fun physicalTwentyMiBImportStreamsCanonicalPartsAndRestoresLogicalPositionAfterReflow() {
        data class Expected(val file: File,val characters: Int,val sha: String,val target: Int)
        fun chapter(name: String,minimum: Int,giant: Boolean): Expected {
            val file=File(root,name);var characters=0;var bytes=0;var selected=-1;var random=17L
            val digest=java.security.MessageDigest.getInstance("SHA-256")
            file.outputStream().buffered().use { output ->
                fun write(value: String) { val encoded=value.toByteArray();output.write(encoded);bytes+=encoded.size }
                fun text(value: String) { write(value);characters+=value.length;digest.update(value.toByteArray(Charsets.UTF_16LE)) }
                write("<html xmlns='http://www.w3.org/1999/xhtml'><head><title>原创长章</title></head><body>")
                if(giant)write("<p id='start'>")
                var paragraph=0
                while(bytes<minimum) {
                    if(!giant)write("<p${if(paragraph%100==0)" id='p$paragraph'" else ""}>")
                    if(selected<0 && bytes>minimum*3/4)selected=characters
                    val value=buildString { repeat(512) { random=(random*1664525+1013904223) and 0xffffffffL;append((0x4e00+(random%20000).toInt()).toChar());if(it%127==126)append("😀") } }
                    text(value)
                    if(!giant)write("</p>")
                    paragraph++
                }
                if(giant)write("</p>")
                write("<p id='tail'>");text("真实章节末尾 END");write("</p></body></html>")
            }
            return Expected(file,characters,digest.digest().joinToString("") { "%02x".format(it) },selected)
        }
        val first=chapter("giant.xhtml",5*1024*1024,true);val second=chapter("many.xhtml",15*1024*1024,false)
        val uri=Uri.parse("content://com.localreader.app.test.fixtures/stream-${UUID.randomUUID()}.epub");uris.add(uri)
        context.contentResolver.openOutputStream(uri,"wt")!!.use { stream -> ZipOutputStream(stream).use { zip ->
            fun entry(name: String,file: File) {
                val crc=CRC32();file.inputStream().use { input -> val buffer=ByteArray(65536);while(true) { val n=input.read(buffer);if(n<0)break;crc.update(buffer,0,n) } }
                val item=ZipEntry(name).apply { time=315532800000L;method=ZipEntry.STORED;size=file.length();compressedSize=size;this.crc=crc.value }
                zip.putNextEntry(item);file.inputStream().use { it.copyTo(zip,65536) };zip.closeEntry()
            }
            fun text(name: String,value: String) { val file=File(root,"entry-${UUID.randomUUID()}");file.writeText(value);entry(name,file);file.delete() }
            text("mimetype","application/epub+zip")
            text("META-INF/container.xml","<container><rootfiles><rootfile full-path='book.opf' media-type='application/oebps-package+xml'/></rootfiles></container>")
            text("book.opf","<package version='3.0' unique-identifier='uid'><metadata xmlns:dc='http://purl.org/dc/elements/1.1/'><dc:identifier id='uid'>own-long-20mib</dc:identifier><dc:title>真实20MiB EPUB</dc:title></metadata><manifest><item id='one' href='giant.xhtml' media-type='application/xhtml+xml'/><item id='two' href='many.xhtml' media-type='application/xhtml+xml'/></manifest><spine><itemref idref='one'/><itemref idref='two'/></spine></package>")
            entry("giant.xhtml",first.file);entry("many.xhtml",second.file)
        } }
        val imported=repository.import(uri);assertNotNull(imported.status,imported.bookId);val id=requireNotNull(imported.bookId)
        val record=requireNotNull(repository.db.content(id));assertTrue(record.bytes>20L*1024*1024)
        val cache=repository.db.epubCache(id);assertEquals(2,cache.sections.size);assertTrue(cache.parts.size>40);assertTrue(cache.parts.all { it.characters<512*1024 })
        val directory=repository.directory(record)
        listOf(first,second).forEachIndexed { spine,expected ->
            assertEquals(expected.characters,cache.sections[spine].characters)
            val hash=java.security.MessageDigest.getInstance("SHA-256");File(directory,cache.sections[spine].canonicalFile).inputStream().use { input -> val buffer=ByteArray(65536);while(true) { val n=input.read(buffer);if(n<0)break;hash.update(buffer,0,n) } }
            assertEquals(expected.sha,hash.digest().joinToString("") { "%02x".format(it) })
        }
        val store=SqliteReadingStore(repository.db);val book=runBlocking { LibraryOpenedBookFactory(repository).open(id) }
        val target=Locator.Epub(book.revision,1,second.target);assertTrue(book.accepts(target));assertTrue(book.publication.target(target).section>1)
        val current=AtomicReference<RendererEvent.Position?>();val error=AtomicReference<String?>();lateinit var adapter: WebReaderAdapter
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun diagnostics(): JSONObject {
                val result=AtomicReference<String?>();scenario.onActivity { adapter.view.evaluateJavascript("JSON.stringify(readerHost.diagnostics())") { result.set(it) } };waitUntil { result.get()!=null }
                return JSONObject(org.json.JSONTokener(result.get()).nextValue() as String)
            }
            try {
                scenario.onActivity { activity -> adapter=WebReaderAdapter(activity) { event -> when(event) { is RendererEvent.Position -> current.set(event);is RendererEvent.Error -> error.set(event.message);is RendererEvent.Crashed -> error.set(event.message);else -> Unit } };activity.setContentView(adapter.view);adapter.open(book.publication,target,ReaderRules(flow=ReaderFlow.SCROLLED)) }
                waitUntil { current.get()?.locator==target || (current.get()?.locator as? Locator.Epub)?.let { it.spine==target.spine && it.offset==target.offset }==true || error.get()!=null };assertNull(error.get())
                var diag=diagnostics();assertTrue(diag.getJSONObject("anchorGeometry").getBoolean("visible"));assertTrue(diag.getJSONArray("contents").length()<=8);val loaded=diag.getJSONArray("contents");assertTrue((0 until loaded.length()).sumOf { loaded.getJSONObject(it).optString("canonicalLength").toLongOrNull() ?: 0L }<first.characters.toLong()+second.characters);assertTrue(adapter.cachedResourceBytes<=4*1024*1024)
                current.set(null);scenario.onActivity { adapter.setRules(ReaderRules(fontSize=26f,flow=ReaderFlow.PAGINATED),target) }
                waitUntil { (current.get()?.locator as? Locator.Epub)?.let { it.spine==target.spine && it.offset==target.offset }==true || error.get()!=null };assertNull(error.get())
                diag=diagnostics();assertTrue(diag.getJSONObject("anchorGeometry").getBoolean("visible"));assertTrue(diag.getJSONArray("contents").length()<=8)
                val saved=current.get()!!.locator;store.savePosition(id,book.revision,saved,EpubReadingExtent(book.revision,1,second.target));assertTrue(store.addBookmark(id,book.revision,saved,book.excerpt(saved,100)))
            } finally { scenario.onActivity { adapter.close() };book.close() }
        }
        runBlocking { LibraryOpenedBookFactory(repository).open(id) }.use { reopened -> val snapshot=store.loadSnapshot(id);val locator=snapshot.current as Locator.Epub;assertEquals(1,locator.spine);assertEquals(second.target,locator.offset);assertEquals(locator,store.bookmarks(id).single().locator);assertTrue(reopened.accepts(locator)) }
    }

    @Test fun factoryAtomicallyRefreshesSchemaTwoDerivedStylesWithoutChangingStoredSourceOrLocators() {
        val imported=repository.import(fixture(epub()));val id=requireNotNull(imported.bookId)
        val content=requireNotNull(repository.db.content(id));val directory=repository.directory(content)
        val metadata=File(directory,"package.json");val json=JSONObject(metadata.readText())
        json.put("renderRevision",content.revision+"-parts2-style3");metadata.writeText(json.toString())
        val original=File(directory.parentFile!!.parentFile,"original.epub")
        val old=EpubCache.read(directory);val originalHash=original.inputStream().use { input ->
            val digest=java.security.MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65536)
            while(true) { val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n) };digest.digest().toList()
        }
        val canonical=old.sections.associate { it.canonicalFile to File(directory,it.canonicalFile).readBytes().toList() }
        val store=SqliteReadingStore(repository.db);val position=Locator.Epub(content.revision,1,3)
        store.savePosition(id,content.revision,position,EpubReadingExtent(content.revision,1,3));assertTrue(store.addBookmark(id,content.revision,position,"source"))
        val before=store.loadSnapshot(id)
        // An interrupted rebuild must leave the complete old cache and reading metadata usable.
        var preparedFailure=false
        val fallback=EpubCache.refresh(original,directory,content.hash) {
            if(directory.listFiles()?.any { it.name.startsWith(".derived-staging-") && File(it,"package.json").isFile }==true) {
                preparedFailure=true;error("injected refresh failure before publish")
            }
        }
        assertTrue(preparedFailure);assertFalse(directory.listFiles()!!.any { it.name.startsWith(".derived-staging-") })
        assertEquals(directory,fallback.first);assertEquals(old.renderRevision,fallback.second.renderRevision)
        assertTrue(fallback.second.warnings.any { it.contains("刷新失败") });assertEquals(directory,EpubCache.activeDirectory(directory))
        runBlocking { LibraryOpenedBookFactory(repository).open(id) }.use { opened ->
            assertEquals(content.revision,opened.revision);assertTrue(opened.accepts(position))
            assertEquals(content.revision+"-"+EpubCache.RENDER_ALGORITHM,opened.publication.revision)
            assertNotEquals(directory,EpubCache.activeDirectory(directory))
        }
        assertEquals(before,store.loadSnapshot(id));assertEquals(position,store.bookmarks(id).single().locator)
        assertEquals(old.renderRevision,JSONObject(metadata.readText()).getString("renderRevision"))
        canonical.forEach { (name,bytes) -> assertEquals(bytes,File(directory,name).readBytes().toList());assertEquals(bytes,File(EpubCache.activeDirectory(directory),name).readBytes().toList()) }
        val afterHash=original.inputStream().use { input ->
            val digest=java.security.MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65536)
            while(true) { val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n) };digest.digest().toList()
        }
        assertEquals(originalHash,afterHash)
    }

    @Test fun controllerKeepsSurfaceAcrossRealRendererTerminationAndSourceGlyphRestore() {
        val id=import(epub());val book=runBlocking { LibraryOpenedBookFactory(repository).open(id) };val store=SqliteReadingStore(repository.db)
        val owner=ReadingSessionOwner(book,1,store,store.loadSnapshot(id));val events=java.util.concurrent.CopyOnWriteArrayList<RendererEvent>();lateinit var surface: ReaderSurface
        val controller=ReaderController(1,{ owner.bind(it) },{ event -> events.add(event);if(event is RendererEvent.Position)owner.position(event.locator,event.intent==IntentCause.UserNavigation,System.currentTimeMillis()) else true })
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { activity -> surface=ReaderSurface(activity);surface.bind(controller);activity.setContentView(surface);surface.present(owner.state()) }
                waitUntil { controller.state.value.phase==ReaderPhase.READY || controller.state.value.phase==ReaderPhase.FAILED };assertEquals(ReaderPhase.READY,controller.state.value.phase)
                lateinit var old: WebView
                scenario.onActivity { old=surface.getChildAt(0) as WebView;assertNotNull(old.webViewRenderProcess);assertTrue(old.webViewRenderProcess!!.terminate()) }
                waitUntil { events.any { it is RendererEvent.Crashed } }
                val stable=surface
                scenario.onActivity { surface.present(owner.state(),1) }
                waitUntil { controller.state.value.phase==ReaderPhase.READY || controller.state.value.phase==ReaderPhase.FAILED };assertEquals(ReaderPhase.READY,controller.state.value.phase)
                scenario.onActivity { assertSame(stable,surface);assertNotSame(old,surface.getChildAt(0));assertNull(old.parent) }
                assertTrue(book.accepts(controller.state.value.position!!));owner.flush()
            } finally { scenario.onActivity { surface.close();owner.revokeRenderers();owner.closeSource() } }
        }
    }
    private fun fixedEpub(rtl: Boolean=false,svgPage: Boolean=false,standaloneSvg: Boolean=false,boundarySide: String?=null): ByteArray {
        require(boundarySide==null || boundarySide in listOf("left","right"))
        val pageCount=if(boundarySide==null)30 else 1
        val output=ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            fun entry(name: String,bytes: ByteArray,stored: Boolean=false) {
                zip.putNextEntry(ZipEntry(name).apply { time=315532800000L;if(stored) { method=ZipEntry.STORED;size=bytes.size.toLong();compressedSize=size;crc=CRC32().apply { update(bytes) }.value } })
                zip.write(bytes);zip.closeEntry()
            }
            fun text(name: String,value: String)=entry(name,value.toByteArray())
            entry("mimetype","application/epub+zip".toByteArray(),true)
            text("META-INF/container.xml","<container xmlns='urn:oasis:names:tc:opendocument:xmlns:container' version='1.0'><rootfiles><rootfile full-path='OPS/book.opf' media-type='application/oebps-package+xml'/></rootfiles></container>")
            val items=(0 until pageCount).joinToString("") { "<item id='p$it' href='$it.xhtml' media-type='${if(standaloneSvg && it==0)"image/svg+xml" else "application/xhtml+xml"}'/>" }
            val spine=(0 until pageCount).joinToString("") { "<itemref idref='p$it'${boundarySide?.let { side -> " properties='page-spread-$side'" } ?: ""}/>" }
            text("OPS/book.opf","<package xmlns='http://www.idpf.org/2007/opf' unique-identifier='uid' version='3.0'><metadata xmlns:dc='http://purl.org/dc/elements/1.1/'><dc:identifier id='uid'>fixed-${if(rtl)"rtl" else "ltr"}</dc:identifier><dc:title>原创固定页面</dc:title><meta property='rendition:layout'>pre-paginated</meta><meta property='rendition:spread'>${if(boundarySide==null)"none" else "auto"}</meta></metadata><manifest>$items<item id='css' href='page.css' media-type='text/css'/><item id='font' href='font.ttf' media-type='font/ttf'/><item id='image' href='image.svg' media-type='image/svg+xml'/><item id='cover' href='image.png' media-type='image/png'/></manifest><spine page-progression-direction='${if(rtl)"rtl" else "ltr"}'>$spine</spine></package>")
            text("OPS/page.css","@font-face{font-family:LocalBook;src:url('font.ttf')}html,body{margin:0;width:100%;height:100%}body{background:#dceaf5;font-family:LocalBook}p{position:absolute;left:40px;top:60px;font-size:31px;color:#143a53}img{position:absolute;left:20px;top:150px;width:300px;height:300px}")
            entry("OPS/font.ttf",context.assets.open("fonts/Bitter-Regular-VF.ttf").use { it.readBytes() })
            text("OPS/image.svg","<svg xmlns='http://www.w3.org/2000/svg' width='300' height='300' viewBox='0,0,300,300'><rect width='300' height='300' fill='#8b6'/></svg>")
            val bitmap=android.graphics.Bitmap.createBitmap(48,72,android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(70,110,170)) }
            val png=ByteArrayOutputStream();try { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,png)) } finally { bitmap.recycle() }
            entry("OPS/image.png",png.toByteArray())
            repeat(pageCount) { i -> text("OPS/$i.xhtml",if(standaloneSvg && i==0) "<s:svg xmlns:s='http://www.w3.org/2000/svg' xmlns:l='http://www.w3.org/1999/xlink' viewBox='0,0,700,1100'><s:rect width='700' height='1100' fill='#88bb66'/><s:image l:href='image.png' width='700' height='1100'/><s:text x='30' y='60'>原创整页 SVG</s:text></s:svg>" else if(svgPage && i==0) "<html xmlns='http://www.w3.org/1999/xhtml'><head><link rel='stylesheet' href='page.css'/></head><body><svg xmlns='http://www.w3.org/2000/svg' viewBox='0,0,700,1100'><image href='image.png' width='700' height='1100'/><text x='30' y='60'>原创整页 SVG</text></svg></body></html>" else "<html xmlns='http://www.w3.org/1999/xhtml'><head><meta name='viewport' content='width=${600+i*2},height=${900+i*4}'/><link rel='stylesheet' href='page.css'/></head><body><p id='label'>原创第${i+1}页 English 123</p><img src='image.png'/><img src='image.svg' style='left:340px'/><a href='${(i+1).coerceAtMost(29)}.xhtml' style='position:absolute;top:500px'>下一页链接</a></body></html>") }
        }
        return output.toByteArray().also { bytes -> assertEquals(0,(bytes[28].toInt() and 255) or ((bytes[29].toInt() and 255) shl 8)) }
    }

    @Test fun fixedPagesSafMetadataStoreAndDerivedLegacyCacheHaveRealConsumers() {
        val id=import(fixedEpub());val store=SqliteReadingStore(repository.db)
        val content=requireNotNull(repository.db.content(id));val directory=repository.directory(content)
        val cache=repository.db.epubCache(id);assertEquals(30,cache.fixedPages.size);assertEquals(30,cache.parts.size);assertEquals("pre-paginated",cache.layout);assertEquals("pre-paginated",repository.db.contents().single().epubLayout);assertEquals("assets/cover-preview",cache.coverFile);assertNotNull(content.coverPath)
        assertEquals(658.0,cache.fixedPages.last().width,0.0);assertEquals(1016.0,cache.fixedPages.last().height,0.0)
        val target=Locator.Page(content.revision,18,.25,.66)
        runBlocking { LibraryOpenedBookFactory(repository).open(id) }.use { book ->
            assertTrue(book is FixedOpenedBook);assertTrue(book.accepts(target));assertEquals(target,book.publication.locator(book.publication.target(target).anchor))
            store.patchRules(id,book.revision,RulesPatch(fxlZoom=FieldUpdate.Set("2"),spreadPreference=FieldUpdate.Set("SINGLE")))
            store.savePosition(id,book.revision,target,FixedReadingExtent(book.revision,22,.1,.4))
            assertTrue(store.addBookmark(id,book.revision,target,"第19页"));assertFalse(store.addBookmark(id,book.revision,target.copy(x=.9,y=.1),"同一页"))
        }
        assertEquals(target,store.loadSnapshot(id).current);assertEquals("2",store.loadSnapshot(id).rules.fxlZoom);assertEquals("SINGLE",store.loadSnapshot(id).rules.spreadPreference)
        val legacy=Locator.Epub(content.revision,18,50)
        repository.db.transaction { db ->
            db.execSQL("UPDATE positions SET locator_json=?,furthest_json=? WHERE content_id=?",arrayOf(LocatorCodec.encode(legacy),ReadingExtentCodec.encode(EpubReadingExtent(content.revision,22,0)),id))
            repository.db.addBookmark(id,legacy,"旧格式位置")
        }
        val json=JSONObject(File(directory,"package.json").readText());json.remove("fixedPages");json.put("renderRevision",content.revision);json.put("schema",1)
        File(directory,"package.json").writeText(json.toString());repository.db.close()
        val untouched=File(directory,"package.json").readBytes().toList()
        runBlocking { LibraryOpenedBookFactory(repository).open(id) }.use { book ->
            assertTrue(book is FixedOpenedBook);assertEquals(content.revision,book.revision);assertTrue(book.publication.target(legacy).nearby)
            assertEquals(18,book.publication.target(legacy).section)
            assertTrue(EpubCache.activeDirectory(directory).name=="derived-fxl1")
            val snapshot=store.loadSnapshot(id);assertEquals(legacy,snapshot.current);assertEquals(FixedReadingExtent(content.revision,22,0.0,0.0),snapshot.furthest)
            assertEquals(1,store.bookmarks(id).count { it.locator==legacy })
            store.savePosition(id,book.revision,target,snapshot.furthest)
        }
        assertEquals(untouched,File(directory,"package.json").readBytes().toList());assertEquals(target,store.loadSnapshot(id).current)
        assertEquals(1,store.bookmarks(id).count { it.locator==legacy })
    }

    @Test fun fixedRendererThirtyPagesTwoAxesRtlZoomFlowAndRestartStayBounded() {
        val id=import(fixedEpub(true));val store=SqliteReadingStore(repository.db)
        val book=runBlocking { LibraryOpenedBookFactory(repository).open(id) }
        val current=AtomicReference<RendererEvent.Position?>();val error=AtomicReference<String?>();lateinit var adapter: WebReaderAdapter
        val listener: (RendererEvent)->Unit={ event -> when(event) { is RendererEvent.Position -> current.set(event);is RendererEvent.Error -> error.set(event.message);is RendererEvent.Crashed -> error.set(event.message);else -> Unit } }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun diagnostics(): JSONObject {
                val result=AtomicReference<String?>();scenario.onActivity { adapter.view.evaluateJavascript("JSON.stringify((()=>{const d=readerHost.diagnostics(),r=document.querySelector('foliate-fxl'),v=r.getBoundingClientRect();return {...d,viewport:{width:innerWidth,height:innerHeight,left:v.left,top:v.top,right:v.right,bottom:v.bottom},scroll:{x:r.scrollLeft,y:r.scrollTop,width:r.scrollWidth,height:r.scrollHeight,clientWidth:r.clientWidth,clientHeight:r.clientHeight},frames:r.getContents().map(c=>{const f=c.doc.defaultView.frameElement,b=f.getBoundingClientRect();return {page:c.index,width:f.clientWidth,height:f.clientHeight,left:b.left,top:b.top,right:b.right,bottom:b.bottom}})}})())") { result.set(it) } };waitUntil { result.get()!=null }
                return JSONObject(org.json.JSONTokener(result.get()).nextValue() as String)
            }
            fun located(page: Int,expectedRevision: String=book.revision) { waitUntil { (current.get()?.locator as? Locator.Page)?.page==page || error.get()!=null };val d=diagnostics();assertNull(d.toString(),error.get());assertTrue(d.toString(),d.getJSONObject("anchorGeometry").getBoolean("visible"));assertTrue(d.toString(),d.getInt("allocatedFrames")<=4);assertTrue(d.getJSONArray("contents").length()<=4)
                val viewport=requireNotNull(current.get()!!.viewport);assertEquals(expectedRevision,viewport.sourceRevision);assertTrue(viewport.contains(current.get()!!.locator));assertTrue(viewport.visiblePages.size in 1..4);assertTrue(viewport.samples.all { !it.estimated && it.pages==1 });assertEquals(d.getBoolean("atEnd"),viewport.atEnd)
            }
            try {
                val rules=ReaderRules(flow=ReaderFlow.PAGINATED,spreadPreference="AUTO")
                scenario.onActivity { activity -> adapter=WebReaderAdapter(activity,onEvent=listener);activity.setContentView(adapter.view);adapter.open(book.publication,Locator.Page(book.revision,17,0.0,0.0),rules) }
                located(17)
                for(page in 0 until 30) { current.set(null);scenario.onActivity { adapter.goTo(Locator.Page(book.revision,page,0.0,0.0)) };located(page) }
                assertEquals(29,(current.get()!!.locator as Locator.Page).page);assertTrue(requireNotNull(current.get()!!.viewport).atEnd)
                current.set(null);scenario.onActivity { adapter.setRules(rules.copy(fxlZoom="2"),Locator.Page(book.revision,29,.25,.5)) };located(29)
                assertTrue(requireNotNull(current.get()!!.viewport).atEnd);assertTrue(book.fractionOf(current.get()!!.locator)<1.0)
                current.set(null);scenario.onActivity { adapter.setRules(rules,Locator.Page(book.revision,28,0.0,0.0)) };located(28);assertFalse(requireNotNull(current.get()!!.viewport).atEnd)
                val target=Locator.Page(book.revision,12,.25,.5)
                current.set(null);scenario.onActivity { adapter.setRules(rules.copy(flow=ReaderFlow.SCROLLED,fxlZoom="2"),target) };located(12)
                var position=current.get()!!.locator as Locator.Page;assertEquals(.25,position.x,.02);assertEquals(.5,position.y,.02)
                val diag=diagnostics();assertEquals(1248.0,diag.getJSONObject("anchorGeometry").getDouble("width"),2.0)
                assertTrue(diag.getJSONArray("contents").let { docs -> (0 until docs.length()).any { i -> val faces=docs.getJSONObject(i).getJSONArray("fontFaces");(0 until faces.length()).any { faces.getJSONObject(it).getString("status")=="loaded" } } })
                current.set(null);scenario.onActivity { adapter.setRules(rules.copy(fxlZoom="2"),target) };located(12)
                position=current.get()!!.locator as Locator.Page;assertEquals(.25,position.x,.02);assertEquals(.5,position.y,.02)
                store.patchRules(id,book.revision,RulesPatch(fxlZoom=FieldUpdate.Set("2")))
                store.savePosition(id,book.revision,position,FixedReadingExtent(book.revision,29,0.0,.9));assertTrue(store.addBookmark(id,book.revision,position,"第13页"))
                assertTrue(adapter.cachedResourceBytes<=8*1024*1024)
                // An XHTML whole-page SVG wrapper gets its actual viewBox, rather
                // than an arbitrary default or the dimensions of its raster child.
                for(standalone in listOf(false,true)) {
                val svgId=import(fixedEpub(svgPage=true,standaloneSvg=standalone))
                runBlocking { LibraryOpenedBookFactory(repository).open(svgId) }.use { svgBook ->
                    val specification=requireNotNull(svgBook.publication.fixedLayout).pages.first()
                    assertEquals(700.0,specification.width,0.0);assertEquals(1100.0,specification.height,0.0)
                    current.set(null);scenario.onActivity { activity -> adapter.close();adapter=WebReaderAdapter(activity,onEvent=listener);activity.setContentView(adapter.view);adapter.open(svgBook.publication,Locator.Page(svgBook.revision,0,0.0,0.0),rules) };located(0,svgBook.revision)
                    val result=AtomicReference<String?>()
                    scenario.onActivity { adapter.view.evaluateJavascript("JSON.stringify((()=>{const d=document.querySelector('foliate-fxl').getContents()[0].doc,s=d.querySelector('svg'),r=s.getBoundingClientRect();return {kind:s.constructor.name,x:r.x,y:r.y,width:r.width,height:r.height,image:d.querySelector('svg image').getBBox().width,rect:d.querySelector('svg rect')?{kind:d.querySelector('svg rect').constructor.name,width:d.querySelector('svg rect').getBBox().width,height:d.querySelector('svg rect').getBBox().height,fill:d.defaultView.getComputedStyle(d.querySelector('svg rect')).fill}:null,text:s.textContent}})())") { result.set(it) } }
                    waitUntil { result.get()!=null };val svg=JSONObject(org.json.JSONTokener(result.get()).nextValue() as String)
                    assertEquals("SVGSVGElement",svg.getString("kind"));assertEquals(0.0,svg.getDouble("x"),.01);assertEquals(0.0,svg.getDouble("y"),.01);assertEquals(700.0,svg.getDouble("width"),1.0);assertEquals(1100.0,svg.getDouble("height"),1.0);assertEquals(700.0,svg.getDouble("image"),1.0)
                    assertEquals("原创整页 SVG",svg.getString("text"))
                    if(standalone) { val rect=svg.getJSONObject("rect");assertEquals("SVGRectElement",rect.getString("kind"));assertEquals(700.0,rect.getDouble("width"),.01);assertEquals(1100.0,rect.getDouble("height"),.01);assertEquals("rgb(136, 187, 102)",rect.getString("fill")) }
                    scenario.onActivity { adapter.close() }
                }
                }
                for(side in listOf("left","right")) {
                    val boundaryId=import(fixedEpub(boundarySide=side))
                    runBlocking { LibraryOpenedBookFactory(repository).open(boundaryId) }.use { single ->
                        assertEquals(1,requireNotNull(single.publication.fixedLayout).pages.size)
                        current.set(null)
                        scenario.onActivity { activity -> adapter.close();adapter=WebReaderAdapter(activity,onEvent=listener);activity.setContentView(adapter.view);adapter.open(single.publication,Locator.Page(single.revision,0,0.0,0.0),rules.copy(spreadPreference="AUTO")) }
                        located(0,single.revision)
                        val boundary=diagnostics()
                        assertEquals(boundary.toString(),2,boundary.getInt("allocatedFrames"))
                        assertEquals(boundary.toString(),1,boundary.getJSONArray("contents").length())
                        assertTrue(boundary.getJSONArray("contents").getJSONObject(0).getBoolean("validated"))
                        scenario.onActivity { adapter.close() }
                    }
                }
            } finally { scenario.onActivity { adapter.close() };book.close() }
        }
        runBlocking { LibraryOpenedBookFactory(repository).open(id) }.use { reopened -> val snapshot=store.loadSnapshot(id);assertTrue(reopened.accepts(snapshot.current));assertEquals(snapshot.current,store.bookmarks(id).single().locator);assertEquals(29,(snapshot.furthest as FixedReadingExtent).page);assertEquals("2",snapshot.rules.fxlZoom) }
    }

    @Test fun fixedControllerCrashReplacesOnlyBackendAndRestoresRealPagePoint() {
        val id=import(fixedEpub());val book=runBlocking { LibraryOpenedBookFactory(repository).open(id) };val store=SqliteReadingStore(repository.db)
        val target=Locator.Page(book.revision,12,.25,.5)
        val owner=ReadingSessionOwner(book,1,store,store.loadSnapshot(id).copy(current=target,rules=ReaderRules(fxlZoom="2",spreadPreference="SINGLE")))
        val events=java.util.concurrent.CopyOnWriteArrayList<RendererEvent>();lateinit var surface: ReaderSurface
        val controller=ReaderController(1,{ owner.bind(it) },{ event -> events.add(event);if(event is RendererEvent.Position)owner.position(event.locator,event.intent==IntentCause.UserNavigation,System.currentTimeMillis()) else true })
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun failureEvidence(): String {
                val result=AtomicReference<String?>()
                scenario.onActivity { (surface.getChildAt(0) as? WebView)?.evaluateJavascript("JSON.stringify(window.readerHost?readerHost.diagnostics():{ready:document.readyState,url:location.href})") { result.set(it) } ?: result.set("no WebView") }
                waitUntil { result.get()!=null }
                return "${controller.state.value}; events=$events; renderer=${result.get()}"
            }
            try {
                scenario.onActivity { activity -> surface=ReaderSurface(activity);surface.bind(controller);activity.setContentView(surface);surface.present(owner.state()) }
                waitUntil { controller.state.value.phase==ReaderPhase.READY || controller.state.value.phase==ReaderPhase.FAILED };assertEquals(if(controller.state.value.phase==ReaderPhase.READY)"Ready" else failureEvidence(),ReaderPhase.READY,controller.state.value.phase)
                lateinit var old: WebView
                scenario.onActivity { old=surface.getChildAt(0) as WebView;assertNotNull(old.webViewRenderProcess);assertTrue(old.webViewRenderProcess!!.terminate()) }
                waitUntil { events.any { it is RendererEvent.Crashed } };val stableSurface=surface;scenario.onActivity { surface.present(owner.state(),1) }
                waitUntil { controller.state.value.phase==ReaderPhase.READY || controller.state.value.phase==ReaderPhase.FAILED };assertEquals(if(controller.state.value.phase==ReaderPhase.READY)"Ready" else failureEvidence(),ReaderPhase.READY,controller.state.value.phase)
                scenario.onActivity { assertSame(stableSurface,surface);assertNotSame(old,surface.getChildAt(0));assertNull(old.parent) }
                val position=controller.state.value.position as Locator.Page;assertEquals(12,position.page);assertEquals(.25,position.x,.02);assertEquals(.5,position.y,.02)
                owner.flush();assertEquals(position,store.loadSnapshot(id).current)
            } finally { scenario.onActivity { surface.close();owner.revokeRenderers();owner.closeSource() } }
        }
    }
}
