package com.lladlam.melox.core.autosync

import android.content.Context
import android.util.Log
import com.lladlam.melox.core.lyrics.LyricLine
import com.lladlam.melox.core.lyrics.LyricSyllable
import com.lladlam.melox.core.lyrics.LyricTimingKind
import com.lladlam.melox.core.lyrics.LyricsDocument
import com.lladlam.melox.core.lyrics.LyricQuality
import com.lladlam.melox.ui.settings.MeloXSettingsRuntime
import java.io.File
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Everything that turns a line-timed lyric into a real word-level timeline.
 *
 * Runs entirely on device: the playback service lends the cached audio bytes,
 * [OnnxCtcEngine] produces frame log-probabilities, [CtcForcedAligner] aligns
 * them and [AutoSyncTimelineStore] remembers the result. Nothing here is
 * allowed to fabricate timing — a line that cannot be aligned is left exactly
 * as the provider returned it.
 */
internal object AutoSyncCoordinator {

    private const val TAG = "MeloXAutoSync"
    private const val SAMPLE_RATE = 16_000
    private const val PADDING_MS = 300L
    private const val MAX_LINE_MS = 12_000L
    private const val MIN_LINE_MS = 250L

    private val generationLock = Mutex()

    /**
     * @return the stored document upgraded with generated word timing, or
     *   `null` when nothing was cached for this exact lyric.
     */
    fun applyStored(
        context: Context,
        document: LyricsDocument,
        trackKey: String,
        audioKey: String? = null,
    ): LyricsDocument? {
        if (!MeloXSettingsRuntime.autoSyncWordLyricsEnabled || document.lines.any { it.syllables.isNotEmpty() }) return null
        val stored = AutoSyncTimelineStore.load(context, trackKey) ?: return null
        if (stored.sourceHash != AutoSyncTimelineStore.sourceHash(document)) return null
        // 音频被换掉（不同码率重下等）时旧时间轴不再适用；拿不到音频身份时按可用处理。
        if (audioKey != null && stored.audioKey.isNotBlank() && stored.audioKey != audioKey) {
            Log.i(TAG, "stored timeline ignored: audio identity changed")
            return null
        }
        // 多语言时间轴的 language 形如 "en+zh"，按组合键校验，别把命中缓存误判为失效。
        val storedLanguages = stored.language.split('+').filter { it.isNotBlank() }
        if (stored.modelKey.isBlank() ||
            stored.modelKey != AutoSyncModelStore.timelineModelKey(context, storedLanguages)
        ) {
            return null
        }
        return merge(document, stored)
    }

    /**
     * Generates a word-level timeline for [document] and persists it.
     *
     * @return the upgraded document, or `null` when AutoSync is disabled, the
     *   lyrics already carry word timing, no model/audio is available, or the
     *   alignment produced nothing usable.
     */
    suspend fun generate(
        context: Context,
        document: LyricsDocument,
        trackKey: String,
        onProgress: ((Float) -> Unit)? = null,
    ): LyricsDocument? = generationLock.withLock {
        if (AutoSyncPlaybackBridge.isCurrentTrack?.invoke(trackKey) == false) return@withLock null
        val audioKey = runCatching { AutoSyncPlaybackBridge.audioResolver?.invoke(trackKey) }
            .getOrNull()
            ?.let { "${it.name}:${it.length()}" }
        applyStored(context, document, trackKey, audioKey)?.let { return@withLock it }
        try {
            generateUncached(context, document, trackKey, onProgress)
        } finally {
            // Keep at most one language session resident, and release native
            // memory immediately after each serialized job (including cancel).
            OnnxCtcEngine.releaseAll()
            ZhAlignmentEngine.releaseAll()
            System.gc()
        }
    }

