package com.localreader.app.core.backup

import com.localreader.app.core.NightMode
import com.localreader.app.core.PageTurn
import com.localreader.app.core.ShelfSort
import org.json.JSONObject

/**
 * The preferences a backup may carry, with the exact type and range of each. A package is judged against this before it can
 * replace anything, because a value of the wrong type would otherwise reach SharedPreferences readers that cast it and throw
 * at start-up, after the switch has been committed.
 */
object PreferenceSchema {
    private fun strings(vararg values: String): (Any?) -> Boolean = { it is String && it in values }
    private val known: Map<String,Map<String,Pair<String,(Any?) -> Boolean>>> = mapOf(
        "ui_prefs" to mapOf(
            // Older backups carry IMPORTED sorts and a TXT/EPUB filter; they are accepted and read back as the default shelf view.
            "shelf_sort" to ("s" to strings(*ShelfSort.entries.map { it.name }.toTypedArray(),"IMPORTED")),
            "shelf_filter" to ("s" to strings("ALL","TXT","EPUB")),
            "shelf_category" to ("l" to { v: Any? -> v is Long && v >= -1 }),
            "brightness" to ("f" to { v: Any? -> v is Double && v.isFinite() && v in 0.0..1.0 }),
            "night_mode" to ("s" to strings(*NightMode.entries.map { it.name }.toTypedArray())),
            "page_turn" to ("s" to strings(*PageTurn.entries.map { it.name }.toTypedArray())),
        ),
        "ui" to mapOf(
            "tab" to ("i" to { v: Any? -> v is Int && v in 0..3 }),
            "sort" to ("s" to { v: Any? -> v is String && v.length <= 20 }),
        ),
    )

    /** One typed entry as stored in the backup: `{"t":"f","v":0.5}`. Returns the Kotlin value or throws if it is malformed. */
    private fun read(entry: JSONObject): Pair<String,Any> {
        val type = entry.getString("t")
        val value: Any = when(type) {
            "b" -> entry.getBoolean("v")
            "i" -> entry.getInt("v")
            "l" -> entry.getLong("v")
            "f" -> entry.getDouble("v")
            "s" -> entry.getString("v")
            else -> throw IllegalArgumentException("未知设置类型")
        }
        return type to value
    }

    /** Throws if any known key has the wrong type or an out-of-range value. Unknown keys are not an error; they are not applied. */
    fun validate(root: JSONObject) {
        known.forEach { (file,keys) -> root.optJSONObject(file)?.let { values -> keys.forEach { (key,rule) ->
            val entry = values.optJSONObject(key) ?: return@forEach
            val (type,value) = read(entry)
            require(type == rule.first && rule.second(value)) { "设置 $file/$key 的类型或取值无效" }
        } } }
    }

    /** The validated, known entries of one file, as Kotlin values ready to write. */
    fun accepted(root: JSONObject,file: String): Map<String,Any> {
        val values = root.optJSONObject(file) ?: return emptyMap()
        return known.getValue(file).mapNotNull { (key,rule) ->
            val entry = values.optJSONObject(key) ?: return@mapNotNull null
            val (type,value) = read(entry)
            if(type == rule.first && rule.second(value)) key to (if(type == "f") (value as Double).toFloat() else value) else null
        }.toMap()
    }
    val files: Set<String> get() = known.keys
}
