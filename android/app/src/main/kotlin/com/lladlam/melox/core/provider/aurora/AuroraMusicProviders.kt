package com.lladlam.melox.core.provider.aurora

import com.lladlam.melox.core.music.model.AudioQualityTier
import com.lladlam.melox.core.music.model.MusicPage
import com.lladlam.melox.core.music.model.MusicSource
import com.lladlam.melox.core.music.model.MusicTrack
import com.lladlam.melox.core.music.model.PlaybackResolution
import com.lladlam.melox.core.music.provider.LyricsCapability
import com.lladlam.melox.core.music.provider.MusicCapability
import com.lladlam.melox.core.music.provider.MusicProvider
import com.lladlam.melox.core.music.provider.PlaybackCapability
import com.lladlam.melox.core.music.provider.SearchCapability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

class JooxProvider(
    private val client: AuroraSourceClient = AuroraSourceClient(),
) : MusicProvider, SearchCapability, PlaybackCapability, LyricsCapability {
    override val source = MusicSource.Joox
    override val displayName = source.displayName
    override val capabilities = setOf(MusicCapability.Search, MusicCapability.Playback, MusicCapability.Lyrics)

    override suspend fun searchSongs(query: String, page: Int, pageSize: Int): MusicPage<MusicTrack> =
        withContext(Dispatchers.IO) {
            val result = client.search(source, query, page, pageSize)
            result.copy(items = result.items.enrichArtwork(client))
        }

    override suspend fun resolvePlayback(track: MusicTrack, quality: AudioQualityTier): PlaybackResolution =
        withContext(Dispatchers.IO) { client.resolvePlayback(track, quality) }

    override suspend fun lyrics(track: MusicTrack) =
        withContext(Dispatchers.IO) { client.lyrics(track) }
}

class HigequProvider(
    private val client: AuroraSourceClient = AuroraSourceClient(),
) : MusicProvider, SearchCapability, PlaybackCapability, LyricsCapability {
    override val source = MusicSource.Higequ
    override val displayName = source.displayName
    override val capabilities = setOf(MusicCapability.Search, MusicCapability.Playback, MusicCapability.Lyrics)

    override suspend fun searchSongs(query: String, page: Int, pageSize: Int): MusicPage<MusicTrack> =
        withContext(Dispatchers.IO) {
            val result = client.search(source, query, page, pageSize)
            result.copy(items = result.items.enrichArtwork(client))
        }

    override suspend fun resolvePlayback(track: MusicTrack, quality: AudioQualityTier): PlaybackResolution =
        withContext(Dispatchers.IO) { client.resolvePlayback(track, quality) }

    override suspend fun lyrics(track: MusicTrack) =
        withContext(Dispatchers.IO) { client.lyrics(track) }
}

private suspend fun List<MusicTrack>.enrichArtwork(client: AuroraSourceClient): List<MusicTrack> =
    coroutineScope {
        map { track ->
            async {
                val artwork = runCatching { client.artwork(track) }.getOrNull()
                if (artwork.isNullOrBlank()) track else track.copy(
                    artworkUrl = artwork,
                    album = track.album?.copy(artworkUrl = artwork),
                )
            }
        }.map { it.await() }
    }
