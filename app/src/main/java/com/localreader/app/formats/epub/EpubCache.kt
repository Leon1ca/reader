package com.localreader.app.formats.epub

import com.localreader.app.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

data class EpubSectionRecord(val index: Int,val path: PackagePath,val characters: Int,val canonicalFile: String,val authorFile: String,val customFile: String,
    val fragments: Map<String,CanonicalFragment>,val elements: Map<String,Int>)
data class EpubCacheRecord(val revision: String,val title: String,val author: String,val language: String?,val identifier: String,
    val sections: List<EpubSectionRecord>,val toc: List<EpubToc>,val resources: List<EpubResourceRecord>,val warnings: List<String>,val layout: String,val mixed: Boolean,val direction: String,val coverFile: String? = null)

object EpubCache {
    fun build(original: File,directory: File,hash: String,checkpoint: () -> Unit = {}): EpubCacheRecord {
        directory.mkdirs()
        return EpubZip(original,checkpoint).use { zip ->
            zip.verifyAll();val packageInfo=EpubPackages.parse(zip)
            // Effective fixed layout metadata is parsed accurately; its backend is implemented in R3.
            require(packageInfo.layout=="reflowable" && packageInfo.spine.all { it.layout=="reflowable" }) { "此 EPUB 含固定版式页面，固定版式阅读将在下一增量接入" }
            val assets=EpubAssets(zip,packageInfo,directory,checkpoint);val sanitizer=EpubSanitizer(assets)
            val revision=MessageDigest.getInstance("SHA-256").digest((hash+":"+EpubSanitizer.VERSION).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(32)
            var outputBytes=0L
            val warnings=linkedSetOf<String>();val sections=packageInfo.spine.mapIndexed { i,spine ->
                checkpoint();val input=zip.read(spine.item.path,EpubLimits.SMALL_SPINE_BYTES)
                if(spine.item.mime=="application/xhtml+xml")EpubXmlReader.parse(input) // bounded XML; no DTD/entity resolution
                val safe=sanitizer.sanitize(input,spine.item.path,spine.item.mime)
                warnings.addAll(safe.warnings)
                val canonical="sections/$i.txt";val author="sections/$i-author.html";val custom="sections/$i-custom.html"
                val canonicalBytes=safe.canonicalText.toByteArray(Charsets.UTF_8);val authorBytes=safe.authorHtml.toByteArray(Charsets.UTF_8);val customBytes=safe.customHtml.toByteArray(Charsets.UTF_8)
                outputBytes+=canonicalBytes.size+authorBytes.size+customBytes.size
                require(outputBytes<=256L*1024*1024) { "EPUB 派生正文超过缓存预算" }
                write(File(directory,canonical),canonicalBytes);write(File(directory,author),authorBytes);write(File(directory,custom),customBytes)
                val doc=org.jsoup.Jsoup.parse(safe.authorHtml);val elements=linkedMapOf<String,Int>();var offset=0;val serializedText=StringBuilder()
                fun visit(node: org.jsoup.nodes.Node) {
                    if(node is org.jsoup.nodes.TextNode) { offset+=node.wholeText.length;serializedText.append(node.wholeText) }
                    else {
                        if(node is org.jsoup.nodes.Element && node.hasAttr("data-reader-element"))elements[node.attr("data-reader-element")]=offset
                        node.childNodes().forEach(::visit)
                    }
                }
                visit(doc.body());check(serializedText.toString()==safe.canonicalText) { "EPUB 序列化正文与索引不同" }
                EpubSectionRecord(i,spine.item.path,safe.canonicalText.length,canonical,author,custom,safe.fragments,elements)
            }
            val coverFile=packageInfo.cover?.let { path ->
                assets.image(path,path.value.substringAfterLast('/'))
            }?.substringAfter("../resources/")?.substringBefore('#')?.let { assets.records[it]?.file }
            warnings.addAll(assets.warnings)
            val record=EpubCacheRecord(revision,packageInfo.title,packageInfo.author,packageInfo.language,packageInfo.identifier,sections,packageInfo.toc,assets.records.values.toList(),warnings.toList(),packageInfo.layout,packageInfo.mixed,packageInfo.direction,coverFile)
            val metadata=encode(record).toByteArray(Charsets.UTF_8);require(metadata.size<=16*1024*1024) { "EPUB 目录与定位索引超过缓存预算" }
            write(File(directory,"package.json"),metadata)
            syncDirectory(File(directory,"assets"));syncDirectory(File(directory,"sections"));syncDirectory(directory)
            record
        }
    }
    fun read(directory: File): EpubCacheRecord {
        val file=File(directory,"package.json");require(file.length() in 1..16L*1024*1024) { "EPUB 缓存索引大小无效" }
        val json=JSONObject(file.readText());require(json.getInt("schema")==1)
        val sections=json.getJSONArray("sections").objects().map { item ->
            val fragments=item.getJSONObject("fragments");val elements=item.getJSONObject("elements")
            EpubSectionRecord(item.getInt("index"),PackagePath.entry(item.getString("path")),item.getInt("characters"),safeFile(item.getString("canonical")),safeFile(item.getString("authorHtml")),safeFile(item.getString("customHtml")),
                fragments.keys().asSequence().associateWith { name -> val f=fragments.getJSONObject(name);CanonicalFragment(f.getInt("offset"),f.optString("elementId").takeIf { it.isNotEmpty() }) },
                elements.keys().asSequence().associateWith { elements.getInt(it) })
        }
        require(sections.isNotEmpty() && sections.size<=20000 && sections.withIndex().all { it.index==it.value.index && it.value.characters in 0..EpubLimits.SMALL_SPINE_BYTES.toInt() })
        return EpubCacheRecord(json.getString("revision"),json.getString("title"),json.getString("author"),json.optString("language").takeIf { it.isNotEmpty() },json.getString("identifier"),sections,
            json.getJSONArray("toc").objects().map { EpubToc(it.getString("title"),PackageHref(PackagePath.entry(it.getString("path")),it.optString("fragment").takeIf { f -> f.isNotEmpty() }),it.getInt("depth")) },
            json.getJSONArray("resources").objects().map { EpubResourceRecord(it.getString("id"),it.getString("mime"),safeFile(it.getString("file")),it.getLong("bytes")) },
            json.getJSONArray("warnings").let { array -> (0 until array.length()).map(array::getString) },json.getString("layout"),json.getBoolean("mixed"),json.getString("direction"),json.optString("coverFile").takeIf { it.isNotEmpty() }?.let(::safeFile))
    }
    private fun encode(record: EpubCacheRecord)=JSONObject().apply {
        put("schema",1);put("revision",record.revision);put("title",record.title);put("author",record.author);put("language",record.language.orEmpty());put("identifier",record.identifier)
        put("coverFile",record.coverFile.orEmpty());put("layout",record.layout);put("mixed",record.mixed);put("direction",record.direction);put("warnings",JSONArray(record.warnings))
        put("sections",JSONArray(record.sections.map { section -> JSONObject().put("index",section.index).put("path",section.path.value).put("characters",section.characters)
            .put("canonical",section.canonicalFile).put("authorHtml",section.authorFile).put("customHtml",section.customFile)
            .put("fragments",JSONObject(section.fragments.mapValues { (_,f) -> JSONObject().put("offset",f.offset).put("elementId",f.elementId.orEmpty()) })).put("elements",JSONObject(section.elements)) }))
        put("toc",JSONArray(record.toc.map { JSONObject().put("title",it.title).put("path",it.target.path.value).put("fragment",it.target.fragment.orEmpty()).put("depth",it.depth) }))
        put("resources",JSONArray(record.resources.map { JSONObject().put("id",it.id).put("mime",it.mime).put("file",it.file).put("bytes",it.bytes) }))
    }.toString()
    private fun JSONArray.objects()=(0 until length()).map(::getJSONObject)
    private fun safeFile(value: String): String { require(value.matches(Regex("(?:assets/[a-z0-9-]+(?:\\.css)?|sections/[0-9]+(?:-author\\.html|-custom\\.html|\\.txt))")));return value }
    private fun write(file: File,bytes: ByteArray) { file.parentFile!!.mkdirs();FileOutputStream(file).use { it.write(bytes);it.fd.sync() } }
    private fun syncDirectory(file: File) {
        if(!file.exists())return
        val fd=android.system.Os.open(file.path,android.system.OsConstants.O_RDONLY,0)
        try { android.system.Os.fsync(fd) } finally { android.system.Os.close(fd) }
    }
}
