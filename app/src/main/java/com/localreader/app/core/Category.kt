package com.localreader.app.core

import kotlinx.coroutines.flow.Flow

/** A user-made group of books. A book may be in several; deleting a category never deletes books. */
data class Category(val id: Long,val name: String,val sort: Int,val count: Int)

interface CategoryStore {
    fun categories(): List<Category>
    fun observeCategories(): Flow<List<Category>>
    fun createCategory(name: String): Long
    fun renameCategory(id: Long,name: String)
    fun deleteCategory(id: Long)
    /** Gives the categories the order of [orderedIds], which must name every existing category exactly once. */
    fun reorderCategories(orderedIds: List<Long>)
    fun setMembership(contentIds: Collection<String>,categoryId: Long,member: Boolean)
    /** Number of books in no category: the system view. */
    fun uncategorizedCount(): Int
}

object CategoryNames {
    const val MAX = 40
    /** Trims and collapses whitespace; blank or over-long names are refused rather than silently cut. */
    fun clean(raw: String): String {
        val name = raw.trim().replace(Regex("\\s+")," ")
        require(name.isNotEmpty()) { "分类名称不能为空" }
        require(name.length <= MAX) { "分类名称最多 $MAX 个字" }
        require(name.none { it.isISOControl() }) { "分类名称含无效字符" }
        return name
    }
}
