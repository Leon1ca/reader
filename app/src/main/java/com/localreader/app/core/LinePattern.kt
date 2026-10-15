package com.localreader.app.core

/**
 * A chapter-title pattern compiled to a Thompson NFA and run as a Pike simulation: every input position advances each live
 * state at most once, so a search costs at most `states x characters` however ambiguous the pattern is. There is no
 * backtracking to blow up and nothing to cancel. Semantics follow java.util.regex without flags for the supported grammar
 * (see [ChapterRules.checkPatternShape]); matching is by code point and is a "contains" search.
 */
internal class LinePattern private constructor(private val ops: IntArray,private val arg1: IntArray,private val arg2: IntArray,private val sets: List<(Int) -> Boolean>) {
    sealed interface Node {
        class Set(val accepts: (Int) -> Boolean): Node
        class Seq(val items: List<Node>): Node
        class Alt(val branches: List<Node>): Node
        /** [max] of -1 means unbounded; a [min] above a line's length can never match. */
        class Rep(val node: Node,val min: Int,val max: Int): Node
        data object Bol: Node
        data object Eol: Node
    }

    /**
     * `$` as java.util.regex without MULTILINE defines it: the very end of the input, or just before a final line terminator
     * (`\n`, `\r`, `\r\n`, U+0085, U+2028, U+2029). A title line that still carries its terminator is therefore a match.
     */
    private fun atEnd(points: IntArray,position: Int): Boolean {
        val size = points.size
        if(position == size) return true
        fun terminator(cp: Int) = cp == 10 || cp == 13 || cp == 0x85 || cp == 0x2028 || cp == 0x2029
        // The gap inside a final \r\n is not a line end: that pair is one terminator.
        if(position == size - 1) return terminator(points[position]) && !(points[position] == 10 && position > 0 && points[position - 1] == 13)
        return position == size - 2 && points[position] == 13 && points[position + 1] == 10
    }

    fun containsMatchIn(text: String): Boolean {
        val points = text.codePoints().toArray()
        val count = ops.size
        var current = IntArray(count);var next = IntArray(count)
        var currentSize: Int;var nextSize: Int
        val mark = IntArray(count) { -1 }
        var matched = false
        fun add(list: IntArray,size: Int,pc: Int,position: Int,stamp: Int): Int {
            // Iterative closure so a long chain of splits cannot overflow the stack.
            var n = size
            val stack = ArrayDeque<Int>();stack.addLast(pc)
            while(stack.isNotEmpty()) {
                val at = stack.removeLast()
                if(mark[at] == stamp) continue
                mark[at] = stamp
                when(ops[at]) {
                    JMP -> stack.addLast(arg1[at])
                    SPLIT -> { stack.addLast(arg2[at]);stack.addLast(arg1[at]) }
                    BOL -> if(position == 0) stack.addLast(at + 1)
                    EOL -> if(atEnd(points,position)) stack.addLast(at + 1)
                    MATCH -> matched = true
                    else -> list[n++] = at
                }
            }
            return n
        }
        currentSize = 0
        for(position in 0..points.size) {
            // A new attempt may start here (unanchored search); stamps are per position.
            currentSize = add(current,currentSize,0,position,position)
            if(matched) return true
            if(position == points.size) break
            nextSize = 0
            val cp = points[position]
            for(k in 0 until currentSize) {
                val pc = current[k]
                if(sets[arg1[pc]](cp)) nextSize = add(next,nextSize,pc + 1,position + 1,position + 1)
            }
            if(matched) return true
            val swap = current;current = next;next = swap;currentSize = nextSize
        }
        return false
    }

    companion object {
        private const val CHAR = 0;private const val SPLIT = 1;private const val JMP = 2;private const val BOL = 3;private const val EOL = 4;private const val MATCH = 5

        fun compile(root: Node): LinePattern {
            val ops = ArrayList<Int>();val a1 = ArrayList<Int>();val a2 = ArrayList<Int>();val sets = ArrayList<(Int) -> Boolean>()
            fun emit(op: Int,x: Int = 0,y: Int = 0): Int { ops += op;a1 += x;a2 += y;return ops.size - 1 }
            fun gen(node: Node) {
                when(node) {
                    is Node.Set -> { sets += node.accepts;emit(CHAR,sets.size - 1) }
                    Node.Bol -> emit(BOL)
                    Node.Eol -> emit(EOL)
                    is Node.Seq -> node.items.forEach(::gen)
                    is Node.Alt -> {
                        val jumps = ArrayList<Int>()
                        node.branches.forEachIndexed { index,branch ->
                            if(index < node.branches.lastIndex) {
                                val split = emit(SPLIT);a1[split] = ops.size
                                gen(branch);jumps += emit(JMP)
                                a2[split] = ops.size
                            } else gen(branch)
                        }
                        jumps.forEach { a1[it] = ops.size }
                    }
                    is Node.Rep -> {
                        if(node.min > MAX_LINE) { // can never match within a line
                            sets += { _: Int -> false };emit(CHAR,sets.size - 1);return
                        }
                        repeat(node.min) { gen(node.node) }
                        if(node.max < 0) {
                            val split = emit(SPLIT);a1[split] = ops.size
                            gen(node.node);emit(JMP,split)
                            a2[split] = ops.size
                        } else {
                            val splits = ArrayList<Int>()
                            repeat(node.max - node.min) { val split = emit(SPLIT);a1[split] = ops.size;splits += split;gen(node.node) }
                            splits.forEach { a2[it] = ops.size }
                        }
                    }
                }
            }
            gen(root);emit(MATCH)
            return LinePattern(ops.toIntArray(),a1.toIntArray(),a2.toIntArray(),sets)
        }
        private val MAX_LINE get() = ChapterRules.MAX_LINE
    }
}
