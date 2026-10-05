package com.localreader.app.core.search

import android.icu.lang.UCharacter
import android.icu.lang.UProperty
import android.icu.text.CaseMap
import android.icu.text.Edits
import android.icu.text.Normalizer2
import java.io.File
import java.util.Locale
import java.util.UUID

/** Whole logical NFKC → ROOT lower → NFKC, with bounded working buffers and disk maps. */
class UnicodeSearchNormalizer(private val directory: File, private val readSize: Int = 16384,
                              private val lowerSize: Int = 16384, private val unitLimit: Int = 65536,
                              private val tapeLimit: Long = 256L * 1024 * 1024) {
    init { require(readSize in 1..65536 && lowerSize in 1..32768 && unitLimit in 2..65536 && tapeLimit > 0) }
    private interface Input {
        val length: Long
        fun slice(start: Long, count: Int): String
        fun inverse(start: Long, end: Long): SourceRange
        fun runAt(start: Long): MappingRun
    }
    private class Original(private val document: CanonicalDocument) : Input {
        private val run = MappingRun(0, document.length, SourceRange(0, document.length), true)
        override val length get() = document.length
        override fun slice(start: Long, count: Int) = document.slice(start, count).also {
            check(it.length.toLong() == minOf(count.toLong(), length - start)) { "正文搜索游标返回长度不符" }
        }
        override fun inverse(start: Long, end: Long) = SourceRange(start, end)
        override fun runAt(start: Long) = run
    }
    private class TapeInput(private val tape: MappedTape, private val checkpoint: () -> Unit) : Input {
        override val length get() = tape.length
        override fun slice(start: Long, count: Int) = tape.slice(start, count)
        override fun inverse(start: Long, end: Long) = tape.inverse(start, end, checkpoint)
        override fun runAt(start: Long) = tape.runAt(start)
    }
    private class Scalars(private val input: Input, private val size: Int) {
        private var start = -1L
        private var buffer = ""
        fun char(at: Long): Char {
            check(at in 0 until input.length)
            if (at < start || at >= start + buffer.length) { start = at;buffer = input.slice(at, size) }
            return buffer[(at - start).toInt()]
        }
        fun point(at: Long): Int {
            val first = char(at)
            return if (first.isHighSurrogate() && at + 1 < input.length && char(at + 1).isLowSurrogate())
                Character.toCodePoint(first, char(at + 1)) else first.code
        }
    }
    fun normalize(document: CanonicalDocument, checkpoint: () -> Unit = {}): NormalizedDocument {
        require(document.length >= 0)
        val work = File(directory, "search-${UUID.randomUUID()}")
        check(work.mkdirs()) { "无法创建搜索临时目录" }
        val tapes = mutableListOf<MappedTape>()
        try {
            val first = TapeWriter(work, "compatibility", tapeLimit).use { writer ->
                nfkc(Original(document), writer, checkpoint);writer.finish()
            }.also(tapes::add)
            val lower = TapeWriter(work, "lower", tapeLimit).use { writer ->
                lowercase(TapeInput(first, checkpoint), writer, checkpoint);writer.finish()
            }.also(tapes::add)
            val result = TapeWriter(work, "final", tapeLimit).use { writer ->
                nfkc(TapeInput(lower, checkpoint), writer, checkpoint);writer.finish()
            }.also(tapes::add)
            checkpoint()
            first.close();lower.close();tapes.clear()
            File(work, "compatibility.u16").delete();File(work, "compatibility.map").delete()
            File(work, "lower.u16").delete();File(work, "lower.map").delete()
            return NormalizedDocument(result, work)
        } catch (error: Throwable) {
            tapes.forEach { it.close() };work.deleteRecursively();throw error
        }
    }
    /** Query is small; source coordinates only apply to the document, never the query. */
    fun query(value: String): String {
        require(value.length <= 4096)
        val nfkc = Normalizer2.getNFKCInstance()
        val input = nfkc.normalize(value)
        val edits = Edits();val replacements = StringBuilder();val output = StringBuilder()
        CaseMap.toLower().omitUnchangedText().apply(Locale.ROOT, input, replacements, edits)
        val iterator = edits.fineIterator
        while (iterator.next()) {
            if (iterator.hasChange()) output.append(replacements, iterator.replacementIndex(), iterator.replacementIndex() + iterator.newLength())
            else output.append(input, iterator.sourceIndex(), iterator.sourceIndex() + iterator.oldLength())
        }
        val result = nfkc.normalize(output)
        require(result.length <= 4096) { "搜索词规范化后过长" }
        return result
    }
    private fun copyMapped(value: String, input: Input, start: Long, writer: TapeWriter) {
        var at = start;var offset = 0
        while (offset < value.length) {
            val run = input.runAt(at)
            val count = minOf((run.end - at).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), value.length - offset)
            val source = if (run.linear) SourceRange(run.source.start + at - run.start, run.source.start + at - run.start + count) else run.source
            writer.append(value.substring(offset, offset + count), source, run.linear)
            at += count;offset += count
        }
    }
    private fun nfkc(input: Input, writer: TapeWriter, checkpoint: () -> Unit) {
        val normalizer = Normalizer2.getNFKCInstance()
        val cursor = Scalars(input, readSize)
        val pending = StringBuilder()
        val direct = StringBuilder()
        var pendingStart = 0L;var directStart = 0L
        fun flushDirect() { if (direct.isNotEmpty()) { copyMapped(direct.toString(), input, directStart, writer);direct.setLength(0) } }
        fun flushUnit() {
            if (pending.isEmpty()) return
            val original = pending.toString()
            val value = normalizer.normalize(original)
            if (value.length > unitLimit * 4) throw SearchResourceLimit("搜索规范化单元展开超过资源预算")
            val scalarPreserving = original.length == value.length && original.codePointCount(0, original.length) == 1 && value.codePointCount(0, value.length) == 1
            if (value == original || scalarPreserving) copyMapped(value, input, pendingStart, writer)
            else writer.append(value, input.inverse(pendingStart, pendingStart + original.length), false)
            pending.setLength(0)
        }
        var at = 0L;var steps = 0
        while (at < input.length) {
            if (++steps % 4096 == 0) checkpoint()
            val point = cursor.point(at);val units = Character.charCount(point)
            if (normalizer.hasBoundaryBefore(point)) flushUnit()
            if (pending.isEmpty() && normalizer.isInert(point)) {
                if (direct.isEmpty()) directStart = at
                direct.appendCodePoint(point)
                if (direct.length >= 16384) flushDirect()
            } else {
                flushDirect()
                if (pending.isEmpty()) pendingStart = at
                if (pending.length + units > unitLimit) throw SearchResourceLimit("搜索规范化组合单元过长")
                pending.appendCodePoint(point)
            }
            at += units
        }
        flushDirect();flushUnit();checkpoint()
    }
    private fun lowercase(input: Input, writer: TapeWriter, checkpoint: () -> Unit) {
        val cursor = Scalars(input, readSize)
        var at = 0L;var leftCased = false
        var nextSignificant = -1L;var nextCased = false
        fun rightCased(end: Long): Boolean {
            if (nextSignificant >= end) return nextCased
            var probe = end;var steps = 0
            while (probe < input.length) {
                if (++steps % 4096 == 0) checkpoint()
                val point = cursor.point(probe)
                if (!UCharacter.hasBinaryProperty(point, UProperty.CASE_IGNORABLE)) {
                    nextSignificant = probe;nextCased = UCharacter.hasBinaryProperty(point, UProperty.CASED);return nextCased
                }
                probe += Character.charCount(point)
            }
            nextSignificant = input.length;nextCased = false;return false
        }
        while (at < input.length) {
            checkpoint()
            var end = minOf(input.length, at + lowerSize)
            if (end < input.length && cursor.char(end - 1).isHighSurrogate() && cursor.char(end).isLowSurrogate())
                end += if (end == at + 1) 1 else -1
            val core = input.slice(at, (end - at).toInt())
            val withContext = (if (leftCased) "A" else ".") + core + (if (rightCased(end)) "A" else ".")
            val edits = Edits();val replacements = StringBuilder()
            // Android 10's full Appendable overload places an expansion before preceding
            // unchanged text. Replacement-only output and Edits retain the correct order.
            CaseMap.toLower().omitUnchangedText().apply(Locale.ROOT, withContext, replacements, edits)
            val iterator = edits.fineIterator
            while (iterator.next()) {
                val oldStart = iterator.sourceIndex();val oldEnd = oldStart + iterator.oldLength()
                val begin = maxOf(1, oldStart);val stop = minOf(core.length + 1, oldEnd)
                if (begin >= stop) continue
                if (!iterator.hasChange()) {
                    val part = withContext.substring(begin, stop)
                    copyMapped(part, input, at + begin - 1, writer)
                } else if (iterator.oldLength() == iterator.newLength()) {
                    // ROOT casing does not reorder scalars. Equal-length fine changes preserve units.
                    val dest = iterator.replacementIndex()
                    val part = replacements.substring(dest + begin - oldStart, dest + stop - oldStart)
                    copyMapped(part, input, at + begin - 1, writer)
                } else {
                    check(begin == oldStart && stop == oldEnd) { "大小写上下文边界不可逆映射" }
                    val dest = iterator.replacementIndex()
                    writer.append(replacements.substring(dest, dest + iterator.newLength()), input.inverse(at + begin - 1, at + stop - 1), false)
                }
            }
            var probe = at;var steps = 0
            while (probe < end) {
                if (++steps % 4096 == 0) checkpoint()
                val point = cursor.point(probe)
                if (!UCharacter.hasBinaryProperty(point, UProperty.CASE_IGNORABLE)) leftCased = UCharacter.hasBinaryProperty(point, UProperty.CASED)
                probe += Character.charCount(point)
            }
            at = end
        }
    }
}
