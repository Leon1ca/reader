package com.localreader.app.data

import android.content.ContentValues
import com.localreader.app.core.*
import com.localreader.app.data.LibraryDb.Companion.string
import com.localreader.app.data.LibraryDb.Companion.long
import com.localreader.app.formats.txt.TxtReadingExtent
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Real schema4 storage; JSON is format-neutral and all attribution checks share the write transaction. */
class SqliteReadingStore(private val db: LibraryDb) : ReadingStore, ReadingPreferences {
    override val preferencesRevision = db.preferencesRevision.asStateFlow()
    override fun loadSnapshot(contentId: String): ReadingSnapshot = db.transaction { database ->
        requireNotNull(db.content(contentId)) { "书籍不存在" }
        database.rawQuery("SELECT * FROM positions WHERE content_id=?",arrayOf(contentId)).use { c -> check(c.moveToFirst())
            ReadingSnapshot(StoredLocatorCodec.decode(c.string("locator_json")),ReadingExtentCodec.decode(c.string("furthest_json")),effectiveRules(contentId).rules,bookmarks(contentId))
        }
    }
    /** Candidate snapshot is prepared before the encoding version transaction commits. */
    internal fun candidateSnapshot(book: TxtRecord) = ReadingSnapshot(book.current,TxtReadingExtent(book.version,book.furthest),effectiveRules(book.id).rules,bookmarks(book.id))
    private fun checkCurrent(contentId: String,revision: String,locator: Locator) {
        val content = requireNotNull(db.content(contentId)) { "书籍不存在" }
        require(content.revision == revision && locator.version == revision && locator !is Locator.Unknown) { "定位不属于当前书籍版本" }
        when(content.format) {
            ContentFormat.TXT -> { val meta = requireNotNull(db.txtMetadata(contentId));require(locator is Locator.Text && locator.sourceOffset in 0..meta.characters && locator.paragraph in 0 until meta.paragraphs && locator.character >= 0) { "无效TXT定位" } }
            ContentFormat.EPUB -> require(locator is Locator.Epub) { "无效EPUB定位" }
        }
    }
    override fun savePosition(contentId: String,revision: String,current: Locator,furthest: ReadingExtent): Unit = db.transaction { database ->
        checkCurrent(contentId,revision,current)
        require(current is Locator.Text && furthest is TxtReadingExtent && furthest.revision == revision) { "当前格式尚不支持位置写入" }
        val meta = requireNotNull(db.txtMetadata(contentId));require(furthest.sourceOffset in 0..meta.characters)
        val previous = loadSnapshot(contentId).furthest;require(previous is TxtReadingExtent)
        val time = System.currentTimeMillis()
        check(database.update("positions",db.positionValues(current,furthest.copy(sourceOffset=maxOf(previous.sourceOffset,furthest.sourceOffset)),meta.characters).apply { put("updated_at",time) },"content_id=?",arrayOf(contentId)) == 1)
        check(database.update("contents",ContentValues().apply { put("last_read_at",time) },"id=? AND revision=?",arrayOf(contentId,revision)) == 1)
    }
    override fun bookmarks(contentId: String) = db.bookmarks(contentId).map { ReaderBookmark(it.id,it.bookId,it.locator,it.excerpt,it.created) }
    override fun addBookmark(contentId: String,revision: String,locator: Locator,excerpt: String): Boolean = db.transaction {
        checkCurrent(contentId,revision,locator);db.addBookmark(contentId,locator,excerpt)
    }
    override fun deleteBookmark(contentId: String,id: Long): Unit = db.transaction { database ->
        check(database.delete("bookmarks","id=? AND content_id=?",arrayOf(id.toString(),contentId)) == 1) { "书签不属于该书" }
    }
    override fun patchRules(contentId: String,revision: String,patch: RulesPatch): ReaderRules =
        patchRules(contentId,revision,requireNotNull(db.content(contentId)).format,RulesScope.BOOK,patch).rules
    override fun effectiveRules(contentId: String): EffectiveRules = db.transaction { readRules(contentId,requireNotNull(db.content(contentId)).format) }
    override fun defaults(format: ContentFormat): EffectiveRules = db.transaction { readRules(null,format) }
    private fun readRules(contentId: String?,format: ContentFormat): EffectiveRules {
        val database = db.readableDatabase
        val values = linkedMapOf<String,Any>();val origins = linkedMapOf<String,RuleOrigin>()
        var revision = 0L
        listOf("app" to RuleOrigin.APP,format.name.lowercase() to RuleOrigin.FORMAT).forEach { (scope,origin) ->
            database.rawQuery("SELECT * FROM reader_defaults WHERE scope=?",arrayOf(scope)).use { c -> check(c.moveToFirst());revision=maxOf(revision,c.long("prefs_revision"))
                FIELDS.forEach { field -> val index=c.getColumnIndexOrThrow(field);if(!c.isNull(index)) { values[field]=c.getString(index);origins[field]=origin } }
            }
        }
        if(contentId != null) database.rawQuery("SELECT * FROM reading_settings WHERE content_id=?",arrayOf(contentId)).use { c -> check(c.moveToFirst())
            FIELDS.forEach { field -> val index=c.getColumnIndexOrThrow(field);if(!c.isNull(index)) { values[field]=c.getString(index);origins[field]=RuleOrigin.BOOK } }
        }
        return EffectiveRules(ReaderRules(fontSize=values.getValue("font_size").toString().toFloat(),theme=values.getValue("theme").toString(),flow=ReaderFlow.entries.first { it.wire == values.getValue("flow") }),origins,revision,values.getValue("line_height").toString().toDouble(),values.getValue("margin").toString(),values.getValue("font_family").toString(),values.getValue("columns").toString(),values.getValue("epub_style_mode").toString(),values["fxl_zoom"]?.toString() ?: "fit-page",values["spread_pref"]?.toString() ?: "AUTO")
    }
    override fun patchRules(contentId: String?,revision: String?,format: ContentFormat,scope: RulesScope,patch: RulesPatch): EffectiveRules {
        val result = db.transaction { database ->
            if(scope == RulesScope.BOOK) { require(contentId != null && db.content(contentId)?.revision == revision); require(db.content(contentId)?.format == format) }
            val updates = listOf("font_size" to patch.fontSize,"theme" to patch.theme,"flow" to patch.flow,"line_height" to patch.lineHeight,"margin" to patch.margin,"font_family" to patch.fontFamily,"columns" to patch.columns,"epub_style_mode" to patch.epubStyleMode,"fxl_zoom" to patch.fxlZoom,"spread_pref" to patch.spreadPreference)
            var changed = false
            updates.forEach { (field,update) -> if(update != FieldUpdate.Keep) {
                require(scope == RulesScope.BOOK || update != FieldUpdate.Clear) { "全局默认不能清空" }
                val value: String? = when(update) { FieldUpdate.Clear -> null;FieldUpdate.Keep -> error("unreachable");is FieldUpdate.Set -> validate(field,update.value) }
                val row = ContentValues().apply { if(value == null) putNull(field) else put(field,value) }
                if(scope == RulesScope.BOOK) check(database.update("reading_settings",row,"content_id=?",arrayOf(contentId)) == 1)
                else { val target = if(field == "theme" || field == "flow") "app" else format.name.lowercase()
                    check(database.update("reader_defaults",row,"scope=?",arrayOf(target)) == 1)
                    if(field == "theme" || field == "flow") database.execSQL("UPDATE reading_settings SET $field=NULL")
                }
                changed=true
            } }
            if(changed) { val next = database.rawQuery("SELECT MAX(prefs_revision)+1 FROM reader_defaults",null).use { check(it.moveToFirst());it.getLong(0) }
                database.execSQL("UPDATE reader_defaults SET prefs_revision=?",arrayOf(next)) }
            readRules(if(scope == RulesScope.BOOK) contentId else null,format)
        }
        db.preferencesRevision.update { maxOf(it,result.preferencesRevision) }
        return result
    }
    override fun resetBookRules(contentId: String,revision: String): EffectiveRules = patchRules(contentId,revision,requireNotNull(db.content(contentId)).format,RulesScope.BOOK,
        RulesPatch(FieldUpdate.Clear,FieldUpdate.Clear,FieldUpdate.Clear,FieldUpdate.Clear,FieldUpdate.Clear,FieldUpdate.Clear,FieldUpdate.Clear,FieldUpdate.Clear,FieldUpdate.Clear,FieldUpdate.Clear))
    private fun validate(field: String,value: Any?): String = when(field) {
        "font_size" -> (value as Number).toFloat().also { require(it.isFinite() && it in 12f..36f) }.toString()
        "line_height" -> (value as Number).toDouble().also { require(it.isFinite() && it in 1.2..2.4) }.toString()
        "theme" -> (value as String).also { require(it in setOf("white","paper","dark")) }
        "flow" -> (value as ReaderFlow).wire
        "margin" -> (value as String).also { require(it in setOf("S","M","L")) }
        "font_family" -> (value as String).also { require(it in setOf("default","serif","sans","kai")) }
        "columns" -> (value as String).also { require(it in setOf("auto","one")) }
        "epub_style_mode" -> (value as String).also { require(it in setOf("AUTHOR","CUSTOM")) }
        "fxl_zoom" -> (value as String).also { require(it in setOf("fit-page","fit-width") || (it.toDoubleOrNull()?.let { n -> n.isFinite() && n in 1.0..5.0 } == true)) }
        "spread_pref" -> (value as String).also { require(it in setOf("AUTO","SINGLE")) }
        else -> error("未知偏好字段")
    }
    private companion object { val FIELDS = listOf("font_size","theme","flow","line_height","margin","font_family","columns","epub_style_mode","fxl_zoom","spread_pref") }
}
