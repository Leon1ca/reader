package com.localreader.app.core

import java.io.*
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Streaming parser. No paragraph exceeds 2048 UTF-16 units (surrogate pairs stay intact). */
object TxtParser {
    const val RECORD_SIZE = 28L
    const val MAX_BLOCK = 2048
    const val MAX_BYTES = 128L * 1024 * 1024
    /** 4M records = ~112 MB of index; beyond this the file is pathological (e.g. millions of blank lines). */
    const val MAX_PARAGRAPHS = 4_000_000
    private const val SENTENCE_END = "。！？，；…"
    private val heading = Regex("^(第[零〇一二三四五六七八九十百千万0-9]+[章节卷部回].*|Chapter\\s+([0-9]+|[IVXLCDM]+|One|Two|Three|Four|Five|Six|Seven|Eight|Nine|Ten|Eleven|Twelve|Thirteen|Fourteen|Fifteen|Sixteen|Seventeen|Eighteen|Nineteen|Twenty|Thirty|Forty|Fifty|Hundred)\\b.*|序章|序言|前言|后记|尾声)$", RegexOption.IGNORE_CASE)
    val encodings = listOf("UTF-8", "UTF-16LE", "UTF-16BE", "GB18030", "Big5", "Shift_JIS", "ISO-8859-1")
    /** Strict single-byte-safe candidates tried in order after UTF-8; ISO-8859-1 maps every byte and never fails. */
    private val fallbacks = listOf("GB18030", "Big5", "Shift_JIS", "ISO-8859-1")

    fun detect(file: File, checkpoint: () -> Unit = {}): String {
        val bom = file.inputStream().use { prefix(it) }
        if (bom.size >= 2 && bom[0] == 0xff.toByte() && bom[1] == 0xfe.toByte()) return "UTF-16LE"
        if (bom.size >= 2 && bom[0] == 0xfe.toByte() && bom[1] == 0xff.toByte()) return "UTF-16BE"
        if (bom.size == 3 && bom[0] == 0xef.toByte() && bom[1] == 0xbb.toByte() && bom[2] == 0xbf.toByte()) return "UTF-8"
        if (valid(file, "UTF-8", checkpoint)) return "UTF-8"
        return fallbacks.firstOrNull { valid(file, it, checkpoint) } ?: "ISO-8859-1"
    }

    private fun valid(file: File, encoding: String, checkpoint: () -> Unit): Boolean = try {
        reader(file, encoding).use { input -> val buffer = CharArray(8192); while (input.read(buffer) >= 0) checkpoint() }; true
    } catch (_: java.nio.charset.CharacterCodingException) { false }

