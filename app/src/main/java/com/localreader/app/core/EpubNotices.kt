package com.localreader.app.core

/** Notices worth showing a reader. Removed at-rules (@page and the like) are routine in real books and Readest never mentions them. */
object EpubNotices {
    private const val AT_RULE = "不支持的 CSS at-rule 已移除"
    fun visible(warnings: List<String>): List<String> = warnings.filterNot { it.startsWith(AT_RULE) }
}
