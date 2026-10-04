package com.localreader.app.core

enum class ReaderFlow(val wire: String) { PAGINATED("paginated"), SCROLLED("scrolled") }
data class ReaderRules(val schemaVersion: Int = 1, val fontSize: Float = 16f, val theme: String = "white", val flow: ReaderFlow = ReaderFlow.PAGINATED) {
    init { require(schemaVersion == 1 && fontSize in 12f..36f && theme in setOf("white", "paper", "dark")) }
}
data class RendererGeometry(val gap: String = "7%", val marginTop: String = "24px", val marginBottom: String = "24px",
                            val marginLeft: String = "16px", val marginRight: String = "16px", val maxColumns: Int = 1)
data class CompiledRules(val profileId: String, val beforeAuthor: String, val afterAuthor: String, val geometry: RendererGeometry = RendererGeometry())

/** Original standards CSS compiled from independently documented behavioral parameters. */
object RuleCompiler {
    fun compile(kind: WebContentKind, rules: ReaderRules, fontUrl: String, bundledFontFaces: String = ""): CompiledRules {
        require(fontUrl.startsWith("https://appassets.androidplatform.net/"))
        val profile = if (kind == WebContentKind.TXT) WebTypographyProfiles.readestTxtCjk else WebTypographyProfiles.readestArticleCjk
        val bg = when (rules.theme) { "dark" -> "#19201c"; "paper" -> "#f6f1e7"; else -> "#ffffff" }
        val fg = if (rules.theme == "dark") "#dbe5dd" else "#272c28"
        // Inline declarations make the entire FontFaceSet known before fonts.ready.
        // Loading font CSS via @import could report Ready before that stylesheet arrives.
        val fontBase = fontUrl.substringBeforeLast('/') + "/"
        var fonts = bundledFontFaces
        BundledFonts.files.filter { it.endsWith(".ttf") }.forEach { fonts = fonts.replace("url('$it')","url('$fontBase$it')") }
        val before = "$fonts html,body{margin:0;padding:0;} body{font-weight:400;} img,svg{max-width:100%;height:auto;} " +
            "h1,h2,h3{line-height:1.3;} pre{white-space:pre-wrap;} blockquote{margin:1em 1.5em;}"
        val after = ":root{font-size:${rules.fontSize * profile.mobileScale}px;color-scheme:${if (rules.theme == "dark") "dark" else "light"};}" +
            "html,body{background:$bg;color:$fg;overflow-wrap:break-word;} body{font-family:${profile.fontFamily};letter-spacing:0;word-spacing:0;}" +
            "p{margin:1em 0;line-height:1.6;text-align:justify;hyphens:auto;${if (kind == WebContentKind.TXT) "white-space:pre-wrap;" else ""}text-indent:2em;}" +
            "p.continuation{text-indent:0;margin-top:0;} p.continues{margin-bottom:0;} h2{font-size:1.5em;text-indent:0;}" +
            "blockquote p,li p{text-indent:0;} li{line-height:1.6;} pre,code{font-family:monospace;line-height:1.5;text-indent:0;}"
        return CompiledRules("${profile.id}/rules-v${rules.schemaVersion}", before, after)
    }
}
