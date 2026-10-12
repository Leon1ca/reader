package com.localreader.app.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.asStateFlow
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
    private val displayMutable=kotlinx.coroutines.flow.MutableStateFlow(DisplayPreferences(
        if(preferences.contains("brightness"))preferences.getFloat("brightness",-1f).takeIf { it.isFinite() && it in 0f..1f } else null,
        NightMode.entries.firstOrNull { it.name==preferences.getString("night_mode",null) } ?: NightMode.SYSTEM,
        PageTurn.entries.firstOrNull { it.name==preferences.getString("page_turn",null) } ?: PageTurn.NONE))
    override val display=displayMutable.asStateFlow()
    override fun saveDisplay(value: DisplayPreferences) {
        preferences.edit { if(value.brightness==null)remove("brightness") else putFloat("brightness",value.brightness);putString("night_mode",value.nightMode.name);putString("page_turn",value.pageTurn.name) }
        displayMutable.value=value
    }
    override fun shelf()=ShelfPreferences(
        ShelfSort.entries.firstOrNull { it.name==preferences.getString("shelf_sort",null) } ?: ShelfSort.RECENT,
        ShelfFilter.entries.firstOrNull { it.name==preferences.getString("shelf_filter",null) } ?: ShelfFilter.ALL)
    override fun saveShelf(value: ShelfPreferences) { preferences.edit { putString("shelf_sort",value.sort.name);putString("shelf_filter",value.filter.name) } }
}