    /**
     * Per-line language plan: which engine each line should go to.
     *
     * Mixed Chinese/English songs are the reason this exists: judging the whole
     * document once used to send pure-English lines to the Chinese pack, where
     * they were refused and silently stayed line-timed.
     */
    private fun buildAlignmentPlan(lines: List<LyricLine>): List<Pair<Int, AutoSyncLineLanguage>> {
        val plan = ArrayList<Pair<Int, AutoSyncLineLanguage>>(lines.size)
        lines.forEachIndexed { index, line ->
            if (!AutoSyncText.isAlignable(line.text)) {
                Log.i(TAG, "line $index skipped: not alignable (credits/instrumental)")
                return@forEachIndexed
            }
            when (val language = AutoSyncText.detectLineLanguage(line.text)) {
                AutoSyncLineLanguage.Unknown -> Log.i(TAG, "line $index skipped: no usable language in \"${line.text.take(24)}\"")
                else -> plan += index to language
            }
        }
        return plan
    }

    private suspend fun generateUncached(
        context: Context,
        document: LyricsDocument,
        trackKey: String,
        onProgress: ((Float) -> Unit)?,
    ): LyricsDocument? {
        if (!MeloXSettingsRuntime.autoSyncWordLyricsEnabled) return null
        if (document.lines.isEmpty()) return null
        if (document.lines.any { it.syllables.isNotEmpty() }) return null

        val plan = buildAlignmentPlan(document.lines)
        if (plan.isEmpty()) {
            Log.i(TAG, "nothing to align for $trackKey")
            return null
        }
        val resolveStartedAt = android.os.SystemClock.elapsedRealtime()
        val audioFile = runCatching { AutoSyncPlaybackBridge.audioResolver?.invoke(trackKey) }
            .onFailure {
                if (it is CancellationException) throw it
                Log.w(TAG, "audio resolver failed", it)
            }
            .getOrNull()
        if (audioFile == null || !audioFile.isFile || audioFile.length() == 0L) {
            Log.i(TAG, "no cached audio available for alignment (${audioFile?.absolutePath ?: "null"})")
            return null
        }
        Log.i(
            TAG,
            "audio resolved in ${android.os.SystemClock.elapsedRealtime() - resolveStartedAt}ms: " +
                "${audioFile.name} ${audioFile.length()}B",
        )

        val range = decodeRange(document)
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val pcm = runCatching {
            AutoSyncAudioDecoder.decode(
                file = audioFile,
                targetSampleRate = SAMPLE_RATE,
                startMs = range.first,
                endMs = range.second,
                timeoutMs = (30_000L + (range.second - range.first) / 2).coerceAtMost(180_000L),
                shouldContinue = {
                    MeloXSettingsRuntime.autoSyncWordLyricsEnabled &&
                        AutoSyncPlaybackBridge.isCurrentTrack?.invoke(trackKey) != false
                },
            )
        }.onFailure {
            if (it is CancellationException) throw it
            Log.w(TAG, "audio decode failed", it)
        }.getOrNull() ?: return null

        return pcm.use {
            alignRouted(
                context, document, trackKey, onProgress, pcm, range, startedAt, plan,
                audioKey = "${audioFile.name}:${audioFile.length()}",
            )
        }
    }

