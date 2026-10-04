package com.localreader.app.data.importer

import com.localreader.app.core.TxtParser
import com.localreader.app.core.TextIndex
import com.localreader.app.formats.epub.*
import java.io.File
import java.util.UUID

/** Format adapters own parsing; private-copy/hash/transaction/cleanup remain one shared pipeline. */
interface ContentImporter {
    fun accepts(name: String,mime: String,header: ByteArray): Boolean
    fun prepare(task: ImportTask): PreparedContent
}
data class ImportTask(val original: File,val versions: File,val hash: String,val checkpoint: () -> Unit)
sealed interface PreparedContent {
    data class Text(val revision: String,val index: TextIndex): PreparedContent
    data class Epub(val cache: EpubCacheRecord): PreparedContent
}
class TxtImporter : ContentImporter {
    override fun accepts(name: String,mime: String,header: ByteArray): Boolean {
        val extension=name.substringAfterLast('.',"").lowercase()
        return extension=="txt" || mime=="text/plain" || extension.isEmpty() && mime.startsWith("text/")
    }
    override fun prepare(task: ImportTask): PreparedContent.Text {
        require(task.original.length()<=TxtParser.MAX_BYTES) { "TXT 文件超过 128 MB 限制" }
        val version=UUID.randomUUID().toString()
        val encoding=TxtParser.detect(task.original,task.checkpoint)
        return PreparedContent.Text(version,TxtParser.build(task.original,File(task.versions,version),encoding,task.checkpoint))
    }
}
class EpubImporter : ContentImporter {
    override fun accepts(name: String,mime: String,header: ByteArray)=header.size>=4 && header[0]==0x50.toByte() && header[1]==0x4b.toByte() && header[2]==3.toByte() && header[3]==4.toByte()
    override fun prepare(task: ImportTask): PreparedContent.Epub {
        val pending=File(task.versions,"pending")
        val cache=EpubCache.build(task.original,pending,task.hash,task.checkpoint)
        task.checkpoint();check(pending.renameTo(File(task.versions,cache.revision))) { "无法提交 EPUB 索引" }
        return PreparedContent.Epub(cache)
    }
}
