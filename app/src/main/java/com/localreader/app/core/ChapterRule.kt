package com.localreader.app.core

/** How a TXT book is divided into chapters. [Auto] is the built-in heading detection every book starts with. */
sealed interface ChapterRule {
    data object Auto: ChapterRule
    /** A line of at most [ChapterRules.MAX_LINE] characters whose text contains a match is a chapter title. */
    data class Pattern(val regex: String): ChapterRule
    /** No titles are looked for; a section starts roughly every [chars] characters. */
    data class EveryChars(val chars: Int): ChapterRule

    fun encode(): String = when(this) { Auto -> "auto";is Pattern -> "regex:$regex";is EveryChars -> "chars:$chars" }
}

class ChapterRuleException(message: String): IllegalArgumentException(message)

object ChapterRules {
    const val MAX_PATTERN = 200
    const val MAX_LINE = 80
    const val MIN_CHARS = 2_000
    const val MAX_CHARS = 200_000

    /** Strict decode of the stored form; anything unknown falls back to [ChapterRule.Auto] so a bad row never blocks a book. */
    fun decode(stored: String?): ChapterRule {
        if(stored == null || stored == "auto") return ChapterRule.Auto
        return try {
            when {
                stored.startsWith("regex:") -> ChapterRule.Pattern(stored.removePrefix("regex:")).also(::validate)
                stored.startsWith("chars:") -> ChapterRule.EveryChars(stored.removePrefix("chars:").toInt()).also(::validate)
                else -> ChapterRule.Auto
            }
        } catch(_: Exception) { ChapterRule.Auto }
    }

    const val MAX_QUANTIFIERS = 6
    const val MAX_UNBOUNDED = 3
    const val MAX_DEPTH = 4
    const val MAX_ALTERNATIVES = 8

    /**
     * Patterns are never handed to java.util.regex. Android's matcher converts its input to a String first (so no per-character
     * budget can interrupt it) and backtracks, so an ambiguous product such as `a*a*a*a{0,8}a{0,8}a{0,8}b` runs for minutes on an
     * 80-character line however few repetitions are counted. Instead the pattern is *parsed* with a small grammar (everything
     * outside it is refused) and executed by [LinePattern], a Thompson/Pike NFA simulation whose cost is at most
     * `states x characters` — about a million steps for the largest accepted pattern — whatever the pattern is.
     *
     * - atoms: a literal, an escaped punctuation mark, `\d \D \w \W \s \S`, `.`, a flat character class `[...]`, or a group `(...)`/`(?:...)`;
     * - `^` and `$` anchors; `|` between alternatives (at most 8 in total, nesting at most 4 deep);
     * - `? * + {n} {n,} {n,m}` (optionally followed by a lazy `?`) on an atom that is not a group — a group may only take `?`;
     * - at most 6 repetitions in all and at most 3 that are unbounded (kept so the rules stay readable, no longer for safety).
     *
     * Quantified groups, look-around, back-references, quoting (`\Q`), other escapes, flags and nested classes are refused.
     */
    fun checkPatternShape(pattern: String) { compile(pattern) }

    internal fun compile(pattern: String): LinePattern = PatternParser(pattern).parse()

    /** Membership of one code point in a platform character class; answers are cached so each distinct character is asked once. */
    private fun platformClass(escape: String,negate: Boolean): (Int) -> Boolean {
        val pattern = java.util.regex.Pattern.compile(escape)
        val cache = java.util.concurrent.ConcurrentHashMap<Int,Boolean>()
        return { cp -> cache.getOrPut(cp) { pattern.matcher(String(Character.toChars(cp))).matches() } != negate }
    }

    private class PatternParser(private val p: String) {
        private var i = 0;private var quantifiers = 0;private var unbounded = 0;private var alternatives = 0
        private fun refuse(why: String): Nothing = throw ChapterRuleException("规则不被支持：$why")
        private fun peek(): Char? = p.getOrNull(i)

