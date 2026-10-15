package com.localreader.app.ui.app

import com.localreader.app.R
import com.localreader.app.ui.UiStrings.text as uiText

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localreader.app.core.*
import com.localreader.app.ui.ReaderViewModel
import com.localreader.app.ui.design.*

/** Chips above the shelf: everything, each category, and the system view of books in none. Only shown once a category exists. */
@Composable
internal fun CategoryChips(categories: List<Category>,selected: Long,total: Int,select: (Long) -> Unit,modifier: Modifier = Modifier) {
    if(categories.isEmpty()) return
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("category-chips"),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        ActionButton(uiText(R.string.category_all,total),{ select(ShelfCategories.ALL) },style=ActionStyle.FILTER,selected=selected==ShelfCategories.ALL)
        categories.forEach { category -> ActionButton(category.name,{ select(category.id) },style=ActionStyle.FILTER,selected=selected==category.id) }
        ActionButton(uiText(R.string.category_uncategorized),{ select(ShelfCategories.NONE) },style=ActionStyle.FILTER,selected=selected==ShelfCategories.NONE)
    }
}

/** Design board `CategoryPicker`: staged choices for the selected books, applied by 完成 and dropped by 取消. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CategoryPickerSheet(books: List<ContentSummary>,model: ReaderViewModel,close: () -> Unit,manage: () -> Unit) {
    val categories by model.categories.collectAsStateWithLifecycle()
    val colors=LocalUiPalette.current;val type=LocalUiText.current
    val ids=books.map { it.id }.toSet()
    var creating by remember { mutableStateOf(false) }
    // true = every selected book goes in, false = none stays; absent = leave as it is.
    val pending=remember { mutableStateMapOf<Long,Boolean>() }
    ModalBottomSheet(onDismissRequest=close,containerColor=colors.page,shape=RoundedCornerShape(topStart=UiSize.sheetRadius,topEnd=UiSize.sheetRadius),
        dragHandle={ Box(Modifier.padding(top=10.dp,bottom=4.dp).size(36.dp,4.dp).background(Color(0xFFD6D6D1),RoundedCornerShape(2.dp))) }) {
        Column(Modifier.fillMaxWidth().padding(horizontal=20.dp).padding(bottom=30.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
                Box(Modifier.heightIn(min=UiSize.touch).widthIn(min=UiSize.touch).clickable(role=Role.Button,onClick=close),contentAlignment=Alignment.CenterStart) { Text(uiText(R.string.cancel),style=type.value.copy(fontSize=15.sp),color=colors.secondaryText) }
                Text(uiText(R.string.category_picker_title,ids.size),style=type.panel,color=colors.text)
                Box(Modifier.heightIn(min=UiSize.touch).widthIn(min=UiSize.touch).clickable(role=Role.Button) {
                    pending.forEach { (id,member) -> model.setCategoryMembership(ids,id,member) };close()
                },contentAlignment=Alignment.CenterEnd) { Text(uiText(R.string.category_done),style=type.value.copy(fontSize=15.sp,fontWeight=androidx.compose.ui.text.font.FontWeight.Medium),color=colors.primary) }
            }
            Column(Modifier.fillMaxWidth().background(colors.card,RoundedCornerShape(UiSize.groupRadius)).padding(horizontal=16.dp).heightIn(max=360.dp).verticalScroll(rememberScrollState()).testTag("category-picker")) {
                categories.forEachIndexed { index,category ->
                    val inCount=books.count { category.id in it.categories }
                    val shown=pending[category.id]?.let { if(it)Tri.ALL else Tri.NONE } ?: when { inCount==ids.size && ids.isNotEmpty() -> Tri.ALL;inCount>0 -> Tri.SOME;else -> Tri.NONE }
                    Row(Modifier.fillMaxWidth().heightIn(min=UiSize.row).clickable(role=Role.Checkbox) { pending[category.id]=shown!=Tri.ALL }
                        .semantics { contentDescription=category.name },verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        CategoryCheck(shown)
                        Text(category.name,Modifier.weight(1f),style=type.row.copy(fontSize=15.sp),color=colors.text)
                        Text(if(pending.containsKey(category.id))"" else if(inCount>0)uiText(R.string.category_in_some,inCount) else uiText(R.string.category_book_count,category.count),style=type.explanation,color=colors.secondaryText)
                    }
                    if(index<categories.size-1)Box(Modifier.fillMaxWidth().height(UiSize.border).background(colors.input))
                }
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                Box(Modifier.heightIn(min=UiSize.touch).clickable(role=Role.Button) { creating=true },contentAlignment=Alignment.CenterStart) { Text(uiText(R.string.category_new),style=type.value.copy(fontSize=15.sp),color=colors.primary) }
                Box(Modifier.heightIn(min=UiSize.touch).clickable(role=Role.Button) { close();manage() },contentAlignment=Alignment.CenterEnd) { Text(uiText(R.string.category_manage)+" ›",style=type.value,color=colors.secondaryText) }
            }
        }
    }
    if(creating)CategoryNameDialog(uiText(R.string.category_create_title),"",categories,null,{ creating=false }) { name -> model.createCategory(name) { id -> pending[id]=true };creating=false }
}

private enum class Tri { ALL, SOME, NONE }

/** 22dp rounded square: filled with a tick (all), filled with a dash (some) or a hairline outline (none). */
@Composable private fun CategoryCheck(state: Tri) {
    val colors=LocalUiPalette.current
    val shape=RoundedCornerShape(6.dp)
    if(state==Tri.NONE) Box(Modifier.size(22.dp).border(1.5.dp,Color(0xFFC6C7CB),shape))
    else Box(Modifier.size(22.dp).background(colors.primary,shape),contentAlignment=Alignment.Center) {
        if(state==Tri.ALL)UiIcon(UiGlyph.CHECK,Modifier.size(22.dp),Color.White) else Box(Modifier.size(10.dp,2.dp).background(Color.White,RoundedCornerShape(1.dp)))
    }
}

