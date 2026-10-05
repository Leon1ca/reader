package com.localreader.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.os.StatFs
import com.localreader.app.core.*
import com.localreader.app.formats.epub.*
import com.localreader.app.data.importer.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.distinctUntilChanged
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

class ImportCancelled : Exception("导入已取消")

class LibraryRepository(private val context: Context) : ContentRepository {
    val db = LibraryDb(context)
    private val library = File(context.filesDir, "library").apply { mkdirs() }
    private val staging = File(context.filesDir, "import-staging").apply { mkdirs() }
    private val trash = File(context.filesDir, "trash").apply { mkdirs() }
    @Volatile private var recovered = false

    internal fun original(book: ContentRecord) = File(File(library, book.id), if (book.format == ContentFormat.TXT) "original.txt" else "original.epub")
    internal val searchRoot: File get() = File(context.cacheDir, "reader-search").apply { check(mkdirs() || isDirectory) }

    /** Run before accepting operations. Metadata is committed only after a complete private directory exists. */
    fun recover() = mutate { recoverLocked() }
    private fun recoverLocked() {
        if (recovered) return // every ViewModel/Activity instance shares one repository; run once per process
        val books = db.contents() // bootstrap/upgrade must succeed before any recovery cleanup
        staging.listFiles()?.forEach { it.deleteRecursively() }
        // Directories the database does not know are moved to trash, never deleted outright: a rebuilt (empty)
        // database must not erase the user's books. Trash entries are named "<millis>-<id>" and purged after 7 days.
        val now = System.currentTimeMillis()
        trash.listFiles()?.filter { (it.name.substringBefore('-').toLongOrNull() ?: 0L) < now - TRASH_RETENTION_MS }?.forEach { it.deleteRecursively() }
        val ids = books.map { it.id }.toSet()
        library.listFiles()?.filter { it.name !in ids }?.forEach { it.renameTo(File(trash, "$now-${it.name}")) }
        books.forEach { book ->
            val keep = db.retainedVersions(book.id) + requireNotNull(db.content(book.id)).revision
            File(File(library, book.id), "versions").listFiles()?.filter { it.name !in keep }?.forEach { it.deleteRecursively() }
        }
        // Old index versions are retained as locator backups; active books are never auto-deleted.
        recovered = true
    }
    override fun observeAll() = db.contentsRevision.map { summaries() }.distinctUntilChanged().flowOn(Dispatchers.IO)
    override fun summaries() = db.contents()
    override fun content(id: String) = db.content(id)
    override fun updateMetadata(id: String,title: String,author: String) { db.setMetadata(id,title,author);db.notifyContentsChanged() }
    override fun markRead(id: String,revision: String,time: Long) {
        db.transaction { database -> check(database.update("contents",android.content.ContentValues().apply {
            put("last_read_at",time);put("hidden_from_continue",0)
        },"id=? AND revision=?",arrayOf(id,revision))==1) { "书籍版本已改变" } }
        db.notifyContentsChanged()
    }
    override fun setFinished(id: String,finished: Boolean) {
        db.transaction { database -> check(database.update("contents",android.content.ContentValues().apply {
            if(finished)put("finished_at",System.currentTimeMillis()) else putNull("finished_at")
        },"id=?",arrayOf(id))==1) { "书籍不存在" } };db.notifyContentsChanged()
    }
    override fun hideFromContinue(id: String,hidden: Boolean) {
        db.transaction { database -> check(database.update("contents",android.content.ContentValues().apply {
            put("hidden_from_continue",if(hidden)1 else 0)
        },"id=?",arrayOf(id))==1) { "书籍不存在" } };db.notifyContentsChanged()
    }
    override fun detail(id: String) = db.contentsRevision.map {
        db.transaction { database -> db.content(id)?.let { content ->
            val summary=db.contents().single { it.id==id }
            val chars=if(content.format==ContentFormat.TXT)db.txtMetadata(id)?.characters else database.rawQuery("SELECT canonical_characters FROM epub_meta WHERE content_id=?",arrayOf(id)).use { c -> if(c.moveToFirst() && !c.isNull(0))c.getLong(0) else null }
            val settings=database.rawQuery("SELECT * FROM reading_settings WHERE content_id=?",arrayOf(id)).use { c -> c.moveToFirst() && (1 until c.columnCount).any { !c.isNull(it) } }
            BookDetail(content,summary,chars,db.bookmarks(id).size,settings)
        } }
    }.distinctUntilChanged().flowOn(Dispatchers.IO)
    fun directory(book: ContentRecord) = File(File(library,book.id),"versions/${book.revision}")
    fun directory(book: TxtRecord) = File(File(library, book.id), "versions/${book.version}")
    internal fun currentEpubCache(book: ContentRecord,checkpoint: () -> Unit): Pair<File,EpubCacheRecord> = mutate {
        val directory=directory(book)
        val refreshed=EpubCache.refresh(File(directory.parentFile!!.parentFile,"original.epub"),directory,book.hash,checkpoint)
        db.replaceEpubCache(book.id,refreshed.second)
        refreshed
    }
    fun engine(book: TxtRecord) = TxtEngine(directory(book), book.version)

