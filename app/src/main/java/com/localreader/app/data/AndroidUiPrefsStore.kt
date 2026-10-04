package com.localreader.app.data

import android.content.Context
import androidx.core.content.edit
import com.localreader.app.core.*

/** UI presentation preferences never duplicate effective reading rules. */
class AndroidUiPrefsStore(context: Context) : UiPrefsStore {
    private val preferences=context.getSharedPreferences("ui_prefs",Context.MODE_PRIVATE)
    init {
        if(!preferences.contains("shelf_sort")) {
            val legacy=context.getSharedPreferences("ui",Context.MODE_PRIVATE).getString("sort",null)
            val migrated=when(legacy) { "标题" -> ShelfSort.TITLE;"作者" -> ShelfSort.AUTHOR;"导入时间" -> ShelfSort.IMPORTED;else -> ShelfSort.RECENT }
            preferences.edit { putString("shelf_sort",migrated.name) }
        }
    }
    override fun shelf()=ShelfPreferences(
        ShelfSort.entries.firstOrNull { it.name==preferences.getString("shelf_sort",null) } ?: ShelfSort.RECENT,
        ShelfFilter.entries.firstOrNull { it.name==preferences.getString("shelf_filter",null) } ?: ShelfFilter.ALL)
    override fun saveShelf(value: ShelfPreferences) { preferences.edit { putString("shelf_sort",value.sort.name);putString("shelf_filter",value.filter.name) } }
}
