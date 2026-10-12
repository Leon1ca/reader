package com.localreader.app.ui.shelf

import java.text.Normalizer

/** UTF-8 FNV-1a of the normalized title, independent of database ids and locale. */
data class CoverSpec(val title: String,val palette: Int,val vertical: Boolean,val innerBorder: Boolean) {
    companion object {
        val colors=listOf(
            0xFF3E5C4E to 0xFFF2EAD8,0xFF8C2F2B to 0xFFF6E6CF,0xFF3F4A5C to 0xFFECE7DD,
            0xFF5C4A3A to 0xFFF0E4D2,0xFF7A3E3A to 0xFFF6E3DA,0xFF5B5148 to 0xFFF0E6D7,
            0xFFE7A93A to 0xFF2A2015,0xFF2F4858 to 0xFFE6EEF2,0xFF6B4E71 to 0xFFF1E6F2,
            0xFF4F6D3A to 0xFFEEF1DC,0xFF8A5A2B to 0xFFF7E9D2,0xFF2E5E63 to 0xFFE2F0EE)
        fun of(title: String): CoverSpec {
            val normalized=Normalizer.normalize(title,Normalizer.Form.NFC)
            var seed=0x811c9dc5L
            normalized.toByteArray(Charsets.UTF_8).forEach { seed=((seed xor (it.toLong() and 255))*0x01000193L) and 0xffffffffL }
            val points=normalized.codePoints().toArray()
            val han=points.isNotEmpty() && points.all { Character.UnicodeScript.of(it)==Character.UnicodeScript.HAN }
            return CoverSpec(normalized,(seed%12).toInt(),han && points.size<=8,han && points.size<=6)
        }
    }
}
