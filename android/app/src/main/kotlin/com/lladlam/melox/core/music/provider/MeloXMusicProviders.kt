package com.lladlam.melox.core.music.provider

import android.content.Context
import com.lladlam.melox.core.account.NeteaseSessionStore
import com.lladlam.melox.core.provider.applemusic.AppleMusicApiClient
import com.lladlam.melox.core.provider.applemusic.AppleMusicSessionStore
import com.lladlam.melox.core.provider.kugou.KugouProvider
import com.lladlam.melox.core.provider.kugou.KugouSessionStore
import com.lladlam.melox.core.provider.kuwo.KuwoProvider
import com.lladlam.melox.core.provider.kuwo.KuwoSessionStore
import com.lladlam.melox.core.provider.netease.NeteaseProvider
import com.lladlam.melox.core.provider.aurora.JooxProvider
import com.lladlam.melox.core.provider.aurora.HigequProvider
import com.lladlam.melox.core.provider.qqmusic.QQMusicProvider
import com.lladlam.melox.core.provider.qqmusic.QQMusicSessionStore
import com.lladlam.melox.core.provider.bilibili.BilibiliProvider
import com.lladlam.melox.core.provider.bilibili.BilibiliSessionStore
import com.lladlam.melox.core.provider.bilibili.BilibiliPlaybackAssociationStore
import com.lladlam.melox.core.provider.bilibili.BilibiliApiCache
import com.lladlam.melox.core.provider.spotify.SpotifyClientConfig
import com.lladlam.melox.core.provider.spotify.SpotifyProvider
import com.lladlam.melox.core.provider.youtubemusic.YouTubeMusicProvider
import com.lladlam.melox.core.provider.jellyfin.JellyfinProvider
import com.lladlam.melox.core.provider.jellyfin.JellyfinSessionStore
import com.lladlam.melox.core.provider.local.LocalProvider
import com.lladlam.melox.core.network.MeloXHttpClient
import okhttp3.OkHttpClient

/** Creates provider instances that all read their authentication state locally. */
object MeloXMusicProviders {
    @Volatile
    private var sharedRegistry: MusicProviderRegistry? = null

    fun create(
        context: Context,
        httpClient: OkHttpClient = MeloXHttpClient.shared,
    ): MusicProviderRegistry {
        val appContext = context.applicationContext
        if (httpClient !== MeloXHttpClient.shared) return buildRegistry(appContext, httpClient)
        return sharedRegistry ?: synchronized(this) {
            sharedRegistry ?: buildRegistry(appContext, httpClient).also { sharedRegistry = it }
        }
    }

    /** Registry reserved for URL resolution and quality probing. */
    fun createPlayback(
        context: Context,
        httpClient: OkHttpClient = MeloXHttpClient.shared,
    ): MusicProviderRegistry {
        val appContext = context.applicationContext
        val nativeProviders = listOf<MusicProvider>(
                NeteaseProvider({ PlaybackAccountStore.neteaseCookie(appContext) }, httpClient),
                JooxProvider(com.lladlam.melox.core.provider.aurora.AuroraSourceClient(httpClient)),
                HigequProvider(com.lladlam.melox.core.provider.aurora.AuroraSourceClient(httpClient)),
                LocalProvider(appContext),
        )
        return MusicProviderRegistry(nativeProviders)
    }

    private fun buildRegistry(context: Context, httpClient: OkHttpClient): MusicProviderRegistry {
        val nativeProviders = listOf<MusicProvider>(
                NeteaseProvider(
                    cookieProvider = { NeteaseSessionStore.readCookie(context) },
                    httpClient = httpClient,
                ),
                JooxProvider(com.lladlam.melox.core.provider.aurora.AuroraSourceClient(httpClient)),
                HigequProvider(com.lladlam.melox.core.provider.aurora.AuroraSourceClient(httpClient)),
                LocalProvider(context),
        )
        return MusicProviderRegistry(nativeProviders)
    }
}
