package com.lladlam.melox.core.autosync

/**
 * Top-level JNI declarations for the OpenJTalk G2P frontend.
 *
 * They live in their own file so the JVM class name is stable and predictable
 * (`JaOpenJtalkJniKt`), matching the symbol names in `ja_g2p_jni.cpp`.
 * Returns `null` when the handle is closed; the caller decides what to do.
 */
internal external fun nativeOpen(dictionaryDirectory: String): Long

internal external fun nativeClose(handle: Long)

/** Space-separated phonemes, exactly `pyopenjtalk.g2p(text)` (no boundary pau). */
internal external fun nativePhonemes(handle: Long, text: String): String?

/** Katakana reading, exactly `pyopenjtalk.g2p(text, kana=True)`. */
internal external fun nativeKana(handle: Long, text: String): String?

/** `surface\tpron` lines, exactly `pyopenjtalk.run_frontend(text)`. */
internal external fun nativeTokens(handle: Long, text: String): String?
