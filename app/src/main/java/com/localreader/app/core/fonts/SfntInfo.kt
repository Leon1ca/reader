package com.localreader.app.core.fonts

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

class FontRejected(message: String): Exception(message)

data class FontInfo(val family: String,val extension: String)

/**
 * Reads just enough of a TrueType/OpenType file to judge it before it is stored: structure, required tables, the license
 * flag and a display name. Rendering stays with the browser engine, which sanitises the font again when it loads it.
 */
object SfntInfo {
    const val MAX_BYTES = 32L shl 20
    private const val TRUETYPE = 0x00010000
    private const val TRUE = 0x74727565   // 'true'
    private const val OTTO = 0x4F54544F   // 'OTTO'
    private const val TTCF = 0x74746366   // 'ttcf'
    private const val MAX_TABLES = 96
    private const val MAX_NAME_RECORDS = 512

    private fun tag(buffer: ByteBuffer,at: Int) = String(ByteArray(4) { buffer.get(at + it) },Charsets.ISO_8859_1)

    fun inspect(file: File,fallbackName: String): FontInfo {
        val length = file.length()
        if(length < 64) throw FontRejected("文件太小，不是字体")
        if(length > MAX_BYTES) throw FontRejected("字体文件超过 ${MAX_BYTES shr 20} MB 限制")
        RandomAccessFile(file,"r").use { raw ->
            val header = ByteArray(12).also { raw.readFully(it) }
            val head = ByteBuffer.wrap(header)
            val version = head.getInt(0)
            if(version == TTCF) throw FontRejected("暂不支持字体集合（.ttc），请导入单个 TTF 或 OTF")
            val cff = version == OTTO
            if(version != TRUETYPE && version != TRUE && !cff) throw FontRejected("不是有效的 TTF 或 OTF 字体")
            val count = head.getShort(4).toInt() and 0xFFFF
            if(count !in 4..MAX_TABLES) throw FontRejected("字体表数量异常")
            val directory = ByteArray(count * 16).also { raw.readFully(it) }
            val tables = HashMap<String,Pair<Long,Long>>()
            val dir = ByteBuffer.wrap(directory)
            for(i in 0 until count) {
                val name = tag(dir,i * 16);val offset = dir.getInt(i * 16 + 8).toLong() and 0xFFFFFFFFL;val size = dir.getInt(i * 16 + 12).toLong() and 0xFFFFFFFFL
                if(offset < 12 + count * 16L || size < 0 || offset + size > length) throw FontRejected("字体表超出文件范围，文件已损坏")
                if(tables.put(name,offset to size) != null) throw FontRejected("字体含重复的表")
            }
            val required = listOf("cmap","head","hhea","hmtx","maxp","name") + if(cff) listOf("CFF ") else listOf("glyf","loca")
            required.firstOrNull { it !in tables }?.let { throw FontRejected("字体缺少必要的表（${it.trim()}），无法使用") }
            tables["OS/2"]?.let { (offset,size) ->
                if(size >= 10) {
                    raw.seek(offset + 8);val fsType = raw.readUnsignedShort()
                    // Bit 1: "Restricted License embedding" — the font must not be embedded in documents.
                    if(fsType and 0x0002 != 0) throw FontRejected("该字体的授权禁止嵌入使用")
                }
            }
            val family = tables["name"]?.let { (offset,size) -> familyName(raw,offset,size) }?.takeIf { it.isNotBlank() } ?: displayName(fallbackName)
            return FontInfo(family,if(cff) "otf" else "ttf")
        }
    }

    internal fun displayName(raw: String): String = raw.substringBeforeLast('.').filter { !it.isISOControl() }.trim().take(60).ifBlank { "字体" }

    private fun familyName(raw: RandomAccessFile,offset: Long,size: Long): String? {
        if(size < 6 || size > 1 shl 20) return null
        val bytes = ByteArray(size.toInt());raw.seek(offset);raw.readFully(bytes)
        val buffer = ByteBuffer.wrap(bytes)
        val records = buffer.getShort(2).toInt() and 0xFFFF;val storage = buffer.getShort(4).toInt() and 0xFFFF
        if(records > MAX_NAME_RECORDS || 6 + records * 12 > bytes.size) return null
        var best: String? = null;var bestScore = -1
        for(i in 0 until records) {
            val at = 6 + i * 12
            val platform = buffer.getShort(at).toInt() and 0xFFFF;val nameId = buffer.getShort(at + 6).toInt() and 0xFFFF
            val len = buffer.getShort(at + 8).toInt() and 0xFFFF;val start = storage + (buffer.getShort(at + 10).toInt() and 0xFFFF)
            if((nameId != 1 && nameId != 16) || start + len > bytes.size || len == 0) continue
            val text = when(platform) {
                3,0 -> String(bytes,start,len,Charsets.UTF_16BE)
                1 -> String(bytes,start,len,Charsets.ISO_8859_1)
                else -> continue
            }
            val clean = text.filter { !it.isISOControl() }.trim().take(60)
            if(clean.isEmpty()) continue
            val score = (if(nameId == 16) 2 else 0) + (if(platform == 3) 1 else 0)
            if(score > bestScore) { best = clean;bestScore = score }
        }
        return best
    }
}
