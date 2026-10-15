package com.localreader.app.ui

import com.localreader.app.ui.app.LibraryNavigation
import com.localreader.app.ui.app.LibraryRoute
import com.localreader.app.ui.app.RouteKind
import org.junit.Assert.*
import org.junit.Test

class LibraryNavigationTest {
    private fun navigation()=LibraryNavigation(List(4) {listOf(LibraryRoute(RouteKind.ROOT))})
    @Test fun detailsPopToTheirOriginAndTabsKeepIndependentStacks() {
        val navigation=navigation()
        navigation.push(0,LibraryRoute(RouteKind.CATEGORIES))
        navigation.push(0,LibraryRoute(RouteKind.STORAGE,"book"))
        navigation.push(3,LibraryRoute(RouteKind.STORAGE))
        assertEquals(RouteKind.STORAGE,navigation.current(3).kind)
        assertEquals("book",navigation.current(0).bookId)
        navigation.pop(0)
        assertEquals(RouteKind.CATEGORIES,navigation.current(0).kind)
        assertEquals(RouteKind.STORAGE,navigation.current(3).kind)
        navigation.pop(0);navigation.pop(0)
        assertEquals(RouteKind.ROOT,navigation.current(0).kind)
    }
    /** The continue-reading list and the book detail page were removed; a stack saved by an older version still restores, without them. */
    @Test fun aSavedStackWithARemovedRouteRestoresWithoutIt() {
        val restored=requireNotNull(LibraryNavigation.Saver.restore(listOf("0:ROOT:","0:CONTINUE:","0:DETAIL:book","0:CATEGORIES:","3:ROOT:")))
        assertEquals(listOf(RouteKind.ROOT,RouteKind.CATEGORIES),restored.stacks[0].map { it.kind })
        assertEquals(RouteKind.ROOT,restored.current(1).kind)
    }
}
