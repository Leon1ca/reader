package com.localreader.app.core

/** Android JSONObject accepts JavaScript-like syntax. Persisted locator JSON uses strict JSON instead. */
internal class StrictJson(private val raw: String) {
    private var offset = 0
    fun validate() { value(0);space();require(offset == raw.length) { "定位JSON包含尾随数据" } }
    private fun space() { while(offset < raw.length && raw[offset] in " \t\r\n") offset++ }
    private fun consume(c: Char): Boolean { space();if(offset < raw.length && raw[offset] == c) { offset++;return true };return false }
    private fun expect(c: Char) { require(consume(c)) { "定位JSON格式错误" } }
    private fun value(depth: Int) {
        require(depth <= 64);space();require(offset < raw.length)
        when(raw[offset]) {
            '{' -> { offset++;val keys=hashSetOf<String>();if(!consume('}')) { do { space();val key=string();require(keys.add(key)) { "定位JSON字段重复" };expect(':');value(depth+1) } while(consume(','));expect('}') } }
            '[' -> { offset++;if(!consume(']')) { do { value(depth+1) } while(consume(','));expect(']') } }
            '"' -> string()
            't' -> literal("true")
            'f' -> literal("false")
            'n' -> literal("null")
            else -> { val match=NUMBER.matchAt(raw,offset);require(match != null);offset=match.range.last+1 }
        }
    }
    private fun literal(text: String) { require(raw.startsWith(text,offset));offset+=text.length }
    private fun string(): String {
        require(offset < raw.length && raw[offset++] == '"')
        val decoded=StringBuilder()
        while(offset < raw.length) {
            val c=raw[offset++]
            if(c == '"') return decoded.toString()
            require(c >= ' ')
            if(c != '\\') decoded.append(c)
            else {
                require(offset < raw.length)
                when(val escape=raw[offset++]) {
                    '"','\\','/' -> decoded.append(escape)
                    'b' -> decoded.append('\b')
                    'f' -> decoded.append('\u000c')
                    'n' -> decoded.append('\n')
                    'r' -> decoded.append('\r')
                    't' -> decoded.append('\t')
                    'u' -> { require(offset+4 <= raw.length);val hex=raw.substring(offset,offset+4);require(hex.all { it.digitToIntOrNull(16) != null });decoded.append(hex.toInt(16).toChar());offset+=4 }
                    else -> error("定位JSON转义错误")
                }
            }
        }
        error("定位JSON字符串未结束")
    }
    private companion object { val NUMBER=Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?") }
}
