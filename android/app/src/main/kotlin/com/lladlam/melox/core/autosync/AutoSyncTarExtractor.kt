package com.lladlam.melox.core.autosync

import java.io.File
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream

/**
 * Extracts a `.tar.gz` into a directory, refusing any entry that would resolve
 * outside it. Split out from the repository so the traversal defence can be
 * unit-tested without Android.
 */
internal object AutoSyncTarExtractor {

    suspend fun extract(archive: File, destination: File) {
        val root = destination.canonicalFile.toPath()
        if (!destination.exists() && !destination.mkdirs()) {
            throw IOException("cannot create ${destination.absolutePath}")
        }
        GzipCompressorInputStream(archive.inputStream().buffered()).use { gzip ->
            TarArchiveInputStream(gzip).use { tar ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = tar.nextEntry ?: break
                    val name = entry.name
                    if (name.isBlank()) continue
                    val target = File(destination, name).canonicalFile
                    check(target.toPath().startsWith(root)) {
                        "archive entry escapes the destination: $name"
                    }
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        target.outputStream().buffered().use { output -> tar.copyTo(output) }
                    }
                }
            }
        }
    }
}
