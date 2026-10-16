package com.localreader.app.core

enum class ReaderFlow(val wire: String) { PAGINATED("paginated"), SCROLLED("scrolled") }
data class ReaderRules(val schemaVersion: Int = 1, val fontSize: Float = 16f, val theme: String = "white", val flow: ReaderFlow = ReaderFlow.PAGINATED,
    val epubStyleMode: String = "AUTHOR",val lineHeight: Double = 1.4,val fontFamily: String = "default",val margin: String = "M",val columns: String = "auto",val fxlZoom: String="fit-page",val spreadPreference: String="AUTO",
    /** Space between paragraphs in em; 0.6 is the long-standing default. */
    val paragraphSpacing: Double = DEFAULT_PARAGRAPH_SPACING) {
    init { require(fxlZoom in setOf("fit-page","fit-width") || fxlZoom.toDoubleOrNull()?.let { it.isFinite() && it in 1.0..5.0 }==true);require(spreadPreference in setOf("AUTO","SINGLE"));require(schemaVersion == 1 && fontSize in 12f..36f && theme in ReaderThemes.labels && epubStyleMode in setOf("AUTHOR","CUSTOM") && lineHeight.isFinite() && lineHeight in 1.2..2.4 && paragraphSpacing.isFinite() && paragraphSpacing in 0.0..2.4) }
    companion object { const val DEFAULT_PARAGRAPH_SPACING = 0.6 }
}
data class RendererGeometry(val gap: String = "4%", val marginTop: String = "24px", val marginBottom: String = "24px",
                            val marginLeft: String = "16px", val marginRight: String = "16px", val maxColumns: Int = 1)
data class CompiledRules(val profileId: String, val beforeAuthor: String, val afterAuthor: String, val geometry: RendererGeometry = RendererGeometry())

/** Original standards CSS compiled from independently documented behavioral parameters. */
object RuleCompiler {
    fun compile(kind: WebContentKind, rules: ReaderRules, fontUrl: String, bundledFontFaces: String = "", language: String? = null): CompiledRules {
        require(fontUrl.startsWith("https://appassets.androidplatform.net/"))
        if(kind==WebContentKind.FIXED)return CompiledRules("fxl-author-v1","","",RendererGeometry())
        if(kind==WebContentKind.EPUB)return compileEpub(rules,fontUrl,bundledFontFaces,language)
        val profile = if (kind == WebContentKind.TXT) WebTypographyProfiles.readestTxtCjk else WebTypographyProfiles.readestArticleCjk
        val bg = ReaderThemes.background(rules.theme)
        val fg = ReaderThemes.foreground(rules.theme)
        // Inline declarations make the entire FontFaceSet known before fonts.ready.
        // Loading font CSS via @import could report Ready before that stylesheet arrives.
        val fontBase = fontUrl.substringBeforeLast('/') + "/"
        var fonts = bundledFontFaces + userFontFace(rules,fontBase)
        BundledFonts.files.filter { it.endsWith(".ttf") }.forEach { fonts = fonts.replace("url('$it')","url('$fontBase$it')") }
        val before = "$fonts html,body{margin:0;padding:0;} body{font-weight:400;} img,svg{max-width:100%;height:auto;} " +
            "h1,h2,h3{line-height:1.3;} pre{white-space:pre-wrap;} blockquote{margin:1em 1.5em;}"
        val after = ":root{font-size:${rules.fontSize * profile.mobileScale}px;color-scheme:${if (rules.theme == "dark") "dark" else "light"};}" +
            "html,body{background:$bg;color:$fg;overflow-wrap:break-word;} body{font-family:${ReaderFontStacks.choose(rules.fontFamily,profile.fontFamily)};letter-spacing:0;word-spacing:0;}" +
            "p{margin:${rules.paragraphSpacing}em 0;line-height:${rules.lineHeight};text-align:left;hyphens:manual;white-space:normal;text-indent:0;}" +
            "p.continuation{text-indent:0;margin-top:0;} p.continues{margin-bottom:0;} h2{font-size:1.25em;font-weight:600;line-height:1.35;margin:0.5em 0;text-indent:0;}" +
            "blockquote p,li p{text-indent:0;} li{line-height:${rules.lineHeight};} pre,code{font-family:monospace;line-height:1.5;text-indent:0;}"
        // layout-v4: chapter headings 1.25em/600, line height 1.35 and half the default margin; layout-v5: side gap 4%, margins 14/22/34px (2026-10-16).
        return CompiledRules("${profile.id}/rules-v${rules.schemaVersion}/layout-v6${spacingSuffix(rules)}", before, after, RendererGeometry(marginTop="12px",marginBottom="12px",marginLeft=margin(rules),marginRight=margin(rules),maxColumns=if(rules.columns=="one")1 else 2))
    }
    /** The chosen imported font becomes one more declared face, so it is known before fonts.ready like the bundled ones. */
    private fun userFontFace(rules: ReaderRules,base: String): String = ReaderFontStacks.userFontId(rules.fontFamily)?.let { id ->
        "@font-face{font-family:'${ReaderFontStacks.USER_FAMILY}';src:url('${base}${SessionResources.USER_FONT_PREFIX}$id');font-display:swap;} "
    }.orEmpty()
    /** A different paragraph gap changes pagination, so it is part of the profile id; the default keeps existing ids and caches valid. */
    private fun spacingSuffix(rules: ReaderRules)=if(rules.paragraphSpacing==ReaderRules.DEFAULT_PARAGRAPH_SPACING)"" else "-ps${rules.paragraphSpacing}"
    /** Chinese, Japanese or Korean content, from the declared language (`zh-CN`, `ja`, `ko-KR`, ...). */
    fun isCjk(language: String?): Boolean = language?.trim()?.lowercase()?.substringBefore('-')?.substringBefore('_') in setOf("zh","ja","ko")
    private fun margin(rules: ReaderRules)=when(rules.margin) { "S" -> "14px";"L" -> "34px";else -> "22px" }
    private fun compileEpub(rules: ReaderRules,fontUrl: String,faces: String,language: String?): CompiledRules {
        val base=fontUrl.substringBeforeLast('/')+"/"
        var fonts=faces+userFontFace(rules,base)
        BundledFonts.files.filter { it.endsWith(".ttf") }.forEach { fonts=fonts.replace("url('$it')","url('$base$it')") }
        val profile=WebTypographyProfiles.readestArticleCjk
        val family=ReaderFontStacks.choose(rules.fontFamily,profile.fontFamily)
        val bg=ReaderThemes.background(rules.theme)
        val fg=ReaderThemes.foreground(rules.theme)
        // Zero-specificity defaults precede the package's sanitized stylesheets.
        // AUTHOR retains its own fonts, colors, dimensions and semantic markup.
        val cjk=isCjk(language)
        // Chinese, Japanese and Korean books indent each paragraph by two characters unless the book says otherwise (zero
        // specificity, so any rule of the book wins). Image-only, quoted, list and table paragraphs and the usual no-indent
        // classes are exempt; the :has() rule is separate so a WebView without :has() still gets the first two.
        val indent=if(cjk)":where(p){text-indent:2em;} :where(blockquote p,li p,td p,th p,.noindent,.nonindent){text-indent:0;} :where(p:has(> img:only-child),p:has(> a:only-child > img:only-child)){text-indent:0;}" else ""
        val before=fonts+":root{${EpubFontFamilies.VARIABLE}:$family;} :where(html,body){margin:0;padding:0;} "+":where(html){font-size:${rules.fontSize*profile.mobileScale}px;font-family:$family;line-height:${rules.lineHeight};color:$fg;background:$bg;text-indent:0;text-align:${if(cjk)"justify" else "left"};hyphens:manual;}"+
            ":where(p){margin:${rules.paragraphSpacing}em 0;} :where(h1,h2,h3,h4,h5,h6,pre,li){text-indent:0;} :where(img,svg){max-width:100%;} :where(pre){white-space:pre-wrap;} $indent"
        val root=":root{font-size:${rules.fontSize*profile.mobileScale}px;color-scheme:${if(rules.theme=="dark")"dark" else "light"};} html{background:$bg;color:$fg;}"
        val after=if(rules.epubStyleMode=="AUTHOR")":root{color-scheme:${if(rules.theme=="dark")"dark" else "light"};}" else root+
            "body{font-family:$family;line-height:${rules.lineHeight};color:$fg;background:$bg;overflow-wrap:break-word;}"+
            "p{margin:${rules.paragraphSpacing}em 0;line-height:${rules.lineHeight};hyphens:manual;} h1,h2,h3{line-height:1.35;margin-top:.6em;margin-bottom:.5em;} blockquote p,li p,td p,th p,.noindent,.nonindent{text-indent:0;} pre,code{font-family:monospace;text-indent:0;}"
        val side=margin(rules)
        return CompiledRules("epub-${rules.epubStyleMode.lowercase()}/rules-v${rules.schemaVersion}/layout-v5${if(cjk)"c" else ""}${spacingSuffix(rules)}",before,after,
            RendererGeometry(marginTop="12px",marginBottom="12px",marginLeft=side,marginRight=side,maxColumns=if(rules.columns=="one")1 else 2))
    }

}

