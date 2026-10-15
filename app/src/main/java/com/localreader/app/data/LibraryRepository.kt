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

/** One book's private directory and the single index revision that is still current. */
internal class BookFiles(val id: String,val directory: File,val revision: String)

class MissingBookFilesException : IllegalStateException("图书的应用内副本或索引缺失，请重新导入；阅读记录已保留")

class ImportCancelled : Exception("导入已取消")

class LibraryRepository(private val context: Context,private val deletionCheckpoint: (String)->Unit={}) : ContentRepository, CategoryStore {
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
        val versions = db.recoveryVersions() // bootstrap/upgrade must succeed before any recovery cleanup
        staging.listFiles()?.forEach { it.deleteRecursively() }
        File(context.cacheDir,"reader-search").listFiles()?.forEach { it.deleteRecursively() }
        // Directories the database does not know are moved to trash, never deleted outright: a rebuilt (empty)
        // database must not erase the user's books. Trash entries are named "<millis>-<id>" and purged after 7 days.
        val now = System.currentTimeMillis()
        val ids = versions.keys
        // A crash between filesystem retirement and DB deletion leaves a live row.
        // Restore it before retention cleanup; a committed deletion has no live ID.
        trash.listFiles()?.forEach { retired ->
            val id=retired.name.substringAfter('-',"")
            if(id in ids) {
                val installed=File(library,id)
                check(!installed.exists()) { "未提交的删除副本与现有目录冲突，两个副本均保留" }
                check(retired.renameTo(installed)) { "无法恢复尚未提交的删除，原件保留在回收目录" }
                syncDirectory(library);syncDirectory(trash)
            }
        }
        trash.listFiles()?.filter { (it.name.substringBefore('-').toLongOrNull() ?: 0L) < now - TRASH_RETENTION_MS }?.forEach { it.deleteRecursively() }
        library.listFiles()?.filter { it.name !in ids }?.forEach { it.renameTo(File(trash, "$now-${it.name}")) }

