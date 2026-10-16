package com.localreader.app.ui.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * In-window members of the bottom-capsule family (Xiaomi system apps / FloatingTabBar): the tab capsule's height
 * ([UiSize.navigationHeight], 54dp at the default font scale), fully round ends, card fill, a hairline and the capsule shadow.
 * The glass of the floating capsules samples the window under a separate popup; a control inside the window cannot sample
 * itself, so these are drawn on the opaque card instead.
 */
@Composable
fun capsuleHeight(): Dp = UiSize.navigationHeight(LocalDensity.current.fontScale)

private val capsuleShape = RoundedCornerShape(percent = 50)

@Composable
fun Modifier.capsuleSurface(): Modifier {
    val colors = LocalUiPalette.current
    return capsuleElevation(capsuleShape).background(colors.card, capsuleShape).border(UiSize.border, colors.text.copy(alpha = 0.06f), capsuleShape)
}

/**
 * The one capsule button style: 取消 beside a search capsule, 选择 / 全选 / 取消全选 and the round icon-only 退出选择 in the
 * shelf header. Height is the capsule's; width wraps the label (never narrower than it is tall), so 取消全选 simply grows.
 * Pressing scales it slightly, as ActionButton does; there is no ripple rectangle.
 */
@Composable
fun CapsuleButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, glyph: UiGlyph? = null,
                  color: Color = if (glyph == null) LocalUiPalette.current.primary else LocalUiPalette.current.controlText) {
    val height = capsuleHeight()
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) UiMotion.pressedScale else 1f, tween(motionDuration(UiMotion.press)), label = "capsule press")
    val tint = color.copy(alpha = if (enabled) 1f else 0.4f)
    Box(modifier.height(height).defaultMinSize(minWidth = height).graphicsLayer { scaleX = scale; scaleY = scale }.capsuleSurface()
        .then(if (glyph != null) Modifier.semantics { contentDescription = label } else Modifier)
        .clickable(enabled = enabled, role = Role.Button, interactionSource = source, indication = null, onClick = onClick)
        .padding(horizontal = if (glyph == null) 18.dp else 0.dp), contentAlignment = Alignment.Center) {
        if (glyph != null) UiIcon(glyph, Modifier.size(20.dp), tint)
        else Text(label, style = LocalUiText.current.row.copy(fontWeight = FontWeight.Medium), color = tint, maxLines = 1)
    }
}

/**
 * Search in the capsule layout: the wide field capsule where the tab capsule sits and a 取消 capsule button where its orb
 * is, 10dp apart, both the capsule's height. Shared by the shelf search page and the in-book search panel. The keyboard's
 * search key calls [onSearch]; [fieldModifier] carries the field's test tag and accessible name.
 */
@Composable
fun SearchCapsuleBar(value: String, onValueChange: (String) -> Unit, placeholder: String, cancelLabel: String, clearLabel: String, onCancel: () -> Unit,
                     modifier: Modifier = Modifier, fieldModifier: Modifier = Modifier, onSearch: (() -> Unit)? = null, autoFocus: Boolean = false) {
    val colors = LocalUiPalette.current; val type = LocalUiText.current
    val height = capsuleHeight()
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    // Opening the page is asking to type: focus once, on entry only, so returning from a result does not pop the keyboard up again.
    if (autoFocus) LaunchedEffect(Unit) { focus.requestFocus(); keyboard?.show() }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.weight(1f).height(height).capsuleSurface()
            // A tap anywhere on the capsule reaches the field; the field's own taps are consumed first, so nothing fires twice.
            .pointerInput(Unit) { detectTapGestures { focus.requestFocus(); keyboard?.show() } }
            .padding(start = 18.dp, end = if (value.isEmpty()) 18.dp else 3.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UiIcon(UiGlyph.SEARCH, Modifier.size(18.dp), colors.secondaryText)
            BasicTextField(value, onValueChange, Modifier.weight(1f).heightIn(min = UiSize.touch).focusRequester(focus).then(fieldModifier), singleLine = true,
                textStyle = type.row.copy(color = colors.text), cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch?.invoke() ?: keyboard?.hide() }),
                decorationBox = { input ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) Text(placeholder, style = type.row, color = colors.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        input()
                    }
                })
            if (value.isNotEmpty()) IconAction(clearLabel, UiGlyph.CLOSE, { onValueChange(""); focus.requestFocus() }, visual = 22.dp, color = colors.secondaryText)
        }
        CapsuleButton(cancelLabel, { keyboard?.hide(); focusManager.clearFocus(); onCancel() })
    }
}
