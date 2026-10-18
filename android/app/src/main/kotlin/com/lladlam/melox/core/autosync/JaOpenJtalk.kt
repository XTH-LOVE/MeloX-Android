package com.lladlam.melox.core.autosync

import android.util.Log
import java.io.File

/**
 * Owns the OpenJTalk G2P engine handle.
 *
 * The native library is loaded once; the engine itself needs the NAIST
 * dictionary on disk (downloaded separately, see `AutoSyncDictionaryRepository`).
 * Every call fails soft: when the library or the dictionary is missing, the
 * Japanese path simply reports itself unavailable instead of guessing readings.
 */
internal object JaOpenJtalk {

    private const val TAG = "MeloXAutoSync"
    private const val LIBRARY = "melox_ja_g2p"

    @Volatile
    private var handle: Long = 0L

    @Volatile
    private var loaded = false

    /** Test hook: an explicit `.so` path used by the host JVM tests. */
    @Volatile
    var overrideLibraryPath: String? = null

    fun isLibraryAvailable(): Boolean {
        ensureLoaded()
        return loaded
    }

    fun isOpen(): Boolean = handle != 0L

    /** Opens the dictionary directory; false when the dictionary is unusable. */
    fun open(dictionaryDirectory: File): Boolean {
        ensureLoaded()
        if (!loaded) return false
        if (handle != 0L) close()
        val opened = runCatching {
            nativeOpen(dictionaryDirectory.absolutePath)
        }.onFailure { Log.w(TAG, "OpenJTalk open failed", it) }.getOrDefault(0L)
        handle = opened
        if (opened == 0L) Log.w(TAG, "OpenJTalk cannot load dictionary at ${dictionaryDirectory.absolutePath}")
        return opened != 0L
    }

    fun close() {
        val current = handle
        handle = 0L
        openedDirectory = null
        if (current != 0L) runCatching { nativeClose(current) }
    }

    @Volatile
    private var openedDirectory: String? = null

    /** Opens the installed NAIST dictionary once; false when it is missing. */
    fun ensureDictionaryInstalled(context: android.content.Context): Boolean {
        val directory = AutoSyncDictionaryRepository.dictionaryDirectory(context) ?: return false
        val path = directory.absolutePath
        if (isOpen() && openedDirectory == path) return true
        return open(directory).also { opened -> if (opened) openedDirectory = path }
    }

    /** Phonemes for `text`, or `null` when the engine is closed. */
    fun phonemes(text: String): List<String>? {
        val current = handle
        if (current == 0L) return null
        val raw = runCatching { nativePhonemes(current, text) }
            .onFailure { Log.w(TAG, "OpenJTalk phonemes failed", it) }
            .getOrNull() ?: return null
        return raw.split(' ').filter { it.isNotEmpty() }
    }

    fun kana(text: String): String? {
        val current = handle
        if (current == 0L) return null
        return runCatching { nativeKana(current, text) }
            .onFailure { Log.w(TAG, "OpenJTalk kana failed", it) }
            .getOrNull()
    }

    /** `(surface, katakana reading)` pairs from OpenJTalk's MeCab frontend. */
    fun tokens(text: String): List<Pair<String, String>>? {
        val current = handle
        if (current == 0L) return null
        val raw = runCatching { nativeTokens(current, text) }
            .onFailure { Log.w(TAG, "OpenJTalk tokens failed", it) }
            .getOrNull() ?: return null
        return raw.split('\n')
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab < 0) null else line.substring(0, tab) to line.substring(tab + 1)
            }
    }

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        val explicit = overrideLibraryPath
        loaded = if (explicit != null) {
            runCatching { System.load(explicit) }
                .onFailure { Log.w(TAG, "cannot load $explicit", it) }
                .isSuccess
        } else {
            runCatching { System.loadLibrary(LIBRARY) }
                .onFailure { Log.i(TAG, "OpenJTalk library unavailable: ${it.message}") }
                .isSuccess
        }
    }
}
