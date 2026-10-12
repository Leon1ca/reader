package com.localreader.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import com.localreader.app.core.PageTurn

/** What the web adapter calls around a paginated turn; both calls are no-ops when no animation is wanted. */
interface PageTurnHook {
    /** A turn was requested (tap, key or swipe). The current page is captured before the engine moves. */
    fun begin(forward: Boolean)
    /** The engine reported its new position; the new page can be captured and the animation started. */
    fun settle()
}

/**
 * Trigger-based page-turn animation drawn from two bitmap snapshots of the reading surface.
 * The page is captured before the engine moves and again once it reports the new position; the
 * animation then plays between the two bitmaps. Nothing here touches page content or input, so a
 * failed capture simply falls back to the instant turn.
 */
class PageTurnOverlay(context: Context) : View(context) {
    var style = PageTurn.NONE
    var pageColor = Color.WHITE
    private enum class Phase { IDLE, WAITING, PLAYING }
    private var phase = Phase.IDLE
    private var before: Bitmap? = null
    private var after: Bitmap? = null
    private var forward = true
    private var progress = 0f
    private var animator: ValueAnimator? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val shade = Paint()
    private val timeout = Runnable { finish() }
    private val density = context.resources.displayMetrics.density

    init { visibility = GONE;isClickable = false;isFocusable = false;importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun begin(target: View, forwardTurn: Boolean) {
        if (style == PageTurn.NONE) return
        // A second request while one is running snaps the first to its end instead of stacking animations.
        if (phase != Phase.IDLE) { finish();return }
        if (target.width <= 0 || target.height <= 0) return
        before = snapshot(target) ?: return
        forward = forwardTurn;progress = 0f;phase = Phase.WAITING
        visibility = VISIBLE;invalidate()
        postDelayed(timeout, WAIT_MS)
    }

    fun settle(target: View) {
        if (phase != Phase.WAITING) return
        removeCallbacks(timeout)
        // One frame lets the engine paint the new page before it is captured.
        postDelayed({
            if (phase != Phase.WAITING) return@postDelayed
            after = snapshot(target)
            if (after == null) { finish();return@postDelayed }
            phase = Phase.PLAYING
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = if (style == PageTurn.CURL) 380L else 280L
                interpolator = DecelerateInterpolator(1.4f)
                addUpdateListener { progress = it.animatedValue as Float;invalidate() }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    private var cancelled = false
                    override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                    override fun onAnimationEnd(animation: android.animation.Animator) { if (!cancelled) finish() }
                })
                start()
            }
        }, SETTLE_MS)
    }

    /** Drops any running animation immediately; the live page below is always already correct. */
    fun finish() {
        removeCallbacks(timeout)
        animator?.let { it.removeAllUpdateListeners();it.cancel() };animator = null
        phase = Phase.IDLE
        before?.recycle();after?.recycle();before = null;after = null
        visibility = GONE
    }

    private fun snapshot(target: View): Bitmap? = try {
        createBitmap(target.width, target.height).also { target.draw(Canvas(it)) }
    } catch (_: Throwable) { null }

    override fun onDetachedFromWindow() { finish();super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val start = before ?: return
        canvas.drawColor(pageColor)
        val end = after
        if (phase != Phase.PLAYING || end == null) { canvas.drawBitmap(start, 0f, 0f, null);return }
        // Backward turns play the forward animation in reverse with the roles of the pages swapped.
        val out = if (forward) start else end
        val incoming = if (forward) end else start
        val p = if (forward) progress else 1f - progress
        val w = width.toFloat();val h = height.toFloat()
        when (style) {
            PageTurn.COVER -> {
                canvas.drawBitmap(incoming, 0f, 0f, null)
                val x = -p * w
                canvas.drawBitmap(out, x, 0f, paint)
                edgeShadow(canvas, x + w, h, true)
            }
            PageTurn.SLIDE -> {
                canvas.drawBitmap(out, -p * w, 0f, paint)
                canvas.drawBitmap(incoming, w - p * w, 0f, paint)
            }
            PageTurn.CURL -> curl(canvas, out, incoming, w, h, w * (1f - p))
            PageTurn.NONE -> canvas.drawBitmap(end, 0f, 0f, null)
        }
    }

    /** Soft shadow beside a page edge; [rightSide] puts it on the outer side of the edge at [x]. */
    private fun edgeShadow(canvas: Canvas, x: Float, h: Float, rightSide: Boolean) {
        val width = 28f * density
        val left = if (rightSide) x else x - width
        shade.shader = LinearGradient(left, 0f, left + width, 0f,
            if (rightSide) intArrayOf(0x44000000, 0) else intArrayOf(0, 0x44000000), null, Shader.TileMode.CLAMP)
        canvas.drawRect(left, 0f, left + width, h, shade);shade.shader = null
    }

    /** A straight fold travelling from the right edge: the flap is the outgoing page mirrored about the fold. */
    private fun curl(canvas: Canvas, out: Bitmap, incoming: Bitmap, w: Float, h: Float, fold: Float) {
        canvas.drawBitmap(incoming, 0f, 0f, null)
        if (fold >= w) { canvas.drawBitmap(out, 0f, 0f, null);return }
        // Shadow the flap casts on the page that is being revealed.
        val reveal = minOf(w - fold, 40f * density)
        shade.shader = LinearGradient(fold, 0f, fold + reveal, 0f, intArrayOf(0x55000000, 0), null, Shader.TileMode.CLAMP)
        canvas.drawRect(fold, 0f, fold + reveal, h, shade);shade.shader = null
        val edge = maxOf(0f, 2f * fold - w)
        canvas.withClip(0f, 0f, fold, h) {
            drawBitmap(out, 0f, 0f, null)
            if (edge > 0f) edgeShadow(this, edge, h, false)
        }
        if (fold <= 0f) return
        canvas.withClip(edge, 0f, fold, h) {
            translate(2f * fold, 0f);scale(-1f, 1f);drawBitmap(out, 0f, 0f, paint)
        }
        // The back of the sheet: paper tint, darker towards the fold.
        shade.color = (pageColor and 0x00FFFFFF) or (0xA0 shl 24);canvas.drawRect(edge, 0f, fold, h, shade)
        shade.shader = LinearGradient(edge, 0f, fold, 0f, intArrayOf(0x00000000, 0x30000000), null, Shader.TileMode.CLAMP)
        canvas.drawRect(edge, 0f, fold, h, shade);shade.shader = null;shade.color = Color.BLACK
    }

    private companion object { const val WAIT_MS = 600L;const val SETTLE_MS = 70L }
}
