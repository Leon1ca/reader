package com.localreader.app.core

data class ReaderCapabilities(val search: Boolean=false,val fontSize: Boolean=true,val authorStyle: Boolean=false,val textPreview: Boolean=false,val fixedZoom: Boolean=false,val spread: Boolean=false)
data class FxlViewState(val zoom: Double,val preference: String,val visiblePages: List<Int>,val overflowX: Boolean,val overflowY: Boolean) {
    init { require(zoom.isFinite() && zoom in 1.0..5.0 && preference in setOf("AUTO","SINGLE") && visiblePages.size<=4) }
}
data class ViewportInsets(val left: Int=0,val top: Int=0,val right: Int=0,val bottom: Int=0) {
    init { require(listOf(left,top,right,bottom).all { it in 0..16384 }) }
}
enum class ReaderCommandResult { APPLIED, NOT_APPLICABLE, UNAVAILABLE }
enum class PreviewPhase { IDLE, RUNNING, DONE, FAILED }
data class TextPreviewState(val phase: PreviewPhase=PreviewPhase.IDLE,val value: TextPreview?=null,val message: String?=null)