    private fun reader(file: File, encoding: String): Reader {
        require(encoding in encodings) { "不支持的编码" }
        val input = BufferedInputStream(file.inputStream())
        input.mark(3)
        val first = prefix(input)
        input.reset()
        val skip = when {
            encoding == "UTF-8" && first.size == 3 && first.contentEquals(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte())) -> 3
            encoding == "UTF-16LE" && first.size >= 2 && first[0] == 0xff.toByte() && first[1] == 0xfe.toByte() -> 2
            encoding == "UTF-16BE" && first.size >= 2 && first[0] == 0xfe.toByte() && first[1] == 0xff.toByte() -> 2
            else -> 0
        }
        repeat(skip) { input.read() }
        return InputStreamReader(input, Charset.forName(encoding).newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)).buffered()
    }

    private fun prefix(input: InputStream): ByteArray {
        val bytes = ByteArray(3); var count = 0
        while (count < bytes.size) { val value = input.read(); if (value < 0) break; bytes[count++] = value.toByte() }
        return bytes.copyOf(count)
    }

    fun build(source: File, directory: File, encoding: String = detect(source), checkpoint: () -> Unit = {}): TextIndex {
        require(source.length() in 1..MAX_BYTES) { "TXT 为空或超过 128 MB 限制" }
        val signature = source.inputStream().use { input -> ByteArray(80).let { bytes ->
            var count = 0
            while (count < bytes.size) { val next = input.read(); if (next < 0) break; bytes[count++] = next.toByte() }
            bytes.copyOf(count)
        } }
        val prefix = signature.take(8).toByteArray()
        require(!(signature.size >= 5 && signature.copyOfRange(0, 5).contentEquals("%PDF-".toByteArray())) &&
            !(signature.size >= 4 && signature.copyOfRange(0, 4).contentEquals(byteArrayOf(0x50, 0x4b, 3, 4))) &&
            !(signature.size >= 68 && signature.copyOfRange(60, 68).contentEquals("BOOKMOBI".toByteArray())) &&
            !(prefix.size >= 4 && prefix.copyOfRange(0, 4).contentEquals(byteArrayOf(-119, 80, 78, 71)))) {
            "实际内容是 PDF、压缩包、MOBI 或图片，当前只支持 TXT"
        }
        directory.mkdirs()
        var count = 0
        var offset = 0L
        var byteOffset = 0L
        var total = 0L
        var controls = 0L
        val chapters = mutableListOf<Chapter>()
        val sections = mutableListOf<Chapter>()
        var nextSection = 0L
        var continued = false
        var visible = false
        var pendingCR = false
        FileOutputStream(File(directory, "text.bin")).use { raw ->
            val out = BufferedOutputStream(raw)
            FileOutputStream(File(directory, "index.bin")).use { indexRaw ->
                val records = DataOutputStream(BufferedOutputStream(indexRaw))
                fun flush(text: String, consumed: Int, continues: Boolean = false) {
                    require(count < MAX_PARAGRAPHS) { "段落数超过 400 万，文件不像正常文本" }
                    val cleaned = text.removeSuffix("\r")
                    val isHeading = !continued && !continues && cleaned.length in 1..80 && heading.matches(cleaned.trim()) &&
                        cleaned.trim().last() !in SENTENCE_END
                    if (isHeading && chapters.size < 10000) chapters += Chapter(cleaned.trim(), count, offset)
                    if (offset >= nextSection && sections.size < 10000) {
                        sections += Chapter("分段 ${sections.size + 1}", count, offset); nextSection = offset + 40000
                    }
                    val bytes = cleaned.toByteArray(Charsets.UTF_8)
                    records.writeLong(byteOffset); records.writeInt(bytes.size); records.writeLong(offset)
                    records.writeInt(consumed); records.writeInt((if (isHeading) 1 else 0) or (if (continued) 2 else 0) or (if (continues) 4 else 0))
                    out.write(bytes)
                    byteOffset += bytes.size; offset += consumed; count++
                    continued = continues
                }
                reader(source, encoding).use { input ->
                    val text = StringBuilder(MAX_BLOCK + 1)
                    var consumed = 0
                    while (true) {
                        val char = input.read()
                        if (char < 0) break
                        // A lone CR (classic Mac line ending) ends the line; CRLF still ends at the LF.
                        if (pendingCR && char != 10) { flush(text.toString(), consumed); text.setLength(0); consumed = 0 }
                        pendingCR = false
                        total++; consumed++
                        if (!visible && !char.toChar().isWhitespace()) visible = true
                        if (total % 8192 == 0L) checkpoint()
                        require(char != 0) { "包含 NUL 字符，无法作为 TXT 导入；无 BOM 的 UTF-16 请先添加 BOM" }
                        // C0 controls, DEL and C1 controls (U+0080..U+009F) never occur in real text; many of them mean binary data,
                        // which matters for the ISO-8859-1 fallback because it can decode any byte sequence.
                        if ((char < 32 && char != 10 && char != 13 && char != 9) || char == 0x7f || char in 0x80..0x9f) controls++
                        require(controls <= 16 || controls * 100 <= total) { "包含大量二进制控制字符，无法作为 TXT 导入" }
                        if (char == 10) { flush(text.toString(), consumed); text.setLength(0); consumed = 0 }
                        else {
                            text.append(char.toChar())
                            if (char == 13) pendingCR = true
                            // Never split right after a CR: the CR ends the physical line, so the next character must start
                            // a new paragraph (not a continuation). The next iteration flushes it (or pairs it with LF).
                            if (text.length >= MAX_BLOCK && char != 13 && !Character.isHighSurrogate(text.last())) {
                                flush(text.toString(), consumed, true); text.setLength(0); consumed = 0; pendingCR = false
                            }
                        }
                    }
                    if (consumed > 0) flush(text.toString(), consumed)
                }
                require(count > 0 && visible) { "文件没有可读正文" }
                require(controls * 100 <= total) { "包含大量二进制控制字符，无法作为 TXT 导入" }
                records.flush(); indexRaw.fd.sync(); out.flush(); raw.fd.sync()
            }
        }
        val result = TextIndex(encoding, count, total, if (chapters.size < 2) sections else chapters)
        FileOutputStream(File(directory, "meta.bin")).use { raw ->
            val meta = DataOutputStream(raw)
            meta.writeInt(3); meta.writeUTF(encoding); meta.writeInt(count); meta.writeLong(total)
            meta.writeInt(result.chapters.size)
            result.chapters.forEach { meta.writeUTF(it.title); meta.writeInt(it.paragraph); meta.writeLong(it.sourceOffset) }
            meta.writeLong(File(directory,"text.bin").length());meta.writeLong(File(directory,"index.bin").length())
            meta.flush(); raw.fd.sync()
        }
        return result
    }
}

