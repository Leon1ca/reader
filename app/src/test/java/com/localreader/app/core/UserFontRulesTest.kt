package com.localreader.app.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class UserFontRulesTest {
    private val id = "123e4567-e89b-12d3-a456-426614174000"
    private val url = "https://appassets.androidplatform.net/reader/session/rev/fonts/readest.css"
    private fun css(kind: WebContentKind,family: String) = RuleCompiler.compile(kind,ReaderRules(fontFamily = family,epubStyleMode = "CUSTOM"),url,"").let { it.beforeAuthor + it.afterAuthor }

    @Test fun onlyWellFormedUserChoicesAreRecognised() {
        assertEquals(id,ReaderFontStacks.userFontId("user:$id"))
        listOf("default","serif","user:","user:abc","user:$id/../x","USER:$id","user:${id.uppercase()}"," user:$id","user:$id ").forEach { assertNull(it,ReaderFontStacks.userFontId(it)) }
    }
    @Test fun aChosenUserFontIsDeclaredAndLeadsTheStackWithTheDefaultAsFallback() {
        listOf(WebContentKind.TXT,WebContentKind.EPUB).forEach { kind ->
            val text = css(kind,"user:$id")
            assertTrue(text.contains("@font-face{font-family:'${ReaderFontStacks.USER_FAMILY}';src:url('https://appassets.androidplatform.net/reader/session/rev/fonts/user/$id')"))
            assertTrue(text.contains("font-family:'${ReaderFontStacks.USER_FAMILY}', "))
            assertTrue("glyphs the font lacks still fall back",text.contains("'${ReaderFontStacks.USER_FAMILY}', ${ReaderFontStacks.DEFAULT}"))
            assertFalse(css(kind,"serif").contains(ReaderFontStacks.USER_FAMILY))
        }
    }
    @Test fun differentFontsGiveDifferentCssSoPaginationNeverReusesAnotherFontsLayout() {
        val other = "223e4567-e89b-12d3-a456-426614174000"
        assertNotEquals(css(WebContentKind.TXT,"user:$id"),css(WebContentKind.TXT,"user:$other"))
    }

    private val publication = ArticleSamplePublication()
    private val bytes = "font-bytes".toByteArray()
    private fun resources(source: UserFontSource?) = SessionResources("session",publication,source) { ByteArrayInputStream(byteArrayOf()) }
    private val found = UserFontSource { requested -> if(requested == id) UserFontResource("font/otf") { ByteArrayInputStream(bytes) } else null }

    @Test fun userFontsAreServedAtOneImmutableOriginOnlyToSubframesAndOnlyForKnownIds() {
        val resources = resources(found)
        val font = requireNotNull(resources.resolve(SessionResources.FONT_BASE + "user/$id",false))
        assertEquals("font/otf",font.mime);assertTrue(font.headers.getValue("Cache-Control").contains("immutable"));assertEquals("nosniff",font.headers["X-Content-Type-Options"])
        assertArrayEquals(bytes,font.open().readBytes())
        assertNull("never as a top-level document",resources.resolve(SessionResources.FONT_BASE + "user/$id",true))
        assertNull(resources.resolve(SessionResources.FONT_BASE + "user/223e4567-e89b-12d3-a456-426614174000",false))
        listOf("user/","user/../Roboto-Regular-VF.ttf","user/$id/x","user/$id.ttf","user/${id.uppercase()}","users/$id").forEach { assertNull(it,resources.resolve(SessionResources.FONT_BASE + it,false)) }
        assertNull("not under the session path",resources.resolve(resources.base + "fonts/user/$id",false))
        assertNull("without a source nothing is served",resources(null).resolve(SessionResources.FONT_BASE + "user/$id",false))
        resources.close()
        assertNull(resources.resolve(SessionResources.FONT_BASE + "user/$id",false))
    }
}
