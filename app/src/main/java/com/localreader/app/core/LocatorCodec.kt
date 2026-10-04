package com.localreader.app.core

import com.localreader.app.formats.txt.TxtReadingExtent
import org.json.JSONObject

/** Persisted JSON never passes through floating point for integer source offsets. */
object LocatorCodec {
    fun encode(locator: Locator): String {
        if (locator is Locator.Unknown) { decode(locator.raw); return locator.raw }
        val json = JSONObject().put("schema",1).put("version",locator.version)
        require(locator.version.isNotEmpty())
        when (locator) {
            is Locator.Text -> { json.put("t","text").put("sourceOffset",locator.sourceOffset).put("paragraph",locator.paragraph)
                .put("character",locator.character).put("fallbackFraction",locator.fallbackFraction) }
            is Locator.Article -> json.put("t","article").put("blockId",locator.blockId).put("textOffset",locator.textOffset).put("element",locator.element)
            is Locator.Epub -> json.put("t","epub").put("spine",locator.spine).put("offset",locator.offset).put("quote",safePrefix(locator.quote,48))
                .put("progression",locator.progression).put("fallbackFraction",locator.fallbackFraction)
            is Locator.Page -> json.put("t","page").put("page",locator.page).put("x",locator.x).put("y",locator.y)
            is Locator.Comic -> json.put("t","comic").put("imageId",locator.imageId).put("order",locator.order).put("y",locator.y)
            is Locator.Unknown -> error("unreachable")
        }
        return json.toString().also { decode(it) }
    }
    fun decode(raw: String): Locator {
        require(raw.length <= 32768) { "定位数据过大" }
        StrictJson(raw).validate()
        val json = JSONObject(raw)
        val version = string(json,"version")
        if (json.opt("schema") != 1) return Locator.Unknown(version,raw)
        return when (string(json,"t")) {
            "text" -> Locator.Text(version,integer(json,"sourceOffset"),int(json,"paragraph"),int(json,"character"),fraction(json,"fallbackFraction"))
            "article" -> Locator.Article(version,string(json,"blockId"),int(json,"textOffset"),boolean(json,"element"))
            "epub" -> Locator.Epub(version,int(json,"spine"),int(json,"offset"),string(json,"quote",allowEmpty = true).also { require(it.length <= 48 && wellFormed(it)) },fraction(json,"progression"),fraction(json,"fallbackFraction"))
            "page" -> Locator.Page(version,int(json,"page"),fraction(json,"x"),fraction(json,"y"))
            "comic" -> Locator.Comic(version,string(json,"imageId"),int(json,"order"),fraction(json,"y"))
            else -> Locator.Unknown(version,raw)
        }
    }
    fun key(locator: Locator): String = when (locator) {
        is Locator.Text -> "text|${locator.version}|${locator.sourceOffset}"
        is Locator.Epub -> "epub|${locator.version}|${locator.spine}|${locator.offset}"
        is Locator.Article -> "article|${locator.version}|${locator.blockId}|${locator.textOffset}|${locator.element}"
        is Locator.Page -> "page|${locator.version}|${locator.page}|${locator.x}|${locator.y}"
        is Locator.Comic -> "comic|${locator.version}|${locator.imageId}|${locator.order}|${locator.y}"
        is Locator.Unknown -> "unknown|${locator.version}|${locator.raw}"
    }
    internal fun integer(json: JSONObject, name: String): Long {
        val value = json.get(name)
        require(value is Int || value is Long) { "$name 必须是整数" }
        return (value as Number).toLong().also { require(it >= 0) }
    }
    private fun int(json: JSONObject, name: String) = integer(json,name).also { require(it <= Int.MAX_VALUE) }.toInt()
    internal fun fraction(json: JSONObject, name: String): Double {
        val value = json.get(name); require(value is Number)
        return value.toDouble().also { require(it.isFinite() && it in 0.0..1.0) }
    }
    internal fun string(json: JSONObject, name: String, allowEmpty: Boolean = false): String {
        val value = json.get(name); require(value is String && (allowEmpty || value.isNotEmpty()))
        return value
    }
    private fun boolean(json: JSONObject, name: String): Boolean { val value = json.get(name); require(value is Boolean); return value }
    fun safePrefix(text: String, limit: Int): String {
        var end = minOf(text.length,limit)
        if (end > 0 && end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        return text.substring(0,end)
    }
    private fun wellFormed(text: String): Boolean {
        var i = 0
        while (i < text.length) { val c = text[i++]; if (c.isHighSurrogate()) { if (i == text.length || !text[i++].isLowSurrogate()) return false } else if (c.isLowSurrogate()) return false }
        return true
    }
}

data class UnknownReadingExtent(val version: String, val raw: String) : ReadingExtent
object ReadingExtentCodec {
    fun encode(extent: ReadingExtent): String = when (extent) {
        is TxtReadingExtent -> JSONObject().put("schema",1).put("t","txt-extent").put("version",extent.revision).put("sourceOffset",extent.sourceOffset).toString().also { decode(it) }
        is UnknownReadingExtent -> extent.raw.also { decode(it) }
        else -> error("不支持的阅读范围类型")
    }
    fun decode(raw: String): ReadingExtent {
        require(raw.length <= 32768)
        StrictJson(raw).validate()
        val json = JSONObject(raw); val version = LocatorCodec.string(json,"version")
        return if (json.opt("schema") == 1 && json.opt("t") == "txt-extent") TxtReadingExtent(version,LocatorCodec.integer(json,"sourceOffset"))
        else UnknownReadingExtent(version,raw)
    }
}
