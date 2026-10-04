package com.localreader.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.localreader.app.core.Locator

data class Book(val id: String, val title: String, val author: String, val hash: String, val bytes: Long,
                val imported: Long, val lastRead: Long, val encoding: String, val version: String,
                val paragraphs: Int, val characters: Long, val current: Locator.Text, val furthest: Long,
                val fontSize: Float, val theme: String, val flow: String = "paginated")
data class Bookmark(val id: Long, val bookId: String, val locator: Locator.Text, val excerpt: String, val created: Long)

class LibraryDb(context: Context) : SQLiteOpenHelper(context, "library.db", null, 3) {
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    /** A v3 database upgraded from a very old v1 by an earlier build may lack this table; creating it is idempotent. */
    override fun onOpen(db: SQLiteDatabase) {
        if (!db.isReadOnly) db.execSQL("""CREATE TABLE IF NOT EXISTS locator_backups(id INTEGER PRIMARY KEY AUTOINCREMENT,book_id TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE,
            version TEXT NOT NULL,payload TEXT NOT NULL,created INTEGER NOT NULL)""")
    }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE books(id TEXT PRIMARY KEY,title TEXT NOT NULL,author TEXT NOT NULL,
            hash TEXT NOT NULL UNIQUE,bytes INTEGER NOT NULL,imported INTEGER NOT NULL,last_read INTEGER NOT NULL DEFAULT 0,
            encoding TEXT NOT NULL,version TEXT NOT NULL,paragraphs INTEGER NOT NULL,characters INTEGER NOT NULL,
            current_offset INTEGER NOT NULL DEFAULT 0,current_paragraph INTEGER NOT NULL DEFAULT 0,current_character INTEGER NOT NULL DEFAULT 0,
            current_fraction REAL NOT NULL DEFAULT 0,furthest INTEGER NOT NULL DEFAULT 0,font_size REAL NOT NULL DEFAULT 16,theme TEXT NOT NULL DEFAULT 'white',flow TEXT NOT NULL DEFAULT 'paginated')""")
        db.execSQL("""CREATE TABLE bookmarks(id INTEGER PRIMARY KEY AUTOINCREMENT,book_id TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE,
            version TEXT NOT NULL,source_offset INTEGER NOT NULL,paragraph INTEGER NOT NULL,character INTEGER NOT NULL,fallback_fraction REAL NOT NULL DEFAULT 0,excerpt TEXT NOT NULL,created INTEGER NOT NULL,
            UNIQUE(book_id,version,source_offset))""")
        db.execSQL("""CREATE TABLE locator_backups(id INTEGER PRIMARY KEY AUTOINCREMENT,book_id TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE,
            version TEXT NOT NULL,payload TEXT NOT NULL,created INTEGER NOT NULL)""")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        require(oldVersion in 1..2 && newVersion == 3) { "此版本没有数据库迁移路径；保留原库" }
        if (oldVersion == 1) {
            db.execSQL("ALTER TABLE books ADD COLUMN current_fraction REAL NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE bookmarks ADD COLUMN fallback_fraction REAL NOT NULL DEFAULT 0")
            db.execSQL("UPDATE books SET current_fraction=CASE WHEN characters>0 THEN current_offset*1.0/characters ELSE 0 END")
            db.execSQL("""UPDATE bookmarks SET fallback_fraction=COALESCE((SELECT CASE WHEN characters>0 THEN bookmarks.source_offset*1.0/characters ELSE 0 END FROM books WHERE books.id=bookmarks.book_id),0)""")
        }
        // Old users keep the previous vertical reading mode, font size and all anchors,
        // but the column default must match onCreate so books imported after the upgrade are paginated.
        db.execSQL("ALTER TABLE books ADD COLUMN flow TEXT NOT NULL DEFAULT 'paginated'")
        db.execSQL("UPDATE books SET flow='scrolled'")
        db.execSQL("""CREATE TABLE IF NOT EXISTS locator_backups(id INTEGER PRIMARY KEY AUTOINCREMENT,book_id TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE,
            version TEXT NOT NULL,payload TEXT NOT NULL,created INTEGER NOT NULL)""")
    }
    fun books(): List<Book> = readableDatabase.rawQuery("SELECT * FROM books", null).use { c -> buildList { while (c.moveToNext()) add(c.book()) } }
    fun book(id: String): Book? = readableDatabase.rawQuery("SELECT * FROM books WHERE id=?", arrayOf(id)).use { if (it.moveToFirst()) it.book() else null }
    fun byHash(hash: String): Book? = readableDatabase.rawQuery("SELECT * FROM books WHERE hash=?", arrayOf(hash)).use { if (it.moveToFirst()) it.book() else null }
    fun retainedVersions(id: String): Set<String> = readableDatabase.rawQuery("SELECT version FROM locator_backups WHERE book_id=?", arrayOf(id)).use { c ->
        buildSet { while (c.moveToNext()) add(c.getString(0)) }
    }
    fun insert(book: Book) = transaction { db ->
        db.insertOrThrow("books", null, ContentValues().apply {
            put("id", book.id); put("title", book.title); put("author", book.author); put("hash", book.hash); put("bytes", book.bytes)
            put("imported", book.imported); put("encoding", book.encoding); put("version", book.version)
            put("paragraphs", book.paragraphs); put("characters", book.characters)
            put("font_size", book.fontSize); put("theme", book.theme); put("flow", book.flow)
        })
    }
    fun delete(id: String) = transaction { it.delete("books", "id=?", arrayOf(id)) }
    fun savePosition(id: String, locator: Locator.Text, furthest: Long = locator.sourceOffset, time: Long = System.currentTimeMillis()) {
        writableDatabase.execSQL("""UPDATE books SET current_offset=?,current_paragraph=?,current_character=?,current_fraction=?,
            furthest=MAX(furthest,?),last_read=? WHERE id=? AND version=?""", arrayOf<Any>(locator.sourceOffset, locator.paragraph, locator.character, locator.fallbackFraction.coerceIn(0.0,1.0), furthest, time, id, locator.version))
    }
    fun setStyle(id: String, font: Float, theme: String) {
        writableDatabase.update("books", ContentValues().apply { put("font_size", font); put("theme", theme) }, "id=?", arrayOf(id))
    }
    fun setFlow(id: String, flow: String) {
        require(flow in setOf("paginated","scrolled"))
        writableDatabase.update("books",ContentValues().apply { put("flow",flow) },"id=?",arrayOf(id))
    }
    fun setMetadata(id: String, title: String, author: String) {
        writableDatabase.update("books", ContentValues().apply { put("title", title); put("author", author) }, "id=?", arrayOf(id))
    }
    fun bookmarks(id: String): List<Bookmark> = readableDatabase.rawQuery("SELECT * FROM bookmarks WHERE book_id=? ORDER BY created DESC", arrayOf(id)).use { c ->
        buildList { while (c.moveToNext()) add(Bookmark(c.long("id"), id,
            Locator.Text(c.string("version"), c.long("source_offset"), c.int("paragraph"), c.int("character"), c.double("fallback_fraction")), c.string("excerpt"), c.long("created"))) }
    }
    /** Returns false when the same position already has a bookmark. */
    fun addBookmark(id: String, locator: Locator.Text, excerpt: String): Boolean {
        return writableDatabase.insertWithOnConflict("bookmarks", null, ContentValues().apply {
            put("book_id", id); put("version", locator.version); put("source_offset", locator.sourceOffset); put("paragraph", locator.paragraph)
            put("character", locator.character); put("excerpt", excerpt.take(120)); put("created", System.currentTimeMillis())
            put("fallback_fraction", locator.fallbackFraction.coerceIn(0.0, 1.0))
        }, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }
    fun deleteBookmark(id: Long) { writableDatabase.delete("bookmarks", "id=?", arrayOf(id.toString())) }

    /** The prior index and all anchors remain recoverable even if decoding changes their meaning. */
    fun reindex(id: String, encoding: String, version: String, paragraphs: Int, characters: Long) = transaction { db ->
        val old = requireNotNull(book(id))
        val anchors = bookmarks(id)
        val backup = buildString {
            append("current=${old.current.sourceOffset},${old.current.paragraph},${old.current.character},${old.current.fallbackFraction};furthest=${old.furthest}\n")
            anchors.forEach { append("bookmark=${it.id},${it.locator.version},${it.locator.sourceOffset},${it.locator.paragraph},${it.locator.character},${it.locator.fallbackFraction}\n") }
        }
        db.insertOrThrow("locator_backups", null, ContentValues().apply {
            put("book_id", id); put("version", old.version); put("payload", backup); put("created", System.currentTimeMillis())
        })
        // Retain only the three newest locator backups (and, via recover(), their index directories).
        db.execSQL("DELETE FROM locator_backups WHERE book_id=? AND id NOT IN (SELECT id FROM locator_backups WHERE book_id=? ORDER BY id DESC LIMIT 3)", arrayOf<Any>(id, id))
        // Keep original bookmark payload/version. TxtTextSource resolves it by fraction with a nearby warning.
        // A different decoding changes the character count, so source offsets are rescaled instead of reused.
        val last = (characters - 1).coerceAtLeast(0)
        val scale = if (old.characters > 0 && old.characters != characters) characters.toDouble() / old.characters else 1.0
        fun remap(offset: Long) = (offset * scale).toLong().coerceIn(0, last)
        val current = remap(old.current.sourceOffset)
        db.update("books", ContentValues().apply {
            put("encoding", encoding); put("version", version); put("paragraphs", paragraphs); put("characters", characters)
            put("current_offset", current)
            put("current_fraction", if (characters > 0) current.toDouble() / characters else 0.0)
            put("furthest", remap(old.furthest))
            put("current_paragraph", 0); put("current_character", 0)
        }, "id=?", arrayOf(id))
    }
    private fun <T> transaction(action: (SQLiteDatabase) -> T): T {
        val db = writableDatabase; db.beginTransaction()
        try { return action(db).also { db.setTransactionSuccessful() } } finally { db.endTransaction() }
    }
    private fun Cursor.string(name: String) = getString(getColumnIndexOrThrow(name))
    private fun Cursor.long(name: String) = getLong(getColumnIndexOrThrow(name))
    private fun Cursor.int(name: String) = getInt(getColumnIndexOrThrow(name))
    private fun Cursor.double(name: String) = getDouble(getColumnIndexOrThrow(name))
    private fun Cursor.book() = Book(string("id"), string("title"), string("author"), string("hash"), long("bytes"), long("imported"), long("last_read"),
        string("encoding"), string("version"), int("paragraphs"), long("characters"),
        Locator.Text(string("version"), long("current_offset"), int("current_paragraph"), int("current_character"), double("current_fraction")), long("furthest"),
        getFloat(getColumnIndexOrThrow("font_size")), string("theme"),string("flow"))
}
