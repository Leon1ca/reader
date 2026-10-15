package com.localreader.app.core.backup

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** One file to place in a package: either bytes already in memory or a file that must not change while exporting. */
sealed class BackupSource(val path: String) {
    class Bytes(path: String,val bytes: ByteArray): BackupSource(path)
    class Disk(path: String,val file: File): BackupSource(path)
    fun size(): Long = when(this) { is Bytes -> bytes.size.toLong();is Disk -> file.length() }
    fun open(): InputStream = when(this) { is Bytes -> bytes.inputStream();is Disk -> file.inputStream() }
}

fun interface BackupProgress { fun report(doneBytes: Long,totalBytes: Long) }

object BackupWriter {
    private const val BUFFER = 64 * 1024

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Plans a package: validates paths and measures every source. Cheap; reads no content. */
    fun estimate(sources: List<BackupSource>): Long = sources.sumOf { it.size() }

    /**
     * Writes [sources] followed by nothing else; the manifest goes first. Content is hashed in a first pass and verified
     * again while writing, so a source that changed under the export fails it instead of producing a package that lies.
     */
    fun write(out: OutputStream,scope: BackupScope,schemaVersion: Int,appVersion: String,createdAt: Long,bookCount: Int,
              sources: List<BackupSource>,cancelled: () -> Boolean = { false },progress: BackupProgress = BackupProgress { _,_ -> }): BackupManifest {
        val seen = HashSet<String>()
        sources.forEach { BackupPaths.requireSafe(it.path);if(!seen.add(it.path.lowercase())) throw BackupRejected(BackupRejected.Kind.UNSAFE,"重复的备份路径") }
        val total = estimate(sources);var done = 0L
        val files = sources.map { source ->
            val digest = MessageDigest.getInstance("SHA-256");var size = 0L
            source.open().use { input -> val buffer = ByteArray(BUFFER);while(true) {
                if(cancelled()) throw BackupRejected(BackupRejected.Kind.CANCELLED,"备份已取消")
                val n = input.read(buffer);if(n < 0) break;digest.update(buffer,0,n);size += n;done += n;progress.report(done / 2,total)
            } }
            BackupFile(source.path,size,digest.digest().joinToString("") { "%02x".format(it) })
        }
        val manifest = BackupManifest(BackupFormat.VERSION,schemaVersion,appVersion,createdAt,scope,bookCount,files)
        val zip = ZipOutputStream(out).apply { setLevel(Deflater.BEST_SPEED) }
        zip.putNextEntry(ZipEntry(BackupFormat.MANIFEST));zip.write(BackupManifestCodec.encode(manifest));zip.closeEntry()
        var written = 0L
        sources.forEachIndexed { index,source ->
            val expected = files[index];val digest = MessageDigest.getInstance("SHA-256");var size = 0L
            zip.putNextEntry(ZipEntry(source.path))
            source.open().use { input -> val buffer = ByteArray(BUFFER);while(true) {
                if(cancelled()) throw BackupRejected(BackupRejected.Kind.CANCELLED,"备份已取消")
                val n = input.read(buffer);if(n < 0) break;digest.update(buffer,0,n);zip.write(buffer,0,n);size += n;written += n;progress.report(total / 2 + written / 2,total)
            } }
            zip.closeEntry()
            if(size != expected.size || digest.digest().joinToString("") { "%02x".format(it) } != expected.sha256)
                throw BackupRejected(BackupRejected.Kind.CORRUPT,"导出期间文件发生变化，请重试")
        }
        zip.finish();zip.flush()
        return manifest
    }

    internal fun hash(bytes: ByteArray) = sha(bytes)
}

/** What the user sees before deciding to replace the library; derived from the verified manifest only. */
data class BackupPreview(val manifest: BackupManifest,val hasPreferences: Boolean,val databaseUserVersion: Int)

object BackupReader {
    private const val BUFFER = 64 * 1024

