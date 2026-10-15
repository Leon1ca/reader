package com.localreader.app.data

import android.content.Context
import com.localreader.app.core.backup.*
import org.json.JSONObject
import java.io.File

/** Owns the restore journal for this app. Runs before the library database is opened. */
class RestoreCoordinator(private val context: Context) {
    val layout = RestoreLayout(File(context.filesDir,"restore"),File(context.filesDir,"library"),context.getDatabasePath("library.db").parentFile!!,liveFonts = File(context.filesDir,"user-fonts"),liveTrash = File(context.filesDir,"trash"))
    val swap = RestoreSwap(layout,::preferencesJson,::applyPreferences,::syncDirectory,restorePreferences = ::restorePreferences)
    @Volatile var outcome: RestoreOutcome? = null; private set

    /** Call from Application.onCreate: finishes or undoes any confirmed restore before any other component touches data. */
    fun recoverOnStart() {
        var failed = false
        outcome = try { swap.recover() } catch(error: Exception) { failed = true;RestoreOutcome(false,error.message ?: "恢复检查失败") }
        // A result that an earlier process produced but the user never saw (killed before the UI showed it).
        if(outcome == null) layout.taskRoot.list().orEmpty().firstNotNullOfOrNull { id -> swap.outcome(id)?.takeIf { !it.applied } }?.let { outcome = it }
        refreshGate(failed)
    }
    /**
     * Raised whenever a started restore did not end safely — whichever step failed, including the journal itself — so the
     * library stays closed and the next start can continue from exactly what the journal says. It never depends on one phase
     * having been written.
     */
    private fun refreshGate(failed: Boolean = false,committedToo: Boolean = false) {
        RestoreGate.reason = if(failed || swap.unsafePending(committedToo)) "上一次恢复尚未安全结束，请重启应用重试；在此之前不会读写书库" else null
    }
    /** The restored library failed to load: undo the switch and say so; the next start opens the previous content. */
    fun libraryFailedToOpen(): RestoreOutcome? {
        var failed = false
        val result = try { swap.rollbackCommitted() } catch(error: Exception) { failed = true;RestoreOutcome(false,error.message) }
        refreshGate(failed,committedToo = true)
        return result
    }
    /** Validates the typed preference document against the known schema without applying it. */
    fun validatePreferences(json: String) { PreferenceSchema.validate(JSONObject(json)) }
    fun consumeOutcome(): RestoreOutcome? = outcome.also { outcome = null }
    /** The restored library opened successfully; the replaced content can go. A rolled-back notice is dismissed once seen. */
    fun libraryOpened() {
        swap.finalizeCommitted()
        layout.taskRoot.list().orEmpty().forEach { swap.acknowledge(it) }
    }

    private val files = listOf("ui_prefs","ui")
    fun preferencesJson(): String {
        val root = JSONObject()
        files.forEach { name ->
            val values = JSONObject()
            context.getSharedPreferences(name,Context.MODE_PRIVATE).all.forEach { (key,value) ->
                val typed = when(value) {
                    is Boolean -> JSONObject().put("t","b").put("v",value)
                    is Int -> JSONObject().put("t","i").put("v",value)
                    is Long -> JSONObject().put("t","l").put("v",value)
                    is Float -> JSONObject().put("t","f").put("v",value.toDouble())
                    is String -> JSONObject().put("t","s").put("v",value)
                    else -> null
                }
                if(typed != null) values.put(key,typed)
            }
            root.put(name,values)
        }
        return root.toString()
    }
    /**
     * Writes the settings of a package. Every file is written with `commit()` and its result is checked, then read back: a write
     * that did not reach storage throws, so the switch is rolled back instead of being reported as done.
     */
    private fun applyPreferences(json: String) = writePreferences(json,strict = true)
    /** Used when putting the previous settings back: unknown or out-of-range old values are skipped, but a failed write still throws. */
    private fun restorePreferences(json: String) = writePreferences(json,strict = false)

    @android.annotation.SuppressLint("ApplySharedPref","UseKtx")
    private fun writePreferences(json: String,strict: Boolean) {
        val root = JSONObject(json)
        if(strict) PreferenceSchema.validate(root)
        PreferenceSchema.files.forEach { name ->
            val accepted = PreferenceSchema.accepted(root,name)
            val preferences = context.getSharedPreferences(name,Context.MODE_PRIVATE)
            val editor = preferences.edit().clear()
            accepted.forEach { (key,value) ->
                when(value) {
                    is Boolean -> editor.putBoolean(key,value)
                    is Int -> editor.putInt(key,value)
                    is Long -> editor.putLong(key,value)
                    is Float -> editor.putFloat(key,value)
                    is String -> editor.putString(key,value)
                }
            }
            check(editor.commit()) { "无法写入设置" }
            val stored = preferences.all
            check(stored.keys == accepted.keys && accepted.all { (key,value) -> stored[key] == value }) { "设置写入后校验不一致" }
        }
    }
    /** Makes a directory's entries durable. A failure is reported, not hidden: the next journal phase must not be published over it. */
    private fun syncDirectory(directory: File) {
        val descriptor = android.system.Os.open(directory.path,android.system.OsConstants.O_RDONLY,0)
        try { android.system.Os.fsync(descriptor) } finally { android.system.Os.close(descriptor) }
    }
    /** Used by staging to make a whole extracted package durable before it can be confirmed. */
    internal fun syncTree(root: File) = Durable.syncTree(root,::syncDirectory)
}
