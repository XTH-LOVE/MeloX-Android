package com.lladlam.melox.core.audio

import com.lladlam.melox.core.music.model.AudioQualityTier
import java.io.IOException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Resolves NetEase streams through the same GD Studio source used by
 * AuroraMusic. This is intentionally playback-only: catalogue, login,
 * playlists and lyrics remain on MeloX's native NetEase clients.
 */
class NeteaseGdAudioClient(
    private val httpClient: OkHttpClient = com.lladlam.melox.core.network.MeloXHttpClient.shared,
    private val apiBase: String = DEFAULT_API_BASE,
) {
    fun playbackSourceBlocking(songId: Long, quality: MusicQuality): NeteasePlaybackSource =
        resolve(songId, bitrateFor(quality))

    fun playbackSourceBlocking(songId: Long, quality: AudioQualityTier): NeteasePlaybackSource =
        resolve(songId, bitrateFor(quality))

    private fun resolve(songId: Long, requestedBitrate: Int): NeteasePlaybackSource {
        require(songId > 0L) { "invalid NetEase song id: $songId" }
        var lastError: Throwable? = null
        for (bitrate in bitrateCandidates(requestedBitrate)) {
            try {
                val source = request(songId, bitrate)
                if (source != null) return source
            } catch (error: Exception) {
                lastError = error
            }
        }
        throw NeteasePlaybackUnavailableException(
            "GD 网易云音源未返回歌曲 $songId 的可播放地址",
            lastError,
        )
    }

    private fun request(songId: Long, bitrate: Int): NeteasePlaybackSource? {
        val url = apiBase.toHttpUrl().newBuilder()
            .addQueryParameter("types", "url")
            .addQueryParameter("source", "netease")
            .addQueryParameter("id", songId.toString())
            .addQueryParameter("br", bitrate.toString())
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "MeloX/0.6.5")
            .header("Accept", "application/json, text/plain, */*")
            .build()
        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("GD 网易云音源请求失败：HTTP ${response.code}")
            }
            val body = response.body.string()
            val json = runCatching { JSONObject(body) }
                .getOrElse { throw IOException("GD 网易云音源返回了无效数据", it) }
            val streamUrl = json.optString("url")
                .takeIf { it.startsWith("http://") || it.startsWith("https://") }
                ?: return@use null
            val actualBitrate = json.optInt("br", bitrate).takeIf { it > 0 } ?: bitrate
            NeteasePlaybackSource(
                url = streamUrl.replaceFirst("http://", "https://"),
                bitrate = actualBitrate,
                format = streamUrl.substringBefore('?').substringAfterLast('.', "").lowercase().takeIf(String::isNotBlank),
                quality = actualBitrate.toMusicQuality(),
                expiresAtEpochMs = System.currentTimeMillis() + URL_TTL_MS,
            )
        }
    }

    companion object {
        const val DEFAULT_API_BASE = "https://music-api.gdstudio.xyz/api.php"
        private const val URL_TTL_MS = 5 * 60 * 1000L

        internal fun bitrateCandidates(requestedBitrate: Int): List<Int> {
            val ladder = when {
                requestedBitrate >= 999 -> listOf(320, 128)
                requestedBitrate >= 320 -> listOf(320, 128)
                requestedBitrate >= 192 -> listOf(192, 128)
                else -> listOf(128)
            }
            return ladder.distinct()
        }

        internal fun bitrateFor(quality: MusicQuality): Int = when (quality) {
            MusicQuality.Standard -> 192
            MusicQuality.High -> 320
            MusicQuality.Lossless,
            MusicQuality.HiResolution,
            MusicQuality.HighDefinitionSurround,
            MusicQuality.ImmersiveSurround,
            MusicQuality.UltraClearMaster -> 999
        }

        internal fun bitrateFor(quality: AudioQualityTier): Int = when (quality) {
            AudioQualityTier.Standard -> 192
            AudioQualityTier.High -> 320
            AudioQualityTier.Lossless,
            AudioQualityTier.HiResolution,
            AudioQualityTier.Immersive,
            AudioQualityTier.Master -> 999
        }
    }
}

private fun Int.toMusicQuality(): MusicQuality = when {
    this >= 999 -> MusicQuality.Lossless
    this >= 320 -> MusicQuality.High
    else -> MusicQuality.Standard
}
