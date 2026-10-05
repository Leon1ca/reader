package com.localreader.app.formats.epub

import com.localreader.app.core.Locator
import com.localreader.app.core.search.*
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID

/** Derived paragraph cuts are verified against the immutable logical canonical stream. */
internal class EpubSearchUnits(private val source: EpubSource, private val original: File) {
    private data class QueryPackage(val zip: EpubZip, val book: EpubPackage)
    private val query = ThreadLocal<QueryPackage>()
    fun lease(checkpoint: () -> Unit): java.io.Closeable {
        check(query.get() == null)
        val zip = EpubZip(original, checkpoint)
        try { query.set(QueryPackage(zip,EpubPackages.parse(zip))) }
        catch (error: Throwable) { zip.close();throw error }
        return java.io.Closeable { query.remove();zip.close() }
    }
    private fun identity(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(32)
    fun open(spine: Int, checkpoint: () -> Unit): RandomAccessFile {
        val directory = File(source.directory, "search-units-1").apply { check(mkdirs() || isDirectory) }
        val installed = File(directory, "$spine.bin")
        val section = source.record.sections[spine]
        fun valid(): Boolean = try { java.io.DataInputStream(installed.inputStream().buffered()).use {
            require(source.record.revision.matches(Regex("[0-9a-f]{32}")))
            if (it.readInt() != 1 || it.readUTF() != source.record.revision || it.readInt() != section.characters ||
                it.readInt() != 0 || it.readShort().toInt() != 0 || installed.length() < 56 ||
                (installed.length() - 48) % 8 != 0L || (installed.length() - 48) / 8 > 200000) return@use false
            var previous = -1L
            repeat(((installed.length() - 48) / 8).toInt()) { index ->
                if (index % 256 == 0) checkpoint()
                val value = it.readLong()
                if (value <= previous || value !in 0..section.characters.toLong()) return@use false
                previous = value
            }
            previous == section.characters.toLong()
        } } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel } catch (_: Exception) { false }
        if (!valid()) {
            val stage = File(directory, ".stage-${UUID.randomUUID()}")
            try {
                File(stage.path).outputStream().use { raw ->
                    val output = java.io.DataOutputStream(raw.buffered(65536))
                    // Revision is always the 32-byte source identity produced by EpubCache.
                    check(source.record.revision.length == 32)
                    output.writeInt(1);output.writeUTF(source.record.revision);output.writeInt(section.characters);output.writeInt(0);output.writeShort(0)
                    var offset = 0;var lastCut = -1L;var cutCount = 0
                    fun cut(local: Int) {
                        val value = offset.toLong() + local
                        if (value == lastCut) return
                        check(value >= lastCut && value <= section.characters)
                        if (++cutCount > 200000) throw SearchResourceLimit("EPUB 搜索段落索引超过资源预算")
                        output.writeLong(value);lastCut = value
                    }
                    val allowed = source.record.resources.mapTo(hashSetOf()) { it.id }
                    val writer = object : EpubResourceWriter {
                        override fun image(base: PackagePath, href: String): String? {
                            if (href.startsWith('#') && href.none { it.code < 32 }) return href
                            val name = if (href.startsWith("data:", true)) "image-${identity(href)}"
                                else PackagePaths.resolve(base, href)?.let { "image-${identity(it.path.value)}" } ?: return null
                            return if (name in allowed) "../resources/$name" else null
                        }
                        override fun font(base: PackagePath, href: String): String? = null
                        override fun stylesheet(base: PackagePath, href: String): String? = null
                        override fun inlineStylesheet(base: PackagePath, css: String): String? = null
                    }
                    val sanitizer = EpubSanitizer(writer, ::cut)
                    run {
                        val packageInfo = requireNotNull(query.get()) { "EPUB 搜索会话未建立" }
                        val zip = packageInfo.zip;val book = packageInfo.book
                        val item = book.spine.getOrNull(spine) ?: error("EPUB 搜索章节不存在")
                        check(item.item.path == section.path) { "EPUB 搜索章节身份不符" }
                        fun batch(safe: SanitizedSpine) {
                            var at = 0
                            while (at < safe.canonicalText.length) {
                                checkpoint();val count = minOf(16384, safe.canonicalText.length - at)
                                check(source.textSlice(spine, offset + at, count) == safe.canonicalText.substring(at, at + count)) { "EPUB 搜索源与 canonical 正文不同" }
                                at += count
                            }
                            offset += safe.canonicalText.length
                        }
                        if (source.record.layout != "pre-paginated" && zip.entries.getValue(item.item.path).size > EpubLimits.SMALL_SPINE_BYTES)
                            EpubStream.read(zip, item, sanitizer, checkpoint, ::batch)
                        else batch(sanitizer.sanitize(zip.read(item.item.path, 4L * 1024 * 1024), item.item.path, item.item.mime,
                            if (source.record.layout == "pre-paginated") 4L * 1024 * 1024 else EpubLimits.SMALL_SPINE_BYTES))
                        check(offset == section.characters) { "EPUB 搜索源长度不符" }
                    }
                    cut(section.characters - offset);output.flush();raw.fd.sync()
                }
                checkpoint()
                synchronized(EpubSearchUnits::class.java) {
                    if (valid()) stage.delete()
                    else { android.system.Os.rename(stage.path, installed.path);EpubCache.syncDirectory(directory) }
                }
            } finally { stage.delete() }
        }
        check(valid()) { "EPUB 搜索段落索引无效" }
        return RandomAccessFile(installed, "r")
    }
}

internal fun epubSearch(source: EpubSource, original: File, root: File, active: () -> Unit,
                        locator: (Int, Int) -> Locator, chapter: (Locator) -> String): BookSearch {
    val units = EpubSearchUnits(source, original)
    return BookSearch(root, source.record.revision, source.record.sections.size, { spine, _, checkpoint ->
        val cuts = units.open(spine, checkpoint)
        object : SearchDocument {
            override val id = "spine:$spine"
            override val length = source.record.sections[spine].characters.toLong()
            override fun slice(start: Long, count: Int): String { active();return source.textSlice(spine, start.toInt(), count) }
            override fun permits(range: SourceRange): Boolean {
                active();var low = 0L;var high = (cuts.length() - 48) / 8
                while (low < high) {
                    val middle = (low + high) ushr 1;cuts.seek(48 + middle * 8)
                    if (cuts.readLong() <= range.start) low = middle + 1 else high = middle
                }
                if (low == (cuts.length() - 48) / 8) return true
                cuts.seek(48 + low * 8);return cuts.readLong() >= range.end
            }
            override fun locator(range: SourceRange) = locator(spine, range.start.toInt())
            override fun chapter(range: SourceRange) = chapter(locator(range))
            override fun close() { cuts.close() }
        }
    }, active, units::lease)
}
