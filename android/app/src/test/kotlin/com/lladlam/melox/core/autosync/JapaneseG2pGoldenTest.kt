package com.lladlam.melox.core.autosync

import java.io.File
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Host-native golden test: the Kotlin mapper + the OpenJTalk JNI must reproduce
 * `MeloX-AutoSync/android_export/ja/g2p_reference.py` (pyopenjtalk chain)
 * exactly, character by character.
 *
 * Gated on the host build of the native library so CI without a C++ toolchain
 * stays green:
 *
 *   cmake -S android/app/src/main/cpp -B /tmp/g2p-host -DCMAKE_BUILD_TYPE=Release \
 *         -DJAVA_HOME=$JAVA_HOME
 *   cmake --build /tmp/g2p-host -j
 *   MELOX_JA_G2P_HOST_LIB=/tmp/g2p-host/libmelox_ja_g2p.so \
 *   MELOX_JA_G2P_DIC=/path/open_jtalk_dic_utf_8-1.11 \
 *     gradle :app:testDebugUnitTest --tests '*JapaneseG2pGoldenTest*'
 */
class JapaneseG2pGoldenTest {

    private fun vectors(): List<JSONObject> {
        val stream = javaClass.classLoader!!.getResourceAsStream("autosync/ja_g2p_vectors.json")
            ?: error("missing golden vectors")
        val root = JSONObject(stream.readBytes().decodeToString())
        val array = root.getJSONArray("vectors")
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    @Test
    fun reproducesReferenceReadingsForEveryVector() {
        val library = System.getenv("MELOX_JA_G2P_HOST_LIB")
        val dictionary = System.getenv("MELOX_JA_G2P_DIC")
        assumeTrue(
            "set MELOX_JA_G2P_HOST_LIB and MELOX_JA_G2P_DIC to run the native G2P test",
            library != null && dictionary != null,
        )
        JaOpenJtalk.overrideLibraryPath = library
        assertTrue(JaOpenJtalk.isLibraryAvailable())
        assertTrue("dictionary must open: $dictionary", JaOpenJtalk.open(File(dictionary!!)))

        var checked = 0
        vectors().forEach { vector ->
            val text = vector.getString("text")
            val result = JapaneseG2pMapper.map(text)
                ?: error("mapper unavailable for $text")

            // 原文必须能无损重建
            assertEquals("reconstruct($text)", text, result.reconstruct())
            assertEquals("reconstruct field($text)", vector.getString("reconstruct"), result.reconstruct())

            val expectedPhonemes = vector.getJSONArray("phonemes").let { array ->
                (0 until array.length()).map { array.getString(it) }
            }
            assertEquals("phonemes($text)", expectedPhonemes, result.phonemes)

            val expectedReadings = vector.getJSONArray("readings")
            assertEquals("reading count($text)", expectedReadings.length(), result.readings.size)
            for (index in 0 until expectedReadings.length()) {
                val expected = expectedReadings.getJSONObject(index)
                val actual = result.readings[index]
                val where = "$text[$index]"
                assertEquals("char($where)", expected.getString("char"), actual.char)
                assertEquals("kana($where)", expected.getString("kana"), actual.kana)
                assertEquals(
                    "phonemes($where)",
                    (0 until expected.getJSONArray("phonemes").length())
                        .map { expected.getJSONArray("phonemes").getString(it) },
                    actual.phonemes,
                )
                assertEquals("uncertain($where)", expected.getBoolean("uncertain"), actual.uncertain)
            }
            checked++
        }
        assertEquals("vector count", vectors().size, checked)
    }

    companion object {
        @AfterClass
        @JvmStatic
        fun tearDown() {
            JaOpenJtalk.close()
            JaOpenJtalk.overrideLibraryPath = null
        }
    }
}
