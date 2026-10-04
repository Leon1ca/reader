package com.localreader.app.data

import com.localreader.app.core.Locator
import com.localreader.app.core.LocatorCodec
import com.localreader.app.core.StrictJson
import org.json.JSONObject

/** Only migration may write this explicit compatibility payload. New positions use the strict codec. */
internal object StoredLocatorCodec {
    private const val LEGACY = "v3-finite-txt-fallback"
    fun encodeLegacyTxt(locator: Locator.Text): String {
        require(locator.fallbackFraction.isFinite()) { "旧定位比例不是有限数值，原库已保留" }
        val bounded = locator.copy(fallbackFraction = locator.fallbackFraction.coerceIn(0.0,1.0))
        if (bounded == locator) return LocatorCodec.encode(locator)
        return JSONObject(LocatorCodec.encode(bounded)).put("fallbackFraction",locator.fallbackFraction).put("compatibility",LEGACY).toString()
    }
    fun decode(raw: String): Locator {
        require(raw.length <= 32768);StrictJson(raw).validate()
        val json = JSONObject(raw)
        if (json.opt("compatibility") != LEGACY) return LocatorCodec.decode(raw)
        require(json.opt("schema") == 1 && json.opt("t") == "text")
        val value = json.get("fallbackFraction");require(value is Number && value.toDouble().isFinite())
        val normalized = LocatorCodec.decode(json.put("fallbackFraction",value.toDouble().coerceIn(0.0,1.0)).toString())
        require(normalized is Locator.Text)
        return normalized.copy(fallbackFraction = value.toDouble())
    }
}
