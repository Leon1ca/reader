package com.localreader.app.core

import org.junit.Assert.*
import org.junit.Test

class ParagraphSpacingTest {
    private val url = "https://appassets.androidplatform.net/reader/session/rev/fonts/readest.css"
    private fun css(kind: WebContentKind,rules: ReaderRules) = RuleCompiler.compile(kind,rules,url,"").let { it.beforeAuthor + it.afterAuthor }

    @Test fun defaultSpacingIsTheLongStandingGapAndKeepsExistingProfileIds() {
        listOf(WebContentKind.TXT,WebContentKind.EPUB).forEach { kind ->
            assertTrue(css(kind,ReaderRules()).contains("margin:0.6em 0"))
            assertFalse(RuleCompiler.compile(kind,ReaderRules(),url,"").profileId.contains("-ps"))
        }
    }
    @Test fun chosenSpacingReachesEveryParagraphRuleAndChangesTheProfileId() {
        listOf(WebContentKind.TXT,WebContentKind.EPUB).forEach { kind ->
            val none = RuleCompiler.compile(kind,ReaderRules(paragraphSpacing = 0.0,epubStyleMode = "CUSTOM"),url,"")
            val wide = RuleCompiler.compile(kind,ReaderRules(paragraphSpacing = 1.2,epubStyleMode = "CUSTOM"),url,"")
            assertTrue((none.beforeAuthor + none.afterAuthor).contains("margin:0.0em 0"))
            assertTrue((wide.beforeAuthor + wide.afterAuthor).contains("margin:1.2em 0"))
            assertFalse((wide.beforeAuthor + wide.afterAuthor).contains("margin:0.6em 0"))
            assertNotEquals(none.profileId,wide.profileId)
            assertTrue(wide.profileId.endsWith("-ps1.2"))
        }
    }
    @Test fun outOfRangeSpacingIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { ReaderRules(paragraphSpacing = -0.1) }
        assertThrows(IllegalArgumentException::class.java) { ReaderRules(paragraphSpacing = 2.5) }
        assertThrows(IllegalArgumentException::class.java) { ReaderRules(paragraphSpacing = Double.NaN) }
    }
    @Test fun fixedLayoutIgnoresIt() {
        assertEquals("fxl-author-v1",RuleCompiler.compile(WebContentKind.FIXED,ReaderRules(paragraphSpacing = 1.2),url,"").profileId)
    }
}
