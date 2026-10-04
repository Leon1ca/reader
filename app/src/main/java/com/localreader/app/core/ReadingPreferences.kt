package com.localreader.app.core

sealed interface FieldUpdate<out T> {
    data object Keep : FieldUpdate<Nothing>
    data object Clear : FieldUpdate<Nothing>
    data class Set<T>(val value: T) : FieldUpdate<T>
}
enum class RulesScope { BOOK, GLOBAL }
enum class RuleOrigin { BOOK, FORMAT, APP }
/** A patch names only fields the user changed; queued writes cannot revive cleared overrides. */
data class RulesPatch(val fontSize: FieldUpdate<Float> = FieldUpdate.Keep,
    val theme: FieldUpdate<String> = FieldUpdate.Keep, val flow: FieldUpdate<ReaderFlow> = FieldUpdate.Keep,
    val lineHeight: FieldUpdate<Double> = FieldUpdate.Keep, val margin: FieldUpdate<String> = FieldUpdate.Keep,
    val fontFamily: FieldUpdate<String> = FieldUpdate.Keep, val columns: FieldUpdate<String> = FieldUpdate.Keep,
    val epubStyleMode: FieldUpdate<String> = FieldUpdate.Keep, val fxlZoom: FieldUpdate<String> = FieldUpdate.Keep,
    val spreadPreference: FieldUpdate<String> = FieldUpdate.Keep)
data class EffectiveRules(val rules: ReaderRules, val origins: Map<String,RuleOrigin>, val preferencesRevision: Long, val lineHeight: Double = 1.6, val margin: String = "M",
    val fontFamily: String = "default", val columns: String = "auto", val epubStyleMode: String = "AUTHOR",
    val fxlZoom: String = "fit-page", val spreadPreference: String = "AUTO")
interface ReadingPreferences {
    val preferencesRevision: kotlinx.coroutines.flow.StateFlow<Long>
    fun effectiveRules(contentId: String): EffectiveRules
    fun defaults(format: ContentFormat = ContentFormat.TXT): EffectiveRules
    fun patchRules(contentId: String?, revision: String?, format: ContentFormat, scope: RulesScope, patch: RulesPatch): EffectiveRules
    fun resetBookRules(contentId: String, revision: String): EffectiveRules
}
