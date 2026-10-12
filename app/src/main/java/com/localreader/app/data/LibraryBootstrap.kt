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
        val rows = linkedMapOf<String,String>()
        val version = SQLiteDatabase.openDatabase(source.path,null,SQLiteDatabase.OPEN_READWRITE,preserveCorruption).use { db ->
            val version = db.version
            require(version in 1..5) { "书库版本不受支持，原库已保留" }
            // A current library is checked by SQLite on open; the full integrity scan only guards migrations.
            if (version == 5) return
            checkIntegrity(db)
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)",null).use { c ->
                check(c.moveToFirst() && c.getInt(0) == 0 && (c.getLong(1) == c.getLong(2))) { "书库仍被使用，无法完成一致备份，请重试" }
            }
            (if(version >= 4) listOf("contents","txt_meta","epub_meta","positions","bookmarks","locator_backups","reading_settings","reader_defaults","sqlite_sequence") else listOf("books","bookmarks","locator_backups","sqlite_sequence")).filter { tableExists(db,it) }.forEach { table ->
                counts[table] = count(db,table);rows[table] = rowDigest(db,table)
            }
            rows["_schema"]=rowDigest(db,"sqlite_master")
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
                counts.forEach { (table,total) -> check(count(copy,table) == total && rowDigest(copy,table) == rows[table]) { "备份记录校验失败" } }
            }
            checkpoint("verified")
            // Repeated failed upgrades of unchanged rows reuse the verified snapshot.
            // This preserves recovery evidence while avoiding one identical backup per retry.
            val hashText=expectedHash.joinToString("") { "%02x".format(it) }
            val existing=source.parentFile?.listFiles()?.firstOrNull { old ->
                old.name.startsWith("library.db.v$version.") && old.name.endsWith(".bak") &&
                    runCatching {
                        val recorded=File(old.path+".sha256").readText()
                        val digest=MessageDigest.getInstance("SHA-256")
                        old.inputStream().use { input -> val buffer=ByteArray(65536);while(true) { val size=input.read(buffer);if(size<0)break;digest.update(buffer,0,size) } }
                        val actual=digest.digest().joinToString("") { "%02x".format(it) }
                        recorded==actual && (actual==hashText || SQLiteDatabase.openDatabase(old.path,null,SQLiteDatabase.OPEN_READONLY,preserveCorruption).use { copy ->
                            // SQLite header counters/journal mode can change after a
                            // rolled-back upgrade without changing any durable rows.
                            // Compare the complete schema and every migration table
                            // before treating those retries as the same snapshot.
                            copy.version==version && rowDigest(copy,"sqlite_master")==rows["_schema"] &&
                                counts.all { (table,total) -> count(copy,table)==total && rowDigest(copy,table)==rows[table] }
                        })
                    }.getOrDefault(false)
            }
            if(existing!=null)return
            check(temporary.renameTo(target)) { "无法提交书库备份" }
            FileOutputStream(File(target.path + ".sha256")).use { out ->
                out.write(expectedHash.joinToString("") { "%02x".format(it) }.toByteArray()); out.fd.sync()
            }
            val directory = android.system.Os.open(requireNotNull(source.parentFile).path,android.system.OsConstants.O_RDONLY,0)
            try { android.system.Os.fsync(directory) } finally { android.system.Os.close(directory) }
            // Retain the latest three verified backups for this source schema. Unverified
            // backups are preserved for manual recovery rather than silently removed.
            source.parentFile?.listFiles()?.filter { it.name.startsWith("library.db.v$version.") && it.name.endsWith(".bak") && File(it.path+".sha256").isFile }
                ?.sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })?.drop(3)?.forEach { old ->
                    if(old.delete())File(old.path+".sha256").delete()
                }
        } finally { temporary.delete() }
    }
    internal fun checkIntegrity(db: SQLiteDatabase) {
        db.rawQuery("PRAGMA integrity_check",null).use { c -> check(c.moveToFirst() && c.getString(0) == "ok" && !c.moveToNext()) { "书库完整性校验失败，原库已保留" } }
    }
    internal fun tableExists(db: SQLiteDatabase, table: String): Boolean = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",arrayOf(table)).use { it.moveToFirst() }
    private fun rowDigest(db: SQLiteDatabase,table: String): String {
        val digest=MessageDigest.getInstance("SHA-256")
        db.rawQuery("SELECT * FROM $table ORDER BY rowid",null).use { cursor ->
            while(cursor.moveToNext())for(column in 0 until cursor.columnCount) {
                val type=cursor.getType(column);digest.update(type.toByte())
                val value=when(type) {
                    android.database.Cursor.FIELD_TYPE_NULL -> byteArrayOf()
                    android.database.Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(column)
                    else -> cursor.getString(column).toByteArray(Charsets.UTF_8)
                }
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(value.size).array());digest.update(value)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun count(db: SQLiteDatabase, table: String): Long = db.rawQuery("SELECT COUNT(*) FROM $table",null).use { check(it.moveToFirst()); it.getLong(0) }
}
