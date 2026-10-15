package com.localreader.app.data

import com.localreader.app.core.ContentFormat
import com.localreader.app.core.Locator

data class TxtMetadata(val encoding: String, val paragraphs: Int, val characters: Long)
/** TXT mutation projection only; it is not the general book model or a second write store. */
data class TxtRecord(val id: String, val title: String, val author: String, val hash: String, val bytes: Long,
    val imported: Long, val lastRead: Long, val encoding: String, val version: String, val paragraphs: Int,
    val characters: Long, val current: Locator.Text, val furthest: Long, val fontSize: Float, val theme: String,
    val flow: String = "paginated")
/** An imported reading font. [family] is the name the font reports; [id] is what rules and URLs use. */
data class UserFont(val id: String,val name: String,val family: String,val extension: String,val bytes: Long,val importedAt: Long)
data class Bookmark(val id: Long, val bookId: String, val locator: Locator, val excerpt: String, val created: Long)