        fun parse(): LinePattern {
            val node = parseAlternation(0)
            if(i != p.length) refuse(if(peek() == ')') "括号不匹配" else "无法识别的写法")
            if(quantifiers > MAX_QUANTIFIERS) refuse("重复符号最多 $MAX_QUANTIFIERS 个")
            if(unbounded > MAX_UNBOUNDED) refuse("不限次数的重复（* + {n,}）最多 $MAX_UNBOUNDED 个")
            return LinePattern.compile(node)
        }
        private fun parseAlternation(depth: Int): LinePattern.Node {
            if(depth > MAX_DEPTH) refuse("括号嵌套过深")
            val branches = arrayListOf(parseSequence(depth))
            while(peek() == '|') { i++;if(++alternatives > MAX_ALTERNATIVES) refuse("“或”最多 $MAX_ALTERNATIVES 个");branches += parseSequence(depth) }
            return if(branches.size == 1) branches[0] else LinePattern.Node.Alt(branches)
        }
        private fun parseSequence(depth: Int): LinePattern.Node {
            val items = arrayListOf<LinePattern.Node>()
            while(true) {
                val c = peek() ?: break
                if(c == '|' || c == ')') break
                items += parseItem(depth)
            }
            return LinePattern.Node.Seq(items)
        }
        private fun parseItem(depth: Int): LinePattern.Node {
            val c = peek()!!
            var group = false;var anchor = false
            val atom: LinePattern.Node = when(c) {
                '(' -> {
                    i++
                    if(peek() == '?') { i++;if(peek() != ':') refuse("只支持普通分组与 (?:…)");i++ }
                    val inner = parseAlternation(depth + 1)
                    if(peek() != ')') refuse("括号不匹配")
                    i++;group = true;inner
                }
                '[' -> parseClass()
                '\\' -> LinePattern.Node.Set(parseEscape(inClass = false))
                '^' -> { i++;anchor = true;LinePattern.Node.Bol }
                '$' -> { i++;anchor = true;LinePattern.Node.Eol }
                '.' -> { i++;LinePattern.Node.Set { cp -> cp != 10 && cp != 13 && cp != 0x85 && cp != 0x2028 && cp != 0x2029 } }
                '*','+','?','{' -> refuse("重复符号前面需要一个字符或字符类")
                '}',']' -> refuse("特殊字符 $c 需要写成 \\$c")
                else -> { val cp = p.codePointAt(i);i += Character.charCount(cp);LinePattern.Node.Set { it == cp } }
            }
            return parseQuantifier(atom,group,anchor)
        }
        /** A class escape or an escaped punctuation mark; returns the predicate. */
        private fun parseEscape(inClass: Boolean): (Int) -> Boolean {
            i++ // the backslash
            val next = peek() ?: refuse("以反斜杠结尾")
            i++
            // \d \w \s follow the platform's own definitions (Unicode on Android: full-width digits, CJK letters, ideographic
            // space), asked about ONE code point at a time. That is constant work and cannot backtrack, unlike matching a line.
            return when(next) {
                'd' -> platformClass("\\d",false)
                'D' -> platformClass("\\d",true)
                'w' -> platformClass("\\w",false)
                'W' -> platformClass("\\w",true)
                's' -> platformClass("\\s",false)
                'S' -> platformClass("\\s",true)
                else -> {
                    val ok = (!next.isLetterOrDigit() && next.code in 0x21..0x7e) || (inClass && next == '-')
                    if(!ok) refuse("不支持 \\$next（只支持 \\d \\w \\s 及转义符号）")
                    val cp = next.code
                    ({ it: Int -> it == cp })
                }
            }
        }
        private fun parseClass(): LinePattern.Node {
            i++ // [
            val negate = peek() == '^';if(negate) i++
            val singles = hashSetOf<Int>();val ranges = arrayListOf<IntRange>();val escapes = arrayListOf<(Int) -> Boolean>()
            var items = 0
            while(true) {
                val c = peek() ?: refuse("字符类没有闭合")
                if(c == ']') { if(items == 0) refuse("字符类不能为空");i++;break }
                if(c == '[') refuse("不支持嵌套字符类")
                if(c == '&' && p.getOrNull(i + 1) == '&') refuse("不支持字符类交集")
                items++
                if(c == '\\') {
                    val escapePredicate = parseEscape(inClass = true)
                    val start = i
                    if(peek() == '-' && p.getOrNull(i + 1).let { it != null && it != ']' }) refuse("字符类范围的端点必须是普通字符")
                    // escaped punctuation as a single member keeps its code point
                    val literal = p[start - 1]
                    if(!literal.isLetter() && !literal.isDigit()) singles += literal.code else escapes += escapePredicate
                    continue
                }
                val lo = p.codePointAt(i);i += Character.charCount(lo)
                if(peek() == '-' && p.getOrNull(i + 1).let { it != null && it != ']' }) {
                    i++
                    if(peek() == '\\') refuse("字符类范围的端点必须是普通字符")
                    if(peek() == '[') refuse("不支持嵌套字符类")
                    val hi = p.codePointAt(i);i += Character.charCount(hi)
                    if(hi < lo) refuse("字符类范围的顺序不对")
                    ranges += lo..hi
                } else singles += lo
            }
            return LinePattern.Node.Set { cp -> (cp in singles || ranges.any { cp in it } || escapes.any { it(cp) }) != negate }
        }
        private fun parseQuantifier(atom: LinePattern.Node,group: Boolean,anchor: Boolean): LinePattern.Node {
            val c = peek() ?: return atom
            val wide: Boolean;val min: Int;val max: Int // max -1 = unbounded
            when(c) {
                '?' -> { wide = false;i++;min = 0;max = 1 }
                '*','+' -> { if(group) refuse("分组后面不能重复（会导致匹配极慢）");wide = true;i++;min = if(c == '*') 0 else 1;max = -1 }
                '{' -> {
                    val close = p.indexOf('}',i)
                    val body = if(close > i) p.substring(i + 1,close) else ""
                    val match = Regex("([0-9]+)(?:(,)([0-9]*))?").matchEntire(body) ?: refuse("{ 后面必须是重复次数，例如 {1,6}")
                    if(group) refuse("分组后面不能重复（会导致匹配极慢）")
                    val lower = match.groupValues[1].toBigInteger();val comma = match.groupValues[2].isNotEmpty();val upper = match.groupValues[3]
                    val eight = 8.toBigInteger();val cap = (MAX_LINE + 1).toBigInteger()
                    wide = lower > eight || (comma && (upper.isEmpty() || upper.toBigInteger() > eight))
                    if(comma && upper.isNotEmpty() && upper.toBigInteger() < lower) refuse("重复次数的范围顺序不对")
                    // More than a line's worth of repeats can never match (lower) or is the same as "any number" (upper).
                    min = lower.min(cap).toInt()
                    max = if(!comma) min else if(upper.isEmpty() || upper.toBigInteger() >= cap) -1 else upper.toInt()
                    i = close + 1
                }
                else -> return atom
            }
            if(anchor) refuse("^ 和 $ 后面不能重复")
            quantifiers++;if(wide) unbounded++
            // A lazy marker is allowed; a possessive one, or a second repetition, is not.
            when(peek()) { '?' -> i++;'+' -> refuse("不支持占有型重复");'*' -> refuse("重复符号不能连用");'{' -> refuse("重复符号不能连用") }
            return LinePattern.Node.Rep(atom,min,max)
        }
    }