    /**
     * Runs the plan one language at a time.
     *
     * Only one acoustic model is resident at any moment: the engines evict the
     * other languages when a new one is loaded, so a mixed song costs a reload
     * but never several models in RAM at once.
     */
    private suspend fun alignRouted(
        context: Context,
        document: LyricsDocument,
        trackKey: String,
        onProgress: ((Float) -> Unit)?,
        pcm: AutoSyncPcmFile,
        range: Pair<Long, Long>,
        startedAt: Long,
        plan: List<Pair<Int, AutoSyncLineLanguage>>,
        audioKey: String,
    ): LyricsDocument? {
        Log.i(
            TAG,
            "decoded ${pcm.sampleCount} samples in ${android.os.SystemClock.elapsedRealtime() - startedAt}ms " +
                "(range ${range.first}..${range.second}ms)",
        )
        if (pcm.sampleCount * 1000L / SAMPLE_RATE < MIN_LINE_MS) return null
        val totalMs = range.first + pcm.sampleCount * 1000L / SAMPLE_RATE
        val decodeMs = android.os.SystemClock.elapsedRealtime() - startedAt

        val timings = ArrayList<AutoSyncLineTiming>(document.lines.size)
        var alignedUnits = 0
        var failedLines = 0
        var lastReported = -1
        val languageModels = LinkedHashMap<String, String>()
        var unitsPerLanguage = 0

        val groups = plan.groupBy({ it.second }, { it.first })
            .entries.sortedBy { (_, indices) -> indices.first() }

        groups.forEach { (language, indices) ->
            currentCoroutineContext().ensureActive()
            if (AutoSyncPlaybackBridge.isCurrentTrack?.invoke(trackKey) == false) return null

            val engine = when (language) {
                AutoSyncLineLanguage.Zh -> ZhAlignmentEngine.forLanguage(context, "zh")
                    ?.let { ZhEngineHandle(it) }
                AutoSyncLineLanguage.En -> OnnxCtcEngine.forLanguage(context, "en")
                    ?.let { CtcEngineHandle(it) }
                AutoSyncLineLanguage.Ja -> OnnxCtcEngine.forLanguage(context, "ja")
                    ?.let { JaEngineHandle(it, context) }
                AutoSyncLineLanguage.Unknown -> null
            }
            if (engine == null) {
                val reason = if (language == AutoSyncLineLanguage.Ja) {
                    "Japanese model unavailable (model not installed)"
                } else {
                    "no installed model for ${language.tag}"
                }
                Log.w(TAG, "language group ${language.tag} skipped: $reason (${indices.size} lines stay line-timed)")
                failedLines += indices.size
                return@forEach
            }
            languageModels[language.tag] = engine.modelLabel
            unitsPerLanguage = 0

            indices.forEach { index ->
                currentCoroutineContext().ensureActive()
                if (AutoSyncPlaybackBridge.isCurrentTrack?.invoke(trackKey) == false) return null
                val line = document.lines[index]
                onProgress?.let { report ->
                    val bucket = index * 100 / document.lines.size.coerceAtLeast(1)
                    if (bucket != lastReported) {
                        lastReported = bucket
                        report(bucket / 100f)
                    }
                }
                val window = windowFor(document, index, line, totalMs) ?: run {
                    Log.i(TAG, "line $index skipped: window outside the decoded range")
                    failedLines++
                    return@forEach
                }
                val startSample = ((window.first - range.first) * SAMPLE_RATE / 1000L)
                    .toInt().coerceIn(0, pcm.sampleCount)
                val endSample = ((window.second - range.first) * SAMPLE_RATE / 1000L)
                    .toInt().coerceIn(startSample, pcm.sampleCount)
                if (endSample - startSample < SAMPLE_RATE / 10) {
                    Log.i(TAG, "line $index skipped: window shorter than 100ms")
                    failedLines++
                    return@forEach
                }
                val snippet = pcm.readWindow(startSample, endSample)
                val units = runCatching { engine.alignLine(line, snippet) }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        Log.w(TAG, "line $index (${language.tag}) inference failed", error)
                    }
                    .getOrNull()
                if (units.isNullOrEmpty()) {
                    failedLines++
                    Log.i(TAG, "line $index (${language.tag}) not aligned; keeping line timing")
                    return@forEach
                }
                val windowed = sanitize(units, snippet.size * 1000L / SAMPLE_RATE)
                if (windowed.isEmpty()) {
                    failedLines++
                    Log.i(TAG, "line $index (${language.tag}) produced no usable spans")
                    return@forEach
                }
                alignedUnits += windowed.size
                unitsPerLanguage += windowed.size
                timings.add(
                    AutoSyncLineTiming(
                        index = index,
                        units = windowed.map { unit ->
                            AutoSyncUnit(unit.text, window.first + unit.startMs, window.first + unit.endMs)
                        },
                        meanScore = 0.0,
                    ),
                )
            }
            Log.i(TAG, "aligned language=${language.tag} lines=${indices.size} units=$unitsPerLanguage")
            engine.close()
        }

