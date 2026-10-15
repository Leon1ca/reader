package com.localreader.app.core

import com.localreader.app.core.diagnostics.DiagnosticLog
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class DiagnosticLogTest {
    private lateinit var dir: File
    private var now = 1_700_000_000_000L
    @Before fun setUp() { dir = kotlin.io.path.createTempDirectory("diag").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }
    private fun log(max: Long = 192 * 1024) = DiagnosticLog(File(dir,"log.txt"),{ now },max)

    @Test fun entriesCarryTimeCodeAndSanitisedDetail() {
        val log = log();log.record("open_failed","java.io.IOException")
        assertEquals(listOf("2023-11-14T22:13:20Z open_failed java.io.IOException"),log.lines())
    }
    @Test fun titlesPathsAddressesAndNonAsciiNeverReachTheFile() {
        val log = log()
        log.record("import_failed","《红楼梦》 /storage/emulated/0/Books/secret.epub content://media/external/42 file:///data/x https://example.com/a?b=1 IllegalStateException")
        val text = File(dir,"log.txt").readText()
        assertTrue(text.contains("IllegalStateException"))
        listOf("红楼梦","storage","secret","content:","file:","example","http").forEach { assertFalse("leaked $it",text.contains(it)) }
        assertEquals("a.b",DiagnosticLog.sanitize("a.b .. ../x"))
        assertEquals("",DiagnosticLog.sanitize("中文标题"))
        assertTrue(DiagnosticLog.sanitize("x".repeat(500)).length <= DiagnosticLog.MAX_DETAIL || DiagnosticLog.sanitize("x".repeat(500)).isEmpty())
    }
    @Test fun badEventCodesAreIgnored() {
        val log = log();listOf("","Open Failed","open failed","1abc","a","x".repeat(60),"书名").forEach { log.record(it,"d") }
        assertEquals(0,log.count())
    }
    @Test fun theLogStaysBoundedAndKeepsTheNewestEntries() {
        val log = log(max = 2_000)
        repeat(500) { now += 1000;log.record("tick","n$it") }
        assertTrue(File(dir,"log.txt").length() < 4_000)
        assertTrue(log.lines().last().endsWith("n499"));assertFalse(log.lines().any { it.endsWith(" n0") })
    }
    @Test fun clearAndExport() {
        val log = log();log.record("a_b","x");log.record("c_d","y")
        val text = log.export(mapOf("app" to "0.6.0","device" to "Redmi 24129PN74C","note" to "书名 /secret"))
        assertTrue(text.startsWith("# Reader diagnostic log"));assertTrue(text.contains("# entries: 2"));assertTrue(text.contains("# app: 0.6.0"))
        assertFalse(text.contains("secret"));assertFalse(text.contains("书名"))
        log.clear();assertEquals(0,log.count());assertTrue(log.export(emptyMap()).contains("# entries: 0"))
    }
}
