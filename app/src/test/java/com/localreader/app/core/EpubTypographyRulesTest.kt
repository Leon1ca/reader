package com.localreader.app.core

import com.localreader.app.formats.epub.EpubCss
import com.localreader.app.formats.epub.EpubStyleMode
import org.junit.Assert.*
import org.junit.Test

/** Readest-aligned handling of a book's own typography: generic fonts, paragraph indent and routine at-rule notices. */
class EpubTypographyRulesTest {
    private val url = "https://appassets.androidplatform.net/reader/session/rev/fonts/readest.css"
    private fun css(rules: ReaderRules, language: String?) = RuleCompiler.compile(WebContentKind.EPUB, rules, url, "", language).let { it.beforeAuthor to it.afterAuthor }

    // Item 5: the font choice is independent of whether the book's layout is used.
    @Test fun systemAndGenericFamiliesBecomeTheReadersFontWhileEmbeddedAndMonospaceFamiliesStay() {
        val keep = setOf("my book font")
        assertEquals("body{font-family:var(--reader-font)}", EpubFontFamilies.redirectCss("body{font-family:serif}"))
        assertEquals("a book that asks for a system font it will not get follows the reader's choice", "p{font-family:var(--reader-font)}", EpubFontFamilies.redirectCss("p{font-family:\"Songti SC\", \"Noto Serif CJK SC\", serif}"))
        assertEquals("h1{font-family:var(--reader-font) !important}", EpubFontFamilies.redirectCss("h1{font-family:Georgia, sans-serif !important}"))
        assertEquals("code{font-family:monospace}", EpubFontFamilies.redirectCss("code{font-family:monospace}"))
        assertEquals("p{font-family:'My Book Font', var(--reader-font)}", EpubFontFamilies.redirectCss("p{font-family:'My Book Font', Georgia, serif}", keep))
        assertEquals("pre{font-family:Courier, monospace}".replace("Courier, ", "var(--reader-font), "), EpubFontFamilies.redirectCss("pre{font-family:Courier, monospace}"))
        val face = "@font-face{font-family:'My Book Font';src:url(a.ttf)}"
        assertEquals("the book's own @font-face is never rewritten", face + "p{font-family:var(--reader-font)}", EpubFontFamilies.redirectCss(face + "p{font-family:Georgia}", setOf()))
        assertEquals(setOf("my book font"), EpubFontFamilies.embeddedFamilies(face + "p{font-family:serif}"))
        assertEquals("a font the book embeds stays in use", "p{font-family:'My Book Font'}", EpubFontFamilies.redirectCss(face + "p{font-family:'My Book Font'}").removePrefix(face))
        assertEquals("inherit and variables are left alone", "p{font-family:inherit}", EpubFontFamilies.redirectCss("p{font-family:inherit}"))
    }
    // UIF-03: only a real start-tag style attribute is rewritten; text that looks like one is canonical text and must not change.
    @Test fun onlyRealStyleAttributesAreRewrittenAndLiteralTextIsUntouched() {
        assertEquals("<p style=\"color:red;font-family:var(--reader-font)\">x</p>", EpubFontFamilies.redirectHtml("<p style=\"color:red;font-family:serif\">x</p>"))
        assertEquals("<p class=\"a\" id=\"b\" style=\"font-family: var(--reader-font)\">x</p>", EpubFontFamilies.redirectHtml("<p class=\"a\" id=\"b\" style=\"font-family: serif\">x</p>"))
        val literal = "<p>Write style=\"font-family: serif;\" on the element.</p><pre>style=\"font-family: serif;\"</pre>"
        assertSame("body text that merely looks like a style attribute is left exactly as it is", literal, EpubFontFamilies.redirectHtml(literal))
        val mixed = "<p style=\"font-family:serif\">style=\"font-family: serif;\"</p>"
        assertEquals("<p style=\"font-family:var(--reader-font)\">style=\"font-family: serif;\"</p>", EpubFontFamilies.redirectHtml(mixed))
        val otherAttribute = "<a title=\"style=&quot;font-family: serif&quot;\">t</a>"
        assertEquals(otherAttribute, EpubFontFamilies.redirectHtml(otherAttribute))
        assertEquals("the text length never changes", mixed.replace(Regex("<[^>]*>"), "").length, EpubFontFamilies.redirectHtml(mixed).replace(Regex("<[^>]*>"), "").length)
    }
    @Test fun theCompiledRulesDefineTheVariableWithTheChosenStack() {
        assertTrue(css(ReaderRules(), null).first.contains(":root{--reader-font:${ReaderFontStacks.DEFAULT};}"))
        assertTrue(css(ReaderRules(fontFamily = "serif"), null).first.contains(":root{--reader-font:${ReaderFontStacks.SERIF};}"))
        val id = "0b8f3d3e-0000-4000-8000-000000000001"
        assertTrue(css(ReaderRules(fontFamily = "user:$id"), null).first.contains("--reader-font:'${ReaderFontStacks.USER_FAMILY}', ${ReaderFontStacks.DEFAULT};"))
    }

