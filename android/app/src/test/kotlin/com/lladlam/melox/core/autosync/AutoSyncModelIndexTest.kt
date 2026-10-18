package com.lladlam.melox.core.autosync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSyncModelIndexTest {

    private val index = AutoSyncModelRepository.parseIndex(
        """
        {
          "pack_version": "0.1.0-beta.4",
          "release_tag": "android-models-v0.1.0-beta.4",
          "release_url": "https://github.com/lladlam/MeloX-AutoSync/releases/tag/android-models-v0.1.0-beta.4",
          "min_backend_protocol": 1,
          "languages": {
            "en": {
              "engine": "ctc-wav2vec2",
              "pack_version": "0.1.0-beta.4",
              "sample_rate": 16000,
              "frame_hop_seconds": 0.02,
              "input": { "name": "input_values" },
              "outputs": [{ "name": "logits" }, { "name": "log_probs" }],
              "blank_id": 0,
              "text": { "tokens_file": "en-vocab.json", "word_delimiter_id": 4 },
              "files": [
                { "name": "en-wav2vec2-base-960h-int8.onnx", "role": "model",
                  "download_url": "$PREFIX/en-wav2vec2-base-960h-int8.onnx", "bytes": 122373796,
                  "sha256": "1c8213118da7f555e4b68c0e08e85a4c471ea2a3e0fdf5620d27412800babd3f" },
                { "name": "en-vocab.json", "role": "tokenizer",
                  "download_url": "$PREFIX/en-vocab.json", "bytes": 291,
                  "sha256": "19727f8944fe6459fc3f240ae2c198395b740f6a029bd23e06656266b83bcf64" }
              ]
            },
            "ja": {
              "engine": "ctc-hubert-phoneme",
              "pack_version": "0.1.0-beta.4",
              "sample_rate": 16000,
              "input": { "name": "input_values" },
              "outputs": [{ "name": "logits" }],
              "blank_id": 0,
              "text": { "tokens_file": "ja-vocab.json" },
              "files": [
                { "name": "ja-hubert-phoneme-ctc-int8.onnx", "role": "model",
                  "download_url": "$PREFIX/ja-hubert-phoneme-ctc-int8.onnx", "bytes": 129412601,
                  "sha256": "10cfcea41d6db285004229360dbbf3d7ce826d67490e8b8c4bd66ccd7f38057d" }
              ]
            }
          },
          "extra_assets": [
            { "name": "en-wav2vec2-base-960h-int8-acc.onnx", "role": "optional-model",
              "download_url": "$PREFIX/en-wav2vec2-base-960h-int8-acc.onnx", "bytes": 292044035,
              "sha256": "b52596cb5b63c6ebd59f66be3be31582c4943e8624de48ea7d3a4652f08514a1" }
          ]
        }
        """.trimIndent(),
    )

    @Test
    fun pinnedIndexHashMatchesThePublishedBetaFourFile() {
        val bytes = javaClass.classLoader!!
            .getResourceAsStream("autosync/android-models-beta4.json")!!
            .readBytes()
        val actual = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        assertEquals(AutoSyncModelRepository.INDEX_SHA256, actual)

        val published = AutoSyncModelRepository.parseIndex(bytes.decodeToString())
        assertEquals("android-models-v0.1.0-beta.4", published.releaseTag)
        assertEquals(
            129_412_601L,
            published.packs.getValue("ja").files.first { it.role == "model" }.bytes,
        )
        assertEquals(
            "10cfcea41d6db285004229360dbbf3d7ce826d67490e8b8c4bd66ccd7f38057d",
            published.packs.getValue("ja").files.first { it.role == "model" }.sha256,
        )
        // en / zh 的模型字节未变，可直接复用已下载文件
        assertEquals(
            "1c8213118da7f555e4b68c0e08e85a4c471ea2a3e0fdf5620d27412800babd3f",
            published.packs.getValue("en").files.first { it.role == "model" }.sha256,
        )
        assertEquals(
            "c95f3615503a23776e18da9b303598eb11972cc4acf1f55791b630979775ddce",
            published.packs.getValue("zh").files.first { it.role == "model" }.sha256,
        )
        assertEquals(292_044_035L, published.packs.getValue("en").optionalModel?.bytes)
    }

    @Test
    fun optionalPrecisionModelIsAttachedToItsLanguage() {
        assertEquals(
            "en-wav2vec2-base-960h-int8-acc.onnx",
            index.packs.getValue("en").optionalModel?.name,
        )
        assertEquals(292044035L, index.packs.getValue("en").optionalModel?.bytes)
        assertNull("ja has no precision variant", index.packs.getValue("ja").optionalModel)
    }

    @Test
    fun matchesIndexAcceptsTheCurrentBaseInstall() {
        val pack = index.packs.getValue("en")
        val manifest = AutoSyncManifest(
            language = "en",
            sourceModel = "facebook/wav2vec2-base-960h",
            license = "Apache-2.0",
            sampleRate = 16_000,
            frameHopSeconds = 0.02,
            inputName = "input_values",
            tokenCountName = null,
            logProbOutputName = "log_probs",
            logitsOutputName = "logits",
            blankId = 0,
            wordDelimiterId = 4,
            unkId = 3,
            tokens = emptyList(),
            modelFileName = "en-wav2vec2-base-960h-int8.onnx",
            requiredFiles = listOf(
                AutoSyncFileDigest(
                    "en-wav2vec2-base-960h-int8.onnx",
                    122_373_796,
                    "1c8213118da7f555e4b68c0e08e85a4c471ea2a3e0fdf5620d27412800babd3f",
                ),
                AutoSyncFileDigest(
                    "en-vocab.json",
                    291,
                    "19727f8944fe6459fc3f240ae2c198395b740f6a029bd23e06656266b83bcf64",
                ),
            ),
        )
        assertTrue(AutoSyncModelRepository.matchesIndex(manifest, pack))

        // 旧版本（beta.2 的日文模型）不匹配 → UI 显示“有更新”，但不会被删除。
        val outdated = manifest.copy(
            requiredFiles = manifest.requiredFiles.map {
                if (it.name.endsWith(".onnx")) it.copy(bytes = 95_723_275, sha256 = "e93184b0".padEnd(64, '0')) else it
            },
        )
        assertFalse(AutoSyncModelRepository.matchesIndex(outdated, pack))
    }

    private companion object {
        const val PREFIX = "https://github.com/lladlam/MeloX-AutoSync/releases/download/android-models-v0.1.0-beta.4/"
    }
}
