package com.localreader.app.ui

import android.content.Context
import androidx.annotation.StringRes

/** Shared resource lookup also serves native Views and non-composable UI helpers. */
internal object UiStrings {
    private lateinit var application: Context
    fun initialize(context: Context) {application=context.applicationContext}
    fun theme(id: String): String=text(when(id) {
        "paper"->com.localreader.app.R.string.theme_paper;"gray"->com.localreader.app.R.string.theme_gray
        "green"->com.localreader.app.R.string.theme_green;"dark"->com.localreader.app.R.string.theme_dark
        else->com.localreader.app.R.string.theme_white
    })
    /** Provider and filesystem exception payloads never become visible path disclosures. */
    fun safeMessage(message: String): String=if(Regex("(?:/|[A-Za-z]:\\\\|content:|file:)").containsMatchIn(message))text(com.localreader.app.R.string.operation_incomplete) else message
    fun relativeTime(timestamp: Long,now: Long=System.currentTimeMillis()): String {
        val minutes=((now-timestamp).coerceAtLeast(0)/60_000)
        return when {
            minutes<1 -> text(com.localreader.app.R.string.relative_just_now)
            minutes<60 -> text(com.localreader.app.R.string.relative_minutes_ago,minutes)
            minutes<1440 -> text(com.localreader.app.R.string.relative_hours_ago,minutes/60)
            else -> text(com.localreader.app.R.string.relative_days_ago,minutes/1440)
        }
    }
    fun text(@StringRes id: Int,vararg arguments: Any?): String=application.getString(id,*arguments)
}
