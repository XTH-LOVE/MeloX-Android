package com.lladlam.melox.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.lladlam.melox.core.audio.MusicQuality
import com.lladlam.melox.core.audio.MusicQualityPreferences
import com.lladlam.melox.core.audio.MusicQualityRuntime
import com.lladlam.melox.core.download.MeloXDownloadStore
import com.lladlam.melox.core.model.SearchSong
import com.lladlam.melox.core.network.MeloXNetworkAvailability
import com.lladlam.melox.core.music.model.AudioQualityTier
import com.lladlam.melox.core.music.model.MusicResourceId
import com.lladlam.melox.core.music.model.MusicSource
import com.lladlam.melox.core.music.model.MusicTrack
import com.lladlam.melox.core.music.provider.MeloXMusicProviders
import com.lladlam.melox.core.music.provider.SearchCapability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.Executor
import java.util.UUID

object PlaybackCommands {
    private const val TAG = "MeloXPlayback"
    const val QUEUE_ORIGIN_KEY = "melox.queue.origin"
    const val QUEUE_ORIGIN_BASE = "base"
    const val QUEUE_ORIGIN_MANUAL = "manual"
    const val QUEUE_ORIGINAL_INDEX_KEY = "melox.queue.original_index"
    const val QUEUE_ENTRY_ID_KEY = "melox.queue.entry_id"
    const val QUEUE_ORIGINAL_INDEX_UNSET = -1
    const val HEART_MODE_KEY = "melox.playback.heart_mode"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { command -> mainHandler.post(command) }
    private val providerMatchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var activeController: MediaController? = null

    fun currentSongId(): Long? = activeController?.currentMediaItem?.mediaId?.toLongOrNull()

