package com.localreader.app.formats.epub

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.security.MessageDigest

/** Structural rules match the safe logical chapter, never the renderer's temporary part tree. */
internal class EpubSourceSelectors {
    data class Rule(val source: String,val token: String,val match: SourceSelector)
    private val registered=linkedMapOf<String,Rule>()
    val rules: List<Rule> get()=registered.values.toList()
    /**
     * An unsupported selector branch (e.g. :hover inside a long chapter's CSS) is dropped
     * instead of failing the whole import; a list with no usable branch never matches.
     */
    fun compile(selectorList: String): String = split(selectorList)
        .mapNotNull { branch -> runCatching { compileBranch(branch) }.getOrNull() }
        .ifEmpty { listOf(":not(*)") }.joinToString(",")
    private fun compileBranch(branch: String): String =
        if(!sensitive(branch))branch else {
            require(!Regex(":(?:hover|active|focus|focus-within|focus-visible|visited|target|contains|matches)(?:\\b|\\()",RegexOption.IGNORE_CASE).containsMatchIn(branch)) { "长章节结构规则含暂不支持的动态或文本选择器" }
            val pseudo=Regex("::(?:before|after|first-letter|first-line)$",RegexOption.IGNORE_CASE).find(branch)?.value.orEmpty()
            val source=branch.removeSuffix(pseudo)
            val specificity=Specificity.of(source)
            require(registered.size<4096 || source in registered) { "EPUB 源结构规则超过 4096" }
            val rule=registered.getOrPut(source) {
                val token="s"+MessageDigest.getInstance("SHA-256").digest(source.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
                Rule(source,token,SourceSelector.parse(source))
            }
            // :where has zero specificity; the always-matching :is arms restore the original tuple.
            buildString {
                append(":where([data-reader-source-style~='");append(rule.token);append("'])")
                if(specificity[0]>0)append(":is("+"#reader-unused".repeat(specificity[0])+",*)")
                if(specificity[1]>0)append(":is("+".reader-unused".repeat(specificity[1])+",*)")
                if(specificity[2]>0)append(":is("+(1..specificity[2]).joinToString(" ") { "reader-unused" }+",*)")
                append(pseudo)
            }
        }
    private fun sensitive(value: String)=Regex("[+~\\[]|:(?:nth-|first-|last-|only-|empty\\b|has\\()",RegexOption.IGNORE_CASE).containsMatchIn(value)
    private fun split(value: String): List<String> {
        val output=mutableListOf<String>();var start=0;var depth=0;var quote='\u0000';var escaped=false
        value.forEachIndexed { index,c ->
            if(escaped)escaped=false else if(c=='\\')escaped=true else if(quote!='\u0000') { if(c==quote)quote='\u0000' }
            else when(c) { '\'', '"' -> quote=c;'(', '[' -> depth++;')', ']' -> depth--;',' -> if(depth==0) { output.add(value.substring(start,index).trim());start=index+1 } }
            require(depth>=0 && depth<=32) { "EPUB 选择器嵌套超过限制" }
        }
        require(depth==0 && quote=='\u0000');output.add(value.substring(start).trim());require(output.all { it.isNotEmpty() && it.length<=16384 });return output
    }
    private object Specificity {
        fun of(value: String): IntArray {
            val result=IntArray(3);var i=0;var expectType=true
            fun name() { while(i<value.length && (value[i].isLetterOrDigit() || value[i] in "_-" || value[i]=='\\')) {
                if(value[i]=='\\') { i++;var digits=0;while(i<value.length && value[i].digitToIntOrNull(16)!=null && digits++<6)i++;if(digits==0 && i<value.length)i++ else if(i<value.length && value[i].isWhitespace())i++ } else i++
            } }
            fun enclosed(open: Char,close: Char): String {
                val start=++i;var depth=1;var quote='\u0000';var escaped=false
                while(i<value.length) { val c=value[i++];if(escaped)escaped=false else if(c=='\\')escaped=true else if(quote!='\u0000') { if(c==quote)quote='\u0000' } else if(c=='\'' || c=='"')quote=c else if(c==open)depth++ else if(c==close && --depth==0)return value.substring(start,i-1) }
                error("EPUB 选择器未闭合")
            }
            while(i<value.length) {
                val c=value[i]
                when {
                    c.isWhitespace() || c in ">+~" -> { i++;expectType=true }
                    c=='#' -> { result[0]++;i++;name();expectType=false }
                    c=='.' -> { result[1]++;i++;name();expectType=false }
                    c=='[' -> { result[1]++;enclosed('[',']');expectType=false }
                    c==':' -> {
                        i++;val double=i<value.length && value[i]==':';if(double)i++
                        val start=i;name();val keyword=value.substring(start,i).lowercase()
                        if(double || keyword in setOf("before","after","first-letter","first-line"))result[2]++
                        else if(i<value.length && value[i]=='(') {
                            val body=enclosed('(',')')
                            if(keyword in setOf("is","not","has")) {
                                val maximum=EpubSourceSelectors().split(body).map(::of).maxWithOrNull(compareBy<IntArray> { it[0] }.thenBy { it[1] }.thenBy { it[2] })!!
                                for(n in 0..2)result[n]+=maximum[n]
                            } else if(keyword!="where") {
                                require(keyword.startsWith("nth-")) { "EPUB 不支持的结构选择器函数：$keyword" }
                                require(!Regex("\\bof\\b",RegexOption.IGNORE_CASE).containsMatchIn(body)) { "EPUB 暂不支持 nth-child 的 of 选择器" };result[1]++
                            }
                        } else result[1]++
                        expectType=false
                    }
                    c=='*' -> { i++;expectType=false }
                    c.isLetter() || c=='_' || c=='\\' -> { if(expectType)result[2]++;name();expectType=false }
                    else -> error("EPUB 不支持的结构选择器语法")
                }
            }
            require(result.all { it<=128 });return result
        }
    }
}

/** Compact safe-source relations. No prose or per-node object tree survives the parsing batch. */
internal class EpubSourceTopology(private val checkpoint: () -> Unit = {}) {
    private val parents=PackedIntColumn(-1);private val previous=PackedIntColumn(-1)
    private val first=PackedIntColumn(-1);private val last=PackedIntColumn(-1);private val next=PackedIntColumn(-1)
    private val ordinals=PackedIntColumn();private val typeOrdinals=PackedIntColumn();private val totals=PackedIntColumn();private val typeTotals=PackedIntColumn()
    private val tags=PackedIntColumn();private val attributes=PackedIntColumn();private val flags=PackedIntColumn()
    private val tagNames=mutableListOf<String>();private val tagIds=mutableMapOf<String,Int>()
    private val attributePool=mutableListOf<String>();private val attributeIds=mutableMapOf<String,Int>();private var poolBytes=0L
    private val decoded=object : LinkedHashMap<Int,Map<String,String>>(64,0.75f,true) { override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int,Map<String,String>>?)=size>64 }
    private var nodes=0;private var body=-1;private var cursor=0;private var selectors: EpubSourceSelectors?=null;private var tests=0L;private var markerBytes=0L;private val batchNodes=mutableListOf<Int>();private var markedBatch=0
    fun tag(node: Int)=tagNames[tags[node]]
    fun parent(node: Int)=parents[node]
    fun prev(node: Int)=previous[node]
    fun ordinal(node: Int,type: Boolean=false)=if(type)typeOrdinals[node] else ordinals[node]
    fun total(node: Int,type: Boolean=false)=if(type)typeTotals[node] else totals[node]
    fun empty(node: Int)=first[node]<0 && (flags[node] and 1)==0
    fun html(node: Int)=(flags[node] and 2)!=0
    fun attr(node: Int,name: String): String? {
        val id=attributes[node]
        return decoded.getOrPut(id) { val json=org.json.JSONObject(attributePool[id]);json.keys().asSequence().associateWith(json::getString) }[name]
    }
    fun step() { require(++tests<=8_000_000) { "EPUB 源结构匹配超过工作预算" };if(tests%4096==0L)checkpoint() }
    private fun append(element: Element,parent: Int): Int {
        require(nodes<100000) { "EPUB 源结构节点超过 100000" };val id=nodes++
        flags[id]=if(element.tag().namespace()=="http://www.w3.org/1999/xhtml")2 else 0
        parents[id]=parent;tags[id]=tagIds.getOrPut(element.normalName()) { tagNames.add(element.normalName());tagNames.lastIndex }
        val json=org.json.JSONObject();element.attributes().asList().filter { !it.key.startsWith("data-reader-") }.sortedBy { it.key }.forEach { json.put(it.key,it.value) }
        val value=json.toString()
        attributes[id]=attributeIds.getOrPut(value) {
            poolBytes+=value.toByteArray().size+128;require(poolBytes<=8L*1024*1024 && nodes*64L+poolBytes<=16L*1024*1024) { "EPUB 源结构属性超过内存预算" }
            attributePool.add(value);attributePool.lastIndex
        }
        if(parent>=0) { previous[id]=last[parent];if(last[parent]>=0)next[last[parent]]=id else first[parent]=id;last[parent]=id }
        return id
    }
    fun add(safe: SanitizedSpine) {
        val source=Jsoup.parse(safe.authorHtml)
        fun visit(element: Element,parent: Int): Int {
            val id=append(element,parent)
            element.childNodes().forEach { child -> when(child) { is Element -> visit(child,id);is TextNode -> if(child.wholeText.isNotEmpty())flags[id]=flags[id] or 1;else -> Unit } }
            return id
        }
        if(body<0) {
            val html=append(source.selectFirst("html")!!,-1);visit(source.head(),html);body=append(source.body(),html)
        }
        val before=nodes
        source.body().childNodes().forEach { child -> when(child) { is Element -> visit(child,body);is TextNode -> if(child.wholeText.isNotEmpty())flags[body]=flags[body] or 1;else -> Unit } }
        batchNodes.add(nodes-before)
    }
    fun prepare(rules: EpubSourceSelectors) {
        check(body>=0);selectors=rules;cursor=body+1
        // The html element is the sole element child of the source document.
        ordinals[0]=1;typeOrdinals[0]=1;totals[0]=1;typeTotals[0]=1
        for(parent in 0 until nodes) {
            if(parent%4096==0)checkpoint()
            var child=first[parent];var ordinal=0;val types=mutableMapOf<Int,Int>()
            while(child>=0) { ordinals[child]=++ordinal;typeOrdinals[child]=types.getOrDefault(tags[child],0)+1;types[tags[child]]=typeOrdinals[child];child=next[child] }
            child=first[parent]
            while(child>=0) { totals[child]=ordinal;typeTotals[child]=types.getValue(tags[child]);child=next[child] }
        }
    }
    private fun marker(node: Int): String = buildString {
        requireNotNull(selectors).rules.forEach { rule ->
            step()
            if(rule.match.matches(this@EpubSourceTopology,node)) {
                require(length+rule.token.length+1<=32768) { "EPUB 单节点结构样式超过预算" }
                markerBytes+=rule.token.length+1;require(markerBytes<=2L*1024*1024) { "EPUB 源结构样式标记超过 2 MB" }
                if(isNotEmpty())append(' ');append(rule.token)
            }
        }
    }
    fun mark(safe: SanitizedSpine): SanitizedSpine {
        require(markedBatch<batchNodes.size)
        val values=buildList { add(marker(body));repeat(batchNodes[markedBatch++]) { require(cursor<nodes);add(marker(cursor++)) } }
        val headers=(0 until body).map(::marker)
        return safe.copy(sourceStyleMarkers=values,sourceStyleHeaderMarkers=headers)
    }
    fun finish() { check(cursor==nodes);decoded.clear() }
}

