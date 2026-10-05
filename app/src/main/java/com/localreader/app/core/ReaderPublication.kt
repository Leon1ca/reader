package com.localreader.app.core

/** Content capabilities contain no Android, SQLite, file URI or renderer implementation. */
interface ReaderPublication {
    val revision: String
    val sourceRevision: String get()=revision
    val kind: WebContentKind
    val paginationParts: List<PaginationPart> get() = emptyList()
    /** Metadata-only estimate input; this method must never read or decode source text. */
    fun paginationCoordinate(locator: Locator): PaginationBoundary? = null
    val auxiliary: Boolean get() = false
    val sectionCount: Int
    val protocolVersion: Int get() = 1
    val direction: String get() = "ltr"
    val fixedLayout: FixedLayoutInfo? get() = null
    val compatibilityNotices: List<String> get() = emptyList()
    fun linkTarget(identity: String): Locator? = null
    fun configure(rules: ReaderRules) { }
    val resourceManifest: Map<String,PackagedContentResource> get() = emptyMap()
    fun section(index: Int): ContentSection
    fun target(locator: Locator): ContentTarget
    fun locator(section: Int, blockId: String, character: Int): Locator?
    fun locator(anchor: RendererAnchor): Locator? = when(anchor) {
        is RendererAnchor.Block -> locator(anchor.section,anchor.blockId,anchor.character)
        else -> null
    }
}
/** Resource capabilities are owned by a content adapter, never supplied by authored HTML URLs. */
class ResourceDescriptor private constructor(val mime: String,val length: Long?,private val source: (() -> java.io.InputStream)?,val assetPath: String?) {
    constructor(mime: String,assetPath: String): this(mime,null,null,assetPath)
    constructor(mime: String,length: Long,open: () -> java.io.InputStream): this(mime,length,open,null)
    fun open(asset: (String) -> java.io.InputStream): java.io.InputStream = source?.invoke() ?: asset(requireNotNull(assetPath))
}
typealias PackagedContentResource = ResourceDescriptor
data class FixedPageSpec(val index: Int,val width: Double,val height: Double,val pageSpread: String?,val spread: String)
data class FixedLayoutInfo(val pages: List<FixedPageSpec>,val mixed: Boolean)
data class ContentSection(val index: Int, val html: String, val utf16Characters: Int)
sealed interface RendererAnchor {
    val section: Int
    data class Block(override val section: Int,val blockId: String,val character: Int): RendererAnchor
    data class Text(override val section: Int,val offset: Int): RendererAnchor
    data class Element(override val section: Int,val elementId: String): RendererAnchor
    data class Page(override val section: Int,val x: Double,val y: Double): RendererAnchor
}
data class ContentTarget(val anchor: RendererAnchor,val nearby: Boolean = false) {
    constructor(section: Int,blockId: String,character: Int,nearby: Boolean = false): this(RendererAnchor.Block(section,blockId,character),nearby)
    val section get()=anchor.section
    val blockId get()=when(val value=anchor) { is RendererAnchor.Block -> value.blockId;is RendererAnchor.Element -> value.elementId;else -> "canonical" }
    val character get()=when(val value=anchor) { is RendererAnchor.Block -> value.character;is RendererAnchor.Text -> value.offset;else -> 0 }
}

