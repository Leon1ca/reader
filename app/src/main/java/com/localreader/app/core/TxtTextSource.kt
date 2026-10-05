package com.localreader.app.core

data class TextBlock(val index: Int, val text: String, val sourceOffset: Long, val heading: Boolean,
                     val continuation: Boolean = false, val continues: Boolean = false)
data class Chapter(val title: String, val paragraph: Int, val sourceOffset: Long)
data class TextIndex(val encoding: String, val paragraphs: Int, val totalCharacters: Long, val chapters: List<Chapter>)
data class ResolvedText(val paragraph: Int, val character: Int, val nearby: Boolean)
data class TxtBlockMetadata(val utf8Bytes: Int, val continuation: Boolean, val sourceOffset: Long)

interface TxtTextSource : java.io.Closeable {
    val version: String
    val index: TextIndex
    fun block(paragraph: Int): TextBlock
    fun resolve(locator: Locator): ResolvedText
    fun metadata(paragraph: Int): TxtBlockMetadata = block(paragraph).let { TxtBlockMetadata(it.text.toByteArray().size,it.continuation,it.sourceOffset) }
}
