package com.localreader.app

import androidx.test.platform.app.InstrumentationRegistry
import com.localreader.app.core.ImportSource
import com.localreader.app.core.ImportOutcome
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Opt-in, owned fixtures for release acceptance. Never clears or rewrites existing books. */
class AcceptanceFixturesTest {
    @Test fun prepareOrRemoveOwnedFixtures() {
        val args=InstrumentationRegistry.getArguments()
        val mode=args.getString("acceptanceFixtures")
        assumeTrue("Explicit acceptanceFixtures=seed/cleanup required",mode=="seed" || mode=="cleanup")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val app=context.applicationContext as ReaderApplication
        val repository=app.repository
        repository.recover()
        val manifest=File(context.getExternalFilesDir(null),"acceptance-fixtures.json")
        if(mode=="cleanup") {
            if(manifest.isFile) {
                val rows=JSONObject(manifest.readText()).getJSONArray("books")
                for(i in 0 until rows.length())repository.content(rows.getJSONObject(i).getString("id"))?.let(repository::delete)
                check(manifest.delete())
            }
            return
        }
        check(!manifest.exists()) { "Clean up the previous owned fixture batch first" }
        val count=(args.getString("acceptanceBooks")?.toInt() ?: 20).also {require(it in 2..1000 && it%2==0)}
        val token=UUID.randomUUID().toString()
        val rows=JSONArray()
        fun save() {manifest.writeText(JSONObject().put("token",token).put("books",rows).toString())}
        save()
        for(i in 0 until count) {
            val title="QA-$token-${i.toString().padStart(4,'0')}"
            val epub=i%2==1
            val bytes=if(epub) epub(title,token,i) else ("Chapter 1\n$title\n"+"A local reading acceptance paragraph 中文。\n".repeat(240)+"Chapter 2\n"+"Second chapter for page turns.\n".repeat(240)).toByteArray()
            val result=repository.import(ImportSource("$title.${if(epub)"epub" else "txt"}",if(epub)"application/epub+zip" else "text/plain",bytes.size.toLong()) {ByteArrayInputStream(bytes)})
            check(result.outcome==ImportOutcome.OK) { result.status }
            rows.put(JSONObject().put("id",result.bookId).put("title",title).put("format",if(epub)"EPUB" else "TXT"))
            save()
        }
        if(args.getString("acceptanceLarge")=="true") {
            val large=listOf(
                Triple("TXT-20MiB","txt",OwnedPerformanceFixtures.txt20MiB("QA-$token-large-TXT")),
                Triple("EPUB-5MiB","epub",OwnedPerformanceFixtures.epub("QA-$token-EPUB-5MiB",large=true)),
                Triple("FIXED-4000x6000","epub",OwnedPerformanceFixtures.fixedLargeImage("QA-$token-FIXED-4000x6000"))
            )
            large.forEach { (kind,extension,bytes) ->
                val title="QA-$token-$kind"
                val result=repository.import(ImportSource("$title.$extension",if(extension=="epub")"application/epub+zip" else "text/plain",bytes.size.toLong()) {ByteArrayInputStream(bytes)})
                check(result.outcome==ImportOutcome.OK) { result.status }
                rows.put(JSONObject().put("id",result.bookId).put("title",title).put("format",extension.uppercase()).put("fixture",kind).put("sourceBytes",bytes.size));save()
            }
        }
        android.util.Log.i("ReaderAcceptance","fixturesReady count=${rows.length()} token=$token")
    }
    private fun epub(title: String,token: String,index: Int): ByteArray {
        val out=ByteArrayOutputStream()
        ZipOutputStream(out).use {zip ->
            fun entry(path: String,text: String) {
                val bytes=text.toByteArray()
                zip.putNextEntry(ZipEntry(path).apply { if(path=="mimetype") {method=ZipEntry.STORED;size=bytes.size.toLong();compressedSize=size;crc=java.util.zip.CRC32().apply {update(bytes)}.value} })
                zip.write(bytes);zip.closeEntry()
            }
            entry("mimetype","application/epub+zip")
            entry("META-INF/container.xml","<container xmlns='urn:oasis:names:tc:opendocument:xmlns:container'><rootfiles><rootfile full-path='book.opf' media-type='application/oebps-package+xml'/></rootfiles></container>")
            entry("book.opf","<package xmlns='http://www.idpf.org/2007/opf' version='3.0' unique-identifier='u'><metadata xmlns:dc='http://purl.org/dc/elements/1.1/'><dc:identifier id='u'>$token-$index</dc:identifier><dc:title>$title</dc:title><dc:language>zh-CN</dc:language></metadata><manifest><item id='a' href='a.xhtml' media-type='application/xhtml+xml'/><item id='b' href='b.xhtml' media-type='application/xhtml+xml'/></manifest><spine><itemref idref='a'/><itemref idref='b'/></spine></package>")
            for(chapter in listOf("a","b"))entry("$chapter.xhtml","<html xmlns='http://www.w3.org/1999/xhtml'><head><title>$title</title></head><body><h1>Chapter $chapter</h1>"+"<p>Local reader acceptance 中文与 English mixed content. No remote resources.</p>".repeat(240)+"</body></html>")
        }
        return out.toByteArray()
    }
}
