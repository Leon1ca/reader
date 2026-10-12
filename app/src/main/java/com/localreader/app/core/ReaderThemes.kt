package com.localreader.app.core

/** Shared named palette; source/author styles remain independent of the page exterior. */
object ReaderThemes {
    val labels=linkedMapOf("paper" to "米黄","white" to "白色","gray" to "浅灰","green" to "豆绿","dark" to "深色")
    fun background(theme: String)=when(theme) {"paper" -> "#f3ecdf";"gray" -> "#e6e6e3";"green" -> "#dce8d6";"dark" -> "#1a1917";else -> "#ffffff"}
    fun foreground(theme: String)=when(theme) {"paper" -> "#2b2620";"gray" -> "#2b2b2b";"green" -> "#26301f";"dark" -> "#b9b2a6";else -> "#171717"}
}
