package com.localreader.app.core

/** Format-specific locators must never be interpreted by another engine. */
sealed interface Locator {
    val version: String
    data class Text(override val version: String, val sourceOffset: Long, val paragraph: Int,
                    val character: Int = 0, val fallbackFraction: Double = 0.0) : Locator
    data class Page(override val version: String, val page: Int, val x: Double, val y: Double) : Locator
    data class Comic(override val version: String, val imageId: String, val order: Int, val y: Double) : Locator
    /** Structured content uses authored block IDs; never a TXT offset or renderer page number. */
    data class Article(override val version: String, val blockId: String, val textOffset: Int, val element: Boolean = false) : Locator
}

data class TextBlock(val index: Int, val text: String, val sourceOffset: Long, val heading: Boolean,
                     val continuation: Boolean = false, val continues: Boolean = false)
data class Chapter(val title: String, val paragraph: Int, val sourceOffset: Long)
data class TextIndex(val encoding: String, val paragraphs: Int, val totalCharacters: Long, val chapters: List<Chapter>)
data class ResolvedText(val paragraph: Int, val character: Int, val nearby: Boolean)
data class TxtBlockMetadata(val utf8Bytes: Int, val continuation: Boolean)

interface TxtTextSource : java.io.Closeable {
    val version: String
    val index: TextIndex
    fun block(paragraph: Int): TextBlock
    fun resolve(locator: Locator): ResolvedText
    fun metadata(paragraph: Int): TxtBlockMetadata = block(paragraph).let { TxtBlockMetadata(it.text.toByteArray().size,it.continuation) }
}
