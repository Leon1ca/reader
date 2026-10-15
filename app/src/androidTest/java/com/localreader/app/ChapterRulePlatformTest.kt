package com.localreader.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localreader.app.core.ChapterRule
import com.localreader.app.core.ChapterRuleException
import com.localreader.app.core.ChapterRules
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** R3-01/R4-01 on the platform: rules never reach java.util.regex (which cannot be budgeted on Android); the app's own NFA bounds the work. */
@RunWith(AndroidJUnit4::class)
class ChapterRulePlatformTest {
    @Test fun escapeTricksAndQuantifiedGroupsAreRefusedBeforeAnyMatching() {
        listOf("(a+)\\Q\\E+b","(a+)\\Q\\E*","(a|a)\\E+","(a+)(?#c)+b","(.*a){12}x","^(a+)+$","(a+)+b").forEach { pattern ->
            assertThrows(pattern,ChapterRuleException::class.java) { ChapterRules.validate(ChapterRule.Pattern(pattern)) }
        }
    }
    @Test fun everyAcceptedRandomPatternReturnsQuicklyOnAdversarialLines() {
        val parts = listOf("a","a+","a*","a?","a{0,1000}","a{2,5}","(a+)","(a|aa)","(?:a|a)","(a?)","\\Q","\\E","(.*a)","[ab]+","[^a]*",".*",".+",".","\\w+","\\s*","b","|","^","$","(","(?:",")","x","\\d")
        val inputs = listOf("a".repeat(40) + "!","a".repeat(79) + "b","ab".repeat(40),"a".repeat(80),"1 ".repeat(40))
        val random = java.util.Random(20261013);var accepted = 0;var worst = 0L
        repeat(4000) {
            val pattern = (0 until 1 + random.nextInt(9)).joinToString("") { parts[random.nextInt(parts.size)] }
            if(runCatching { ChapterRules.validate(ChapterRule.Pattern(pattern)) }.isSuccess) {
                accepted++;val match = ChapterRules.matcher(ChapterRule.Pattern(pattern))
                inputs.forEach { input ->
                    val started = System.nanoTime();match(input);val millis = (System.nanoTime() - started) / 1_000_000
                    worst = maxOf(worst,millis);assertTrue("$pattern took ${millis}ms",millis < 1000)
                }
            }
        }
        assertTrue(accepted > 100);android.util.Log.i("ChapterRulePlatformTest","accepted=$accepted worst=${worst}ms")
    }
    /** R4-01: the ambiguous product that kept the platform matcher busy for more than six seconds is now answered at once. */
    @Test fun ambiguousProductThatHungThePlatformMatcherReturnsAtOnce() {
        val match = ChapterRules.matcher(ChapterRule.Pattern("a*a*a*a{0,8}a{0,8}a{0,8}b"))
        val started = System.nanoTime()
        assertFalse(match("a".repeat(79) + "!"));assertTrue(match("a".repeat(70) + "b"))
        assertTrue("took ${(System.nanoTime() - started) / 1_000_000}ms",(System.nanoTime() - started) / 1_000_000 < 1000)
    }
    /** R5-02: \d \w \s mean what the platform says (Unicode digits, CJK letters, ideographic space), exactly as before the NFA. */
    @Test fun characterClassesKeepTheirPlatformUnicodeMeaning() {
        val cases = listOf("^第\\d+章$" to "第１２章","^第\\d+章\\s+\\w+$" to "第12章\u3000起航","^\\w+$" to "序章","^\\D+$" to "第一章","^\\W+$" to "，。","^\\S+$" to "序\u3000章",
            "^[\\d.]+$" to "１.２","^[^\\s]+$" to "序章","^[\\w-]+$" to "序-章","^\\w+$" to "e\u0301","^a$" to "a\u0085","^a$" to "a\u2028","^a$" to "a\u2029","^a$" to "a\r\n","^a$" to "a\n\n","^a$" to "a\u0085\u0085","^\\d$" to "٣","^\\s$" to "\u00a0","^\\s$" to "\u2003","^[\\D]$" to "１")
        cases.forEach { (pattern,line) ->
            val expected = java.util.regex.Pattern.compile(pattern).matcher(line).find()
            assertEquals("/$pattern/ on $line",expected,ChapterRules.matcher(ChapterRule.Pattern(pattern))(line))
        }
        // The platform really is Unicode-aware here, so the cases above are not all trivially false.
        assertTrue(ChapterRules.matcher(ChapterRule.Pattern("^第\\d+章$"))("第１２章"))
        assertTrue(ChapterRules.matcher(ChapterRule.Pattern("^\\w+$"))("序章"))
        assertTrue(ChapterRules.matcher(ChapterRule.Pattern("^第\\d+章\\s+\\w+$"))("第12章\u3000起航"))
    }
    @Test fun realScanAndBuildFindFullWidthNumberedChapters() {
        val dir = kotlin.io.path.createTempDirectory("r5-scan").toFile()
        try {
            val file = java.io.File(dir,"book.txt").apply { writeText("第１２章\n" + "正文内容很长很长很长很长。\n".repeat(20) + "第１３章\n" + "正文内容很长很长很长很长。\n".repeat(20)) }
            val rule = ChapterRule.Pattern("^第\\d+章$")
            assertEquals(2,com.localreader.app.core.TxtParser.scanChapters(file,"UTF-8",rule).hits)
            val index = com.localreader.app.core.TxtParser.build(file,java.io.File(dir,"idx"),"UTF-8",rule = rule)
            assertEquals(listOf("第１２章","第１３章"),index.chapters.map { it.title })
        } finally { dir.deleteRecursively() }
    }
}
