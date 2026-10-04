package com.localreader.app.formats.epub

enum class EpubStyleMode { AUTHOR, CUSTOM }
data class FilteredCss(val text: String,val warnings: List<String>)

/** Original bounded token scanner. Strings/comments/escapes are parsed before URL classification. */
class EpubCss(private val resourceUrl: (String,Boolean) -> String?,private val imported: (String) -> String? = { null },private val selector: (String) -> String = { it }) {
    private var rules=0
    private val warnings=linkedSetOf<String>()
    val notices: List<String> get()=warnings.toList()
    fun filter(input: String,mode: EpubStyleMode=EpubStyleMode.AUTHOR): FilteredCss {
        require(input.toByteArray(Charsets.UTF_8).size<=EpubLimits.CSS_BYTES) { "EPUB 样式表超过 2 MB" }
        rules=0;warnings.clear()
        return FilteredCss(sheet(input,mode,0),warnings.toList())
    }
    fun inline(input: String,mode: EpubStyleMode=EpubStyleMode.AUTHOR): String {
        require(input.length<=32768) { "EPUB 行内样式过长" }
        return declarations(input,mode,false)
    }
    private fun sheet(source: String,mode: EpubStyleMode,depth: Int): String {
        require(depth<=16) { "EPUB CSS 嵌套过深" }
        val cursor=Cursor(source)
        return buildCss {
            while(cursor.more()) {
                require(++rules<=50000) { "EPUB CSS 规则超过限制" }
                val prelude=cursor.until(setOf('{',';','}')).trim()
                if(prelude.isEmpty() && !cursor.more())break
                val delimiter=cursor.take()
                if(delimiter==';') {
                    if(prelude.startsWith('@')) {
                        val name=cssName(prelude.drop(1).takeWhile { !it.isWhitespace() && it!='(' }).lowercase()
                        if(name=="import") {
                            val rest=prelude.drop(1).dropWhile { !it.isWhitespace() && it!='(' }.trim()
                            val target=importTarget(rest)
                            val css=target?.first?.let(imported)
                            if(css!=null) {
                                val media=target.second
                                if(media.isNotBlank())append("@media $media{")
                                append(css)
                                if(media.isNotBlank())append('}')
                            } else warnings.add("外部、缺失或循环 CSS import 已阻止")
                        } else if(name!="charset")warnings.add("不支持的 CSS at-rule 已移除：$name")
                    }
                    continue
                }
                require(delimiter=='{') { "EPUB CSS 语法无效" }
                val body=cursor.block()
                if(prelude.startsWith('@')) {
                    val name=cssName(prelude.drop(1).takeWhile { !it.isWhitespace() && it!='(' }).lowercase()
                    when(name) {
                        "font-face" -> { val decl=declarations(body,mode,true);if(decl.isNotBlank())append("@font-face{$decl}") }
                        "media","supports","layer","container" -> append("$prelude{${sheet(body,mode,depth+1)}}")
                        else -> warnings.add("不支持的 CSS at-rule 已移除：$name")
                    }
                } else if(prelude.isNotBlank()) {
                    val decl=declarations(body,mode,false)
                    if(decl.isNotBlank())append("${selector(prelude)}{$decl}")
                }
            }
        }
    }
    private fun declarations(source: String,mode: EpubStyleMode,fontFace: Boolean): String {
        val cursor=Cursor(source)
        return buildCss {
            while(cursor.more()) {
                val statement=cursor.until(setOf(';')).trim();if(cursor.more())cursor.take()
                if(statement.isEmpty())continue
                val split=Cursor(statement);val rawName=split.until(setOf(':')).trim()
                if(split.take()!=':') { warnings.add("无效 CSS 声明已移除");continue }
                val name=cssName(rawName).lowercase();val rawValue=split.remainder().trim()
                if(name in setOf("behavior","-moz-binding") || name.isEmpty()) { warnings.add("活动 CSS 声明已移除");continue }
                if(mode==EpubStyleMode.CUSTOM && !fontFace && name=="font") {
                    val value=filteredValue(rawValue,false)
                    val components=value?.let(::fontComponents)
                    if(components!=null)append(components) else warnings.add("自定义模式无法解析此 font 简写，已使用用户字体与默认字形样式")
                    continue
                }
                if(mode==EpubStyleMode.CUSTOM && !fontFace && (name.startsWith("background") || name in CUSTOM))continue
                val value=if(fontFace && name=="src") {
                    val candidates=Cursor(rawValue)
                    buildCss {
                        var count=0
                        while(candidates.more()) {
                            val candidate=candidates.until(setOf(','));if(candidates.more())candidates.take()
                            filteredValue(candidate,true)?.takeIf { it.isNotBlank() }?.let {
                                if(count++>0)append(',')
                                append(it)
                            }
                        }
                    }.takeIf { it.isNotEmpty() }
                } else filteredValue(rawValue,fontFace)
                if(value==null)continue
                val keyword=cssName(value.substringBefore('!').trim()).lowercase()
                if((name=="position" && keyword in setOf("fixed","sticky")) || (name=="filter" && "url(" in value.lowercase())) { warnings.add("活动 CSS 布局已移除");continue }
                append(rawName);append(':');append(value);append(';')
            }
        }
    }
    private fun filteredValue(source: String,fontFace: Boolean): String? {
        val cursor=Cursor(source)
        return buildCss {
            while(cursor.remaining()) {
                val char=cursor.peek()
                if(cursor.comment()) { cursor.space();append(' ');continue }
                if(char=='\'' || char=='"') { append(cursor.quoted());continue }
                if(char=='\\' || char=='-' || char=='_' || char.isLetter()) {
                    val raw=cursor.identifier();val name=cssName(raw).lowercase()
                    if(name=="expression" || name=="-moz-binding") { warnings.add("活动 CSS 函数已移除");return null }
                    if(cursor.peek()=='(' && name in setOf("image-set","-webkit-image-set","image","cross-fade","-webkit-cross-fade","paint","element")) {
                        warnings.add("暂不支持的 CSS 图像函数已阻止：$name");return null
                    }
                    if(cursor.peek()=='(' && name in setOf("url","local")) {
                        cursor.take();val inner=cursor.parenthesis().trim()
                        if(name=="local") { warnings.add("字体 local() 已阻止");return null }
                        val href=if(inner.startsWith('"') || inner.startsWith('\'')) {
                            val p=Cursor(inner);val quoted=p.quoted();p.space();if(p.more())return null;cssName(quoted.substring(1,quoted.length-1))
                        } else cssName(inner)
                        val rewritten=resourceUrl(href,fontFace) ?: run { warnings.add("外部或不支持的 CSS 资源已阻止");return null }
                        append("url(\"");append(rewritten.replace("\\","\\\\").replace("\"","\\\""));append("\")")
                    } else append(raw)
                } else append(cursor.take())
            }
        }.trim()
    }
    /** Shorthand resets are explicit: an omitted style/weight must not inherit a parent's italic/bold. */
    private fun fontComponents(value: String): String? {
        val cursor=Cursor(value);val tokens=mutableListOf<String>()
        while(cursor.more()) {
            tokens.add(if(cursor.peek() in setOf('/','!'))cursor.take().toString() else cursor.until(setOf(' ','\t','\r','\n','\u000c','/','!')))
            if(tokens.size>512)return null
        }
        var important=""
        if(tokens.lastOrNull()?.let(::cssName)?.equals("!important",true)==true) { tokens.removeAt(tokens.lastIndex);important="!important" }
        else if(tokens.size>=2 && tokens[tokens.lastIndex-1]=="!" && cssName(tokens.last()).equals("important",true)) {
            tokens.removeAt(tokens.lastIndex);tokens.removeAt(tokens.lastIndex);important="!important"
        }
        if(tokens.isEmpty())return null
        val global=cssName(tokens.singleOrNull().orEmpty()).lowercase()
        if(global in setOf("inherit","initial","unset","revert","revert-layer"))return semanticFont(global,global,global,global,important)
        var style="normal";var weight="normal";var variant="normal";var stretch="normal"
        var styled=false;var weighted=false;var varianted=false;var stretched=false;var index=0
        while(index<tokens.size) {
            val raw=tokens[index];val token=cssName(raw).lowercase()
            if(fontSizeToken(token))break
            when {
                token=="normal" -> Unit
                token in setOf("italic","oblique") && !styled -> {
                    styled=true;style=raw
                    if(token=="oblique" && tokens.getOrNull(index+1)?.let { ANGLE.matches(cssName(it).lowercase()) }==true)style+=" "+tokens[++index]
                }
                token=="small-caps" && !varianted -> { varianted=true;variant=raw }
                (token in setOf("bold","bolder","lighter") || NUMBER.matches(token) && token.toDoubleOrNull()?.let { it in 1.0..1000.0 }==true) && !weighted -> { weighted=true;weight=raw }
                token in STRETCH && !stretched -> { stretched=true;stretch=raw }
                else -> return null // var()/system font/unsupported grammar needs an explicit capability notice.
            }
            index++
        }
        if(index>=tokens.lastIndex)return null // require both size and authored family
        index++
        if(tokens.getOrNull(index)=="/") { index+=2;if(index>=tokens.size)return null }
        if(tokens.drop(index).any { it=="/" || it.startsWith('!') })return null
        return semanticFont(style,weight,variant,stretch,important)
    }
    private fun semanticFont(style: String,weight: String,variant: String,stretch: String,important: String)=
        "font-style:$style$important;font-weight:$weight$important;font-variant:$variant$important;font-stretch:$stretch$important;"
    private fun fontSizeToken(token: String)=token in setOf("xx-small","x-small","small","medium","large","x-large","xx-large","xxx-large","smaller","larger","math") ||
        token=="0" || SIZE.matches(token) || token.startsWith("calc(") || token.startsWith("min(") || token.startsWith("max(") || token.startsWith("clamp(")
    private fun importTarget(source: String): Pair<String,String>? {
        val cursor=Cursor(source);cursor.space()
        val target=when(cursor.peek()) {
            '\'', '"' -> cursor.quoted().let { cssName(it.substring(1,it.length-1)) }
            else -> { val name=cssName(cursor.identifier()).lowercase();cursor.space();if(name!="url" || cursor.take()!='(')return null
                val body=cursor.parenthesis().trim();cssName(body.removeSurrounding("\"").removeSurrounding("'")) }
        }
        val media=cursor.remainder().trim();if(media.any { it=='{' || it=='}' || it==';' })return null
        return target to media
    }
    private companion object {
        val NUMBER=Regex("(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)")
        val SIZE=Regex("[+]?(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)(?:px|pt|pc|em|rem|ex|rex|ch|rch|cap|rcap|ic|ric|lh|rlh|vw|vh|vmin|vmax|svw|svh|lvw|lvh|dvw|dvh|vi|vb|cm|mm|q|in|%)")
        val ANGLE=Regex("[+-]?(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)(?:deg|grad|rad|turn)")
        val STRETCH=setOf("ultra-condensed","extra-condensed","condensed","semi-condensed","semi-expanded","expanded","extra-expanded","ultra-expanded")
        val CUSTOM=setOf("color","font","font-family","font-size","line-height","text-align","text-indent","letter-spacing","word-spacing")
    }
}

