package com.localreader.app.data

import android.content.ContentValues
import com.localreader.app.core.*
import com.localreader.app.data.LibraryDb.Companion.string
import com.localreader.app.data.LibraryDb.Companion.long
import com.localreader.app.formats.txt.TxtReadingExtent
import com.localreader.app.formats.epub.*
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Real versioned storage; JSON is format-neutral and all attribution checks share the write transaction. */
class SqliteReadingStore(private val db: LibraryDb) : ReadingStore, ReadingPreferences {
    override val preferencesRevision = db.preferencesRevision.asStateFlow()
    override fun loadSnapshot(contentId: String): ReadingSnapshot = db.read { database ->
        val content=requireNotNull(db.content(contentId)) { "书籍不存在" }
        database.rawQuery("SELECT * FROM positions WHERE content_id=?",arrayOf(contentId)).use { c -> check(c.moveToFirst())
            val current=StoredLocatorCodec.decode(c.string("locator_json"));val extent=ReadingExtentCodec.decode(c.string("furthest_json"))
            // Old unread fixed books used reflow coordinates. Adapt the snapshot only;
            // existing position JSON is replaced only after a validated page event.
            val mixedCache=if(content.format==ContentFormat.EPUB && current !is Locator.Unknown && (extent is EpubReadingExtent || extent is FixedReadingExtent))db.epubCache(contentId).takeIf { it.mixed } else null
            val fixed=mixedCache==null && content.format==ContentFormat.EPUB && extent is EpubReadingExtent && database.rawQuery("SELECT layout FROM epub_meta WHERE content_id=?",arrayOf(contentId)).use { metadata -> metadata.moveToFirst() && metadata.getString(0)=="pre-paginated" }
            ReadingSnapshot(current,
                if(mixedCache!=null && extent is FixedReadingExtent) {
                    val section=mixedCache.sections.getOrNull(extent.page) ?: error("混排阅读范围损坏")
                    EpubReadingExtent(extent.revision,extent.page,(extent.y*section.characters).toInt())
                } else if(fixed)FixedReadingExtent(extent.revision,extent.spine,0.0,0.0) else extent,effectiveRules(contentId).rules,bookmarks(contentId))
        }
    }
    /** Candidate snapshot is prepared before the encoding version transaction commits. */
    internal fun candidateSnapshot(book: TxtRecord) = ReadingSnapshot(book.current,TxtReadingExtent(book.version,book.furthest),effectiveRules(book.id).rules,bookmarks(book.id))
    private fun checkCurrent(contentId: String,revision: String,locator: Locator) {
        val content = requireNotNull(db.content(contentId)) { "书籍不存在" }
        require(content.revision == revision && locator.version == revision && locator !is Locator.Unknown) { "定位不属于当前书籍版本" }
        when(content.format) {
            ContentFormat.TXT -> { val meta = requireNotNull(db.txtMetadata(contentId));require(locator is Locator.Text && locator.sourceOffset in 0..meta.characters && locator.paragraph in 0 until meta.paragraphs && locator.character >= 0) { "无效TXT定位" } }
            ContentFormat.EPUB -> {
                val cache=db.epubCache(contentId)
                if(locator is Locator.EpubResource) {
                    require(locator.returnTo is Locator.Epub || locator.returnTo is Locator.Page)
                    checkCurrent(contentId,revision,locator.returnTo)
                    val record=EpubAuxiliary.read(db.epubLogicalDirectory(contentId),revision,locator.docKey).second
                    val section=record.sections.single()
                    require(locator.offset in 0..section.characters && (locator.elementId==null || section.elements[locator.elementId]==locator.offset)) { "无效附属正文坐标" }
                } else if(cache.mixed) {
                    val fixedSpines=cache.fixedPages.filter { it.declaredLayout=="pre-paginated" }.map { cache.parts[it.index].spine }.toSet()
                    if(locator is Locator.Page)require(locator.page in fixedSpines && locator.x.isFinite() && locator.y.isFinite() && locator.x in 0.0..1.0 && locator.y in 0.0..1.0) { "无效混排固定页面坐标" }
                    else {
                        require(locator is Locator.Epub && locator.spine !in fixedSpines) { "无效混排正文定位" }
                        val section=cache.sections.getOrNull(locator.spine)
                        require(section!=null && locator.offset in 0..section.characters && (locator.elementId==null || section.elements[locator.elementId]==locator.offset)) { "无效混排正文坐标" }
                    }
                } else if(cache.layout=="pre-paginated") {
                    require(locator is Locator.Page && locator.page in cache.fixedPages.indices && locator.x.isFinite() && locator.y.isFinite() && locator.x in 0.0..1.0 && locator.y in 0.0..1.0) { "无效固定页面坐标" }
                } else {
                    require(locator is Locator.Epub) { "无效EPUB定位" }
                    val section=cache.sections.getOrNull(locator.spine)
                    require(section!=null && locator.offset in 0..section.characters && (locator.elementId==null || section.elements[locator.elementId]==locator.offset)) { "无效EPUB正文坐标" }
                }
            }
        }
    }
    override fun savePosition(contentId: String,revision: String,current: Locator,furthest: ReadingExtent) {
        db.transaction { database ->
            checkCurrent(contentId,revision,current)
            val storedPrevious=database.rawQuery("SELECT furthest_json FROM positions WHERE content_id=?",arrayOf(contentId)).use { c -> check(c.moveToFirst());ReadingExtentCodec.decode(c.string("furthest_json")) }
            val packageCache=if(current !is Locator.Text)db.epubCache(contentId) else null
            val mixed=packageCache?.takeIf { it.mixed }
            val previous=if(mixed!=null && storedPrevious is FixedReadingExtent) {
                val section=mixed.sections.getOrNull(storedPrevious.page) ?: error("混排阅读范围损坏")
                EpubReadingExtent(revision,storedPrevious.page,(storedPrevious.y*section.characters).toInt())
            } else if(packageCache?.layout=="pre-paginated" && !packageCache.mixed && storedPrevious is EpubReadingExtent)FixedReadingExtent(storedPrevious.revision,storedPrevious.spine,0.0,0.0) else storedPrevious
            val values=when(current) {
                is Locator.Text -> {
                    require(furthest is TxtReadingExtent && previous is TxtReadingExtent && furthest.revision==revision)
                    val meta=requireNotNull(db.txtMetadata(contentId));require(furthest.sourceOffset in 0..meta.characters)
                    db.positionValues(current,furthest.copy(sourceOffset=maxOf(previous.sourceOffset,furthest.sourceOffset)),meta.characters)
                }
                is Locator.EpubResource -> {
                    val cache=db.epubCache(contentId)
                    // The owner can still hold a valid main advance not yet committed
                    // when an auxiliary anchor replaces its debounce snapshot.
                    val extent: ReadingExtent
                    val extentFraction: Double
                    when {
                        furthest is EpubReadingExtent && previous is EpubReadingExtent && (cache.mixed || cache.layout!="pre-paginated") -> {
                            require(furthest.revision==revision && previous.revision==revision && furthest.spine in cache.sections.indices && furthest.offset in 0..cache.sections[furthest.spine].characters)
                            val merged=if(previous.spine>furthest.spine || previous.spine==furthest.spine && previous.offset>=furthest.offset)previous else furthest
                            extent=merged;extentFraction=EpubCoordinates.fraction(cache.sections,merged.spine,merged.offset)
                        }
                        furthest is FixedReadingExtent && previous is FixedReadingExtent && cache.layout=="pre-paginated" -> {
                            require(furthest.revision==revision && previous.revision==revision && furthest.page in cache.fixedPages.indices && furthest.x.isFinite() && furthest.y.isFinite() && furthest.x in 0.0..1.0 && furthest.y in 0.0..1.0)
                            val merged=if(previous.page>furthest.page || previous.page==furthest.page && previous.y>=furthest.y)previous else furthest
                            extent=merged;extentFraction=FixedCoordinates.fraction(cache.sections.size,merged.page,merged.y)
                        }
                        else -> error("附属正文的主文阅读范围无效")
                    }
                    val main=current.returnTo
                    val fraction=when(main) { is Locator.Epub -> EpubCoordinates.fraction(cache.sections,main.spine,main.offset);is Locator.Page -> if(cache.mixed)EpubCoordinates.fraction(cache.sections,main.page,(main.y*cache.sections[main.page].characters).toInt()) else FixedCoordinates.fraction(cache.sections.size,main.page,main.y);else -> error("无效主文返回位置") }
                    ContentValues().apply { put("locator_json",LocatorCodec.encode(current));put("fraction",fraction);put("furthest_json",ReadingExtentCodec.encode(extent));put("furthest_fraction",extentFraction) }
                }
                is Locator.Epub -> {
                    require(furthest is EpubReadingExtent && previous is EpubReadingExtent && furthest.revision==revision && previous.revision==revision)
                    val sections=db.epubCache(contentId).sections
                    require(furthest.spine in sections.indices && furthest.offset in 0..sections[furthest.spine].characters)
                    val extent=if(previous.spine>furthest.spine || previous.spine==furthest.spine && previous.offset>furthest.offset)previous else furthest
                    ContentValues().apply {
                        put("locator_json",LocatorCodec.encode(current));put("furthest_json",ReadingExtentCodec.encode(extent))
                        put("fraction",EpubCoordinates.fraction(sections,current.spine,current.offset));put("furthest_fraction",EpubCoordinates.fraction(sections,extent.spine,extent.offset))
                    }
                }
                is Locator.Page -> {
                    if(mixed!=null) {
                        val old=previous as? EpubReadingExtent ?: error("混排阅读范围格式错误")
                        require(furthest is EpubReadingExtent && furthest.revision==revision && old.revision==revision && furthest.spine in mixed.sections.indices && furthest.offset in 0..mixed.sections[furthest.spine].characters)
                        val extent=if(old.spine>furthest.spine || old.spine==furthest.spine && old.offset>=furthest.offset)old else furthest
                        ContentValues().apply {
                            put("locator_json",LocatorCodec.encode(current));put("furthest_json",ReadingExtentCodec.encode(extent))
                            put("fraction",EpubCoordinates.fraction(mixed.sections,current.page,(current.y*mixed.sections[current.page].characters).toInt()))
                            put("furthest_fraction",EpubCoordinates.fraction(mixed.sections,extent.spine,extent.offset))
                        }
                    } else {
                    require(furthest is FixedReadingExtent && previous is FixedReadingExtent && furthest.revision==revision && previous.revision==revision)
                    val count=db.epubCache(contentId).fixedPages.size
                    require(furthest.page in 0 until count && furthest.x.isFinite() && furthest.y.isFinite() && furthest.x in 0.0..1.0 && furthest.y in 0.0..1.0)
                    val extent=if(previous.page>furthest.page || previous.page==furthest.page && previous.y>=furthest.y)previous else furthest
                    ContentValues().apply { put("locator_json",LocatorCodec.encode(current));put("furthest_json",ReadingExtentCodec.encode(extent));put("fraction",FixedCoordinates.fraction(count,current.page,current.y));put("furthest_fraction",FixedCoordinates.fraction(count,extent.page,extent.y));put("updated_at",System.currentTimeMillis()) }
                    }
                }
                else -> error("此格式不可保存位置")
            }
            val time=System.currentTimeMillis();values.put("updated_at",time)
            check(database.update("positions",values,"content_id=?",arrayOf(contentId))==1)
        }
        // Reading saves about once a second; the shelf only needs progress occasionally
        // (and is refreshed explicitly when the book closes), so throttle the reload.
        val now=android.os.SystemClock.elapsedRealtime()
        if(now-lastShelfNotice>=SHELF_NOTICE_MS) { lastShelfNotice=now;db.notifyContentsChanged() }
    }
    @Volatile private var lastShelfNotice=0L
    override fun bookmarks(contentId: String) = db.bookmarks(contentId).map { ReaderBookmark(it.id,it.bookId,it.locator,it.excerpt,it.created) }
    override fun addBookmark(contentId: String,revision: String,locator: Locator,excerpt: String): Boolean = db.transaction {
        checkCurrent(contentId,revision,locator);db.addBookmark(contentId,locator,excerpt)
    }
    override fun deleteBookmark(contentId: String,id: Long): Unit = db.transaction { database ->
        check(database.delete("bookmarks","id=? AND content_id=?",arrayOf(id.toString(),contentId)) == 1) { "书签不属于该书" }
    }
    /** Reader panels hand over a book id and revision; app-wide fields still land in the shared row. */
    override fun patchRules(contentId: String,revision: String,patch: RulesPatch): ReaderRules {
        val content=requireNotNull(db.content(contentId)) { "书籍不存在" }
        require(content.revision==revision) { "定位不属于当前书籍版本" }
        return write(contentId,patch).rules
    }
    override fun patchRules(patch: RulesPatch): EffectiveRules {
        require(!patch.touchesBook) { "固定版式缩放属于单本书" }
        return write(null,patch)
    }
    override fun effectiveRules(contentId: String?): EffectiveRules = db.read { readRules(contentId) }
    /**
     * Typography, theme and flow come from the single app row. Installs that predate the shared
     * settings stored typography in the 'txt' format row, so it is the fallback before code defaults.
     * Only the fixed-layout view state is read from the book.
     */
    private fun readRules(contentId: String?): EffectiveRules {
        val database = db.readableDatabase
        val values = linkedMapOf<String,String>()
        var revision = 0L
        listOf("txt","app").forEach { scope ->
            database.rawQuery("SELECT * FROM reader_defaults WHERE scope=?",arrayOf(scope)).use { c -> check(c.moveToFirst());revision=maxOf(revision,c.long("prefs_revision"))
                APP_FIELDS.forEach { field -> val index=c.getColumnIndexOrThrow(field);if(!c.isNull(index))values[field]=c.getString(index) }
            }
        }
        if(contentId != null) database.rawQuery("SELECT fxl_zoom,spread_pref FROM reading_settings WHERE content_id=?",arrayOf(contentId)).use { c -> check(c.moveToFirst())
            BOOK_FIELDS.forEachIndexed { index,field -> if(!c.isNull(index))values[field]=c.getString(index) }
        }
        val rules=ReaderRules(fontSize=values["font_size"]?.toFloat() ?: 16f,theme=values["theme"] ?: "white",flow=ReaderFlow.entries.first { it.wire==(values["flow"] ?: "paginated") },
            epubStyleMode=values["epub_style_mode"] ?: "AUTHOR",lineHeight=values["line_height"]?.toDouble() ?: 1.4,margin=values["margin"] ?: "M",fontFamily=values["font_family"] ?: "default",
            columns=values["columns"] ?: "auto",fxlZoom=values["fxl_zoom"] ?: "fit-page",spreadPreference=values["spread_pref"] ?: "AUTO")
        return EffectiveRules(rules,revision)
    }
    private fun write(contentId: String?,patch: RulesPatch): EffectiveRules {
        val result = db.transaction { database ->
            val app = ContentValues();val book = ContentValues()
            listOf("font_size" to patch.fontSize,"theme" to patch.theme,"flow" to patch.flow,"line_height" to patch.lineHeight,"margin" to patch.margin,"font_family" to patch.fontFamily,"columns" to patch.columns,"epub_style_mode" to patch.epubStyleMode,"fxl_zoom" to patch.fxlZoom,"spread_pref" to patch.spreadPreference).forEach { (field,update) ->
                if(update is FieldUpdate.Set<*>) (if(field in BOOK_FIELDS)book else app).put(field,validate(field,update.value))
            }
            if(book.size()>0) { require(contentId != null) { "固定版式缩放属于单本书" };check(database.update("reading_settings",book,"content_id=?",arrayOf(contentId)) == 1) }
            if(app.size()>0) {
                check(database.update("reader_defaults",app,"scope='app'",null) == 1)
                val next = database.rawQuery("SELECT MAX(prefs_revision)+1 FROM reader_defaults",null).use { check(it.moveToFirst());it.getLong(0) }
                database.execSQL("UPDATE reader_defaults SET prefs_revision=?",arrayOf(next))
            }
            readRules(contentId)
        }
        db.preferencesRevision.update { maxOf(it,result.preferencesRevision) }
        return result
    }
    private fun validate(field: String,value: Any?): String = when(field) {
        "font_size" -> (value as Number).toFloat().also { require(it.isFinite() && it in 12f..36f) }.toString()
        "line_height" -> (value as Number).toDouble().also { require(it.isFinite() && it in 1.2..2.4) }.toString()
        "theme" -> (value as String).also { require(it in ReaderThemes.labels) }
        "flow" -> (value as ReaderFlow).wire
        "margin" -> (value as String).also { require(it in setOf("S","M","L")) }
        "font_family" -> (value as String).also { require(it in setOf("default","serif","sans","kai")) }
        "columns" -> (value as String).also { require(it in setOf("auto","one")) }
        "epub_style_mode" -> (value as String).also { require(it in setOf("AUTHOR","CUSTOM")) }
        "fxl_zoom" -> (value as String).also { require(it in setOf("fit-page","fit-width") || (it.toDoubleOrNull()?.let { n -> n.isFinite() && n in 1.0..5.0 } == true)) }
        "spread_pref" -> (value as String).also { require(it in setOf("AUTO","SINGLE")) }
        else -> error("未知偏好字段")
    }
    private companion object { const val SHELF_NOTICE_MS=10_000L;val APP_FIELDS = listOf("font_size","theme","flow","line_height","margin","font_family","columns","epub_style_mode");val BOOK_FIELDS = listOf("fxl_zoom","spread_pref") }
}
