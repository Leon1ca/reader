package com.localreader.app.formats.txt

import com.localreader.app.core.*
import java.util.concurrent.atomic.AtomicBoolean
import java.io.File

data class TxtReadingExtent(val revision: String, val sourceOffset: Long) : ReadingExtent

class TxtOpenedBook(override val contentId: String, override val metadata: ContentMetadata,
                    private val source: TxtTextSource, original: File? = null, searchRoot: File? = null, checkpoint: () -> Unit = {}) : OpenedBook {
    private val closed = AtomicBoolean(false)
    override val format = ContentFormat.TXT
    override val revision = source.version
    override val publication: ReaderPublication = TxtPublication(source, checkpoint)
    private val chapters = source.index.chapters
    private val entries = chapters.map { TocEntry("text:${it.sourceOffset}", it.title) }
    override val textControls = TextControls(source.index.encoding, TxtParser.encodings.toList(),
        if (chapters.firstOrNull()?.title?.startsWith("分段 ") == true) TocSource.TEXT_SEGMENTS else TocSource.CHAPTERS)
    private fun checkOpen() { check(!closed.get()) { "正文资源已关闭" } }
    override val search = if (original != null && searchRoot != null) txtSearch(source, original, searchRoot, ::checkOpen) else null
    override fun toc(): List<TocEntry> = entries
    override fun locatorForToc(id: String): Locator? {
        checkOpen()
        val index = entries.indexOfFirst { it.id == id }
        return chapters.getOrNull(index)?.let { Locator.Text(revision,it.sourceOffset,it.paragraph) }
    }
    override fun chapterOf(locator: Locator): TocEntry? {
        checkOpen()
        val resolved = source.resolve(locator)
        return entries.getOrNull(chapters.indexOfLast { it.paragraph <= resolved.paragraph })
    }
    override fun fractionOf(locator: Locator): Double {
        checkOpen()
        val offset = if (locator is Locator.Text && locator.version == revision && locator.sourceOffset in 0 until source.index.totalCharacters) locator.sourceOffset
            else source.resolve(locator).let { source.block(it.paragraph).sourceOffset + it.character }
        return (offset.toDouble() / (source.index.totalCharacters - 1).coerceAtLeast(1)).coerceIn(0.0,1.0)
    }
    override fun excerpt(locator: Locator, maxChars: Int): String {
        checkOpen(); require(maxChars in 0..120)
        val resolved = source.resolve(locator)
        val text = source.block(resolved.paragraph).text
        var start = resolved.character
        // Skip the rest of a pair when a UTF-16 locator points inside it; do not alter the locator itself.
        if (start > 0 && start < text.length && Character.isLowSurrogate(text[start]) && Character.isHighSurrogate(text[start - 1])) start++
        var end = minOf(start + maxChars,text.length)
        if (end > start && end < text.length && Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])) end--
        return text.substring(start,end)
    }
    override fun accepts(locator: Locator): Boolean {
        if (closed.get() || locator !is Locator.Text || locator.version != revision ||
            locator.paragraph !in 0 until source.index.paragraphs || locator.character < 0) return false
        val block = source.block(locator.paragraph)
        return locator.character <= block.text.length && locator.sourceOffset == block.sourceOffset + locator.character
    }
    override fun fractionOf(extent: ReadingExtent): Double {
        require(extent is TxtReadingExtent && extent.revision == revision)
        return (extent.sourceOffset.toDouble() / (source.index.totalCharacters - 1).coerceAtLeast(1)).coerceIn(0.0,1.0)
    }
    override fun furthestOf(first: ReadingExtent, second: Locator): ReadingExtent {
        require(first is TxtReadingExtent && second is Locator.Text && first.revision == revision && second.version == revision)
        return if (first.sourceOffset >= second.sourceOffset) first else TxtReadingExtent(revision,second.sourceOffset)
    }
    override fun close() { if (closed.compareAndSet(false,true)) source.close() }
}
