package com.localreader.app

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.localreader.app.core.ContentFormat
import com.localreader.app.core.ContentSummary
import com.localreader.app.ui.shelf.CoverLoader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

/** Actual Android decoder against owned files; no database rows or user originals. */
@RunWith(AndroidJUnit4::class)
class CoverDecoderNativeTest {
    private fun png(width: Int,height: Int): ByteArray {
        val encoded=ByteArrayOutputStream()
        DataOutputStream(encoded).use { output ->
            output.write(byteArrayOf(-119,80,78,71,13,10,26,10))
            fun chunk(type: String,data: ByteArray) {
                val tag=type.toByteArray(Charsets.US_ASCII)
                output.writeInt(data.size);output.write(tag);output.write(data)
                val crc=CRC32();crc.update(tag);crc.update(data);output.writeInt(crc.value.toInt())
            }
            val header=ByteArrayOutputStream().also {bytes -> DataOutputStream(bytes).use {it.writeInt(width);it.writeInt(height);it.write(byteArrayOf(8,6,0,0,0))}}.toByteArray()
            chunk("IHDR",header)
            val compressed=ByteArrayOutputStream()
            DeflaterOutputStream(compressed).use { zip ->
                val row=ByteArray(width*4).apply {for(x in 0 until width) {this[x*4]=32;this[x*4+1]=80;this[x*4+2]=112;this[x*4+3]=-1}}
                repeat(height) {zip.write(0);zip.write(row)}
            }
            chunk("IDAT",compressed.toByteArray());chunk("IEND",byteArrayOf())
        }
        return encoded.toByteArray()
    }
    @Test fun actualDecoderSamplesFullResolutionSourceAndCannotEscapeOwnedCoverRoot() = runBlocking {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(target.cacheDir,"owned-cover-${UUID.randomUUID()}").apply {check(mkdirs())}
        var failure: Throwable?=null
        try {
            val own=File(root,"own").apply {check(mkdirs())}
            val source=File(own,"cover.png");val original=png(4000,6000);source.writeBytes(original)
            val loader=CoverLoader(root)
            val book=ContentSummary("own",ContentFormat.EPUB,"OWNED","",original.size.toLong(),0,0,0.0,0.0,coverPath="cover.png")
            val image=requireNotNull(loader.load(book))
            assertEquals(125,image.width);assertTrue(image.height in 187..188)
            assertTrue(maxOf(image.width,image.height)<=360)
            assertTrue(image.width.toLong()*image.height<=131072)
            assertTrue(image.allocationByteCount<=131072*4)
            assertSame(image,loader.load(book))
            assertEquals(1L,loader.revision.value)
            val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true};BitmapFactory.decodeFile(source.path,bounds)
            assertEquals(4000,bounds.outWidth);assertEquals(6000,bounds.outHeight)
            assertArrayEquals(original,source.readBytes())
            assertNull(loader.load(book.copy(coverPath="../own/cover.png")))
            val linked=File(own,"linked.png");java.nio.file.Files.createSymbolicLink(linked.toPath(),source.toPath())
            assertNull(loader.load(book.copy(coverPath="linked.png")))
            // A real extreme thin PNG is either safely decoded or reported unavailable.
            // Its arithmetic limit is also checked independently in the JVM contract.
            val thin=File(own,"thin.png").apply {writeBytes(png(524288,1))}
            val thinImage=loader.load(book.copy(coverPath="thin.png"))
            thinImage?.let {assertTrue(maxOf(it.width,it.height)<=360);assertTrue(it.width.toLong()*it.height<=131072);assertTrue(it.allocationByteCount<=131072*4)}
            assertTrue(thin.isFile)
            val tile=png(350,350)
            var first: android.graphics.Bitmap?=null
            val books=(0 until 36).map {index ->
                val directory=File(root,"cache-$index").apply {check(mkdirs())}
                File(directory,"cover.png").writeBytes(tile)
                book.copy(id="cache-$index")
            }
            for((index,item) in books.withIndex()) {
                val decoded=requireNotNull(loader.load(item))
                if(index==0)first=decoded
                assertTrue(loader.cachedBytes<=16*1024*1024)
            }
            val beforeReload=loader.revision.value
            assertNotSame(first,loader.load(books.first()))
            assertEquals(beforeReload+1,loader.revision.value)
            assertTrue(loader.cachedBytes<=16*1024*1024)

        } catch(error: Throwable) {failure=error;throw error}
        finally {try {check(root.deleteRecursively())} catch(error: Throwable) {if(failure!=null)failure?.addSuppressed(error) else throw error}}
    }
}
