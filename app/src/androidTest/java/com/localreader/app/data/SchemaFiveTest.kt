package com.localreader.app.data

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.localreader.app.core.*
import com.localreader.app.formats.txt.TxtReadingExtent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class SchemaFiveTest {
    private lateinit var root: File
    private lateinit var context: Context
    private val helpers=mutableListOf<LibraryDb>()
    private val tables=listOf("contents","txt_meta","epub_meta","positions","bookmarks","locator_backups","reading_settings","reader_defaults","sqlite_sequence")
    @Before fun setup() {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        root=File(target.cacheDir,"schema-five-${UUID.randomUUID()}").apply { mkdirs() }
        context=object: ContextWrapper(target) {
            override fun getFilesDir()=File(root,"files").apply { mkdirs() }
            override fun getDatabasePath(name: String)=File(root,name)
            override fun openOrCreateDatabase(name: String,mode: Int,factory: SQLiteDatabase.CursorFactory?)=SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory)
            override fun openOrCreateDatabase(name: String,mode: Int,factory: SQLiteDatabase.CursorFactory?,handler: DatabaseErrorHandler?)=SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path,factory,handler)
        }
    }
    @After fun cleanup() { helpers.forEach { it.close() };root.deleteRecursively() }
    private fun helper(checkpoint: (String)->Unit = {})=LibraryDb(context,checkpoint).also { helpers+=it }
    private fun fixture(): File {
        val path=context.getDatabasePath("library.db")
        SQLiteDatabase.openOrCreateDatabase(path,null).use { db ->
            LibraryDb.SCHEMA.forEach { sql -> db.execSQL(sql.replace(",hidden_from_continue INTEGER NOT NULL DEFAULT 0 CHECK(hidden_from_continue IN (0,1))","").replace(",canonical_characters INTEGER","")) }
            for((id,format) in listOf("txt" to "txt","epub" to "epub")) {
                db.execSQL("INSERT INTO contents(id,format,revision,title,author,hash,bytes,imported_at,last_read_at,finished_at,original_name) VALUES(?,?,'rev',?,'作者',?,123,11,12,NULL,?)",arrayOf(id,format,"书$id","hash$id","原名$id"))
                val locator=if(format=="txt")LocatorCodec.encode(Locator.Text("rev",40,0,40,0.4)) else "{\"type\":\"future\",\"version\":\"rev\",\"payload\":\"😀\"}"
                val extent=if(format=="txt")ReadingExtentCodec.encode(TxtReadingExtent("rev",100)) else locator
                db.execSQL("INSERT INTO positions VALUES(?,?,0.4,?,1.0,13)",arrayOf(id,locator,extent))
                db.execSQL("INSERT INTO reading_settings(content_id,font_size,theme,flow) VALUES(?,17.7,NULL,'scrolled')",arrayOf(id))
                db.execSQL("INSERT INTO bookmarks(content_id,locator_json,locator_key,fallback_fraction,excerpt,created) VALUES(?,?,?,0.4,'原书签😀',15)",arrayOf(id,locator,"key$id"))
                db.execSQL("INSERT INTO locator_backups(content_id,version,payload,payload_format,created) VALUES(?,'older','原始字节😀','unknown-v1',16)",arrayOf(id))
            }
            db.execSQL("INSERT INTO txt_meta VALUES('txt','UTF-8',1,100)")
            db.execSQL("INSERT INTO epub_meta(content_id,spine_count,package_revision,sanitizer_version,layout) VALUES('epub',2,'rev','old','reflowable')")
            db.execSQL("INSERT INTO reader_defaults(scope,theme,flow,prefs_revision) VALUES('app','dark','paginated',27)")
            for(scope in listOf("txt","epub"))db.execSQL("INSERT INTO reader_defaults(scope,font_size,line_height,margin,font_family,columns,epub_style_mode,prefs_revision) VALUES(?,16,1.6,'M','default','auto','AUTHOR',27)",arrayOf(scope))
            db.execSQL("UPDATE sqlite_sequence SET seq=900 WHERE name='bookmarks'")
            db.execSQL("UPDATE sqlite_sequence SET seq=800 WHERE name='locator_backups'")
            db.version=4
        }
        return path
    }
    private fun rows(db: SQLiteDatabase,table: String,columns: List<String>?=null): List<List<String?>> = db.rawQuery("SELECT ${columns?.joinToString(",") ?: "*"} FROM $table ORDER BY rowid",null).use { c -> buildList { while(c.moveToNext())add((0 until c.columnCount).map { if(c.isNull(it))null else c.getString(it) }) } }
    @Test fun trueV4WalSnapshotMigratesEveryOriginalFieldAndSequenceThenDoesNotBackupAgain() {
        val source=fixture()
        val before=linkedMapOf<String,Pair<List<String>,List<List<String?>>>>()
        SQLiteDatabase.openDatabase(source.path,null,SQLiteDatabase.OPEN_READWRITE,LibraryBootstrap.preserveCorruption).use { writer ->
            writer.enableWriteAheadLogging()
            writer.rawQuery("PRAGMA wal_autocheckpoint=0",null).use { assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0)) }
            writer.execSQL("UPDATE contents SET author='已提交 WAL 😀' WHERE id='epub'")
            tables.forEach { table -> val columns=writer.rawQuery("SELECT * FROM $table LIMIT 0",null).use { it.columnNames.toList() };before[table]=columns to rows(writer,table) }
            val db=helper().writableDatabase
            assertEquals(6,db.version)
            before.forEach { (table,data) -> assertEquals(table,data.second,rows(db,table,data.first)) }
            db.rawQuery("SELECT hidden_from_continue FROM contents",null).use { c -> while(c.moveToNext())assertEquals(0,c.getInt(0)) }
            db.rawQuery("SELECT canonical_characters FROM epub_meta",null).use { assertTrue(it.moveToFirst());assertTrue(it.isNull(0)) }
        }
        val backup=root.listFiles()!!.single { it.name.endsWith(".bak") }
        val hash=MessageDigest.getInstance("SHA-256").digest(backup.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals(hash,File(backup.path+".sha256").readText())
        SQLiteDatabase.openDatabase(backup.path,null,SQLiteDatabase.OPEN_READONLY,LibraryBootstrap.preserveCorruption).use { db ->
            assertEquals(4,db.version);before.forEach { (table,data) -> assertEquals(table,data.second,rows(db,table,data.first)) }
        }
        helpers.forEach { it.close() };helper().writableDatabase
        assertEquals(1,root.listFiles()!!.count { it.name.endsWith(".bak") })
    }
    @Test fun failedAlterRollsBackVersionAndBothColumnsAndReopeningCanRetry() {
        val source=fixture()
        assertThrows(IllegalStateException::class.java) { helper { if(it=="after-alter")error("injected migration failure") }.writableDatabase }
        helpers.forEach { it.close() }
        SQLiteDatabase.openDatabase(source.path,null,SQLiteDatabase.OPEN_READONLY,LibraryBootstrap.preserveCorruption).use { db ->
            assertEquals(4,db.version)
            db.rawQuery("PRAGMA table_info(contents)",null).use { c -> while(c.moveToNext())assertNotEquals("hidden_from_continue",c.getString(1)) }
        }
        assertEquals(6,helper().writableDatabase.version)
    }
    @Test fun finishedIsIndependentOfPositionAndVerifiedReadOnlyMovesLastRead() = runBlocking {
        fixture();val repository=LibraryRepository(context);helpers+=repository.db
        val original=repository.db.txt("txt")!!.current
        repository.setFinished("txt",true)
        assertNotNull(repository.content("txt")!!.finishedAt)
        repository.markRead("txt","rev",42)
        val after=repository.content("txt")!!
        assertEquals(42L,after.lastRead);assertNotNull(after.finishedAt)
        assertEquals(original,repository.db.txt("txt")!!.current)
        repository.setFinished("txt",false);assertNull(repository.content("txt")!!.finishedAt)
        assertThrows(IllegalStateException::class.java) { repository.markRead("txt","old",100) }
        assertEquals(42L,repository.content("txt")!!.lastRead)
        // The book's other records are untouched by finishing and reading.
        assertEquals("原名epub",repository.content("epub")!!.originalName)
    }
    @Test fun previousUiSortMigratesOnlyWhenNewPreferenceIsAbsent() {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        for((legacy,expected) in listOf("标题" to ShelfSort.TITLE,"导入时间" to ShelfSort.RECENT,"最近阅读" to ShelfSort.RECENT)) {
            val prefix="ui-migration-${UUID.randomUUID()}"
            val isolated=object: ContextWrapper(target) {
                override fun getSharedPreferences(name: String,mode: Int)=target.getSharedPreferences("$prefix-$name",mode)
            }
            try {
                assertTrue(isolated.getSharedPreferences("ui",0).edit().putString("sort",legacy).commit())
                val store=AndroidUiPrefsStore(isolated)
                assertEquals(expected,store.shelf().sort)
                store.saveShelf(ShelfPreferences(ShelfSort.AUTHOR))
                assertTrue(isolated.getSharedPreferences("ui",0).edit().putString("sort","导入时间").commit())
                assertEquals(ShelfPreferences(ShelfSort.AUTHOR),AndroidUiPrefsStore(isolated).shelf())
                assertEquals("导入时间",isolated.getSharedPreferences("ui",0).getString("sort",null))
            } finally { target.deleteSharedPreferences("$prefix-ui");target.deleteSharedPreferences("$prefix-ui_prefs") }
        }
    }

    /** The shelf no longer has a format filter or an "imported" order; values stored by older versions must not hide or reorder anything. */
    @Test fun oldShelfFilterAndImportedOrderReadBackAsTheDefaultViewAndTheFilterKeyIsDropped() {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        val prefix="ui-old-filter-${UUID.randomUUID()}"
        val isolated=object: ContextWrapper(target) {
            override fun getSharedPreferences(name: String,mode: Int)=target.getSharedPreferences("$prefix-$name",mode)
        }
        try {
            assertTrue(isolated.getSharedPreferences("ui_prefs",0).edit().putString("shelf_sort","IMPORTED").putString("shelf_filter","EPUB").putLong("shelf_category",7L).commit())
            val store=AndroidUiPrefsStore(isolated)
            assertEquals("IMPORTED normalises to the recent order, the category is kept",ShelfPreferences(ShelfSort.RECENT,7L),store.shelf())
            store.saveShelf(store.shelf())
            val saved=isolated.getSharedPreferences("ui_prefs",0)
            assertFalse("the dead filter is not written back",saved.contains("shelf_filter"));assertEquals("RECENT",saved.getString("shelf_sort",null))
        } finally { target.deleteSharedPreferences("$prefix-ui");target.deleteSharedPreferences("$prefix-ui_prefs") }
    }
}
