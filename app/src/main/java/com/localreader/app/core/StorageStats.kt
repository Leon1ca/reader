package com.localreader.app.core

import java.io.File
import java.nio.file.Files

enum class StorageCategory { ORIGINAL, DERIVED, COVER, DATABASE, STAGING, TRASH, OTHER }
data class StorageStats(val bytes: Map<StorageCategory,Long>,val availableBytes: Long,val consistent: Boolean) {
    val total: Long get() = bytes.values.fold(0L,Math::addExact)
}
/** No symlinks are followed; each physical path is visited exactly once. No cleanup side effects. */
fun scanStorage(roots: List<File>,availableBytes: Long,revision: ()->Long,checkpoint: ()->Unit={},quiescent: ()->Boolean={true}): StorageStats {
    val before=revision();val idleBefore=quiescent();val totals=StorageCategory.entries.associateWith { 0L }.toMutableMap()
    val seen=hashSetOf<String>();var count=0
    fun visit(file: File) {
        if(++count%128==0)checkpoint()
        if(Files.isSymbolicLink(file.toPath()) || !seen.add(file.canonicalPath))return
        if(file.isDirectory) { file.listFiles()?.forEach(::visit) ?: error("无法读取存储目录") ;return }
        if(!file.isFile)return
        val path=file.invariantSeparatorsPath
        val category=when {
            "/trash/" in path -> StorageCategory.TRASH
            "/import-staging/" in path || ".stage-" in path || ".derived-staging-" in path -> StorageCategory.STAGING
            file.name.startsWith("cover") -> StorageCategory.COVER
            file.name in setOf("original.txt","original.epub") -> StorageCategory.ORIGINAL
            "/databases/" in path -> StorageCategory.DATABASE
            "/versions/" in path || "/reader-search/" in path -> StorageCategory.DERIVED
            else -> StorageCategory.OTHER
        }
        totals[category]=Math.addExact(totals.getValue(category),file.length())
    }
    // Resolve caller-owned root aliases once. Descendant symlinks are never followed.
    roots.map { it.canonicalFile }.distinctBy { it.path }.forEach(::visit);checkpoint()
    val idleAfter=quiescent();val after=revision()
    return StorageStats(totals,availableBytes,idleBefore && idleAfter && before%2L==0L && before==after)
}