    /**
     * Extracts [input] into [destination] (an empty task directory) and verifies it. Nothing outside the manifest is
     * written, sizes come from the manifest and are enforced while inflating, and every file is hashed.
     */
    fun extract(input: InputStream,destination: File,limits: BackupLimits = BackupLimits(),
                cancelled: () -> Boolean = { false },progress: BackupProgress = BackupProgress { _,_ -> },
                /** Called for every extracted file once complete; defaults to a real fsync. Must throw if the bytes cannot be made durable. */
                syncFile: (File) -> Unit = Durable::syncFile): BackupPreview {
        if(!destination.isDirectory || destination.list().orEmpty().isNotEmpty()) throw BackupRejected(BackupRejected.Kind.UNSAFE,"恢复目录必须为空")
        val zip = ZipInputStream(input)
        val first = try { zip.nextEntry } catch(_: Exception) { throw BackupRejected(BackupRejected.Kind.MALFORMED,"这不是有效的备份文件") }
        if(first == null || first.name != BackupFormat.MANIFEST) throw BackupRejected(BackupRejected.Kind.MALFORMED,"这不是有效的备份文件")
        val manifest = BackupManifestCodec.decode(readLimited(zip,limits.maxManifestBytes),limits)
        val byPath = manifest.files.associateBy { it.path }
        val extracted = HashSet<String>();var done = 0L;val total = manifest.totalBytes
        try {
            while(true) {
                if(cancelled()) throw BackupRejected(BackupRejected.Kind.CANCELLED,"恢复已取消")
                val entry = try { zip.nextEntry } catch(_: Exception) { throw BackupRejected(BackupRejected.Kind.CORRUPT,"备份文件已损坏或不完整") } ?: break
                if(entry.isDirectory) throw BackupRejected(BackupRejected.Kind.UNSAFE,"备份含目录项")
                val declared = byPath[entry.name] ?: throw BackupRejected(BackupRejected.Kind.UNSAFE,"备份含清单之外的文件")
                if(!extracted.add(entry.name)) throw BackupRejected(BackupRejected.Kind.UNSAFE,"备份含重复文件")
                val target = BackupPaths.resolve(destination,entry.name)
                target.parentFile!!.mkdirs()
                val digest = MessageDigest.getInstance("SHA-256");var size = 0L
                try {
                    target.outputStream().use { output -> val buffer = ByteArray(BUFFER);while(true) {
                        if(cancelled()) throw BackupRejected(BackupRejected.Kind.CANCELLED,"恢复已取消")
                        val n = try { zip.read(buffer) } catch(_: Exception) { throw BackupRejected(BackupRejected.Kind.CORRUPT,"备份文件已损坏或不完整") }
                        if(n < 0) break
                        size += n;if(size > declared.size) throw BackupRejected(BackupRejected.Kind.UNSAFE,"备份文件超出清单声明的大小")
                        digest.update(buffer,0,n);output.write(buffer,0,n);done += n;progress.report(done,total)
                    } }
                } catch(error: BackupRejected) { throw error }
                if(size != declared.size || digest.digest().joinToString("") { "%02x".format(it) } != declared.sha256) throw BackupRejected(BackupRejected.Kind.CORRUPT,"备份文件校验失败")
                syncFile(target) // a correct hash in memory says nothing about what survives a power loss
            }
            val missing = manifest.files.firstOrNull { it.path !in extracted }
            if(missing != null) throw BackupRejected(BackupRejected.Kind.CORRUPT,"备份文件不完整")
            if(BackupFormat.DATABASE !in byPath) throw BackupRejected(BackupRejected.Kind.MALFORMED,"备份缺少书库数据")
            val userVersion = databaseUserVersion(File(destination,BackupFormat.DATABASE))
            if(manifest.scope == BackupScope.RECORDS && manifest.files.any { it.path.startsWith(BackupFormat.FILES) || it.path.startsWith(BackupFormat.FONTS) }) throw BackupRejected(BackupRejected.Kind.MALFORMED,"仅记录备份不应含图书文件")
            return BackupPreview(manifest,BackupFormat.PREFERENCES in byPath,userVersion)
        } catch(error: Throwable) {
            destination.listFiles()?.forEach { it.deleteRecursively() }
            throw error
        }
    }

    private fun readLimited(input: InputStream,max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream();val buffer = ByteArray(8192)
        while(true) { val n = input.read(buffer);if(n < 0) break;out.write(buffer,0,n);if(out.size() > max) throw BackupRejected(BackupRejected.Kind.TOO_LARGE,"备份清单过大") }
        return out.toByteArray()
    }

    /** Reads SQLite's header without opening the file, so a hostile database never reaches the engine before it is judged. */
    fun databaseUserVersion(file: File): Int {
        val header = ByteArray(100)
        file.inputStream().use { input ->
            var read = 0
            while(read < header.size) { val n = input.read(header,read,header.size - read);if(n < 0) break;read += n }
            if(read < header.size) throw BackupRejected(BackupRejected.Kind.CORRUPT,"备份书库损坏")
        }
        if(String(header,0,16,Charsets.ISO_8859_1) != BackupFormat.SQLITE_MAGIC) throw BackupRejected(BackupRejected.Kind.CORRUPT,"备份书库不是有效的数据库")
        return java.nio.ByteBuffer.wrap(header,60,4).int
    }
}
