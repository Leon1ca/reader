package com.localreader.app.ui

import com.localreader.app.R
import com.localreader.app.ui.UiStrings.text as uiText

/** Typed panel identity; localized labels never participate in navigation. */
enum class ReaderPanel(private val titleResource: Int) {
    CONTENTS(R.string.contents), BOOKMARKS(R.string.bookmarks), READING(R.string.reading), TYPOGRAPHY(R.string.settings), SEARCH(R.string.search), ENCODING(R.string.encoding);
    val title: String get()=uiText(titleResource)
}