    /**
     * Existing MeloX screens keep calling this legacy entry point. Provider-backed
     * bridge models are detected here and dispatched without teaching the UI
     * anything about QQ Music/Kugou. This is the compatibility seam that allows
     * future iOS UI migrations to reuse the same actions unchanged.
     */
    fun playQueue(
        context: Context,
        songs: List<SearchSong>,
        selectedSongId: Long,
        startPositionMs: Long = C.TIME_UNSET,
        heartMode: Boolean = false,
        onFailure: ((Throwable) -> Unit)? = null,
        providerSource: MusicSource = MusicSource.Netease,
    ) {
        val appContext = context.applicationContext
        if (providerSource != MusicSource.Netease && songs.none { it.providerTrack != null }) {
            providerMatchScope.launch {
                runCatching {
                    resolveProviderQueue(appContext, songs, selectedSongId, providerSource)
                }.onSuccess { resolved ->
                    if (resolved.tracks.isEmpty() || resolved.selected == null) {
                        error("没有找到 ${providerSource.displayName} 的对应歌曲")
                    }
                    ProviderPlaybackCommands.playQueue(
                        context = appContext,
                        tracks = resolved.tracks,
                        selectedTrackId = resolved.selected.id,
                        startPositionMs = startPositionMs,
                        onFailure = onFailure,
                    )
                }.onFailure { error ->
                    MeloXPlaybackNotice.show(appContext, error.message ?: "当前音源没有找到对应歌曲")
                    onFailure?.invoke(error)
                }
            }
            return
        }
        val selectedProviderTrack = songs.firstOrNull { it.id == selectedSongId }?.providerTrack
        if (selectedProviderTrack != null) {
            val providerTracks = songs.mapNotNull(SearchSong::providerTrack)
            if (providerTracks.isEmpty()) {
                MeloXPlaybackNotice.show(appContext, MeloXPlaybackNotice.EMPTY_QUEUE)
                return
            }
            ProviderPlaybackCommands.playQueue(
                context = context,
                tracks = providerTracks,
                selectedTrackId = selectedProviderTrack.id,
                startPositionMs = startPositionMs,
                onFailure = onFailure,
            )
            return
        }

        val quality = MusicQualityPreferences.read(appContext)
        MusicQualityRuntime.selected = quality
        MusicQualityRuntime.clear()
        CrossProviderPlaybackRuntime.clear()
        val token = SessionToken(
            appContext,
            ComponentName(appContext, MeloXPlaybackService::class.java),
        )
        val controllerFuture = MediaController.Builder(appContext, token).buildAsync()

        controllerFuture.addListener(
            {
                try {
                    val controller = controllerFuture.get()
                    val sourceSongs = songs.ifEmpty {
                        MeloXPlaybackNotice.show(appContext, MeloXPlaybackNotice.EMPTY_QUEUE)
                        controller.release()
                        return@addListener
                    }
                    val downloads = MeloXDownloadStore.get(appContext)
                    val offline = !MeloXNetworkAvailability.isOnline(appContext)
                    val sourceStartIndex = sourceSongs.indexOfFirst { it.id == selectedSongId }
                        .takeIf { it >= 0 } ?: 0
                    val playable = sourceSongs.mapIndexed { index, song -> index to song }
                        .let { indexed ->
                            if (offline) indexed.filter { (_, song) -> downloads.contains(song.id) }
                            else indexed
                        }
                    if (playable.isEmpty()) {
                        controller.release()
                        MeloXPlaybackNotice.show(appContext, MeloXPlaybackNotice.OFFLINE_NO_DOWNLOADS)
                        onFailure?.invoke(IOException("离线状态下没有可播放的已下载歌曲"))
                        return@addListener
                    }
                    val selectedPair = playable.firstOrNull { it.first == sourceStartIndex }
                        ?: playable.firstOrNull { it.first > sourceStartIndex }
                        ?: playable.first()
                    val originalQueue = playable.map { (index, song) ->
                        song.toMediaItem(
                            quality = quality,
                            queueOrigin = QUEUE_ORIGIN_BASE,
                            originalIndex = index,
                            artworkOverride = downloads.localArtworkUri(song.id),
                            heartMode = heartMode,
                        )
                    }
                    val selectedItemIndex = originalQueue.indexOfFirst {
                        it.mediaMetadata.extras?.getInt(QUEUE_ORIGINAL_INDEX_KEY) == selectedPair.first
                    }.coerceAtLeast(0)
                    val smartQueue = MeloXPlaybackModePreferences.autoMix(appContext) &&
                        MeloXPlaybackModePreferences.smartQueue(appContext) &&
                        originalQueue.size > 1
                    val useShuffle = MeloXPlaybackModePreferences.shuffle(appContext) && !smartQueue
                    val queue = if (useShuffle) {
                        val selected = originalQueue[selectedItemIndex]
                        listOf(selected) + originalQueue.filterIndexed { index, _ -> index != selectedItemIndex }.shuffled()
                    } else {
                        originalQueue
                    }
                    val startIndex = if (useShuffle) 0 else selectedItemIndex
                    // 智能队列**仍然把整首歌单交给播放器**。候选池只决定"下一首是谁"，
                    // 靠 `moveMediaItem` 在时间线里重排（见 MeloXPlaybackService.commitSmartQueueChain），
                    // 不再把歌扣在池子里等分析完才逐首补。旧实现只 setMediaItems 1 首，
                    // 而队列面板完全按 mediaItemCount 建（MeloXPlayerUi.buildQueue），
                    // 结果"打开歌单/播放搜索完的歌曲"后列表从头到尾都是空的。
                    if (smartQueue) MeloXSmartQueueBuilder.begin(queue, startIndex)
                    else MeloXSmartQueueBuilder.reset()
                    val dispatchedQueue = queue
                    val dispatchedStartIndex = startIndex

                    adoptController(controller)
                    controller.shuffleModeEnabled = false
                    controller.setMediaItems(dispatchedQueue, dispatchedStartIndex, startPositionMs)
                    MeloXPlaybackModeRuntime.heartModeActive = heartMode
                    controller.prepare()
                    controller.play()

                    Log.d(
                        TAG,
                        "Playback queue dispatched: size=${dispatchedQueue.size}, smart=$smartQueue, start=$dispatchedStartIndex, offline=$offline, quality=${quality.apiLevel}",
                    )
                } catch (error: Throwable) {
                    Log.e(TAG, "Unable to connect MediaController", error)
                    MeloXPlaybackNotice.show(
                        appContext,
                        MeloXPlaybackNotice.describe(
                            error,
                            offline = !MeloXNetworkAvailability.isOnline(appContext),
                        ),
                    )
                    onFailure?.invoke(error)
                }
            },
            mainExecutor,
        )
    }

    private data class ProviderQueue(
        val tracks: List<MusicTrack>,
        val selected: MusicTrack?,
    )