        if (timings.isEmpty()) {
            Log.i(TAG, "alignment produced nothing usable for $trackKey (failed=$failedLines)")
            return null
        }
        val modelKey = languageModels.entries.joinToString("+") { "${it.key}:${it.value}" }
        val timeline = AutoSyncTimeline(
            trackKey = trackKey,
            sourceHash = AutoSyncTimelineStore.sourceHash(document),
            language = languageModels.keys.sorted().joinToString("+"),
            engine = modelKey,
            lines = timings,
            createdAtMs = System.currentTimeMillis(),
            modelKey = AutoSyncModelStore.timelineModelKey(context, languageModels.keys.sorted()) ?: modelKey,
            audioKey = audioKey,
        )
        if (AutoSyncPlaybackBridge.isCurrentTrack?.invoke(trackKey) == false) return null
        AutoSyncTimelineStore.save(context, timeline)
        Log.i(
            TAG,
            "aligned $trackKey lines=${timings.size}/${document.lines.size} units=$alignedUnits " +
                "failed=$failedLines decode=${decodeMs}ms total=" +
                "${android.os.SystemClock.elapsedRealtime() - startedAt}ms models=$modelKey " +
                "rss=${currentRssBytes()}B",
        )
        onProgress?.invoke(1f)
        return merge(document, timeline)
    }

    /**
     * Reference `map_spans_to_char_units`: every original character is kept; a
     * character without a known phoneme gets a zero-width interval at its
     * nearest known boundary and is reported as uncertain.
     */
    private fun mapSpansToCharUnits(
        readings: List<JapaneseG2pMapper.CharReading>,
        phonemes: List<String>,
        spansByPhoneme: Map<Int, CtcForcedAligner.Span>,
        frameSeconds: Double,
    ): List<AutoSyncUnit>? {
        val inner = readings.flatMap { it.phonemes }
        val offset = locateInnerOffset(phonemes, inner) ?: return null
        val resolved = arrayOfNulls<Pair<Double, Double>>(readings.size)
        var cursor = offset
        readings.forEachIndexed { index, reading ->
            val count = reading.phonemes.size
            var start: Double? = null
            var end: Double = 0.0
            for (k in 0 until count) {
                val span = spansByPhoneme[cursor + k] ?: continue
                val spanStart = span.startFrame * frameSeconds
                val spanEnd = (span.endFrame + 1) * frameSeconds
                if (start == null) start = spanStart
                end = spanEnd
            }
            resolved[index] = start?.let { it to end }
            cursor += count
        }
        // Nearest known boundaries for characters with no known phoneme.
        val previousEnd = DoubleArray(resolved.size)
        var running = 0.0
        resolved.indices.forEach { index ->
            previousEnd[index] = running
            resolved[index]?.let { running = it.second }
        }
        val nextStart = arrayOfNulls<Double>(resolved.size)
        var upcoming: Double? = null
        for (index in resolved.indices.reversed()) {
            resolved[index]?.let { upcoming = it.first }
            nextStart[index] = upcoming
        }
        val previousKnown = BooleanArray(resolved.size)
        var seen = false
        resolved.indices.forEach { index ->
            previousKnown[index] = seen
            if (resolved[index] != null) seen = true
        }
        return readings.mapIndexed { index, reading ->
            val value = resolved[index]
            if (value != null) {
                AutoSyncUnit(
                    text = reading.char,
                    startMs = (value.first * 1000.0).roundToLong(),
                    endMs = (value.second * 1000.0).roundToLong(),
                )
            } else {
                val position = when {
                    previousKnown[index] -> previousEnd[index]
                    nextStart[index] != null -> nextStart[index]!!
                    else -> 0.0
                }
                val ms = (position * 1000.0).roundToLong()
                AutoSyncUnit(text = reading.char, startMs = ms, endMs = ms)
            }
        }
    }

    /** Index of `inner` inside the `pau`-wrapped `full` sequence. */
    private fun locateInnerOffset(full: List<String>, inner: List<String>): Int? {
        if (inner.isEmpty()) return 0
        for (offset in 0..(full.size - inner.size)) {
            if (full.subList(offset, offset + inner.size) == inner) return offset
        }
        return null
    }

    /** Uniform view over the two engines so the driver stays language agnostic. */
    private interface LineEngine {
        val modelLabel: String
        /** Per-line units with window-relative milliseconds, or empty when refused. */
        fun alignLine(line: LyricLine, snippet: FloatArray): List<AutoSyncUnit>
        fun close()
    }

    private class CtcEngineHandle(private val engine: OnnxCtcEngine) : LineEngine {
        override val modelLabel: String get() = engine.sourceModel
        override fun alignLine(line: LyricLine, snippet: FloatArray): List<AutoSyncUnit> {
            val normalized = AutoSyncText.normalizeEn(line.text)
            val tokenIds = AutoSyncText.tokenIds(normalized, engine.manifest)
            if (tokenIds.isEmpty()) return emptyList()
            val alignment = engine.alignWindow(snippet, tokenIds)
            if (!alignment.feasible) {
                Log.i(TAG, "CTC alignment not feasible: ${alignment.reasons}")
                return emptyList()
            }
            return AutoSyncText.groupWords(
                originalText = line.text,
                normalized = normalized,
                tokenIds = tokenIds,
                spans = alignment.spans,
                manifest = engine.manifest,
                frameHopSeconds = engine.manifest.frameHopSeconds,
            )
        }

        override fun close() = engine.close()
    }

    /**
     * Japanese: OpenJTalk G2P (kanji readings via the NAIST dictionary) →
     * phoneme CTC → per-character spans.
     *
     * Ported 1:1 from the reference chain; the dictionary is downloaded
     * separately, so a missing dictionary is reported instead of guessed.
     */
    private class JaEngineHandle(
        private val engine: OnnxCtcEngine,
        private val context: Context,
    ) : LineEngine {
        override val modelLabel: String get() = engine.sourceModel

        override fun alignLine(line: LyricLine, snippet: FloatArray): List<AutoSyncUnit> {
            if (!JaOpenJtalk.ensureDictionaryInstalled(context)) {
                Log.w(TAG, "Japanese G2P unavailable: NAIST dictionary is not installed")
                return emptyList()
            }
            val mapping = runCatching { JapaneseG2pMapper.map(line.text) }
                .onFailure { Log.w(TAG, "Japanese G2P failed", it) }
                .getOrNull()
            if (mapping == null || mapping.phonemes.isEmpty()) {
                Log.i(TAG, "Japanese G2P produced no phonemes for this line")
                return emptyList()
            }
            val manifest = engine.manifest
            val lookup = HashMap<String, Int>(manifest.tokens.size * 2)
            manifest.tokens.forEachIndexed { index, token -> lookup.putIfAbsent(token, index) }
            // Phonemes outside the 48-token vocabulary are skipped, never UNK.
            val ids = ArrayList<Int>(mapping.phonemes.size)
            val keptIndices = ArrayList<Int>(mapping.phonemes.size)
            mapping.phonemes.forEachIndexed { index, phoneme ->
                lookup[phoneme]?.let { id ->
                    ids += id
                    keptIndices += index
                }
            }
            if (ids.isEmpty()) {
                Log.i(TAG, "Japanese line has no phoneme from the model vocabulary")
                return emptyList()
            }
            val alignment = engine.alignWindow(snippet, ids)
            if (!alignment.feasible) {
                Log.i(TAG, "Japanese CTC alignment not feasible: ${alignment.reasons}")
                return emptyList()
            }
            val spansByPhoneme = HashMap<Int, CtcForcedAligner.Span>(alignment.spans.size)
            alignment.spans.forEachIndexed { position, span ->
                val phonemeIndex = keptIndices.getOrNull(position) ?: return@forEachIndexed
                if (span != null) spansByPhoneme[phonemeIndex] = span
            }
            return mapSpansToCharUnits(
                readings = mapping.readings,
                phonemes = mapping.phonemes,
                spansByPhoneme = spansByPhoneme,
                frameSeconds = manifest.frameHopSeconds,
            ) ?: run {
                Log.i(TAG, "Japanese per-character mapping failed")
                emptyList()
            }
        }

        override fun close() = engine.close()
    }

    private class ZhEngineHandle(private val engine: ZhAlignmentEngine) : LineEngine {
        override val modelLabel: String get() = engine.manifest.sourceModel
        override fun alignLine(line: LyricLine, snippet: FloatArray): List<AutoSyncUnit> {
            if (!ZhText.isAlignableForChinese(line.text)) {
                Log.i(TAG, "Chinese line refused: mixed script or unsupported characters")
                return emptyList()
            }
            val normalized = ZhText.normalize(line.text)
            val tokenIds = ZhText.tokenIds(normalized, engine.manifest)
            if (tokenIds.isEmpty()) return emptyList()
            val spans = engine.alignWindow(snippet, tokenIds) ?: return emptyList()
            return chineseUnits(line.text, normalized, engine.manifest, spans)
        }

        override fun close() = engine.close()
    }

    /**
     * Maps per-token spans back onto the original lyric characters.
     *
     * `normalizeZh` only deletes characters, so a greedy scan recovers the
     * position of every normalised character in the original line. When the
     * mapping is not exact the line is refused outright: timing that cannot be
     * attributed to the displayed text is never reported.
     */
    private fun chineseUnits(
        original: String,
        normalized: String,
        manifest: AutoSyncManifest,
        spans: List<ZhCifPostprocess.TokenSpan>,
    ): List<AutoSyncUnit> {
        if (spans.isEmpty() || normalized.isEmpty()) return emptyList()
        val lookup = HashMap<String, Int>(manifest.tokens.size * 2)
        manifest.tokens.forEachIndexed { index, token -> lookup.putIfAbsent(token, index) }
        val keptPositions = ArrayList<Int>(normalized.length)
        for (index in normalized.indices) {
            if (lookup.containsKey(normalized[index].toString())) keptPositions.add(index)
        }
        if (keptPositions.size != spans.size) return emptyList()

        val toOriginal = HashMap<Int, Int>(normalized.length * 2)
        var cursor = 0
        for (index in original.indices) {
            if (cursor < normalized.length && original[index] == normalized[cursor]) {
                toOriginal[cursor] = index
                cursor++
            }
        }
        if (cursor != normalized.length) return emptyList()

        val units = ArrayList<AutoSyncUnit>(spans.size)
        for (index in spans.indices) {
            val originalIndex = toOriginal[keptPositions[index]] ?: return emptyList()
            val character = original[originalIndex]
            if (character.isWhitespace()) continue
            units.add(
                AutoSyncUnit(character.toString(), spans[index].startMs, spans[index].endMs),
            )
        }
        return units
    }


    /**
     * Span of audio AutoSync actually needs: from shortly before the first
     * lyric to shortly after the last one. Intro, interludes and outro are
     * skipped, which can be more than half of a track.
     */
    private fun decodeRange(document: LyricsDocument): Pair<Long, Long> {
        val first = document.lines.firstOrNull()?.timeMs?.coerceAtLeast(0L) ?: 0L
        val last = document.lines.lastOrNull()?.timeMs?.coerceAtLeast(first) ?: first
        val start = (first - RANGE_LEAD_MS).coerceAtLeast(0L)
        val end = last + RANGE_TAIL_MS
        return start to end.coerceAtLeast(start + 1_000L)
    }

    /** Null when the line's window falls outside the decoded range. */
    private fun windowFirst(
        document: LyricsDocument,
        index: Int,
        line: LyricLine,
        totalMs: Long,
        rangeStartMs: Long,
    ): Long? {
        val window = windowFor(document, index, line, totalMs) ?: return null
        return window.first.takeIf { it >= rangeStartMs }
    }

    private const val RANGE_LEAD_MS = 5_000L
    private const val RANGE_TAIL_MS = 15_000L

    private fun currentRssBytes(): Long = runCatching {
        File("/proc/self/status").useLines { lines ->
            lines.first { it.startsWith("VmRSS:") }.substringAfter(':').trim().substringBefore(' ').toLong() * 1024L
        }
    }.getOrDefault(-1L)

    /** Merges generated timing into the display document without touching text. */
    fun merge(document: LyricsDocument, timeline: AutoSyncTimeline): LyricsDocument? {
        val byIndex = timeline.lines.associateBy { it.index }
        var changed = false
        val lines = document.lines.mapIndexed { index, line ->
            val timing = byIndex[index]?.takeIf { it.units.isNotEmpty() } ?: return@mapIndexed line
            changed = true
            line.copy(
                syllables = timing.units.map { unit ->
                    LyricSyllable(unit.text, unit.startMs, unit.endMs.coerceAtLeast(unit.startMs + 1L))
                },
                timingKind = LyricTimingKind.Precise,
            )
        }
        if (!changed) return null
        return document.copy(
            lines = lines,
            quality = LyricQuality.WordSynchronized,
            // Lines the model could not align keep provider line timing; never let a
            // renderer fill those with an even split that looks model-generated.
            pseudoTimingAllowed = false,
            autoSyncModelKey = timeline.modelKey,
        )
    }

    /**
     * Audio window for one line, mirroring the reference engine: from the line
     * timestamp to the next line, plus padding that never crosses a neighbour,
     * capped so a long instrumental tail is not fed to the model.
     */
    private fun windowFor(
        document: LyricsDocument,
        index: Int,
        line: LyricLine,
        totalMs: Long,
    ): Pair<Long, Long>? {
        val nextStart = document.lines.getOrNull(index + 1)?.timeMs
        var start = max(0L, line.timeMs - PADDING_MS)
        var end = min(nextStart?.takeIf { it > line.timeMs } ?: totalMs, totalMs)
        end = min(totalMs, end + PADDING_MS)
        if (end - line.timeMs > MAX_LINE_MS) end = min(totalMs, line.timeMs + MAX_LINE_MS)
        if (end <= start) {
            end = min(totalMs, start + MAX_LINE_MS)
        }
        if (end - start < MIN_LINE_MS) return null
        return start to end
    }

    /** Clamps units into the window and enforces monotonic, positive durations. */
    private fun sanitize(units: List<AutoSyncUnit>, windowMs: Long): List<AutoSyncUnit> {
        if (units.isEmpty()) return units
        val result = ArrayList<AutoSyncUnit>(units.size)
        var lastEnd = 0L
        units.forEach { unit ->
            var start = unit.startMs.coerceIn(0L, windowMs)
            var end = unit.endMs.coerceIn(0L, windowMs)
            if (end < start) {
                val swap = start
                start = end
                end = swap
            }
            if (start < lastEnd) start = lastEnd
            if (end <= start) end = min(windowMs, start + 10L)
            if (end <= start) return@forEach
            result.add(AutoSyncUnit(unit.text, start, end))
            lastEnd = end
        }
        return result
    }
}

/**
 * Lets the playback service hand AutoSync the same cached audio bytes the
 * analysis pipeline already uses, without the lyric loader needing a decoder or
 * a media item of its own. Both live in the same process.
 */
object AutoSyncPlaybackBridge {
    /** Resolves the currently playing track to a local file, if one is cached. */
    @Volatile
    var audioResolver: (suspend (String) -> File?)? = null

    @Volatile
    var isCurrentTrack: (suspend (String) -> Boolean)? = null
}
