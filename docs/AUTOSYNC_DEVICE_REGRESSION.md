# AutoSync Android 音频链路回归

## 实现

- MediaCodec 异步回调解码，独立工作线程；播放管线保持原有实现。
- 只对已知支持多帧输入的 `c2.android.mp3.decoder` 合并最多 16 个完整 MP3 帧。其他解码器仍使用单 access unit 输入。
- 63 抽头抗混叠 FIR 流式降采样到 16 kHz 单声道。工作缓冲固定大小，整首 PCM 写入临时文件，推理时只读取一行窗口。
- 按输出 PTS 裁剪并保留时间轴原点，包括 gapless trimming 后的起点空隙；MP3 避免近似 seek，尾部额外喂入少量数据以处理解码延迟。
- 文件在成功、失败、协程取消时关闭和删除。生成串行化，切歌/关闭开关终止旧任务；缓存校验歌曲、歌词 SHA-256 和模型/音频管线版本。
- 本地模型生成结果具有独立来源标记，允许超过原 AMLL 30 秒升级窗口后更新当前歌曲；人工逐字源的优先级保留。

## 真机结果（2026-10-11）

设备上已经缓存的标准音质 MP3：48 kHz、立体声、约 211.8 秒。音频、歌词和模型权重未加入仓库。

| 检查 | 结果 |
| --- | --- |
| 20 秒片段解码 | 538 ms |
| 10.013 秒非帧对齐起点，3 秒窗口 | 48,000 个采样 |
| 批量完整解码与单帧裁剪解码的窗口对比 | 平均绝对误差 0 |
| 英文整首解码 | 4,692 ms |
| 英文真实 ONNX + CTC 对齐 | 47/47 行，322 个单词，失败 0 行 |
| 解码与对齐总耗时 | 49,580 ms（含模型获取的调用耗时约 50,043 ms） |
| 持久化后重新读取 | 与生成文档一致 |

这些检查证明音频获取、真实推理、时间轴生成和持久化链路能够运行，**不代表逐字精度通过人工真值验收**。模型仍为 `NOT_ACCEPTED_FOR_WORD_LEVEL_ACCURACY`。日文 G2P 尚未实现，仍不可用。中文使用独立 CIF 路径；先前真机发现其复用的 ONNX direct-buffer 未限长，短句失败，已修正有效长度并添加 CIF 回归。中文逐字精度仍未人工验收。

## 复现

测试 runner 仅位于 `androidTest` APK，不进入应用 APK，无新增测试框架依赖。参数指定设备上已有的音频文件；只有指定 `songId` 时才获取对应供应商歌词并做完整生成。

```sh
gradle :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument --user 0 -w \
  -e audio cache/automix_analysis/EXISTING_FILE.media \
  -e endMs 20000 \
  -e songId MATCHING_NETEASE_SONG_ID \
  com.lladlam.melox.android.dev.test/com.lladlam.melox.core.autosync.AutoSyncDeviceRunner
```

不传 `songId` 可仅测解码及采样对比；音频必须与指定歌曲真实对应。成功输出 `AutoSync device regression passed`，包括耗时、采样差异及生成单词数。日志标签为 `MeloXAutoSync` 和 `MeloXLyricsAuto`。

## beta.4（2026-10-13）

- 模型索引固定为 `android-models-v0.1.0-beta.4`，索引 SHA-256
  `4b7b54a6f436b4f74ac6028f3876477a2d7dffe13fba557f9649577354a0f0ed`（14 093 字节）。
- en / zh 的模型字节未变（`1c821311…` / `c95f3615…`），安装时会**逐文件 SHA-256 校验后复用**，
  不重复下载；ja 换成 beta.4 的 `aL8`（129 412 601 字节，`10cfcea4…`）。
- 迁移策略：只有当已装文件与新索引逐字节匹配时才重写本地清单（只改元数据、不重下）；
  不匹配的包**保持可用**并在设置里显示“有更新”，断网/取消/失败都不会破坏已装模型。
- `extra_assets` 里的 292 MB 英文精度优先模型（`b52596cb…`）作为可选变体：安装后会替换标准模型，
  时间轴缓存通过 modelKey（含清单文本与模型哈希）自动失效。

## 自动歌词生成（本次改动）

- **逐行语言路由**：`AutoSyncText.detectLineLanguage` 按行判定 zh/en/ja；中英混排的行判为
  Unknown 并保留逐行时间（两套包都会丢字，不做近似）。
- 单一时刻只驻留一个语言模型：按语言分组处理，组间切换时会话被释放。
- 每一行失败都写出原因（不可对齐、窗口过短、模型拒绝、缺少模型、日文需要词典等），不再静默跳过。
- 缓存绑定：歌词内容哈希 + 模型/管线 key + **音频身份**（文件名+长度）+ 多语言组合 key。

## 日文（未完成，界面会如实显示）

- 已实现并接线：`JaG2p`（假名 → 48 音素表，含拗音/促音 cl/拨音 N/片假名归一）与
  日文 CTC 推理路径（音素跨度映射回原字符，保证可还原原文）。
- **未完成**：汉字读音、长音符 ー、拉丁词需要 OpenJTalk + NAIST 词典（JNI），
  且必须与参考实现对拍后才能启用。这些行会被明确拒绝并记录原因，界面也标注
  “日文 G2P 尚未打包”，不会用 ONNX 加载成功冒充生成成功。

## 日文 G2P：OpenJTalk JNI（2026-10-13）

- **实现**：把 Open JTalk 的文本前端（`text2mecab` → MeCab + NAIST 词典 → NJD 链 → JPCommon 标签）
  编译为 Android 原生库 `libmelox_ja_g2p.so`（arm64-v8a 3.11 MB / armeabi-v7a 2.30 MB，NDK 29 + CMake），
  JNI 暴露 `tokens / kana / phonemes`，与 `pyopenjtalk.g2p` 完全等价；**不含 HTS Engine/Voice**。
- **Kotlin 侧**：`JapaneseG2pMapper` 按 `src/autosync/lyrics/ja_pronunciation.py` 1:1 移植
  （NFKC、MeCab token 优先路径、DP 汉字 1..12 モーラ对齐、前缀音素计数、pau 定位、
  `map_spans_to_char_units` 的最近边界补位与 uncertain 标记），原文可无损重建。
- **主机对拍**：
  - 原生前端 vs `pyopenjtalk.g2p`：18/18 音素、18/18 假名完全一致（含汉字、片假名、长音、
    促音、拗音、助词、拉丁、数字、空格、符号）。
  - Kotlin 映射器 + JNI vs `android_export/ja/g2p_reference.py` 链：**20/20 黄金向量逐字段一致**
    （char / kana / phonemes / uncertain / reconstruct）。
- **词典**：不打包、不内置；按需从固定 GitHub release 下载（23,646,843 B，
  `fe6ba0e4…f382a`），解压约 102.3 MB，原子安装，失败/取消不破坏已装词典；含 NAIST 修改版 BSD 声明。
- **仍未完成 / 未验证**：真机 arm64 上的实际加载与推理（ADB 不可用）。
