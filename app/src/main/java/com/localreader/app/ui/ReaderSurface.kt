package com.localreader.app.ui

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs
import com.localreader.app.core.PageTurn
import com.localreader.app.core.ReaderController
import com.localreader.app.core.ReaderFlow
import com.localreader.app.core.WebContentKind
import com.localreader.app.core.ReadingSession

/** The FrameLayout is stable across layout updates and renderer process replacement. */
class ReaderSurface(context: Context) : FrameLayout(context) {
    private var controller: ReaderController?=null
    private var adapter: WebReaderAdapter?=null
    private var release: (() -> Unit)?=null
    private var attempt=0
    private var closed=false
    private val overlay=PageTurnOverlay(context)
    /** Page-turn animation only applies to paginated reflowable text; fixed layout keeps its own pan and zoom. */
    private var turnAllowed=false
    var pageTurn: PageTurn=PageTurn.NONE
        set(value) { field=value;overlay.style=value;if(value==PageTurn.NONE)overlay.finish() }
    // The web paginator scrolls reflowable pages itself and only reports the result, so with an animated style a
    // horizontal swipe is taken here and becomes an ordinary next/prev request that the overlay can animate.
    private val touchSlop=ViewConfiguration.get(context).scaledTouchSlop
    private val minSwipe=40*context.resources.displayMetrics.density
    private var tracking=false
    private var swiping=false
    private var downX=0f
    private var downY=0f
    private var downTime=0L
    private val swipeTurns get() = animatesNow && pageTurn!=PageTurn.NONE
    /** Reflowable pages only; judged per current page because a mixed EPUB alternates with fixed-layout pages. */
    private val animatesNow get() = turnAllowed && adapter?.onFixedPage != true
    var pageColor: Int
        get() = overlay.pageColor
        set(value) { overlay.pageColor=value }
    fun bind(controller: ReaderController) {
        check(this.controller==null && !closed)
        this.controller=controller
        rebuild()
    }
    private fun rebuild() {
        release?.invoke();release=null;adapter=null;removeAllViews()
        val owner=checkNotNull(controller)
        var created: WebReaderAdapter?=null
        val next=WebReaderAdapter(context,generation=owner.sessionGeneration) { event -> created?.let { owner.receive(it,event) } }
        created=next;adapter=next
        addView(next.view,LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.MATCH_PARENT))
        overlay.finish();addView(overlay,LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.MATCH_PARENT))
        next.turnHook=object: PageTurnHook {
            override fun begin(forward: Boolean) { if(animatesNow)overlay.begin(next.view,forward) }
            override fun settle() { if(turnAllowed)overlay.settle(next.view) }
        }
        release=owner.attach(next)
    }
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if(!swipeTurns) { tracking=false;swiping=false;return false }
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { tracking=true;swiping=false;downX=event.x;downY=event.y;downTime=event.eventTime }
            // A second finger means pinch or selection handles: leave the gesture to the page.
            MotionEvent.ACTION_POINTER_DOWN -> { tracking=false;swiping=false }
            MotionEvent.ACTION_MOVE -> if(tracking && event.pointerCount==1) {
                val dx=event.x-downX;val dy=event.y-downY
                // Held still first means long-press text selection, which must keep working.
                if(abs(dx)>touchSlop*1.5f && abs(dx)>abs(dy)*1.6f && event.eventTime-downTime<android.view.ViewConfiguration.getLongPressTimeout()) {
                    swiping=true;tracking=false;parent?.requestDisallowInterceptTouchEvent(true);return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> tracking=false
        }
        return false
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if(!swiping) return super.onTouchEvent(event)
        when(event.actionMasked) {
            // A second finger is a pinch or selection gesture: the whole gesture loses its right to turn the page.
            MotionEvent.ACTION_POINTER_DOWN -> swiping=false
            MotionEvent.ACTION_UP -> {
                swiping=false
                val dx=event.x-downX
                if(abs(dx)>=minSwipe) {
                    performClick()
                    if(if(adapter?.pageRtl==true)dx>0 else dx<0)controller?.next() else controller?.prev()
                }
            }
            MotionEvent.ACTION_CANCEL -> swiping=false
        }
        return true
    }
    // Required by accessibility lint for views that interpret touch gestures; the swipe is also available as next/prev actions.
    override fun performClick(): Boolean = super.performClick()
    fun present(session: ReadingSession,recoveryAttempt: Int=attempt) {
        if(closed)return
        if(recoveryAttempt!=attempt) { attempt=recoveryAttempt;rebuild() }
        turnAllowed=session.document.kind!=WebContentKind.FIXED && session.snapshot.rules.flow==ReaderFlow.PAGINATED
        if(!turnAllowed)overlay.finish()
        controller?.present(session)
    }
    fun close() { if(!closed) { closed=true;overlay.finish();release?.invoke();release=null;adapter=null;controller=null;removeAllViews() } }
}
