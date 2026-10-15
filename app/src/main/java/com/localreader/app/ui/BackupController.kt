package com.localreader.app.ui

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import com.localreader.app.R
import com.localreader.app.core.backup.*
import com.localreader.app.data.*
import com.localreader.app.ui.UiStrings.text as uiText
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface BackupUi {
    data object Idle: BackupUi
    data class Working(val verb: Verb,val done: Long,val total: Long): BackupUi
    data class Exported(val bytes: Long,val scope: BackupScope): BackupUi
    data class Preview(val data: RestorePreviewData): BackupUi
    /** The restore is confirmed; it completes when the app starts again. */
    data class ReadyToRestart(val taskId: String): BackupUi
    data class Failed(val message: String): BackupUi
    enum class Verb { EXPORT, VERIFY }
}

/** Runs exports and restore staging off the main thread; all state the screens show lives here. */
internal class BackupController(private val application: Application,private val service: BackupService,private val scope: CoroutineScope,
                                private val log: (String,String) -> Unit,private val settle: suspend () -> Boolean,private val canReplaceLibrary: () -> Boolean,private val closeDatabase: () -> Unit) {
    private val mutable = MutableStateFlow<BackupUi>(BackupUi.Idle)
    val state: StateFlow<BackupUi> = mutable.asStateFlow()
    private val estimateMutable = MutableStateFlow<BackupEstimate?>(null)
    val estimate: StateFlow<BackupEstimate?> = estimateMutable.asStateFlow()
    private var job: Job? = null
    @Volatile private var cancelRequested = false

    fun refreshEstimate() { scope.launch { estimateMutable.value = withContext(Dispatchers.IO) { runCatching { service.estimate() }.getOrNull() } } }
    fun busy() = state.value is BackupUi.Working
    fun reset() { if(!busy() && state.value !is BackupUi.ReadyToRestart) { (state.value as? BackupUi.Preview)?.let { discard(it.data) };mutable.value = BackupUi.Idle } }
    fun cancel() { cancelRequested = true }

    fun export(uri: Uri,backupScope: BackupScope) {
        if(busy()) return
        cancelRequested = false;mutable.value = BackupUi.Working(BackupUi.Verb.EXPORT,0,0)
        job = scope.launch(Dispatchers.IO) {
            var written = 0L
            try {
                // A reading position whose save failed would be missing from the package; stop rather than export stale progress.
                if(!settle()) throw BackupRejected(BackupRejected.Kind.CORRUPT,uiText(R.string.backup_progress_unsaved))
                val out = application.contentResolver.openOutputStream(uri,"wt") ?: throw java.io.IOException("无法写入所选位置")
                val counting = out.counting { written = it }
                val manifest = counting.use { stream ->
                    service.export(backupScope,stream,com.localreader.app.BuildConfig.VERSION_NAME,{ cancelRequested },{ done,total -> mutable.value = BackupUi.Working(BackupUi.Verb.EXPORT,done,total) })
                }
                log("backup_exported",manifest.scope.wire);mutable.value = BackupUi.Exported(written,manifest.scope)
            } catch(error: CancellationException) { removePartial(uri);throw error
            } catch(error: Throwable) {
                removePartial(uri)
                log("backup_export_failed",(error as? BackupRejected)?.kind?.name ?: error.javaClass.name)
                mutable.value = if(error is BackupRejected && error.kind == BackupRejected.Kind.CANCELLED) BackupUi.Idle else BackupUi.Failed(message(error,R.string.backup_export_failed))
            }
        }
    }

    fun stage(uri: Uri) {
        if(busy()) return
        cancelRequested = false;mutable.value = BackupUi.Working(BackupUi.Verb.VERIFY,0,0)
        job = scope.launch(Dispatchers.IO) {
            try {
                val input = application.contentResolver.openInputStream(uri) ?: throw java.io.IOException("无法读取所选文件")
                val preview = input.use { service.stage(it,{ cancelRequested },{ done,total -> mutable.value = BackupUi.Working(BackupUi.Verb.VERIFY,done,total) }) }
                mutable.value = BackupUi.Preview(preview)
            } catch(error: CancellationException) { throw error
            } catch(error: Throwable) {
                log("restore_rejected",(error as? BackupRejected)?.kind?.name ?: error.javaClass.name)
                mutable.value = if(error is BackupRejected && error.kind == BackupRejected.Kind.CANCELLED) BackupUi.Idle else BackupUi.Failed(message(error,R.string.backup_restore_failed))
            }
        }
    }

    /** Marks the verified package for replacement at the next start. Returns false if the library is busy. */
    fun confirm(data: RestorePreviewData) {
        if(!canReplaceLibrary()) { mutable.value = BackupUi.Failed(uiText(R.string.restore_library_busy));return }
        scope.launch {
            try {
                if(!settle()) { mutable.value = BackupUi.Failed(uiText(R.string.backup_progress_unsaved));return@launch }
                withContext(Dispatchers.IO) { service.confirmRestore(data.taskId) }
                log("restore_confirmed","");mutable.value = BackupUi.ReadyToRestart(data.taskId)
            } catch(cancel: CancellationException) { throw cancel
            } catch(error: Throwable) { mutable.value = BackupUi.Failed(message(error,R.string.backup_restore_failed)) }
        }
    }
    /** Changes of mind before the app is closed: the library was never touched. */
    fun cancelRestart() {
        val waiting = state.value as? BackupUi.ReadyToRestart ?: return
        scope.launch(Dispatchers.IO) { service.abortRestore(waiting.taskId);mutable.value = BackupUi.Idle }
    }
    /** Closes the library cleanly; the caller then ends the process so the next start performs the switch. */
    fun exit(then: () -> Unit) { scope.launch { if(settle()) { withContext(Dispatchers.IO) { closeDatabase() };then() } else mutable.value = BackupUi.Failed(uiText(R.string.backup_progress_unsaved)) } }
    fun discard(data: RestorePreviewData) { scope.launch(Dispatchers.IO) { service.discard(data.taskId) } }

    private fun message(error: Throwable,fallback: Int): String =
        if(error is BackupRejected) error.message.orEmpty() else if(error is java.io.IOException && error.message?.contains("ENOSPC") == true) uiText(R.string.backup_no_space) else uiText(fallback)
    private fun removePartial(uri: Uri) { runCatching { DocumentsContract.deleteDocument(application.contentResolver,uri) } }
    private fun OutputStream.counting(report: (Long) -> Unit): OutputStream {
        val target = this
        return object: java.io.FilterOutputStream(target) {
            var total = 0L
            override fun write(b: Int) { target.write(b);total++;report(total) }
            override fun write(b: ByteArray,off: Int,len: Int) { target.write(b,off,len);total += len;report(total) }
        }
    }
}
private typealias OutputStream = java.io.OutputStream
