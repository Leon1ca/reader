package com.localreader.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.localreader.app.core.*
import com.localreader.app.formats.txt.TxtReadingExtent

/** One schema and one write path for every format. Bootstrap precedes Helper's automatic upgrade. */
class LibraryDb(private val context: Context, private val migrationCheckpoint: (String) -> Unit = {}) : SQLiteOpenHelper(context,"library.db",null,4,LibraryBootstrap.preserveCorruption) {
    val preferencesRevision = kotlinx.coroutines.flow.MutableStateFlow(0L)
    @Volatile private var initialized = false
    override fun getWritableDatabase(): SQLiteDatabase = open { super.getWritableDatabase() }
    override fun getReadableDatabase(): SQLiteDatabase = open { super.getReadableDatabase() }
    private fun open(work: () -> SQLiteDatabase): SQLiteDatabase = synchronized(this) {
        if (initialized) work() else LibraryBootstrap.open(context) { work().also { initialized = true } }
    }
    @Synchronized override fun close() { super.close(); initialized = false }
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) = createSchema(db)
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        require(oldVersion in 1..3 && newVersion == 4) { "此版本没有迁移路径，原库已保留" }
        migrate(db,oldVersion)
        migrationCheckpoint("before-commit")
    }
    override fun onOpen(db: SQLiteDatabase) {
        db.rawQuery("SELECT MAX(prefs_revision) FROM reader_defaults",null).use { check(it.moveToFirst()); preferencesRevision.value = it.getLong(0) }
        db.rawQuery("PRAGMA foreign_key_check",null).use { check(!it.moveToFirst()) { "书库关联校验失败" } }
    }
    private fun createSchema(db: SQLiteDatabase) {
        SCHEMA.forEach(db::execSQL)
        db.execSQL("INSERT INTO reader_defaults(scope,theme,flow,prefs_revision) VALUES('app','white','paginated',0)")
        db.execSQL("INSERT INTO reader_defaults(scope,font_size,line_height,margin,font_family,columns,epub_style_mode,prefs_revision) VALUES('txt',16,1.6,'M','default','auto','AUTHOR',0)")
        db.execSQL("INSERT INTO reader_defaults(scope,font_size,line_height,margin,font_family,columns,epub_style_mode,prefs_revision) VALUES('epub',16,1.6,'M','default','auto','AUTHOR',0)")
    }
    /** SQLiteOpenHelper already owns this transaction, including user_version. No FK disabling. */
    private fun migrate(db: SQLiteDatabase, version: Int) {
        fun columns(table: String): Set<String> = db.rawQuery("PRAGMA table_info($table)",null).use { c -> buildSet { while(c.moveToNext()) add(c.getString(1)) } }
        val bookColumns = columns("books")
        require(bookColumns.containsAll(setOf("id","title","author","hash","bytes","imported","last_read","encoding","version","paragraphs","characters","current_offset","current_paragraph","current_character","furthest","font_size","theme"))) { "旧书库字段不完整，原库已保留" }
        val markColumns = columns("bookmarks")
        require(markColumns.containsAll(setOf("id","book_id","version","source_offset","paragraph","character","excerpt","created"))) { "旧书签字段不完整，原库已保留" }
        require(version != 3 || "flow" in bookColumns) { "旧书库阅读模式字段缺失" }
        fun sequence(table: String): Long = if (!LibraryBootstrap.tableExists(db,"sqlite_sequence")) 0 else db.rawQuery("SELECT seq FROM sqlite_sequence WHERE name=?",arrayOf(table)).use { if(it.moveToFirst()) it.getLong(0) else 0 }
        val markSequence = sequence("bookmarks"); val backupSequence = sequence("locator_backups")
        db.execSQL("ALTER TABLE bookmarks RENAME TO legacy_bookmarks")
        val hasBackups = LibraryBootstrap.tableExists(db,"locator_backups")
        if (hasBackups) db.execSQL("ALTER TABLE locator_backups RENAME TO legacy_locator_backups")
        createSchema(db)
        var copied = 0L
        db.rawQuery("SELECT * FROM books",null).use { c -> while(c.moveToNext()) {
            val id = c.string("id"); val revision = c.string("version"); val chars = c.long("characters")
            val fraction = if ("current_fraction" in bookColumns) c.double("current_fraction") else if(chars > 0) (c.long("current_offset").toDouble()/chars).coerceIn(0.0,1.0) else 0.0
            val anchor = Locator.Text(revision,c.long("current_offset"),c.int("current_paragraph"),c.int("current_character"),fraction)
            val extent = TxtReadingExtent(revision,c.long("furthest"))
            insertContent(db,ContentRecord(id,ContentFormat.TXT,revision,c.string("title"),c.string("author"),c.string("hash"),c.long("bytes"),c.long("imported"),c.long("last_read")),TxtMetadata(c.string("encoding"),c.int("paragraphs"),chars),anchor,extent,legacy = true)
            db.update("reading_settings",ContentValues().apply { put("font_size",c.double("font_size")); put("theme",c.string("theme")); put("flow",if("flow" in bookColumns) c.string("flow") else "scrolled") },"content_id=?",arrayOf(id))
            copied++;migrationCheckpoint("row")
        } }
        db.rawQuery("SELECT * FROM legacy_bookmarks",null).use { c -> while(c.moveToNext()) {
            val id = c.string("book_id")
            val fraction = if("fallback_fraction" in markColumns) c.double("fallback_fraction") else db.rawQuery("SELECT characters FROM txt_meta WHERE content_id=?",arrayOf(id)).use { check(it.moveToFirst()); if(it.getLong(0)>0) (c.long("source_offset").toDouble()/it.getLong(0)).coerceIn(0.0,1.0) else 0.0 }
            val locator = Locator.Text(c.string("version"),c.long("source_offset"),c.int("paragraph"),c.int("character"),fraction)
            db.insertOrThrow("bookmarks",null,ContentValues().apply { put("id",c.long("id"));put("content_id",id);put("locator_json",StoredLocatorCodec.encodeLegacyTxt(locator));put("locator_key",LocatorCodec.key(locator));put("fallback_fraction",fraction);put("excerpt",c.string("excerpt"));put("created",c.long("created")) })
        } }
        if(hasBackups) db.execSQL("INSERT INTO locator_backups(id,content_id,version,payload,payload_format,created) SELECT id,book_id,version,payload,'legacy-v3-text',created FROM legacy_locator_backups")
        fun count(table: String) = db.rawQuery("SELECT COUNT(*) FROM $table",null).use { check(it.moveToFirst());it.getLong(0) }
        check(copied == count("contents") && count("legacy_bookmarks") == count("bookmarks"))
        if(hasBackups) { check(count("legacy_locator_backups") == count("locator_backups")); db.execSQL("DROP TABLE legacy_locator_backups") }
        migrationCheckpoint("before-swap")
        db.execSQL("DROP TABLE legacy_bookmarks");db.execSQL("DROP TABLE books")
        listOf("bookmarks" to markSequence,"locator_backups" to backupSequence).forEach { (table,high) ->
            db.execSQL("UPDATE sqlite_sequence SET seq=MAX(seq,?) WHERE name=?",arrayOf<Any>(high,table))
            if(high > 0 && db.rawQuery("SELECT 1 FROM sqlite_sequence WHERE name=?",arrayOf(table)).use { !it.moveToFirst() }) db.execSQL("INSERT INTO sqlite_sequence(name,seq) VALUES(?,?)",arrayOf<Any>(table,high))
        }
        db.rawQuery("PRAGMA foreign_key_check",null).use { check(!it.moveToFirst()) }
    }
    fun content(id: String): ContentRecord? = readableDatabase.rawQuery("SELECT * FROM contents WHERE id=?",arrayOf(id)).use { if(it.moveToFirst()) it.content() else null }
    fun txtMetadata(id: String): TxtMetadata? = readableDatabase.rawQuery("SELECT * FROM txt_meta WHERE content_id=?",arrayOf(id)).use { if(it.moveToFirst()) TxtMetadata(it.string("encoding"),it.int("paragraphs"),it.long("characters")) else null }
    fun contents(): List<ContentSummary> = readableDatabase.rawQuery("SELECT c.*,p.fraction,p.furthest_fraction,t.encoding FROM contents c JOIN positions p ON p.content_id=c.id LEFT JOIN txt_meta t ON t.content_id=c.id",null).use { c -> buildList { while(c.moveToNext()) add(ContentSummary(c.string("id"),ContentFormat.valueOf(c.string("format").uppercase()),c.string("title"),c.string("author"),c.long("bytes"),c.long("imported_at"),c.long("last_read_at"),c.double("fraction"),c.double("furthest_fraction"),c.nullString("encoding"))) } }
    fun byHash(hash: String): ContentRecord? = readableDatabase.rawQuery("SELECT * FROM contents WHERE hash=?",arrayOf(hash)).use { if(it.moveToFirst()) it.content() else null }
    fun retainedVersions(id: String): Set<String> = readableDatabase.rawQuery("SELECT version FROM locator_backups WHERE content_id=?",arrayOf(id)).use { c -> buildSet { while(c.moveToNext()) add(c.getString(0)) } }
    /** Explicit TXT projection for the encoding/import pipeline; general consumers use content/contents. */
    fun txt(id: String): TxtRecord? = transaction { db ->
        val record = content(id) ?: return@transaction null
        require(record.format == ContentFormat.TXT)
        val meta = requireNotNull(txtMetadata(id)); val rules = SqliteReadingStore(this).effectiveRules(id).rules
        db.rawQuery("SELECT * FROM positions WHERE content_id=?",arrayOf(id)).use { c -> check(c.moveToFirst())
            val locator = StoredLocatorCodec.decode(c.string("locator_json")); val extent = ReadingExtentCodec.decode(c.string("furthest_json"))
            require(locator is Locator.Text && extent is TxtReadingExtent)
            TxtRecord(id,record.title,record.author,record.hash,record.bytes,record.imported,record.lastRead,meta.encoding,record.revision,meta.paragraphs,meta.characters,locator,extent.sourceOffset,rules.fontSize,rules.theme,rules.flow.wire)
        }
    }
    fun insert(record: TxtRecord, originalName: String? = null, inheritDefaults: Boolean = true) = transaction { db ->
        insertContent(db,ContentRecord(record.id,ContentFormat.TXT,record.version,record.title,record.author,record.hash,record.bytes,record.imported,record.lastRead,originalName=originalName),TxtMetadata(record.encoding,record.paragraphs,record.characters),record.current,TxtReadingExtent(record.version,record.furthest))
        if(!inheritDefaults) db.update("reading_settings",ContentValues().apply { put("font_size",record.fontSize);put("theme",record.theme);put("flow",record.flow) },"content_id=?",arrayOf(record.id))
    }
    private fun insertContent(db: SQLiteDatabase, record: ContentRecord, meta: TxtMetadata, current: Locator.Text, extent: TxtReadingExtent, legacy: Boolean = false) {
        db.insertOrThrow("contents",null,ContentValues().apply { put("id",record.id);put("format",record.format.name.lowercase());put("revision",record.revision);put("title",record.title);put("author",record.author);put("hash",record.hash);put("bytes",record.bytes);put("imported_at",record.imported);put("last_read_at",record.lastRead);put("original_name",record.originalName) })
        db.insertOrThrow("txt_meta",null,ContentValues().apply { put("content_id",record.id);put("encoding",meta.encoding);put("paragraphs",meta.paragraphs);put("characters",meta.characters) })
        db.insertOrThrow("positions",null,positionValues(current,extent,meta.characters,legacy).apply { put("content_id",record.id);put("updated_at",record.lastRead) })
        db.insertOrThrow("reading_settings",null,ContentValues().apply { put("content_id",record.id) })
    }
    internal fun positionValues(current: Locator.Text, extent: TxtReadingExtent, chars: Long, legacy: Boolean = false) = ContentValues().apply {
        put("locator_json",if(legacy) StoredLocatorCodec.encodeLegacyTxt(current) else LocatorCodec.encode(current));put("furthest_json",ReadingExtentCodec.encode(extent))
        put("fraction",(current.sourceOffset.toDouble()/(chars-1).coerceAtLeast(1)).coerceIn(0.0,1.0));put("furthest_fraction",(extent.sourceOffset.toDouble()/(chars-1).coerceAtLeast(1)).coerceIn(0.0,1.0))
    }
    fun delete(id: String) = transaction { it.delete("contents","id=?",arrayOf(id)) }
    fun setMetadata(id: String,title: String,author: String) { writableDatabase.update("contents",ContentValues().apply { put("title",title);put("author",author) },"id=?",arrayOf(id)) }
    fun savePosition(id: String,locator: Locator.Text,furthest: Long = locator.sourceOffset,time: Long = System.currentTimeMillis()) = transaction { db ->
        val meta = requireNotNull(txtMetadata(id)); check(content(id)?.revision == locator.version)
        val old = txt(id) ?: error("书籍不存在")
        check(db.update("positions",positionValues(locator,TxtReadingExtent(locator.version,maxOf(old.furthest,furthest)),meta.characters).apply { put("updated_at",time) },"content_id=?",arrayOf(id)) == 1)
        db.update("contents",ContentValues().apply { put("last_read_at",time) },"id=?",arrayOf(id));Unit
    }
    fun setStyle(id: String,font: Float,theme: String) { SqliteReadingStore(this).patchRules(id,requireNotNull(content(id)).revision,ContentFormat.TXT,RulesScope.BOOK,RulesPatch(fontSize=FieldUpdate.Set(font),theme=FieldUpdate.Set(theme))) }
    fun setFlow(id: String,flow: String) { SqliteReadingStore(this).patchRules(id,requireNotNull(content(id)).revision,ContentFormat.TXT,RulesScope.BOOK,RulesPatch(flow=FieldUpdate.Set(ReaderFlow.entries.first { it.wire == flow }))) }
    fun bookmarks(id: String): List<Bookmark> = readableDatabase.rawQuery("SELECT * FROM bookmarks WHERE content_id=? ORDER BY created DESC,id DESC",arrayOf(id)).use { c -> buildList { while(c.moveToNext()) add(Bookmark(c.long("id"),id,StoredLocatorCodec.decode(c.string("locator_json")),c.string("excerpt"),c.long("created"))) } }
    fun addBookmark(id: String,locator: Locator,excerpt: String): Boolean = writableDatabase.insertWithOnConflict("bookmarks",null,ContentValues().apply { put("content_id",id);put("locator_json",LocatorCodec.encode(locator));put("locator_key",LocatorCodec.key(locator));put("fallback_fraction",if(locator is Locator.Text) locator.fallbackFraction else 0.0);put("excerpt",LocatorCodec.safePrefix(excerpt,120));put("created",System.currentTimeMillis()) },SQLiteDatabase.CONFLICT_IGNORE) != -1L
    fun deleteBookmark(id: Long,contentId: String) { writableDatabase.delete("bookmarks","id=? AND content_id=?",arrayOf(id.toString(),contentId)) }
    fun reindex(id: String,encoding: String,version: String,paragraphs: Int,characters: Long) = transaction { db ->
        val old = requireNotNull(txt(id)); val anchors = bookmarks(id)
        val backup = buildString { append("current=${old.current.sourceOffset},${old.current.paragraph},${old.current.character},${old.current.fallbackFraction};furthest=${old.furthest}\n")
            anchors.forEach { val anchor = it.locator; if(anchor is Locator.Text) append("bookmark=${it.id},${anchor.version},${anchor.sourceOffset},${anchor.paragraph},${anchor.character},${anchor.fallbackFraction}\n") } }
        db.insertOrThrow("locator_backups",null,ContentValues().apply { put("content_id",id);put("version",old.version);put("payload",backup);put("payload_format","legacy-v3-text");put("created",System.currentTimeMillis()) })
        db.execSQL("DELETE FROM locator_backups WHERE content_id=? AND id NOT IN (SELECT id FROM locator_backups WHERE content_id=? ORDER BY id DESC LIMIT 3)",arrayOf(id,id))
        val last = (characters-1).coerceAtLeast(0);val scale = if(old.characters>0 && old.characters!=characters) characters.toDouble()/old.characters else 1.0
        fun remap(offset: Long) = (offset*scale).toLong().coerceIn(0,last)
        val offset = remap(old.current.sourceOffset)
        db.update("contents",ContentValues().apply { put("revision",version) },"id=?",arrayOf(id))
        db.update("txt_meta",ContentValues().apply { put("encoding",encoding);put("paragraphs",paragraphs);put("characters",characters) },"content_id=?",arrayOf(id))
        db.update("positions",positionValues(Locator.Text(version,offset,0,0,if(characters>0) offset.toDouble()/characters else 0.0),TxtReadingExtent(version,remap(old.furthest)),characters),"content_id=?",arrayOf(id));Unit
    }
    internal fun <T> transaction(action: (SQLiteDatabase) -> T): T {
        val db = writableDatabase;db.beginTransaction()
        try { return action(db).also { db.setTransactionSuccessful() } } finally { db.endTransaction() }
    }
    private fun Cursor.content() = ContentRecord(string("id"),ContentFormat.valueOf(string("format").uppercase()),string("revision"),string("title"),string("author"),string("hash"),long("bytes"),long("imported_at"),long("last_read_at"),nullString("language"),nullString("original_name"),nullString("cover_path"),if(isNull(getColumnIndexOrThrow("finished_at"))) null else long("finished_at"))
    internal companion object {
        val SCHEMA = listOf(
            "CREATE TABLE contents(id TEXT PRIMARY KEY,format TEXT NOT NULL CHECK(format IN ('txt','epub')),revision TEXT NOT NULL,title TEXT NOT NULL,author TEXT NOT NULL,hash TEXT NOT NULL UNIQUE,bytes INTEGER NOT NULL,imported_at INTEGER NOT NULL,last_read_at INTEGER NOT NULL DEFAULT 0,finished_at INTEGER,cover_path TEXT,language TEXT,original_name TEXT)",
            "CREATE TABLE txt_meta(content_id TEXT PRIMARY KEY REFERENCES contents(id) ON DELETE CASCADE,encoding TEXT NOT NULL,paragraphs INTEGER NOT NULL,characters INTEGER NOT NULL)",
            "CREATE TABLE epub_meta(content_id TEXT PRIMARY KEY REFERENCES contents(id) ON DELETE CASCADE,spine_count INTEGER NOT NULL,package_revision TEXT NOT NULL,sanitizer_version TEXT NOT NULL,layout TEXT NOT NULL,fxl_json TEXT)",
            "CREATE TABLE positions(content_id TEXT PRIMARY KEY REFERENCES contents(id) ON DELETE CASCADE,locator_json TEXT NOT NULL,fraction REAL NOT NULL,furthest_json TEXT NOT NULL,furthest_fraction REAL NOT NULL,updated_at INTEGER NOT NULL)",
            "CREATE TABLE bookmarks(id INTEGER PRIMARY KEY AUTOINCREMENT,content_id TEXT NOT NULL REFERENCES contents(id) ON DELETE CASCADE,locator_json TEXT NOT NULL,locator_key TEXT NOT NULL,fallback_fraction REAL NOT NULL,excerpt TEXT NOT NULL,chapter_title TEXT,created INTEGER NOT NULL,UNIQUE(content_id,locator_key))",
            "CREATE TABLE locator_backups(id INTEGER PRIMARY KEY AUTOINCREMENT,content_id TEXT NOT NULL REFERENCES contents(id) ON DELETE CASCADE,version TEXT NOT NULL,payload TEXT NOT NULL,payload_format TEXT NOT NULL,created INTEGER NOT NULL)",
            "CREATE TABLE reading_settings(content_id TEXT PRIMARY KEY REFERENCES contents(id) ON DELETE CASCADE,font_size REAL,theme TEXT,flow TEXT,line_height REAL,margin TEXT,font_family TEXT,columns TEXT,epub_style_mode TEXT,fxl_zoom TEXT,spread_pref TEXT)",
            "CREATE TABLE reader_defaults(scope TEXT PRIMARY KEY CHECK(scope IN ('app','txt','epub')),font_size REAL,theme TEXT,flow TEXT,line_height REAL,margin TEXT,font_family TEXT,columns TEXT,epub_style_mode TEXT,fxl_zoom TEXT,spread_pref TEXT,prefs_revision INTEGER NOT NULL DEFAULT 0)"
        )
        internal fun Cursor.string(name: String) = getString(getColumnIndexOrThrow(name))
        internal fun Cursor.nullString(name: String): String? = if(isNull(getColumnIndexOrThrow(name))) null else string(name)
        internal fun Cursor.long(name: String) = getLong(getColumnIndexOrThrow(name))
        internal fun Cursor.int(name: String) = getInt(getColumnIndexOrThrow(name))
        internal fun Cursor.double(name: String) = getDouble(getColumnIndexOrThrow(name))
    }
}
