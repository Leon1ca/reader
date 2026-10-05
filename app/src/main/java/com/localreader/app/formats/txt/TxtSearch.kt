package com.localreader.app.formats.txt

import com.localreader.app.core.*
import com.localreader.app.core.search.*
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Original-file decoding preserves CR/LF/CRLF that are absent from display blocks. */
internal fun txtSearch(source: TxtTextSource, original: File, root: File, active: () -> Unit): BookSearch =
    BookSearch(root, source.version, 1, { _, directory, checkpoint ->
        val file = File(directory, "txt-canonical.u16")
        var decodedLength = 0L
        original.inputStream().buffered().use { input ->
            input.mark(3)
            val prefix = ByteArray(3);var read = 0
            while (read < 3) { val next = input.read();if (next < 0) break;prefix[read++] = next.toByte() }
            input.reset()
            val encoding = source.index.encoding
            val skip = when {
                encoding == "UTF-8" && read == 3 && prefix.contentEquals(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte())) -> 3
                encoding == "UTF-16LE" && read >= 2 && prefix[0] == 0xff.toByte() && prefix[1] == 0xfe.toByte() -> 2
                encoding == "UTF-16BE" && read >= 2 && prefix[0] == 0xfe.toByte() && prefix[1] == 0xff.toByte() -> 2
                else -> 0
            }
            repeat(skip) { check(input.read() >= 0) }
            val decoder = Charset.forName(encoding).newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            java.io.InputStreamReader(input, decoder).use { reader ->
                file.outputStream().buffered(65536).use { output ->
                    val chars = CharArray(16384);val bytes = ByteArray(chars.size * 2)
                    while (true) {
                        checkpoint();val count = reader.read(chars);if (count < 0) break
                        decodedLength += count;check(decodedLength <= source.index.totalCharacters) { "TXT 原件与当前文本版本不符" }
                        repeat(count) { i -> bytes[i * 2] = chars[i].code.toByte();bytes[i * 2 + 1] = (chars[i].code ushr 8).toByte() }
                        output.write(bytes, 0, count * 2)
                    }
                }
            }
        }
        check(decodedLength == source.index.totalCharacters) { "TXT 搜索文本长度与阅读索引不符" }
        val input = RandomAccessFile(file, "r")
        object : SearchDocument {
            override val id = "txt"
            override val length = decodedLength
            override fun slice(start: Long, count: Int): String {
                active();require(start in 0..length && count in 0..65536)
                val size = minOf(count.toLong(), length - start).toInt();val bytes = ByteArray(size * 2)
                input.seek(start * 2);input.readFully(bytes)
                return String(CharArray(size) { i -> ((bytes[i * 2].toInt() and 255) or ((bytes[i * 2 + 1].toInt() and 255) shl 8)).toChar() })
            }
            private fun resolved(at: Long) = source.resolve(Locator.Text(source.version, at, 0))
            override fun permits(range: SourceRange): Boolean {
                active();var cursor = range.start
                while (cursor < range.end) { checkpoint();val text = slice(cursor, minOf(16384L, range.end - cursor).toInt());if (text.any { it == '\r' || it == '\n' }) return false;cursor += text.length }
                val resolved = resolved(range.start)
                var paragraph = resolved.paragraph;var block = source.block(paragraph)
                if (range.start < block.sourceOffset || range.start >= block.sourceOffset + block.text.length) return false
                while (range.end > block.sourceOffset + block.text.length) {
                    checkpoint()
                    if (!block.continues || ++paragraph >= source.index.paragraphs) return false
                    val next = source.block(paragraph)
                    if (!next.continuation || next.sourceOffset != block.sourceOffset + block.text.length) return false
                    block = next
                }
                return true
            }
            override fun locator(range: SourceRange): Locator {
                val resolved = resolved(range.start);val block = source.block(resolved.paragraph)
                return Locator.Text(source.version, range.start, resolved.paragraph, (range.start - block.sourceOffset).toInt(),
                    (range.start.toDouble() / (source.index.totalCharacters - 1).coerceAtLeast(1)).coerceIn(0.0, 1.0))
            }
            override fun chapter(range: SourceRange) = source.index.chapters.lastOrNull { it.sourceOffset <= range.start }?.title.orEmpty()
            override fun close() { input.close();file.delete() }
        }
    }, active)
