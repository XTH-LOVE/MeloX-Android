package com.lladlam.melox.core.provider.aurora

import android.text.Html
import android.util.Base64
import com.lladlam.melox.core.lyrics.LyricLine
import com.lladlam.melox.core.lyrics.LyricsDocument
import com.lladlam.melox.core.lyrics.LyricQuality
import com.lladlam.melox.core.lyrics.LyricSource
import com.lladlam.melox.core.music.model.AudioQualityTier
import com.lladlam.melox.core.music.model.MusicAlbumRef
import com.lladlam.melox.core.music.model.MusicArtistRef
import com.lladlam.melox.core.music.model.MusicPage
import com.lladlam.melox.core.music.model.MusicResourceId
import com.lladlam.melox.core.music.model.MusicSource
import com.lladlam.melox.core.music.model.MusicTrack
import com.lladlam.melox.core.music.model.PlaybackResolution
import com.lladlam.melox.core.music.model.ProviderTrackMetadata
import com.lladlam.melox.core.music.model.TrackAvailability
import com.lladlam.melox.core.network.MeloXHttpClient
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Joox uses AuroraMusic's GD endpoint. Hi歌 is the only source that needs
 * HTML parsing, so both are kept here as small source adapters and exposed
 * through the same provider-neutral UI contracts.
 */
