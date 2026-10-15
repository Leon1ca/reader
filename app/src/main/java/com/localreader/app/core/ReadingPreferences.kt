package com.localreader.app.core

sealed interface FieldUpdate<out T> {
    data object Keep : FieldUpdate<Nothing>
    data class Set<T>(val value: T) : FieldUpdate<T>
}
/**
 * A patch names only the fields the user changed; queued writes cannot revive older values.
 *
 * Typography, theme and flow are one app-wide set shared by every book. Only the fixed-layout
 * view state ([fxlZoom], [spreadPreference]) belongs to a single book, like the reading position.
 */
data class RulesPatch(val fontSize: FieldUpdate<Float> = FieldUpdate.Keep,
    val theme: FieldUpdate<String> = FieldUpdate.Keep, val flow: FieldUpdate<ReaderFlow> = FieldUpdate.Keep,
    val lineHeight: FieldUpdate<Double> = FieldUpdate.Keep, val margin: FieldUpdate<String> = FieldUpdate.Keep,
    val fontFamily: FieldUpdate<String> = FieldUpdate.Keep, val columns: FieldUpdate<String> = FieldUpdate.Keep,
    val epubStyleMode: FieldUpdate<String> = FieldUpdate.Keep, val fxlZoom: FieldUpdate<String> = FieldUpdate.Keep,
    val spreadPreference: FieldUpdate<String> = FieldUpdate.Keep, val paragraphSpacing: FieldUpdate<Double> = FieldUpdate.Keep) {
    /** True when the patch changes something that is stored per book instead of app-wide. */
    val touchesBook: Boolean get() = fxlZoom != FieldUpdate.Keep || spreadPreference != FieldUpdate.Keep
}
data class EffectiveRules(val rules: ReaderRules, val preferencesRevision: Long)
interface ReadingPreferences {
    val preferencesRevision: kotlinx.coroutines.flow.StateFlow<Long>
    /** The app-wide rules; [contentId] only selects that book's fixed-layout view state. */
    fun effectiveRules(contentId: String? = null): EffectiveRules
    /** Writes app-wide fields; per-book fields in the patch are rejected. */
    fun patchRules(patch: RulesPatch): EffectiveRules
}
