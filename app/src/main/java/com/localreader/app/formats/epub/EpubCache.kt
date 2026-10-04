package com.localreader.app.formats.epub

import com.localreader.app.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

data class EpubSectionRecord(val index: Int,val path: PackagePath,val characters: Int,val canonicalFile: String,val authorFile: String,val customFile: String,
    val fragments: Map<String,CanonicalFragment>,val elements: Map<String,Int>,val canonicalEncoding: String="utf-8")
data class EpubCacheRecord(val revision: String,val title: String,val author: String,val language: String?,val identifier: String,
    val sections: List<EpubSectionRecord>,val toc: List<EpubToc>,val resources: List<EpubResourceRecord>,val warnings: List<String>,val layout: String,val mixed: Boolean,val direction: String,val coverFile: String? = null,val renderRevision: String=revision,val parts: List<EpubPartRecord> = sections.map { EpubPartRecord(it.index,it.index,0,it.characters,it.authorFile,it.customFile,it.elements) },val fixedPages: List<EpubFixedPage> = emptyList(),val declaredLayout: String=layout)

object EpubCache {
    const val RENDER_ALGORITHM="parts2-style4"
    private const val DERIVED_DIRECTORY="derived-parts2-style4"
    private const val FIXED_DERIVED_DIRECTORY="derived-fxl1"
    fun activeDirectory(directory: File)=File(directory,FIXED_DERIVED_DIRECTORY).takeIf { it.isDirectory } ?: File(directory,DERIVED_DIRECTORY).takeIf { it.isDirectory } ?: directory
    /** Schema 1 remains immutable. A schema 2 rebuild never replaces the original source/index. */
    fun refresh(original: File,directory: File,hash: String,checkpoint: () -> Unit = {}): Pair<File,EpubCacheRecord> {
        val currentDirectory=activeDirectory(directory);val old=readExact(currentDirectory)
        val fixed=old.layout=="pre-paginated"
        if(fixed && old.fixedPages.size==old.sections.size && old.renderRevision==old.revision+"-fxl1")return currentDirectory to old
        if(!fixed && (currentDirectory!=directory || old.renderRevision==old.revision || old.renderRevision==old.revision+"-"+RENDER_ALGORITHM))return currentDirectory to old
        val stage=File(directory,".derived-staging-${java.util.UUID.randomUUID()}")
        try {
            // Interrupted unpublished directories are owned by this derivation path only.
            directory.listFiles()?.filter { it.name.matches(Regex("\\.derived-staging-[0-9a-f-]{36}")) }?.forEach { check(it.deleteRecursively()) }
            val digest=MessageDigest.getInstance("SHA-256")
            original.inputStream().use { input -> val buffer=ByteArray(65536);while(true) { checkpoint();val n=input.read(buffer);if(n<0)break;digest.update(buffer,0,n) } }
            require(digest.digest().joinToString("") { "%02x".format(it) }==hash) { "EPUB 原件校验失败，未刷新排版缓存" }

            val fresh=build(original,stage,hash,checkpoint)
            require(fresh.revision==old.revision && fresh.sections.size==old.sections.size) { "EPUB 派生刷新改变了源身份" }
            old.sections.zip(fresh.sections).forEach { (before,after) ->
                checkpoint()
                require(before.index==after.index && before.path==after.path && before.characters==after.characters && before.canonicalEncoding==after.canonicalEncoding && before.fragments==after.fragments && before.elements==after.elements) { "EPUB 派生刷新改变了源定位索引" }
                require(equalFiles(File(currentDirectory,before.canonicalFile),File(stage,after.canonicalFile),checkpoint)) { "EPUB 派生刷新改变了 canonical UTF16 正文" }
            }
            checkpoint()
            val installed=File(directory,if(fixed)FIXED_DERIVED_DIRECTORY else DERIVED_DIRECTORY)
            check(!installed.exists() && stage.renameTo(installed)) { "EPUB 派生刷新提交失败" }
            syncDirectory(directory)
            return installed to fresh
        } catch(error: Exception) {
            if(error is kotlinx.coroutines.CancellationException)throw error
            // The complete old cache is still available; report fallback without touching its bytes.
            return currentDirectory to old.copy(warnings=(old.warnings+"排版缓存刷新失败，暂用原缓存：${error.message.orEmpty().take(120)}").distinct())
        } finally { stage.deleteRecursively() }
    }
    private fun equalFiles(a: File,b: File,checkpoint: () -> Unit): Boolean {
        if(a.length()!=b.length())return false
        a.inputStream().use { left -> b.inputStream().use { right ->
            val x=ByteArray(65536);val y=ByteArray(65536)
            var remaining=a.length()
            while(remaining>0) {
                checkpoint();val n=minOf(x.size.toLong(),remaining).toInt()
                fun fill(input: java.io.InputStream,buffer: ByteArray) { var count=0;while(count<n) { val read=input.read(buffer,count,n-count);check(read>0);count+=read } }
                fill(left,x);fill(right,y)
                for(i in 0 until n)if(x[i]!=y[i])return false
                remaining-=n
            }
        } }
        return true
    }
    fun build(original: File,directory: File,hash: String,checkpoint: () -> Unit = {}): EpubCacheRecord {
        directory.mkdirs()
        return EpubZip(original,checkpoint).use { zip ->
            zip.verifyAll();val packageInfo=EpubPackages.parse(zip)
            // Effective fixed layout metadata is parsed accurately; its backend is implemented in R3.
            val fixed=packageInfo.layout=="pre-paginated"
            val fixedPages=mutableListOf<EpubFixedPage>()
            val assets=EpubAssets(zip,packageInfo,directory,checkpoint);val sanitizer=EpubSanitizer(assets)
            val revision=MessageDigest.getInstance("SHA-256").digest((hash+":"+EpubSanitizer.VERSION).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(32)
            var outputBytes=0L
            val parts=mutableListOf<EpubPartRecord>();val warnings=linkedSetOf<String>();val sections=packageInfo.spine.mapIndexed { i,spine ->
                checkpoint()
                val long=!fixed && zip.entries.getValue(spine.item.path).size>EpubLimits.SMALL_SPINE_BYTES
                val selectors=EpubSourceSelectors();assets.selectorScope(if(long)"/source-chapter-$i" else null,selectors::compile)
                val canonical="sections/$i.${if(long)"u16" else "txt"}"
                val fragments=linkedMapOf<String,CanonicalFragment>();val elements=linkedMapOf<String,Int>();var characters=0;val firstPart=parts.size
                File(directory,canonical).parentFile!!.mkdirs()
                FileOutputStream(File(directory,canonical)).use { textFile ->
                    fun batch(safe: SanitizedSpine) {
                        checkpoint();warnings.addAll(safe.warnings)
                        safe.fragments.forEach { (key,value) -> fragments.putIfAbsent(key,value.copy(offset=value.offset+characters)) }
                        val divided=if(long)EpubParts.split(safe) else listOf(EpubParts.annotated(safe))
                        var local=0
                        divided.forEach { piece ->
                            require(parts.size<20000) { "EPUB 技术分块超过 20000" }
                            val index=parts.size;val author="sections/$index-author.html";val custom="sections/$index-custom.html"
                            val authorBytes=piece.author.toByteArray();val customBytes=piece.custom.toByteArray()
                            val doc=org.jsoup.Jsoup.parse(piece.author);val mapped=linkedMapOf<String,Int>();var offset=0;val verified=StringBuilder()
                            fun visit(node: org.jsoup.nodes.Node) {
                                if(node is org.jsoup.nodes.TextNode) { offset+=node.wholeText.length;verified.append(node.wholeText) }
                                else { if(node is org.jsoup.nodes.Element && node.hasAttr("data-reader-element"))mapped.putIfAbsent(node.attr("data-reader-element"),offset);node.childNodes().forEach(::visit) }
                            }
                            visit(doc.body());check(verified.toString()==piece.text) { "EPUB 分块序列化正文与索引不同" }
                            val textBytes=piece.text.toByteArray(if(long)Charsets.UTF_16LE else Charsets.UTF_8)
                            outputBytes+=textBytes.size+authorBytes.size+customBytes.size;require(outputBytes<=256L*1024*1024) { "EPUB 派生正文超过缓存预算" }
                            textFile.write(textBytes);write(File(directory,author),authorBytes);write(File(directory,custom),customBytes)
                            mapped.forEach { (key,value) -> elements.putIfAbsent(key,characters+local+value) }
                            parts.add(EpubPartRecord(index,i,characters+local,piece.text.length,author,custom,mapped));local+=piece.text.length
                        }
                        check(local==safe.canonicalText.length);characters+=local
                    }
                    if(long) {
                        var topology: EpubSourceTopology?=null;var direct: Boolean?=null
                        EpubStream.read(zip,spine,sanitizer,checkpoint) { safe ->
                            // All head and body sheets are discovered before the first safe batch.
                            if(direct==null)direct=selectors.rules.isEmpty()
                            if(direct==true)batch(safe) else {
                                val source=topology ?: EpubSourceTopology(checkpoint).also { topology=it };source.add(safe)
                            }
                        }
                        topology?.let { source ->
                            source.prepare(selectors)
                            EpubStream.read(zip,spine,sanitizer,checkpoint) { batch(source.mark(it)) }
                            source.finish()
                        }
                    } else if(fixed) {
                        val input=zip.read(spine.item.path,4L*1024*1024)
                        if(spine.item.mime in setOf("application/xhtml+xml","image/svg+xml"))EpubXmlReader.parse(input)
                        val safe=sanitizer.sanitize(input,spine.item.path,spine.item.mime,4L*1024*1024)
                        val viewport=EpubFixedViewport.resolve(safe.authorHtml,packageInfo.viewport,spine.item.mime=="image/svg+xml") { image ->
                            val capability=image.attr("src").substringAfter("../resources/","").substringBefore('#')
                            val resource=assets.records[capability]
                            if(resource==null || resource.mime=="image/svg+xml")null else {
                                val options=android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds=true }
                                android.graphics.BitmapFactory.decodeFile(File(directory,resource.file).path,options)
                                if(options.outWidth>0 && options.outHeight>0)options.outWidth.toDouble() to options.outHeight.toDouble() else null
                            }
                        } ?: (1000.0 to 2000.0).also { warnings.add("第 ${i+1} 页缺少有效尺寸，使用 1000×2000 兼容视口。") }
                        fixedPages.add(EpubFixedPage(i,viewport.first,viewport.second,spine.layout,spine.spread,spine.pageSpread))
                        val page=org.jsoup.Jsoup.parse(safe.authorHtml).apply {
                            outputSettings().prettyPrint(false)
                            // A standalone SVG has no HTML body default margin. This
                            // wrapper default precedes author sheets, preserving overrides.
                            if(spine.item.mime=="image/svg+xml")head().prependElement("style").text("html,body{margin:0;padding:0;width:100%;height:100%}body>svg:only-child{display:block;width:100%;height:100%}")
                            head().select("meta[name=viewport]").remove()
                            head().prependElement("meta").attr("name","viewport").attr("content","width=${viewport.first},height=${viewport.second}")
                        }.outerHtml()
                        require(page.toByteArray().size<=4*1024*1024) { "EPUB 固定页面超过 4 MiB 渲染预算" }
                        batch(safe.copy(authorHtml=page,customHtml=page))
                    } else {
                        val input=zip.read(spine.item.path,EpubLimits.SMALL_SPINE_BYTES)
                        if(spine.item.mime in setOf("application/xhtml+xml","image/svg+xml"))EpubXmlReader.parse(input)
                        batch(sanitizer.sanitize(input,spine.item.path,spine.item.mime))
                    }
                    textFile.fd.sync()
                }
                require(parts.size>firstPart) { "EPUB 章节没有正文或可见元素" }
                val first=parts[firstPart]
                EpubSectionRecord(i,spine.item.path,characters,canonical,first.authorFile,first.customFile,fragments,elements,if(long)"utf-16le" else "utf-8")
            }
            val explicitCover=packageInfo.cover?.let { path -> assets.image(path,path.value.substringAfterLast('/')) }
                ?.substringAfter("../resources/")?.substringBefore('#')?.let { assets.records[it] }
            val firstPageImage=if(fixed && explicitCover==null) {
                val doc=org.jsoup.Jsoup.parse(File(directory,sections.first().authorFile).readText())
                val id=doc.selectFirst("img[src]")?.attr("src")?.substringAfter("../resources/","")?.substringBefore('#')
                id?.let { assets.records[it] }
            } else null
            val coverResource=explicitCover ?: firstPageImage
            val coverFile=if(fixed && coverResource!=null && coverResource.mime!="image/svg+xml") {
                val target=File(directory,"assets/cover-preview");target.parentFile!!.mkdirs()
                if(FixedCover.create(File(directory,coverResource.file),target))"assets/cover-preview" else coverResource.file
            } else coverResource?.file
            warnings.addAll(assets.warnings)
            if(packageInfo.mixed)warnings.add("此书混合不同版式；当前按全局 ${packageInfo.layout} 渲染，逐页版式切换尚不支持。")
            val record=EpubCacheRecord(revision,packageInfo.title,packageInfo.author,packageInfo.language,packageInfo.identifier,sections,packageInfo.toc,assets.records.values.toList(),warnings.toList(),packageInfo.layout,packageInfo.mixed,packageInfo.direction,coverFile,revision+"-"+(if(fixed)"fxl1" else RENDER_ALGORITHM),parts,fixedPages,packageInfo.layout)
            val metadata=encode(record).toByteArray(Charsets.UTF_8);require(metadata.size<=16*1024*1024) { "EPUB 目录与定位索引超过缓存预算" }
            write(File(directory,"package.json"),metadata)
            syncDirectory(File(directory,"assets"));syncDirectory(File(directory,"sections"));syncDirectory(directory)
            record
        }
    }
    fun read(directory: File): EpubCacheRecord = readExact(activeDirectory(directory))
    private fun readExact(directory: File): EpubCacheRecord {
        val file=File(directory,"package.json");require(file.length() in 1..16L*1024*1024) { "EPUB 缓存索引大小无效" }
        val json=JSONObject(file.readText());val schema=json.getInt("schema");require(schema in 1..2)
        val sections=json.getJSONArray("sections").objects().map { item ->
            val fragments=item.getJSONObject("fragments");val elements=item.getJSONObject("elements")
            EpubSectionRecord(item.getInt("index"),PackagePath.entry(item.getString("path")),item.getInt("characters"),safeFile(item.getString("canonical")),safeFile(item.getString("authorHtml")),safeFile(item.getString("customHtml")),
                fragments.keys().asSequence().associateWith { name -> val f=fragments.getJSONObject(name);CanonicalFragment(f.getInt("offset"),f.optString("elementId").takeIf { it.isNotEmpty() }) },
                elements.keys().asSequence().associateWith { elements.getInt(it) },item.optString("canonicalEncoding","utf-8"))
        }
        require(sections.isNotEmpty() && sections.size<=20000 && sections.withIndex().all { it.index==it.value.index && it.value.characters in 0..EpubLimits.ENTRY_BYTES.toInt() && it.value.canonicalEncoding in setOf("utf-8","utf-16le") })
        val parts=if(schema==1)sections.map { EpubPartRecord(it.index,it.index,0,it.characters,it.authorFile,it.customFile,it.elements) } else json.getJSONArray("parts").objects().map { item ->
            val elements=item.getJSONObject("elements")
            EpubPartRecord(item.getInt("index"),item.getInt("spine"),item.getInt("start"),item.getInt("characters"),safeFile(item.getString("authorHtml")),safeFile(item.getString("customHtml")),elements.keys().asSequence().associateWith { elements.getInt(it) })
        }
        require(parts.size in 1..20000 && parts.withIndex().all { it.index==it.value.index && it.value.spine in sections.indices && it.value.canonicalStart>=0 && it.value.characters in 0..(if(json.getString("layout")=="pre-paginated")4*1024*1024 else EpubLimits.SMALL_SPINE_BYTES.toInt()) })
        sections.forEach { section -> val rows=parts.filter { it.spine==section.index };var end=0;require(rows.isNotEmpty());rows.forEach { require(it.canonicalStart==end);end+=it.characters };require(end==section.characters) }
        return EpubCacheRecord(json.getString("revision"),json.getString("title"),json.getString("author"),json.optString("language").takeIf { it.isNotEmpty() },json.getString("identifier"),sections,
            json.getJSONArray("toc").objects().map { EpubToc(it.getString("title"),PackageHref(PackagePath.entry(it.getString("path")),it.optString("fragment").takeIf { f -> f.isNotEmpty() }),it.getInt("depth")) },
            json.getJSONArray("resources").objects().map { EpubResourceRecord(it.getString("id"),it.getString("mime"),safeFile(it.getString("file")),it.getLong("bytes")) },
            json.getJSONArray("warnings").let { array -> (0 until array.length()).map(array::getString) },json.getString("layout"),json.getBoolean("mixed"),json.getString("direction"),json.optString("coverFile").takeIf { it.isNotEmpty() }?.let(::safeFile),json.optString("renderRevision",json.getString("revision")),parts,
            json.optJSONArray("fixedPages")?.objects()?.map { page -> EpubFixedPage(page.getInt("index"),page.getDouble("width"),page.getDouble("height"),page.getString("layout"),page.getString("spread"),page.optString("pageSpread").takeIf { it.isNotEmpty() }) } ?: emptyList(),json.optString("declaredLayout",json.getString("layout")))
    }
    private fun encode(record: EpubCacheRecord)=JSONObject().apply {
        put("fixedPages",JSONArray(record.fixedPages.map { page -> JSONObject().put("index",page.index).put("width",page.width).put("height",page.height).put("layout",page.declaredLayout).put("spread",page.spread).put("pageSpread",page.pageSpread.orEmpty()) }));put("declaredLayout",record.declaredLayout)
        put("schema",2);put("revision",record.revision);put("renderRevision",record.renderRevision);put("title",record.title);put("author",record.author);put("language",record.language.orEmpty());put("identifier",record.identifier)
        put("coverFile",record.coverFile.orEmpty());put("layout",record.layout);put("mixed",record.mixed);put("direction",record.direction);put("warnings",JSONArray(record.warnings))
        put("sections",JSONArray(record.sections.map { section -> JSONObject().put("index",section.index).put("path",section.path.value).put("characters",section.characters)
            .put("canonical",section.canonicalFile).put("canonicalEncoding",section.canonicalEncoding).put("authorHtml",section.authorFile).put("customHtml",section.customFile)
            .put("fragments",JSONObject(section.fragments.mapValues { (_,f) -> JSONObject().put("offset",f.offset).put("elementId",f.elementId.orEmpty()) })).put("elements",JSONObject(section.elements)) }))
        put("parts",JSONArray(record.parts.map { part -> JSONObject().put("index",part.index).put("spine",part.spine).put("start",part.canonicalStart).put("characters",part.characters).put("authorHtml",part.authorFile).put("customHtml",part.customFile).put("elements",JSONObject(part.elements)) }))
        put("toc",JSONArray(record.toc.map { JSONObject().put("title",it.title).put("path",it.target.path.value).put("fragment",it.target.fragment.orEmpty()).put("depth",it.depth) }))
        put("resources",JSONArray(record.resources.map { JSONObject().put("id",it.id).put("mime",it.mime).put("file",it.file).put("bytes",it.bytes) }))
    }.toString()
    private fun JSONArray.objects()=(0 until length()).map(::getJSONObject)
    private fun safeFile(value: String): String { require(value.matches(Regex("(?:assets/[a-z0-9-]+(?:\\.css)?|sections/[0-9]+(?:-author\\.html|-custom\\.html|\\.txt|\\.u16))")));return value }
    private fun write(file: File,bytes: ByteArray) { file.parentFile!!.mkdirs();FileOutputStream(file).use { it.write(bytes);it.fd.sync() } }
    private fun syncDirectory(file: File) {
        if(!file.exists())return
        val fd=android.system.Os.open(file.path,android.system.OsConstants.O_RDONLY,0)
        try { android.system.Os.fsync(fd) } finally { android.system.Os.close(fd) }
    }
}
