package com.localreader.app.core

import org.junit.Assert.*
import org.junit.Test

class CategoryProjectionTest {
    private fun book(id: String,vararg categories: Long) = ContentSummary(id,ContentFormat.TXT,"书$id","作者",1,1,1,0.0,0.0,categories=categories.toSet())
    private val all = listOf(book("a",1),book("b",1,2),book("c",2),book("d"))
    private fun shown(category: Long) = ShelfProjection.project(all,ShelfState(category=category)).items.map { it.id }.sorted()

    @Test fun allShowsEverythingAndUncategorizedOnlyBooksInNoCategory() {
        assertEquals(listOf("a","b","c","d"),shown(ShelfCategories.ALL))
        assertEquals(listOf("d"),shown(ShelfCategories.NONE))
    }
    @Test fun aCategoryShowsItsMembersIncludingBooksInSeveral() {
        assertEquals(listOf("a","b"),shown(1))
        assertEquals(listOf("b","c"),shown(2))
        assertEquals(emptyList<String>(),shown(99))
    }
    @Test fun categoryCombinesWithSearchAndShowsEveryFormat() {
        val rows = listOf(book("a",1).copy(format=ContentFormat.EPUB),book("b",1))
        assertEquals(listOf("a","b"),ShelfProjection.project(rows,ShelfState(category=1)).items.map { it.id }.sorted())
        assertEquals(listOf("a"),ShelfProjection.project(rows,ShelfState(category=1,query="书a")).items.map { it.id })
    }
    @Test fun selectionIsDroppedForBooksOutsideTheCategory() {
        val state = ShelfProjection.project(all,ShelfState(category=1,selected=setOf("a","c","d")))
        assertEquals(setOf("a"),state.selected)
    }
    @Test fun namesAreCleanedAndBoundedNotSilentlyCut() {
        assertEquals("小 说",CategoryNames.clean("  小   说 "))
        assertThrows(IllegalArgumentException::class.java) { CategoryNames.clean("   ") }
        assertThrows(IllegalArgumentException::class.java) { CategoryNames.clean("a".repeat(CategoryNames.MAX + 1)) }
        assertEquals(CategoryNames.MAX,CategoryNames.clean("a".repeat(CategoryNames.MAX)).length)
        assertThrows(IllegalArgumentException::class.java) { CategoryNames.clean("a\u0007b") }
    }
}
