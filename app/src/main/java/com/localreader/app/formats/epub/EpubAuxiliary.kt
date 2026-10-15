package com.localreader.app.formats.epub

import com.localreader.app.core.*
import java.io.File
import java.security.MessageDigest

/** Immutable, separately indexed documents never enter the logical reading spine. */
object EpubAuxiliary {
    fun key(path: PackagePath)=MessageDigest.getInstance("SHA-256").digest(path.value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(32)
    fun directory(main: File,key: String): File {
        require(key.matches(Regex("[0-9a-f]{32}")))
        return File(File(main,"auxiliary-1-${EpubParts.budget.key}"),key)
    }
    /** main is the explicit logical version root, never inferred from a renderer's parent. */
    internal fun verifyRetainedBudget(main: File,checkpoint: ()->Unit={},newDocKey: String?=null) {
        val roots=listOf(main)+main.listFiles().orEmpty().filter { it.isDirectory && it.name.matches(Regex("derived-(?:mixed1-parts2-style[45]-split(?:256|384|512)|parts2-style[45](?:-split(?:256|384|512))?|fxl1)")) }
        val documents=mutableSetOf<String>()
        newDocKey?.let {require(it.matches(Regex("[0-9a-f]{32}")));documents+=it}
        var total=0L
        roots.forEach { owner -> owner.listFiles().orEmpty().filter { it.isDirectory && it.name.matches(Regex("auxiliary-1(?:-split(?:256|384|512))?")) }.forEach { folder ->
            documents+=folder.listFiles().orEmpty().filter {it.isDirectory && it.name.matches(Regex("[0-9a-f]{32}"))}.map {it.name}
            require(documents.size<=128) { "附属文档缓存超过 128 个逻辑文档预算" }
            folder.walkTopDown().forEach { file -> checkpoint();if(file.isFile) {
                total+=file.length();require(total<=256L*1024*1024) { "附属正文全部档位缓存超过 256 MiB 预算" }
            } }
        } }
    }
    fun read(main: File,version: String,docKey: String): Pair<File,EpubCacheRecord> {
        val directory=directory(main,docKey);val record=EpubCache.read(directory)
        require(record.revision==version && record.sections.size==1 && key(record.sections.single().path)==docKey && record.layout=="reflowable" && EpubCache.compatibleSplit(record)) { "附属正文身份不符" }
        return directory to record
    }
    fun prepare(original: File,main: File,hash: String,version: String,path: PackagePath,checkpoint: ()->Unit): Pair<File,EpubCacheRecord> {
        val key=key(path);val installed=directory(main,key)
        synchronized(EpubAuxiliary::class.java) {
            checkpoint()
            val root=installed.parentFile!!.apply { check(mkdirs() || isDirectory) }
            // Only our exact unpublished staging names may be removed after process death.
            (root.listFiles().orEmpty().toList()+File(main,"auxiliary-1").listFiles().orEmpty().toList()).distinct().filter { it.name.matches(Regex("\\.stage-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) }.forEach { checkpoint();check(it.deleteRecursively()) }
            if(installed.isDirectory)return read(main,version,key)
            verifyRetainedBudget(main,checkpoint,key)
            // The private original was hashed at import. Each consumed ZIP entry
            // still verifies its declared size and CRC; do not scan unrelated entries.
            val stage=File(root,".stage-${java.util.UUID.randomUUID()}")
            try {
                val record=EpubCache.buildAuxiliary(original,stage,hash,path,checkpoint)
                require(record.revision==version && record.sections.size==1)
                verifyRetainedBudget(main,checkpoint,key)
                checkpoint();check(stage.renameTo(installed));EpubCache.syncDirectory(root)
                return installed to record
            } finally { stage.deleteRecursively() }
        }
    }
}

/** Local renderer coordinates map only to this document's canonical stream. */
class EpubResourcePublication(private val local: EpubPublication,private val source: EpubSource,val docKey: String,val returnTo: Locator): ReaderPublication by local {
    override fun renderingPublication(locator: Locator): ReaderPublication=this
    override val auxiliary=true
    override val revision=local.revision+"-aux-"+docKey
    override val paginationParts=emptyList<PaginationPart>()
    override fun paginationCoordinate(locator: Locator): PaginationBoundary?=null
    private var focusedOffset=0
    fun reveal(offset: Int) { focusedOffset=offset }
    fun internal(locator: Locator.EpubResource)=Locator.Epub(sourceRevision,0,locator.offset,locator.quote,elementId=locator.elementId)
    fun external(locator: Locator.Epub)=Locator.EpubResource(sourceRevision,docKey,locator.offset,returnTo,locator.quote,locator.elementId)
    override fun target(locator: Locator): ContentTarget = if(locator is Locator.EpubResource && locator.docKey==docKey)local.target(internal(locator)) else local.target(Locator.Epub(sourceRevision,0,0)).copy(nearby=true)
    override fun locator(anchor: RendererAnchor): Locator?=local.locator(anchor)?.let(::external)
    override fun locator(section: Int,blockId: String,character: Int): Locator?=null
    override fun linkTarget(identity: String): Locator?=local.linkTarget(identity)?.let { external(it as Locator.Epub) }
    override fun section(index: Int): ContentSection {
        val original=local.section(index)
        val document=org.jsoup.Jsoup.parse(original.html).apply { outputSettings().prettyPrint(false) }
        // A footnote hidden in the main document must become readable in its
        // separate document surface. This changes presentation, never canonical text.
        val focus=source.record.sections.single().fragments.filterValues { it.offset<=focusedOffset }.maxByOrNull { it.value.offset }?.key?.let(document::getElementById)
        (document.select("[hidden],[data-epub-type~=footnote],[data-epub-type~=endnote],[role=doc-footnote],[role=doc-endnote]")+listOfNotNull(focus)).distinct().forEach { element ->
            (listOf(element)+element.parents()).filter { it.tagName() !in setOf("head","html") }.forEach { parent ->
                parent.removeAttr("hidden");parent.attr("style",parent.attr("style")+";display:block!important;visibility:visible!important")
            }
        }
        return original.copy(html=document.outerHtml())
    }
    fun accepts(locator: Locator.EpubResource): Boolean {
        if(locator.version!=sourceRevision || locator.docKey!=docKey || locator.returnTo.version!=sourceRevision)return false
        val section=source.record.sections.single()
        return locator.offset in 0..section.characters && (locator.elementId==null || section.elements[locator.elementId]==locator.offset)
    }
    fun excerpt(locator: Locator.EpubResource,limit: Int): String {
        require(limit in 0..120);if(!accepts(locator))return ""
        var start=locator.offset
        if(start>0 && start<source.record.sections.single().characters) {
            val pair=source.textSlice(0,start-1,2)
            if(pair.length==2 && pair[0].isHighSurrogate() && pair[1].isLowSurrogate())start++
        }
        return LocatorCodec.safePrefix(source.textSlice(0,start,limit+1),limit)
    }
}
