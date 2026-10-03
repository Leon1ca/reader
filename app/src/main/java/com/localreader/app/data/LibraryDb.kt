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
                val fontSize: Float, val theme: String)
data class Bookmark(val id: Long, val bookId: String, val locator: Locator.Text, val excerpt: String, val created: Long)

class LibraryDb(context: Context) : SQLiteOpenHelper(context, "library.db", null, 2) {
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE books(id TEXT PRIMARY KEY,title TEXT NOT NULL,author TEXT NOT NULL,
            hash TEXT NOT NULL UNIQUE,bytes INTEGER NOT NULL,imported INTEGER NOT NULL,last_read INTEGER NOT NULL DEFAULT 0,
            encoding TEXT NOT NULL,version TEXT NOT NULL,paragraphs INTEGER NOT NULL,characters INTEGER NOT NULL,
            current_offset INTEGER NOT NULL DEFAULT 0,current_paragraph INTEGER NOT NULL DEFAULT 0,current_character INTEGER NOT NULL DEFAULT 0,
            current_fraction REAL NOT NULL DEFAULT 0,furthest INTEGER NOT NULL DEFAULT 0,font_size REAL NOT NULL DEFAULT 16,theme TEXT NOT NULL DEFAULT 'white')""")
        db.execSQL("""CREATE TABLE bookmarks(id INTEGER PRIMARY KEY AUTOINCREMENT,book_id TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE,
            version TEXT NOT NULL,source_offset INTEGER NOT NULL,paragraph INTEGER NOT NULL,character INTEGER NOT NULL,fallback_fraction REAL NOT NULL DEFAULT 0,excerpt TEXT NOT NULL,created INTEGER NOT NULL,
            UNIQUE(book_id,version,source_offset))""")
        db.execSQL("""CREATE TABLE locator_backups(id INTEGER PRIMARY KEY AUTOINCREMENT,book_id TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE,
            version TEXT NOT NULL,payload TEXT NOT NULL,created INTEGER NOT NULL)""")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion == 1 && newVersion == 2) {
            db.execSQL("ALTER TABLE books ADD COLUMN current_fraction REAL NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE bookmarks ADD COLUMN fallback_fraction REAL NOT NULL DEFAULT 0")
            db.execSQL("UPDATE books SET current_fraction=CASE WHEN characters>0 THEN current_offset*1.0/characters ELSE 0 END")
            db.execSQL("""UPDATE bookmarks SET fallback_fraction=COALESCE((SELECT CASE WHEN characters>0 THEN bookmarks.source_offset*1.0/characters ELSE 0 END FROM books WHERE books.id=bookmarks.book_id),0)""")
        } else error("此版本没有数据库迁移路径；保留原库")
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
            put("font_size", book.fontSize); put("theme", book.theme)
        })
    }
    fun delete(id: String) = transaction { it.delete("books", "id=?", arrayOf(id)) }
    fun savePosition(id: String, locator: Locator.Text, furthest: Long = locator.sourceOffset, time: Long = System.currentTimeMillis()) {
        writableDatabase.execSQL("""UPDATE books SET current_offset=?,current_paragraph=?,current_character=?,current_fraction=?,
            furthest=MAX(furthest,?),last_read=? WHERE id=? AND version=?""", arrayOf(locator.sourceOffset, locator.paragraph, locator.character, locator.fallbackFraction.coerceIn(0.0,1.0), maxOf(locator.sourceOffset, furthest), time, id, locator.version))
    }
    fun setStyle(id: String, font: Float, theme: String) {
        writableDatabase.update("books", ContentValues().apply { put("font_size", font); put("theme", theme) }, "id=?", arrayOf(id))
    }
    fun setMetadata(id: String, title: String, author: String) {
        writableDatabase.update("books", ContentValues().apply { put("title", title); put("author", author) }, "id=?", arrayOf(id))
    }
    fun bookmarks(id: String): List<Bookmark> = readableDatabase.rawQuery("SELECT * FROM bookmarks WHERE book_id=? ORDER BY created DESC", arrayOf(id)).use { c ->
        buildList { while (c.moveToNext()) add(Bookmark(c.long("id"), id,
            Locator.Text(c.string("version"), c.long("source_offset"), c.int("paragraph"), c.int("character"), c.double("fallback_fraction")), c.string("excerpt"), c.long("created"))) }
    }
    fun addBookmark(id: String, locator: Locator.Text, excerpt: String) {
        writableDatabase.insertWithOnConflict("bookmarks", null, ContentValues().apply {
            put("book_id", id); put("version", locator.version); put("source_offset", locator.sourceOffset); put("paragraph", locator.paragraph)
            put("character", locator.character); put("excerpt", excerpt.take(120)); put("created", System.currentTimeMillis())
            put("fallback_fraction", locator.fallbackFraction.coerceIn(0.0, 1.0))
        }, SQLiteDatabase.CONFLICT_IGNORE)
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
        // Keep the original bookmark payload/version. ReaderEngine resolves it with an explicit nearby warning.
        db.update("books", ContentValues().apply {
            put("encoding", encoding); put("version", version); put("paragraphs", paragraphs); put("characters", characters)
            put("current_offset", old.current.sourceOffset.coerceAtMost((characters - 1).coerceAtLeast(0)))
            put("furthest", old.furthest.coerceAtMost((characters - 1).coerceAtLeast(0)))
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
        getFloat(getColumnIndexOrThrow("font_size")), string("theme"))
}
