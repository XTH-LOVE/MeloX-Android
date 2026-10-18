package com.lladlam.melox.core.autosync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JaG2pTest {

    private fun phonemes(text: String): List<String> =
        JaG2p.units(text)?.flatMap { it.phonemes } ?: error("unsupported: $text")

    @Test
    fun kanaBecomesThePackPhonemeInventory() {
        assertEquals(listOf("k", "i", "m", "i", "n", "o"), phonemes("きみの"))
        assertEquals(listOf("n", "a", "m", "a", "e"), phonemes("なまえ"))
        assertEquals(listOf("k", "a"), phonemes("か"))
    }

    @Test
    fun contractedSoundsAndSpecialsAreMapped() {
        assertEquals(listOf("sh", "a"), phonemes("しゃ"))
        assertEquals(listOf("ky", "o"), phonemes("きょ"))
        assertEquals(listOf("ch", "u"), phonemes("ちゅ"))
        // っ → cl（促音）、ん → N
        assertEquals(listOf("g", "a", "cl", "k", "o"), phonemes("がっこ"))
        assertEquals(listOf("N", "N"), phonemes("んん"))
    }

    @Test
    fun katakanaIsNormalisedBeforeMapping() {
        assertEquals(phonemes("きみ"), phonemes("キミ"))
        // ん 在音素层是 N，不是 n
        assertEquals(listOf("r", "i", "N", "k", "o"), phonemes("リンコ"))
    }

    @Test
    fun everyUnitMapsBackToTheExactSourceCharacters() {
        val line = "きみのなまえをよぶ"
        val units = JaG2p.units(line) ?: error("unsupported")
        // 单元按顺序拼回原文，且不重叠
        var cursor = 0
        units.forEach { unit ->
            assertEquals(cursor, unit.start)
            assertEquals(line.substring(unit.start, unit.end), unit.text)
            cursor = unit.end
        }
        assertEquals(line.length, cursor)
    }

    @Test
    fun unsupportedInputIsReportedInsteadOfGuessed() {
        // 汉字需要 OpenJTalk + NAIST 词典，不能靠猜
        assertNull(JaG2p.units("君の名は"))
        assertFalse(JaG2p.isAvailable("君の名は"))
        // 长音符 ー 的拍处理未对拍，同样拒绝
        assertNull(JaG2p.units("らーめん"))
        // 拉丁词按字读需要参考实现，拒绝
        assertNull(JaG2p.units("きみの hello"))
        assertTrue(JaG2p.isAvailable("きみの"))
    }
}
