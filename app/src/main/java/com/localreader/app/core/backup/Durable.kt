package com.localreader.app.core.backup

import java.io.File
import java.io.FileInputStream

/**
 * Restore journals are fsynced, so every byte they point at must already be on stable storage when they are written —
 * otherwise a power loss leaves a durable "COMMITTED" over zero-length files. A failure here is never ignored: the caller
 * must see it and stop before publishing the next phase.
 */
object Durable {
    /** Forces a file's contents to stable storage. */
    fun syncFile(file: File) { FileInputStream(file).use { it.fd.sync() } }

    /** Forces every regular file under [root] to stable storage and then every directory (children first), so names are durable too. */
    fun syncTree(root: File,syncDirectory: (File) -> Unit,syncFile: (File) -> Unit = ::syncFile) {
        val children = root.listFiles() ?: throw java.io.IOException("无法读取 ${root.name}")
        for(child in children) {
            if(java.nio.file.Files.isSymbolicLink(child.toPath())) continue
            if(child.isDirectory) syncTree(child,syncDirectory,syncFile) else if(child.isFile) syncFile(child)
        }
        syncDirectory(root)
    }
}
