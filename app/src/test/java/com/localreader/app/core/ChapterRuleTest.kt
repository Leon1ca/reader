package com.localreader.app.core

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class ChapterRuleTest {
    private lateinit var dir: File
    @Before fun setUp() { dir = kotlin.io.path.createTempDirectory("chapter-rule").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }

    private fun book(text: String) = File(dir,"book.txt").apply { writeText(text) }
    private val novel = buildString {
        append("前言\n"); repeat(6) { c -> append("第${c + 1}章 开端$c\n"); repeat(30) { append("这是正文的一段内容，长度足够长。\n") } }
    }
    private val bracketed = buildString { repeat(4) { c -> append("【卷${c + 1}】\n"); repeat(20) { append("正文内容很长很长很长。\n") } } }
    private fun build(file: File,rule: ChapterRule) = TxtParser.build(file,File(dir,"idx-${rule.encode().hashCode()}"),"UTF-8",rule = rule)

    @Test fun storedFormRoundTripsAndBadValuesFallBackToAuto() {
        listOf(ChapterRule.Auto,ChapterRule.Pattern("^第.+章"),ChapterRule.EveryChars(5000)).forEach { assertEquals(it,ChapterRules.decode(it.encode())) }
        listOf(null,"","garbage","regex:","regex:(","chars:abc","chars:5","chars:99999999","regex:"+"a".repeat(500)).forEach { assertEquals("$it",ChapterRule.Auto,ChapterRules.decode(it)) }
    }
    @Test fun validationExplainsWhatIsWrong() {
        assertThrows(ChapterRuleException::class.java) { ChapterRules.validate(ChapterRule.Pattern(" ")) }
        assertTrue(assertThrows(ChapterRuleException::class.java) { ChapterRules.validate(ChapterRule.Pattern("(")) }.message!!.contains("括号"))
        assertTrue(assertThrows(ChapterRuleException::class.java) { ChapterRules.validate(ChapterRule.Pattern("*a")) }.message!!.contains("重复符号"))
        assertThrows(ChapterRuleException::class.java) { ChapterRules.validate(ChapterRule.Pattern("a".repeat(201))) }
        assertThrows(ChapterRuleException::class.java) { ChapterRules.validate(ChapterRule.EveryChars(1999)) }
        ChapterRules.validate(ChapterRule.EveryChars(2000));ChapterRules.validate(ChapterRule.EveryChars(200_000))
    }
    @Test fun autoKeepsTheBuiltInDetection() {
        val index = build(book(novel),ChapterRule.Auto)
        assertEquals(listOf("前言","第1章 开端0"),index.chapters.take(2).map { it.title })
        assertEquals(7,index.chapters.size)
    }
    @Test fun aCustomPatternFindsTitlesTheBuiltInRuleMissesAndMarksThemAsHeadings() {
        val file = book(bracketed)
        assertTrue("auto finds no chapters here",build(file,ChapterRule.Auto).chapters.all { it.title.startsWith("分段 ") })
        val index = build(file,ChapterRule.Pattern("^【卷.】$"))
        assertEquals(listOf("【卷1】","【卷2】","【卷3】","【卷4】"),index.chapters.map { it.title })
        TxtEngine(File(dir,"idx-${ChapterRule.Pattern("^【卷.】$").encode().hashCode()}"),"v").use { engine ->
            assertTrue(engine.block(index.chapters[1].paragraph).heading)
        }
    }
    @Test fun aPatternWithFewerThanTwoHitsFallsBackToSections() {
        val index = build(book(bracketed),ChapterRule.Pattern("^【卷1】$"))
        assertTrue(index.chapters.all { it.title.startsWith("分段 ") })
    }
    @Test fun everyCharsMakesSectionsOfTheChosenSizeAndNoHeadings() {
        val text = "无标题的正文行内容。\n".repeat(2000)
        val index = build(book(text),ChapterRule.EveryChars(5000))
        val total = index.totalCharacters
        assertEquals(((total + 4999) / 5000).toInt(),index.chapters.size)
        assertEquals("分段 1",index.chapters.first().title)
        assertTrue(index.chapters.zipWithNext().all { (a,b) -> b.sourceOffset - a.sourceOffset in 5000..5100 })
        TxtEngine(File(dir,"idx-${ChapterRule.EveryChars(5000).encode().hashCode()}"),"v").use { engine -> assertTrue((0 until 50).none { engine.block(it).heading }) }
    }
    @Test fun scanCountsWhatBuildWouldFind() {
        val file = book(bracketed)
        listOf<ChapterRule>(ChapterRule.Pattern("^【卷.】$"),ChapterRule.Auto,ChapterRule.Pattern("正文")).forEach { rule ->
            val scan = TxtParser.scanChapters(file,"UTF-8",rule)
            val built = build(file,rule).chapters.filterNot { it.title.startsWith("分段 ") }
            if(built.isNotEmpty()) assertEquals("$rule",built.size,scan.hits)
        }
        val scan = TxtParser.scanChapters(book(novel),"UTF-8",ChapterRule.Auto)
        assertEquals(7,scan.hits);assertEquals("前言",scan.titles.first());assertEquals(7,scan.titles.size);assertFalse(scan.truncated)
        val many = TxtParser.scanChapters(book("第1章\n正文\n".repeat(30)),"UTF-8",ChapterRule.Auto)
        assertEquals(30,many.hits);assertEquals(8,many.titles.size);assertTrue(many.truncated)
        val chars = TxtParser.scanChapters(book("甲".repeat(10_000)),"UTF-8",ChapterRule.EveryChars(2000))
        assertEquals(5,chars.hits)
    }
    @Test fun patternsThatCanBacktrackCatastrophicallyAreRefusedUpFront() {
        listOf("(.*a){12}x","^(a+)+$","(a|aa)+b","(x+x+)+y","(a?){30}a{30}","(\\w+\\s?)*$","(a|b)*c","^(a)\\1$",".*.*.*.*x","a?a?a?a?a?a?a?aaaaaaa","a{0,1000}".repeat(12) + "b","a{0,1000}a{0,1000}a{0,1000}a{0,1000}b","a{x}","(?=a)b","(?<!a)b","[[a-z]&&[^b]]").forEach { pattern ->
            assertThrows(pattern,ChapterRuleException::class.java) { ChapterRules.validate(ChapterRule.Pattern(pattern)) }
        }
        assertTrue(assertThrows(ChapterRuleException::class.java) { ChapterRules.validate(ChapterRule.Pattern("(.*a){12}x")) }.message!!.contains("分组后面"))
    }
    @Test fun quotingAndEscapeTricksCannotHideARepetition() {
        listOf("(a+)\\Q\\E+b","\\Qab\\E","(a+)\\Q\\E*","(a|a)\\E+","(a+)\\x{0}+b","(a+)\\u0000+b","(a+)(?#c)+b","(a+)(?i)+b","(?i)abc","(?<n>a)","\\bword","\\p{L}+","\\1","\\k<n>","a**","a+*","a++","a{2}{3}","^*","$+","(a)+","(a){2,}","(?:a)*","(a|b)+c","[a-z","[]","[[a]]","[a&&b]","a{","}","]","a)","(a","\\").forEach { pattern ->
            assertThrows(pattern,ChapterRuleException::class.java) { ChapterRules.validate(ChapterRule.Pattern(pattern)) }
        }
    }
    @Test fun ordinaryTitlePatternsStillWork() {
        listOf("^第.{1,6}[章回节]","^第[0-9零一二三四五六七八九十百千万]+章","^(序章|楔子|尾声)$","^Chapter\\s+[0-9]+","^【卷.】$","^\\d+[.、]\\s*\\S+","^(?:第.{1,4}章)\\s?.{0,20}$","^\\[.+\\]$","^[^\\s]+\\.\\s","^第\\d+[章回]\\s*(?:\\S+)?$","a{1000}b","a{1,99999999999}b").forEach { pattern ->
            ChapterRules.validate(ChapterRule.Pattern(pattern))
        }
        val match = ChapterRules.matcher(ChapterRule.Pattern("^第.{1,6}[章回节]"))
        assertTrue(match("第十二章 风起"));assertFalse(match("他说第十二章很长"));assertFalse(match("第".repeat(81)))
    }
    /**
     * Whatever the validator accepts must be cheap on the worst short input. Random patterns are drawn from the constructs that
     * cause trouble; every accepted one is run through the real matcher against adversarial 80-character lines.
     */
    @Test fun everyAcceptedPatternIsFastOnAdversarialLines() {
        val parts = listOf("a","a+","a*","a?","a{0,1000}","a{2,5}","(a+)","(a*)","(a|aa)","(?:a|a)","(a?)","\\Q","\\E","(.*a)","[ab]","[ab]+","[^a]*",".*",".+",".","\\w+","\\s*","b","|","^","$","(","(?:",")","x","\\d")
        val inputs = listOf("a".repeat(40) + "!","a".repeat(79) + "b","ab".repeat(40),"a".repeat(80),"x".repeat(30) + "y","1 ".repeat(40))
        val random = java.util.Random(20261013);var accepted = 0;var worst = 0L
        repeat(6000) {
            val pattern = (0 until 1 + random.nextInt(9)).joinToString("") { parts[random.nextInt(parts.size)] }
            if(runCatching { ChapterRules.validate(ChapterRule.Pattern(pattern)) }.isSuccess) {
                accepted++
                val match = ChapterRules.matcher(ChapterRule.Pattern(pattern))
                inputs.forEach { input ->
                    val started = System.nanoTime();match(input);val millis = (System.nanoTime() - started) / 1_000_000
                    worst = maxOf(worst,millis);assertTrue("$pattern took ${millis}ms on ${input.take(8)}…",millis < 250)
                }
            }
        }
        assertTrue("the generator should reach accepted patterns ($accepted)",accepted > 100)
        println("accepted=$accepted worst=${worst}ms")
    }
    /** R4-01: legal-looking ambiguous products used to run for minutes on the platform regex; the NFA cost is bounded by construction. */
    @Test fun ambiguousRepetitionProductsAreLinearOnTheWorstLines() {
        val patterns = listOf("a*a*a*a{0,8}a{0,8}a{0,8}b","^a*a*a*a{0,8}a{0,8}a{0,8}b$","a*a*a*b","(a|aa|aaa|aaaa)?(a|aa)?a*a*a*c",".*.*.*x","\\w*\\w*\\w*\\s","[ab]*[ab]*[ab]*c","a{0,80}a{0,80}a{0,80}b","a+a+a+b")
        val lines = listOf("a".repeat(79) + "!","a".repeat(80),"a".repeat(78) + "bc","ab".repeat(40),"a".repeat(40) + "b" + "a".repeat(39),"x".repeat(80))
        patterns.forEach { pattern ->
            val match = ChapterRules.matcher(ChapterRule.Pattern(pattern))
            lines.forEach { line ->
                val started = System.nanoTime();match(line);val millis = (System.nanoTime() - started) / 1_000_000
                assertTrue("$pattern took ${millis}ms",millis < 250)
            }
        }
        val title = ChapterRules.matcher(ChapterRule.Pattern("a*a*a*a{0,8}a{0,8}a{0,8}b"))
        assertFalse(title("a".repeat(79) + "!"));assertTrue(title("a".repeat(70) + "b"))
    }
    /** The NFA must agree with java.util.regex on every accepted pattern (short, benign inputs where backtracking is safe). */
    @Test fun matcherAgreesWithJavaRegexOnAcceptedPatterns() {
        val atoms = listOf("a","b","1","x","\\d","\\w","\\s",".","[ab]","[^a]","[a-c1]","[\\d-]","\\.","第","章","(a|b)","(?:ab|ba)","(a)?","^","$","|")
        val quant = listOf("","","","?","*","+","{2}","{1,3}","{0,2}","{2,}","*?","+?")
        val inputs = listOf("","a","ab","aab","ba1","12 x","a.b","第一章 开端","第12回","abab","  a","b1b1b1","aaaaab","x y z","章章","😀a","a😀","a\u0085","a\n","a\r\n","a\r","a\u2028","a\u2029","a\n\n","a\r\r","a\u0085b","第１２章","第12章　起航","序章","é","e\u0301","٣","\u00a0x")
        val random = java.util.Random(4)
        var compared = 0
        repeat(5000) {
            val pattern = (0 until 1 + random.nextInt(5)).joinToString("") { val atom = atoms[random.nextInt(atoms.size)]; if(atom in listOf("^","$","|")) atom else atom + quant[random.nextInt(quant.size)] }
            if(runCatching { ChapterRules.validate(ChapterRule.Pattern(pattern)) }.isFailure) return@repeat
            val java = Regex(pattern);val ours = ChapterRules.compile(pattern)
            inputs.forEach { input ->
                assertEquals("/$pattern/ on \"$input\"",java.containsMatchIn(input),ours.containsMatchIn(input));compared++
            }
        }
        assertTrue("compared=$compared",compared > 5000)
    }
    /** CLOSE-01: `$` accepts one final line terminator, exactly as java.util.regex does, so a title that keeps its NEL still matches. */
    @Test fun dollarMatchesBeforeOneFinalLineTerminatorOfAnyKind() {
        val match = ChapterRules.matcher(ChapterRule.Pattern("^a$"))
        listOf("a","a\n","a\r","a\r\n","a\u0085","a\u2028","a\u2029").forEach { assertTrue(it.map { c -> c.code }.toString(),match(it)) }
        listOf("ab","a\n\n","a\r\r","a\u0085\u0085","a\nb","\na").forEach { assertFalse(it.map { c -> c.code }.toString(),match(it)) }
        val title = ChapterRules.matcher(ChapterRule.Pattern("^第.+章$"))
        assertTrue(title("第一章\u0085"));assertTrue(title("第一章"));assertFalse(title("第一章 后面还有"))
    }
    @Test fun aTitleLineKeepingItsNelIsFoundByScanAndBuild() {
        val text = "前言\n" + (1..3).joinToString("") { "第${it}章\u0085\n" + "正文内容足够长，用来分段使用。\n".repeat(20) }
        val file = book(text);val rule = ChapterRule.Pattern("^第.章$")
        assertEquals(3,TxtParser.scanChapters(file,"UTF-8",rule).hits)
        assertEquals(listOf("第1章\u0085","第2章\u0085","第3章\u0085").map { it.trim() },build(file,rule).chapters.filterNot { it.title.startsWith("分段 ") }.map { it.title.trim() })
    }
    @Test fun aRejectedRuleNeverReachesTheParser() {
        val file = book("a".repeat(40) + "!\n" + "正文\n".repeat(10))
        assertThrows(ChapterRuleException::class.java) { TxtParser.scanChapters(file,"UTF-8",ChapterRule.Pattern("(.*a){12}x")) }
        assertThrows(ChapterRuleException::class.java) { build(file,ChapterRule.Pattern("(.*a){12}x")) }
    }
}
