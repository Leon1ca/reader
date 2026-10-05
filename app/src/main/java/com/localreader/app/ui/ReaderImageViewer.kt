package com.localreader.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.localreader.app.core.ImageSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** The original image stays untouched; only this disposable viewer bitmap is sampled. */
internal object ReaderImageDecoder {
    fun decode(source: ImageSource,checkpoint: ()->Unit): Bitmap {
        require(source.bytes in 1..32L*1024*1024 && source.mime!="image/svg+xml") { "无法显示这张图片" }
        checkpoint()
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        source.open().use { BitmapFactory.decodeStream(it,null,bounds) }
        require(bounds.outWidth>0 && bounds.outHeight>0 && bounds.outWidth.toLong()*bounds.outHeight<=25_000_000) { "图片尺寸超过显示预算" }
        var sample=1
        while(((bounds.outWidth+sample-1)/sample).toLong()*((bounds.outHeight+sample-1)/sample)>1_048_576)sample*=2
        checkpoint()
        val bitmap=source.open().use { BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply { inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888 }) } ?: error("无法显示这张图片")
        try { checkpoint();require(bitmap.allocationByteCount<=4*1024*1024) { "图片解码超过 4 MiB 预算" };return bitmap }
        catch(error: Throwable) { bitmap.recycle();throw error }
    }
}

@Composable internal fun ReaderImageViewer(source: ImageSource,onClose: ()->Unit) {
    var bitmap by remember(source) { mutableStateOf<Bitmap?>(null) }
    var error by remember(source) { mutableStateOf<String?>(null) }
    LaunchedEffect(source) {
        var decoded: Bitmap?=null
        try {
            val coroutine=coroutineContext
            decoded=withContext(Dispatchers.IO) { ReaderImageDecoder.decode(source) { coroutine.ensureActive() }.also { decoded=it } }
            bitmap=decoded;decoded=null
        } catch(cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch(failure: Exception) { error=failure.message ?: "无法显示这张图片" }
        finally { decoded?.recycle() }
    }
    DisposableEffect(source) { onDispose { bitmap?.recycle();bitmap=null } }
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        BackHandler(onBack=onClose)
        Surface(Modifier.fillMaxSize()) { Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(8.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                Text(source.index?.let { "当前文档图片 $it / ${source.count ?: "未知"}" } ?: "图片")
                TextButton(onClick=onClose) { Text("关闭图片") }
            }
            if(error!=null)Text(requireNotNull(error),Modifier.padding(24.dp))
            else if(bitmap==null)CircularProgressIndicator(Modifier.padding(24.dp))
            else AndroidView(factory={ ImagePanView(it).apply { closeAction=onClose } },update={ it.image=bitmap;it.closeAction=onClose },modifier=Modifier.weight(1f).fillMaxWidth())
            Text(source.alt.ifBlank { "双指缩放，拖动平移；双击切换整图与 2×，整图状态下滑关闭。" },Modifier.padding(16.dp))
        } }
    }
}

/** Native overlay owns only a sampled bitmap, never another WebView or book path. */
internal class ImagePanView @JvmOverloads constructor(context: Context,attrs: AttributeSet?=null,defStyleAttr: Int=0): View(context,attrs,defStyleAttr) {
    var image: Bitmap?=null;set(value) { if(field!==value) { field=value;scale=1f;panX=0f;panY=0f;invalidate() } }
    var closeAction: ()->Unit={}
    private var scale=1f
    private var panX=0f
    private var panY=0f
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val imageBounds=RectF()
    private val scaler=ScaleGestureDetector(context,object: ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean { scale=(scale*detector.scaleFactor).coerceIn(1f,5f);clamp();invalidate();return true }
    })
    private val gestures=GestureDetector(context,object: GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent)=true
        override fun onDoubleTap(event: MotionEvent): Boolean { scale=if(scale==1f)2f else 1f;clamp();invalidate();return true }
        override fun onScroll(first: MotionEvent?,second: MotionEvent,distanceX: Float,distanceY: Float): Boolean {
            if(!scaler.isInProgress && scale>1f) { panX-=distanceX;panY-=distanceY;clamp();invalidate() };return true
        }
        override fun onFling(first: MotionEvent?,second: MotionEvent,velocityX: Float,velocityY: Float): Boolean {
            if(scale==1f && first!=null && second.y-first.y>120*resources.displayMetrics.density && kotlin.math.abs(second.x-first.x)<kotlin.math.abs(second.y-first.y))closeAction()
            return true
        }
    })
    init { isClickable=true;contentDescription="图片，双指缩放，双击切换整图与两倍" }
    private fun fit(): Float { val value=image ?: return 1f;return minOf(width.toFloat()/value.width,height.toFloat()/value.height) }
    private fun clamp() { val value=image ?: return;val fit=fit();val maxX=maxOf(0f,(value.width*fit*scale-width)/2);val maxY=maxOf(0f,(value.height*fit*scale-height)/2);panX=panX.coerceIn(-maxX,maxX);panY=panY.coerceIn(-maxY,maxY) }
    override fun onSizeChanged(width: Int,height: Int,oldWidth: Int,oldHeight: Int) { super.onSizeChanged(width,height,oldWidth,oldHeight);clamp() }
    override fun onDraw(canvas: Canvas) { super.onDraw(canvas);val value=image?.takeUnless { it.isRecycled } ?: return;val factor=fit()*scale;val w=value.width*factor;val h=value.height*factor;imageBounds.set((width-w)/2+panX,(height-h)/2+panY,(width+w)/2+panX,(height+h)/2+panY);canvas.drawBitmap(value,null,imageBounds,paint) }
    override fun onTouchEvent(event: MotionEvent): Boolean { scaler.onTouchEvent(event);gestures.onTouchEvent(event);if(event.actionMasked==MotionEvent.ACTION_UP)performClick();return true }
    override fun performClick(): Boolean { super.performClick();return true }
    override fun onDetachedFromWindow() { image=null;super.onDetachedFromWindow() }
}
