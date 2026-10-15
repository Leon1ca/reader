package com.localreader.app.core.backup

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** Where the live library lives and where a restore task keeps its staging, retired content and journal. */
class RestoreLayout(val taskRoot: File,val liveLibrary: File,val liveDatabaseDirectory: File,val databaseName: String = "library.db",val liveFonts: File? = null,val liveTrash: File? = null) {
    /**
     * Everything a restore replaces, in the order it is moved aside. The recycle directory belongs to the library it came from:
     * its entries are named by book id, and carried into a different library they would collide with restored books.
     */
    val items: List<String> get() = listOf("library") + listOfNotNull("fonts".takeIf { liveFonts != null },"trash".takeIf { liveTrash != null }) + databaseFiles
    val databaseFiles: List<String> get() = listOf(databaseName,"$databaseName-wal","$databaseName-shm")
    fun task(id: String) = File(taskRoot,id)
}

enum class RestorePhase { STAGED, CONFIRMED, RETIRING, INSTALLING, PREFERENCES, ROLLING_BACK, COMMITTED, ROLLED_BACK }

data class RestoreOutcome(val applied: Boolean,val message: String?)

/**
 * A restore switches the library only at the next process start, before anything opens the database. Each step is a
 * rename that can be repeated, and the journal says which step is next, so a crash anywhere resumes forward or rolls
 * back to the previous library. [checkpoint] is called at every step boundary; tests use it to crash the process.
 */
