package com.localreader.app.ui

import com.localreader.app.ui.app.LibraryNavigation
import com.localreader.app.ui.app.LibraryRoute
import com.localreader.app.ui.app.RouteKind
import org.junit.Assert.*
import org.junit.Test

class LibraryNavigationTest {
    private fun navigation()=LibraryNavigation(List(4) {listOf(LibraryRoute(RouteKind.ROOT))})
    @Test fun detailsPopToContinueAndTabsKeepIndependentStacks() {
        val navigation=navigation()
        navigation.push(0,LibraryRoute(RouteKind.CONTINUE))
        navigation.push(0,LibraryRoute(RouteKind.DETAIL,"book"))
        navigation.push(3,LibraryRoute(RouteKind.STORAGE))
        assertEquals(RouteKind.STORAGE,navigation.current(3).kind)
        assertEquals("book",navigation.current(0).bookId)
        navigation.pop(0)
        assertEquals(RouteKind.CONTINUE,navigation.current(0).kind)
        assertEquals(RouteKind.STORAGE,navigation.current(3).kind)
        navigation.pop(0);navigation.pop(0)
        assertEquals(RouteKind.ROOT,navigation.current(0).kind)
    }
}
