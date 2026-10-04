package com.localreader.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.os.StatFs
import com.localreader.app.core.*
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

data class ImportResult(val name: String, val status: String, val bookId: String? = null)
class ImportCancelled : Exception("导入已取消")

class LibraryRepository(private val context: Context) {
    val db = LibraryDb(context)
    private val library = File(context.filesDir, "library").apply { mkdirs() }
    private val staging = File(context.filesDir, "import-staging").apply { mkdirs() }
    private val trash = File(context.filesDir, "trash").apply { mkdirs() }
    @Volatile private var recovered = false

    /** Run before accepting operations. Metadata is committed only after a complete private directory exists. */
    @Synchronized fun recover() {
        if (recovered) return // every ViewModel/Activity instance shares one repository; run once per process
        staging.listFiles()?.forEach { it.deleteRecursively() }
        // Directories the database does not know are moved to trash, never deleted outright: a rebuilt (empty)
        // database must not erase the user's books. Trash entries are named "<millis>-<id>" and purged after 7 days.
        val now = System.currentTimeMillis()
        trash.listFiles()?.filter { (it.name.substringBefore('-').toLongOrNull() ?: 0L) < now - TRASH_RETENTION_MS }?.forEach { it.deleteRecursively() }
        val books = db.books()
        val ids = books.map { it.id }.toSet()
        library.listFiles()?.filter { it.name !in ids }?.forEach { it.renameTo(File(trash, "$now-${it.name}")) }
        books.forEach { book ->
            val keep = db.retainedVersions(book.id) + book.version
            File(File(library, book.id), "versions").listFiles()?.filter { it.name !in keep }?.forEach { it.deleteRecursively() }
        }
        // Old index versions are retained as locator backups; active books are never auto-deleted.
        recovered = true
    }
    fun directory(book: Book) = File(File(library, book.id), "versions/${book.version}")
    fun engine(book: Book) = TxtEngine(directory(book), book.version)

    fun import(uri: Uri, cancelled: () -> Boolean = { false }): ImportResult {
        var name = "所选文件"
        val task = File(staging, UUID.randomUUID().toString()).apply { mkdirs() }
        var committed: File? = null
        try {
            var declaredSize: Long? = null
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val nameColumn = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameColumn >= 0) name = c.getString(nameColumn) ?: name
                    val sizeColumn = c.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeColumn >= 0 && !c.isNull(sizeColumn)) declaredSize = c.getLong(sizeColumn)
                }
            }
            val mime = context.contentResolver.getType(uri).orEmpty()
            val extension = name.substringAfterLast('.', "").lowercase()
            require(extension == "txt" || mime == "text/plain" || (extension.isEmpty() && mime.startsWith("text/"))) { "当前增量只支持 TXT；EPUB、MOBI、PDF 和漫画尚未实现" }
            require((declaredSize ?: 1) <= TxtParser.MAX_BYTES) { "文件超过 128 MB 限制" }
            val available = StatFs(staging.absolutePath).availableBytes
            // Original copy + UTF-8 text (<= ~1.5x) + index; pathological blank-line files are stopped by the paragraph cap
            // and by the streaming free-space checks, so a flat multiple is enough up front.
            val needed = (declaredSize?.coerceAtLeast(0) ?: 1024 * 1024) * 4 + 8 * 1024 * 1024
            require(available > needed) { "空间不足：预计需 ${needed / 1048576} MB，可用 ${available / 1048576} MB" }
            val original = File(task, "original.txt")
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(original).use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        if (cancelled()) throw ImportCancelled()
                        val count = input.read(buffer); if (count < 0) break
                        copied += count
                        require(copied <= TxtParser.MAX_BYTES) { "文件超过 128 MB 限制" }
                        if (copied % (1024 * 1024) < buffer.size) require(StatFs(staging.absolutePath).availableBytes > copied * 4 + 8 * 1024 * 1024) { "空间不足，导入已停止" }
                        digest.update(buffer, 0, count); output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            } ?: error("文件不可读取，请通过系统文件选择器重新选择")
            require(copied > 0) { "文件为空" }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            db.byHash(hash)?.let { return ImportResult(name, "重复内容，已保留原进度与书签", it.id) }
            val version = UUID.randomUUID().toString()
            val guard: () -> Unit = {
                if (cancelled()) throw ImportCancelled()
                require(StatFs(staging.absolutePath).availableBytes > 8 * 1024 * 1024) { "空间不足，导入已停止" }
            }
            guard()
            val detected = TxtParser.detect(original, guard)
            val parsed = TxtParser.build(original, File(task, "versions/$version"), detected, guard)
            if (cancelled()) throw ImportCancelled()
            val id = UUID.randomUUID().toString()
            val book = Book(id, name.substringBeforeLast('.', name), "作者未知", hash, copied, System.currentTimeMillis(), 0,
                parsed.encoding, version, parsed.paragraphs, parsed.totalCharacters, Locator.Text(version, 0, 0), 0, 16f, "white")
            val destination = File(library, id)
            check(task.renameTo(destination)) { "无法提交文件，请检查可用空间" }; committed = destination
            db.insert(book)
            committed = null
            return ImportResult(name, "导入成功 · ${parsed.encoding}", id)
        } catch (e: Exception) {
            val reason = when (e) {
                is ImportCancelled -> "已取消"
                is java.nio.charset.CharacterCodingException -> "文本编码无法识别；请先用文本编辑器另存为 UTF-8 后重试"
                else -> e.message?.take(180) ?: "导入失败，请重新选择文件"
            }
            return ImportResult(name, reason)
        } finally { task.deleteRecursively(); committed?.deleteRecursively() }
    }

    fun delete(book: Book) {
        // Delete metadata first: a failed file removal is an orphan cleaned on next startup, never a ghost book.
        db.delete(book.id)
        File(library, book.id).deleteRecursively()
    }
    fun reencode(book: Book, encoding: String) {
        val task = File(staging, UUID.randomUUID().toString()).apply { mkdirs() }
        val version = UUID.randomUUID().toString()
        var destination: File? = null
        try {
            val parsed = TxtParser.build(File(File(library, book.id), "original.txt"), task, encoding) {
                require(StatFs(staging.absolutePath).availableBytes > 8 * 1024 * 1024) { "空间不足，旧索引已保留" }
            }
            val target = File(File(library, book.id), "versions/$version")
            check(task.renameTo(target)) { "无法提交新索引" }; destination = target
            db.reindex(book.id, encoding, version, parsed.paragraphs, parsed.totalCharacters)
            destination = null
        } finally { task.deleteRecursively(); destination?.deleteRecursively() }
    }
    private companion object { const val TRASH_RETENTION_MS = 7L * 24 * 60 * 60 * 1000 }
}
