package com.localreader.app.formats.epub

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** Logical spine page identity. Viewport and declaration are renderer metadata, never locators. */
data class EpubFixedPage(val index: Int,val width: Double,val height: Double,val declaredLayout: String,val spread: String,val pageSpread: String?)
internal object EpubFixedViewport {
    private fun positive(value: String?): Double?=value?.toDoubleOrNull()?.takeIf { it.isFinite() && it>0 && it<=16384 }
    private fun named(value: String?): Pair<Double,Double>? {
        if(value==null)return null
        val fields=value.split(Regex("[,;\\s]+")).mapNotNull { token -> val parts=token.split('=',limit=2);if(parts.size==2)parts[0].lowercase() to parts[1] else null }.toMap()
        val width=positive(fields["width"]) ?: return null;val height=positive(fields["height"]) ?: return null
        return width to height
    }
    fun resolve(html: String,bookViewport: String?,standaloneSvg: Boolean=false,imageSize: ((Element)->Pair<Double,Double>?)?=null): Pair<Double,Double>? {
        val doc=Jsoup.parse(html)
        named(doc.selectFirst("meta[name=viewport]")?.attr("content"))?.let { return it }
        fun svgSize(svg: Element): Pair<Double,Double>? {
            val box=svg.attr("viewBox").ifEmpty { svg.attr("viewbox") }.trim().split(Regex("[,\\s]+"))
            if(box.size==4 && box.all { it.toDoubleOrNull()?.isFinite()==true }) { val width=positive(box[2]);val height=positive(box[3]);if(width!=null && height!=null)return width to height }
            val width=positive(svg.attr("width").removeSuffix("px"));val height=positive(svg.attr("height").removeSuffix("px"));return if(width!=null && height!=null)width to height else null
        }
        if(standaloneSvg)doc.selectFirst("svg")?.let { svgSize(it)?.let { size -> return size } }
        named(bookViewport)?.let { return it }
        if(standaloneSvg && doc.selectFirst("svg")!=null)return 300.0 to 150.0
        val root=doc.body().children().singleOrNull()
        if(root?.normalName()=="svg" && doc.body().ownText().isBlank())svgSize(root)?.let { return it }
        doc.selectFirst("img")?.let { image ->
            val width=positive(image.attr("width"));val height=positive(image.attr("height"));if(width!=null && height!=null)return width to height
            imageSize?.invoke(image)?.takeIf { it.first.isFinite() && it.second.isFinite() && it.first in 1.0..16384.0 && it.second in 1.0..16384.0 }?.let { return it }
        }
        return null
    }
}
