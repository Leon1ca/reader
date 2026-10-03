package com.localreader.app

import android.app.Application
import com.localreader.app.data.LibraryRepository

class ReaderApplication : Application() {
    val repository by lazy { LibraryRepository(this) }
}
