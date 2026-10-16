package com.localreader.app.core

import org.junit.Assert.*
import org.junit.Test

/** Item 4 (2026-10-16): compact chapter headings for TXT, EPUB author styles untouched. */
class HeadingRulesTest {
    private val url = "https://appassets.androidplatform.net/reader/session/rev/fonts/readest.css"
    private val heading = "h2{font-size:1.25em;font-weight:600;line-height:1.35;margin:0.5em 0;text-indent:0;}"

    @Test fun txtHeadingsAreCompactAndTheProfileMovesToLayoutV4() {
        val compiled = RuleCompiler.compile(WebContentKind.TXT,ReaderRules(),url,"")
        assertTrue(compiled.afterAuthor.contains(heading))
        assertFalse(compiled.afterAuthor.contains("font-size:1.5em"))
        assertTrue(compiled.profileId.contains("/layout-v6"))
    }
    @Test fun userBodySettingsStayAsChosen() {
        val css = RuleCompiler.compile(WebContentKind.TXT,ReaderRules(fontSize = 20f,lineHeight = 1.8,paragraphSpacing = 1.2),url,"").afterAuthor
        assertTrue(css.contains("p{margin:1.2em 0;line-height:1.8;"))
        assertTrue(css.contains(heading))
    }
    @Test fun epubAuthorAndCustomModesKeepTheirHeadings() {
        listOf("AUTHOR","CUSTOM").forEach { mode ->
            val compiled = RuleCompiler.compile(WebContentKind.EPUB,ReaderRules(epubStyleMode = mode),url,"","zh-CN")
            assertFalse(mode,(compiled.beforeAuthor + compiled.afterAuthor).contains(heading))
            assertTrue(mode,compiled.profileId.contains("/layout-v5"))
        }
    }
}
