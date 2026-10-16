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
    private val projection=source.displayDirectory?.let { TxtDisplayProjection.prepare(source,it,checkpoint) }
    override val publication: ReaderPublication = try { TxtPublication(source,checkpoint,projection) }
        catch(error: Throwable) { try {projection?.close()} catch(close: Throwable) {error.addSuppressed(close)};throw error }
    override val textPreview = original?.let { file -> object: TextPreviewSource {
        override fun preview(encoding: String,lines: Int,checkpoint: ()->Unit) = run { checkOpen();readTextPreview({ file.inputStream() },encoding,lines,null,source.index.chapters.size) { checkOpen();checkpoint() } }
        override fun scanChapters(encoding: String,rule: ChapterRule,checkpoint: ()->Unit) = run { checkOpen();TxtParser.scanChapters(file,encoding,rule) { checkOpen();checkpoint() } }
    } }
    private val chapters = source.index.chapters
    private val entries = chapters.map { TocEntry("text:${it.sourceOffset}", it.title) }
    override val textControls = TextControls(source.index.encoding, TxtParser.encodings.toList(),
        if (chapters.firstOrNull()?.title?.startsWith("分段 ") == true) TocSource.TEXT_SEGMENTS else TocSource.CHAPTERS)
    private fun checkOpen() { check(!closed.get()) { "正文资源已关闭" } }
    override val search = if (original != null && searchRoot != null) txtSearch(source, original, searchRoot, ::checkOpen) else null
    override fun paginationChapterEnd(locator: Locator): PaginationBoundary? {
        val offset=(locator as? Locator.Text)?.sourceOffset ?: return null
        val end=chapters.firstOrNull { it.sourceOffset>offset }?.sourceOffset ?: return null
        return publication.paginationCoordinate(Locator.Text(revision,end,0))
    }
    private val tocCoordinates by lazy { chapters.mapIndexedNotNull { index,chapter -> publication.paginationCoordinate(Locator.Text(revision,chapter.sourceOffset,chapter.paragraph))?.let { entries[index].id to it } }.toMap() }
    override fun paginationTocCoordinates()=tocCoordinates
    override fun toc(): List<TocEntry> = entries
    override fun locatorForToc(id: String): Locator? {
        checkOpen()
        val index = entries.indexOfFirst { it.id == id }
        // A heading is usually indented; the display trims that indent. Aiming at the raw paragraph start would land in
        // trimmed whitespace and be reported as a "nearby" restore, so aim at the first displayed character instead.
        return chapters.getOrNull(index)?.let { chapter ->
            val skip=projection?.slice(chapter.paragraph)?.takeIf { it.displayedLength>0 }?.start ?: 0
            Locator.Text(revision,chapter.sourceOffset+skip,chapter.paragraph,skip)
        }
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
    override fun close() { if (closed.compareAndSet(false,true))try {projection?.close()} finally {source.close()} }
}