    private suspend fun resolveProviderQueue(
        context: Context,
        songs: List<SearchSong>,
        selectedSongId: Long,
        source: MusicSource,
    ): ProviderQueue {
        val search = MeloXMusicProviders.create(context).require(source) as? SearchCapability
            ?: error("${source.displayName} 当前没有搜索能力")
        val candidates = songs.distinctBy { "${it.name}\u001f${it.artists}" }.take(40)
        val resolved = coroutineScope {
            candidates.map { song ->
                async {
                    val query = listOf(song.name, song.artists).filter(String::isNotBlank).joinToString(" ")
                    val matches = runCatching { search.searchSongs(query, page = 1, pageSize = 8).items }
                        .getOrDefault(emptyList())
                    val normalizedTitle = normalizeMatchText(song.name)
                    val normalizedArtists = normalizeMatchText(song.artists)
                    val best = matches.maxByOrNull { track ->
                        val title = normalizeMatchText(track.title)
                        val artist = normalizeMatchText(track.artistText)
                        (if (title == normalizedTitle) 100 else if (title.contains(normalizedTitle) || normalizedTitle.contains(title)) 45 else 0) +
                            (if (normalizedArtists.isNotBlank() && artist.contains(normalizedArtists)) 60 else 0) +
                            (if (song.durationMs > 0L && track.durationMs != null) {
                                (30 - kotlin.math.abs(song.durationMs - track.durationMs!!).toDouble() / 1000.0).coerceAtLeast(0.0).toInt()
                            } else 0)
                    }
                    best?.let { track ->
                        val titleMatches = normalizeMatchText(track.title) == normalizedTitle ||
                            normalizeMatchText(track.title).contains(normalizedTitle) ||
                            normalizedTitle.contains(normalizeMatchText(track.title))
                        val artistMatches = normalizedArtists.isBlank() ||
                            normalizeMatchText(track.artistText).contains(normalizedArtists)
                        if (titleMatches && artistMatches) song.id to track else null
                    }
                }
            }.awaitAll().filterNotNull()
        }
        val tracks = resolved.map { it.second }
        val selected = resolved.firstOrNull { it.first == selectedSongId }?.second
        return ProviderQueue(tracks = tracks, selected = selected)
    }

    private fun normalizeMatchText(value: String): String =
        value.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")

    /**
     * 「播放全部 / 随机播放」按钮的入口。以前 UI 侧写的是
     * `songs.firstOrNull()?.let { playQueue(...) }`，列表为空时整个点击被吞掉，
     * 用户看不到任何反馈。这里把空列表补成一句提示。
     */
    fun playAll(
        context: Context,
        songs: List<SearchSong>,
        shuffle: Boolean = false,
        heartMode: Boolean = false,
        onFailure: ((Throwable) -> Unit)? = null,
        providerSource: MusicSource = MusicSource.Netease,
    ) {
        val queue = if (shuffle) songs.shuffled() else songs
        val first = queue.firstOrNull()
        if (first == null) {
            MeloXPlaybackNotice.show(context.applicationContext, MeloXPlaybackNotice.EMPTY_QUEUE)
            return
        }
        playQueue(
            context = context,
            songs = queue,
            selectedSongId = first.id,
            heartMode = heartMode,
            onFailure = onFailure,
            providerSource = providerSource,
        )
    }

    fun addToQueue(context: Context, song: SearchSong) {
        val appContext = context.applicationContext
        val quality = MusicQualityPreferences.read(appContext)
        val controller = activeController
        if (controller == null) {
            playQueue(context, listOf(song), song.id)
            return
        }
        val providerTrack = song.providerTrack
        if (providerTrack != null) {
            prioritizeManualQueue(controller)
            val current = controller.currentMediaItemIndex.coerceAtLeast(-1)
            val manualCount = ((current + 1) until controller.mediaItemCount).count { index ->
                controller.getMediaItemAt(index).queueOrigin() == QUEUE_ORIGIN_MANUAL
            }
            val insertion = (current + 1 + manualCount).coerceIn(0, controller.mediaItemCount)
            controller.addMediaItem(
                insertion,
                ProviderPlaybackCommands.mediaItemFor(
                    track = providerTrack,
                    neteaseQuality = quality,
                    queueOrigin = QUEUE_ORIGIN_MANUAL,
                ),
            )
            return
        }

        val downloads = MeloXDownloadStore.get(appContext)
        if (!MeloXNetworkAvailability.isOnline(appContext) && !downloads.contains(song.id)) {
            MeloXPlaybackNotice.show(appContext, MeloXPlaybackNotice.OFFLINE_NO_DOWNLOADS)
            return
        }
        prioritizeManualQueue(controller)
        val current = controller.currentMediaItemIndex.coerceAtLeast(-1)
        val manualCount = ((current + 1) until controller.mediaItemCount).count { index ->
            controller.getMediaItemAt(index).queueOrigin() == QUEUE_ORIGIN_MANUAL
        }
        val insertion = (current + 1 + manualCount).coerceIn(0, controller.mediaItemCount)
        controller.addMediaItem(
            insertion,
            song.toMediaItem(
                quality,
                QUEUE_ORIGIN_MANUAL,
                QUEUE_ORIGINAL_INDEX_UNSET,
                downloads.localArtworkUri(song.id),
            ),
        )
    }

