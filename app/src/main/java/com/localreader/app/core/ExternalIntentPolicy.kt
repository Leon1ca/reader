package com.localreader.app.core

/** The parts of an incoming system intent that matter for deciding what to do with it. Plain data, so the policy is testable. */
data class ExternalIntent(val action: String?,val mimeType: String?,val dataUri: String?,val streamUris: List<String>,val text: String?,val relaunchedFromHistory: Boolean=false)

sealed interface ExternalDecision {
    /** Hand these readable content URIs to the standard import pipeline. */
    data class Import(val uris: List<String>): ExternalDecision
    /** A shared web link or plain text: saving links is not available yet, and no file is created from it. */
    data object LinkNotAvailable: ExternalDecision
    /** The sender offered something this app cannot read safely (e.g. a bare file path). */
    data object Unreadable: ExternalDecision
    data object Ignore: ExternalDecision
}

/**
 * LIB-08 / §5.1 policy for VIEW and SEND. Only `content:` URIs with a book MIME type are forwarded; the importer then
 * decides by content (magic bytes), so a lying MIME or extension cannot smuggle anything in. A TXT share that carries
 * only text is a link or a note, never a file, whatever its MIME says.
 */
object ExternalIntentPolicy {
    const val ACTION_VIEW = "android.intent.action.VIEW"
    const val ACTION_SEND = "android.intent.action.SEND"
    const val ACTION_SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE"
    const val MAX_FILES = 50
    private val bookTypes = setOf("text/plain","application/epub+zip","application/octet-stream")

    fun decide(intent: ExternalIntent): ExternalDecision {
        if(intent.relaunchedFromHistory) return ExternalDecision.Ignore // the original delivery was already handled
        val type = intent.mimeType?.substringBefore(';')?.trim()?.lowercase()
        val candidates = when(intent.action) {
            ACTION_VIEW -> listOfNotNull(intent.dataUri)
            ACTION_SEND,ACTION_SEND_MULTIPLE -> intent.streamUris
            else -> return ExternalDecision.Ignore
        }
        if(candidates.isEmpty()) {
            val text = intent.text?.trim().orEmpty()
            return if(intent.action != ACTION_VIEW && text.isNotEmpty() && (type == null || type.startsWith("text/"))) ExternalDecision.LinkNotAvailable else ExternalDecision.Ignore
        }
        if(type != null && type !in bookTypes && type != "*/*") return ExternalDecision.Ignore
        val readable = candidates.filter { it.startsWith("content://") }.distinct().take(MAX_FILES)
        return if(readable.isEmpty()) ExternalDecision.Unreadable else ExternalDecision.Import(readable)
    }
}
