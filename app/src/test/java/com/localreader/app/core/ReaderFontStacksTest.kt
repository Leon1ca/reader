package com.localreader.app.core

import org.junit.Assert.*
import org.junit.Test

class ReaderFontStacksTest {
    private val url = "https://appassets.androidplatform.net/reader/session/rev/fonts/readest.css"
    @Test fun everyChoiceNamesItsOwnSystemCjkFamilyBeforeTheBundledKaiFace() {
        val serif = ReaderFontStacks.SERIF
        val sans = ReaderFontStacks.SANS
        assertTrue(serif.indexOf("Noto Serif CJK SC") in 0 until serif.indexOf("LXGW"))
        assertTrue(sans.indexOf("Noto Sans CJK SC") in 0 until sans.indexOf("LXGW"))
        assertFalse(sans.contains("Serif"))
        assertTrue(ReaderFontStacks.KAI_STACK.startsWith("'LXGW WenKai GB Screen'"))
        assertEquals(ReaderFontStacks.DEFAULT, ReaderFontStacks.choose("default", "profile"))
        assertEquals("an unknown stored value reads as the default", ReaderFontStacks.DEFAULT, ReaderFontStacks.choose("nonsense"))
    }
    @Test fun compiledRulesCarryTheSelectedStackForTextAndEpub() {
        listOf(WebContentKind.TXT, WebContentKind.EPUB).forEach { kind ->
            val serif = RuleCompiler.compile(kind, ReaderRules(fontFamily = "serif"), url, "")
            val sans = RuleCompiler.compile(kind, ReaderRules(fontFamily = "sans"), url, "")
            val css = { rules: CompiledRules -> rules.beforeAuthor + rules.afterAuthor }
            assertTrue(css(serif).contains(ReaderFontStacks.SERIF))
            assertTrue(css(sans).contains(ReaderFontStacks.SANS))
            assertFalse(css(serif).contains(ReaderFontStacks.SANS))
        }
    }
    @Test fun defaultBodyFontIsRobotoForLatinThenLxgwForChineseAndNoSystemFontInBetween() {
        val stack = ReaderFontStacks.DEFAULT
        assertTrue(stack.startsWith("'Roboto', 'LXGW WenKai GB Screen'"))
        assertFalse("Chinese must not be handed to a system sans", stack.contains("Noto Sans CJK"))
        listOf(WebContentKind.TXT, WebContentKind.EPUB).forEach { kind ->
            val css = RuleCompiler.compile(kind, ReaderRules(), url, "").let { it.beforeAuthor + it.afterAuthor }
            assertTrue("default rules use the default stack ($kind)", css.contains(stack))
            // Choosing 宋体 or 黑体 explicitly is still honoured, and so is a stored legacy 楷体 choice.
            assertTrue(RuleCompiler.compile(kind, ReaderRules(fontFamily = "kai"), url, "").let { it.beforeAuthor + it.afterAuthor }.contains(ReaderFontStacks.KAI_STACK))
        }
    }
    @Test fun importedFontFallsBackToTheDefaultStackForMissingGlyphs() {
        val id = "0b8f3d3e-0000-4000-8000-000000000001"
        assertEquals("'ReaderUserFont', ${ReaderFontStacks.DEFAULT}", ReaderFontStacks.choose("user:$id"))
    }
    @Test fun listOffersDefaultSerifAndSansAndShowsLegacyKaiAsTheDefaultEntry() {
        assertEquals(listOf("default", "serif", "sans"), ReaderFontStacks.BUILT_IN)
        assertEquals("default", ReaderFontStacks.displayChoice("kai"))
        assertEquals("serif", ReaderFontStacks.displayChoice("serif"))
    }
}

class DisplayPreferencesTest {
    @Test fun pageTurnDefaultsToTheEngineBehaviourAndSurvivesCopy() {
        assertEquals(PageTurn.NONE, DisplayPreferences().pageTurn)
        assertEquals(PageTurn.CURL, DisplayPreferences().copy(pageTurn = PageTurn.CURL).pageTurn)
    }
}