class AuroraSourceClient(
    private val httpClient: OkHttpClient = MeloXHttpClient.shared,
    private val gdApiBase: String = GD_API_BASE,
    private val higequBase: String = HIGEQU_BASE,
) {
    private val higequDetails = ConcurrentHashMap<String, HigequDetail>()

    fun search(source: MusicSource, query: String, page: Int, pageSize: Int): MusicPage<MusicTrack> {
        require(source == MusicSource.Joox || source == MusicSource.Higequ)
        if (query.isBlank() || page < 1) return MusicPage(emptyList(), page.coerceAtLeast(1), pageSize.coerceAtLeast(1), 0L, false)
        return if (source == MusicSource.Joox) {
            searchJoox(query, page, pageSize)
        } else {
            searchHigequ(query, page)
        }
    }

    fun resolvePlayback(track: MusicTrack, quality: AudioQualityTier): PlaybackResolution {
        return when (track.id.source) {
            MusicSource.Joox -> {
                val source = resolveGdUrl("joox", track.providerId(), quality)
                    ?: resolveJooxCandidate(track, quality)
                    ?: return PlaybackResolution.Unavailable("Joox 没有返回可播放地址")
                PlaybackResolution.Playable(
                    url = source.url,
                    requestedQuality = quality,
                    actualQuality = source.actualQuality,
                    bitrate = source.bitrate,
                    format = source.format,
                    expiresAtEpochMs = System.currentTimeMillis() + URL_TTL_MS,
                )
            }
            MusicSource.Higequ -> {
                val detail = higequDetail(track.providerId())
                    ?: return PlaybackResolution.Unavailable("Hi歌没有返回可播放地址")
                detail.audioUrl.takeIf(::isHttpUrl)?.let {
                    PlaybackResolution.Playable(
                        url = it.replaceFirst("http://", "https://"),
                        requestedQuality = quality,
                        actualQuality = AudioQualityTier.Standard,
                        bitrate = 128,
                        format = "mp3",
                        expiresAtEpochMs = System.currentTimeMillis() + HIGEQU_TTL_MS,
                    )
                } ?: PlaybackResolution.Unavailable("Hi歌没有返回可播放地址")
            }
            else -> error("AuroraSourceClient cannot resolve ${track.id.source}")
        }
    }

    fun lyrics(track: MusicTrack): LyricsDocument {
        return when (track.id.source) {
            MusicSource.Joox -> {
                val json = requestGdJson(
                    mapOf("types" to "lyric", "source" to "joox", "id" to track.jooxLyricId()),
                )
                parseLrcDocument(toSimplifiedChinese(json.optString("lyric")), LyricSource.Provider)
            }
            MusicSource.Higequ -> {
                val detail = higequDetail(track.providerId())
                parseLrcDocument(detail?.lyric.orEmpty(), LyricSource.Provider)
            }
            else -> LyricsDocument(emptyList())
        }
    }

    private fun searchJoox(query: String, page: Int, pageSize: Int): MusicPage<MusicTrack> {
        val response = requestGdJson(
            mapOf(
                "types" to "search",
                "source" to "joox",
                "name" to query,
                "count" to pageSize.coerceIn(1, 50).toString(),
                "pages" to page.toString(),
            ),
        )
        val items = when {
            response.has("data") && response.opt("data") is JSONArray -> response.optJSONArray("data") ?: JSONArray()
            else -> JSONArray(response.toString())
        }
        val tracks = buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val id = item.value("id") ?: continue
                val urlId = item.value("url_id") ?: id
                val picId = item.value("pic_id")
                val lyricId = item.value("lyric_id") ?: urlId
                val title = toSimplifiedChinese(item.optString("name").trim()).takeIf(String::isNotBlank) ?: continue
                val artists = item.artists().map(::toSimplifiedChinese)
                val albumTitle = toSimplifiedChinese(item.optString("album").trim())
                val artwork = null
                add(
                    MusicTrack(
                        id = MusicResourceId(MusicSource.Joox, urlId),
                        title = title,
                        artists = artists.map { MusicArtistRef(name = it) }.ifEmpty { listOf(MusicArtistRef(name = "未知歌手")) },
                        album = albumTitle.takeIf(String::isNotBlank)?.let { MusicAlbumRef(name = it, artworkUrl = artwork) },
                        artworkUrl = artwork,
                        durationMs = item.optLong("duration").takeIf { it > 0L }?.let { if (it < 10_000L) it * 1000L else it },
                        availability = TrackAvailability.Playable,
                        providerMetadata = ProviderTrackMetadata.Joox(
                            trackId = urlId,
                            artworkId = picId ?: urlId,
                            lyricId = lyricId,
                        ),
                    ),
                )
            }
        }
        return MusicPage(tracks, page, pageSize, null, tracks.size >= pageSize)
    }

    private fun searchHigequ(query: String, page: Int): MusicPage<MusicTrack> {
        val html = requestHigequ("/s/${java.net.URLEncoder.encode(query, Charsets.UTF_8.name())}/${page.coerceAtLeast(1)}/")
        val matches = RID_PATTERN.findAll(html)
        val tracks = matches.mapNotNull { match ->
            val rid = match.groupValues[1].trim().takeIf(String::isNotBlank) ?: return@mapNotNull null
            val start = match.range.last + 1
            val end = html.indexOfNextRid(start).let { if (it < 0) html.length else it }
            val block = html.substring(start, end)
            val title = classText(block, "result-title").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val artist = classText(block, "result-artist")
            val album = classText(block, "result-album").replace(Regex("^专辑[:：]\\s*"), "")
            MusicTrack(
                id = MusicResourceId(MusicSource.Higequ, rid),
                title = title,
                artists = splitHigequArtists(artist).map { MusicArtistRef(name = it) }
                    .ifEmpty { listOf(MusicArtistRef(name = "未知歌手")) },
                album = album.takeIf(String::isNotBlank)?.let { MusicAlbumRef(name = it) },
                availability = TrackAvailability.Playable,
                providerMetadata = ProviderTrackMetadata.Higequ(rid),
            )
        }.toList()
        return MusicPage(tracks, page, 10, null, html.contains("id=\"next-page\"") && !html.contains("id=\"next-page\" disabled"))
    }

    fun artwork(track: MusicTrack): String? = when (track.id.source) {
        MusicSource.Joox -> {
            val picId = (track.providerMetadata as? ProviderTrackMetadata.Joox)?.artworkId
                ?: track.id.value
            runCatching {
                requestGdJson(mapOf("types" to "pic", "source" to "joox", "id" to picId, "size" to "640"))
                    .optString("url")
                    .takeIf(::isHttpUrl)
            }.getOrNull()
        }
        MusicSource.Higequ -> higequDetail(track.providerId())?.coverUrl?.takeIf(::isHttpUrl)
        else -> null
    }

    private fun resolveGdUrl(source: String, id: String, quality: AudioQualityTier): ResolvedGdSource? {
        val requested = when (quality) {
            AudioQualityTier.Standard -> 192
            else -> 320
        }
        listOf(requested, 128).distinct().forEach { bitrate ->
            val json = runCatching {
                requestGdJson(mapOf("types" to "url", "source" to source, "id" to id, "br" to bitrate.toString()))
            }.getOrNull() ?: return@forEach
            val url = json.optString("url").takeIf(::isHttpUrl) ?: return@forEach
            val actual = json.optInt("br", bitrate).takeIf { it > 0 } ?: bitrate
            return ResolvedGdSource(
                url = url,
                bitrate = actual,
                actualQuality = when {
                    actual >= 320 -> AudioQualityTier.High
                    else -> AudioQualityTier.Standard
                },
                format = url.substringBefore('?').substringAfterLast('.').takeIf(String::isNotBlank),
            )
        }
        return null
    }

    private fun resolveJooxCandidate(track: MusicTrack, quality: AudioQualityTier): ResolvedGdSource? {
        val query = listOf(track.title, track.artistText).filter(String::isNotBlank).joinToString(" ")
        if (query.isBlank()) return null
        val response = runCatching {
            requestGdJson(
                mapOf(
                    "types" to "search",
                    "source" to "joox",
                    "name" to query,
                    "count" to "8",
                    "pages" to "1",
                ),
            )
        }.getOrNull() ?: return null
        val items = response.optJSONArray("data") ?: return null
        val candidates = (0 until items.length()).mapNotNull { index ->
            val item = items.optJSONObject(index) ?: return@mapNotNull null
            val title = item.optString("name").trim()
            val id = item.value("url_id") ?: item.value("id") ?: return@mapNotNull null
            Triple(title, item.artists(), id)
        }.filter { normalizeSourceText(it.first) == normalizeSourceText(track.title) }
            .sortedByDescending { (_, artists, _) ->
                val targetArtist = track.artists.firstOrNull()?.name.orEmpty()
                artists.any { artist -> normalizeSourceText(artist) == normalizeSourceText(targetArtist) }
            }
            .take(3)
        candidates.forEach { (_, _, id) ->
            resolveGdUrl("joox", id, quality)?.let { return it }
        }
        return resolveViaHigequ(track, quality)
    }

    private fun resolveViaHigequ(track: MusicTrack, quality: AudioQualityTier): ResolvedGdSource? {
        val query = listOf(track.title, track.artistText).filter(String::isNotBlank).joinToString(" ")
        val results = runCatching { searchHigequ(query, 1).items }.getOrNull().orEmpty()
        val targetTitle = normalizeSourceText(track.title)
        val candidate = results.firstOrNull { normalizeSourceText(it.title) == targetTitle } ?: results.firstOrNull()
        val url = candidate?.let { higequDetail(it.id.value)?.audioUrl }?.takeIf(::isHttpUrl) ?: return null
        return ResolvedGdSource(
            url = url.replaceFirst("http://", "https://"),
            bitrate = 128,
            actualQuality = AudioQualityTier.Standard,
            format = "mp3",
        )
    }

    private fun higequDetail(rid: String): HigequDetail? {
        higequDetails[rid]?.let { return it }
        val html = runCatching { requestHigequ("/player/${java.net.URLEncoder.encode(rid, Charsets.UTF_8.name())}/") }.getOrNull()
            ?: return null
        val code = CODE_PATTERN.find(html)?.groupValues?.get(1)
        val audioUrl = code?.let {
            runCatching { String(Base64.decode(it, Base64.DEFAULT), Charsets.UTF_8) }.getOrNull().orEmpty()
        }.orEmpty().takeIf(::isHttpUrl).orEmpty()
        val coverUrl = SRC_PATTERN.find(html)?.groupValues?.get(1).orEmpty()
            .replaceFirst("http://", "https://")
        val lyric = LYRIC_PATTERN.findAll(html).mapNotNull { match ->
            val seconds = match.groupValues[1].toDoubleOrNull() ?: return@mapNotNull null
            val text = cleanHtml(match.groupValues[2])
            if (text.isBlank()) null else "[${formatLrcTime(seconds)}]$text"
        }.joinToString("\n")
        return HigequDetail(rid, audioUrl, coverUrl, lyric).also { higequDetails[rid] = it }
    }

    private fun requestGdJson(parameters: Map<String, String>): JSONObject {
        val url = gdApiBase.toHttpUrl().newBuilder().apply {
            parameters.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val body = request(url, "MeloX/0.8.8").ifBlank { throw IOException("GD 音源返回空响应") }
        return runCatching { JSONObject(body) }.getOrElse {
            runCatching { JSONObject().put("data", JSONArray(body)) }.getOrElse { throw IOException("GD 音源返回无效数据", it) }
        }
    }

    private fun requestHigequ(path: String): String =
        request((higequBase.trimEnd('/') + path).toHttpUrl(), HIGEQU_UA)

    private fun request(url: okhttp3.HttpUrl, userAgent: String): String {
        val request = Request.Builder().url(url).header("User-Agent", userAgent).header("Accept", "text/html,application/json,*/*").build()
        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("音源请求失败：HTTP ${response.code}")
            response.body.string()
        }
    }

    private fun parseLrcDocument(raw: String, source: LyricSource): LyricsDocument {
        val lines = raw.lineSequence().mapNotNull { line ->
            val match = LRC_PATTERN.find(line) ?: return@mapNotNull null
            val minutes = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val seconds = match.groupValues[2].toDoubleOrNull() ?: return@mapNotNull null
            val text = match.groupValues[3].trim()
            if (text.isBlank()) null else LyricLine((minutes * 60_000 + (seconds * 1000).toLong()), text = text)
        }.sortedBy(LyricLine::timeMs).toList()
        return LyricsDocument(lines, source = source, quality = LyricQuality.LineSynchronized, pseudoTimingAllowed = true)
    }

    private fun JSONObject.value(key: String): String? =
        opt(key).takeIf { it != JSONObject.NULL }?.toString()?.trim()?.takeIf(String::isNotBlank)

    private fun JSONObject.artists(): List<String> {
        val value = opt("artist")
        return when (value) {
            is JSONArray -> (0 until value.length()).mapNotNull { value.optString(it).trim().takeIf(String::isNotBlank) }
            else -> value?.toString()?.split('/', '&')?.map(String::trim)?.filter(String::isNotBlank).orEmpty()
        }
    }

    private fun String.indexOfNextRid(fromIndex: Int): Int =
        RID_PATTERN.find(this, fromIndex)?.range?.first ?: -1

    private fun classText(block: String, className: String): String {
        val match = Regex("""(?is)<[^>]*class=["'][^"']*\b$className\b[^"']*["'][^>]*>(.*?)</[^>]+>""").find(block)
        return cleanHtml(match?.groupValues?.get(1).orEmpty())
    }

    private fun cleanHtml(value: String): String =
        Html.fromHtml(value.replace(Regex("(?is)<script.*?</script>"), ""), Html.FROM_HTML_MODE_LEGACY)
            .toString().replace(Regex("\\s+"), " ").trim()

    private fun formatLrcTime(seconds: Double): String {
        val safe = seconds.coerceAtLeast(0.0)
        val minutes = (safe / 60).toInt()
        val remainder = safe - minutes * 60
        return "%02d:%05.2f".format(java.util.Locale.US, minutes, remainder)
    }

    private fun isHttpUrl(value: String): Boolean =
        value.startsWith("http://", true) || value.startsWith("https://", true)

    private fun jooxArtworkUrl(picId: String?): String? =
        picId?.trim()?.takeIf(String::isNotBlank)?.let {
            "https://image.joox.com/JOOXcover/0/$it/640"
        }

    private fun normalizeSourceText(value: String): String =
        value.lowercase().replace(Regex("[\\s\\p{Punct}（）()《》【】「」]"), "")

    private fun splitHigequArtists(raw: String): List<String> {
        val value = raw.trim()
        if (value.isBlank()) return emptyList()
        if (!value.contains('&') || value.contains(" & ")) return listOf(value)
        return value.split('&').map(String::trim).filter(String::isNotBlank)
    }

    private fun toSimplifiedChinese(value: String): String =
        runCatching {
            android.icu.text.Transliterator.getInstance("Traditional-Simplified").transliterate(value)
        }.getOrDefault(value)

    private data class ResolvedGdSource(
        val url: String,
        val bitrate: Int,
        val actualQuality: AudioQualityTier,
        val format: String?,
    )

    private data class HigequDetail(
        val rid: String,
        val audioUrl: String,
        val coverUrl: String,
        val lyric: String,
    )

    companion object {
        const val GD_API_BASE = "https://music-api.gdstudio.xyz/api.php"
        const val HIGEQU_BASE = "https://higequ.com"
        private const val HIGEQU_UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36"
        private const val URL_TTL_MS = 15 * 60 * 1000L
        private const val HIGEQU_TTL_MS = 10 * 60 * 1000L
        private val RID_PATTERN = Regex("""(?is)data-rid\s*=\s*["']([^"']+)["']""")
        private val CODE_PATTERN = Regex("""(?is)let\s+code\s*=\s*["']([^"']+)["']""")
        private val SRC_PATTERN = Regex("""(?is)id\s*=\s*["']album-cover["'][^>]*src\s*=\s*["']([^"']+)["']""")
        private val LYRIC_PATTERN = Regex("""(?is)<[^>]*class=["'][^"']*\blyric-line\b[^"']*["'][^>]*data-time\s*=\s*["']([^"']+)["'][^>]*>(.*?)</[^>]+>""")
        private val LRC_PATTERN = Regex("""^\s*\[(\d{1,2}):(\d{1,2}(?:[.:]\d{1,3})?)\](.*)$""")
    }
}

private fun MusicTrack.providerId(): String =
    when (val metadata = providerMetadata) {
        is ProviderTrackMetadata.Joox -> metadata.trackId
        is ProviderTrackMetadata.Higequ -> metadata.rid
        else -> id.value
    }

private fun MusicTrack.jooxLyricId(): String =
    (providerMetadata as? ProviderTrackMetadata.Joox)?.lyricId ?: id.value
