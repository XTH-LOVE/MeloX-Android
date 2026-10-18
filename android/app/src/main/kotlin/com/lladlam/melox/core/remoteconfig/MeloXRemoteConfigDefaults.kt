package com.lladlam.melox.core.remoteconfig

object MeloXRemoteConfigDefaults {
    val AllowedCapabilities = setOf(
        "netease_phone_login",
        "qq_phone_login",
        "qq_playback",
        "kugou_playback",
        "bilibili_playback",
        "spotify_oauth",
        "cross_provider_fallback",
    )
    val AllowedProviders = setOf("qq_music", "kugou", "bilibili")
    val FallbackOrder = listOf("qq_music", "kugou", "bilibili")
    val Strategies = mapOf(
        "netease_playback" to "v1",
        "qq_playback" to "v1",
        "kugou_playback" to "v1",
    )

    /**
     * Values a strategy may take. `compat` caps NetEase requests at 高品质 and
     * exists so lossless/immersive endpoints can be abandoned without an APK.
     */
    val AllowedStrategyValues = mapOf(
        "netease_playback" to setOf("v1", "compat"),
        "qq_playback" to setOf("v1"),
        "kugou_playback" to setOf("v1"),
    )

    /** Only files under this prefix may be referenced by the updater. */
    const val PinnedReleasePrefix = "https://github.com/XTH-LOVE/MeloX-Android/releases/download/"

    val Rules = MeloXRemoteRules(
        lyricMatchOriginalToleranceMs = 2_000L,
        lyricMatchVersionToleranceMs = 1_000L,
        searchTitleStripSuffixes = emptyList(),
    )
    val Config = MeloXRemoteConfig(
        schemaVersion = 1,
        configVersion = 0,
        issuedAtEpochSeconds = 0L,
        minVersionCode = 1,
        maxVersionCode = Int.MAX_VALUE,
        disabledCapabilities = emptySet(),
        strategies = Strategies,
        fallback = MeloXRemoteFallbackConfig(
            enabled = true,
            order = FallbackOrder,
            disabledProviders = emptySet(),
            timeoutMs = 6_000,
        ),
        notice = null,
        textOverrides = emptyMap(),
        scripts = emptyMap(),
        colorOverrides = emptyMap(),
        rules = Rules,
        updatePatches = emptyList(),
    )
}