    /** Throws [ChapterRuleException] with a message fit for the user. */
    fun validate(rule: ChapterRule) {
        when(rule) {
            ChapterRule.Auto -> Unit
            is ChapterRule.Pattern -> {
                if(rule.regex.isBlank()) throw ChapterRuleException("请输入章节标题的匹配规则")
                if(rule.regex.length > MAX_PATTERN) throw ChapterRuleException("规则最多 $MAX_PATTERN 个字符")
                checkPatternShape(rule.regex)
            }
            is ChapterRule.EveryChars -> if(rule.chars !in MIN_CHARS..MAX_CHARS) throw ChapterRuleException("每段字数需在 $MIN_CHARS 到 $MAX_CHARS 之间")
        }
    }

    /** Returns a function telling whether a trimmed line is a chapter title under [rule]. [Auto] and [ChapterRule.EveryChars] are handled by the parser. */
    fun matcher(rule: ChapterRule.Pattern): (String) -> Boolean {
        validate(rule)
        val pattern = compile(rule.regex)
        return { line -> line.length in 1..MAX_LINE && pattern.containsMatchIn(line) }
    }
}

/** What a rule would find, without rebuilding the book: the hit count and the first few titles. */
data class ChapterScan(val hits: Int,val titles: List<String>,val truncated: Boolean)
