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
        listOf(byteArrayOf(), byteArrayOf(0, 1, 2, 3, 0, 65), byteArrayOf(-1), "%PDF-1.7\u0000binary".toByteArray()).forEach { bytes ->
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
