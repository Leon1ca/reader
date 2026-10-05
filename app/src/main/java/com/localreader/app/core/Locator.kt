package com.localreader.app.core

/** Format-specific locators must never be interpreted by another engine. */
sealed interface Locator {
    val version: String
    data class Text(override val version: String, val sourceOffset: Long, val paragraph: Int,
                    val character: Int = 0, val fallbackFraction: Double = 0.0) : Locator
    /** Unknown JSON is retained exactly; a reader must not reinterpret it as a TXT anchor. */
    data class Unknown(override val version: String, val raw: String) : Locator
    data class Epub(override val version: String, val spine: Int, val offset: Int, val quote: String = "",
                    val progression: Double = 0.0, val fallbackFraction: Double = 0.0, val elementId: String? = null) : Locator
    /** A package document outside the reading spine; returnTo is exactly one main location. */
    data class EpubResource(override val version: String,val docKey: String,val offset: Int,val returnTo: Locator,val quote: String="",val elementId: String?=null): Locator
    data class Page(override val version: String, val page: Int, val x: Double, val y: Double) : Locator
    data class Comic(override val version: String, val imageId: String, val order: Int, val y: Double) : Locator
    /** Structured content uses authored block IDs; never a TXT offset or renderer page number. */
    data class Article(override val version: String, val blockId: String, val textOffset: Int, val element: Boolean = false) : Locator
}