    fun playNext(context: Context, song: SearchSong) {
        val appContext = context.applicationContext
        val quality = MusicQualityPreferences.read(appContext)
        val controller = activeController
        if (controller == null) {
            playQueue(context, listOf(song), song.id)
            return
        }
        val providerTrack = song.providerTrack
        if (providerTrack != null) {
            prioritizeManualQueue(controller)
            val insertion = (controller.currentMediaItemIndex + 1).coerceIn(0, controller.mediaItemCount)
            controller.addMediaItem(
                insertion,
                ProviderPlaybackCommands.mediaItemFor(
                    track = providerTrack,
                    neteaseQuality = quality,
                    queueOrigin = QUEUE_ORIGIN_MANUAL,
                ),
            )
            return
        }

        val downloads = MeloXDownloadStore.get(appContext)
        if (!MeloXNetworkAvailability.isOnline(appContext) && !downloads.contains(song.id)) {
            MeloXPlaybackNotice.show(appContext, MeloXPlaybackNotice.OFFLINE_NO_DOWNLOADS)
            return
        }
        prioritizeManualQueue(controller)
        val insertion = (controller.currentMediaItemIndex + 1).coerceIn(0, controller.mediaItemCount)
        controller.addMediaItem(
            insertion,
            song.toMediaItem(
                quality,
                QUEUE_ORIGIN_MANUAL,
                QUEUE_ORIGINAL_INDEX_UNSET,
                downloads.localArtworkUri(song.id),
            ),
        )
    }

    fun changeQuality(context: Context, quality: MusicQuality) {
        val appContext = context.applicationContext
        MusicQualityPreferences.write(appContext, quality)
        MusicQualityRuntime.selected = quality
        MusicQualityRuntime.clear()
        CrossProviderPlaybackRuntime.clear()

        val controller = activeController ?: return
        val currentIndex = controller.currentMediaItemIndex.coerceAtLeast(0)
        val currentPosition = controller.currentPosition.coerceAtLeast(0L)
        val shouldResume = controller.playWhenReady
        val items = List(controller.mediaItemCount) { index ->
            val item = controller.getMediaItemAt(index)
            val songId = item.mediaId.toLongOrNull()
            if (songId == null) item else MediaItem.Builder()
                .setMediaId(item.mediaId)
                .setUri(
                    NeteasePlaybackResolver.uriForSong(
                        songId = songId,
                        quality = quality,
                        title = item.mediaMetadata.title?.toString(),
                        artist = item.mediaMetadata.artist?.toString(),
                        durationMs = item.mediaMetadata.extras
                            ?.getLong(PlaybackTrackIdentity.DurationMsExtra, 0L),
                    ),
                )
                .setMediaMetadata(item.mediaMetadata)
                .build()
        }
        if (items.isEmpty()) return
        controller.setMediaItems(items, currentIndex.coerceIn(0, items.lastIndex), currentPosition)
        controller.prepare()
        if (shouldResume) controller.play()
    }

    /**
     * Shuffle is represented by the real future timeline. We move existing items
     * instead of rebuilding the player, so the currently playing decoder/position
     * stays untouched. Manual queue items always remain before base items.
     */
    fun setExplicitShuffle(context: Context, player: Player, enabled: Boolean) {
        val count = player.mediaItemCount
        if (count <= 0 || player.currentMediaItemIndex !in 0 until count) {
            MeloXPlaybackModePreferences.setShuffle(context, enabled)
            player.shuffleModeEnabled = false
            return
        }
        val currentIndex = player.currentMediaItemIndex
        val items = List(count) { player.getMediaItemAt(it) }
        val future = items.drop(currentIndex + 1)
        val manual = future.filter { it.queueOrigin() == QUEUE_ORIGIN_MANUAL }
        val base = future.filter { it.queueOrigin() != QUEUE_ORIGIN_MANUAL }
        val orderedBase = if (enabled) {
            base.shuffled()
        } else {
            base.sortedWith(
                compareBy<MediaItem> { item ->
                    item.mediaMetadata.extras
                        ?.getInt(QUEUE_ORIGINAL_INDEX_KEY, QUEUE_ORIGINAL_INDEX_UNSET)
                        ?.takeIf { it >= 0 } ?: Int.MAX_VALUE
                }.thenBy { it.mediaId },
            )
        }
        reorderFutureInPlace(player, manual + orderedBase)
        player.shuffleModeEnabled = false
        MeloXPlaybackModePreferences.setShuffle(context, enabled)
    }

