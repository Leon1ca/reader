package com.localreader.app.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TxtDisplayProjectionTest {
    @get:Rule val temporary=TemporaryFolder()
    private fun engine(text: String): Pair<File,TxtEngine> {
        val original=temporary.newFile().apply {writeText(text)};val directory=temporary.newFolder()
        TxtParser.build(original,directory,"UTF-8")
        return original to TxtEngine(directory,"original-sha")
    }
    @Test fun plausibleSliceCorruptionCannotSilentlyTrimVisibleBody() {
        val (_,source)=engine("  Keep every visible letter.  \nSecond line.")
        source.use {
            val directory=requireNotNull(source.displayDirectory)
            TxtDisplayProjection.prepare(source,directory).use { assertEquals(2,it.slice(0).start) }
            val cache=File(directory,"${TxtDisplayProjection.ALGORITHM}.bin");val original=cache.readBytes()
            java.io.RandomAccessFile(cache,"rw").use { file ->
                file.readInt();file.readUTF();file.readUTF();file.readInt();file.readLong()
                val first=file.filePointer;file.seek(first+12);file.writeInt(3) // Still structurally valid, but drops the K.
            }
            TxtDisplayProjection.prepare(source,directory).use { assertEquals(2,it.slice(0).start) }
            assertArrayEquals(original,cache.readBytes())
        }
    }
    @Test fun emptyVisibleBodyFailsExplicitlyWithoutPublishingOrChangingOriginalIndex() {
        for(text in listOf("\uFEFF","\uFEFF\u00a0 \r\n\r\n\u3000\t","\r\n\r\n")) {
            val original=temporary.newFile().apply {writeText(text)};val bytes=original.readBytes()
            assertThrows(IllegalArgumentException::class.java) {TxtParser.build(original,temporary.newFolder(),"UTF-8")}
            assertArrayEquals(bytes,original.readBytes())
        }
        // A later BOM is visible to the legacy parser but removed by the
        // display trim contract, so the derived publication must reject it.
        val (original,source)=engine(" \r\n\uFEFF\u00a0 \r\n\r\n\u3000\t")
        val directory=requireNotNull(source.displayDirectory)
        val bytes=original.readBytes();val index=File(directory,"index.bin").readBytes()
        source.use {
            val error=assertThrows(IllegalArgumentException::class.java) {TxtDisplayProjection.prepare(source,directory)}
            assertTrue(error.message.orEmpty().contains("原文件已保留"))
        }
        assertFalse(File(directory,"${TxtDisplayProjection.ALGORITHM}.bin").exists())
        assertArrayEquals(bytes,original.readBytes());assertArrayEquals(index,File(directory,"index.bin").readBytes())
    }
    @Test fun mixedLatinShortChapterRulerKeepsSourceTitleSemanticsAndCoordinates() {
        val text="第一章 字体对照\nabcdefghijklm\nnopqrstuvwxyz\nABCDEFGHIJKLM\nNOPQRSTUVWXYZ\n0123456789\n０１２３４５６７８９\n春风阅读中文数字\n清晨书页天地山水\niiii llll tttt\nmmmm wwww\né é 😀 中文\n第二章 测试尾部\n参考测试尾部，保持原样。\n"
        val (_,source)=engine(text)
        source.use {TxtDisplayProjection.prepare(source,requireNotNull(source.displayDirectory)).use {projection ->
            val publication=TxtPublication(source,projection=projection);val html=publication.section(0).html
            assertEquals(2,source.index.chapters.size)
            assertFalse(html.contains("data-generated"))
            assertTrue(html.contains("<h2><span data-block=\"0\""));assertTrue(projection.slice(0).sourceHeading)
            val original=Locator.Text(source.version,source.block(12).sourceOffset,12)
            val target=publication.target(original);assertEquals(12.toString(),target.blockId)
            assertEquals(original.sourceOffset,(publication.locator(target.anchor) as Locator.Text).sourceOffset)
        }}
        val (_,hundred)=engine((1..101).joinToString("\n") {"河岸文字第${it}行。"})
        hundred.use {TxtDisplayProjection.prepare(hundred,requireNotNull(hundred.displayDirectory)).use {projection ->
            assertEquals(1,projection.slice(0).generatedHeading);assertEquals(2,projection.slice(100).generatedHeading)
        }}
    }
    @Test fun singleSourceHeadingSurvivesTocFallbackLatinBodyAndDistantGiantParagraph() {
        val (_,source)=engine("第一章 起点\n"+"English body ".repeat(20)+"\n"+"L".repeat(120000))
        source.use {TxtDisplayProjection.prepare(source,requireNotNull(source.displayDirectory)).use {projection ->
            assertTrue(source.block(0).heading);assertTrue(projection.slice(0).sourceHeading)
            assertTrue(TxtPublication(source,projection=projection).section(0).html.contains("<h2><span data-block=\"0\""))
            assertEquals(0,projection.slice(1).generatedHeading)
            val middle=source.resolve(Locator.Text(source.version,70000,0));val target=projection.target(middle)
            assertFalse(target.nearby);assertEquals(middle,target)
        }}
    }
    @Test fun naturalTrimMapsEveryRetainedUtf16UnitToOriginalAndKeepsInteriorTechnicalSpaces() {
        val text="\t \u3000汉😀é 中间\u00A0 \r\n\n"+" ".repeat(3000)+"A"+" ".repeat(2100)+"B"+" ".repeat(3000)+"\n结尾"
        val (original,source)=engine(text);val bytes=original.readBytes()
        val before=listOf("index.bin","text.bin","meta.bin").associateWith {File(source.displayDirectory,it).readBytes()}
        source.use {
            TxtDisplayProjection.prepare(source,requireNotNull(source.displayDirectory)).use {projection ->
                val publication=TxtPublication(source,projection=projection)
                val displayed=(0 until source.index.paragraphs).map {n ->val block=source.block(n);val slice=projection.slice(n);block.text.substring(slice.start,slice.end)}
                assertEquals("汉😀é 中间",displayed[0]);assertEquals("",displayed[1]);assertTrue(projection.slice(1).emptyUnit)
                val blank=publication.target(Locator.Text(source.version,source.block(1).sourceOffset,1))
                assertTrue(blank.nearby);assertNotEquals("1",blank.blockId)
                assertTrue(publication.section(0).html.contains("<h2 data-generated=\"true\">1</h2>"))
                assertTrue(publication.section(0).html.contains("<p hidden>"))
                assertEquals("A"+" ".repeat(2100)+"B",displayed.drop(2).dropLast(1).joinToString(""))
                repeat(source.index.paragraphs) { n ->
                    val slice=projection.slice(n)
                    repeat(slice.displayedLength) {char ->
                        val sourceChar=slice.start+char;val offset=source.block(n).sourceOffset+sourceChar
                        val raw=Locator.Text(source.version,offset,n,sourceChar)
                        val target=publication.target(raw)
                        assertEquals(n.toString(),target.blockId);assertEquals(char,target.character);assertFalse(target.nearby)
                        val restored=publication.locator(target.section,target.blockId,target.character) as Locator.Text
                        assertEquals(offset,restored.sourceOffset);assertEquals(sourceChar,restored.character);assertEquals(source.version,restored.version)
                    }
                }
                val prefix=publication.target(Locator.Text(source.version,0,0))
                assertTrue(prefix.nearby);assertEquals(0,prefix.character)
                assertTrue(publication.revision!=source.version);assertEquals(source.version,publication.sourceRevision)
            }
            // An installed projection opens without any正文 decode.
            val metadataOnly=object: TxtTextSource by source {override fun block(paragraph: Int): TextBlock=error("unexpected decode")}
            TxtDisplayProjection.prepare(metadataOnly,requireNotNull(source.displayDirectory)).use {assertEquals(0,it.slice(1).displayedLength)}
        }
        assertArrayEquals(bytes,original.readBytes())
        before.forEach {(name,expected) ->assertArrayEquals(expected,File(source.displayDirectory,name).readBytes())}
    }
    @Test fun enormousTrimmedTechnicalPrefixAndSuffixDoNotPublishEmptyResources() {
        val (_,source)=engine(" ".repeat(200000)+"汉😀保留"+" ".repeat(200000))
        source.use {TxtDisplayProjection.prepare(source,requireNotNull(source.displayDirectory)).use {projection ->
            val publication=TxtPublication(source,projection=projection)
            assertEquals(1,publication.sectionCount)
            val html=org.jsoup.Jsoup.parse(publication.section(0).html)
            assertEquals("汉😀保留",html.select("[data-block]").text());assertEquals(1,html.select("h2[data-generated]").size)
            for(offset in listOf(0L,199999L,200000L,400005L)) {
                val target=publication.target(Locator.Text(source.version,offset,0))
                assertEquals(0,target.section);assertTrue(requireNotNull(publication.locator(target.anchor)) is Locator.Text)
            }
            assertTrue(publication.section(0).utf16Characters<=TxtParser.MAX_BLOCK+1)
        }}
    }
    @Test fun canceledBuildRemovesOnlyOwnedStageAndLaterRebuildsCorruptDerivedFile() {
        val (_,source)=engine("  可读😀  \n".repeat(500))
        source.use {
            val directory=requireNotNull(source.displayDirectory);val unrelated=File(directory,"other.stage").apply {writeText("preserve")}
            var checks=0
            assertThrows(InterruptedException::class.java) {TxtDisplayProjection.prepare(source,directory) {if(++checks==9)throw InterruptedException("cancel")}}
            assertTrue(unrelated.isFile);assertTrue(directory.listFiles().orEmpty().none {it.name.startsWith(".${TxtDisplayProjection.ALGORITHM}-")})
            TxtDisplayProjection.prepare(source,directory).close()
            File(directory,"${TxtDisplayProjection.ALGORITHM}.bin").writeBytes(byteArrayOf(1,2,3))
            TxtDisplayProjection.prepare(source,directory).use {assertEquals(4,it.slice(0).displayedLength)}
        }
    }
    @Test fun twentyMegabyteOrdinaryAndMillionCharacterSingleParagraphRemainFixedRecordBounded() {
        val (original,source)=engine(("  "+"Ａ".repeat(2040)+" \n").repeat(3500)+" ".repeat(4096)+"汉".repeat(1_200_000)+" ".repeat(4096))
        assertTrue(original.length()>20*1024*1024)
        source.use {
            val directory=requireNotNull(source.displayDirectory)
            TxtDisplayProjection.prepare(source,directory).use {projection ->
                assertTrue(File(directory,"${TxtDisplayProjection.ALGORITHM}.bin").length()<source.index.paragraphs*37L+1024)
                val last=source.index.paragraphs-1
                val target=projection.target(ResolvedText(last,source.block(last).text.length,false))
                assertTrue(target.nearby);assertTrue(projection.slice(target.paragraph).displayedLength>0)
            }
        }
    }
}
