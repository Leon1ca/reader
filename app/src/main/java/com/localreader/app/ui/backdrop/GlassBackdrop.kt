package com.localreader.app.ui.backdrop

import android.app.Activity
import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import java.lang.ref.WeakReference
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.core.graphics.withTranslation
import androidx.core.graphics.withClip
import androidx.annotation.RequiresApi
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Native-window source and popup foreground are deliberately separate surfaces. */
@Composable
fun GlassBackdrop(activity: Activity,tint: Color,key: Any,modifier: Modifier=Modifier,splitFraction: Float?=null,dirtyKey: Any=key,saturation: Float=1.6f) {
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val view=remember(activity) { GlassBackdropView(activity,activity) }
    DisposableEffect(view,lifecycle) {
        fun update() { view.setForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
        val observer=LifecycleEventObserver { _,_ -> update() };lifecycle.addObserver(observer);update()
        onDispose { lifecycle.removeObserver(observer);view.close() }
    }
    LaunchedEffect(view,dirtyKey) { view.markDirty() }
    AndroidView(factory={ view },modifier=modifier,update={ it.configure(tint.toArgb(),splitFraction,key,saturation) })
}

private object CaptureCopies { val lease=CopyLease() }
internal fun interface WindowPixelCopy {
    fun request(window: Window,rect: Rect,bitmap: Bitmap,callback: (Int)->Unit,handler: Handler)
}
private object PlatformPixelCopy: WindowPixelCopy {
    override fun request(window: Window,rect: Rect,bitmap: Bitmap,callback: (Int)->Unit,handler: Handler) {
        PixelCopy.request(window,rect,bitmap,{callback(it)},handler)
    }
}
private class CopyLease {
    var inFlight=false;private set
    var bytes=0L;private set
    var lastStart=Long.MIN_VALUE/2;private set
    private val waiting=mutableListOf<WeakReference<GlassBackdropView>>()
    fun forget(view: GlassBackdropView) { waiting.removeAll {it.get()==null || it.get()===view} }
    fun acquire(view: GlassBackdropView,size: Long): Boolean {
        if(inFlight || bytes+size>4L*1024*1024) {
            if(waiting.none { it.get()===view })waiting+=WeakReference(view)
            return false
        }
        inFlight=true;bytes+=size;lastStart=SystemClock.uptimeMillis();return true
    }
    fun front(delta: Long) { bytes+=delta;check(bytes>=0) }
    fun finish(size: Long) {
        bytes-=size;inFlight=false
        val wake=waiting.toList();waiting.clear();wake.forEach { it.get()?.markDirty() }
    }
}
private interface TileEffect { fun set(bitmap: Bitmap,radius: Float,saturation: Float);fun draw(canvas: Canvas);fun clear() }

/** ≤1 copy in flight + one front, ≤4MiB; static content has no repeating frame observer. */
internal class GlassBackdropView(context: Context,private val activity: Activity?,private val copy: WindowPixelCopy=PlatformPixelCopy): View(context) {
    /** Layout tools can construct the tint-only view; capture requires an explicit owner. */
    constructor(context: Context): this(context,null)
    private val handler=Handler(Looper.getMainLooper())
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val shapes=Path()
    private val lease=CaptureCopies.lease
    private var front: Bitmap?=null
    private var sourceRect=Rect()
    private var targetRect=Rect()
    private var generation=0L
    private var closed=false
    private var foreground=false
    private var copying=false
    private var dirty=false
    private var failures=0
    private var saturation=1.6f
    private var tint=android.graphics.Color.WHITE
    private var split: Float?=null
    private var key: Any?=null
    private var fallback: String?="waiting"
    private var drawListener: ViewTreeObserver.OnDrawListener?=null
    private var effect: TileEffect?=null
    private val capture=Runnable { requestCapture() }
    private fun clearFront() { front?.let { lease.front(-it.allocationByteCount.toLong()) };front=null
        try {effect?.clear()} catch(_: RuntimeException) {effect=null} catch(_: OutOfMemoryError) {effect=null}
    }
    fun configure(color: Int,splitFraction: Float?,contentKey: Any,saturation: Float=1.6f) {
        require(saturation.isFinite() && saturation in 1f..2f)
        val changed=this.saturation!=saturation
        this.saturation=saturation
        tint=color;split=splitFraction
        if(key!=contentKey || changed) { key=contentKey;generation++;clearFront();failures=0;fallback="waiting";markDirty() }
        invalidate()
    }
    fun setForeground(value: Boolean) {
        if(foreground==value)return
        foreground=value
        if(value) { failures=0;markDirty() } else { generation++;handler.removeCallbacks(capture);dirty=false;clearFront();fallback="background";invalidate() }
    }
    fun markDirty() {
        if(closed || !foreground)return
        dirty=true
        handler.removeCallbacks(capture)
        handler.postDelayed(capture,max(0L,250L-(SystemClock.uptimeMillis()-lease.lastStart)))
    }
    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) { super.onSizeChanged(w,h,oldw,oldh);generation++;clearFront();markDirty() }
    override fun onAttachedToWindow() { super.onAttachedToWindow();afterSourceDraw() }
    private fun afterSourceDraw() {
        if(drawListener!=null || closed)return
        val decor=activity?.window?.decorView ?: return
        val observer=decor.viewTreeObserver
        val listener=ViewTreeObserver.OnDrawListener { handler.post {
            drawListener?.let { if(observer.isAlive)observer.removeOnDrawListener(it) };drawListener=null;markDirty()
        } }
        drawListener=listener;observer.addOnDrawListener(listener);decor.invalidate()
    }
    private fun requestCapture() {
        if(closed || !foreground || !dirty || copying || failures>=2 || width<=0 || height<=0)return
        if(Build.VERSION.SDK_INT<31) { fallback="api";dirty=false;invalidate();return }
        val owner=activity ?: run {fallback="owner";dirty=false;invalidate();return}
        if(!isHardwareAccelerated || !isAttachedToWindow || !owner.window.decorView.isAttachedToWindow) { fallback="surface";dirty=false;invalidate();return }
        if(SystemClock.uptimeMillis()-lease.lastStart<250) { markDirty();return }
        val decor=owner.window.decorView
        val sourceLocation=IntArray(2);decor.getLocationOnScreen(sourceLocation)
        val location=IntArray(2);getLocationOnScreen(location)
        val target=Rect(location[0]-sourceLocation[0],location[1]-sourceLocation[1],location[0]-sourceLocation[0]+width,location[1]-sourceLocation[1]+height)
        val halo=ceil(72f*resources.displayMetrics.density).toInt()
        val rect=Rect(target).apply { inset(-halo,-halo) }
        if(!rect.intersect(0,0,decor.width,decor.height) || rect.isEmpty) { fallback="bounds";dirty=false;invalidate();return }
        val ratio=min(1.0,min(1024.0/max(rect.width(),rect.height()),kotlin.math.sqrt(524288.0/(rect.width().toDouble()*rect.height()))))
        val reservation=2L*1024*1024
        if(!lease.acquire(this,reservation)) { fallback="busy";invalidate();return }
        val bitmap=try { createBitmap(max(1,(rect.width()*ratio).toInt()),max(1,(rect.height()*ratio).toInt())) }
        catch(_: OutOfMemoryError) { lease.finish(reservation);fallback="memory";failures=2;dirty=false;invalidate();return }
        if(bitmap.allocationByteCount.toLong()>reservation) { lease.finish(reservation);bitmap.recycle();fallback="budget";dirty=false;invalidate();return }
        // Anything the copy does not write (edges outside the drawn window, system-bar areas) must read as the tint,
        // not as transparent black that the blur would smear into the glass.
        bitmap.eraseColor(tint or 0xFF000000.toInt())
        copying=true;dirty=false
        val ownGeneration=generation
        val timeout=Runnable { if(copying && generation==ownGeneration) { generation++;failures++;fallback="timeout";clearFront();invalidate() } }
        handler.postDelayed(timeout,200)
        try { copy.request(owner.window,rect,bitmap,{ result ->
            handler.removeCallbacks(timeout);copying=false;lease.finish(reservation)
            if(closed || !foreground || generation!=ownGeneration) { bitmap.recycle() }
            else if(result==PixelCopy.SUCCESS && implausiblyDark(bitmap)) {
                // A near-black capture under a light glass is a failed read of the window, not real content.
                bitmap.recycle();failures=2;fallback="dark";invalidate()
            }
            else if(result==PixelCopy.SUCCESS) {
                clearFront();front=bitmap;lease.front(bitmap.allocationByteCount.toLong());sourceRect=rect;targetRect=target;failures=0;fallback=null
                if(Build.VERSION.SDK_INT>=31)try {
                    if(effect==null)effect=EffectTile();effect?.set(bitmap,24f*resources.displayMetrics.density*ratio.toFloat(),saturation)
                } catch(_: RuntimeException) { clearFront();effect=null;failures=2;fallback="effect" }
                  catch(_: OutOfMemoryError) { clearFront();effect=null;failures=2;fallback="memory" }
                invalidate()
            } else { bitmap.recycle();failures++;fallback="copy:$result";invalidate();if(result==PixelCopy.ERROR_SOURCE_NO_DATA && failures==1)afterSourceDraw() }
            if(dirty && !closed && foreground && failures<2)markDirty()
        },handler) } catch(_: IllegalArgumentException) { handler.removeCallbacks(timeout);copying=false;lease.finish(reservation);bitmap.recycle();failures++;fallback="surface";invalidate() }
    }
    private fun luminance(color: Int) = (0.2126*android.graphics.Color.red(color)+0.7152*android.graphics.Color.green(color)+0.0722*android.graphics.Color.blue(color))/255.0
    /** Samples a coarse grid; only a bright tint over an almost black capture is rejected, so dark themes are unaffected. */
    private fun implausiblyDark(bitmap: Bitmap): Boolean {
        if(luminance(tint)<0.5)return false
        var total=0.0;var count=0
        for(row in 0 until 8)for(column in 0 until 8) {
            val pixel=bitmap[((column+0.5)*bitmap.width/8).toInt().coerceIn(0,bitmap.width-1),((row+0.5)*bitmap.height/8).toInt().coerceIn(0,bitmap.height-1)]
            total+=luminance(pixel);count++
        }
        return total/count<0.06
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density=resources.displayMetrics.density
        shapes.reset()
        val splitAt=split
        if(splitAt==null)shapes.addRoundRect(0f,0f,width.toFloat(),height.toFloat(),height/2f,height/2f,Path.Direction.CW)
        else { val orb=height.toFloat();val end=width-orb-10f*density;shapes.addRoundRect(0f,0f,end,height.toFloat(),height/2f,height/2f,Path.Direction.CW);shapes.addCircle(width-orb/2f,orb/2f,orb/2f,Path.Direction.CW) }
        canvas.withClip(shapes) {
            val bitmap=front
            if(bitmap!=null && Build.VERSION.SDK_INT>=31 && canvas.isHardwareAccelerated) {
                canvas.withTranslation((sourceRect.left-targetRect.left).toFloat(),(sourceRect.top-targetRect.top).toFloat()) {
                    scale(sourceRect.width().toFloat()/bitmap.width,sourceRect.height().toFloat()/bitmap.height)
                    try { effect?.draw(this) }
                    catch(_: RuntimeException) {clearFront();effect=null;failures=2;fallback="draw"}
                    catch(_: OutOfMemoryError) {clearFront();effect=null;failures=2;fallback="memory"}
                }
            }
            paint.color=tint;paint.alpha=if(bitmap!=null && fallback==null)if(saturation==1.4f)230 else 214 else 240;paint.style=Paint.Style.FILL;canvas.drawPath(shapes,paint)
            paint.color=android.graphics.Color.WHITE;paint.alpha=204;paint.style=Paint.Style.STROKE;paint.strokeWidth=.5f*density;canvas.drawPath(shapes,paint)
        }
    }
    fun close() {
        if(closed)return
        closed=true;generation++;handler.removeCallbacks(capture);lease.forget(this)
        drawListener?.let { val observer=activity?.window?.decorView?.viewTreeObserver;if(observer?.isAlive==true)observer.removeOnDrawListener(it) };drawListener=null
        clearFront();effect=null
        // PixelCopy owns its in-flight bitmap until its callback; do not recycle it here.
    }
    override fun onDetachedFromWindow() { close();super.onDetachedFromWindow() }
}

/** Class isolation prevents old WebViews/devices resolving RenderEffect at startup. */
@RequiresApi(31)
private class EffectTile: TileEffect {
    private val node=RenderNode("reader backdrop")
    override fun set(bitmap: Bitmap,radius: Float,saturation: Float) {
        node.setPosition(0,0,bitmap.width,bitmap.height)
        val canvas=node.beginRecording();try {canvas.drawBitmap(bitmap,0f,0f,null)} finally {node.endRecording()}
        val matrix=ColorMatrix().apply { setSaturation(saturation) }
        node.setRenderEffect(RenderEffect.createChainEffect(RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(matrix)),RenderEffect.createBlurEffect(radius,radius,Shader.TileMode.CLAMP)))
    }
    override fun draw(canvas: Canvas) { canvas.drawRenderNode(node) }
    override fun clear() { node.discardDisplayList() }
}
