# MeloX Android

<p align="center">
  <img src="docs/icon.png" width="144" alt="MeloX Android" />
</p>

<h3 align="center">把音乐，重新交还给 Android。</h3>

<p align="center">
  一个真正原生、重视播放体验、歌词表现与系统融合的现代 Android 音乐客户端
</p>

<p align="center">
  <a href="https://github.com/XTH-LOVE/MeloX-Android/releases"><img src="https://img.shields.io/github/downloads/XTH-LOVE/MeloX-Android/total?label=downloads&color=2ea44f" alt="Downloads"></a>
  <a href="https://github.com/XTH-LOVE/MeloX-Android/releases/latest"><img src="https://img.shields.io/github/v/release/XTH-LOVE/MeloX-Android?display_name=release&label=release&color=ff2d55" alt="Release"></a>
  <a href="https://github.com/XTH-LOVE/MeloX-Android/actions"><img src="https://img.shields.io/github/actions/workflow/status/XTH-LOVE/MeloX-Android/ci.yml?branch=main&label=CI" alt="CI"></a>
  <a href="https://github.com/XTH-LOVE/MeloX-Android/blob/main/LICENSE"><img src="https://img.shields.io/badge/license-GPLv3-2563eb" alt="GPLv3"></a>
</p>

<p align="center">
  <a href="README.en.md">English</a>
  ·
  <a href="README.zh-Hans.md">完整中文说明</a>
  ·
  <a href="https://github.com/XTH-LOVE/MeloX-Android/releases">下载 Release</a>
</p>

> MeloX Android 是 MeloX 的原生 Android 迁移版。它不是 WebView 套壳，也不是网易云音乐官方客户端，而是使用 Kotlin、Jetpack Compose 与 AndroidX Media3 重新构建的一套完整音乐体验。

## 为什么是 MeloX

很多音乐客户端能“播放一首歌”，但很少有客户端愿意把播放链路、歌词、通知、锁屏、横竖屏、动态背景和系统能力当成一个整体来打磨。

MeloX 的目标很明确：

- **播放要可靠**：队列、后台播放、MediaSession、锁屏控制和音质选择都属于核心体验，而不是附加功能；
- **歌词要有表现力**：逐行、逐字、翻译、罗马音、歌词通知、悬浮歌词与横屏歌词统一在一套播放状态中；
- **界面要像 Android 应用**：Compose 原生渲染、共享元素转场、液态玻璃材质和动态取色，不依赖网页容器；
- **系统要真正参与**：媒体通知、控制中心、锁屏、HyperOS Focus / Super Island 和可变刷新率都能接入播放状态；
- **数据要属于用户**：账号、音乐库、下载、离线副本和用户音源各自独立，第三方服务失败时明确反馈原因。

## 能力地图

### 网易云音乐深度体验

- Web 登录、扫码登录、手机号验证码登录与持久化 `MUSIC_U` 会话；
- 首页推荐、发现、搜索、排行榜、每日推荐、私人 FM、心动模式；
- 歌单、专辑、艺人、播客、评论、歌曲百科与网易云音乐云盘；
- 我喜欢的音乐、最近播放、歌单编辑、云盘上传/删除和本地音乐库；
- 根据账号权益、地区与服务端结果请求标准、高品质、无损、Hi-Res、环绕声和母带音质。

### 一个完整的播放系统

- Media3 / ExoPlayer 播放引擎与 `MediaSessionService` 后台播放；
- MiniPlayer、Apple Music 风格播放器、Classic 播放器和横屏布局；
- 播放队列、下一首播放、手动队列、拖动进度、上一首/下一首；
- 锁屏信息、系统媒体通知、控制中心按钮和播放状态同步；
- AutoMix 双播放器预加载、节拍/速度分析、淡入淡出、智能过渡与十段均衡器；
- 睡眠定时、播放缓存、下载修复、离线播放和 MediaStore 导出。

### 歌词，不只是几行文字

- LRC 逐行歌词与官方 YRC 逐字歌词；
- 当前行跟随、逐字进度、翻译、罗马音、Ruby 注音和间奏倒计时；
- 点击跳转、长按分享、拖尾滚动、回弹、高光、模糊和长音表现；
- 独立歌词通知、系统媒体歌词、可拖动悬浮歌词和 Skyline 横屏歌词；
- 本地 AutoSync：在设备端为缺少逐字时间轴的歌曲生成对齐结果，并保留人工时间轴优先级。

### 多服务与用户音源

MeloX 将“歌曲信息”和“播放资源”分开处理：网易云条目可以保留原有标题、歌手、专辑、歌词、收藏和歌单身份，播放资源则在明确不可用时按策略尝试兼容来源。

当前代码包含：

- 网易云音乐、QQ 音乐、酷狗、酷我、Bilibili、YouTube Music、Spotify、Apple Music；
- LX Music 兼容 JavaScript 音源；
- 受限 QuickJS 运行时，脚本与用户数据隔离；
- provider-neutral 下载与离线播放链路；
- 可选 CHKSZ 解析服务，API Key 只保存在本机；
- 严格的标题、主艺人和时长匹配，拒绝使用试听片段或伪造播放成功。

