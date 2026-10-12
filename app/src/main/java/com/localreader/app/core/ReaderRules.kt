package com.localreader.app.core

enum class ReaderFlow(val wire: String) { PAGINATED("paginated"), SCROLLED("scrolled") }
data class ReaderRules(val schemaVersion: Int = 1, val fontSize: Float = 16f, val theme: String = "white", val flow: ReaderFlow = ReaderFlow.PAGINATED,
    val epubStyleMode: String = "AUTHOR",val lineHeight: Double = 1.4,val fontFamily: String = "default",val margin: String = "M",val columns: String = "auto",val fxlZoom: String="fit-page",val spreadPreference: String="AUTO") {
    init { require(fxlZoom in setOf("fit-page","fit-width") || fxlZoom.toDoubleOrNull()?.let { it.isFinite() && it in 1.0..5.0 }==true);require(spreadPreference in setOf("AUTO","SINGLE"));require(schemaVersion == 1 && fontSize in 12f..36f && theme in ReaderThemes.labels && epubStyleMode in setOf("AUTHOR","CUSTOM") && lineHeight.isFinite() && lineHeight in 1.2..2.4) }
}
data class RendererGeometry(val gap: String = "7%", val marginTop: String = "24px", val marginBottom: String = "24px",
                            val marginLeft: String = "16px", val marginRight: String = "16px", val maxColumns: Int = 1)
data class CompiledRules(val profileId: String, val beforeAuthor: String, val afterAuthor: String, val geometry: RendererGeometry = RendererGeometry())

/** Original standards CSS compiled from independently documented behavioral parameters. */
object RuleCompiler {
    fun compile(kind: WebContentKind, rules: ReaderRules, fontUrl: String, bundledFontFaces: String = ""): CompiledRules {
        require(fontUrl.startsWith("https://appassets.androidplatform.net/"))
        if(kind==WebContentKind.FIXED)return CompiledRules("fxl-author-v1","","",RendererGeometry())
        if(kind==WebContentKind.EPUB)return compileEpub(rules,fontUrl,bundledFontFaces)
        val profile = if (kind == WebContentKind.TXT) WebTypographyProfiles.readestTxtCjk else WebTypographyProfiles.readestArticleCjk
        val bg = ReaderThemes.background(rules.theme)
        val fg = ReaderThemes.foreground(rules.theme)
        // Inline declarations make the entire FontFaceSet known before fonts.ready.
        // Loading font CSS via @import could report Ready before that stylesheet arrives.
        val fontBase = fontUrl.substringBeforeLast('/') + "/"
        var fonts = bundledFontFaces
        BundledFonts.files.filter { it.endsWith(".ttf") }.forEach { fonts = fonts.replace("url('$it')","url('$fontBase$it')") }
        val before = "$fonts html,body{margin:0;padding:0;} body{font-weight:400;} img,svg{max-width:100%;height:auto;} " +
            "h1,h2,h3{line-height:1.3;} pre{white-space:pre-wrap;} blockquote{margin:1em 1.5em;}"
        val after = ":root{font-size:${rules.fontSize * profile.mobileScale}px;color-scheme:${if (rules.theme == "dark") "dark" else "light"};}" +
            "html,body{background:$bg;color:$fg;overflow-wrap:break-word;} body{font-family:${ReaderFontStacks.choose(rules.fontFamily,profile.fontFamily)};letter-spacing:0;word-spacing:0;}" +
            "p{margin:0.6em 0;line-height:${rules.lineHeight};text-align:left;hyphens:manual;white-space:normal;text-indent:0;}" +
            "p.continuation{text-indent:0;margin-top:0;} p.continues{margin-bottom:0;} h2{font-size:1.5em;text-indent:0;}" +
            "blockquote p,li p{text-indent:0;} li{line-height:${rules.lineHeight};} pre,code{font-family:monospace;line-height:1.5;text-indent:0;}"
        return CompiledRules("${profile.id}/rules-v${rules.schemaVersion}/layout-v2", before, after, RendererGeometry(marginLeft=margin(rules),marginRight=margin(rules),maxColumns=if(rules.columns=="one")1 else 2))
    }
    private fun margin(rules: ReaderRules)=when(rules.margin) { "S" -> "16px";"L" -> "40px";else -> "26px" }
    private fun compileEpub(rules: ReaderRules,fontUrl: String,faces: String): CompiledRules {
        val base=fontUrl.substringBeforeLast('/')+"/"
        var fonts=faces
        BundledFonts.files.filter { it.endsWith(".ttf") }.forEach { fonts=fonts.replace("url('$it')","url('$base$it')") }
        val profile=WebTypographyProfiles.readestArticleCjk
        val family=ReaderFontStacks.choose(rules.fontFamily,profile.fontFamily)
        val bg=ReaderThemes.background(rules.theme)
        val fg=ReaderThemes.foreground(rules.theme)
        // Zero-specificity defaults precede the package's sanitized stylesheets.
        // AUTHOR retains its own fonts, colors, dimensions and semantic markup.
        val before=fonts+":where(html,body){margin:0;padding:0;} :where(html){font-size:${rules.fontSize*profile.mobileScale}px;font-family:$family;line-height:${rules.lineHeight};color:$fg;background:$bg;text-indent:0;text-align:left;hyphens:manual;}"+
            ":where(p){margin:0.6em 0;} :where(h1,h2,h3,h4,h5,h6,pre,li){text-indent:0;} :where(img,svg){max-width:100%;} :where(pre){white-space:pre-wrap;}"
        val root=":root{font-size:${rules.fontSize*profile.mobileScale}px;color-scheme:${if(rules.theme=="dark")"dark" else "light"};} html{background:$bg;color:$fg;}"
        val after=if(rules.epubStyleMode=="AUTHOR")":root{color-scheme:${if(rules.theme=="dark")"dark" else "light"};}" else root+
            "body{font-family:$family;line-height:${rules.lineHeight};color:$fg;background:$bg;overflow-wrap:break-word;}"+
            "p{margin:0.6em 0;line-height:${rules.lineHeight};text-align:left;text-indent:0;hyphens:manual;} blockquote p,li p{text-indent:0;} pre,code{font-family:monospace;text-indent:0;}"
        val side=margin(rules)
        return CompiledRules("epub-${rules.epubStyleMode.lowercase()}/rules-v${rules.schemaVersion}/layout-v2",before,after,
            RendererGeometry(marginLeft=side,marginRight=side,maxColumns=if(rules.columns=="one")1 else 2))
    }

}

/**
 * Bitter and Roboto only cover Latin, so Chinese text used to fall straight through to the bundled
 * LXGW face for every option and the choice looked broken. Each stack now names the system CJK
 * family of its style before the bundled face, which stays the last resort and the 楷体 option.
 */
object ReaderFontStacks {
    private const val KAI = "'LXGW WenKai GB Screen'"
    const val SERIF = "'Bitter', 'Noto Serif CJK SC', 'Noto Serif SC', 'Source Han Serif SC', 'Songti SC', serif, $KAI"
    const val SANS = "'Roboto', 'Noto Sans CJK SC', 'Noto Sans SC', 'Source Han Sans SC', 'PingFang SC', sans-serif, $KAI"
    const val KAI_STACK = "$KAI, 'Kaiti SC', serif"
    /** "default" keeps the reading profile's own stack, which is the sans style. */
    fun choose(family: String, profileDefault: String) = when(family) { "serif" -> SERIF;"sans" -> SANS;"kai" -> KAI_STACK;else -> profileDefault }
}
