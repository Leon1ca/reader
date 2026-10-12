package com.localreader.app.core

enum class WebContentKind { TXT, ARTICLE, EPUB, FIXED }
data class WebTypographyProfile(val id: String, val kind: WebContentKind, val fontFamily: String,
                                val mobileScale: Float = 1.25f, val lineHeight: Float = 1.4f,
                                val paragraphMarginEm: Float = 0.6f, val indentEm: Float = 0f,
                                val justify: Boolean = false)
object WebTypographyProfiles {
    private const val SANS = "Roboto, 'Noto Sans CJK SC', 'Noto Sans SC', 'Source Han Sans SC', 'PingFang SC', 'MiSans L3', 'Noto Sans', 'Open Sans', 'PT Sans', Helvetica, sans-serif, 'LXGW WenKai GB Screen'"
    val readestTxtCjk = WebTypographyProfile("readest-0.12.10-txt-cjk", WebContentKind.TXT, SANS)
    val readestArticleCjk = WebTypographyProfile("readest-0.12.10-article-cjk", WebContentKind.ARTICLE,
        "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Oxygen-Sans', Ubuntu, Cantarell, 'Helvetica Neue', $SANS")
}
