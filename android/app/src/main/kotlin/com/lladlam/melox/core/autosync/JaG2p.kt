package com.lladlam.melox.core.autosync

/**
 * Japanese grapheme-to-phoneme mapping for the `ctc-hubert-phoneme` pack.
 *
 * **Scope, deliberately limited and honest:** this converts *kana* into the
 * pack's 48-token phoneme inventory (pyopenjtalk style: `k`+`a`, `ky`+`a`,
 * `cl` for っ, `N` for ん). Anything that needs a dictionary or a声学 rule the
 * reference implementation owns — kanji readings (OpenJTalk + the NAIST
 * dictionary), Latin words, the long-vowel mark ー, and 長音/促音の拍整理 —
 * is reported as unsupported instead of being guessed, because a wrong phoneme
 * string would produce confident-looking but wrong timings.
 *
 * Each unit keeps the original characters it came from, so the mapping can
 * always reproduce the line's text exactly.
 */
internal object JaG2p {

    data class Unit(
        /** Source characters of the original line (1-2 kana). */
        val text: String,
        val phonemes: List<String>,
        /** Index into the line this unit starts at. */
        val start: Int,
        /** Exclusive end index. */
        val end: Int,
    )

    fun isAvailable(text: String): Boolean = units(text) != null

    /** @return units covering the whole line, or `null` when anything is unsupported. */
    fun units(text: String): List<Unit>? {
        if (text.isEmpty()) return null
        val hiragana = StringBuilder(text.length)
        for (character in text) {
            val code = character.code
            // 片假名 → 平假名（仅限可映射区间），其余原样保留以便识别为不支持。
            hiragana.append(if (code in 0x30A1..0x30F6) (code - 0x60).toChar() else character)
        }
        val value = hiragana.toString()
        val result = ArrayList<Unit>(value.length)
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (character.isWhitespace() || character == '　') {
                index++
                continue
            }
            if (character == 'っ') {
                result += Unit(character.toString(), listOf("cl"), index, index + 1)
                index++
                continue
            }
            val pair: String? = if (index + 1 < value.length) value.substring(index, index + 2) else null
            val combos: List<String>? = if (pair != null) YOON[pair] else null
            if (pair != null && combos != null) {
                result += Unit(pair, combos, index, index + 2)
                index += 2
                continue
            }
            val phonemes: List<String> = MORA[character] ?: return null
            result += Unit(character.toString(), phonemes, index, index + 1)
            index++
        }
        return result.takeIf { it.isNotEmpty() }
    }

    private fun mora(vararg phonemes: String) = phonemes.toList()

    /** Single-mora table (hiragana). */
    private val MORA: Map<Char, List<String>> = buildMap {
        this['あ'] = mora("a"); this['い'] = mora("i"); this['う'] = mora("u")
        this['え'] = mora("e"); this['お'] = mora("o")
        this['か'] = mora("k", "a"); this['き'] = mora("k", "i"); this['く'] = mora("k", "u")
        this['け'] = mora("k", "e"); this['こ'] = mora("k", "o")
        this['が'] = mora("g", "a"); this['ぎ'] = mora("g", "i"); this['ぐ'] = mora("g", "u")
        this['げ'] = mora("g", "e"); this['ご'] = mora("g", "o")
        this['さ'] = mora("s", "a"); this['し'] = mora("sh", "i"); this['す'] = mora("s", "u")
        this['せ'] = mora("s", "e"); this['そ'] = mora("s", "o")
        this['ざ'] = mora("z", "a"); this['じ'] = mora("j", "i"); this['ず'] = mora("z", "u")
        this['ぜ'] = mora("z", "e"); this['ぞ'] = mora("z", "o")
        this['た'] = mora("t", "a"); this['ち'] = mora("ch", "i"); this['つ'] = mora("ts", "u")
        this['て'] = mora("t", "e"); this['と'] = mora("t", "o")
        this['だ'] = mora("d", "a"); this['ぢ'] = mora("j", "i"); this['づ'] = mora("z", "u")
        this['で'] = mora("d", "e"); this['ど'] = mora("d", "o")
        this['な'] = mora("n", "a"); this['に'] = mora("ny", "i"); this['ぬ'] = mora("n", "u")
        this['ね'] = mora("n", "e"); this['の'] = mora("n", "o")
        this['は'] = mora("h", "a"); this['ひ'] = mora("hy", "i"); this['ふ'] = mora("f", "u")
        this['へ'] = mora("h", "e"); this['ほ'] = mora("h", "o")
        this['ば'] = mora("b", "a"); this['び'] = mora("b", "i"); this['ぶ'] = mora("b", "u")
        this['べ'] = mora("b", "e"); this['ぼ'] = mora("b", "o")
        this['ぱ'] = mora("p", "a"); this['ぴ'] = mora("p", "i"); this['ぷ'] = mora("p", "u")
        this['ぺ'] = mora("p", "e"); this['ぽ'] = mora("p", "o")
        this['ま'] = mora("m", "a"); this['み'] = mora("m", "i"); this['む'] = mora("m", "u")
        this['め'] = mora("m", "e"); this['も'] = mora("m", "o")
        this['や'] = mora("y", "a"); this['ゆ'] = mora("y", "u"); this['よ'] = mora("y", "o")
        this['ら'] = mora("r", "a"); this['り'] = mora("r", "i"); this['る'] = mora("r", "u")
        this['れ'] = mora("r", "e"); this['ろ'] = mora("r", "o")
        this['わ'] = mora("w", "a"); this['ゐ'] = mora("i"); this['ゑ'] = mora("e")
        this['を'] = mora("o")
        this['ん'] = mora("N")
        this['ゔ'] = mora("v", "u")
    }

    /** Two-character 拗音 combinations that map to a single contracted phoneme pair. */
    private val YOON: Map<String, List<String>> = mapOf(
        "きゃ" to mora("ky", "a"), "きゅ" to mora("ky", "u"), "きょ" to mora("ky", "o"),
        "ぎゃ" to mora("gy", "a"), "ぎゅ" to mora("gy", "u"), "ぎょ" to mora("gy", "o"),
        "しゃ" to mora("sh", "a"), "しゅ" to mora("sh", "u"), "しょ" to mora("sh", "o"),
        "じゃ" to mora("j", "a"), "じゅ" to mora("j", "u"), "じょ" to mora("j", "o"),
        "ちゃ" to mora("ch", "a"), "ちゅ" to mora("ch", "u"), "ちょ" to mora("ch", "o"),
        "にゃ" to mora("ny", "a"), "にゅ" to mora("ny", "u"), "にょ" to mora("ny", "o"),
        "ひゃ" to mora("hy", "a"), "ひゅ" to mora("hy", "u"), "ひょ" to mora("hy", "o"),
        "びゃ" to mora("by", "a"), "びゅ" to mora("by", "u"), "びょ" to mora("by", "o"),
        "ぴゃ" to mora("py", "a"), "ぴゅ" to mora("py", "u"), "ぴょ" to mora("py", "o"),
        "みゃ" to mora("my", "a"), "みゅ" to mora("my", "u"), "みょ" to mora("my", "o"),
        "りゃ" to mora("ry", "a"), "りゅ" to mora("ry", "u"), "りょ" to mora("ry", "o"),
        "ふぁ" to mora("f", "a"), "ふぃ" to mora("f", "i"), "ふぇ" to mora("f", "e"), "ふぉ" to mora("f", "o"),
        "てぃ" to mora("t", "i"), "でぃ" to mora("d", "i"),
        "しぇ" to mora("sh", "e"), "じぇ" to mora("j", "e"), "ちぇ" to mora("ch", "e"),
        "つぁ" to mora("ts", "a"), "つぃ" to mora("ts", "i"), "つぇ" to mora("ts", "e"), "つぉ" to mora("ts", "o"),
        "うぃ" to mora("w", "i"), "うぇ" to mora("w", "e"),
        "ゔぁ" to mora("v", "a"), "ゔぃ" to mora("v", "i"), "ゔぇ" to mora("v", "e"), "ゔぉ" to mora("v", "o"),
    )
}
