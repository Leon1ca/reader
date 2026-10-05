package com.localreader.app.core.search

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class MappedTapeTest {
    @Test fun partialExpandedGlyphMapsWholeSourceAndUnchangedRunsStayLinear() {
        val root = Files.createTempDirectory("mapped-tape").toFile()
        try {
            TapeWriter(root, "test", 4096).use { writer ->
                writer.append("甲", SourceRange(0, 1), true)
                writer.append("ffi", SourceRange(1, 2), false)
                writer.append("😀乙", SourceRange(2, 5), true)
                writer.finish().use { tape ->
                    assertEquals("甲ffi😀乙", tape.slice(0, 20))
                    assertEquals(SourceRange(1, 2), tape.inverse(2, 4))
                    assertEquals(SourceRange(1, 5), tape.inverse(3, 7))
                    assertTrue(tape.slice(4, 1)[0].isHighSurrogate())
                    assertTrue(tape.slice(5, 1)[0].isLowSurrogate())
                    assertEquals(SourceRange(3, 4), tape.inverse(5, 6))
                }
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun scanDeduplicatesExpandedSourceAndProvesTheTwoHundredAndFirstDistinctHit() {
        val root = Files.createTempDirectory("mapped-search").toFile()
        try {
            val document = object : CanonicalDocument {
                override val length = 201L
                override fun slice(start: Long, count: Int) = "ﬃ".repeat(minOf(count.toLong(), length - start).toInt())
                override fun permits(range: SourceRange) = true
            }
            TapeWriter(root, "test", 40000).use { writer ->
                repeat(201) { writer.append("ffi", SourceRange(it.toLong(), it.toLong() + 1), false) }
                NormalizedDocument(writer.finish(), root).use { normalized ->
                    val result = normalized.find("f", document)
                    assertEquals(200, result.ranges.size);assertTrue(result.truncated)
                    assertEquals(SourceRange(199, 200), result.ranges.last())
                }
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun cancellationInterruptsBothCandidateLoopAndNonlinearInverseTraversal() {
        val root = Files.createTempDirectory("mapped-cancel").toFile()
        try {
            val document = object : CanonicalDocument {
                override val length = 20000L
                override fun slice(start: Long, count: Int) = "e\u0301".repeat(minOf(count.toLong() / 2, (length - start) / 2).toInt())
                override fun permits(range: SourceRange) = true
            }
            TapeWriter(root, "test", 500000).use { writer ->
                repeat(10000) { writer.append("é", SourceRange(it.toLong() * 2, it.toLong() * 2 + 2), false) }
                NormalizedDocument(writer.finish(), root).use { normalized ->
                    var checks = 0
                    assertThrows(java.util.concurrent.CancellationException::class.java) {
                        normalized.find("é".repeat(4096), document) { if (++checks == 3) throw java.util.concurrent.CancellationException() }
                    }
                    assertEquals(3, checks)
                    checks = 0
                    assertThrows(java.util.concurrent.CancellationException::class.java) {
                        normalized.find("é", document) { if (++checks == 3) throw java.util.concurrent.CancellationException() }
                    }
                    assertEquals(3, checks)
                }
            }
        } finally { root.deleteRecursively() }
    }
}
