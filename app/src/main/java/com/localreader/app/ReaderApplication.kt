package com.localreader.app

import android.app.Application
import com.localreader.app.data.LibraryRepository
import com.localreader.app.data.LibraryOpenedBookFactory
import com.localreader.app.data.SqliteReadingStore

class ReaderApplication : Application() {
    val covers by lazy { com.localreader.app.ui.shelf.CoverLoader(java.io.File(filesDir,"library")) }
    override fun onCreate() {
        super.onCreate()
        com.localreader.app.ui.UiStrings.initialize(this)
        // A confirmed restore is finished or undone here, before any component opens the library.
        restore.recoverOnStart()
        android.util.Log.i("ReaderTiming","applicationCreate elapsedRealtimeNanos=${android.os.SystemClock.elapsedRealtimeNanos()}")
    }
    private val applicationScope=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()+kotlinx.coroutines.Dispatchers.IO)
    val uiPreferences by lazy { com.localreader.app.data.AndroidUiPrefsStore(this) }
    val imports by lazy { com.localreader.app.core.ImportCoordinator(repository,applicationScope,repository::availableBytes,com.localreader.app.ui.AndroidReadingTimings.ledger) }
    val diagnostics by lazy { com.localreader.app.core.diagnostics.DiagnosticLog(java.io.File(filesDir,"diagnostics/log.txt")) }
    val userFonts by lazy { com.localreader.app.data.UserFontStore(this,repository) }
    val restore by lazy { com.localreader.app.data.RestoreCoordinator(this) }
    val backups by lazy { com.localreader.app.data.BackupService(this,repository,restore) }
    val repository by lazy { LibraryRepository(this) }
    val openedBooks by lazy { LibraryOpenedBookFactory(repository) }
    val readingStore by lazy { SqliteReadingStore(repository.db) }
}