class RestoreSwap(private val layout: RestoreLayout,
                  private val readPreferences: () -> String,private val applyPreferences: (String) -> Unit,
                  private val syncDirectory: (File) -> Unit = {},private val checkpoint: (String) -> Unit = {},
                  /** Puts the previous settings back during a rollback; defaults to the same writer. Must throw if it could not. */
                  private val restorePreferences: (String) -> Unit = applyPreferences) {
    private class Journal(var phase: RestorePhase,val scope: BackupScope,val existed: MutableSet<String>,var error: String?,val restored: MutableSet<String> = mutableSetOf())

    private fun journalFile(id: String) = File(layout.task(id),"journal.json")
    private fun stage(id: String) = File(layout.task(id),"stage")
    private fun previous(id: String) = File(layout.task(id),"previous")

    private fun write(id: String,journal: Journal) {
        val json = JSONObject().put("phase",journal.phase.name).put("scope",journal.scope.wire).put("existed",JSONArray(journal.existed.toList())).put("restored",JSONArray(journal.restored.toList())).apply { journal.error?.let { put("error",it) } }
        val target = journalFile(id);val temporary = File(target.path + ".part")
        FileOutputStream(temporary).use { it.write(json.toString().toByteArray());it.fd.sync() }
        if(!temporary.renameTo(target)) throw java.io.IOException("无法记录恢复进度")
        syncDirectory(target.parentFile!!)
    }
    private fun read(id: String): Journal? {
        val file = journalFile(id);if(!file.isFile) return null
        return runCatching {
            val json = JSONObject(file.readText())
            Journal(RestorePhase.valueOf(json.getString("phase")),BackupScope.fromWire(json.getString("scope"))!!,
                json.getJSONArray("existed").let { a -> (0 until a.length()).map(a::getString).toMutableSet() },json.optString("error").takeIf { it.isNotEmpty() },
                json.optJSONArray("restored")?.let { a -> (0 until a.length()).map(a::getString).toMutableSet() } ?: mutableSetOf())
        }.getOrNull()
    }

    /** Registers a verified, extracted package. [staged] must already contain `data/` and, for full backups, `files/`. */
    fun stage(id: String,scope: BackupScope) {
        check(pendingTask() == null) { "已有未完成的恢复任务" }
        check(File(layout.task(id),"stage").isDirectory)
        write(id,Journal(RestorePhase.STAGED,scope,mutableSetOf(),null))
    }
    /** The user confirmed the replacement; it will be applied at the next start. */
    fun confirm(id: String) {
        val journal = requireNotNull(read(id)) { "恢复任务不存在" }
        check(journal.phase == RestorePhase.STAGED) { "恢复任务状态不允许确认" }
        journal.phase = RestorePhase.CONFIRMED;write(id,journal)
    }
    /** Task directories in a state that still matters, oldest first. */
    fun tasks(): List<String> = layout.taskRoot.listFiles().orEmpty().filter { it.isDirectory }.map { it.name }.sorted()
    fun pendingTask(): String? = tasks().firstOrNull { id -> read(id)?.phase.let { it != null && it != RestorePhase.COMMITTED && it != RestorePhase.ROLLED_BACK } }
    fun phase(id: String): RestorePhase? = read(id)?.phase
    /**
     * True while a restore that has started is not safely finished: its journal says anything between CONFIRMED and ROLLING_BACK,
     * whatever the reason (a failed step, or a journal that could not even be written). The library must stay untouched then,
     * because the next start moves or deletes content by that journal. A COMMITTED task counts only when [committedToo]: the
     * restored library could not be loaded and putting the old one back failed.
     */
    fun unsafePending(committedToo: Boolean = false): Boolean = tasks().any { id ->
        when(read(id)?.phase) {
            RestorePhase.CONFIRMED, RestorePhase.RETIRING, RestorePhase.INSTALLING, RestorePhase.PREFERENCES, RestorePhase.ROLLING_BACK -> true
            RestorePhase.COMMITTED -> committedToo
            else -> false
        }
    }
    fun outcome(id: String): RestoreOutcome? = read(id)?.let { when(it.phase) { RestorePhase.COMMITTED -> RestoreOutcome(true,null);RestorePhase.ROLLED_BACK -> RestoreOutcome(false,it.error);else -> null } }

    /** Run once per process start before the database is touched. Returns the outcome if a switch was attempted. */
    fun recover(): RestoreOutcome? {
        val id = pendingTask() ?: return null
        val journal = read(id)
        if(journal == null) { layout.task(id).deleteRecursively();return null } // an unreadable journal never started switching
        if(journal.phase == RestorePhase.STAGED) { layout.task(id).deleteRecursively();return null } // the user left without confirming
        if(journal.phase == RestorePhase.ROLLING_BACK) return rollback(id,journal,journal.error ?: "恢复被中断")
        return apply(id,journal)
    }

    private fun apply(id: String,journal: Journal): RestoreOutcome {
        try {
            if(journal.phase == RestorePhase.CONFIRMED) {
                journal.existed.clear()
                layout.items.forEach { name -> if(live(name).exists()) journal.existed.add(name) }
                journal.phase = RestorePhase.RETIRING;write(id,journal)
            }
            if(journal.phase == RestorePhase.RETIRING) {
                checkpoint("retiring")
                layout.items.forEach { name -> retire(id,name);checkpoint("retired:$name") }
                journal.phase = RestorePhase.INSTALLING;write(id,journal)
            }
            if(journal.phase == RestorePhase.INSTALLING) {
                checkpoint("installing")
                install(id,journal.scope);checkpoint("installed")
                journal.phase = RestorePhase.PREFERENCES;write(id,journal)
            }
            if(journal.phase == RestorePhase.PREFERENCES) {
                checkpoint("preferences")
                val saved = File(previous(id),"ui-preferences.json")
                if(!saved.exists()) saveDurably(saved,readPreferences())
                val incoming = File(stage(id),BackupFormat.PREFERENCES)
                if(incoming.isFile) applyPreferences(incoming.readText())
                journal.phase = RestorePhase.COMMITTED;write(id,journal)
            }
            return RestoreOutcome(true,null)
        } catch(error: Exception) {
            return rollback(id,journal,error.message ?: error.javaClass.simpleName)
        }
    }

    /**
     * The old settings are the only copy a rollback can restore. They are written aside, fsynced, and their directory entries
     * made durable *before* the new settings are applied or any later phase is journaled; a sync failure aborts the step.
     */
    private fun saveDurably(target: File,text: String) {
        val directory = target.parentFile!!;directory.mkdirs()
        val temporary = File(directory,target.name + ".part")
        FileOutputStream(temporary).use { it.write(text.toByteArray());it.flush();it.fd.sync() }
        if(!temporary.renameTo(target)) throw java.io.IOException("无法保存原有设置")
        syncDirectory(directory);syncDirectory(directory.parentFile!!)
    }

    private fun live(name: String) = when(name) { "library" -> layout.liveLibrary;"fonts" -> requireNotNull(layout.liveFonts);"trash" -> requireNotNull(layout.liveTrash);else -> File(layout.liveDatabaseDirectory,name) }
    private fun previousItem(id: String,name: String) = File(previous(id),if(name == "library" || name == "fonts" || name == "trash") name else "db/$name")

    private fun retire(id: String,name: String) {
        val source = live(name);val destination = previousItem(id,name)
        if(!source.exists()) return
        check(!destination.exists()) { "已存在被替换内容，无法继续" }
        destination.parentFile!!.mkdirs()
        check(source.renameTo(destination)) { "无法移出当前内容" }
        syncDirectory(source.parentFile!!);syncDirectory(destination.parentFile!!)
    }

    private fun install(id: String,scope: BackupScope) {
        val stagedLibrary = File(stage(id),"files");val stagedDatabase = File(stage(id),BackupFormat.DATABASE)
        if(!layout.liveLibrary.exists()) {
            if(stagedLibrary.isDirectory) check(stagedLibrary.renameTo(layout.liveLibrary)) { "无法安装图书文件" }
            else if(scope == BackupScope.RECORDS || !stagedLibrary.exists()) check(layout.liveLibrary.mkdirs() || layout.liveLibrary.isDirectory) { "无法创建图书目录" }
            syncDirectory(layout.liveLibrary.parentFile!!)
        }
        // Imported fonts come back only with a full backup; otherwise the restored records fall back to the default font.
        layout.liveFonts?.let { fonts ->
            val stagedFonts = File(stage(id),"fonts")
            if(!fonts.exists() && stagedFonts.isDirectory) { check(stagedFonts.renameTo(fonts)) { "无法安装字体文件" };syncDirectory(fonts.parentFile!!) }
        }
        checkpoint("library-installed")
        val liveDatabase = File(layout.liveDatabaseDirectory,layout.databaseName)
        if(!liveDatabase.exists()) {
            check(stagedDatabase.isFile) { "恢复数据缺少书库" }
            layout.liveDatabaseDirectory.mkdirs()
            check(stagedDatabase.renameTo(liveDatabase)) { "无法安装书库数据" }
            syncDirectory(layout.liveDatabaseDirectory)
        }
    }

    private fun rollback(id: String,journal: Journal,reason: String): RestoreOutcome {
        return try {
            // Rolling back is itself journaled: after this point a crash must never resume forward over a half-restored library.
            if(journal.phase != RestorePhase.ROLLING_BACK) { journal.phase = RestorePhase.ROLLING_BACK;journal.error = reason;write(id,journal) }
            checkpoint("rollback")
            layout.items.forEach { name ->
                // An item already put back by an earlier attempt is never touched again: whatever the library did since then
                // (a font imported, a book added) belongs to the restored-to content and must not be replayed away.
                if(name in journal.restored) return@forEach
                val back = previousItem(id,name)
                if(back.exists()) {
                    live(name).takeIf { it.exists() }?.deleteRecursively()
                    check(back.renameTo(live(name))) { "无法还原原有内容" }
                } else if(name !in journal.existed) live(name).takeIf { it.exists() }?.deleteRecursively()
                // Both directory entries may have changed, now or in an earlier attempt whose sync failed after the rename (the
                // retired copy is then already gone and the old content live). Either way they reach storage before the journal
                // says the item is back; a failing sync leaves it unrecorded and the journal ROLLING_BACK for the next start.
                syncDirectory(live(name).parentFile!!);back.parentFile!!.takeIf { it.isDirectory }?.let(syncDirectory)
                journal.restored.add(name);write(id,journal)
            }
            syncDirectory(layout.liveLibrary.parentFile!!)
            // A settings write that fails here must not be reported as a finished rollback: the journal stays ROLLING_BACK and the
            // next start tries again, so the old settings are never silently lost.
            File(previous(id),"ui-preferences.json").takeIf { it.isFile }?.let { restorePreferences(it.readText()) }
            journal.phase = RestorePhase.ROLLED_BACK;journal.error = reason;write(id,journal)
            stage(id).deleteRecursively()
            RestoreOutcome(false,reason)
        } catch(error: Exception) {
            RestoreOutcome(false,"恢复失败且无法自动还原，原内容保留在恢复目录：" + (error.message ?: reason))
        }
    }

    /**
     * The switch was committed but the restored library could not be loaded. While the replaced content is still kept, put it
     * back; after [finalizeCommitted] this is no longer possible, which is why finalizing waits for a successful load.
     */
    fun rollbackCommitted(): RestoreOutcome? {
        val id = tasks().firstOrNull { read(it)?.phase == RestorePhase.COMMITTED } ?: return null
        val journal = read(id) ?: return null
        return rollback(id,journal,"恢复后的书库无法打开")
    }
    /** After a committed switch has been opened successfully, the previous library is no longer needed. */
    fun finalizeCommitted(): Boolean {
        var removed = false
        tasks().forEach { id -> val phase = read(id)?.phase;if(phase == RestorePhase.COMMITTED) { layout.task(id).deleteRecursively();removed = true } }
        return removed
    }
    /** The user changed their mind before the switch started: nothing has been touched, so the task is simply dropped. */
    fun abort(id: String): Boolean {
        val phase = read(id)?.phase
        if(phase != RestorePhase.STAGED && phase != RestorePhase.CONFIRMED) return false
        layout.task(id).deleteRecursively();return true
    }
    /** The user has seen a rolled-back result. */
    fun acknowledge(id: String) { if(read(id)?.phase == RestorePhase.ROLLED_BACK) layout.task(id).deleteRecursively() }
}
