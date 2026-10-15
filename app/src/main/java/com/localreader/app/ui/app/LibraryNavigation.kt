package com.localreader.app.ui.app

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable

internal enum class RouteKind { ROOT, STORAGE, LICENSES, BACKUP, RESTORE_PREVIEW, CATEGORIES, DIAGNOSTICS, FONTS }
internal enum class LibrarySheet { IMPORT, MORE }
internal data class LibraryRoute(val kind: RouteKind,val bookId: String?=null)

/** Every tab has its own stack; popping details preserves the origin screen. */
internal class LibraryNavigation(initial: List<List<LibraryRoute>>) {
    var stacks by mutableStateOf(initial); private set
    fun current(tab: Int)=stacks[tab].last()
    fun push(tab: Int,route: LibraryRoute) { stacks=stacks.mapIndexed {index,stack -> if(index==tab)stack+route else stack} }
    fun pop(tab: Int) {stacks=stacks.mapIndexed {index,stack -> if(index==tab && stack.size>1)stack.dropLast(1) else stack} }
    companion object {
        val Saver=listSaver<LibraryNavigation,String>(save={ nav -> nav.stacks.flatMapIndexed {tab,stack -> stack.map {"$tab:${it.kind.name}:${it.bookId.orEmpty()}"}} },restore={ saved ->
            LibraryNavigation((0..3).map {tab -> saved.filter {it.startsWith("$tab:")}.mapNotNull {value -> val parts=value.split(':',limit=3)
                // A route removed in a later version (the continue-reading list) is dropped from a restored stack.
                RouteKind.entries.firstOrNull { it.name==parts.getOrNull(1) }?.let { LibraryRoute(it,parts.getOrNull(2)?.ifEmpty {null}) }}.ifEmpty {listOf(LibraryRoute(RouteKind.ROOT))} })
        })
    }
}
@Composable internal fun rememberLibraryNavigation()=rememberSaveable(saver=LibraryNavigation.Saver) {LibraryNavigation(List(4) {listOf(LibraryRoute(RouteKind.ROOT))})}