/** Static selectors are evaluated against packed logical-source relations, before splitting. */
internal class SourceSelector private constructor(private val compounds: List<List<(EpubSourceTopology,Int)->Boolean>>,private val relations: List<Char>) {
    fun matches(tree: EpubSourceTopology,node: Int): Boolean {
        fun match(at: Int,index: Int): Boolean {
            tree.step();if(index<0 || compounds[at].any { !it(tree,index) })return false
            if(at==0)return true
            return when(relations[at-1]) {
                '>' -> match(at-1,tree.parent(index))
                '+' -> match(at-1,tree.prev(index))
                '~' -> { var sibling=tree.prev(index);var found=false;while(sibling>=0 && !found) { found=match(at-1,sibling);sibling=tree.prev(sibling) };found }
                else -> { var parent=tree.parent(index);var found=false;while(parent>=0 && !found) { found=match(at-1,parent);parent=tree.parent(parent) };found }
            }
        }
        return match(compounds.lastIndex,node)
    }
    companion object {
        fun parse(value: String,depth: Int=0): SourceSelector {
            require(depth<=16);val parts=mutableListOf<String>();val relations=mutableListOf<Char>();val text=StringBuilder()
            var bracket=0;var quote='\u0000';var escape=false;var pendingSpace=false;var relation=' '
            fun flush() { if(text.isNotEmpty()) { if(parts.isNotEmpty())relations.add(relation);parts.add(text.toString());text.clear();relation=' ' } }
            value.forEach { c ->
                if(escape) { text.append(c);escape=false }
                else if(c=='\\') { if(pendingSpace && bracket==0)flush();pendingSpace=false;text.append(c);escape=true }
                else if(quote!='\u0000') { text.append(c);if(c==quote)quote='\u0000' }
                else if(c=='\'' || c=='"') { text.append(c);quote=c }
                else if(c=='(' || c=='[') { if(pendingSpace && bracket==0)flush();pendingSpace=false;bracket++;text.append(c) }
                else if(c==')' || c==']') { bracket--;text.append(c) }
                else if(bracket==0 && c in ">+~") { flush();relation=c;pendingSpace=false }
                else if(bracket==0 && c.isWhitespace())pendingSpace=true
                else { if(pendingSpace && bracket==0)flush();pendingSpace=false;text.append(c) }
            }
            flush();require(parts.isNotEmpty() && bracket==0 && quote=='\u0000')
            return SourceSelector(parts.map { compound(it,depth) },relations)
        }
        private fun compound(value: String,depth: Int): List<(EpubSourceTopology,Int)->Boolean> {
            val predicates=mutableListOf<(EpubSourceTopology,Int)->Boolean>();var i=0
            fun name(): String { val start=i;while(i<value.length && (value[i].isLetterOrDigit() || value[i] in "_-"))i++;require(i>start) { "EPUB 不支持的源结构选择器名称" };return value.substring(start,i) }
            fun body(open: Char,close: Char): String { val start=++i;var nesting=1;var quote='\u0000';var escaped=false;while(i<value.length) { val c=value[i++];if(escaped)escaped=false else if(c=='\\')escaped=true else if(quote!='\u0000') { if(c==quote)quote='\u0000' } else if(c=='\'' || c=='"')quote=c else if(c==open)nesting++ else if(c==close && --nesting==0)return value.substring(start,i-1) };error("EPUB 选择器未闭合") }
            if(value.firstOrNull()=='*')i++ else if(value.firstOrNull()?.isLetter()==true || value.firstOrNull()=='_') { val tag=name().lowercase();predicates.add { tree,node -> tree.tag(node)==tag } }
            while(i<value.length) {
                when(value[i++]) {
                    '#' -> { val id=name();predicates.add { tree,node -> tree.attr(node,"id")==id } }
                    '.' -> { val cls=name();predicates.add { tree,node -> tree.attr(node,"class")?.split(Regex("[\\t\\n\\r\\f ]+"))?.contains(cls)==true } }
                    '[' -> {
                        i--;val raw=body('[',']');val attribute=Regex("^\\s*([a-zA-Z_][a-zA-Z0-9_-]*)\\s*(?:(~=|\\|=|\\^=|\\$=|\\*=|=)\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s]+?))\\s*(?:([isIS]))?)?\\s*$").matchEntire(raw) ?: error("EPUB 不支持的结构属性选择器")
                        val key=attribute.groupValues[1].lowercase();val op=attribute.groupValues[2];val expected=attribute.groupValues.drop(3).take(3).firstOrNull { it.isNotEmpty() }.orEmpty();val flag=attribute.groupValues[6].lowercase()
                        predicates.add { tree,node -> val actual=tree.attr(node,key);val ignore=flag=="i" || flag!="s" && tree.html(node) && key in HTML_INSENSITIVE;val a=if(ignore)actual?.let(::asciiLower) else actual;val e=if(ignore)asciiLower(expected) else expected
                            a!=null && when(op) { "" -> true;"=" -> a==e;"~=" -> e.isNotEmpty() && a.split(Regex("[\\t\\n\\r\\f ]+")).contains(e);"|=" -> a==e || a.startsWith("$e-");"^=" -> e.isNotEmpty() && a.startsWith(e);"$=" -> e.isNotEmpty() && a.endsWith(e);"*=" -> e.isNotEmpty() && a.contains(e);else -> false }
                        }
                    }
                    ':' -> {
                        val keyword=name().lowercase();val arg=if(i<value.length && value[i]=='(')body('(',')') else null
                        when {
                            keyword=="empty" && arg==null -> predicates.add { tree,node -> tree.empty(node) }
                            keyword=="root" && arg==null -> predicates.add { tree,node -> tree.parent(node)<0 }
                            keyword in setOf("is","where","not") && arg!=null -> {
                                val nested=splitArguments(arg).map { parse(it,depth+1) };predicates.add { tree,node -> nested.any { it.matches(tree,node) } != (keyword=="not") }
                            }
                            keyword in setOf("first-child","last-child","only-child","first-of-type","last-of-type","only-of-type") && arg==null -> {
                                val type=keyword.endsWith("of-type");predicates.add { tree,node -> val ordinal=tree.ordinal(node,type);val total=tree.total(node,type);when { keyword.startsWith("first") -> ordinal==1;keyword.startsWith("last") -> ordinal==total && ordinal>0;else -> total==1 } }
                            }
                            keyword in setOf("nth-child","nth-last-child","nth-of-type","nth-last-of-type") && arg!=null -> {
                                val formula=nth(arg);val type=keyword.endsWith("of-type");val reverse=keyword.startsWith("nth-last")
                                predicates.add { tree,node -> val ordinal=tree.ordinal(node,type);val position=if(reverse)tree.total(node,type)-ordinal+1 else ordinal;position>0 && if(formula.first==0)position==formula.second else (position-formula.second).let { it%formula.first==0 && it/formula.first>=0 } }
                            }
                            else -> error("EPUB 不支持的源结构伪类：$keyword")
                        }
                    }
                    else -> error("EPUB 不支持的源结构选择器语法")
                }
            }
            return predicates
        }
        private fun nth(value: String): Pair<Int,Int> {
            val text=value.replace(Regex("/\\*.*?\\*/"),"").filterNot(Char::isWhitespace).lowercase()
            if(text=="odd")return 2 to 1;if(text=="even")return 2 to 0
            text.toIntOrNull()?.let { return 0 to it }
            val match=Regex("([+-]?[0-9]*)n([+-][0-9]+)?").matchEntire(text) ?: error("EPUB 不支持的 nth 公式")
            val a=when(val n=match.groupValues[1]) { "","+" -> 1;"-" -> -1;else -> n.toInt() };return a to (match.groupValues[2].toIntOrNull() ?: 0)
        }
        private val HTML_INSENSITIVE=setOf("accept","accept-charset","align","alink","axis","bgcolor","charset","checked","clear","codetype","color","compact","declare","defer","dir","disabled","enctype","face","frame","hreflang","http-equiv","lang","language","link","media","method","multiple","nohref","noresize","noshade","nowrap","readonly","rel","rev","rules","scope","scrolling","selected","shape","target","text","type","valign","valuetype","vlink")
        private fun asciiLower(value: String)=buildString(value.length) { value.forEach { append(if(it in 'A'..'Z')it.lowercaseChar() else it) } }
        private fun splitArguments(value: String): List<String> {
            var depth=0;var start=0;var quote='\u0000';var escaped=false;val result=mutableListOf<String>()
            value.forEachIndexed { index,c ->
                if(escaped)escaped=false else if(c=='\\')escaped=true else if(quote!='\u0000') { if(c==quote)quote='\u0000' }
                else when(c) { '\'','"' -> quote=c;'(','[' -> depth++;')',']' -> depth--;',' -> if(depth==0) { result.add(value.substring(start,index));start=index+1 } }
                require(depth>=0)
            }
            require(depth==0 && quote=='\u0000');result.add(value.substring(start));return result
        }
    }
}

/** Fixed-size chunks avoid reserving 4.4 MiB for a chapter with only one giant paragraph. */
private class PackedIntColumn(private val default: Int=0) {
    private val chunks=mutableListOf<IntArray>()
    operator fun get(index: Int): Int=chunks.getOrNull(index/512)?.get(index%512) ?: default
    operator fun set(index: Int,value: Int) { while(chunks.size<=index/512)chunks.add(IntArray(512) { default });chunks[index/512][index%512]=value }
}
