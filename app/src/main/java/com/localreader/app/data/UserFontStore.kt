package com.localreader.app.data

import android.content.Context
import android.os.StatFs
import com.localreader.app.core.UserFontResource
import com.localreader.app.core.UserFontSource
import com.localreader.app.core.fonts.FontRejected
import com.localreader.app.core.fonts.SfntInfo
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/** Imported reading fonts (S9-2). Files are private to the app, named by random id, and judged by [SfntInfo] before they are kept. */
class UserFontStore(context: Context,private val repository: LibraryRepository): UserFontSource {
    private val db get() = repository.db
    companion object {
        const val MAX_FONTS = 24
        const val MAX_TOTAL = 256L shl 20
        val ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
    private val context = context.applicationContext
    private val root get() = File(context.filesDir,"user-fonts")

    class Imported(val font: UserFont,val duplicate: Boolean)

    fun list(): List<UserFont> = db.userFonts()

    /** Copies, validates and keeps the font. Nothing is stored unless the whole file passes; an identical font is not stored twice. */
    fun import(displayName: String,declaredBytes: Long?,open: () -> InputStream,cancelled: () -> Boolean = { false }): Imported = repository.exclusive {
        com.localreader.app.core.backup.RestoreGate.require()
        root.mkdirs()
        root.listFiles()?.filter { it.name.startsWith(".staging-") }?.forEach { it.delete() }
        if(declaredBytes != null && declaredBytes > SfntInfo.MAX_BYTES) throw FontRejected("字体文件超过 ${SfntInfo.MAX_BYTES shr 20} MB 限制")
        val existing = db.userFonts()
        val staging = File(root,".staging-${UUID.randomUUID()}")
        try {
            val digest = MessageDigest.getInstance("SHA-256");var copied = 0L
            open().use { input -> staging.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while(true) {
                    if(cancelled()) throw FontRejected("已取消")
                    val n = input.read(buffer);if(n < 0) break
                    copied += n;if(copied > SfntInfo.MAX_BYTES) throw FontRejected("字体文件超过 ${SfntInfo.MAX_BYTES shr 20} MB 限制")
                    digest.update(buffer,0,n);output.write(buffer,0,n)
                }
                output.fd.sync()
            } }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            db.userFontByHash(hash)?.let { existing ->
                // A record that outlived its file (records-only restore) is given its file back instead of being called a duplicate.
                val kept = File(root,"${existing.id}.${existing.extension}")
                if(!kept.isFile) {
                    SfntInfo.inspect(staging,displayName)
                    check(staging.renameTo(kept)) { "无法保存字体" }
                }
                return@exclusive Imported(existing,true)
            }
            // The limits apply to fonts that would be new; an identical font is recognised (and its file restored) above, even when full.
            if(existing.size >= MAX_FONTS) throw FontRejected("最多导入 $MAX_FONTS 个字体，请先删除不用的")
            if(existing.sumOf { it.bytes } + copied > MAX_TOTAL) throw FontRejected("字体总大小超过 ${MAX_TOTAL shr 20} MB，请先删除不用的")
            if(StatFs(root.absolutePath).availableBytes < copied * 2 + (16L shl 20)) throw FontRejected("存储空间不足")
            val info = SfntInfo.inspect(staging,displayName)
            val id = UUID.randomUUID().toString()
            val target = File(root,"$id.${info.extension}")
            check(staging.renameTo(target)) { "无法保存字体" }
            val font = UserFont(id,SfntInfo.displayName(displayName),info.family,info.extension,copied,System.currentTimeMillis())
            try { db.insertUserFont(font,hash) } catch(error: Throwable) { target.delete();throw error }
            Imported(font,false)
        } finally { staging.delete() }
    }

    fun delete(id: String) = repository.exclusive {
        com.localreader.app.core.backup.RestoreGate.require()
        require(ID.matches(id))
        val font = db.userFont(id)
        db.deleteUserFont(id)
        if(font != null) File(root,"$id.${font.extension}").delete()
    }

    /** Files with no row (after a restore or an interrupted import) are removed; rows with no file stay and fall back at render time. */
    fun reconcile() = repository.exclusive {
        com.localreader.app.core.backup.RestoreGate.require()
        val known = db.userFonts().associate { "${it.id}.${it.extension}" to it.id }
        root.listFiles()?.forEach { file -> if(file.name.startsWith(".staging-") || file.name !in known) file.delete() }
    }

    override fun find(id: String): UserFontResource? {
        if(!ID.matches(id)) return null
        val font = db.userFont(id) ?: return null
        val file = File(root,"$id.${font.extension}").takeIf { it.isFile } ?: return null
        return UserFontResource(if(font.extension == "otf") "font/otf" else "font/ttf") { file.inputStream() }
    }
}
