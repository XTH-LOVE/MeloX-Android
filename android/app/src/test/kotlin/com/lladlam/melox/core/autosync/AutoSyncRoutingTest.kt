package com.lladlam.melox.core.autosync

import org.junit.Assert.*
import org.junit.Test

class AutoSyncRoutingTest {
    @Test fun modelReleaseIsPinnedToBetaFour() {
        assertEquals("android-models-v0.1.0-beta.4", AutoSyncModelRepository.RELEASE_TAG)
        assertEquals(
            "https://github.com/lladlam/MeloX-AutoSync/releases/download/android-models-v0.1.0-beta.4/",
            AutoSyncModelRepository.RELEASE_PREFIX,
        )
        // Pinned hash of the published beta.4 index (14 093 bytes).
        assertEquals(
            "4b7b54a6f436b4f74ac6028f3876477a2d7dffe13fba557f9649577354a0f0ed",
            AutoSyncModelRepository.INDEX_SHA256,
        )
    }

    @Test fun japaneseCannotFallThroughToChineseCif() {
        assertNull(AutoSyncText.detectLanguage(listOf("君の知らない物語", "空を見上げている")))
    }

    @Test fun chineseWithEnglishLineKeepsCifAndRefusesThatMixedLine() {
        assertEquals("zh", AutoSyncText.detectLanguage(listOf("天空之中飘落的雨滴", "诉说着那些遥远的故事", "你好 hello")))
        assertFalse(ZhText.isAlignableForChinese("你好 hello"))
        assertFalse(ZhText.isAlignableForChinese("你好 OK"))
    }

    @Test fun linesAreRoutedIndividuallyInMixedSongs() {
        // 整首偏中文，但纯英文行必须走英文引擎，而不是被中文包拒收后静默回退。
        assertEquals(AutoSyncLineLanguage.Zh, AutoSyncText.detectLineLanguage("天空之中飘落的雨滴"))
        assertEquals(AutoSyncLineLanguage.En, AutoSyncText.detectLineLanguage("Falling through the sky tonight"))
        assertEquals(AutoSyncLineLanguage.Ja, AutoSyncText.detectLineLanguage("君の知らない物語"))
        // 中英混排的行两套包都会丢字，按未知处理（保持逐行时间）。
        assertEquals(AutoSyncLineLanguage.Unknown, AutoSyncText.detectLineLanguage("你好 hello"))
        assertEquals(AutoSyncLineLanguage.Unknown, AutoSyncText.detectLineLanguage("1234 …"))
    }

    @Test fun creditsAndInstrumentalsAreNeverRouted() {
        assertEquals(AutoSyncLineLanguage.Unknown, AutoSyncText.detectLineLanguage("♪"))
        assertFalse(AutoSyncText.isAlignable("作词：某人"))
        assertFalse(AutoSyncText.isAlignable("间奏"))
        assertFalse(AutoSyncText.isAlignable("[Music]"))
        assertTrue(AutoSyncText.isAlignable("Falling through the sky"))
    }
}
