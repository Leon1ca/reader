package com.localreader.app

import android.app.Application
import com.localreader.app.data.LibraryRepository
import com.localreader.app.data.LibraryOpenedBookFactory
import com.localreader.app.data.SqliteReadingStore

class ReaderApplication : Application() {
    val repository by lazy { LibraryRepository(this) }
    val openedBooks by lazy { LibraryOpenedBookFactory(repository) }
    val readingStore by lazy { SqliteReadingStore(repository.db) }
}
