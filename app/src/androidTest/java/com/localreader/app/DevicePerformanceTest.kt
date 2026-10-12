package com.localreader.app

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.localreader.app.core.*
import com.localreader.app.data.LibraryOpenedBookFactory
import com.localreader.app.data.LibraryRepository
import com.localreader.app.data.SqliteReadingStore
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.ceil

/** Opt-in kernel/storage benchmarks, isolated from the user's library. Does not claim rendered OPEN/PAGE timing. */
class DevicePerformanceTest {
    @Test fun measureStorageAndTxtFactory() = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("Explicit devicePerformance=storage required",args.getString("devicePerformance")=="storage")
        val target=instrumentation.targetContext
        // Instrumentation restarts the target process. MIUI otherwise leaves it
        // behind the previous app and freezes workers before the first sample.
        target.startActivity(requireNotNull(target.packageManager.getLaunchIntentForPackage(target.packageName)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        val root=File(target.cacheDir,"owned-performance-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context=object: ContextWrapper(target) {
            override fun getFilesDir()=File(root,"files").apply { mkdirs() }
            override fun getCacheDir()=File(root,"cache").apply { mkdirs() }
            override fun getDatabasePath(name: String)=File(File(root,"databases").apply { mkdirs() },name)
            override fun openOrCreateDatabase(name: String,mode: Int,factory: SQLiteDatabase.CursorFactory?)=SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory)
            override fun openOrCreateDatabase(name: String,mode: Int,factory: SQLiteDatabase.CursorFactory?,handler: DatabaseErrorHandler?)=SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path,factory,handler)
        }
        val repository=LibraryRepository(context)
        val report=JSONObject().put("scope","Factory/storage only; render-visible OPEN/PAGE requires UI acceptance")
            .put("buildVariant",if(target.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE!=0)"debug" else "release")
            .put("sdk",android.os.Build.VERSION.SDK_INT).put("device",android.os.Build.MODEL).put("fingerprint",android.os.Build.FINGERPRINT)
            .put("webView",android.webkit.WebView.getCurrentWebViewPackage()?.versionName)
        val metrics=JSONObject();report.put("metrics",metrics).put("txtSourceBytes",20*1024*1024).put("shelfTxtCount",500).put("shelfEpubCount",500)
        val failures=mutableListOf<String>()
        val stage=java.util.concurrent.atomic.AtomicReference("starting")
        val stageSince=java.util.concurrent.atomic.AtomicLong(android.os.SystemClock.elapsedRealtime())
        val running=java.util.concurrent.atomic.AtomicBoolean(true)
        val testThread=Thread.currentThread()
        fun phase(value: String) {stage.set(value);stageSince.set(android.os.SystemClock.elapsedRealtime());android.util.Log.i("ReaderPerformance","phase=$value")}
        val watchdog=Thread({
            try {
                while(running.get()) {
                    Thread.sleep(1000)
                    if(android.os.SystemClock.elapsedRealtime()-stageSince.get()>120_000) {
                        android.util.Log.e("ReaderPerformance","TIMEOUT phase=${stage.get()} elapsedMs=${android.os.SystemClock.elapsedRealtime()-stageSince.get()}")
                        Thread.getAllStackTraces().forEach { (thread,frames) -> android.util.Log.e("ReaderPerformance","thread=${thread.name} state=${thread.state}\n${frames.joinToString("\n")}") }
                        testThread.interrupt();break
                    }
                }
            } catch(_: InterruptedException) { /* Test completed. */ }
        },"owned-performance-watchdog").apply { isDaemon=true;start() }
        fun measure(name: String,limitMs: Double,work: ()->Unit) {
            val samples=List(20) { index -> phase("$name sample=${index+1}/20");val start=System.nanoTime();work();(System.nanoTime()-start)/1_000_000.0 }.sorted()
            val median=(samples[9]+samples[10])/2;val p95=samples[ceil(samples.size*.95).toInt()-1]
            metrics.put(name,JSONObject().put("samplesMs",JSONArray(samples)).put("medianMs",median).put("p95Ms",p95).put("limitMs",limitMs).put("passed",p95<=limitMs))
            if(p95>limitMs)failures+=name
            android.util.Log.i("ReaderPerformance","$name n=20 medianMs=$median p95Ms=$p95 limitMs=$limitMs")
        }
        fun import(name: String,bytes: ByteArray): String {
            val result=repository.import(ImportSource(name,if(name.endsWith(".epub"))"application/epub+zip" else "text/plain",bytes.size.toLong()) { ByteArrayInputStream(bytes) })
            check(result.outcome==ImportOutcome.OK) { result.status };return requireNotNull(result.bookId)
        }
        try {
            phase("recover");repository.recover()
            val factory=LibraryOpenedBookFactory(repository);val store=SqliteReadingStore(repository.db)
            phase("txt20MiBFixture")
            val largeTxt=OwnedPerformanceFixtures.txt20MiB("Owned large TXT")
            phase("txt20MiBImport")
            val txt=import("owned-20MiB.txt",largeTxt)
            phase("txt20MiBFactoryWarmup")
            factory.open(txt).use { phase("txt20MiBPresentationWarmup");ReadingPresentation.prepare(it,store.loadSnapshot(txt)) }
            measure("txt20MiBHotFactoryAndPresentation",800.0) {
                runBlocking { factory.open(txt).use { opened -> ReadingPresentation.prepare(opened,store.loadSnapshot(txt)) } }
            }
            repository.content(txt)?.let(repository::delete)
            val seed=UUID.randomUUID().toString()
            repeat(1000) { index ->
                phase("shelfSeed ${index+1}/1000")
                val title="Owned-$seed-${index.toString().padStart(4,'0')}"
                if(index%2==0)import("$title.txt",("Chapter 1\n$title\n"+"Readable owned shelf fixture.\n".repeat(16)).toByteArray())
                else import("$title.epub",OwnedPerformanceFixtures.epub(title))
            }
            val rows=repository.summaries();check(rows.size==1000)
            // Warm the exact production projection cache outside measured samples.
            ShelfProjection.project(rows,ShelfState())
            measure("shelf1000Title",50.0) { ShelfProjection.project(rows,ShelfState(sort=ShelfSort.TITLE)) }
            measure("shelf1000Author",50.0) { ShelfProjection.project(rows,ShelfState(sort=ShelfSort.AUTHOR)) }
            measure("shelf1000Filter",50.0) { ShelfProjection.project(rows,ShelfState(filter=ShelfFilter.EPUB)) }
            measure("shelf1000Search",50.0) { ShelfProjection.project(rows,ShelfState(query="000")) }
            // Unique titles prevent the deduplication shortcut. Fixture creation and deletion are excluded.
            val importSamples=mutableListOf<Double>();val sourceBytes=mutableListOf<Int>()
            repeat(20) { index ->
                phase("epub5MiBFixture ${index+1}/20")
                val bytes=OwnedPerformanceFixtures.epub("Owned-5MiB-$seed-$index",large=true);check(bytes.size>=5*1024*1024)
                sourceBytes+=bytes.size
                phase("epub5MiBImport ${index+1}/20")
                val start=System.nanoTime();val id=import("owned-large-$index.epub",bytes);importSamples+=(System.nanoTime()-start)/1_000_000.0
                repository.content(id)?.let(repository::delete)
            }
            val sorted=importSamples.sorted();val median=(sorted[9]+sorted[10])/2;val p95=sorted[18]
            metrics.put("epub5MiBImport",JSONObject().put("sourceBytes",JSONArray(sourceBytes)).put("samplesMs",JSONArray(sorted)).put("medianMs",median).put("p95Ms",p95).put("limitMs",5000).put("passed",p95<=5000))
            if(p95>5000)failures+="epub5MiBImport"
            android.util.Log.i("ReaderPerformance","epub5MiBImport n=20 medianMs=$median p95Ms=$p95 limitMs=5000")
        } finally {
            running.set(false);watchdog.interrupt()
            report.put("lastPhase",stage.get())
            report.put("failures",JSONArray(failures))
            val hash=MessageDigest.getInstance("SHA-256")
            File(target.applicationInfo.sourceDir).inputStream().use { input -> val buffer=ByteArray(65536);while(true) { val count=input.read(buffer);if(count<0)break;hash.update(buffer,0,count) } }
            report.put("apkSha256",hash.digest().joinToString("") { "%02x".format(it) })
            val output=File(target.getExternalFilesDir(null),"device-performance-${System.currentTimeMillis()}.json");output.writeText(report.toString(2))
            android.util.Log.i("ReaderPerformance","report=${output.name}")
            repository.db.close();root.deleteRecursively()
        }
        if(args.getString("performanceEnforce")=="true")assertTrue("Performance thresholds failed: $failures",failures.isEmpty())
    }
}
