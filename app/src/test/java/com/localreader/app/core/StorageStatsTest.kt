package com.localreader.app.core

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class StorageStatsTest {
    @Test fun everyFileOnceNoSymlinkEscapeAndRevisionMixIsReported() {
        val root=Files.createTempDirectory("storage").toFile();val outside=Files.createTempFile("outside","txt").toFile()
        try {
            fun file(path: String,size: Int) { java.io.File(root,path).apply { parentFile!!.mkdirs();writeBytes(ByteArray(size)) } }
            file("library/id/original.txt",17);file("library/id/versions/v/index.bin",31);file("library/id/versions/v/cover.webp",11);file("trash/old/data",9)
            Files.createSymbolicLink(java.io.File(root,"escape").toPath(),outside.toPath());outside.writeBytes(ByteArray(1000))
            val alias=java.io.File(root.parentFile,"alias-${root.name}")
            Files.createSymbolicLink(alias.toPath(),root.toPath())
            val result=try { scanStorage(listOf(alias,root,java.io.File(alias,"library")),123,{2}) } finally { alias.delete() }
            assertEquals(68L,result.total);assertEquals(17L,result.bytes[StorageCategory.ORIGINAL]);assertEquals(31L,result.bytes[StorageCategory.DERIVED]);assertTrue(result.consistent)
            var revision=2L
            assertFalse(scanStorage(listOf(root),123,{revision},checkpoint={revision=4}).consistent)
            assertFalse(scanStorage(listOf(root),123,{3}).consistent)
        } finally { root.deleteRecursively();outside.delete() }
    }
    @Test fun writeSpanningScanCannotBecomeConsistentBetweenRevisionAndIdleReads() {
        val root=Files.createTempDirectory("storage-clock").toFile()
        try {
            java.io.File(root,"data").writeText("original")
            var epoch=0L;var active=false;var revisionReads=0;var idleReads=0
            val result=scanStorage(listOf(root),0,revision={
                revisionReads++
                val snapshot=epoch
                if(revisionReads==2) { epoch=4;active=false }
                snapshot
            },quiescent={
                idleReads++
                val idle=!active
                if(idleReads==1) { active=true;epoch=2 }
                idle
            })
            assertFalse(result.consistent);assertEquals(8L,result.total)
        } finally { root.deleteRecursively() }
    }

}
