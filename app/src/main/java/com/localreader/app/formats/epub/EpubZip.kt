package com.localreader.app.formats.epub

import java.io.Closeable
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.CRC32

object EpubLimits {
    const val FILE_BYTES=256L*1024*1024
    const val ENTRIES=20000
    const val ENTRY_BYTES=64L*1024*1024
    const val TOTAL_BYTES=1024L*1024*1024
    const val RATIO=100L
    const val XML_BYTES=4L*1024*1024
    const val CSS_BYTES=2L*1024*1024
    const val FONT_BYTES=16L*1024*1024
    const val SMALL_SPINE_BYTES=1024L*1024
}

/** Never extracts archive paths. Every stream verifies actual counts and ZIP metadata. */
class EpubZip(file: File, private val checkpoint: () -> Unit = {}) : Closeable {
    private val zip: ZipFile
    val entries: Map<PackagePath,ZipEntry>
    @Volatile private var closed=false
    init {
        require(file.length() in 1..EpubLimits.FILE_BYTES) { "EPUB 超过 256 MB 限制或文件为空" }
        // EPUB's uncompressed mimetype must be the first local entry, without an extra field.
        RandomAccessFile(file,"r").use { r ->
            val header=ByteArray(30);r.readFully(header)
            fun word(i: Int)=(header[i].toInt() and 255) or ((header[i+1].toInt() and 255) shl 8)
            require(header.take(4)==listOf<Byte>(0x50,0x4b,0x03,0x04) && word(8)==0 && word(26)==8 && word(28)==0) { "EPUB 缺少首条未压缩的 mimetype" }
            val name=ByteArray(8);r.readFully(name);require(name.toString(Charsets.US_ASCII)=="mimetype")
        }
        zip=ZipFile(file)
        try {
            val all=linkedMapOf<PackagePath,ZipEntry>();var total=0L
            val list=zip.entries()
            while(list.hasMoreElements()) {
                checkpoint(); val item=list.nextElement()
                require(all.size<EpubLimits.ENTRIES) { "EPUB 条目超过 20000" }
                val path=PackagePath.entry(item.name)
                require(all.put(path,item)==null) { "EPUB 含重复条目：${path.value}" }
                require(item.size in 0..EpubLimits.ENTRY_BYTES && item.compressedSize>=0) { "EPUB 条目超过 64 MB 或大小无效" }
                total+=item.size;require(total<=EpubLimits.TOTAL_BYTES) { "EPUB 总解压大小超过 1 GB" }
                require(item.size<=1024*1024 || item.size<=item.compressedSize.coerceAtLeast(1)*EpubLimits.RATIO) { "EPUB 压缩比超过安全限制" }
                require(item.method==ZipEntry.STORED || item.method==ZipEntry.DEFLATED) { "EPUB 压缩算法不受支持" }
            }
            entries=all
            require(read(PackagePath.entry("mimetype"),64).toString(Charsets.US_ASCII)=="application/epub+zip") { "文件不是有效 EPUB" }
            require(PackagePath.entry("META-INF/container.xml") in entries) { "EPUB 缺少 container.xml" }
            require(PackagePath.entry("META-INF/rights.xml") !in entries) { "EPUB 含 DRM 权限文件，无法离线打开" }
        } catch(error: Throwable) { zip.close();throw error }
    }
    fun contains(path: PackagePath)=entries[path]?.isDirectory==false
    fun stream(path: PackagePath, limit: Long=EpubLimits.ENTRY_BYTES): InputStream {
        check(!closed) { "EPUB 资源会话已关闭" }
        val entry=requireNotNull(entries[path]) { "EPUB 资源不存在：${path.value}" }
        require(!entry.isDirectory && entry.size<=limit) { "EPUB 资源超过读取限制" }
        return object: FilterInputStream(zip.getInputStream(entry)) {
            private var count=0L
            private val crc=CRC32()
            private fun checked(size: Int): Int {
                checkpoint();check(!closed) { "EPUB 资源会话已关闭" }
                if(size>=0) { count+=size;require(count<=limit && count<=entry.size) { "EPUB 实际资源大小超过限制" } }
                else { require(count==entry.size) { "EPUB 资源大小不符" };require(crc.value==entry.crc) { "EPUB 资源校验失败" } }
                return size
            }
            override fun read(): Int { val value=super.read();if(value>=0)crc.update(value);checked(if(value<0)-1 else 1);return value }
            override fun read(buffer: ByteArray,offset: Int,length: Int): Int { val n=`in`.read(buffer,offset,length);if(n>0)crc.update(buffer,offset,n);return checked(n) }
            override fun skip(count: Long): Long { if(count<=0)return 0;val buffer=ByteArray(minOf(count,65536).toInt());var consumed=0L
                while(consumed<count) { val n=read(buffer,0,minOf(buffer.size.toLong(),count-consumed).toInt());if(n<0)break;consumed+=n };return consumed }
        }
    }
    fun read(path: PackagePath,limit: Long=EpubLimits.ENTRY_BYTES)=stream(path,limit).use { it.readBytes() }
    /** Checks unreferenced entries too; declared central-directory sizes cannot hide a bomb. */
    fun verifyAll() {
        var total=0L;val buffer=ByteArray(65536)
        entries.filterValues { !it.isDirectory }.forEach { (path,_) -> stream(path).use { input ->
            while(true) { val n=input.read(buffer);if(n<0)break;total+=n;require(total<=EpubLimits.TOTAL_BYTES) { "EPUB 实际总解压大小超过 1 GB" } }
        } }
    }
    override fun close() { if(!closed) { closed=true;zip.close() } }
}
