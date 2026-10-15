package com.localreader.app.core.backup

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupPackageTest {
    private lateinit var root: File
    @Before fun setUp() { root = kotlin.io.path.createTempDirectory("backup-test").toFile() }
    @After fun tearDown() { root.deleteRecursively() }

    private fun database(userVersion: Int = 5): ByteArray = ByteArray(4096).also {
        BackupFormat.SQLITE_MAGIC.toByteArray(Charsets.ISO_8859_1).copyInto(it)
        java.nio.ByteBuffer.wrap(it,60,4).putInt(userVersion)
    }
    private fun sources(full: Boolean = true): List<BackupSource> = buildList {
        add(BackupSource.Bytes(BackupFormat.DATABASE,database()))
        add(BackupSource.Bytes(BackupFormat.PREFERENCES,"{\"night_mode\":\"SYSTEM\"}".toByteArray()))
        if(full) {
            add(BackupSource.Bytes("files/book-1/original.txt","第一章\n正文".toByteArray()))
            add(BackupSource.Bytes("files/book-1/versions/v1/index.bin",ByteArray(5000) { (it % 7).toByte() }))
        }
    }
    private fun pack(scope: BackupScope = BackupScope.FULL,list: List<BackupSource> = sources(scope == BackupScope.FULL)): ByteArray {
        val out = ByteArrayOutputStream()
        BackupWriter.write(out,scope,5,"0.5.0",1_700_000_000_000,1,list)
        return out.toByteArray()
    }
    private fun extract(bytes: ByteArray,limits: BackupLimits = BackupLimits()): BackupPreview {
        val target = File(root,"out").apply { deleteRecursively();mkdirs() }
        return BackupReader.extract(bytes.inputStream(),target,limits)
    }
    private fun rejected(kind: BackupRejected.Kind,block: () -> Unit) {
        val error = assertThrows(BackupRejected::class.java) { block() }
        assertEquals(error.message,kind,error.kind)
        assertTrue("a rejected package leaves nothing behind",File(root,"out").list().orEmpty().isEmpty())
    }
    /** Builds an arbitrary (possibly hostile) package whose manifest declares [declared] and whose entries are [entries]. */
    private fun forged(declared: List<Triple<String,Long,ByteArray>>,entries: List<Pair<String,ByteArray>>,formatVersion: Int = 1,scope: String = "full"): ByteArray {
        val json = org.json.JSONObject().put("formatVersion",formatVersion).put("schemaVersion",5).put("appVersion","0.5.0").put("createdAt",1).put("scope",scope).put("bookCount",1)
            .put("files",org.json.JSONArray().apply { declared.forEach { (path,size,content) -> put(org.json.JSONObject().put("path",path).put("size",size).put("sha256",BackupWriter.hash(content))) } })
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(BackupFormat.MANIFEST));zip.write(json.toString().toByteArray());zip.closeEntry()
            entries.forEach { (name,content) -> zip.putNextEntry(ZipEntry(name));zip.write(content);zip.closeEntry() }
        }
        return out.toByteArray()
    }

    @Test fun fullPackageRoundTripsEveryFileByteForByte() {
        val list = sources();val preview = extract(pack())
        assertEquals(BackupScope.FULL,preview.manifest.scope);assertEquals(5,preview.databaseUserVersion);assertTrue(preview.hasPreferences)
        assertEquals(list.size,preview.manifest.files.size);assertEquals(1,preview.manifest.bookCount)
        list.forEach { source -> assertArrayEquals(source.path,(source as BackupSource.Bytes).bytes,File(root,"out/${source.path}").readBytes()) }
    }
    @Test fun recordsPackageHasNoBookFiles() {
        val preview = extract(pack(BackupScope.RECORDS))
        assertEquals(BackupScope.RECORDS,preview.manifest.scope);assertTrue(preview.manifest.files.none { it.path.startsWith("files/") })
    }
    @Test fun diskSourceThatChangesWhileExportingFailsTheExport() {
        val file = File(root,"data.bin").apply { writeBytes(ByteArray(300_000) { 1 }) }
        var calls = 0
        val error = assertThrows(BackupRejected::class.java) {
            BackupWriter.write(ByteArrayOutputStream(),BackupScope.FULL,5,"0.5.0",1,1,listOf(BackupSource.Bytes(BackupFormat.DATABASE,database()),BackupSource.Disk("files/b/original.txt",file)),
                progress = { _,_ -> if(++calls == 8) file.appendBytes(byteArrayOf(9)) })
        }
        assertEquals(BackupRejected.Kind.CORRUPT,error.kind)
    }
    @Test fun cancellationStopsWriterAndReader() {
        val error = assertThrows(BackupRejected::class.java) { BackupWriter.write(ByteArrayOutputStream(),BackupScope.FULL,5,"0.5.0",1,1,sources(),cancelled = { true }) }
        assertEquals(BackupRejected.Kind.CANCELLED,error.kind)
        val bytes = pack();val target = File(root,"out").apply { mkdirs() }
        var polls = 0
        val readError = assertThrows(BackupRejected::class.java) { BackupReader.extract(bytes.inputStream(),target,cancelled = { ++polls > 2 }) }
        assertEquals(BackupRejected.Kind.CANCELLED,readError.kind);assertTrue(target.list().orEmpty().isEmpty())
    }

    @Test fun corruptTruncatedAndNonPackageFilesAreRejected() {
        val good = pack()
        rejected(BackupRejected.Kind.CORRUPT) { extract(good.copyOf(good.size / 2)) }
        rejected(BackupRejected.Kind.MALFORMED) { extract("not a zip".toByteArray()) }
        rejected(BackupRejected.Kind.MALFORMED) { extract(ByteArray(0)) }
        val flipped = good.copyOf();flipped[flipped.size / 2] = (flipped[flipped.size / 2] + 1).toByte()
        val result = runCatching { extract(flipped) }.exceptionOrNull()
        assertTrue(result is BackupRejected)
    }
    @Test fun contentThatDoesNotMatchItsManifestHashIsRejected() {
        val real = "abcdef".toByteArray();val other = "abcxyz".toByteArray()
        rejected(BackupRejected.Kind.CORRUPT) { extract(forged(listOf(Triple(BackupFormat.DATABASE,database().size.toLong(),database()),Triple("files/b/original.txt",6,real)),listOf(BackupFormat.DATABASE to database(),"files/b/original.txt" to other))) }
    }
    @Test fun higherFormatVersionIsRefusedWithoutReadingFurther() {
        rejected(BackupRejected.Kind.UNSUPPORTED_VERSION) { extract(forged(emptyList(),emptyList(),formatVersion = 2)) }
    }
    @Test fun manifestMustComeFirstAndBeValid() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { it.putNextEntry(ZipEntry("data/library.db"));it.write(database());it.closeEntry() }
        rejected(BackupRejected.Kind.MALFORMED) { extract(out.toByteArray()) }
        val bad = ByteArrayOutputStream()
        ZipOutputStream(bad).use { it.putNextEntry(ZipEntry(BackupFormat.MANIFEST));it.write("{}".toByteArray());it.closeEntry() }
        rejected(BackupRejected.Kind.MALFORMED) { extract(bad.toByteArray()) }
    }
    @Test fun unsafePathsAreRejectedBeforeAnythingIsWritten() {
        val payload = "x".toByteArray()
        listOf("../escape.txt","files/../../escape","/abs/path","files//b/x","files/b/./x","files\\b\\x","files/b/x\u0000","files/C:/x","files/b/trailing.","other/b/x","fonts/x.ttf","fonts/123e4567-e89b-12d3-a456-426614174000.exe","fonts/sub/123e4567-e89b-12d3-a456-426614174000.ttf","fonts","files/onlyone","files/b/${"a".repeat(300)}").forEach { name ->
            rejected(BackupRejected.Kind.UNSAFE) { extract(forged(listOf(Triple(name,1,payload)),listOf(name to payload))) }
        }
    }
    @Test fun entriesNotInTheManifestAndDuplicatesAreRejected() {
        val db = database()
        rejected(BackupRejected.Kind.UNSAFE) { extract(forged(listOf(Triple(BackupFormat.DATABASE,db.size.toLong(),db)),listOf(BackupFormat.DATABASE to db,"files/b/extra.txt" to "x".toByteArray()))) }
        rejected(BackupRejected.Kind.UNSAFE) { extract(forged(listOf(Triple(BackupFormat.DATABASE,db.size.toLong(),db),Triple("files/b/x","1".length.toLong(),"x".toByteArray()),Triple("FILES/B/X",1,"x".toByteArray())),emptyList())) }
        rejected(BackupRejected.Kind.CORRUPT) { extract(forged(listOf(Triple(BackupFormat.DATABASE,db.size.toLong(),db),Triple("files/b/x",1,"x".toByteArray())),listOf(BackupFormat.DATABASE to db))) }
    }
    @Test fun decompressionBombIsStoppedAtTheDeclaredSize() {
        val db = database();val bomb = ByteArray(50 * 1024 * 1024)
        rejected(BackupRejected.Kind.UNSAFE) { extract(forged(listOf(Triple(BackupFormat.DATABASE,db.size.toLong(),db),Triple("files/b/bomb",1024,ByteArray(1024))),listOf(BackupFormat.DATABASE to db,"files/b/bomb" to bomb))) }
    }
    @Test fun limitsRejectOversizedAndOverlongManifests() {
        val small = BackupLimits(maxEntries = 3,maxFileBytes = 6000,maxTotalBytes = 7000)
        rejected(BackupRejected.Kind.TOO_LARGE) { extract(pack(),small) }
        rejected(BackupRejected.Kind.TOO_LARGE) { extract(pack(),BackupLimits(maxEntries = 2)) }
        rejected(BackupRejected.Kind.TOO_LARGE) { extract(pack(),BackupLimits(maxManifestBytes = 10)) }
    }
    @Test fun databaseHeaderIsCheckedWithoutOpeningIt() {
        val notDb = ByteArray(200)
        rejected(BackupRejected.Kind.CORRUPT) { extract(forged(listOf(Triple(BackupFormat.DATABASE,200,notDb)),listOf(BackupFormat.DATABASE to notDb))) }
        rejected(BackupRejected.Kind.MALFORMED) { extract(forged(emptyList(),emptyList())) }
        val newer = database(userVersion = 99)
        assertEquals(99,extract(forged(listOf(Triple(BackupFormat.DATABASE,newer.size.toLong(),newer)),listOf(BackupFormat.DATABASE to newer))).databaseUserVersion)
    }
    @Test fun recordsScopeCannotCarryBookFiles() {
        val db = database();val book = "x".toByteArray()
        rejected(BackupRejected.Kind.MALFORMED) { extract(forged(listOf(Triple(BackupFormat.DATABASE,db.size.toLong(),db),Triple("files/b/original.txt",1,book)),listOf(BackupFormat.DATABASE to db,"files/b/original.txt" to book),scope = "records")) }
    }
    @Test fun destinationMustBeEmpty() {
        val target = File(root,"out").apply { mkdirs();File(this,"stale").writeText("x") }
        assertThrows(BackupRejected::class.java) { BackupReader.extract(pack().inputStream(),target) }
        assertTrue(File(target,"stale").exists())
    }
    @Test fun resolveRefusesLinksThatLeaveTheRoot() {
        val outside = File(root,"outside").apply { mkdirs() }
        val inside = File(root,"inside").apply { mkdirs() }
        java.nio.file.Files.createSymbolicLink(File(inside,"files").toPath(),outside.toPath())
        val error = assertThrows(BackupRejected::class.java) { BackupPaths.resolve(inside,"files/b/x") }
        assertEquals(BackupRejected.Kind.UNSAFE,error.kind)
    }
    @Test fun fontsTravelOnlyInFullPackagesWithStrictNames() {
        val id = "123e4567-e89b-12d3-a456-426614174000"
        val list = sources() + BackupSource.Bytes("fonts/$id.ttf",ByteArray(64) { 3 })
        val preview = extract(pack(list = list))
        assertTrue(preview.manifest.files.any { it.path == "fonts/$id.ttf" });assertTrue(File(root,"out/fonts/$id.ttf").isFile)
        val db = database();val font = ByteArray(8)
        rejected(BackupRejected.Kind.MALFORMED) { extract(forged(listOf(Triple(BackupFormat.DATABASE,db.size.toLong(),db),Triple("fonts/$id.otf",8,font)),listOf(BackupFormat.DATABASE to db,"fonts/$id.otf" to font),scope = "records")) }
    }
    /** V060-P1-01: every extracted file is forced to stable storage, complete, before extraction returns. */
    @Test fun everyExtractedFileIsSyncedCompleteAndASyncFailureIsNotSwallowed() {
        val bytes = pack();val list = sources()
        val synced = mutableListOf<Pair<String,Long>>()
        val target = File(root,"out").apply { deleteRecursively();mkdirs() }
        val preview = BackupReader.extract(bytes.inputStream(),target,syncFile = { synced += it.canonicalPath.removePrefix(target.canonicalPath + "/") to it.length() })
        assertEquals("one sync per manifest file",preview.manifest.files.map { it.path }.toSet(),synced.map { it.first }.toSet())
        preview.manifest.files.forEach { declared -> assertEquals("${declared.path} was complete when synced",declared.size,synced.first { it.first == declared.path }.second) }
        assertEquals(list.size,synced.size)
        val failing = File(root,"out2").apply { deleteRecursively();mkdirs() }
        assertThrows(java.io.IOException::class.java) { BackupReader.extract(bytes.inputStream(),failing,syncFile = { throw java.io.IOException("fsync failed") }) }
        assertTrue("a package that could not be made durable leaves nothing behind",failing.list().orEmpty().isEmpty())
    }
    @Test fun syncTreeSyncsEveryFileThenEveryDirectoryChildrenFirstAndPropagatesFailures() {
        val tree = File(root,"tree").apply { File(this,"a/b").mkdirs();File(this,"a/b/x.bin").writeText("x");File(this,"y.bin").writeText("y") }
        val order = mutableListOf<String>()
        Durable.syncTree(tree,{ order += "D:" + it.canonicalPath.removePrefix(tree.canonicalPath).removePrefix("/") },{ order += "F:" + it.canonicalPath.removePrefix(tree.canonicalPath).removePrefix("/") })
        assertEquals(setOf("F:a/b/x.bin","F:y.bin","D:a/b","D:a","D:"),order.toSet())
        assertTrue("a directory is synced after the files inside it",order.indexOf("F:a/b/x.bin") < order.indexOf("D:a/b") && order.indexOf("D:a/b") < order.indexOf("D:a") && order.indexOf("D:a") < order.indexOf("D:"))
        assertThrows(java.io.IOException::class.java) { Durable.syncTree(tree,{ throw java.io.IOException("dir fsync failed") }) }
        assertThrows(java.io.IOException::class.java) { Durable.syncTree(tree,{ },{ throw java.io.IOException("file fsync failed") }) }
    }
}
