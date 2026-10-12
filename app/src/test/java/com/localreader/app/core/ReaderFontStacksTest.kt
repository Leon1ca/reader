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
        assertEquals("profile", ReaderFontStacks.choose("default", "profile"))
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
    @Test fun defaultCjkFallbackIsSystemSansNotTheBundledKaiFace() {
        val defaultSans = WebTypographyProfiles.readestTxtCjk.fontFamily
        assertTrue(defaultSans.indexOf("Noto Sans CJK SC") in 0 until defaultSans.indexOf("LXGW"))
    }
}

class DisplayPreferencesTest {
    @Test fun pageTurnDefaultsToTheEngineBehaviourAndSurvivesCopy() {
        assertEquals(PageTurn.NONE, DisplayPreferences().pageTurn)
        assertEquals(PageTurn.CURL, DisplayPreferences().copy(pageTurn = PageTurn.CURL).pageTurn)
    }
}
