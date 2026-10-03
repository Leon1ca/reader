package com.localreader.app.core

/** Engine-neutral specification. The current profile is provisional and has NOT passed Readest parity. */
data class TypographyProfile(val id: String, val bodyFontFamily: String, val lineHeight: Float,
                             val horizontalMarginDp: Int, val paragraphSpacingDp: Int)

object TypographyProfiles {
    val temporaryNativeTxt = TypographyProfile("temporary-native-system", "system", 1.7f, 20, 12)
}