/** Bounded resources prefer physical paragraph boundaries; extreme paragraphs retain continuation. */
class TxtPublication(private val source: TxtTextSource, checkpoint: () -> Unit = {}) : ReaderPublication {
    override val revision = source.version
    override val kind = WebContentKind.TXT
    // Only compact record metadata is scanned. No book text is decoded for this directory.
    private val directory: Pair<IntArray,LongArray> = run {
        val paragraphs=mutableListOf<Int>();val offsets=mutableListOf<Long>()
        var records = 0; var bytes = 0
        for (paragraph in 0 until source.index.paragraphs) {
            if (paragraph % 1024 == 0) checkpoint()
            val meta = source.metadata(paragraph)
            if (paragraph==0 || records > 0 && ((records >= 64 || bytes >= 32768) && !meta.continuation || bytes + meta.utf8Bytes > 128 * 1024)) {
                paragraphs.add(paragraph);offsets.add(meta.sourceOffset);records = 0; bytes = 0
            }
            bytes += meta.utf8Bytes; records++
        }
        paragraphs.toIntArray() to offsets.toLongArray()
    }
    private val starts=directory.first
    override val sectionCount = starts.size
    override val paginationParts: List<PaginationPart> = directory.second.mapIndexed { index,first ->
        val end=directory.second.getOrNull(index+1) ?: source.index.totalCharacters
        PaginationPart(index,end-first,0,first)
    }
    override fun paginationCoordinate(locator: Locator): PaginationBoundary? {
        val text=locator as? Locator.Text ?: return null
        if(text.version!=sourceRevision || text.sourceOffset !in 0..source.index.totalCharacters)return null
        val parts=paginationParts
        var low=0;var high=parts.size
        while(low<high) { val middle=(low+high) ushr 1;if(parts[middle].sourceStart<=text.sourceOffset)low=middle+1 else high=middle }
        val part=parts[(low-1).coerceAtLeast(0)]
        return PaginationBoundary(part.index,((text.sourceOffset-part.sourceStart).toDouble()/part.characters.coerceAtLeast(1)).coerceIn(0.0,1.0),true)
    }
    private fun sectionOf(paragraph: Int): Int = starts.binarySearch(paragraph).let { if (it >= 0) it else -it - 2 }
    override fun target(locator: Locator): ContentTarget {
        val resolved = source.resolve(locator)
        return ContentTarget(sectionOf(resolved.paragraph), resolved.paragraph.toString(), resolved.character, resolved.nearby)
    }
    override fun section(index: Int): ContentSection {
        require(index in 0 until sectionCount)
        val start = starts[index]
        val end = starts.getOrElse(index + 1) { source.index.paragraphs }
        val blocks = (start until end).map(source::block)
        val body = buildString {
            var tag: String? = null
            blocks.forEach { block ->
                if (tag == null || !block.continuation) {
                    tag?.let { append("</$it>") }
                    tag = if (block.heading) "h2" else "p"
                    val continuesOutside = block.continues && blocks.last().continues && blocks.last().index >= block.index &&
                        blocks.dropWhile { it.index < block.index }.drop(1).all { it.continuation }
                    val classes = listOfNotNull(if (block.continuation) "continuation" else null,if (continuesOutside) "continues" else null).joinToString(" ")
                    append("<$tag${if (classes.isNotEmpty()) " class=\"$classes\"" else ""}>")
                }
                append("<span data-block=\"${block.index}\" data-length=\"${block.text.length}\">${HtmlText.escape(block.text.ifEmpty { "\u200B" })}</span>")
                if (!block.continues) { append("</$tag>"); tag = null }
            }
            tag?.let { append("</$it>") }
        }
        return ContentSection(index, sectionDocument(body), blocks.sumOf { it.text.length })
    }
    override fun locator(section: Int, blockId: String, character: Int): Locator.Text? {
        val paragraph = blockId.toIntOrNull() ?: return null
        if (section !in 0 until sectionCount || paragraph !in 0 until source.index.paragraphs || sectionOf(paragraph) != section || character < 0) return null
        val block = source.block(paragraph)
        if (character > block.text.length) return null
        val offset = block.sourceOffset + character
        return Locator.Text(revision, offset, paragraph, character, offset.toDouble() / source.index.totalCharacters.coerceAtLeast(1))
    }
}

/** An authored, inert article fixture. This is not a web import/clip/RSS pipeline. */
class ArticleSamplePublication : ReaderPublication {
    override val revision = "article-sample-v1"
    override val kind = WebContentKind.ARTICLE
    override val resourceManifest = mapOf("images/landscape.svg" to PackagedContentResource("image/svg+xml","reader/sample/landscape.svg"))
    private val texts = listOf("结构化文章排版样本", "中文 English 123４５６，保留文章的标题、段落和强调。", "强调 Bold 和 Italic 与数字 2026。",
        "引用是一段独立的文字。", "第一个列表条目", "第二个列表条目", "val example = 123 // 中文代码", "图：本地山景示意", "语言", "示例", "中文", "123", "English", "456")
    override val sectionCount = 1
    override fun target(locator: Locator): ContentTarget {
        val article = locator as? Locator.Article
        if (article?.blockId == "image-1") return ContentTarget(0,"image-1",0,article.version != revision)
        val id = article?.blockId?.toIntOrNull()?.takeIf { it in texts.indices } ?: 0
        return ContentTarget(0, id.toString(), (article?.textOffset ?: 0).coerceIn(0, texts[id].length), article?.version != revision)
    }
    override fun locator(section: Int, blockId: String, character: Int): Locator.Article? {
        if (section == 0 && blockId == "image-1" && character == 0) return Locator.Article(revision,blockId,0,element = true)
        val id = blockId.toIntOrNull() ?: return null
        return if (section == 0 && id in texts.indices && character in 0..texts[id].length) Locator.Article(revision, blockId, character) else null
    }
    override val paginationParts get()=listOf(PaginationPart(0,texts.sumOf { it.length }.toLong()))
    override fun section(index: Int): ContentSection {
        require(index == 0)
        fun block(id: Int, content: String = HtmlText.escape(texts[id])) = "<span data-block=\"$id\" data-length=\"${texts[id].length}\">$content</span>"
        val body = "<article><h1>${block(0)}</h1><p>${block(1)}</p><p>${block(2, "强调 <strong>Bold</strong> 和 <em>Italic</em> 与数字 2026。")}</p>" +
            "<blockquote><p>${block(3)}</p></blockquote><ul><li>${block(4)}</li><li>${block(5)}</li></ul><pre><code>${block(6)}</code></pre>" +
            "<figure><img data-block=\"image-1\" data-length=\"0\" data-element=\"image\" src=\"../images/landscape.svg\" alt=\"本地山景\" width=\"480\" height=\"240\"><figcaption>${block(7)}</figcaption></figure>" +
            "<table><thead><tr><th>${block(8)}</th><th>${block(9)}</th></tr></thead><tbody><tr><td>${block(10)}</td><td>${block(11)}</td></tr><tr><td>${block(12)}</td><td>${block(13)}</td></tr></tbody></table></article>"
        return ContentSection(0, sectionDocument(body), texts.sumOf { it.length })
    }
}
private fun sectionDocument(body: String) = "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"></head><body>$body</body></html>"
