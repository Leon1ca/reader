package com.localreader.app.core.search

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile

internal data class MappingRun(val start: Long, val end: Long, val source: SourceRange, val linear: Boolean)

/** Fixed-width records on disk; no per-character object index is retained in memory. */
internal class MappedTape(private val textFile: File, private val mapFile: File, val length: Long) : Closeable {
    private val text = RandomAccessFile(textFile, "r")
    private val map = RandomAccessFile(mapFile, "r")
    private val records = map.length() / 40
    private var cached: MappingRun? = null
    private var cachedRow = -1L
    @Synchronized fun slice(start: Long, count: Int): String {
        require(start in 0..length && count in 0..65536)
        val size = minOf(count.toLong(), length - start).toInt()
        val bytes = ByteArray(size * 2)
        text.seek(start * 2);text.readFully(bytes)
        return String(CharArray(size) { i -> ((bytes[i * 2].toInt() and 255) or ((bytes[i * 2 + 1].toInt() and 255) shl 8)).toChar() })
    }
    private fun row(index: Long): MappingRun {
        map.seek(index * 40)
        return MappingRun(map.readLong(), map.readLong(), SourceRange(map.readLong(), map.readLong()), map.readLong() == 1L)
    }
    @Synchronized fun runAt(at: Long): MappingRun {
        require(at in 0 until length)
        cached?.takeIf { at >= it.start && at < it.end }?.let { return it }
        if (cached?.end == at && cachedRow + 1 < records) {
            cachedRow++
            return row(cachedRow).also { check(at >= it.start && at < it.end);cached = it }
        }
        var low = 0L;var high = records
        while (low < high) {
            val middle = (low + high) ushr 1
            if (row(middle).end <= at) low = middle + 1 else high = middle
        }
        cachedRow = low
        return row(low).also { check(at >= it.start && at < it.end);cached = it }
    }
    fun inverse(start: Long, end: Long, checkpoint: () -> Unit = {}): SourceRange {
        require(start >= 0 && end > start && end <= length)
        var at = start;var first = Long.MAX_VALUE;var last = 0L;var steps = 0
        while (at < end) {
            if (++steps % 256 == 0) checkpoint()
            val run = runAt(at);val stop = minOf(end, run.end)
            val sourceStart = if (run.linear) run.source.start + at - run.start else run.source.start
            val sourceEnd = if (run.linear) run.source.start + stop - run.start else run.source.end
            first = minOf(first, sourceStart);last = maxOf(last, sourceEnd);at = stop
        }
        return SourceRange(first, last)
    }
    override fun close() { text.close();map.close() }
}

internal class TapeWriter(directory: File, name: String, private val byteLimit: Long) : Closeable {
    private val textFile = File(directory, "$name.u16")
    private val mapFile = File(directory, "$name.map")
    private val text = BufferedOutputStream(textFile.outputStream(), 65536)
    private val map = DataOutputStream(BufferedOutputStream(mapFile.outputStream(), 65536))
    private var pending: MappingRun? = null
    private var rows = 0L
    var length = 0L;private set
    private var closed = false
    fun append(value: String, source: SourceRange, linear: Boolean) {
        if (value.isEmpty()) return
        require(source.start < source.end && (!linear || source.end - source.start == value.length.toLong()))
        if ((length + value.length) * 2 + (rows + 2) * 40 > byteLimit) throw SearchResourceLimit("搜索临时索引超过资源预算")
        for (char in value) { text.write(char.code and 255);text.write(char.code ushr 8) }
        val next = MappingRun(length, length + value.length, source, linear)
        val old = pending
        val merge = old != null && old.linear == next.linear &&
            (if (linear) old.source.end == source.start else old.source == source)
        pending = if (merge) old!!.copy(end = next.end, source = SourceRange(old.source.start, source.end))
            else { flushRun();next }
        length = next.end
    }
    private fun flushRun() {
        pending?.let { map.writeLong(it.start);map.writeLong(it.end);map.writeLong(it.source.start);map.writeLong(it.source.end);map.writeLong(if (it.linear) 1 else 0);rows++ }
        pending = null
    }
    fun finish(): MappedTape { close();return MappedTape(textFile, mapFile, length) }
    override fun close() { if (!closed) { closed = true;flushRun();try { text.close() } finally { map.close() } } }
}