        // Old index versions are retained as locator backups; active books are never auto-deleted.
        recovered = true
    }
    /**
     * Frees only data the app can recreate: search caches, unfinished import staging, and index revisions no locator refers to.
     * Book copies, progress, bookmarks and the database are never touched. Returns the bytes freed.
     */
    fun cleanRebuildable(checkpoint: () -> Unit = {}): Long = mutate {
        var freed=0L
        fun size(file: File): Long = if(file.isFile)file.length() else file.listFiles().orEmpty().sumOf(::size)
        fun remove(file: File) { checkpoint();val bytes=size(file);if(file.deleteRecursively())freed+=bytes }
        File(context.cacheDir,"reader-search").listFiles()?.forEach(::remove)
        staging.listFiles()?.forEach(::remove)
        db.recoveryVersions().forEach { (id,keep) ->
            File(File(library,id),"versions").listFiles()?.filter { it.name !in keep }?.forEach(::remove)
        }
        freed
    }
    private fun rebuildTxtIndex(book: TxtRecord,original: File,directory: File) = mutate {
        if(directory.isDirectory)return@mutate
        val task=File(staging,UUID.randomUUID().toString()).apply { mkdirs() }
        try {
            val meta=requireNotNull(db.txtMetadata(book.id)) { "书籍记录不完整" }
            TxtParser.build(original,File(task,"index"),meta.encoding,{ },db.chapterRule(book.id))
            File(task,"index").renameTo(directory.also { it.parentFile?.mkdirs() })
            syncDirectory(requireNotNull(directory.parentFile))
        } finally { task.deleteRecursively() }
    }
    /** Rebuildable historical indexes are cleaned after the shelf becomes interactive. */
    fun cleanupRetiredVersions() = mutate {
        db.recoveryVersions().forEach { (id,keep) ->
            File(File(library,id),"versions").listFiles()?.filter { it.name !in keep }?.forEach { it.deleteRecursively() }
        }
    }
    override fun categories() = db.categories()
    override fun observeCategories() = db.contentsRevision.map { db.categories() }.distinctUntilChanged().flowOn(Dispatchers.IO)
    fun observeUserFonts() = db.contentsRevision.map { db.userFonts() }.distinctUntilChanged().flowOn(Dispatchers.IO)
    override fun createCategory(name: String) = db.createCategory(name)
    override fun renameCategory(id: Long,name: String) = db.renameCategory(id,name)
    override fun deleteCategory(id: Long) = db.deleteCategory(id)
    override fun reorderCategories(orderedIds: List<Long>) = db.reorderCategories(orderedIds)
    override fun setMembership(contentIds: Collection<String>,categoryId: Long,member: Boolean) = db.setMembership(contentIds,categoryId,member)
    override fun uncategorizedCount() = db.uncategorizedCount()
    /** Bookmarks across the given books, for the delete-confirmation summary. */
    fun bookmarkCount(ids: Set<String>): Int = db.bookmarkCount(ids)
    /** Tells shelf observers that reading progress was stored, so the shelf shows the final position. */
    fun notifyProgressStored() = db.notifyContentsChanged()
    /**
     * Holds the library still for a backup: imports, deletions and re-indexing wait, while the database is copied once and
     * the book files are listed under the same lock, so the package never mixes two states.
     */
    internal fun <T> stableForBackup(snapshot: File,work: (files: List<BookFiles>) -> T): T = mutate {
        recoverLocked()
        db.snapshotTo(snapshot)
        val books=db.contents().map { summary -> requireNotNull(db.content(summary.id)) }
        work(books.map { book -> BookFiles(book.id,File(library,book.id),book.revision) })
    }
    override fun observeAll() = db.contentsRevision.map { summaries() }.distinctUntilChanged().flowOn(Dispatchers.IO)
    override fun summaries() = db.contents().map { book ->
        if(File(File(library,book.id),if(book.format==ContentFormat.TXT)"original.txt" else "original.epub").isFile)book else book.copy(filesMissing=true)
    }
    override fun content(id: String) = db.content(id)
    override fun markRead(id: String,revision: String,time: Long) {
        db.transaction { database -> check(database.update("contents",android.content.ContentValues().apply {
            put("last_read_at",time)
        },"id=? AND revision=?",arrayOf(id,revision))==1) { "书籍版本已改变" } }
        db.notifyContentsChanged()
    }
    override fun setFinished(id: String,finished: Boolean) {
        db.transaction { database -> check(database.update("contents",android.content.ContentValues().apply {
            if(finished)put("finished_at",System.currentTimeMillis()) else putNull("finished_at")
        },"id=?",arrayOf(id))==1) { "书籍不存在" } };db.notifyContentsChanged()
    }
    fun directory(book: ContentRecord) = File(File(library,book.id),"versions/${book.revision}").also {
        if(!original(book).isFile || !it.isDirectory)throw MissingBookFilesException()
    }
    fun directory(book: TxtRecord) = File(File(library, book.id), "versions/${book.version}")
    internal fun currentEpubCache(book: ContentRecord,checkpoint: () -> Unit): Pair<File,EpubCacheRecord> = mutate {
        val directory=directory(book)
        val refreshed=EpubCache.refresh(File(directory.parentFile!!.parentFile,"original.epub"),directory,book.hash,checkpoint)
        db.replaceEpubCache(book.id,refreshed.second)
        refreshed
    }
    fun engine(book: TxtRecord): TxtEngine {
        val directory=directory(book)
        val original=File(File(library,book.id),"original.txt")
        if(!original.isFile)throw MissingBookFilesException()
        // The copy survived but its index did not (a cleanup after an interrupted operation): rebuild it under the same revision.
        if(!directory.isDirectory)rebuildTxtIndex(book,original,directory)
        if(!directory.isDirectory)throw MissingBookFilesException()
        return TxtEngine(directory,book.version)
    }

    /** The Android facade never retains permission or mutates the provider's original file. */
    fun importSource(uri: Uri,cancellation: ImportCancellation?=null): ImportSource {
        var name="所选文件";var size: Long?=null
        val signal=android.os.CancellationSignal()
        val release=cancellation?.register { signal.cancel() }
        return try {
            cancellation?.check()
            context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null,signal)?.use { c ->
                if(c.moveToFirst()) {
                    val n=c.getColumnIndex(OpenableColumns.DISPLAY_NAME);if(n>=0)name=c.getString(n) ?: name
                    val z=c.getColumnIndex(OpenableColumns.SIZE);if(z>=0 && !c.isNull(z))size=c.getLong(z)
                }
            }
            val mime=context.contentResolver.getType(uri).orEmpty();cancellation?.check()
            ImportSource(name,mime,size) {
                context.contentResolver.openInputStream(uri) ?: error("文件不可读取，请通过系统文件选择器重新选择")
            }
        } catch(_: SecurityException) { throw IllegalStateException(context.getString(com.localreader.app.R.string.import_permission_expired))
        } finally { release?.invoke() }
    }
    fun import(uri: Uri,cancelled: ()->Boolean={false}): ImportResult = try { import(importSource(uri),cancelled) }
        catch(error: Exception) { ImportResult("所选文件",error.message ?: "文件不可读取") }
    fun availableBytes()=StatFs(context.filesDir.absolutePath).availableBytes
    fun storageStats(checkpoint: ()->Unit={}): StorageStats=scanStorage(listOf(context.filesDir,context.cacheDir,context.getDatabasePath("library.db").parentFile!!),availableBytes(),{ mutationRevision.get()+2L*db.storageWriteRevision() },checkpoint,{ mutationRevision.get()%2L==0L && db.storageWritesIdle() })
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
            // A provider that states a positive length and then ends the stream early without an error would otherwise be
            // imported as if it were the whole file. Nothing is published; the user can pick the file again. An unknown or
            // non-positive length keeps working.
            source.declaredBytes?.takeIf { it>0 }?.let { declared -> require(copied>=declared) { "文件读取不完整：只读到 $copied 字节，文件应为 $declared 字节，请重新选择" } }
            val hash=digest.digest().joinToString("") { "%02x".format(it) }
            db.byHash(hash)?.let { existing ->
                // A record that survived a records-only restore has no copy: importing the same file links it again.
                if(original(existing).isFile || File(library,existing.id).exists()) return ImportResult(name,"重复内容，已保留原进度与书签",existing.id,ImportOutcome.DUPLICATE)
                return relink(existing,original,hash,task,cancelled).let { ImportResult(name,"已重新关联文件，阅读记录已保留",existing.id,ImportOutcome.OK) }
            }
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
            syncTreeDirectories(task)
            val id=UUID.randomUUID().toString()
            val destination=File(library,id)
            val result: String
            when(prepared) {
                is PreparedContent.Epub -> {
                    val cache=prepared.cache
                    guard();check(task.renameTo(destination)) { "无法提交文件，请检查可用空间" };committed=destination;syncDirectory(library);syncDirectory(staging)
                    val record=ContentRecord(id,ContentFormat.EPUB,cache.revision,cache.title,cache.author,hash,copied,System.currentTimeMillis(),0,cache.language,name)
                    db.insertEpub(record,cache)
                    result=if(cache.warnings.isEmpty())"导入成功 · EPUB" else "导入成功 · EPUB，${cache.warnings.size} 项资源已安全降级"
                }
                is PreparedContent.Text -> {
                    val version=prepared.revision;val parsed=prepared.index
                    guard()
                    val book=TxtRecord(id,name.substringBeforeLast('.',name),"作者未知",hash,copied,System.currentTimeMillis(),0,
                        parsed.encoding,version,parsed.paragraphs,parsed.totalCharacters,Locator.Text(version,0,0),0,16f,"white")
                    check(task.renameTo(destination)) { "无法提交文件，请检查可用空间" };committed=destination;syncDirectory(library);syncDirectory(staging)
                    db.insert(book,originalName=name)
                    result="导入成功 · ${parsed.encoding}"
                }
            }
            committed=null;db.notifyContentsChanged()
            return ImportResult(name,result,id,ImportOutcome.OK)
        } catch(e: Exception) {
            val reason=when(e) {
                is ImportCancelled -> "已取消"
                is java.nio.charset.CharacterCodingException -> "文本编码无法识别；请先用文本编辑器另存为 UTF-8 后重试"
                is SecurityException -> context.getString(com.localreader.app.R.string.import_permission_expired)
                else -> e.message?.take(180) ?: "导入失败，请重新选择文件"
            }
            return ImportResult(name,reason,outcome=if(cancelled() || e is ImportCancelled)ImportOutcome.CANCELED else ImportOutcome.FAILED)
        } finally { task.deleteRecursively();committed?.deleteRecursively() }
    }

    /**
     * Restores the private copy and derived index of a book whose records are already here (DATA-04). The file must hash to the
     * recorded content, so it cannot attach to the wrong book. Position and bookmarks are kept: an EPUB index is a pure function
     * of the file, and a TXT is rebuilt with the encoding and chapter rule the book already had.
     */
    private fun relink(book: ContentRecord,pending: File,hash: String,task: File,cancelled: () -> Boolean) {
        val guard: () -> Unit={
            if(cancelled())throw ImportCancelled()
            require(StatFs(staging.absolutePath).availableBytes>8*1024*1024) { "空间不足，导入已停止" }
        }
        val destination=File(library,book.id)
        val versions=File(task,"versions").apply { mkdirs() }
        if(book.format==ContentFormat.EPUB) {
            val renamed=File(task,"original.epub");check(pending.renameTo(renamed))
            val prepared=EpubImporter().prepare(ImportTask(renamed,versions,hash,guard))
            check(prepared.cache.revision==book.revision) { "这个版本的应用已更新索引格式，无法直接关联；请更新到记录所用的版本后重试" }
            syncTreeDirectories(task);guard()
            check(task.renameTo(destination)) { "无法提交文件，请检查可用空间" }
            syncDirectory(library);syncDirectory(staging)
            db.notifyContentsChanged()
        } else {
            val renamed=File(task,"original.txt");check(pending.renameTo(renamed))
            val meta=requireNotNull(db.txtMetadata(book.id)) { "书籍记录不完整" }
            val rule=db.chapterRule(book.id)
            // The index is a pure function of the file, encoding and rule, so it is rebuilt under the revision the records already
            // carry. Nothing in the database changes: one directory rename completes the link, and a crash leaves nothing half-done.
            TxtParser.build(renamed,File(versions,book.revision),meta.encoding,guard,rule)
            syncTreeDirectories(task);guard()
            check(task.renameTo(destination)) { "无法提交文件，请检查可用空间" }
            syncDirectory(library);syncDirectory(staging)
        }
    }

    fun delete(book: ContentSummary) { delete(requireNotNull(db.content(book.id))) }
    fun delete(book: TxtRecord) { delete(requireNotNull(db.content(book.id))) }
    override fun delete(book: ContentRecord) { deleteDetailed(book) }
    override fun deleteDetailed(book: ContentRecord): ContentDeletion = mutate {
        val source=File(library,book.id)
        val retired=File(trash,"${System.currentTimeMillis()}-${book.id}")
        // Move to a recoverable private trash capability before removing metadata.
        var moved=false
        try {
            if(source.exists()) {
                check(source.renameTo(retired)) { "无法移除应用内副本，图书仍保留" };moved=true
                deletionCheckpoint("retired")
                syncDirectory(library);syncDirectory(trash)
            }
            db.delete(book.id)
        } catch(error: Throwable) {
            if(moved && retired.exists())try { check(retired.renameTo(source)) { "删除失败，副本保留在回收目录" };syncDirectory(library);syncDirectory(trash) }
                catch(rollback: Throwable) { error.addSuppressed(rollback) }
            throw error
        }
        val removed=runCatching { deletionCheckpoint("committed");!retired.exists() || retired.deleteRecursively() }.getOrDefault(false)
        db.notifyContentsChanged()
        ContentDeletion(book.id,true,!removed,if(removed)null else "图书已删除，文件保留在回收目录等待清理")
    }
    fun reencode(book: TxtRecord, encoding: String) { reencodePrepared(book, encoding) { Unit } }
    /** Validate/open the new source before the database switches; failure keeps the previous version active. */
    fun <T> reencodePrepared(book: TxtRecord, encoding: String, checkpoint: () -> Unit = {}, rule: ChapterRule? = null, prepare: (TxtRecord) -> T): T = mutate { reencodeLocked(book,encoding,checkpoint,rule,prepare) }
    private fun <T> reencodeLocked(book: TxtRecord, encoding: String, checkpoint: () -> Unit, requestedRule: ChapterRule?, prepare: (TxtRecord) -> T): T {
        // A rebuild keeps the book's chosen chapter rule unless the caller picks another.
        val rule = requestedRule ?: db.chapterRule(book.id)
        val task = File(staging, UUID.randomUUID().toString()).apply { mkdirs() }
        val version = UUID.randomUUID().toString()
        var destination: File? = null
        try {
            val parsed = TxtParser.build(File(File(library, book.id), "original.txt"), task, encoding,{
                checkpoint()
                require(StatFs(staging.absolutePath).availableBytes > 8 * 1024 * 1024) { "空间不足，旧索引已保留" }
            },rule)
            syncTreeDirectories(task)
            val target = File(File(library, book.id), "versions/$version")
            check(task.renameTo(target)) { "无法提交新索引" }; destination = target
            syncDirectory(requireNotNull(target.parentFile));syncDirectory(staging)
            val fresh = requireNotNull(db.txt(book.id))
            check(fresh.version == book.version) { "书籍版本已改变；保留当前索引" }
            val last = (parsed.totalCharacters - 1).coerceAtLeast(0)
            val scale = if (fresh.characters > 0 && fresh.characters != parsed.totalCharacters) parsed.totalCharacters.toDouble() / fresh.characters else 1.0
            fun remap(offset: Long) = (offset * scale).toLong().coerceIn(0,last)
            val offset = remap(fresh.current.sourceOffset)
            val candidate = fresh.copy(encoding = encoding,version = version,paragraphs = parsed.paragraphs,characters = parsed.totalCharacters,
                current = Locator.Text(version,offset,0,0,offset.toDouble() / parsed.totalCharacters.coerceAtLeast(1)),furthest = remap(fresh.furthest))
            val prepared = prepare(candidate)
            db.reindex(book.id, encoding, version, parsed.paragraphs, parsed.totalCharacters, rule.encode())
            destination = null
            return prepared
        } finally { task.deleteRecursively(); destination?.deleteRecursively() }
    }
    private fun syncTreeDirectories(directory: File) {
        directory.listFiles()?.filter { it.isDirectory }?.forEach(::syncTreeDirectories)
        syncDirectory(directory)
    }
    private fun syncDirectory(directory: File) {
        val descriptor=android.system.Os.open(directory.path,android.system.OsConstants.O_RDONLY,0)
        try { android.system.Os.fsync(descriptor) } finally { android.system.Os.close(descriptor) }
    }
    private val mutationRevision=java.util.concurrent.atomic.AtomicLong()
    /** For collaborators whose files must not change while a backup holds the library still (fonts). */
    internal fun <T> exclusive(work: () -> T): T = mutate(work)
    private fun <T> mutate(work: () -> T): T = synchronized(lock) { mutationRevision.incrementAndGet();try { work() } finally { mutationRevision.incrementAndGet() } }
    private val lock = synchronized(locks) { locks.getOrPut(context.getDatabasePath("library.db").absolutePath) { Any() } }
    private companion object {
        const val TRASH_RETENTION_MS = 7L * 24 * 60 * 60 * 1000
        val locks = mutableMapOf<String,Any>()
    }
}
