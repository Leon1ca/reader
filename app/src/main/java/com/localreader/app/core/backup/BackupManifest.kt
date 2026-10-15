package com.localreader.app.core.backup

import org.json.JSONArray
import org.json.JSONObject

/** The only format version this build writes and the highest it can read. Higher versions are refused, never guessed. */
object BackupFormat {
    const val VERSION = 1
    const val MANIFEST = "manifest.json"
    const val DATABASE = "data/library.db"
    const val PREFERENCES = "data/ui-preferences.json"
    const val FILES = "files/"
    const val FONTS = "fonts/"
    /** SQLite's file header: magic at 0, big-endian user_version at 60. */
    const val SQLITE_MAGIC = "SQLite format 3\u0000"
}

enum class BackupScope(val wire: String) {
    /** Book copies and derived indexes plus records and settings. */
    FULL("full"),
    /** Records and settings only; books must be linked to files again after restoring. */
    RECORDS("records");
    companion object { fun fromWire(value: String) = entries.firstOrNull { it.wire == value } }
}

data class BackupFile(val path: String,val size: Long,val sha256: String)

data class BackupManifest(val formatVersion: Int,val schemaVersion: Int,val appVersion: String,val createdAt: Long,
    val scope: BackupScope,val bookCount: Int,val files: List<BackupFile>) {
    val totalBytes: Long get() = files.sumOf { it.size }
}

class BackupRejected(val kind: Kind,message: String): Exception(message) {
    enum class Kind { MALFORMED, UNSUPPORTED_VERSION, UNSAFE, TOO_LARGE, CORRUPT, CANCELLED }
}

object BackupManifestCodec {
    fun encode(manifest: BackupManifest): ByteArray = JSONObject().apply {
        put("formatVersion",manifest.formatVersion);put("schemaVersion",manifest.schemaVersion);put("appVersion",manifest.appVersion)
        put("createdAt",manifest.createdAt);put("scope",manifest.scope.wire);put("bookCount",manifest.bookCount)
        put("note","Not encrypted. May contain private reading content. Login sessions are never included.")
        put("files",JSONArray().apply { manifest.files.forEach { put(JSONObject().put("path",it.path).put("size",it.size).put("sha256",it.sha256)) } })
    }.toString().toByteArray(Charsets.UTF_8)

    /** Strict decode: any missing, mistyped or out-of-range field rejects the whole package. */
    fun decode(bytes: ByteArray,limits: BackupLimits): BackupManifest {
        val json = try { JSONObject(String(bytes,Charsets.UTF_8)) } catch(_: Exception) { throw BackupRejected(BackupRejected.Kind.MALFORMED,"备份清单无法读取") }
        fun integer(key: String): Long = WireLong.read(json.opt(key)) ?: throw BackupRejected(BackupRejected.Kind.MALFORMED,"备份清单字段无效：$key")
        val version = integer("formatVersion")
        if(version > BackupFormat.VERSION) throw BackupRejected(BackupRejected.Kind.UNSUPPORTED_VERSION,"备份来自更新版本的应用，当前版本无法恢复")
        if(version < 1) throw BackupRejected(BackupRejected.Kind.MALFORMED,"备份格式版本无效")
        val scope = (json.opt("scope") as? String)?.let(BackupScope::fromWire) ?: throw BackupRejected(BackupRejected.Kind.MALFORMED,"备份范围无效")
        val appVersion = (json.opt("appVersion") as? String)?.takeIf { it.length in 1..64 } ?: throw BackupRejected(BackupRejected.Kind.MALFORMED,"备份清单缺少应用版本")
        val array = json.optJSONArray("files") ?: throw BackupRejected(BackupRejected.Kind.MALFORMED,"备份清单缺少文件列表")
        if(array.length() > limits.maxEntries) throw BackupRejected(BackupRejected.Kind.TOO_LARGE,"备份条目过多")
        val seen = HashSet<String>();var total = 0L
        val files = (0 until array.length()).map { index ->
            val row = array.optJSONObject(index) ?: throw BackupRejected(BackupRejected.Kind.MALFORMED,"备份清单条目无效")
            val path = (row.opt("path") as? String) ?: throw BackupRejected(BackupRejected.Kind.MALFORMED,"备份清单路径无效")
            BackupPaths.requireSafe(path)
            val size = WireLong.read(row.opt("size"))?.takeIf { it >= 0 } ?: throw BackupRejected(BackupRejected.Kind.MALFORMED,"备份清单大小无效")
            val digest = (row.opt("sha256") as? String)?.takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: throw BackupRejected(BackupRejected.Kind.MALFORMED,"备份清单校验值无效")
            if(size > limits.maxFileBytes) throw BackupRejected(BackupRejected.Kind.TOO_LARGE,"备份内含超大文件")
            if(!seen.add(path.lowercase())) throw BackupRejected(BackupRejected.Kind.UNSAFE,"备份清单含重复路径")
            total += size
            if(total > limits.maxTotalBytes) throw BackupRejected(BackupRejected.Kind.TOO_LARGE,"备份展开后超过允许大小")
            BackupFile(path,size,digest)
        }
        return BackupManifest(version.toInt(),integer("schemaVersion").toInt(),appVersion,integer("createdAt"),scope,integer("bookCount").also { if(it !in 0..1_000_000) throw BackupRejected(BackupRejected.Kind.MALFORMED,"备份图书数无效") }.toInt(),files)
    }
}

/** JSON numbers only; no numeric strings, fractions or values outside Long. */
internal object WireLong {
    fun read(value: Any?): Long? = when(value) {
        is Int -> value.toLong()
        is Long -> value
        is Number -> value.toDouble().takeIf { it.isFinite() && it == Math.rint(it) && kotlin.math.abs(it) < 9.0e15 }?.toLong()
        else -> null
    }
}

data class BackupLimits(val maxEntries: Int = 300_000,val maxFileBytes: Long = 8L shl 30,val maxTotalBytes: Long = 64L shl 30,val maxManifestBytes: Int = 64 shl 20)
