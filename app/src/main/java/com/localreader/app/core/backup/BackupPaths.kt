package com.localreader.app.core.backup

import java.io.File

/** SEC-05: every package path is validated as data before it can touch the file system. */
object BackupPaths {
    private const val MAX_DEPTH = 12
    private val fontName = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(ttf|otf)")
    private const val MAX_NAME = 200
    private const val MAX_PATH = 1024

    fun requireSafe(path: String) {
        fun bad(why: String): Nothing = throw BackupRejected(BackupRejected.Kind.UNSAFE,"备份含不安全路径（$why）")
        if(path.isEmpty() || path.length > MAX_PATH) bad("长度")
        if(path.startsWith("/") || path.endsWith("/")) bad("绝对路径或目录项")
        if(path.any { it == '\\' || it == '\u0000' || it < ' ' || it == '\u007f' }) bad("控制字符")
        val parts = path.split('/')
        if(parts.size > MAX_DEPTH) bad("层级过深")
        for(part in parts) {
            if(part.isEmpty() || part == "." || part == "..") bad("相对跳转")
            if(part.length > MAX_NAME) bad("名称过长")
            if(part.endsWith(" ") || part.endsWith(".")) bad("名称")
            if(part.length >= 2 && part[1] == ':' && part[0].isLetter()) bad("盘符")
        }
        val first = parts.first()
        val isFonts = first == BackupFormat.FONTS.trimEnd('/')
        if(path != BackupFormat.MANIFEST && path != BackupFormat.DATABASE && path != BackupFormat.PREFERENCES && first != BackupFormat.FILES.trimEnd('/') && !isFonts) bad("位置")
        if(first == BackupFormat.FILES.trimEnd('/') && parts.size < 3) bad("缺少图书目录")
        if(isFonts && !(parts.size == 2 && fontName.matches(parts[1]))) bad("字体名称")
    }

    /** Resolves below [root] and refuses anything whose canonical location leaves it, including through links. */
    fun resolve(root: File,path: String): File {
        requireSafe(path)
        val canonicalRoot = root.canonicalFile
        val target = File(canonicalRoot,path).canonicalFile
        if(!target.path.startsWith(canonicalRoot.path + File.separator)) throw BackupRejected(BackupRejected.Kind.UNSAFE,"备份路径越出恢复目录")
        return target
    }
}
