# MeloX Android（弥乐）

[English README](README.en.md)

![MeloX Android](docs/icon.png)

MeloX Android 是 MeloX 的原生 Android 版本：使用 Kotlin 和 Jetpack Compose 重建界面，以 AndroidX Media3 / ExoPlayer 负责播放、后台音频和系统媒体控制。它不是 WebView 套壳，也不是网易云音乐官方客户端。

> 项目仍在持续开发中。第三方音乐服务的接口、登录方式、播放权限和设备兼容性都可能变化。

## 项目亮点

- 网易云音乐网页登录、扫码登录、手机号验证码登录和持久化登录态；
- 首页推荐、发现、搜索、音乐库、歌单、专辑、艺人、播客、评论和云盘；
- 播放队列、后台播放、锁屏控制、系统媒体通知、睡眠定时和十段均衡器；
- LRC / YRC 歌词、翻译、罗马音、歌词通知、悬浮歌词和横屏歌词；
- AutoMix 音频分析、预加载、节拍匹配、淡入淡出和智能过渡；
- 网易云、QQ 音乐、酷狗、酷我、哔哩哔哩、YouTube Music、Spotify、Apple Music 以及 LX Music 兼容用户音源；
- HyperOS 焦点通知 / 超级岛适配，不支持 OEM 扩展的设备仍可使用标准 Android 媒体通知；
- 本地下载、离线播放、下载修复、音乐识别和一起听；
- 受限 QuickJS 用户音源运行时，以及本地 AutoSync 逐字歌词模型。

第三方音源需要在应用内单独启用并阅读对应协议。外部服务不可用时，应用会显示实际失败原因，不保证任何第三方接口长期可用。

## 当前版本

- 版本名称：`0.6.5`
- 版本号：`23`
- 最低 Android 版本：`Android 7.0 (API 24)`
- 编译 / 目标 SDK：`37`
- Release 包名：`com.lladlam.melox.android.fork`

## 构建环境

- Android Studio（打开 `android/` 目录）
- JDK 17
- Android SDK 37
- Android NDK `29.0.14206865`
- Gradle 9.5.1

### Release 构建

Release 构建必须提供项目签名材料。可以在 `android/keystore.properties` 中配置，也可以通过 Gradle 用户属性传入：

```text
meloxReleaseStoreFile
meloxReleaseStorePassword
meloxReleaseKeyAlias
meloxReleaseKeyPassword
```

然后执行：

```bash
cd android
./gradlew :app:assembleRelease --no-daemon --max-workers=2
```

Release APK 输出到：

```text
android/app/build/outputs/apk/release/app-release.apk
```

签名文件、`keystore.properties`、APK、Gradle 缓存和 CMake 缓存均不会提交到仓库。

GitHub Actions 只编译 Release Kotlin 源码，不生成 Debug APK。

## GitHub Actions

`.github/workflows/ci.yml` 会在 `main` 推送、Pull Request 或手动触发时：

1. 安装 JDK 17、Android SDK 37.0 和 Build Tools；
2. 编译 Release Kotlin 源码。

正式 Release 应在配置签名材料后构建，并将 APK 附加到对应版本的 GitHub Release。应用内更新检查和下载均指向本仓库：

<https://github.com/XTH-LOVE/MeloX-Android>

## 项目结构

```text
.
├── android/
│   ├── app/src/main/kotlin/com/lladlam/melox/
│   │   ├── core/       # 账号、网络、音乐库、歌词、下载、更新
│   │   ├── playback/   # Media3 播放服务和播放资源解析
│   │   ├── platform/   # Android、HyperOS、悬浮歌词适配
│   │   └── ui/         # Compose 页面、播放器、歌词和设置
│   ├── innertube/      # YouTube Music InnerTube 模块
│   └── settings.gradle.kts
├── docs/               # 设计说明、已知问题和合规文档
├── tools/              # Release 辅助脚本
└── .github/workflows/  # CI 构建工作流
```

## 上游与第三方项目

MeloX Android 的设计和业务逻辑来自 [lladlam/MeloX](https://github.com/lladlam/MeloX)。项目还使用或参考了 AndroidX、Jetpack Compose、Media3、Coil、OkHttp、Kotlin Coroutines、librespot-java、Metrolist InnerTube、Kyant0/AndroidLiquidGlass、NewPipeExtractor 和其他开源项目。

第三方代码、库、模型、音源脚本和资源继续适用各自许可证。请查看项目内的许可证文件、`docs/` 文档和对应上游项目。

## 免责声明

MeloX Android 是非官方开源项目，与网易云音乐、小米、Apple 或其他音乐服务提供商不存在隶属、合作或授权关系。

- 本项目不以绕过付费、版权、地区限制或平台安全措施为目的；
- 用户应遵守当地法律、音乐服务条款以及内容版权要求；
- 第三方接口可能失效、限流或返回不可播放资源；
- 本项目按许可证提供，不对可用性或数据安全作额外保证。

## 许可证

项目主体代码使用 GNU GPLv3 发布，详见 [LICENSE](LICENSE)。

提交代码、修改或再分发时，请保留版权声明、修改说明和第三方许可证信息。
