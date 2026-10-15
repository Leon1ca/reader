package com.localreader.app.ui.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember

private val NightPalette = UiPalette(primary=Color(0xFF9CBFFF),primaryContainer=Color(0xFF253751),text=Color(0xFFB9B2A6),controlText=Color(0xFFC9C2B6),
    secondaryText=Color(0xFF8E877B),page=Color(0xFF1A1917),card=Color(0xFF272624),input=Color(0xFF33322F),neutral=Color(0xFF373632),
    success=Color(0xFF85C9A5),warning=Color(0xFFF1C285),danger=Color(0xFFFFB4A8),warningContainer=Color(0xFF3A2E1C),dangerContainer=Color(0xFF422724),
    chromeText=Color(0xFF8E877B),sheet=Color(0xFF272624),sheetHandle=Color(0xFF4A4844),outline=Color(0xFF4A4844),onPrimary=Color(0xFF1A1917),
    successContainer=Color(0xFF1F3328),track=Color(0xFF45433F))

/** Fonts are prepared on IO by AppRoot; this scope owns application UI only. */
@Composable
fun UiDesignTheme(fonts: UiFontSet,dark: Boolean=false,reader: Boolean=false,content: @Composable ()->Unit) {
    val palette=remember(dark,reader) { if(dark) NightPalette else UiPalette().let { if(reader)it.copy(page=it.readingPaper,card=it.readingPanel,input=it.readingInput,text=it.readingText,
        controlText=it.readingHeader,secondaryText=it.readingSecondary,sheet=it.readingPanel,sheetHandle=Color(0xFFDDD4C4),track=Color(0xFFE2D9C8),outline=Color(0xFFDDD4C4)) else it } }
    val text=remember(fonts) { UiTextStyles(fonts) }
    val scheme=remember(palette,dark) { val base=if(dark) darkColorScheme() else lightColorScheme();base.copy(
        primary=palette.primary,onPrimary=palette.onPrimary,primaryContainer=palette.primaryContainer,onPrimaryContainer=palette.primary,
        secondary=palette.controlText,onSecondary=palette.card,secondaryContainer=palette.neutral,onSecondaryContainer=palette.text,
        tertiary=palette.primary,onTertiary=palette.onPrimary,tertiaryContainer=palette.primaryContainer,onTertiaryContainer=palette.primary,
        background=palette.page,onBackground=palette.text,surface=palette.card,onSurface=palette.text,
        surfaceVariant=palette.input,onSurfaceVariant=palette.secondaryText,
        // Material3 paints dialogs, menus, sheets and date pickers from the surfaceContainer roles and tints them
        // with surfaceTint; left unmapped they show the baseline lavender. Every container is our card.
        surfaceTint=Color.Transparent,surfaceBright=palette.card,surfaceDim=palette.input,
        surfaceContainerLowest=palette.card,surfaceContainerLow=palette.card,surfaceContainer=palette.card,
        surfaceContainerHigh=palette.card,surfaceContainerHighest=palette.card,
        // Snackbar: dark pill with a light action in light mode, and the reverse at night.
        inverseSurface=palette.text,inverseOnSurface=palette.card,inversePrimary=if(dark) Color(0xFF1F5FCC) else Color(0xFF9CBFFF),
        error=palette.danger,onError=palette.onPrimary,errorContainer=palette.dangerContainer,onErrorContainer=palette.danger,
        outline=palette.outline,outlineVariant=palette.input,scrim=Color.Black
    ) }
    val typography=remember(text) { Typography(
        displayLarge=text.page,displayMedium=text.page,displaySmall=text.detail,
        headlineLarge=text.page,headlineMedium=text.detail,headlineSmall=text.sheetTitle,
        titleLarge=text.sheetTitle,titleMedium=text.card,titleSmall=text.row,
        bodyLarge=text.row,bodyMedium=text.value,bodySmall=text.auxiliary,
        labelLarge=text.row,labelMedium=text.explanation,labelSmall=text.navigation
    ) }
    CompositionLocalProvider(LocalUiPalette provides palette,LocalUiText provides text) {
        MaterialTheme(colorScheme=scheme,typography=typography,content=content)
    }
}