    /** The Android facade never retains permission or mutates the provider's original file. */
    fun import(uri: Uri,cancelled: () -> Boolean = { false }): ImportResult {
        var name="所选文件";var size: Long?=null
        return try {
            context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null)?.use { c ->
                if(c.moveToFirst()) {
                    val n=c.getColumnIndex(OpenableColumns.DISPLAY_NAME);if(n>=0)name=c.getString(n) ?: name
                    val z=c.getColumnIndex(OpenableColumns.SIZE);if(z>=0 && !c.isNull(z))size=c.getLong(z)
                }
            }
            import(ImportSource(name,context.contentResolver.getType(uri).orEmpty(),size) {
                context.contentResolver.openInputStream(uri) ?: error("文件不可读取，请通过系统文件选择器重新选择")
            },cancelled)
        } catch(e: Exception) { ImportResult(name,e.message?.take(180) ?: "文件不可读取") }
    }
    override fun import(source: ImportSource,cancelled: () -> Boolean): ImportResult = mutate { importLocked(source,cancelled) }
    private fun importLocked(source: ImportSource,cancelled: () -> Boolean): ImportResult {
        val name=source.name
        val task=File(staging,UUID.randomUUID().toString()).apply { mkdirs() }
        var committed: File?=null
        try {
            db.writableDatabase
            val extension=name.substringAfterLast('.',"").lowercase()
            val declaredEpub=extension=="epub" || source.mime=="application/epub+zip"
            val limit=EpubLimits.FILE_BYTES
            require((source.declaredBytes ?: 1)<=limit) { "文件超过 ${limit/1048576} MB 限制" }
            val available=StatFs(staging.absolutePath).availableBytes
            val needed=(source.declaredBytes?.coerceAtLeast(0) ?: 1024*1024)*4+8*1024*1024
            require(available>needed) { "空间不足：预计需 ${needed/1048576} MB，可用 ${available/1048576} MB" }
            var original=File(task,"original.pending")
            val digest=MessageDigest.getInstance("SHA-256");var copied=0L
            source.open().use { input -> FileOutputStream(original).use { output ->
                val buffer=ByteArray(65536)
                while(true) {
                    if(cancelled())throw ImportCancelled()
                    val count=input.read(buffer);if(count<0)break
                    copied+=count;require(copied<=limit) { "文件超过 ${limit/1048576} MB 限制" }
                    if(copied%(1024*1024)<buffer.size)require(StatFs(staging.absolutePath).availableBytes>copied*4+8*1024*1024) { "空间不足，导入已停止" }
                    digest.update(buffer,0,count);output.write(buffer,0,count)
                }
                output.fd.sync()
            } }
            require(copied>0) { "文件为空" }
            val hash=digest.digest().joinToString("") { "%02x".format(it) }
            db.byHash(hash)?.let { return ImportResult(name,"重复内容，已保留原进度与书签",it.id) }
            val guard: () -> Unit={
                if(cancelled())throw ImportCancelled()
                require(StatFs(staging.absolutePath).availableBytes>8*1024*1024) { "空间不足，导入已停止" }
            }
            guard()
            val header=original.inputStream().use { input -> ByteArray(4).also { bytes -> var count=0;while(count<bytes.size) { val read=input.read(bytes,count,bytes.size-count);if(read<0)break;count+=read } } }
            val epub=EpubImporter();val txt=TxtImporter()
            val importer=when {
                epub.accepts(name,source.mime,header) -> epub // magic wins over filename/MIME
                !declaredEpub && txt.accepts(name,source.mime,header) -> txt
                else -> error("请选择有效 TXT 或 EPUB 文件")
            }
            val renamed=File(task,if(importer is EpubImporter)"original.epub" else "original.txt")
            check(original.renameTo(renamed));original=renamed
            val prepared=importer.prepare(ImportTask(original,File(task,"versions"),hash,guard))
            val id=UUID.randomUUID().toString()
            val destination=File(library,id)
            val result: String
            when(prepared) {
                is PreparedContent.Epub -> {
                    val cache=prepared.cache
                    guard();check(task.renameTo(destination)) { "无法提交文件，请检查可用空间" };committed=destination
                    val record=ContentRecord(id,ContentFormat.EPUB,cache.revision,cache.title,cache.author,hash,copied,System.currentTimeMillis(),0,cache.language,name)
                    db.insertEpub(record,cache)
                    result=if(cache.warnings.isEmpty())"导入成功 · EPUB" else "导入成功 · EPUB，${cache.warnings.size} 项资源已安全降级"
                }
                is PreparedContent.Text -> {
                    val version=prepared.revision;val parsed=prepared.index
                    guard()
                    val book=TxtRecord(id,name.substringBeforeLast('.',name),"作者未知",hash,copied,System.currentTimeMillis(),0,
                        parsed.encoding,version,parsed.paragraphs,parsed.totalCharacters,Locator.Text(version,0,0),0,16f,"white")
                    check(task.renameTo(destination)) { "无法提交文件，请检查可用空间" };committed=destination
                    db.insert(book,originalName=name)
                    result="导入成功 · ${parsed.encoding}"
                }
            }
            committed=null;db.notifyContentsChanged()
            return ImportResult(name,result,id)
        } catch(e: Exception) {
            val reason=when(e) {
                is ImportCancelled -> "已取消"
                is java.nio.charset.CharacterCodingException -> "文本编码无法识别；请先用文本编辑器另存为 UTF-8 后重试"
                else -> e.message?.take(180) ?: "导入失败，请重新选择文件"
            }
            return ImportResult(name,reason)
        } finally { task.deleteRecursively();committed?.deleteRecursively() }
    }

    fun delete(book: ContentSummary) { delete(requireNotNull(db.content(book.id))) }
    fun delete(book: TxtRecord) { delete(requireNotNull(db.content(book.id))) }
    override fun delete(book: ContentRecord) = mutate { deleteLocked(book) }
    private fun deleteLocked(book: ContentRecord) {
        // Delete metadata first: a failed file removal is an orphan cleaned on next startup, never a ghost book.
        db.delete(book.id)
        File(library, book.id).deleteRecursively()
        db.notifyContentsChanged()
    }
    fun reencode(book: TxtRecord, encoding: String) { reencodePrepared(book, encoding) { Unit } }
    /** Validate/open the new source before the database switches; failure keeps the previous version active. */
    fun <T> reencodePrepared(book: TxtRecord, encoding: String, checkpoint: () -> Unit = {}, prepare: (TxtRecord) -> T): T = mutate { reencodeLocked(book,encoding,checkpoint,prepare) }
    private fun <T> reencodeLocked(book: TxtRecord, encoding: String, checkpoint: () -> Unit, prepare: (TxtRecord) -> T): T {
        val task = File(staging, UUID.randomUUID().toString()).apply { mkdirs() }
        val version = UUID.randomUUID().toString()
        var destination: File? = null
        try {
            val parsed = TxtParser.build(File(File(library, book.id), "original.txt"), task, encoding) {
                checkpoint()
                require(StatFs(staging.absolutePath).availableBytes > 8 * 1024 * 1024) { "空间不足，旧索引已保留" }
            }
            val target = File(File(library, book.id), "versions/$version")
            check(task.renameTo(target)) { "无法提交新索引" }; destination = target
            val fresh = requireNotNull(db.txt(book.id))
            check(fresh.version == book.version) { "书籍版本已改变；保留当前索引" }
            val last = (parsed.totalCharacters - 1).coerceAtLeast(0)
            val scale = if (fresh.characters > 0 && fresh.characters != parsed.totalCharacters) parsed.totalCharacters.toDouble() / fresh.characters else 1.0
            fun remap(offset: Long) = (offset * scale).toLong().coerceIn(0,last)
            val offset = remap(fresh.current.sourceOffset)
            val candidate = fresh.copy(encoding = encoding,version = version,paragraphs = parsed.paragraphs,characters = parsed.totalCharacters,
                current = Locator.Text(version,offset,0,0,offset.toDouble() / parsed.totalCharacters.coerceAtLeast(1)),furthest = remap(fresh.furthest))
            val prepared = prepare(candidate)
            db.reindex(book.id, encoding, version, parsed.paragraphs, parsed.totalCharacters)
            destination = null
            return prepared
        } finally { task.deleteRecursively(); destination?.deleteRecursively() }
    }
    private fun <T> mutate(work: () -> T): T = synchronized(lock) { work() }
    private val lock = synchronized(locks) { locks.getOrPut(context.getDatabasePath("library.db").absolutePath) { Any() } }
    private companion object {
        const val TRASH_RETENTION_MS = 7L * 24 * 60 * 60 * 1000
        val locks = mutableMapOf<String,Any>()
    }
}
