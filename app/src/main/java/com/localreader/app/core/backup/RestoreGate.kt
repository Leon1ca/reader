package com.localreader.app.core.backup

/**
 * While a restore could not be finished or undone (its journal is still ROLLING_BACK), the library must not be opened or
 * written: anything written then lands in content the next retry will move or delete. Every storage entry point checks this
 * gate, so the application stays read-nothing/write-nothing until a start whose recovery succeeds.
 */
object RestoreGate {
    @Volatile var reason: String? = null
    fun require() { reason?.let { throw IllegalStateException(it) } }
    val blocked: Boolean get() = reason != null
}
