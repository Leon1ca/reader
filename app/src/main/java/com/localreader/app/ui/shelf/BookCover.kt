package com.localreader.app.ui.shelf

import com.localreader.app.R
import com.localreader.app.ui.UiStrings.text as uiText

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localreader.app.core.ContentSummary
import com.localreader.app.ui.design.LocalUiText
import com.localreader.app.ui.design.UiSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files

/** Covers only, never full-resolution page images. Caller passes the existing library root. */
class CoverLoader(private val libraryRoot: File) {
    /** Two decodes at a time: one slow file no longer holds up every other cover on screen. */
    private val decode=Semaphore(2)
    /** Book (id + cover path) to the decoded file's cache key, so a cover seen before is found without touching the disk. */
    private val bookKeys=java.util.concurrent.ConcurrentHashMap<String,String>()
    private fun bookKey(book: ContentSummary)="${book.id}\u0000${book.coverPath}"
    /** The cached cover for [book], or null; no I/O, safe on the main thread. */
    fun peek(book: ContentSummary): Bitmap?=bookKeys[bookKey(book)]?.let { cache.get(it) }
    /** Decodes covers that are about to scroll into view; already cached ones cost nothing. */
    suspend fun prefetch(books: List<ContentSummary>) { books.forEach { if(peek(it)==null)load(it) } }
    private data class Presented(val id: String,val ready: Boolean,val bounds: Rect)
    private val presented=mutableMapOf<Any,Presented>()
    private val presentationChanged=MutableStateFlow(0L)
    val presentationRevision=presentationChanged.asStateFlow()
    @Synchronized internal fun present(key: Any,id: String,ready: Boolean,bounds: Rect) {
        val next=Presented(id,ready,bounds)
        if(presented[key]!=next) {presented[key]=next;presentationChanged.value++}
    }
    @Synchronized internal fun removePresentation(key: Any) {if(presented.remove(key)!=null)presentationChanged.value++}
    @Synchronized internal fun visibleReadiness(width: Int,height: Int): Pair<Int,Boolean> {
        val visible=presented.values.filter {it.bounds.width>0 && it.bounds.height>0 && it.bounds.left<width && it.bounds.right>0 && it.bounds.top<height && it.bounds.bottom>0}
        return visible.map {it.id}.distinct().size to visible.all {it.ready}
    }
    private val changed=MutableStateFlow(0L)
    val revision=changed.asStateFlow()
    private val cache=object: LruCache<String,Bitmap>(16*1024*1024) {
        override fun sizeOf(key: String,value: Bitmap)=value.allocationByteCount
        // Evicted images may still be displayed by Compose. GC, not recycle(), owns them.
    }
    internal val cachedBytes: Int get()=cache.size()
    suspend fun load(book: ContentSummary): Bitmap? = withContext(Dispatchers.IO) {
        try {
        val relative=book.coverPath ?: return@withContext null
        if(relative.startsWith('/') || relative.split('/').any { it==".." || it=="." })return@withContext null
        val canonicalLibrary=libraryRoot.canonicalFile
        val root=File(canonicalLibrary,book.id).canonicalFile
        if(!root.path.startsWith(canonicalLibrary.path+File.separator))return@withContext null
        val file=File(root,relative)
        if(Files.isSymbolicLink(file.toPath()) || !file.canonicalPath.startsWith(root.path+File.separator) || !file.isFile)return@withContext null
        val key="${file.canonicalPath}:${file.length()}:${file.lastModified()}"
        cache.get(key)?.let { bookKeys[bookKey(book)]=key;return@withContext it }
        decode.withPermit {
            ensureActive()
            cache.get(key)?.let { bookKeys[bookKey(book)]=key;return@withPermit it }
            val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
            BitmapFactory.decodeFile(file.path,bounds)
            if(bounds.outWidth<=0 || bounds.outHeight<=0)return@withPermit null
            val sample=CoverSampling.sampleSize(bounds.outWidth,bounds.outHeight)
            val image=BitmapFactory.decodeFile(file.path,BitmapFactory.Options().apply { inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888 }) ?: return@withPermit null
            try { ensureActive() } catch(error: Throwable) { image.recycle();throw error }
            if(maxOf(image.width,image.height)>360 || image.width.toLong()*image.height>131072L || image.allocationByteCount>512*1024) { image.recycle();return@withPermit null }
            cache.put(key,image);bookKeys[bookKey(book)]=key;changed.value=changed.value+1;image
        }
        } catch(cancel: CancellationException) { throw cancel }
        catch(_: java.io.IOException) { null }
        catch(_: SecurityException) { null }
    }
}

