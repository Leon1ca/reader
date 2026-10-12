package com.localreader.app.ui.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember

/** Fonts are prepared on IO by AppRoot; this scope owns application UI only. */
@Composable
fun UiDesignTheme(fonts: UiFontSet,dark: Boolean=false,reader: Boolean=false,content: @Composable ()->Unit) {
    val palette=remember(dark,reader) { if(dark) UiPalette(primary=Color(0xFF9CBFFF),primaryContainer=Color(0xFF253751),text=Color(0xFFB9B2A6),controlText=Color(0xFFC9C2B6),secondaryText=Color(0xFF8E877B),page=Color(0xFF1A1917),card=Color(0xFF272624),input=Color(0xFF33322F),neutral=Color(0xFF373632),success=Color(0xFF85C9A5),warning=Color(0xFFF1C285),danger=Color(0xFFFFB4A8),warningContainer=Color(0xFF3A2E1C),dangerContainer=Color(0xFF422724)) else UiPalette().let { if(reader)it.copy(page=it.readingPaper,card=it.readingPanel,input=it.readingInput,text=it.readingText,controlText=it.readingHeader,secondaryText=it.readingSecondary) else it } }
    val text=remember(fonts) { UiTextStyles(fonts) }
    val scheme=remember(palette,dark) { val base=if(dark) darkColorScheme() else lightColorScheme();base.copy(
        primary=palette.primary,onPrimary=palette.card,primaryContainer=palette.primaryContainer,onPrimaryContainer=palette.primary,
        secondary=palette.controlText,onSecondary=palette.card,secondaryContainer=palette.neutral,onSecondaryContainer=palette.text,
        background=palette.page,onBackground=palette.text,surface=palette.card,onSurface=palette.text,
        surfaceVariant=palette.input,onSurfaceVariant=palette.secondaryText,error=palette.danger,onError=palette.card,
        errorContainer=palette.dangerContainer,onErrorContainer=palette.danger,outline=palette.secondaryText.copy(alpha=0.35f)
    ) }
    val typography=remember(text) { Typography(
        displayLarge=text.page,displayMedium=text.page,displaySmall=text.detail,
        headlineLarge=text.page,headlineMedium=text.detail,headlineSmall=text.section,
        titleLarge=text.panel,titleMedium=text.card,titleSmall=text.row,
        bodyLarge=text.row,bodyMedium=text.value,bodySmall=text.auxiliary,
        labelLarge=text.value,labelMedium=text.explanation,labelSmall=text.navigation
    ) }
    CompositionLocalProvider(LocalUiPalette provides palette,LocalUiText provides text) {
        MaterialTheme(colorScheme=scheme,typography=typography,content=content)
    }
}
