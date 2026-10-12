package com.localreader.app.ui

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localreader.app.core.ReaderOperationOutcome
import com.localreader.app.core.ReaderOperationToken
import com.localreader.app.ui.shelf.CoverLoader

/** Data-publication segment. The separate native timestamp links to process/Activity startup traces. */
@Composable
internal fun ShelfPresentationTiming(operation: ReaderOperationToken?,visible: Boolean,count: Int,loader: CoverLoader) {
    val host=LocalView.current
    val coverRevision by loader.presentationRevision.collectAsStateWithLifecycle()
    DisposableEffect(operation,host) {onDispose {operation?.let {AndroidReadingTimings.ledger.finish(it,ReaderOperationOutcome.CANCELED)}}}
    DisposableEffect(operation,visible,host,coverRevision) {
        var completed=false
        if(!visible)operation?.let {AndroidReadingTimings.ledger.finish(it,ReaderOperationOutcome.CANCELED)}
        val root=host.rootView
        val observer=root.viewTreeObserver
        val window=root.windowToken
        val covers=loader.visibleReadiness(root.width,root.height)
        val ready=visible && covers.second && (count==0 || covers.first>0)
        val callback=Runnable {
            root.post {
                if(!completed && operation!=null) {
                    completed=true
                    val current=loader.visibleReadiness(root.width,root.height)
                    val valid=ready && root.isAttachedToWindow && root.windowToken===window && root.hasWindowFocus() && current.second && (count==0 || current.first>0)
                    AndroidReadingTimings.ledger.finish(operation,if(valid)ReaderOperationOutcome.ACCEPTED else ReaderOperationOutcome.CANCELED,measurements=mapOf("visibleCount" to count.toLong(),"visibleCovers" to current.first.toLong(),"coversResolved" to if(current.second)1L else 0L,"nativeWidth" to root.width.toLong(),"nativeHeight" to root.height.toLong(),"frameCommitted" to 1L))
                    if(valid)Log.i("ReaderTiming","shelfReadyFrame elapsedRealtimeNanos=${SystemClock.elapsedRealtimeNanos()} visibleCount=$count visibleCovers=${current.first} coversResolved=true fontsResolved=true")
                }
            }
        }
        val registered=operation!=null && ready && root.isHardwareAccelerated && observer.isAlive
        if(registered) {observer.registerFrameCommitCallback(callback);root.invalidate()}
        onDispose {
            if(registered && observer.isAlive)observer.unregisterFrameCommitCallback(callback)
            // Cover/layout changes keep the same operation alive for its eventual real frame.
            if(!completed && !visible)operation?.let {AndroidReadingTimings.ledger.finish(it,ReaderOperationOutcome.CANCELED)}
            completed=true
        }
    }
}
