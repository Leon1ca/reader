package com.localreader.app.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.charset.Charset

class TxtEngineTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun parse(bytes: ByteArray, encoding: String? = null): TxtEngine {
        val source = temporary.newFile().apply { writeBytes(bytes) }
        val directory = temporary.newFolder()
        if (encoding == null) TxtParser.build(source, directory) else TxtParser.build(source, directory, encoding)
        return TxtEngine(directory, "version-a")
    }
    @Test fun mappedIndexRejectsTruncationAndOutOfBoundsRecords() {
        val source=temporary.newFile().apply { writeText("第一章 开始\n正文\n第二章 继续") }
        val directory=temporary.newFolder();TxtParser.build(source,directory)
        val index=java.io.File(directory,"index.bin")
        java.io.RandomAccessFile(index,"rw").use { it.setLength(it.length()-1) }
        assertThrows(IllegalArgumentException::class.java) { TxtEngine(directory,"v") }
        TxtParser.build(source,directory)
        java.io.RandomAccessFile(index,"rw").use { it.writeLong(Long.MAX_VALUE) }
        TxtEngine(directory,"v").use { engine -> assertThrows(IllegalArgumentException::class.java) { engine.block(0) } }
    }
    @Test fun oldVersionTwoIndexesStillOpenAndSectionCacheRecoversFromCorruption() {
        val source=temporary.newFile().apply { writeText("正文。".repeat(40000)) }
        val directory=temporary.newFolder();TxtParser.build(source,directory)
        java.io.RandomAccessFile(java.io.File(directory,"meta.bin"),"rw").use { meta -> meta.writeInt(2);meta.setLength(meta.length()-16) }
        TxtEngine(directory,"v").use { engine ->
            val first=TxtPublication(engine);val expected=(0 until first.sectionCount).map { first.section(it).html }
            val cache=java.io.File(directory,"txt-sections-v2-raw.bin");assertTrue(cache.isFile)
            cache.writeBytes(byteArrayOf(1,2,3))
            val reopened=TxtPublication(engine)
            assertEquals(expected,(0 until reopened.sectionCount).map { reopened.section(it).html })
            val correct=cache.readBytes()
            java.io.RandomAccessFile(cache,"rw").use { file ->
                file.readInt();file.readUTF();file.readInt();file.readLong();file.readInt()
                file.readInt();file.readInt();file.readLong();val position=file.filePointer;val empty=file.readBoolean()
                file.seek(position);file.writeBoolean(!empty) // A valid field change must fail the checksum.
            }
            TxtPublication(engine)
            assertArrayEquals(correct,cache.readBytes())
        }
    }
    @Test fun commonEncodingsProduceSameReadableContent() {
        val expected = "第一章 开始\n你好，世界。\n第二章 回来\n终章"
        listOf("UTF-8", "UTF-16LE", "UTF-16BE", "GB18030").forEach { encoding ->
            val bom = when (encoding) { "UTF-16LE" -> byteArrayOf(-1, -2); "UTF-16BE" -> byteArrayOf(-2, -1); "UTF-8" -> byteArrayOf(-17, -69, -65); else -> byteArrayOf() }
            parse(bom + expected.toByteArray(Charset.forName(encoding))).use { engine ->
                assertEquals(encoding, engine.index.encoding)
                assertEquals(expected, (0 until engine.index.paragraphs).joinToString("\n") { engine.block(it).text })
                assertEquals(2, engine.index.chapters.size)
            }
        }
    }
    @Test fun longParagraphsPreserveSurrogatesAndStableOffsets() {
        val text = "序言\r\n" + "汉😀".repeat(6000) + "\n\n结束"
        parse(text.toByteArray()).use { engine ->
            assertTrue(engine.index.paragraphs > 5)
            var lastOffset = -1L
            repeat(engine.index.paragraphs) { paragraph ->
                val block = engine.block(paragraph)
                assertTrue(block.text.length <= TxtParser.MAX_BLOCK + 1)
                assertFalse(block.text.lastOrNull()?.let(Character::isHighSurrogate) ?: false)
                assertTrue(block.sourceOffset > lastOffset); lastOffset = block.sourceOffset
                val character = (block.text.length / 2).coerceAtLeast(0)
                val resolved = engine.resolve(Locator.Text("version-a", block.sourceOffset + character, paragraph, character))
                assertEquals(paragraph, resolved.paragraph); assertEquals(character, resolved.character)
            }
            val content = (1 until engine.index.paragraphs - 2).joinToString("") { engine.block(it).text }
            assertEquals("汉😀".repeat(6000), content)
        }
    }
    @Test fun corruptedAndBinaryDocumentsAreRejected() {
        listOf(
            byteArrayOf(),
            byteArrayOf(0, 1, 2, 3, 0, 65),
            // 0xFF rejects multibyte decoders; Latin-1 produces mostly C1 controls.
            ByteArray(64) { if (it == 0) 0xFF.toByte() else (0x80 + it % 32).toByte() },
            "%PDF-1.7\u0000binary".toByteArray()
        ).forEach { bytes ->
            val source = temporary.newFile().apply { writeBytes(bytes) }
            assertThrows(Exception::class.java) { TxtParser.build(source, temporary.newFolder()) }
        }
    }
    @Test fun absentHeadingsUseHonestBoundedSectionsAndNearbyFallback() {
        parse("正文。".repeat(30000).toByteArray()).use { engine ->
            assertTrue(engine.index.chapters.size >= 2)
            assertTrue(engine.index.chapters.first().title.startsWith("分段"))
            val resolved = engine.resolve(Locator.Text("old-version", Long.MAX_VALUE, 999999, fallbackFraction = 1.0))
            assertTrue(resolved.nearby); assertEquals(engine.index.paragraphs - 1, resolved.paragraph)
            val halfway = engine.resolve(Locator.Text("old-version", Long.MAX_VALUE, 999999, fallbackFraction = 0.5))
            assertTrue(halfway.nearby); assertEquals(((engine.index.totalCharacters - 1) / 2 / TxtParser.MAX_BLOCK).toInt(), halfway.paragraph)
            assertThrows(IllegalArgumentException::class.java) { engine.resolve(Locator.Page("v", 2, 0.0, 0.0)) }
        }
    }
    @Test fun undecodableBytesFallBackThroughLegacyEncodingsInsteadOfFailing() {
        // Truncated multi-byte lead byte is invalid UTF-8 and also invalid GB18030/Big5/Shift_JIS; Latin-1 always decodes.
        val source = temporary.newFile().apply { writeBytes("caf".toByteArray() + byteArrayOf(0xE9.toByte())) }
        assertEquals("ISO-8859-1", TxtParser.detect(source))
        val gb = temporary.newFile().apply { writeBytes("第一章 开始\n你好".toByteArray(Charset.forName("GB18030"))) }
        assertEquals("GB18030", TxtParser.detect(gb))
        TxtParser.build(source, temporary.newFolder(), "ISO-8859-1")
        // A lone 0xFF is a legal Latin-1 character, not corruption; only control-heavy data is rejected.
        val single = temporary.newFile().apply { writeBytes(byteArrayOf(-1)) }
        assertEquals("ISO-8859-1", TxtParser.detect(single))
        TxtParser.build(single, temporary.newFolder(), "ISO-8859-1")
    }
    @Test fun carriageReturnAtTheBlockLimitStillStartsANewParagraph() {
        listOf(2046, 2047, 2048).forEach { n ->
            listOf("\r" to 1, "\r\n" to 2).forEach { (eol, width) ->
                val text = "a".repeat(n) + eol + "第二章 回来\n正文"
                parse(text.toByteArray()).use { engine ->
                    val heading = (0 until engine.index.paragraphs).map(engine::block).single { it.text == "第二章 回来" }
                    assertFalse("n=$n eol=${eol.length}", heading.continuation)
                    assertTrue("n=$n eol=${eol.length}", heading.heading)
                    assertEquals((n + width).toLong(), heading.sourceOffset)
                    assertEquals(text.length.toLong(), engine.index.totalCharacters)
                    if (n < 2048) {
                        val html = TxtPublication(engine).section(0).html
                        assertTrue("n=$n eol=${eol.length}", Regex("</span></p><h2[^>]*><span[^>]*>第二章 回来").containsMatchIn(html))
                    }
                }
            }
        }
    }
    @Test fun anchorsFromAnotherIndexVersionResolveByFractionNotByStaleOffset() {
        parse("正文。".repeat(30000).toByteArray()).use { engine ->
            val stale = Locator.Text("old-version", 0, 0, 0, fallbackFraction = 0.5)
            val resolved = engine.resolve(stale)
            assertTrue(resolved.nearby)
            assertEquals(((engine.index.totalCharacters - 1) / 2 / TxtParser.MAX_BLOCK).toInt(), resolved.paragraph)
            // The same offset under the current version is trusted exactly.
            assertEquals(0, engine.resolve(Locator.Text("version-a", 0, 0, 0, fallbackFraction = 0.5)).paragraph)
        }
    }
    @Test fun loneCarriageReturnsSplitLinesAndKeepOffsetsConsistent() {
        val text = "第一章 开始\r正文一\r第二章 回来\r正文二\r\n结尾"
        parse(text.toByteArray()).use { engine ->
            assertEquals(5, engine.index.paragraphs)
            assertEquals(listOf("第一章 开始", "正文一", "第二章 回来", "正文二", "结尾"), (0 until 5).map { engine.block(it).text })
            assertEquals(2, engine.index.chapters.size)
            assertEquals(text.length.toLong(), engine.index.totalCharacters)
            var expected = 0L
            (0 until 5).forEach { assertEquals(expected, engine.block(it).sourceOffset); expected += listOf(7, 4, 7, 5, 2)[it] }
        }
    }
    @Test fun whitespaceOnlyFilesAreRejected() {
        listOf("\n", "\n\n  \r\n\t", "\u3000\u3000\n").forEach { content ->
            val source = temporary.newFile().apply { writeText(content) }
            assertThrows(IllegalArgumentException::class.java) { TxtParser.build(source, temporary.newFolder()) }
        }
    }
    @Test fun headingRulesRejectSentencesAndBareChapterWords() {
        val text = "第一节课我就迟到了。\nChapter and verse\n正文。\nChapter 3 The Road\n第三章 归来\n正文。"
        parse(text.toByteArray()).use { engine ->
            assertEquals(listOf("Chapter 3 The Road", "第三章 归来"), engine.index.chapters.map { it.title })
        }
    }
    @Test fun aSingleHeadingFallsBackToSectionsInsteadOfAOneEntryContents() {
        parse(("序言\n" + "正文。".repeat(30000)).toByteArray()).use { engine ->
            assertTrue(engine.index.chapters.size >= 2)
            assertTrue(engine.index.chapters.first().title.startsWith("分段"))
        }
    }
    @Test fun twentyMegabyteSourceBuildsWithoutOneHugeBlock() {
        val source = temporary.newFile()
        source.bufferedWriter().use { writer -> repeat(11000) { writer.write("a".repeat(2048)); writer.newLine() } }
        val directory = temporary.newFolder(); TxtParser.build(source, directory)
        TxtEngine(directory, "v").use { engine ->
            assertTrue(source.length() >= 20 * 1024 * 1024)
            assertTrue(engine.index.paragraphs >= 11000)
            assertTrue(engine.block(engine.index.paragraphs / 2).text.length <= TxtParser.MAX_BLOCK + 1)
            assertEquals("a".repeat(2048), engine.block(0).text)
        }
    }
    @Test fun manualDecodeFailureDoesNotRewriteSource() {
        val source = temporary.newFile().apply { writeBytes("a中文".toByteArray()) }
        val old = source.readBytes()
        assertThrows(Exception::class.java) { TxtParser.build(source, temporary.newFolder(), "UTF-16LE") }
        assertArrayEquals(old, source.readBytes())
    }
}
