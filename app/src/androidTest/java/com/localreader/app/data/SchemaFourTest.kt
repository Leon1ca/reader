package com.localreader.app.data

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import com.localreader.app.core.*
import com.localreader.app.formats.txt.TxtReadingExtent
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real SQLite files, WAL and transactions; these are not in-memory schema mocks. */
class SchemaFourTest {
    private lateinit var context: Context
    private lateinit var root: File
    private val helpers = mutableListOf<LibraryDb>()
    @Before fun setup() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(target.cacheDir,"schema-four-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(target) {
            override fun getFilesDir() = File(root,"files").apply { mkdirs() }
            override fun getDatabasePath(name: String) = File(root,name)
            override fun openOrCreateDatabase(name: String,mode: Int,factory: SQLiteDatabase.CursorFactory?) = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory)
            override fun openOrCreateDatabase(name: String,mode: Int,factory: SQLiteDatabase.CursorFactory?,handler: DatabaseErrorHandler?) = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path,factory,handler)
        }
    }
    @After fun cleanup() { helpers.forEach { it.close() };root.deleteRecursively() }
    private fun helper(checkpoint: (String) -> Unit = {}) = LibraryDb(context,checkpoint).also { helpers += it }
    private fun fixture(version: Int = 3): File {
        val source = context.getDatabasePath("library.db")
        SQLiteDatabase.openOrCreateDatabase(source,null).use { db ->
            db.execSQL("CREATE TABLE books(id TEXT PRIMARY KEY,title TEXT NOT NULL,author TEXT NOT NULL,hash TEXT UNIQUE NOT NULL,bytes INTEGER NOT NULL,imported INTEGER NOT NULL,last_read INTEGER NOT NULL,encoding TEXT NOT NULL,version TEXT NOT NULL,paragraphs INTEGER NOT NULL,characters INTEGER NOT NULL,current_offset INTEGER NOT NULL,current_paragraph INTEGER NOT NULL,current_character INTEGER NOT NULL," + (if(version>=2) "current_fraction REAL NOT NULL," else "") + "furthest INTEGER NOT NULL,font_size REAL NOT NULL,theme TEXT NOT NULL" + (if(version==3) ",flow TEXT NOT NULL" else "") + ")")
            db.execSQL("CREATE TABLE bookmarks(id INTEGER PRIMARY KEY AUTOINCREMENT,book_id TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE,version TEXT NOT NULL,source_offset INTEGER NOT NULL,paragraph INTEGER NOT NULL,character INTEGER NOT NULL," + (if(version>=2) "fallback_fraction REAL NOT NULL," else "") + "excerpt TEXT NOT NULL,created INTEGER NOT NULL,UNIQUE(book_id,version,source_offset))")
            db.execSQL("CREATE TABLE locator_backups(id INTEGER PRIMARY KEY AUTOINCREMENT,book_id TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE,version TEXT NOT NULL,payload TEXT NOT NULL,created INTEGER NOT NULL)")
            listOf("a","b").forEachIndexed { index,id ->
                val bindings=mutableListOf<Any>(id,"旧书$id","hash$id","rev$id")
                if(version==3) bindings.add(if(index==0) "paginated" else "scrolled")
                db.execSQL("INSERT INTO books VALUES(?,?,'作者',?,100,10,11,'UTF-8',?,3,100,40,1,2," + (if(version>=2) "0.4," else "") + "60,17.7,'dark'" + (if(version==3) ",?" else "") + ")",bindings.toTypedArray())
                db.execSQL("INSERT INTO bookmarks(book_id,version,source_offset,paragraph,character," + (if(version>=2) "fallback_fraction," else "") + "excerpt,created) VALUES(?,'history',50,1,3," + (if(version>=2) "0.5," else "") + "'历史书签',12)",arrayOf(id))
                repeat(3) { n -> db.execSQL("INSERT INTO locator_backups(book_id,version,payload,created) VALUES(?,?,?,?)",arrayOf<Any>(id,"backup$n","original payload 😀 $n\n",n)) }
            }
            db.execSQL("UPDATE sqlite_sequence SET seq=500 WHERE name='bookmarks'")
            db.execSQL("UPDATE sqlite_sequence SET seq=600 WHERE name='locator_backups'")
            db.version = version
        }
        return source
    }
    @Test fun everyLegacyVersionKeepsMixedSettingsAnchorsBackupsAndHighWaterIds() {
        for(version in 1..3) {
            val source = fixture(version)
            val db = helper();val store = SqliteReadingStore(db)
            assertEquals(6,db.writableDatabase.version)
            db.writableDatabase.rawQuery("SELECT font_size FROM reading_settings WHERE content_id='a'",null).use { assertTrue(it.moveToFirst());assertEquals(17.7,it.getDouble(0),0.0) }
            for(id in listOf("a","b")) {
                val snapshot = store.loadSnapshot(id)
                assertEquals(Locator.Text("rev$id",40,1,2,0.4),snapshot.current)
                assertEquals(TxtReadingExtent("rev$id",60),snapshot.furthest)
                assertEquals(1.6,snapshot.rules.lineHeight,0.0)
                // The legacy per-book style stays in its row (asserted above) but no longer applies: rules are app-wide.
                assertEquals(16f,snapshot.rules.fontSize);assertEquals("white",snapshot.rules.theme)
                assertEquals(ReaderFlow.PAGINATED,snapshot.rules.flow)
                assertEquals(Locator.Text("history",50,1,3,0.5),snapshot.bookmarks.single().locator)
                val content=requireNotNull(db.content(id))
                assertEquals("旧书$id",content.title);assertEquals("作者",content.author);assertEquals("hash$id",content.hash)
                assertEquals(100L,content.bytes);assertEquals(10L,content.imported);assertEquals(11L,content.lastRead)
                assertEquals("rev$id",content.revision);assertNull(content.originalName)
                assertEquals(TxtMetadata("UTF-8",3,100),db.txtMetadata(id))
                val bookmark=snapshot.bookmarks.single()
                assertEquals(if(id=="a") 1L else 2L,bookmark.id);assertEquals(12L,bookmark.created);assertEquals("历史书签",bookmark.excerpt)
                db.readableDatabase.rawQuery("SELECT id,version,payload,created,payload_format FROM locator_backups WHERE content_id=? ORDER BY id",arrayOf(id)).use { rows ->
                    repeat(3) { n -> assertTrue(rows.moveToNext());assertEquals((if(id=="a") 1L else 4L)+n,rows.getLong(0));assertEquals("backup$n",rows.getString(1))
                        assertEquals("original payload 😀 $n\n",rows.getString(2));assertEquals(n.toLong(),rows.getLong(3));assertEquals("legacy-v3-text",rows.getString(4)) }
                    assertFalse(rows.moveToNext())
                }
                assertEquals(setOf("backup0","backup1","backup2"),db.retainedVersions(id))
            }
            assertTrue(store.addBookmark("a","reva",Locator.Text("reva",70,2),"new"))
            assertTrue(store.bookmarks("a").maxOf { it.id } > 500)
            db.reindex("a","UTF-8","next",3,100)
            db.writableDatabase.rawQuery("SELECT MAX(id) FROM locator_backups",null).use { assertTrue(it.moveToFirst());assertTrue(it.getLong(0)>600) }
            val backups=root.listFiles()!!.filter { it.name.endsWith(".bak") }
            val backup=backups.last { SQLiteDatabase.openDatabase(it.path,null,SQLiteDatabase.OPEN_READONLY).use { saved -> saved.version==version } }
            SQLiteDatabase.openDatabase(backup.path,null,SQLiteDatabase.OPEN_READONLY).use { saved -> saved.rawQuery("SELECT COUNT(*) FROM books",null).use { assertTrue(it.moveToFirst());assertEquals(2,it.getInt(0)) } }
            db.close();SQLiteDatabase.deleteDatabase(source)
        }
    }
    @Test fun newTypographyDefaultsAndExistingPreferencesSurviveReopenAndVersionFourUpgrade() {
        val db=helper();val store=SqliteReadingStore(db)
        assertEquals(1.4,store.effectiveRules().rules.lineHeight,0.0)
        for(id in listOf("a","b"))db.insert(TxtRecord(id,"自有$id","作者","hash$id",3,99,0,"UTF-8","rev$id",1,3,Locator.Text("rev$id",0,0),0,16f,"white"),"original.txt")
        store.patchRules(RulesPatch(lineHeight=FieldUpdate.Set(1.6)))
        // Typography is shared: a write made while reading book "a" is what book "b" shows too.
        store.patchRules("a","reva",RulesPatch(lineHeight=FieldUpdate.Set(1.9)))
        db.close()
        val reopened=SqliteReadingStore(helper())
        assertEquals(1.9,reopened.effectiveRules().rules.lineHeight,0.0)
        assertEquals(1.9,reopened.loadSnapshot("a").rules.lineHeight,0.0)
        assertEquals(1.9,reopened.loadSnapshot("b").rules.lineHeight,0.0)
        helpers.forEach {it.close()};SQLiteDatabase.deleteDatabase(context.getDatabasePath("library.db"))
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("library.db"),null).use { old ->
            LibraryDb.SCHEMA.map {it.replace(",hidden_from_continue INTEGER NOT NULL DEFAULT 0 CHECK(hidden_from_continue IN (0,1))","").replace(",canonical_characters INTEGER","")}.forEach(old::execSQL)
            old.execSQL("INSERT INTO reader_defaults(scope,theme,flow,prefs_revision) VALUES('app','white','paginated',0)")
            for(scope in listOf("txt","epub"))old.execSQL("INSERT INTO reader_defaults(scope,font_size,line_height,margin,font_family,columns,epub_style_mode,prefs_revision) VALUES(?,16,1.6,'M','default','auto','AUTHOR',0)",arrayOf(scope))
            old.execSQL("INSERT INTO contents(id,format,revision,title,author,hash,bytes,imported_at) VALUES('owned','txt','rev','自有','作者','hash',3,1)")
            old.execSQL("INSERT INTO reading_settings(content_id,line_height) VALUES('owned',1.9)")
            old.version=4
        }
        val migrated=helper();val oldStore=SqliteReadingStore(migrated)
        assertEquals(6,migrated.writableDatabase.version)
        // Installs from before shared settings keep their format default; a stray per-book override no longer wins.
        assertEquals(1.6,oldStore.effectiveRules().rules.lineHeight,0.0)
        assertEquals(1.6,oldStore.effectiveRules("owned").rules.lineHeight,0.0)
    }
    @Test fun committedWalBackupIsStandaloneOnEverySupportedAndroidVersion() {
        val source=fixture()
        SQLiteDatabase.openDatabase(source.path,null,SQLiteDatabase.OPEN_READWRITE).use { writer ->
            assertTrue(writer.enableWriteAheadLogging())
            writer.rawQuery("PRAGMA wal_autocheckpoint=0",null).use { assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0)) }
            writer.execSQL("UPDATE books SET title='WAL committed' WHERE id='a'")
            assertTrue(File(source.path+"-wal").length()>0)
            LibraryBootstrap.prepare(source)
            val backup=root.listFiles()!!.single { it.name.endsWith(".bak") }
            assertFalse(File(backup.path+"-wal").exists())
            SQLiteDatabase.openDatabase(backup.path,null,SQLiteDatabase.OPEN_READONLY).use { saved -> saved.rawQuery("SELECT title FROM books WHERE id='a'",null).use { assertTrue(it.moveToFirst());assertEquals("WAL committed",it.getString(0)) } }
        }
        assertEquals(6,helper().writableDatabase.version)
    }
    @Test @SdkSuppress(minSdkVersion = 35) fun committedWalIsPresentInIndependentBackupAndBusyReaderPreventsUpgrade() {
        val source=fixture()
        SQLiteDatabase.openDatabase(source.path,null,SQLiteDatabase.OPEN_READWRITE).use { writer ->
            assertTrue(writer.enableWriteAheadLogging())
            writer.rawQuery("PRAGMA wal_autocheckpoint=0",null).use { assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0)) }
            SQLiteDatabase.openDatabase(source.path,null,SQLiteDatabase.OPEN_READWRITE).use { reader ->
                reader.beginTransactionReadOnly()
                try {
                    reader.rawQuery("SELECT title FROM books",null).use { assertTrue(it.moveToFirst()) }
                    writer.execSQL("UPDATE books SET title='只在日志中的新标题' WHERE id='a'")
                    try { LibraryBootstrap.prepare(source);fail("busy checkpoint must fail") } catch(_: IllegalStateException) { }
                    assertEquals(3,writer.version);assertTrue(root.listFiles()!!.none { it.name.endsWith(".bak") })
                } finally { reader.endTransaction() }
            }
            LibraryBootstrap.prepare(source)
            val backup=root.listFiles()!!.single { it.name.endsWith(".bak") }
            SQLiteDatabase.openDatabase(backup.path,null,SQLiteDatabase.OPEN_READONLY).use { saved -> saved.rawQuery("SELECT title FROM books WHERE id='a'",null).use { assertTrue(it.moveToFirst());assertEquals("只在日志中的新标题",it.getString(0)) } }
        }
        assertEquals(6,helper().writableDatabase.version)
    }
    @Test fun failedBackupAndMigrationLeaveOriginalRowsAndValidBackupIntact() {
        val source=fixture()
        try { LibraryBootstrap.prepare(source,{ if(it=="synced") error("injected sync/rename boundary") });fail("must stop") } catch(_: IllegalStateException) { }
        assertTrue(root.listFiles()!!.none { it.name.endsWith(".part") || it.name.endsWith(".bak") })
        for(stage in listOf("row","before-swap","before-commit")) {
            val failing=helper { if(it==stage) error("injected migration boundary") }
            try { failing.writableDatabase;fail(stage) } catch(_: IllegalStateException) { }
            failing.close()
            SQLiteDatabase.openDatabase(source.path,null,SQLiteDatabase.OPEN_READONLY).use { original ->
                assertEquals(3,original.version);assertTrue(LibraryBootstrap.tableExists(original,"books"));assertFalse(LibraryBootstrap.tableExists(original,"contents"))
                original.rawQuery("SELECT COUNT(*) FROM books",null).use { assertTrue(it.moveToFirst());assertEquals(2,it.getInt(0)) }
            }
        }
        val kept=root.listFiles()!!.filter { it.name.endsWith(".bak") }.map { it.name to it.readBytes().toList() }.toMap()
        assertEquals(6,helper().writableDatabase.version)
        kept.forEach { (name,bytes) -> assertEquals(bytes,File(root,name).readBytes().toList()) }
    }
    @Test fun corruptMissingAndUnknownVersionDoNotCreateEmptyLibraryOrRunRecovery() {
        val source=context.getDatabasePath("library.db")
        val original=ByteArray(4096) { 0x41 };source.writeBytes(original)
        val kept=File(context.filesDir,"library/original-book").apply { mkdirs();File(this,"original.txt").writeText("keep") }
        try { LibraryRepository(context).recover();fail("corrupt library") } catch(_: Exception) { }
        assertTrue(source.exists());assertArrayEquals(original,source.readBytes());assertTrue(kept.exists())
        source.delete()
        try { LibraryRepository(context).recover();fail("missing database with existing library") } catch(_: Exception) { }
        assertFalse(source.exists());assertTrue(kept.exists())
        kept.deleteRecursively();fixture()
        SQLiteDatabase.openDatabase(source.path,null,SQLiteDatabase.OPEN_READWRITE).use { it.version=99 }
        try { helper().writableDatabase;fail("unknown version") } catch(_: IllegalArgumentException) { }
        SQLiteDatabase.openDatabase(source.path,null,SQLiteDatabase.OPEN_READONLY).use { assertEquals(99,it.version) }
    }
    @Test fun patchesAreAtomicShareAcrossBooksAndPreserveEofRawExtent() {
        fixture();val db=helper();val store=SqliteReadingStore(db)
        store.patchRules("a","reva",RulesPatch(fontSize=FieldUpdate.Set(28f)))
        store.patchRules(RulesPatch(theme=FieldUpdate.Set("paper")))
        assertEquals(28f,store.effectiveRules("b").rules.fontSize);assertEquals("paper",store.effectiveRules("b").rules.theme)
        assertEquals(ReaderFlow.PAGINATED,store.effectiveRules("b").rules.flow)
        db.writableDatabase.rawQuery("SELECT theme,font_size FROM reading_settings WHERE content_id='a'",null).use { assertTrue(it.moveToFirst());assertEquals("dark",it.getString(0));assertEquals(17.7,it.getDouble(1),0.0) } // legacy columns are never written again
        store.patchRules(RulesPatch(flow=FieldUpdate.Set(ReaderFlow.PAGINATED),theme=FieldUpdate.Set("white")))
        assertEquals(ReaderFlow.PAGINATED,store.effectiveRules("b").rules.flow)
        store.savePosition("a","reva",Locator.Text("reva",100,2,8,1.0),TxtReadingExtent("reva",100))
        assertEquals(1.0,db.contents().single { it.id=="a" }.fraction,0.0)
        assertEquals(1.0,db.contents().single { it.id=="a" }.furthestFraction,0.0)
        val before=store.loadSnapshot("a")
        // Fixed-layout view state is the only per-book rule.
        store.patchRules("a","reva",RulesPatch(fxlZoom=FieldUpdate.Set("fit-width")))
        assertEquals("fit-width",store.effectiveRules("a").rules.fxlZoom);assertEquals("fit-page",store.effectiveRules("b").rules.fxlZoom)
        val after=store.loadSnapshot("a")
        assertEquals(before.current,after.current);assertEquals(before.furthest,after.furthest);assertEquals(before.bookmarks,after.bookmarks)
        try { store.patchRules(RulesPatch(fxlZoom=FieldUpdate.Set("fit-width")));fail("zoom is per book") } catch(_: IllegalArgumentException) { }
        try { store.patchRules(RulesPatch(theme=FieldUpdate.Set("dark"),fontSize=FieldUpdate.Set(99f)));fail("invalid multi-field patch") } catch(_: IllegalArgumentException) { }
        assertEquals("white",store.effectiveRules().rules.theme)
    }
    @Test fun concurrentFieldPatchesNeverLoseAnotherFieldAndNewBooksShareSettings() {
        fixture();val db=helper();val first=SqliteReadingStore(db);val second=SqliteReadingStore(db)
        val start=java.util.concurrent.CountDownLatch(1)
        val failure=java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val jobs=listOf(Thread { try { start.await();repeat(20) { first.patchRules("a","reva",RulesPatch(fontSize=FieldUpdate.Set(18f+it%3))) } } catch(e: Throwable) { failure.set(e) } },
            Thread { try { start.await();repeat(20) { second.patchRules(RulesPatch(theme=FieldUpdate.Set("paper"))) } } catch(e: Throwable) { failure.set(e) } })
        jobs.forEach { it.start() };start.countDown();jobs.forEach { it.join(10000);assertFalse(it.isAlive) }
        failure.get()?.let { throw AssertionError("concurrent patch failed",it) }
        val current=first.effectiveRules("a")
        assertEquals(19f,current.rules.fontSize);assertEquals("paper",current.rules.theme)
        assertEquals(current.preferencesRevision,first.preferencesRevision.value)
        db.insert(TxtRecord("fresh","新书","作者","fresh-hash",3,99,0,"UTF-8","fresh-rev",1,3,Locator.Text("fresh-rev",0,0),0,16f,"white"),"original.txt")
        assertEquals("paper",first.loadSnapshot("fresh").rules.theme)
        assertEquals(19f,first.loadSnapshot("fresh").rules.fontSize)
        assertEquals("original.txt",requireNotNull(db.content("fresh")).originalName)
        db.writableDatabase.rawQuery("SELECT font_size,theme,flow FROM reading_settings WHERE content_id='fresh'",null).use { assertTrue(it.moveToFirst());repeat(3) { column -> assertTrue(it.isNull(column)) } }
        first.patchRules("a","reva",RulesPatch(lineHeight=FieldUpdate.Set(1.75),fontFamily=FieldUpdate.Set("serif")))
        assertEquals(1.75,first.effectiveRules("b").rules.lineHeight,0.0)
        assertEquals("serif",first.effectiveRules("b").rules.fontFamily)
    }
    @Test fun unknownFormatPayloadsRoundTripWithoutPretendingToOpenEpub() {
        val db=helper();val database=db.writableDatabase
        val raw="{ \"schema\":9, \"t\":\"future\", \"version\":\"epub-version\", \"payload\":42 }"
        database.execSQL("INSERT INTO contents(id,format,revision,title,author,hash,bytes,imported_at) VALUES('epub','epub','epub-version','存储夹具','作者','epub-hash',12,1)")
        database.execSQL("INSERT INTO epub_meta(content_id,spine_count,package_revision,sanitizer_version,layout,fxl_json) VALUES('epub',2,'package','future','reflowable',NULL)")
        database.execSQL("INSERT INTO positions VALUES('epub',?,0,?,0,1)",arrayOf(raw,raw))
        database.execSQL("INSERT INTO reading_settings(content_id) VALUES('epub')")
        val store=SqliteReadingStore(db);val snapshot=store.loadSnapshot("epub")
        assertEquals(raw,LocatorCodec.encode(snapshot.current))
        assertEquals(raw,ReadingExtentCodec.encode(snapshot.furthest))
        assertEquals(ContentFormat.EPUB,db.contents().single().format)
        assertTrue(snapshot.current is Locator.Unknown)
        // This verifies durable storage only; no EPUB renderer/import success is claimed.
    }
    @Test fun viewModelReportsCorruptPreferencesInitializationAndCanRetryWithoutLosingSource() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val app=instrumentation.targetContext.applicationContext as com.localreader.app.ReaderApplication
        val source=context.getDatabasePath("library.db")
        val bad=ByteArray(4096) { 0x41 };source.writeBytes(bad)
        val preferences=SqliteReadingStore(helper())
        val models=androidx.lifecycle.ViewModelStore()
        lateinit var model: com.localreader.app.ui.ReaderViewModel
        try {
            instrumentation.runOnMainSync {
                model=com.localreader.app.ui.ReaderViewModel(app,app.readingStore,preferences)
                models.put("failure",model)
            }
            val deadline=android.os.SystemClock.uptimeMillis()+10000
            while((model.state.value.loading || model.state.value.error==null) && android.os.SystemClock.uptimeMillis()<deadline) Thread.sleep(25)
            assertFalse(model.state.value.loading);assertNotNull(model.state.value.error)
            assertArrayEquals(bad,source.readBytes())
            source.delete();fixture()
            instrumentation.runOnMainSync { model.retryLibrary() }
            val retryDeadline=android.os.SystemClock.uptimeMillis()+10000
            while(model.state.value.error!=null && android.os.SystemClock.uptimeMillis()<retryDeadline) Thread.sleep(25)
            assertNull(model.state.value.error)
            preferences.effectiveRules()
            SQLiteDatabase.openDatabase(source.path,null,SQLiteDatabase.OPEN_READONLY).use { db -> assertEquals(6,db.version) }
        } finally { instrumentation.runOnMainSync { models.clear() } }
    }
    @Test fun historicalOutOfRangeFractionsSurviveMigrationButNewWritesStayStrict() = kotlinx.coroutines.runBlocking {
        for(version in 1..3) {
            val path=fixture(version)
            val original=File(context.filesDir,"library/a/original.txt").apply { parentFile!!.mkdirs();writeText("旧书正文。\n".repeat(20)) }
            val index=File(context.filesDir,"library/a/versions/reva")
            val parsed=TxtParser.build(original,index)
            val historicalOffset=parsed.totalCharacters*2
            SQLiteDatabase.openDatabase(path.path,null,SQLiteDatabase.OPEN_READWRITE).use { old ->
                old.execSQL("UPDATE books SET characters=?,paragraphs=? WHERE id='a'",arrayOf<Any>(parsed.totalCharacters,parsed.paragraphs))
                old.execSQL("UPDATE bookmarks SET source_offset=?" + (if(version>=2) ",fallback_fraction=2.0" else "") + " WHERE book_id='a'",arrayOf<Any>(historicalOffset))
                if(version>=2) old.execSQL("UPDATE books SET current_fraction=2.0 WHERE id='a'")
            }
            val repository=LibraryRepository(context);helpers += repository.db
            val store=SqliteReadingStore(repository.db)
            val bookmark=store.bookmarks("a").single()
            val locator=bookmark.locator as Locator.Text
            assertEquals(historicalOffset,locator.sourceOffset);assertEquals("history",locator.version)
            assertEquals(if(version==1) 1.0 else 2.0,locator.fallbackFraction,0.0)
            if(version>=2) assertEquals(2.0,(store.loadSnapshot("a").current as Locator.Text).fallbackFraction,0.0)
            val raw=repository.db.readableDatabase.rawQuery("SELECT locator_json,fallback_fraction FROM bookmarks WHERE id=?",arrayOf(bookmark.id.toString())).use { assertTrue(it.moveToFirst());assertEquals(locator.fallbackFraction,it.getDouble(1),0.0);it.getString(0) }
            val opened=LibraryOpenedBookFactory(repository).open("a")
            try {
                val target=opened.publication.target(locator)
                assertTrue(target.nearby)
                val resolved=requireNotNull(opened.publication.locator(target.section,target.blockId,target.character))
                assertTrue(opened.accepts(resolved));assertEquals(1.0,opened.fractionOf(resolved),0.02)
                val invalid=Locator.Text(opened.revision,0,0,0,2.0)
                try { store.addBookmark("a",opened.revision,invalid,"invalid");fail("new invalid fallback") } catch(_: IllegalArgumentException) { }
                try { store.savePosition("a",opened.revision,invalid,TxtReadingExtent(opened.revision,0));fail("new invalid position") } catch(_: IllegalArgumentException) { }
                repository.db.readableDatabase.rawQuery("SELECT locator_json FROM bookmarks WHERE id=?",arrayOf(bookmark.id.toString())).use { assertTrue(it.moveToFirst());assertEquals(raw,it.getString(0)) }
            } finally { opened.close() }
            repository.db.close()
            val reopened=helper();assertEquals(locator,SqliteReadingStore(reopened).bookmarks("a").single().locator)
            reopened.close();SQLiteDatabase.deleteDatabase(path)
        }
    }
}
