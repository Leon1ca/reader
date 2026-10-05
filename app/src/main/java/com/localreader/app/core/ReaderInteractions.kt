package com.localreader.app.core

import java.io.InputStream

/** The host sends opaque identities; the format revalidates them against its sanitized document. */
sealed interface ContentInteraction {
    val section: Int
    data class Link(override val section: Int,val identity: String,val external: Boolean=false): ContentInteraction
    data class Image(override val section: Int,val elementId: String): ContentInteraction
    data class Selection(override val section: Int,val start: RendererAnchor,val end: RendererAnchor,val text: String,val ranges: List<VisibleRangeCodec.Interval>): ContentInteraction
}
data class NoteContent(val text: String,val sourceLabel: String,val jump: Locator)
interface ImageSource : java.io.Closeable {
    val mime: String
    val bytes: Long
    val alt: String
    val width: Int? get()=null
    val height: Int? get()=null
    val index: Int?
    val count: Int?
    fun open(): InputStream
}
sealed interface InteractionResult {
    data class Navigate(val locator: Locator): InteractionResult
    data class Note(val content: NoteContent): InteractionResult
    data class Image(val source: ImageSource): InteractionResult
    data class External(val url: String): InteractionResult
    data class Notice(val message: String): InteractionResult
    data class Selection(val text: String,val start: Locator,val end: Locator): InteractionResult
}
interface BookInteractions : java.io.Closeable {
    fun resolve(document: ReaderPublication,request: ContentInteraction,origin: Locator,checkpoint: ()->Unit): InteractionResult
    fun prepare(locator: Locator,origin: Locator?,checkpoint: ()->Unit): Locator = locator
    fun activate(document: ReaderPublication) { }
    fun publication(locator: Locator): ReaderPublication?
    fun accepts(locator: Locator): Boolean
    fun excerpt(locator: Locator,limit: Int): String
}
sealed interface ReaderUiEvent {
    data class Note(val content: NoteContent): ReaderUiEvent
    data class Image(val source: ImageSource): ReaderUiEvent
    data class ExternalLink(val url: String): ReaderUiEvent
    data class Selection(val text: String,val start: Locator,val end: Locator): ReaderUiEvent
    data class Notice(val message: String): ReaderUiEvent
}