    fun prioritizeManualQueue(player: Player) {
        val currentIndex = player.currentMediaItemIndex
        if (currentIndex !in 0 until player.mediaItemCount) return
        val items = List(player.mediaItemCount) { player.getMediaItemAt(it) }
        val future = items.drop(currentIndex + 1)
        val manual = future.filter { it.queueOrigin() == QUEUE_ORIGIN_MANUAL }
        val base = future.filter { it.queueOrigin() != QUEUE_ORIGIN_MANUAL }
        if (manual.isEmpty()) return
        reorderFutureInPlace(player, manual + base)
    }

    internal fun adoptController(controller: MediaController) {
        activeController?.takeIf { it !== controller }?.release()
        activeController = controller
    }

    internal fun mediaItemFor(
        song: SearchSong,
        quality: MusicQuality = MusicQualityRuntime.selected,
        queueOrigin: String = QUEUE_ORIGIN_BASE,
        originalIndex: Int = QUEUE_ORIGINAL_INDEX_UNSET,
        artworkOverride: Uri? = null,
        heartMode: Boolean = MeloXPlaybackModeRuntime.heartModeActive,
    ): MediaItem = song.providerTrack?.let { track ->
        ProviderPlaybackCommands.mediaItemFor(
            track = track,
            neteaseQuality = quality,
            queueOrigin = queueOrigin,
            originalIndex = originalIndex,
        )
    } ?: song.toMediaItem(quality, queueOrigin, originalIndex, artworkOverride, heartMode)

    private fun reorderFutureInPlace(player: Player, desiredFuture: List<MediaItem>) {
        val start = player.currentMediaItemIndex + 1
        if (start < 0 || desiredFuture.isEmpty()) return
        val simulated = MutableList(player.mediaItemCount) { player.getMediaItemAt(it) }
        desiredFuture.forEachIndexed { offset, wanted ->
            val target = start + offset
            if (target !in simulated.indices) return@forEachIndexed
            val from = (target until simulated.size).firstOrNull { simulated[it] === wanted }
                ?: (target until simulated.size).firstOrNull { simulated[it] == wanted }
                ?: return@forEachIndexed
            if (from != target) {
                player.moveMediaItem(from, target)
                val moved = simulated.removeAt(from)
                simulated.add(target, moved)
            }
        }
    }

    private fun MediaItem.queueOrigin(): String =
        mediaMetadata.extras?.getString(QUEUE_ORIGIN_KEY) ?: QUEUE_ORIGIN_BASE

    private fun SearchSong.toMediaItem(
        quality: MusicQuality,
        queueOrigin: String,
        originalIndex: Int = QUEUE_ORIGINAL_INDEX_UNSET,
        artworkOverride: Uri? = null,
        heartMode: Boolean = MeloXPlaybackModeRuntime.heartModeActive,
    ): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(name)
            .setArtist(artists)
            .setAlbumTitle(album)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(
                Bundle().apply {
                    putString(QUEUE_ORIGIN_KEY, queueOrigin)
                    putInt(QUEUE_ORIGINAL_INDEX_KEY, originalIndex)
                    putString(QUEUE_ENTRY_ID_KEY, UUID.randomUUID().toString())
                    putBoolean(HEART_MODE_KEY, heartMode)
                    putLong(PlaybackTrackIdentity.DurationMsExtra, durationMs.coerceAtLeast(0L))
                    putBoolean(PlaybackTrackIdentity.IsFavoriteExtra, false)
                },
            )
            .apply {
                artworkOverride?.let(::setArtworkUri) ?: artworkUrl
                    ?.takeIf(String::isNotBlank)
                    ?.let { setArtworkUri(Uri.parse(it)) }
            }
            .build()

        return MediaItem.Builder()
            .setMediaId(id.toString())
            .setUri(
                NeteasePlaybackResolver.uriForSong(
                    songId = id,
                    quality = quality,
                    title = name,
                    artist = artists,
                    durationMs = durationMs,
                ),
            )
            .setMediaMetadata(metadata)
            .build()
    }
}
