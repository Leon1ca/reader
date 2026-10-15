package com.localreader.app.data

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

/** v6 adds categories. A v5 library must keep every row, take a verified backup first, and roll back as a unit on failure. */
class SchemaSixTest {
    private lateinit var root: File
    private lateinit var context: Context
    private val helpers = mutableListOf<LibraryDb>()
    @Before fun setup() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(target.cacheDir,"schema-six-${UUID.randomUUID()}").apply { mkdirs() }
        context = object: ContextWrapper(target) {
            override fun getFilesDir() = File(root,"files").apply { mkdirs() }
            override fun getDatabasePath(name: String) = File(root,name)
            override fun openOrCreateDatabase(name: String,mode: Int,factory: SQLiteDatabase.CursorFactory?) = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory)
            override fun openOrCreateDatabase(name: String,mode: Int,factory: SQLiteDatabase.CursorFactory?,handler: DatabaseErrorHandler?) = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path,factory,handler)
        }
    }
    @After fun cleanup() { helpers.forEach { it.close() };root.deleteRecursively() }
    private fun helper(checkpoint: (String) -> Unit = {}) = LibraryDb(context,checkpoint).also { helpers += it }

    /** A real v5 file: the current schema without the category tables. */
    private fun v5(): File {
        val path = context.getDatabasePath("library.db")
        SQLiteDatabase.openOrCreateDatabase(path,null).use { db ->
            LibraryDb.SCHEMA.forEach(db::execSQL)
            // A real v5 library has all three default rows, not just the app row.
            db.execSQL("INSERT INTO reader_defaults(scope,theme,flow,prefs_revision) VALUES('app','white','paginated',0)")
            for(scope in listOf("txt","epub"))db.execSQL("INSERT INTO reader_defaults(scope,font_size,line_height,margin,font_family,columns,epub_style_mode,prefs_revision) VALUES(?,16,1.4,'M','default','auto','AUTHOR',0)",arrayOf(scope))
            for(id in listOf("a","b","c")) {
                db.execSQL("INSERT INTO contents(id,format,revision,title,author,hash,bytes,imported_at,last_read_at,original_name) VALUES(?,'txt','rev',?,'作者',?,10,1,2,'x.txt')",arrayOf(id,"书$id","hash$id"))
                db.execSQL("INSERT INTO txt_meta VALUES(?,'UTF-8',1,10)",arrayOf(id))
                db.execSQL("INSERT INTO positions VALUES(?,?,0.1,?,0.1,3)",arrayOf(id,"{}","{}"))
                db.execSQL("INSERT INTO reading_settings(content_id) VALUES(?)",arrayOf(id))
            }
            db.version = 5
        }
        return path
    }

    @Test fun v5MigratesWithAllRowsAndAnEmptyCategorySet() {
        v5()
        val db = helper()
        assertEquals(6,db.writableDatabase.version)
        assertEquals(listOf("a","b","c"),db.contents().map { it.id }.sorted())
        assertTrue(db.categories().isEmpty());assertEquals(3,db.uncategorizedCount())
        assertEquals(1,root.listFiles()!!.count { it.name.endsWith(".bak") })
    }
    @Test fun failedUpgradeRollsBackAndRetries() {
        val source = v5()
        assertThrows(IllegalStateException::class.java) { helper { if(it == "before-commit")error("injected") }.writableDatabase }
        helpers.forEach { it.close() }
        SQLiteDatabase.openDatabase(source.path,null,SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(5,db.version)
            db.rawQuery("SELECT 1 FROM sqlite_master WHERE name='categories'",null).use { assertFalse(it.moveToFirst()) }
        }
        assertEquals(6,helper().writableDatabase.version)
    }
    @Test fun categoriesCreateRenameOrderAndDeleteWithoutTouchingBooks() {
        v5();val db = helper()
        val novels = db.createCategory("  小说  ");val tools = db.createCategory("工具书")
        assertThrows(IllegalArgumentException::class.java) { db.createCategory("小说") }
        assertThrows(IllegalArgumentException::class.java) { db.createCategory(" ") }
        assertThrows(IllegalArgumentException::class.java) { db.createCategory("x".repeat(41)) }
        db.renameCategory(tools,"参考书")
        assertThrows(IllegalArgumentException::class.java) { db.renameCategory(tools,"小说") }
        assertEquals(listOf("小说","参考书"),db.categories().map { it.name })
        db.reorderCategories(listOf(tools,novels))
        assertEquals(listOf("参考书","小说"),db.categories().map { it.name })
        assertThrows(IllegalArgumentException::class.java) { db.reorderCategories(listOf(tools)) }
        db.setMembership(listOf("a","b"),novels,true);db.setMembership(listOf("b"),tools,true)
        assertEquals(setOf(novels),db.summary("a")!!.categories);assertEquals(setOf(novels,tools),db.summary("b")!!.categories);assertTrue(db.summary("c")!!.categories.isEmpty())
        assertEquals(2,db.categories().first { it.id == novels }.count);assertEquals(1,db.uncategorizedCount())
        db.deleteCategory(novels)
        assertEquals(3,db.contents().size)
        assertEquals(setOf(tools),db.summary("b")!!.categories);assertTrue(db.summary("a")!!.categories.isEmpty())
        assertEquals(listOf(0),db.categories().map { it.sort })
        db.setMembership(listOf("b"),tools,false)
        assertEquals(3,db.uncategorizedCount())
    }
    @Test fun deletingABookRemovesItsMembershipAndFilteringAThousandBooksIsFast() {
        v5();val db = helper()
        val category = db.createCategory("多本")
        db.setMembership(listOf("a","b"),category,true)
        db.delete("a")
        assertEquals(1,db.categories().single().count)
        db.writableDatabase.beginTransaction()
        try {
            for(i in 0 until 1000)db.writableDatabase.execSQL("INSERT INTO contents(id,format,revision,title,author,hash,bytes,imported_at,last_read_at) VALUES('n$i','txt','r','书$i','a','h$i',1,1,1)")
            db.writableDatabase.execSQL("INSERT INTO content_categories SELECT id,$category FROM contents WHERE id LIKE 'n%' AND CAST(substr(id,2) AS INTEGER)%2=0")
            db.writableDatabase.setTransactionSuccessful()
        } finally { db.writableDatabase.endTransaction() }
        val begin = System.nanoTime()
        db.writableDatabase.rawQuery("SELECT COUNT(*) FROM contents c WHERE EXISTS(SELECT 1 FROM content_categories m WHERE m.content_id=c.id AND m.category_id=?)",arrayOf(category.toString())).use { it.moveToFirst() }
        assertTrue("category filter over 1000 books took ${(System.nanoTime() - begin) / 1_000_000} ms",(System.nanoTime() - begin) / 1_000_000 < 50)
    }
    @Test fun paragraphSpacingColumnIsAddedWithTheDefaultAndPersistsPatches() {
        v5();val db = helper()
        val store = SqliteReadingStore(db)
        assertEquals(0.6,store.effectiveRules().rules.paragraphSpacing,0.0)
        store.patchRules(com.localreader.app.core.RulesPatch(paragraphSpacing = com.localreader.app.core.FieldUpdate.Set(1.2)))
        assertEquals(1.2,store.effectiveRules().rules.paragraphSpacing,0.0)
        assertThrows(IllegalArgumentException::class.java) { store.patchRules(com.localreader.app.core.RulesPatch(paragraphSpacing = com.localreader.app.core.FieldUpdate.Set(5.0))) }
        assertEquals(1.2,store.effectiveRules().rules.paragraphSpacing,0.0)
    }
}
