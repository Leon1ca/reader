package com.localreader.app

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Locally authored public-domain test prose; STORED entries give honest source byte sizes. */
internal object OwnedPerformanceFixtures {
    fun txt20MiB(title: String): ByteArray {
        val size=20*1024*1024
        val bytes=ByteArray(size) { 'a'.code.toByte() }
        val prefix="Chapter 1\n$title\n".toByteArray();prefix.copyInto(bytes)
        for(index in prefix.size until size)if(index%120==119)bytes[index]='\n'.code.toByte()
        return bytes
    }
    fun epub(title: String,large: Boolean=false): ByteArray {
        val output=ByteArrayOutputStream()
        val count=if(large)20 else 2
        ZipOutputStream(output).use { zip ->
            fun entry(path: String,bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(path).apply { method=ZipEntry.STORED;size=bytes.size.toLong();compressedSize=size;crc=CRC32().apply { update(bytes) }.value })
                zip.write(bytes);zip.closeEntry()
            }
            fun text(path: String,value: String)=entry(path,value.toByteArray())
            text("mimetype","application/epub+zip")
            text("META-INF/container.xml","<container xmlns='urn:oasis:names:tc:opendocument:xmlns:container'><rootfiles><rootfile full-path='book.opf' media-type='application/oebps-package+xml'/></rootfiles></container>")
            text("book.opf","<package xmlns='http://www.idpf.org/2007/opf' version='3.0' unique-identifier='u'><metadata xmlns:dc='http://purl.org/dc/elements/1.1/'><dc:identifier id='u'>$title</dc:identifier><dc:title>$title</dc:title><dc:language>en</dc:language></metadata><manifest>"+(0 until count).joinToString("") { "<item id='c$it' href='c$it.xhtml' media-type='application/xhtml+xml'/>" }+"</manifest><spine>"+(0 until count).joinToString("") { "<itemref idref='c$it'/>" }+"</spine></package>")
            repeat(count) { chapter ->
                val start="<html xmlns='http://www.w3.org/1999/xhtml'><head><title>$title</title></head><body><h1>Chapter $chapter</h1>"
                val paragraph="<p>Owned performance fixture. Read local books safely. The quick brown fox jumps over the lazy dog.</p>\n"
                val repetitions=if(large)(256*1024-start.length-14)/paragraph.length+1 else 16
                text("c$chapter.xhtml",start+paragraph.repeat(repetitions)+"</body></html>")
            }
        }
        return output.toByteArray()
    }
    fun fixedLargeImage(title: String): ByteArray {
        val compressed=ByteArrayOutputStream()
        java.util.zip.DeflaterOutputStream(compressed).use { stream ->
            val row=ByteArray(1+4000*3)
            repeat(6000) { y ->
                row[0]=0
                repeat(4000) { x ->
                    val index=1+x*3;val grid=x%500<8 || y%500<8
                    row[index]=(if(grid)32 else 240).toByte();row[index+1]=(if(grid)64 else 235-y/100).toByte();row[index+2]=(if(grid)96 else 220+x/100).toByte()
                }
                stream.write(row)
            }
        }
        val png=ByteArrayOutputStream()
        java.io.DataOutputStream(png).use { out ->
            out.write(byteArrayOf(-119,80,78,71,13,10,26,10))
            fun chunk(type: String,bytes: ByteArray) {
                val name=type.toByteArray(Charsets.US_ASCII);out.writeInt(bytes.size);out.write(name);out.write(bytes)
                out.writeInt(CRC32().apply { update(name);update(bytes) }.value.toInt())
            }
            val header=java.nio.ByteBuffer.allocate(13).putInt(4000).putInt(6000).put(8).put(2).put(0).put(0).put(0).array()
            chunk("IHDR",header);chunk("IDAT",compressed.toByteArray());chunk("IEND",byteArrayOf())
        }
        val result=ByteArrayOutputStream()
        ZipOutputStream(result).use { zip ->
            fun entry(path: String,bytes: ByteArray) {zip.putNextEntry(ZipEntry(path).apply {method=ZipEntry.STORED;size=bytes.size.toLong();compressedSize=size;crc=CRC32().apply {update(bytes)}.value});zip.write(bytes);zip.closeEntry()}
            fun text(path: String,value: String)=entry(path,value.toByteArray())
            text("mimetype","application/epub+zip")
            text("META-INF/container.xml","<container xmlns='urn:oasis:names:tc:opendocument:xmlns:container'><rootfiles><rootfile full-path='book.opf' media-type='application/oebps-package+xml'/></rootfiles></container>")
            text("book.opf","<package xmlns='http://www.idpf.org/2007/opf' version='3.0' unique-identifier='u'><metadata xmlns:dc='http://purl.org/dc/elements/1.1/'><dc:identifier id='u'>$title</dc:identifier><dc:title>$title</dc:title><meta property='rendition:layout'>pre-paginated</meta></metadata><manifest>"+(0 until 30).joinToString("") { "<item id='p$it' href='p$it.xhtml' media-type='application/xhtml+xml'/>" }+"<item id='image' href='large.png' media-type='image/png'/></manifest><spine>"+(0 until 30).joinToString("") { "<itemref idref='p$it'/>" }+"</spine></package>")
            repeat(30) { page -> text("p$page.xhtml","<html xmlns='http://www.w3.org/1999/xhtml'><head><meta name='viewport' content='width=4000,height=6000'/><title>$title</title></head><body style='margin:0'><img src='large.png' width='4000' height='6000' alt='Owned 4000 by 6000 grid image, page $page'/></body></html>") }
            entry("large.png",png.toByteArray())
        }
        return result.toByteArray()
    }

}
