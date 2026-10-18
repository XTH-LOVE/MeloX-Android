package com.lladlam.melox.core.lyrics

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MeloXLyricPreferenceTest {

    @Test
    fun settingOverridesTheSystemLocale() {
        val system = Locale.forLanguageTag("zh-Hans-CN")

        val explicitTurkish = MeloXLyricPreference.fromSetting("tr", system)
        assertEquals("tr", explicitTurkish.languageTag)
        assertEquals(MeloXLyricScript.Original, explicitTurkish.script)

        val explicitTraditional = MeloXLyricPreference.fromSetting("zh-Hant", system)
        assertNull(explicitTraditional.languageTag)
        assertEquals(MeloXLyricScript.Traditional, explicitTraditional.script)

        val explicitSimplified = MeloXLyricPreference.fromSetting("zh-Hans", system)
        assertNull(explicitSimplified.languageTag)
        assertEquals(MeloXLyricScript.Simplified, explicitSimplified.script)
    }

    @Test
    fun systemSettingFollowsTheLocale() {
        val turkish = MeloXLyricPreference.fromSetting("system", Locale.forLanguageTag("tr-TR"))
        assertEquals("tr", turkish.languageTag)

        val traditionalChinese = MeloXLyricPreference.fromSetting("system", Locale.forLanguageTag("zh-Hant-TW"))
        assertNull(traditionalChinese.languageTag)
        assertEquals(MeloXLyricScript.Traditional, traditionalChinese.script)
    }

    @Test
    fun unknownOrBlankSettingsFallBackToTheSystemLocale() {
        val system = Locale.forLanguageTag("en-US")
        assertEquals(MeloXLyricPreference.system(system), MeloXLyricPreference.fromSetting("", system))
        assertEquals(MeloXLyricPreference.system(system), MeloXLyricPreference.fromSetting("   ", system))
        // 地区标签按基础语言匹配，这样 en-US 也能命中 en 轨
        assertEquals("en", MeloXLyricPreference.fromSetting("en-US", system).languageTag)
    }
}