@Composable
internal fun CategoryNameDialog(title: String,initial: String,existing: List<Category>,renaming: Long?,dismiss: () -> Unit,confirm: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    val colors=LocalUiPalette.current;val type=LocalUiText.current
    val problem=remember(value,existing) {
        runCatching { CategoryNames.clean(value) }.fold({ name -> if(existing.any { it.id!=renaming && it.name.equals(name,ignoreCase=true) })uiText(R.string.category_duplicate) else null },{ error -> if(value.isBlank())null else error.message })
    }
    val focus=remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val label=uiText(R.string.category_name)
    AlertDialog(onDismissRequest=dismiss,title={ Text(title) },text={
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth().heightIn(min=UiSize.touch).background(colors.text.copy(alpha=0.05f),RoundedCornerShape(12.dp)).padding(horizontal=12.dp),contentAlignment=Alignment.CenterStart) {
                BasicTextField(value,{ value=it.take(CategoryNames.MAX+10) },Modifier.fillMaxWidth().focusRequester(focus).testTag("category-name").semantics { contentDescription=label },singleLine=true,
                    textStyle=type.value.copy(color=colors.text),cursorBrush=SolidColor(colors.primary),decorationBox={ input ->
                        Box { if(value.isEmpty())Text(uiText(R.string.category_name_hint),style=type.value,color=colors.secondaryText);input() }
                    })
            }
            problem?.let { Text(it,style=type.explanation,color=colors.danger) }
        }
    },confirmButton={ ActionButton(uiText(R.string.done),{ confirm(value) },enabled=problem==null && value.isNotBlank()) },
        dismissButton={ ActionButton(uiText(R.string.cancel),dismiss,style=ActionStyle.SECONDARY) })
}

