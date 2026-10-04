package com.localreader.app.core

enum class ReaderFlow(val wire: String) { PAGINATED("paginated"), SCROLLED("scrolled") }
data class ReaderRules(val schemaVersion: Int = 1, val fontSize: Float = 16f, val theme: String = "white", val flow: ReaderFlow = ReaderFlow.PAGINATED,
    val epubStyleMode: String = "AUTHOR",val lineHeight: Double = 1.6,val fontFamily: String = "default",val margin: String = "M",val columns: String = "auto",val fxlZoom: String="fit-page",val spreadPreference: String="AUTO") {
    init { require(fxlZoom in setOf("fit-page","fit-width") || fxlZoom.toDoubleOrNull()?.let { it.isFinite() && it in 1.0..5.0 }==true);require(spreadPreference in setOf("AUTO","SINGLE"));require(schemaVersion == 1 && fontSize in 12f..36f && theme in setOf("white", "paper", "dark") && epubStyleMode in setOf("AUTHOR","CUSTOM") && lineHeight.isFinite() && lineHeight in 1.2..2.4) }
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
    private fun compileEpub(rules: ReaderRules,fontUrl: String,faces: String): CompiledRules {
        val base=fontUrl.substringBeforeLast('/')+"/"
        var fonts=faces
        BundledFonts.files.filter { it.endsWith(".ttf") }.forEach { fonts=fonts.replace("url('$it')","url('$base$it')") }
        val profile=WebTypographyProfiles.readestArticleCjk
        val family=when(rules.fontFamily) {
            "serif" -> "'Bitter', 'LXGW WenKai GB Screen', serif"
            "sans" -> "'Roboto', 'LXGW WenKai GB Screen', sans-serif"
            "kai" -> "'LXGW WenKai GB Screen', serif"
            else -> profile.fontFamily
        }
        val bg=when(rules.theme) { "dark" -> "#19201c";"paper" -> "#f6f1e7";else -> "#ffffff" }
        val fg=if(rules.theme=="dark")"#dbe5dd" else "#272c28"
        // Zero-specificity defaults precede the package's sanitized stylesheets.
        // AUTHOR retains its own fonts, colors, dimensions and semantic markup.
        val before=fonts+":where(html,body){margin:0;padding:0;} :where(body){font-family:$family;line-height:${rules.lineHeight};color:$fg;background:$bg;}"+
            ":where(p){margin:1em 0;text-indent:2em;text-align:justify;} :where(img,svg){max-width:100%;} :where(pre){white-space:pre-wrap;}"
        val root=":root{font-size:${rules.fontSize*profile.mobileScale}px;color-scheme:${if(rules.theme=="dark")"dark" else "light"};} html{background:$bg;color:$fg;}"
        val after=if(rules.epubStyleMode=="AUTHOR")root else root+
            "body{font-family:$family;line-height:${rules.lineHeight};color:$fg;background:$bg;overflow-wrap:break-word;}"+
            "p{line-height:${rules.lineHeight};text-align:justify;text-indent:2em;} blockquote p,li p{text-indent:0;} pre,code{font-family:monospace;text-indent:0;}"
        val side=when(rules.margin) { "S" -> "8px";"L" -> "32px";else -> "16px" }
        return CompiledRules("epub-${rules.epubStyleMode.lowercase()}/rules-v${rules.schemaVersion}",before,after,
            RendererGeometry(marginLeft=side,marginRight=side,maxColumns=if(rules.columns=="one")1 else 2))
    }

}