/**
 * The body font. The product default is Latin in Roboto and Chinese in the bundled LXGW WenKai GB Screen face (Roboto has no
 * Chinese glyphs, so mixed text falls to LXGW per character). 宋体 and 黑体 keep their style's Latin face and name the system
 * CJK family of that style before LXGW, which stays the last resort. "kai" is no longer offered but still renders as stored.
 * Applies to reading text only; the application's own interface keeps its fonts.
 */
object ReaderFontStacks {
    private const val KAI = "'LXGW WenKai GB Screen'"
    const val SERIF = "'Bitter', 'Noto Serif CJK SC', 'Noto Serif SC', 'Source Han Serif SC', 'Songti SC', serif, $KAI"
    const val SANS = "'Roboto', 'Noto Sans CJK SC', 'Noto Sans SC', 'Source Han Sans SC', 'PingFang SC', sans-serif, $KAI"
    const val KAI_STACK = "$KAI, 'Kaiti SC', serif"
    /** Roboto for Latin, LXGW WenKai GB Screen for everything Roboto lacks (all of Chinese). */
    const val DEFAULT = "'Roboto', $KAI, sans-serif"
    /** The built-in choices the font list offers, in order; "default" is the LXGW + Roboto face. Imported fonts follow as `user:<id>`. */
    val BUILT_IN: List<String> = listOf("default","serif","sans")
    /** Which built-in entry to show as selected: a stored legacy "kai" is shown as the default (LXGW) entry; it still renders as stored. */
    fun displayChoice(family: String): String = if(family == "kai") "default" else family
    fun choose(family: String, @Suppress("UNUSED_PARAMETER") profileDefault: String = DEFAULT) = when {
        family == "serif" -> SERIF;family == "sans" -> SANS;family == "kai" -> KAI_STACK
        // The imported face first; the default stack still covers any character it lacks, or the whole page if the file is gone.
        userFontId(family) != null -> "'$USER_FAMILY', $DEFAULT"
        else -> DEFAULT
    }
    const val USER_FAMILY = "ReaderUserFont"
    private val userId = Regex("user:([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})")
    /** The id inside a `user:<id>` choice, or null for the built-in options. */
    fun userFontId(family: String): String? = userId.matchEntire(family)?.groupValues?.get(1)
}