@Composable
fun BookCover(book: ContentSummary,loader: CoverLoader,modifier: Modifier=Modifier) {
    val presentation=remember {Any()}
    // A cover already in memory is shown in the first frame; only a fresh decode fades in over the generated cover.
    val cached=remember(book.id,book.coverPath,loader) { loader.peek(book) }
    var ready by remember(book.id,book.coverPath,loader) {mutableStateOf(cached!=null)}
    var bounds by remember {mutableStateOf(Rect.Zero)}
    val image by produceState(cached,book.id,book.coverPath,loader) { if(cached==null) { value=loader.load(book);ready=true } }
    val reveal by androidx.compose.animation.core.animateFloatAsState(if(image!=null)1f else 0f,
        if(cached!=null) androidx.compose.animation.core.snap() else androidx.compose.animation.core.tween(com.localreader.app.ui.design.motionDuration(180)),label="cover")
    SideEffect {loader.present(presentation,book.id,ready,bounds)}
    DisposableEffect(loader,presentation) {onDispose {loader.removePresentation(presentation)}}
    val spec=remember(book.title) {CoverSpec.of(book.title)}
    val background=Color(CoverSpec.colors[spec.palette].first)
    val shape=RoundedCornerShape(topStart=2.dp,topEnd=5.dp,bottomEnd=5.dp,bottomStart=2.dp)
    Box(modifier.onGloballyPositioned {bounds=it.boundsInWindow()}.clearAndSetSemantics {}.aspectRatio(UiSize.coverRatio).clip(shape).background(background)
        .border(UiSize.border,Color.Black.copy(alpha=0.08f),shape)
        .drawWithContent {
            drawContent()
            drawRect(Brush.horizontalGradient(0f to Color.Black.copy(alpha=0.22f),0.15f to Color.White.copy(alpha=0.16f),0.5f to Color.Transparent,
                startX=0f,endX=14.dp.toPx()))
            drawRect(Brush.linearGradient(listOf(Color.White.copy(alpha=0.12f),Color.Transparent,Color.Black.copy(alpha=0.08f)),Offset.Zero,Offset(size.width,size.height)))
        },contentAlignment=Alignment.TopCenter) {
        val bitmap=image
        if(bitmap==null || reveal<1f) GeneratedCoverTitle(book,spec)
        if(bitmap!=null) Image(bitmap.asImageBitmap(),contentDescription=null,modifier=Modifier.fillMaxSize(),contentScale=ContentScale.Crop,alpha=reveal)
        Text(if(book.epubLayout=="pre-paginated")uiText(R.string.fixed_layout) else book.format.name,
            Modifier.align(Alignment.BottomEnd).padding(6.dp).background(Color.White.copy(alpha=.84f),RoundedCornerShape(3.dp)).padding(horizontal=5.dp,vertical=2.dp),
            style=LocalUiText.current.cover.copy(fontFamily=LocalUiText.current.fonts.sans(FontWeight.Medium),fontSize=10.sp,fontWeight=FontWeight.Medium,letterSpacing=.6.sp),color=Color(0xFF44474D))
    }
}


@Composable private fun GeneratedCoverTitle(book: ContentSummary,spec: CoverSpec) {
    val foreground=Color(CoverSpec.colors[spec.palette].second)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if(spec.innerBorder)Box(Modifier.matchParentSize().padding(8.dp).border(.5.dp,foreground.copy(alpha=.32f),RoundedCornerShape(1.dp)))
        if(spec.vertical) {
            val points=spec.title.codePoints().toArray()
            val fontScale=LocalDensity.current.fontScale
            val fit=((maxHeight-28.dp-(3*(points.size-1)).dp).value/points.size/fontScale).coerceIn(1f,13f).sp
            Column(Modifier.align(Alignment.TopCenter).padding(top=14.dp),verticalArrangement=Arrangement.spacedBy(3.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                points.forEach {point -> Text(String(Character.toChars(point)),
                    style=LocalUiText.current.book.copy(fontSize=fit,lineHeight=fit,fontWeight=FontWeight.Medium),color=foreground) }
            }
        } else Column(Modifier.fillMaxWidth().padding(start=11.dp,end=11.dp,top=14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(spec.title.ifEmpty {uiText(R.string.untitled)},style=LocalUiText.current.book.copy(fontSize=13.sp,lineHeight=18.sp),color=foreground,maxLines=2,overflow=TextOverflow.Ellipsis)
            Box(Modifier.width(18.dp).height(1.dp).background(foreground.copy(alpha=.5f)))
            Text(book.author,style=LocalUiText.current.cover.copy(fontSize=9.5.sp),color=foreground.copy(alpha=.85f),maxLines=2,overflow=TextOverflow.Ellipsis)
        }
    }
}