class TxtEngine(private val directory: File, override val version: String) : TxtTextSource {
    override val displayDirectory get()=directory
    private var formatVersion = 1
    private var expectedTextBytes: Long?=null
    private var expectedIndexBytes: Long?=null
    override val index: TextIndex = DataInputStream(File(directory, "meta.bin").inputStream().buffered()).use { input ->
        formatVersion = input.readInt()
        require(formatVersion in 1..3) { "未知 TXT 索引版本" }
        val encoding = input.readUTF(); val count = input.readInt(); val total = input.readLong()
        require(encoding in TxtParser.encodings && count in 1..TxtParser.MAX_PARAGRAPHS && total in 1..TxtParser.MAX_BYTES) { "TXT 索引头损坏" }
        val chapterCount=input.readInt();require(chapterCount in 0..10000)
        val chapters = List(chapterCount) { Chapter(input.readUTF(), input.readInt(), input.readLong()) }
        require(chapters.all { it.paragraph in 0 until count && it.sourceOffset in 0 until total })
        if(formatVersion>=3) {expectedTextBytes=input.readLong();expectedIndexBytes=input.readLong()}
        TextIndex(encoding, count, total, chapters)
    }
    private val text = RandomAccessFile(File(directory, "text.bin"), "r")
    private val textBytes=text.length()
    private val records = try { RandomAccessFile(File(directory, "index.bin"), "r") } catch (e: Exception) { text.close(); throw e }
    private val cache = object : LinkedHashMap<Int, TextBlock>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, TextBlock>?) = size > 64
    }
    private val mapped = try {
        require(records.length()==index.paragraphs*TxtParser.RECORD_SIZE && (expectedIndexBytes==null || expectedIndexBytes==records.length()) &&
            (expectedTextBytes==null || expectedTextBytes==textBytes)) { "TXT 索引长度不匹配" }
        records.channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY,0,records.length())
    } catch(error: Throwable) { records.close();text.close();throw error }
    /** Fixed-size mapped records avoid seek/read system calls; validate before every use. */
    private fun record(paragraph: Int): java.nio.ByteBuffer {
        require(paragraph in 0 until index.paragraphs)
        val value=mapped.asReadOnlyBuffer();value.position((paragraph*TxtParser.RECORD_SIZE).toInt())
        val record=value.slice();record.limit(TxtParser.RECORD_SIZE.toInt())
        val start=record.getLong(0);val length=record.getInt(8);val offset=record.getLong(12);val consumed=record.getInt(20);val flags=record.getInt(24)
        require(start>=0 && length in 0..(TxtParser.MAX_BLOCK+1)*4 && start<=textBytes-length && offset in 0 until index.totalCharacters &&
            consumed in 1..TxtParser.MAX_BLOCK+2 && consumed<=index.totalCharacters-offset && flags in 0..7) { "TXT 索引记录损坏" }
        if(paragraph==0)require(start==0L && offset==0L) { "TXT 索引首记录损坏" }
        else {
            val prior=(paragraph-1)*TxtParser.RECORD_SIZE.toInt()
            val previousStart=mapped.getLong(prior);val previousLength=mapped.getInt(prior+8)
            val previousOffset=mapped.getLong(prior+12);val previousConsumed=mapped.getInt(prior+20)
            require(previousStart>=0 && previousLength in 0..(TxtParser.MAX_BLOCK+1)*4 && previousStart+previousLength==start &&
                previousOffset>=0 && previousConsumed in 1..TxtParser.MAX_BLOCK+2 && previousOffset+previousConsumed==offset) { "TXT 索引记录顺序损坏" }
        }
        if(paragraph==index.paragraphs-1)require(start+length==textBytes && offset+consumed==index.totalCharacters) { "TXT 索引末记录损坏" }
        return record
    }
    @Synchronized override fun block(paragraph: Int): TextBlock {
        require(paragraph in 0 until index.paragraphs)
        cache[paragraph]?.let { return it }
        val r = record(paragraph)
        val start = r.getLong(0); val length = r.getInt(8); val sourceOffset = r.getLong(12)
        val consumed = r.getInt(20); val flags = r.getInt(24)
        require(length in 0..(TxtParser.MAX_BLOCK + 1) * 4) { "索引数据损坏" }
        val bytes = ByteArray(length); text.seek(start); text.readFully(bytes)
        val content = bytes.toString(Charsets.UTF_8)
        var continuation = flags and 2 != 0
        var continues = flags and 4 != 0
        if (formatVersion == 1) {
            continues = content.length >= TxtParser.MAX_BLOCK && consumed == content.length
            if (paragraph > 0) {
                val p = record(paragraph - 1)
                val previousStart = p.getLong(0); val previousLength = p.getInt(8)
                val previousConsumed = p.getInt(20)
                require(previousLength in 0..(TxtParser.MAX_BLOCK + 1) * 4) { "索引数据损坏" }
                val previousBytes = ByteArray(previousLength); text.seek(previousStart); text.readFully(previousBytes)
                val previousTextLength = previousBytes.toString(Charsets.UTF_8).length
                continuation = previousTextLength >= TxtParser.MAX_BLOCK && previousConsumed == previousTextLength
            }
        }
        return TextBlock(paragraph, content, sourceOffset, flags and 1 != 0, continuation, continues).also { cache[paragraph] = it }
    }
    @Synchronized override fun resolve(locator: Locator): ResolvedText {
        require(locator is Locator.Text) { "TXT 引擎不能使用此格式的位置" }
        val last = (index.totalCharacters - 1).coerceAtLeast(0)
        // An offset recorded against another index version may mean a different character after re-decoding.
        val foreign = locator.version != version
        val outside = locator.sourceOffset !in 0..last
        val target = if ((outside || foreign) && locator.fallbackFraction.isFinite() && locator.fallbackFraction in 0.0..1.0)
            (locator.fallbackFraction * last).toLong() else locator.sourceOffset.coerceIn(0, last)
        var low = 0; var high = index.paragraphs - 1
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (record(mid).getLong(12) <= target) low = mid else high = mid - 1
        }
        val block = block(low)
        return ResolvedText(low, (target - block.sourceOffset).toInt().coerceIn(0, block.text.length), locator.version != version || outside)
    }
    @Synchronized override fun metadata(paragraph: Int): TxtBlockMetadata {
        require(paragraph in 0 until index.paragraphs)
        if (formatVersion == 1) return super.metadata(paragraph)
        val r = record(paragraph)
        val length = r.getInt(8); val flags = r.getInt(24)
        require(length in 0..(TxtParser.MAX_BLOCK + 1) * 4) { "索引数据损坏" }
        return TxtBlockMetadata(length,flags and 2 != 0,r.getLong(12))
    }
    @Synchronized override fun close() { text.close(); records.close(); cache.clear() }
}