第三方音源依赖外部服务，可能受接口变更、限流、地区、版权、账号权限、DNS 或证书问题影响。MeloX 会把失败当成失败处理，不把搜索结果误报为可播放资源。

### Android 系统融合

- 标准 Android 媒体通知与 MediaSession；
- 小米 HyperOS Focus Notification / Super Island 适配；
- vivo OriginOS 原子岛尝试接入；
- 可变刷新率、系统动画倍率、音量键桥接与触感反馈；
- 无 Root 也可使用核心功能，OEM 增强能力不可用时自动降级。

## 技术底座

| 层级 | 实现 |
| --- | --- |
| UI | Kotlin + Jetpack Compose |
| 导航与生命周期 | AndroidX Navigation / Lifecycle |
| 播放 | AndroidX Media3 / ExoPlayer |
| 网络 | OkHttp、网易云 EAPI / WeAPI |
| 图片与封面 | Coil 3 |
| 异步 | Kotlin Coroutines |
| 歌词与分析 | LRC / YRC / TTML、ONNX Runtime、OpenJTalk |
| 用户音源 | QuickJS 沙箱运行时 |
| 系统增强 | MediaSession、HyperOS Focus、OriginOS 适配 |
| 性能 | Baseline Profile、R8、资源压缩、双 ABI Release 包 |

## 当前版本

| 项目 | 状态 |
| --- | --- |
| 版本 | `0.6.5` |
| versionCode | `23` |
| 最低 Android | Android 7.0（API 24） |
| 编译 / 目标 SDK | Android 37 |
| Release applicationId | `com.lladlam.melox.android.fork` |
| 许可证 | GNU GPLv3 |

`0.6.5` 包含签名热更新通道：已签名云控可以提供受限文案覆盖与 QuickJS 行为钩子；未授权、验签失败、版本不匹配、脚本异常或超时都会回落到内置行为。

## 快速开始

### Android Studio

1. 克隆仓库；
2. 使用 Android Studio 打开 `android/`；
3. 安装 Android SDK 37，使用 JDK 17；
4. 同步 Gradle 后运行 Release 相关任务。

### Release 构建

Release APK 必须使用项目签名材料。可以在 `android/keystore.properties` 中配置，或通过 Gradle 用户属性传入：

```text
meloxReleaseStoreFile
meloxReleaseStorePassword
meloxReleaseKeyAlias
meloxReleaseKeyPassword
```

```bash
cd android
./gradlew :app:assembleRelease --no-daemon --max-workers=2
```

产物：

```text
android/app/build/outputs/apk/release/app-release.apk
```

仓库不会提交签名文件、APK、Gradle 缓存、CMake 缓存或本地构建产物。CI 只编译 Release Kotlin 源码，不构建 Debug APK。

## 工程结构

```text
.
├── android/
│   ├── app/src/main/kotlin/com/lladlam/melox/
│   │   ├── core/       # 账号、网络、音乐库、歌词、下载、更新、音源
│   │   ├── playback/   # Media3 播放服务、队列与资源解析
│   │   ├── platform/   # Android、HyperOS、悬浮歌词等平台适配
│   │   └── ui/         # Compose 页面、播放器、歌词、设置
│   ├── innertube/      # YouTube Music InnerTube 模块
│   └── baselineprofile/
├── docs/               # 架构、审计、已知问题、版本记录
├── tools/              # Release 与 APK 辅助脚本
└── .github/workflows/  # Release 源码 CI
```

## 开源与合规

MeloX Android 基于 [lladlam/MeloX](https://github.com/lladlam/MeloX) 的设计、交互与业务逻辑进行 Android 原生迁移，同时使用或参考 AndroidX、Media3、Coil、OkHttp、Kotlin Coroutines、librespot-java、Metrolist InnerTube、NewPipeExtractor、Kyant0/AndroidLiquidGlass 等项目。

本项目是非官方客户端，与网易云音乐、小米、Apple 或其他音乐服务提供商不存在隶属、合作、赞助或授权关系。项目不以绕过付费、版权、地区限制、DRM 或平台安全措施为目的；用户应自行遵守所在地法律、服务条款和内容版权要求。

主体代码采用 GNU GPLv3，详见 [LICENSE](LICENSE)。第三方代码、库、模型、音源脚本和资源继续适用各自许可证。

## 相关链接

- [GitHub Releases](https://github.com/XTH-LOVE/MeloX-Android/releases)
- [更新日志](CHANGELOG.md)
- [完整中文说明](README.zh-Hans.md)
- [English README](README.en.md)
- [问题反馈](https://github.com/XTH-LOVE/MeloX-Android/issues)

MeloX Android 仍在持续迭代。接口会变化，系统会变化，音乐应用也应该继续进化。
