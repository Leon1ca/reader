package com.localreader.app.data

import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

/** Runs before SQLiteOpenHelper can upgrade. Corruption is reported without deleting user files. */
internal object LibraryBootstrap {
    val preserveCorruption = DatabaseErrorHandler { throw SQLiteDatabaseCorruptException("书库损坏；已保留原库与备份，请重试或导出数据") }
    private val locks = mutableMapOf<String,Any>()
    private fun lock(path: File): Any = synchronized(locks) { locks.getOrPut(path.absolutePath) { Any() } }
    fun <T> open(context: Context, work: () -> T): T {
        val source = context.getDatabasePath("library.db")
        return synchronized(lock(source)) { prepare(source,existingLibrary = File(context.filesDir,"library")); work() }
    }
    internal fun prepare(source: File, checkpoint: (String) -> Unit = {}, existingLibrary: File? = null) {
        if (!source.exists()) {
            check(existingLibrary?.listFiles().isNullOrEmpty() && source.parentFile?.listFiles().orEmpty().none { it.name.startsWith("library.db.v") && it.name.endsWith(".bak") }) { "书库数据库缺失；原件及备份已保留，不能新建空库" }
            return
        }
        val counts = linkedMapOf<String,Long>()
        val version = SQLiteDatabase.openDatabase(source.path,null,SQLiteDatabase.OPEN_READWRITE,preserveCorruption).use { db ->
            val version = db.version
            require(version in 1..4) { "书库版本不受支持，原库已保留" }
            checkIntegrity(db)
            if (version == 4) return
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)",null).use { c ->
                check(c.moveToFirst() && c.getInt(0) == 0 && (c.getLong(1) == c.getLong(2))) { "书库仍被使用，无法完成一致备份，请重试" }
            }
            listOf("books","bookmarks","locator_backups").filter { tableExists(db,it) }.forEach { table ->
                counts[table] = count(db,table)
            }
            version
        }
        checkpoint("checkpointed")
        check(!File(source.path + "-wal").exists() || File(source.path + "-wal").length() == 0L) { "书库日志尚未合并，原库已保留" }
        val target = File(source.parentFile,"library.db.v$version.${UUID.randomUUID()}.bak")
        val temporary = File(target.path + ".part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            source.inputStream().use { input -> FileOutputStream(temporary).use { output ->
                val buffer = ByteArray(65536)
                while (true) { val size = input.read(buffer); if (size < 0) break; output.write(buffer,0,size); digest.update(buffer,0,size); checkpoint("copy") }
                output.fd.sync(); checkpoint("synced")
            } }
            val expectedHash = digest.digest()
            val verifiedDigest = MessageDigest.getInstance("SHA-256")
            temporary.inputStream().use { input -> val buffer = ByteArray(65536);while(true) { val size=input.read(buffer);if(size<0) break;verifiedDigest.update(buffer,0,size) } }
            check(verifiedDigest.digest().contentEquals(expectedHash)) { "备份字节校验失败" }
            SQLiteDatabase.openDatabase(temporary.path,null,SQLiteDatabase.OPEN_READONLY,preserveCorruption).use { copy ->
                check(copy.version == version); checkIntegrity(copy)
                counts.forEach { (table,total) -> check(count(copy,table) == total) { "备份记录校验失败" } }
            }
            checkpoint("verified")
            check(temporary.renameTo(target)) { "无法提交书库备份" }
            FileOutputStream(File(target.path + ".sha256")).use { out ->
                out.write(expectedHash.joinToString("") { "%02x".format(it) }.toByteArray()); out.fd.sync()
            }
            val directory = android.system.Os.open(requireNotNull(source.parentFile).path,android.system.OsConstants.O_RDONLY,0)
            try { android.system.Os.fsync(directory) } finally { android.system.Os.close(directory) }
            // An older validated backup is deliberately retained; no startup-count deletion policy.
        } finally { temporary.delete() }
    }
    internal fun checkIntegrity(db: SQLiteDatabase) {
        db.rawQuery("PRAGMA integrity_check",null).use { c -> check(c.moveToFirst() && c.getString(0) == "ok" && !c.moveToNext()) { "书库完整性校验失败，原库已保留" } }
    }
    internal fun tableExists(db: SQLiteDatabase, table: String): Boolean = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",arrayOf(table)).use { it.moveToFirst() }
    private fun count(db: SQLiteDatabase, table: String): Long = db.rawQuery("SELECT COUNT(*) FROM $table",null).use { check(it.moveToFirst()); it.getLong(0) }
}
