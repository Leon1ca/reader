package com.localreader.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.StatFs
import com.localreader.app.core.backup.*
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** Sizes the user is shown before choosing a backup, plus the space check. */
data class BackupEstimate(val fullBytes: Long,val recordsBytes: Long,val availableBytes: Long)

/** Everything the restore preview needs; derived from the verified, extracted package. */
data class RestorePreviewData(val taskId: String,val scope: BackupScope,val createdAt: Long,val formatVersion: Int,val appVersion: String,
    val books: Int,val bookmarks: Int,val bytes: Long,val hasPreferences: Boolean,val currentBooks: Int)

/** Android side of backup and restore: gathers state under the library's mutation lock and validates a staged package. */
class BackupService(private val context: Context,private val repository: LibraryRepository,private val coordinator: RestoreCoordinator) {
    companion object { const val SCHEMA_VERSION = 6;private const val RESERVE = 64L shl 20 }

    private val scratch get() = File(context.cacheDir,"backup-export").apply { mkdirs() }

    fun estimate(): BackupEstimate {
        val library = File(context.filesDir,"library")
        val full = repository.summaries().sumOf { summary -> directorySize(File(library,summary.id)) }
        val records = context.getDatabasePath("library.db").length() + preferencesJson().toByteArray().size
        return BackupEstimate(full + records,records,StatFs(context.filesDir.absolutePath).availableBytes)
    }
    private fun directorySize(file: File): Long = if(file.isFile) file.length() else file.listFiles().orEmpty().sumOf(::directorySize)

    /** Writes a backup to [output]. Blocks imports and deletions for the duration; cancellation is honoured between chunks. */
    fun export(scope: BackupScope,output: OutputStream,appVersion: String,cancelled: () -> Boolean,progress: BackupProgress): BackupManifest {
        val snapshot = File(scratch,"snapshot-${UUID.randomUUID()}.db")
        try {
            return repository.stableForBackup(snapshot) { books ->
                LibraryBootstrap.openForCheck(snapshot) { db -> LibraryBootstrap.checkIntegrity(db) }
                val sources = ArrayList<BackupSource>()
                sources.add(BackupSource.Disk(BackupFormat.DATABASE,snapshot))
                sources.add(BackupSource.Bytes(BackupFormat.PREFERENCES,preferencesJson().toByteArray(Charsets.UTF_8)))
                if(scope == BackupScope.FULL) {
                    books.forEach { book -> collectBook(book,sources) }
                    repository.db.userFonts().forEach { font -> File(context.filesDir,"user-fonts/${font.id}.${font.extension}").takeIf { it.isFile }?.let { sources.add(BackupSource.Disk("fonts/${it.name}",it)) } }
                }
                BackupWriter.write(output,scope,SCHEMA_VERSION,appVersion,System.currentTimeMillis(),books.size,sources,cancelled,progress)
            }
        } finally { snapshot.delete() }
    }
    private fun collectBook(book: BookFiles,into: MutableList<BackupSource>) {
        if(!book.directory.isDirectory) return // a book already missing its files is backed up as a record only
        fun walk(directory: File,relative: String) {
            directory.listFiles().orEmpty().sortedBy { it.name }.forEach { child ->
                val name = "$relative/${child.name}"
                // Older index revisions are rebuildable locator backups; only the current revision travels.
                if(relative == "files/${book.id}/versions" && child.name != book.revision) return@forEach
                if(child.isDirectory) walk(child,name) else if(child.isFile) into.add(BackupSource.Disk(name,child))
            }
        }
        walk(book.directory,"files/${book.id}")
    }

    fun preferencesJson(): String = coordinator.preferencesJson()

    /** Extracts and verifies [input] into a fresh restore task. The live library is untouched. */
    fun stage(input: InputStream,cancelled: () -> Boolean,progress: BackupProgress): RestorePreviewData {
        check(coordinator.swap.pendingTask() == null) { "已有未完成的恢复，请先重新启动应用" }
        val taskId = UUID.randomUUID().toString()
        val task = coordinator.layout.task(taskId);val stage = File(task,"stage").apply { mkdirs() }
        try {
            val available = StatFs(context.filesDir.absolutePath).availableBytes
            val limits = BackupLimits(maxTotalBytes = (available - RESERVE).coerceAtLeast(0))
            val preview = BackupReader.extract(input,stage,limits,cancelled,progress)
            val manifest = preview.manifest
            if(manifest.schemaVersion !in 1..SCHEMA_VERSION || preview.databaseUserVersion !in 1..SCHEMA_VERSION)
                throw BackupRejected(BackupRejected.Kind.UNSUPPORTED_VERSION,"备份来自更新版本的应用，当前版本无法恢复")
            val stats = inspectDatabase(File(stage,BackupFormat.DATABASE),preview.databaseUserVersion)
            // Judge the package the way the app will use it: really open and migrate a copy, and parse the settings.
            migrateStagedDatabase(stage)
            if(preview.hasPreferences) try { coordinator.validatePreferences(File(stage,BackupFormat.PREFERENCES).readText()) } catch(_: Exception) { throw BackupRejected(BackupRejected.Kind.CORRUPT,"备份中的设置无法读取") }
            if(manifest.scope == BackupScope.FULL) { verifyBookFiles(stage,stats.bookIds);verifyFonts(stage,stats.fontIds) }
            // Everything the restore will install (files, migrated database, settings, directory entries) must be on stable
            // storage before the journal can say STAGED; a failure here refuses the package while nothing has changed.
            coordinator.syncTree(task)
            coordinator.swap.stage(taskId,manifest.scope)
            return RestorePreviewData(taskId,manifest.scope,manifest.createdAt,manifest.formatVersion,manifest.appVersion,stats.bookIds.size,stats.bookmarks,
                manifest.totalBytes,preview.hasPreferences,repository.summaries().size)
        } catch(error: Throwable) { task.deleteRecursively();throw error }
    }
    /** The user agreed to replace the library; the switch happens at the next start. */
    fun confirmRestore(taskId: String) { coordinator.swap.confirm(taskId) }
    fun abortRestore(taskId: String): Boolean = coordinator.swap.abort(taskId)
    fun discard(taskId: String) { coordinator.layout.task(taskId).deleteRecursively() }

