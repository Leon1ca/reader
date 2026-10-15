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
    /** A renderer group retains source locators while bounding one layout backend. */
    fun renderingPublication(locator: Locator): ReaderPublication = this
    fun adjacentRenderingLocator(locator: Locator,next: Boolean): Locator? = null
    val sectionOffset: Int get() = 0
    val finalRenderingGroup: Boolean get() = true
    val compatibilityNotices: List<String> get() = emptyList()
    /** BCP 47 language of the content when the source declares one (used for script-specific typography defaults). */
    val language: String? get() = null
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
class TxtPublication(private val source: TxtTextSource, checkpoint: () -> Unit = {}, private val projection: TxtDisplayProjection?=null) : ReaderPublication {
    override val sourceRevision=source.version
    override val revision = source.version + if(projection!=null)"-${TxtDisplayProjection.ALGORITHM}" else ""
    override val kind = WebContentKind.TXT
    // Only compact record metadata is scanned. No book text is decoded for this directory.
    private data class Directory(val starts: IntArray,val ends: IntArray,val offsets: LongArray,val records: List<IntArray>,val emptyNatural: BooleanArray)
    private val directory: Directory = run {
        val cache=source.displayDirectory?.let { java.io.File(it,"txt-sections-v2-${if(projection==null)"raw" else TxtDisplayProjection.ALGORITHM}.bin") }
        fun load(): Directory? = try {
            cache?.takeIf { it.isFile }?.let { file ->
                val checksum=java.util.zip.CRC32()
                java.io.DataInputStream(java.util.zip.CheckedInputStream(file.inputStream().buffered(),checksum)).use { input ->
                require(input.readInt()==0x54534432 && input.readUTF()==source.version && input.readInt()==source.index.paragraphs && input.readLong()==source.index.totalCharacters)
                val count=input.readInt();require(count in 1..source.index.paragraphs)
                val starts=IntArray(count);val ends=IntArray(count);val offsets=LongArray(count);val empty=BooleanArray(count)
                var previous=-1;var totalRecords=0
                val groups=List(count) { section ->
                    checkpoint();starts[section]=input.readInt();ends[section]=input.readInt();offsets[section]=input.readLong();empty[section]=input.readBoolean()
                    val size=input.readInt();totalRecords+=size;require(size in 1..source.index.paragraphs && totalRecords in 1..source.index.paragraphs && starts[section]>previous && ends[section] in starts[section]+1..source.index.paragraphs && offsets[section] in 0 until source.index.totalCharacters)
                    IntArray(size) { input.readInt().also { record -> require(record>previous && record in starts[section] until ends[section]);previous=record } }.also {
                        require(it.first()==starts[section] && it.last()+1==ends[section] && source.metadata(it.first()).sourceOffset==offsets[section])
                    }
                }
                val expected=checksum.value;require(input.readLong()==expected && input.read()==-1) { "TXT 分节缓存校验失败" }
                Directory(starts,ends,offsets,groups,empty)
            } }
        } catch(_: java.io.IOException) { null } catch(_: IllegalArgumentException) { null }
        load()?.let { return@run it }

        val paragraphs=mutableListOf<Int>();val offsets=mutableListOf<Long>();val ends=mutableListOf<Int>()
        val groups=mutableListOf<MutableList<Int>>();val emptyNatural=mutableListOf<Boolean>()
        var records=0;var bytes=0
        for(paragraph in 0 until source.index.paragraphs) {
            if(paragraph%1024==0)checkpoint()
            val meta=source.metadata(paragraph)
            // Fully trimmed physical records have no rendered page. Persistent
            // raw coordinates still resolve through the projection's neighbors.
            val display=projection?.slice(paragraph)
            if(display?.displayedLength==0) {if(display.emptyUnit && emptyNatural.isNotEmpty())emptyNatural[emptyNatural.lastIndex]=true;continue}
            if(paragraphs.isEmpty() || records>0 && ((records>=64 || bytes>=32768) && !meta.continuation || bytes+meta.utf8Bytes>128*1024)) {
                paragraphs.add(paragraph);offsets.add(meta.sourceOffset);ends.add(paragraph+1);groups.add(mutableListOf());emptyNatural.add(false);records=0;bytes=0
            }
            ends[ends.lastIndex]=paragraph+1;groups.last().add(paragraph)
            bytes+=meta.utf8Bytes;records++
        }
        Directory(paragraphs.toIntArray(),ends.toIntArray(),offsets.toLongArray(),groups.map {it.toIntArray()},emptyNatural.toBooleanArray()).also { built ->
            if(cache!=null) {
                val stage=java.io.File(cache.parentFile,".${cache.name}-${java.util.UUID.randomUUID()}.stage")
                try {
                    java.io.FileOutputStream(stage).use { raw ->
                        val checksum=java.util.zip.CRC32()
                        java.io.DataOutputStream(java.util.zip.CheckedOutputStream(java.io.BufferedOutputStream(raw),checksum)).let { out ->
                        out.writeInt(0x54534432);out.writeUTF(source.version);out.writeInt(source.index.paragraphs);out.writeLong(source.index.totalCharacters);out.writeInt(built.starts.size)
                        built.starts.indices.forEach { section ->
                            checkpoint();out.writeInt(built.starts[section]);out.writeInt(built.ends[section]);out.writeLong(built.offsets[section]);out.writeBoolean(built.emptyNatural[section]);out.writeInt(built.records[section].size);built.records[section].forEach(out::writeInt)
                        }
                        out.flush();java.io.DataOutputStream(raw).writeLong(checksum.value);raw.fd.sync()
                    } }
                    java.nio.file.Files.move(stage.toPath(),cache.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                } finally { stage.delete() }
            }
        }
    }
    private val starts=directory.starts
    override val sectionCount = starts.size
    override val paginationParts: List<PaginationPart> = directory.offsets.mapIndexed { index,first ->
        val end=directory.offsets.getOrNull(index+1) ?: source.index.totalCharacters
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
        val original=source.resolve(locator)
        val resolved = projection?.target(original) ?: original
        return ContentTarget(sectionOf(resolved.paragraph), resolved.paragraph.toString(), resolved.character, resolved.nearby)
    }
    override fun section(index: Int): ContentSection {
        require(index in 0 until sectionCount)
        val blocks=directory.records[index].map(source::block)
        val body = buildString {
            if(projection!=null && directory.emptyNatural[index])append("<p hidden></p>")
            var tag: String? = null
            blocks.forEach { block ->
                if (tag == null || !block.continuation) {
                    tag?.let { append("</$it>") }
                    val display=projection?.slice(block.index)
                    if(display?.generatedHeading?.let {it>0}==true)append("<h2 data-generated=\"true\">${display.generatedHeading}</h2>")
                    tag = if (if(projection==null)block.heading else display?.sourceHeading==true) "h2" else "p"
                    val continuesOutside = block.continues && blocks.last().continues && blocks.last().index >= block.index &&
                        blocks.dropWhile { it.index < block.index }.drop(1).all { it.continuation } &&
                        (projection==null || projection.slice(blocks.last().index).retainedLast>blocks.last().index)
                    val classes = listOfNotNull(if (block.continuation) "continuation" else null,if (continuesOutside) "continues" else null).joinToString(" ")
                    append("<$tag${if (classes.isNotEmpty()) " class=\"$classes\"" else ""}${if(display?.emptyUnit==true)" hidden" else ""}>")
                }
                val slice=projection?.slice(block.index)
                val text=if(slice==null)block.text else block.text.substring(slice.start,slice.end)
                val placeholder=if(text.isEmpty() && (slice==null || !slice.emptyUnit && slice.retainedFirst==block.index && slice.retainedLast==block.index))"\u200B" else text
                append("<span data-block=\"${block.index}\" data-length=\"${text.length}\">${HtmlText.escape(placeholder)}</span>")
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
        val slice=projection?.slice(paragraph)
        if (character > (slice?.displayedLength ?: block.text.length)) return null
        val rawCharacter=slice?.sourceCharacter(character) ?: character
        val offset = block.sourceOffset + rawCharacter
        return Locator.Text(sourceRevision, offset, paragraph, rawCharacter, offset.toDouble() / source.index.totalCharacters.coerceAtLeast(1))
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
