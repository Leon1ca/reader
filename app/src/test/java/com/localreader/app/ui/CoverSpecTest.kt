package com.localreader.app.ui

import com.localreader.app.ui.shelf.CoverSpec
import com.localreader.app.core.*
import org.junit.Assert.*
import org.junit.Test

class CoverSpecTest {
    @Test fun titleNormalizationSeedPaletteAndHanLayoutsHaveIndependentExpectations() {
        assertEquals(3,CoverSpec.of("hello").palette)
        assertEquals(CoverSpec.of("é"),CoverSpec.of("e\u0301"))
        val cases=listOf("" to (false to false),"😀" to (false to false),"中文A" to (false to false),
            "天地玄黄宇宙" to (true to true),"天地玄黄宇宙洪荒" to (true to false),"天地玄黄宇宙洪荒日" to (false to false),"かな" to (false to false))
        cases.forEach { (title,expected) -> val value=CoverSpec.of(title);assertEquals(title,expected.first,value.vertical);assertEquals(title,expected.second,value.innerBorder) }
        assertEquals((0..11).toSet(),(0..1000).map {CoverSpec.of("原创书名$it").palette}.toSet())
        assertEquals(12,CoverSpec.colors.size)
    }
    @Test fun fiveBackgroundsCompileAndTxtBookTypographyReallyAffectsOutput() {
        ReaderThemes.labels.keys.forEach {theme -> val rules=ReaderRules(theme=theme,fontFamily="sans",lineHeight=2.0,margin="L")
            val compiled=RuleCompiler.compile(WebContentKind.TXT,rules,"https://appassets.androidplatform.net/session/fonts/readest.css")
            assertTrue(compiled.afterAuthor.contains(ReaderThemes.background(theme)))
            assertTrue(compiled.afterAuthor.contains(ReaderThemes.foreground(theme)))
            assertTrue(compiled.afterAuthor.contains("line-height:2.0"));assertTrue(compiled.afterAuthor.contains("'Roboto'"))
            assertEquals("40px",compiled.geometry.marginLeft)
        }
    }
}