/** Small CSS lexical cursor shared by sheet and declaration parsers. */
private class Cursor(private val source: String) {
    private var i=0
    fun peek()=source.getOrNull(i) ?: '\u0000'
    fun remaining()=i<source.length
    fun comment()=source.startsWith("/*",i)
    fun take()=source.getOrNull(i++) ?: '\u0000'
    fun remainder()=source.substring(i)
    fun space() {
        while(i<source.length) {
            if(source[i].isWhitespace())i++
            else if(source.startsWith("/*",i)) { val end=source.indexOf("*/",i+2);require(end>=0) { "EPUB CSS 注释未闭合" };i=end+2 }
            else break
        }
    }
    fun more(): Boolean { space();return i<source.length }
    fun quoted(): String {
        val start=i;val quote=take();require(quote=='\'' || quote=='"')
        while(i<source.length) {
            val c=take();if(c==quote)return source.substring(start,i)
            if(c=='\\') { require(i<source.length);if(peek()=='\r') { i++;if(peek()=='\n')i++ } else i++ }
            else require(c!='\n' && c!='\r') { "EPUB CSS 字符串无效" }
        }
        throw IllegalArgumentException("EPUB CSS 字符串未闭合")
    }
    fun identifier(): String {
        val start=i
        while(i<source.length) {
            val c=peek()
            if(c=='\\') { i++;require(i<source.length)
                var digits=0;while(i<source.length && peek().digitToIntOrNull(16)!=null && digits<6) { i++;digits++ }
                if(digits==0)i++ else if(peek().isWhitespace())i++
            } else if(c=='-' || c=='_' || c.isLetterOrDigit() || c.code>=128)i++ else break
        }
        return source.substring(start,i)
    }
    fun until(delimiters: Set<Char>): String = buildString {
        var parens=0;var brackets=0
        while(i<source.length) {
            val c=peek()
            if(parens==0 && brackets==0 && c in delimiters)break
            if(source.startsWith("/*",i)) { space();append(' ');continue }
            if(c=='\'' || c=='"') { append(quoted());continue }
            if(c=='\\') { append(take());require(i<source.length);append(take());continue }
            if(c=='(')parens++ else if(c==')') { require(parens>0);parens-- }
            if(c=='[')brackets++ else if(c==']') { require(brackets>0);brackets-- }
            require(parens<=64 && brackets<=64)
            append(take())
        }
        require(parens==0 && brackets==0);toString()
    }
    fun block(): String = balanced('{','}')
    fun parenthesis(): String = balanced('(',')')
    private fun balanced(open: Char,close: Char): String { return buildString {
        var depth=1
        while(i<source.length) {
            val c=peek()
            if(source.startsWith("/*",i)) { space();append(' ');continue }
            if(c=='\'' || c=='"') { append(quoted());continue }
            if(c=='\\') { append(take());require(i<source.length);append(take());continue }
            i++
            if(c==open) { depth++;require(depth<=64) } else if(c==close) { if(--depth==0)return toString() }
            append(c)
        }
        throw IllegalArgumentException("EPUB CSS 分组未闭合")
    } }
}
private fun cssName(raw: String): String = buildString {
    var i=0
    while(i<raw.length) {
        val c=raw[i++];if(c!='\\') { append(c);continue }
        require(i<raw.length)
        var code=0;var count=0
        while(i<raw.length && raw[i].digitToIntOrNull(16)!=null && count<6) { code=code*16+raw[i++].digitToInt(16);count++ }
        if(count==0) { val next=raw[i++];if(next!='\n' && next!='\r')append(next) }
        else { if(i<raw.length && raw[i].isWhitespace())i++;appendCodePoint(if(code in 1..0x10ffff && code !in 0xd800..0xdfff)code else 0xfffd) }
    }
}

/** Count generated UTF-8 bytes before append; repeated imports cannot allocate an oversized result. */
private class CssOutput {
    private val value=StringBuilder()
    private var bytes=0L
    fun append(text: String) {
        var count=0L;var i=0
        while(i<text.length) {
            val c=text[i++].code
            count+=when { c<0x80 -> 1;c<0x800 -> 2;c in 0xd800..0xdbff && i<text.length && text[i].isLowSurrogate() -> { i++;4 };else -> 3 }
        }
        require(bytes+count<=EpubLimits.CSS_BYTES) { "EPUB 样式展开超过 2 MB 输出预算" }
        bytes+=count;value.append(text)
    }
    fun append(char: Char)=append(char.toString())
    override fun toString()=value.toString()
}
private inline fun buildCss(block: CssOutput.() -> Unit): String {
    val output=CssOutput();output.block();return output.toString()
}
