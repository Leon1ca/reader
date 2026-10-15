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
            val migrated=when(legacy) { "标题" -> ShelfSort.TITLE;"作者" -> ShelfSort.AUTHOR;"导入时间" -> ShelfSort.RECENT;else -> ShelfSort.RECENT }
            preferences.edit { putString("shelf_sort",migrated.name) }
        }
    }
    /** A stored value of an unexpected type (an old or damaged file) reads as the default instead of failing start-up. */
    private fun <T> safe(default: T,read: () -> T): T = try { read() } catch(_: ClassCastException) { default }
    private val displayMutable=kotlinx.coroutines.flow.MutableStateFlow(DisplayPreferences(
        safe(null) { if(preferences.contains("brightness"))preferences.getFloat("brightness",-1f).takeIf { it.isFinite() && it in 0f..1f } else null },
        NightMode.entries.firstOrNull { it.name==safe(null) { preferences.getString("night_mode",null) } } ?: NightMode.SYSTEM,
        PageTurn.entries.firstOrNull { it.name==safe(null) { preferences.getString("page_turn",null) } } ?: PageTurn.NONE))
    override val display=displayMutable.asStateFlow()
    override fun saveDisplay(value: DisplayPreferences) {
        if(com.localreader.app.core.backup.RestoreGate.blocked) return // settings are about to be put back by the unfinished restore
        preferences.edit { if(value.brightness==null)remove("brightness") else putFloat("brightness",value.brightness);putString("night_mode",value.nightMode.name);putString("page_turn",value.pageTurn.name) }
        displayMutable.value=value
    }
    override fun shelf()=ShelfPreferences(
        ShelfSort.entries.firstOrNull { it.name==safe(null) { preferences.getString("shelf_sort",null) } } ?: ShelfSort.RECENT,
        safe(ShelfCategories.ALL) { preferences.getLong("shelf_category",ShelfCategories.ALL) })
    override fun saveShelf(value: ShelfPreferences) { if(com.localreader.app.core.backup.RestoreGate.blocked) return;preferences.edit { putString("shelf_sort",value.sort.name);remove("shelf_filter");putLong("shelf_category",value.category) } }
}
