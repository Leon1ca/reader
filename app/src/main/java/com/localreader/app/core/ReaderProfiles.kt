package com.localreader.app.core

enum class WebContentKind { TXT, ARTICLE, EPUB, FIXED }
data class WebTypographyProfile(val id: String, val kind: WebContentKind, val fontFamily: String,
                                val mobileScale: Float = 1.25f, val lineHeight: Float = 1.6f,
                                val paragraphMarginEm: Float = 1f, val indentEm: Float = 2f,
                                val justify: Boolean = true)
object WebTypographyProfiles {
    private const val SANS = "Roboto, 'LXGW WenKai GB Screen', 'Noto Sans', 'Open Sans', 'PT Sans', Helvetica, 'Noto Sans SC', 'Noto Sans TC', 'MiSans L3', sans-serif"
    val readestTxtCjk = WebTypographyProfile("readest-0.12.10-txt-cjk", WebContentKind.TXT, "Arial, $SANS")
    val readestArticleCjk = WebTypographyProfile("readest-0.12.10-article-cjk", WebContentKind.ARTICLE,
        "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Oxygen-Sans', Ubuntu, Cantarell, 'Helvetica Neue', $SANS")
}