    // Item 4: first-line indent for CJK books, as in Readest, without touching the book's own rules.
    @Test fun cjkBooksGetAZeroSpecificityTwoEmIndentWithTheUsualExemptions() {
        listOf("zh-CN", "zh", "ja", "ko-KR", "zh_TW").forEach { language ->
            val (before, after) = css(ReaderRules(), language)
            assertTrue(language, before.contains(":where(p){text-indent:2em;}"))
            assertTrue(before.contains(":where(blockquote p,li p,td p,th p,.noindent,.nonindent){text-indent:0;}"))
            assertTrue(before.contains(":where(p:has(> img:only-child)"))
            assertFalse("author mode forces nothing", after.contains("text-indent"))
        }
        listOf("en", "en-US", "fr", null, "").forEach { language -> assertFalse("$language", css(ReaderRules(), language).first.contains("text-indent:2em")) }
    }
    @Test fun customModeNoLongerForcesAlignmentOrIndentSoTheBooksCenteringAndIndentSurvive() {
        val custom = ReaderRules(epubStyleMode = "CUSTOM")
        listOf("zh-CN", "en").forEach { language ->
            val (before, after) = css(custom, language)
            assertFalse("$language: no forced left alignment", after.contains("text-align:left"))
            assertFalse("$language: no forced zero indent on every paragraph", Regex("p\\{[^}]*text-indent:0;[^}]*hyphens").containsMatchIn(after))
            assertTrue(after.contains("blockquote p,li p,td p,th p,.noindent,.nonindent{text-indent:0;}"))
            assertEquals(language.startsWith("zh"), before.contains(":where(p){text-indent:2em;}"))
        }
        assertTrue("CJK books justify by default like Readest", css(ReaderRules(), "zh-CN").first.contains("text-align:justify"))
        assertTrue(css(ReaderRules(), "en").first.contains("text-align:left"))
        assertNotEquals("the profile id tells the two layouts apart", RuleCompiler.compile(WebContentKind.EPUB, custom, url, "", "zh-CN").profileId, RuleCompiler.compile(WebContentKind.EPUB, custom, url, "", "en").profileId)
    }
    @Test fun customStylesheetsKeepTheBooksAlignmentAndIndentButStillDropFontsSizesAndColours() {
        val filtered = EpubCss({ _, _ -> null }).filter(".t{text-align:center;text-indent:0;color:red;font-size:2em;line-height:3;font-family:serif;letter-spacing:1px;margin:0 5%}",EpubStyleMode.CUSTOM).text
        assertTrue(filtered, filtered.contains("text-align:center") && filtered.contains("text-indent:0") && filtered.contains("margin:0 5%"))
        listOf("color","font-size","line-height","font-family","letter-spacing").forEach { assertFalse(it,filtered.contains("$it:")) }
        assertEquals("p{text-indent:2em;text-align:justify}",EpubCss({ _, _ -> null }).inline("text-indent:2em;text-align:justify;color:red",EpubStyleMode.CUSTOM).let { "p{$it}" }.replace(";}","}"))
    }
    @Test fun isCjkReadsTheDeclaredLanguageOnly() {
        assertTrue(RuleCompiler.isCjk("zh-Hans-CN")); assertTrue(RuleCompiler.isCjk(" JA "))
        assertFalse(RuleCompiler.isCjk("en-GB")); assertFalse(RuleCompiler.isCjk(null)); assertFalse(RuleCompiler.isCjk("zho-x"))
    }

    // Item 3: routine CSS at-rules never become a notice.
    @Test fun unsupportedAtRulesAreDroppedSilently() {
        val filter = EpubCss({ _, _ -> null })
        val result = filter.filter("@charset \"utf-8\"; @namespace x url(a); @page{margin:0} @keyframes k{from{top:0}to{top:1}} p{color:red}")
        assertEquals(emptyList<String>(), result.warnings)
        assertTrue(result.text, result.text.contains("color:red"))
        assertFalse(result.text.contains("@page"))
    }
    @Test fun alreadyImportedBooksDoNotShowTheOldAtRuleNotices() {
        val stored = listOf("不支持的 CSS at-rule 已移除：page", "不支持的 CSS at-rule 已移除：keyframes", "外部、缺失或循环 CSS import 已阻止", "包内图像损坏或尺寸超限：x.png")
        assertEquals(listOf("外部、缺失或循环 CSS import 已阻止", "包内图像损坏或尺寸超限：x.png"), EpubNotices.visible(stored))
    }
}
