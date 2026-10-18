package com.lladlam.melox.core.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtmlLyricsParserTest {
    @Test
    fun parsesTimedSyllablesTranslationsRomanizationAgentsAndBackground() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xmlns:itunes="http://music.apple.com/lyric-ttml-internal">
              <head><metadata><ttm:agent xml:id="v1"/><ttm:agent xml:id="v2"/></metadata></head>
              <body><div>
                <p begin="00:00:01.000" end="00:00:03.000" ttm:agent="v2" itunes:key="L1">
                  <span begin="00:00:01.000" end="00:00:01.500">你</span><span begin="00:00:01.500" end="00:00:02.000">好</span>
                  <span ttm:role="x-translation" xml:lang="zh-CN">你好（Hello）</span>
                  <span ttm:role="x-roman">ni hao</span>
                  <span ttm:role="x-bg" begin="00:00:02.000" end="00:00:02.800"><span begin="00:00:02.000" end="00:00:02.400">和</span><span begin="00:00:02.400" end="00:00:02.800">声</span></span>
                </p>
              </div></body>
            </tt>
        """.trimIndent()

        val document = TtmlLyricsParser.parse(ttml)
        val line = document.lines.single()
        assertEquals("你好", line.text)
        assertEquals(2, line.syllables.size)
        assertEquals("你好（Hello）", line.translation)
        assertEquals("ni hao", line.romanization)
        assertEquals(LyricAgentAlignment.Flipped, line.agent?.alignment)
        assertEquals("和声", line.accompaniment.single().text)
        assertEquals(LyricQuality.Authored, document.quality)
        assertTrue(!document.pseudoTimingAllowed)
    }

    @Test
    fun selectsAmllTraditionalTranslationWhenRequested() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xmlns:itunes="http://music.apple.com/lyric-ttml-internal">
              <body><div>
                <p begin="00:00:01.000" end="00:00:02.000" itunes:key="L1">
                  <span begin="00:00:01.000" end="00:00:02.000">Hello</span>
                  <span ttm:role="x-translation" xml:lang="zh-Hans">你好</span>
                  <span ttm:role="x-translation" xml:lang="zh-Hant">你好嗎</span>
                </p>
              </div></body>
            </tt>
        """.trimIndent()

        assertEquals("你好嗎", TtmlLyricsParser.parse(ttml, MeloXLyricScript.Traditional).lines.single().translation)
        assertEquals("你好", TtmlLyricsParser.parse(ttml, MeloXLyricScript.Simplified).lines.single().translation)
    }

    @Test
    fun selectsTheSystemLanguageTranslationForNonChineseLocales() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xmlns:itunes="http://music.apple.com/lyric-ttml-internal">
              <body><div>
                <p begin="00:00:01.000" end="00:00:02.000" itunes:key="L1">
                  <span begin="00:00:01.000" end="00:00:02.000">Merhaba</span>
                  <span ttm:role="x-translation" xml:lang="zh-Hans">你好</span>
                  <span ttm:role="x-translation" xml:lang="en">Hello</span>
                  <span ttm:role="x-translation" xml:lang="tr">Merhaba</span>
                </p>
              </div></body>
            </tt>
        """.trimIndent()

        fun translationFor(language: String?) =
            TtmlLyricsParser.parse(ttml, MeloXLyricPreference(language, MeloXLyricScript.Original))
                .lines.single().translation

        assertEquals("Merhaba", translationFor("tr"))
        assertEquals("Hello", translationFor("en"))
        // 地区变体也算匹配（en-US → en）
        assertEquals("Hello", translationFor("en"))
        // 没有目标语言时优先非中文翻译，而不是把中文翻译塞给外语用户
        assertEquals("Hello", translationFor("de"))
        // 只有中文翻译时仍然保留（不因偏好而丢翻译）
        val chineseOnly = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xmlns:itunes="http://music.apple.com/lyric-ttml-internal">
              <body><div>
                <p begin="00:00:01.000" end="00:00:02.000" itunes:key="L1">
                  <span begin="00:00:01.000" end="00:00:02.000">Hello</span>
                  <span ttm:role="x-translation" xml:lang="zh-Hans">你好</span>
                </p>
              </div></body>
            </tt>
        """.trimIndent()
        assertEquals(
            "你好",
            TtmlLyricsParser.parse(chineseOnly, MeloXLyricPreference("tr", MeloXLyricScript.Original))
                .lines.single().translation,
        )
    }

    @Test
    fun officialApiLookupPrefersPlatformId() {
        assertEquals("ncmMusicId", AmlldbLyricQuery.Netease(186016).officialParameter()?.first)
        assertEquals("186016", AmlldbLyricQuery.Netease(186016).officialParameter()?.second)
        assertEquals("qqMusicId", AmlldbLyricQuery.QQ("97773").officialParameter()?.first)
        assertEquals(listOf("ncm-lyrics/186016.ttml"), AmlldbLyricQuery.Netease(186016).rawPaths())
        assertEquals("spotify:abc", AmlldbLyricQuery.Spotify("abc").bindingValue())
    }

}
