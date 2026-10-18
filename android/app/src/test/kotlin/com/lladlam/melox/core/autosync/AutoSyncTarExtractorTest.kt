package com.lladlam.melox.core.autosync

import java.io.File
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AutoSyncTarExtractorTest {

    private fun writeArchive(target: File, entries: List<Pair<String, ByteArray>>) {
        GzipCompressorOutputStream(target.outputStream().buffered()).use { gzip ->
            TarArchiveOutputStream(gzip).use { tar ->
                entries.forEach { (name, bytes) ->
                    val entry = TarArchiveEntry(name)
                    entry.size = bytes.size.toLong()
                    tar.putArchiveEntry(entry)
                    tar.write(bytes)
                    tar.closeArchiveEntry()
                }
            }
        }
    }

    @Test
    fun extractsFilesAndDirectories() = runBlocking {
        val archive = File.createTempFile("dic", ".tar.gz")
        val destination = File.createTempFile("dic-out", "").let { it.delete(); File(it.parentFile, it.name) }
        try {
            writeArchive(
                archive,
                listOf(
                    "open_jtalk_dic_utf_8-1.11/" to ByteArray(0),
                    "open_jtalk_dic_utf_8-1.11/sys.dic" to "dictionary".toByteArray(),
                    "open_jtalk_dic_utf_8-1.11/COPYING" to "naist".toByteArray(),
                ),
            )
            AutoSyncTarExtractor.extract(archive, destination)
            assertEquals(
                "dictionary",
                File(destination, "open_jtalk_dic_utf_8-1.11/sys.dic").readText(),
            )
            assertEquals("naist", File(destination, "open_jtalk_dic_utf_8-1.11/COPYING").readText())
        } finally {
            archive.delete()
            destination.deleteRecursively()
        }
    }

    @Test
    fun refusesEntriesThatEscapeTheDestination() = runBlocking {
        val archive = File.createTempFile("evil", ".tar.gz")
        val parent = File.createTempFile("staging", "").let { it.delete(); it.mkdirs(); it }
        try {
            writeArchive(archive, listOf("../escaped.txt" to "nope".toByteArray()))
            try {
                AutoSyncTarExtractor.extract(archive, File(parent, "inner"))
                fail("path traversal must be rejected")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message.orEmpty().contains("escapes"))
            }
            assertTrue("nothing may be written outside", !File(parent, "escaped.txt").exists())
        } finally {
            archive.delete()
            parent.deleteRecursively()
        }
    }
}