/** Design board `CategoryManage`: one overflow menu per row (rename, move, delete); the system view "uncategorized" is read-only. */
@Composable
internal fun CategoryManageScreen(model: ReaderViewModel,books: List<ContentSummary>,back: () -> Unit) {
    val categories by model.categories.collectAsStateWithLifecycle()
    val colors=LocalUiPalette.current;val type=LocalUiText.current
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Category?>(null) }
    var deleting by remember { mutableStateOf<Category?>(null) }
    val uncategorized=remember(books) { books.count { it.categories.isEmpty() } }
    LazyColumn(Modifier.fillMaxSize().testTag("category-manage"),contentPadding=PaddingValues(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { ScreenHeader(uiText(R.string.category_manage),back) }
        if(categories.isNotEmpty())item { Column(Modifier.padding(horizontal=20.dp).fillMaxWidth().background(colors.card,RoundedCornerShape(UiSize.groupRadius)).padding(horizontal=16.dp)) {
            categories.forEachIndexed { index,category ->
                var menu by remember(category.id) { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().heightIn(min=UiSize.row),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    UiIcon(UiGlyph.GRIP,Modifier.size(18.dp),colors.secondaryText)
                    Text(category.name,Modifier.weight(1f),style=type.row.copy(fontSize=15.sp),color=colors.text)
                    Text(uiText(R.string.category_book_count,category.count),style=type.explanation,color=colors.secondaryText)
                    Box {
                        IconAction(uiText(R.string.category_row_actions,category.name),UiGlyph.MORE,{ menu=true })
                        DropdownMenu(menu,{ menu=false }) {
                            DropdownMenuItem(text={ Text(uiText(R.string.category_rename_title)) },onClick={ menu=false;renaming=category })
                            DropdownMenuItem(text={ Text(uiText(R.string.category_move_up,category.name)) },enabled=index>0,onClick={ menu=false;model.reorderCategories(categories.map { it.id }.toMutableList().apply { add(index-1,removeAt(index)) }) })
                            DropdownMenuItem(text={ Text(uiText(R.string.category_move_down,category.name)) },enabled=index<categories.size-1,onClick={ menu=false;model.reorderCategories(categories.map { it.id }.toMutableList().apply { add(index+1,removeAt(index)) }) })
                            DropdownMenuItem(text={ Text(uiText(R.string.delete),color=colors.danger) },onClick={ menu=false;deleting=category })
                        }
                    }
                }
                if(index<categories.size-1)Box(Modifier.fillMaxWidth().height(UiSize.border).background(colors.input))
            }
        } }
        item { Row(Modifier.padding(horizontal=20.dp).fillMaxWidth().heightIn(min=UiSize.row).background(colors.card,RoundedCornerShape(UiSize.groupRadius)).padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
            Text(uiText(R.string.category_uncategorized),style=type.row.copy(fontSize=15.sp),color=colors.secondaryText)
            Text(uiText(R.string.category_system_view,uncategorized),style=type.explanation,color=colors.secondaryText)
        } }
        item { Box(Modifier.padding(horizontal=20.dp).fillMaxWidth().height(UiSize.row).background(colors.card,RoundedCornerShape(UiSize.groupRadius)).clickable(role=Role.Button) { creating=true },contentAlignment=Alignment.Center) {
            Text(uiText(R.string.category_new),style=type.row.copy(fontSize=15.sp),color=colors.primary)
        } }
        item { Text(uiText(R.string.category_manage_hint),Modifier.padding(horizontal=24.dp),style=type.explanation,color=colors.secondaryText) }
    }
    if(creating)CategoryNameDialog(uiText(R.string.category_create_title),"",categories,null,{ creating=false }) { name -> model.createCategory(name);creating=false }
    renaming?.let { category -> CategoryNameDialog(uiText(R.string.category_rename_title),category.name,categories,category.id,{ renaming=null }) { name -> model.renameCategory(category.id,name);renaming=null } }
    deleting?.let { category -> ConfirmSheet(uiText(R.string.category_delete_title,category.name),uiText(R.string.category_delete_body,category.count),uiText(R.string.delete),{ model.deleteCategory(category.id);deleting=null },{ deleting=null },uiText(R.string.cancel)) }
}