    /**
     * Opens a copy of the packaged database with the real [LibraryDb] (bootstrap, integrity, migration, foreign keys) and keeps the
     * result as the database to install. A package whose library cannot be loaded is refused now, while nothing has changed.
     */
    private fun migrateStagedDatabase(stage: File) {
        val work = File(stage,"validate").apply { mkdirs() }
        val databases = File(work,"databases").apply { mkdirs() }
        val copy = File(databases,"library.db")
        File(stage,BackupFormat.DATABASE).copyTo(copy)
        val sandbox = object: android.content.ContextWrapper(context) {
            override fun getFilesDir() = File(work,"files").apply { mkdirs() }
            override fun getDatabasePath(name: String) = File(databases,name)
            override fun openOrCreateDatabase(name: String,mode: Int,factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory)
            override fun openOrCreateDatabase(name: String,mode: Int,factory: SQLiteDatabase.CursorFactory?,handler: android.database.DatabaseErrorHandler?): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).absolutePath,factory,handler)
        }
        val library = LibraryDb(sandbox)
        try {
            val database = library.writableDatabase
            val required = listOf("contents","positions","bookmarks","reading_settings","reader_defaults","categories","content_categories","user_fonts")
            required.firstOrNull { !LibraryBootstrap.tableExists(database,it) }?.let { throw BackupRejected(BackupRejected.Kind.CORRUPT,"备份书库缺少必要的数据") }
            val scopes = database.rawQuery("SELECT scope FROM reader_defaults",null).use { c -> buildSet { while(c.moveToNext()) add(c.getString(0)) } }
            if(!scopes.containsAll(setOf("app","txt","epub"))) throw BackupRejected(BackupRejected.Kind.CORRUPT,"备份书库缺少阅读设置")
            database.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)",null).use { it.moveToFirst() }
        } catch(error: BackupRejected) { throw error
        } catch(error: Exception) { throw BackupRejected(BackupRejected.Kind.CORRUPT,"备份书库无法升级或加载") }
        finally { library.close() }
        val target = File(stage,BackupFormat.DATABASE)
        File(target.path + "-wal").delete();File(target.path + "-shm").delete()
        check(target.delete() && copy.renameTo(target)) { "无法保存校验后的书库" }
        work.deleteRecursively()
    }
    private class DatabaseStats(val bookIds: Set<String>,val bookmarks: Int,val fontIds: Set<String>)
    private fun inspectDatabase(file: File,userVersion: Int): DatabaseStats {
        return try {
            SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READONLY).use { db ->
                LibraryBootstrap.checkIntegrity(db)
                val table = if(userVersion >= 4) "contents" else "books"
                val ids = db.rawQuery("SELECT id FROM $table",null).use { c -> buildSet { while(c.moveToNext()) add(c.getString(0)) } }
                val marks = db.rawQuery("SELECT COUNT(*) FROM bookmarks",null).use { c -> if(c.moveToFirst()) c.getInt(0) else 0 }
                val fonts = if(userVersion >= 6) db.rawQuery("SELECT id FROM user_fonts",null).use { c -> buildSet { while(c.moveToNext()) add(c.getString(0)) } } else emptySet()
                DatabaseStats(ids,marks,fonts)
            }
        } catch(error: BackupRejected) { throw error
        } catch(error: Exception) { throw BackupRejected(BackupRejected.Kind.CORRUPT,"备份书库无法通过完整性校验") }
    }
    /** Every font in a package is judged again as if it were being imported; one bad file rejects the whole package. */
    private fun verifyFonts(stage: File,rows: Set<String>) {
        File(stage,"fonts").listFiles().orEmpty().forEach { file ->
            // A file with no row, or one that is not a font at all, makes the package untrustworthy.
            if(file.name.substringBefore('.') !in rows) throw BackupRejected(BackupRejected.Kind.CORRUPT,"备份中的字体文件与记录不一致")
            try { com.localreader.app.core.fonts.SfntInfo.inspect(file,file.name) }
            catch(error: com.localreader.app.core.fonts.FontRejected) { throw BackupRejected(BackupRejected.Kind.CORRUPT,"备份中的字体文件无效") }
        }
    }
    /** A full backup must contain exactly the books its records describe, each with its original. */
    private fun verifyBookFiles(stage: File,ids: Set<String>) {
        val present = File(stage,"files").list().orEmpty().toSet()
        if(present != ids.filter { id -> File(stage,"files/$id").isDirectory }.toSet()) throw BackupRejected(BackupRejected.Kind.CORRUPT,"备份中的图书文件与记录不一致")
        present.forEach { id ->
            if(!File(stage,"files/$id/original.txt").isFile && !File(stage,"files/$id/original.epub").isFile) throw BackupRejected(BackupRejected.Kind.CORRUPT,"备份缺少图书原件")
        }
    }
}
