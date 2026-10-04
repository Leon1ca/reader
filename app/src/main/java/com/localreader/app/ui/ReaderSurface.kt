package com.localreader.app.ui

import android.content.Context
import android.widget.FrameLayout
import com.localreader.app.core.ReaderController
import com.localreader.app.core.ReadingSession

/** The FrameLayout is stable across layout updates and renderer process replacement. */
class ReaderSurface(context: Context) : FrameLayout(context) {
    private var controller: ReaderController?=null
    private var adapter: WebReaderAdapter?=null
    private var release: (() -> Unit)?=null
    private var attempt=0
    private var closed=false
    fun bind(controller: ReaderController) {
        check(this.controller==null && !closed)
        this.controller=controller
        rebuild()
    }
    private fun rebuild() {
        release?.invoke();release=null;adapter=null;removeAllViews()
        val owner=checkNotNull(controller)
        var created: WebReaderAdapter?=null
        val next=WebReaderAdapter(context) { event -> created?.let { owner.receive(it,event) } }
        created=next;adapter=next
        addView(next.view,LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.MATCH_PARENT))
        release=owner.attach(next)
    }
    fun present(session: ReadingSession,recoveryAttempt: Int=attempt) {
        if(closed)return
        if(recoveryAttempt!=attempt) { attempt=recoveryAttempt;rebuild() }
        controller?.present(session)
    }
    fun close() { if(!closed) { closed=true;release?.invoke();release=null;adapter=null;controller=null;removeAllViews() } }
}
