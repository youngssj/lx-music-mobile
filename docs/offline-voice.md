# 安卓离线语音助手

在“设置 → 基本设置 → 离线语音助手”开启后台监听，并授权麦克风及通知。
唤醒词为“小洛小洛”，唤醒成功后会语音回复“我在，请说”并短振动，播报结束后再说指令。回复音频内置在 APK 中，离线播放，无需安装系统语音引擎或中文语音包。播报跟随媒体音量；播报期间的麦克风音频不会送入指令识别。

语音助手的启动、准备就绪、唤醒、识别中、未听清、关闭、权限与错误提示，以及搜索进度、搜索结果、播放控制、收藏反馈和执行失败，均使用内置中文语音播报。界面和通知保留文字状态与详细错误，便于排查。播报按队列依次播放；音乐播放时会降低歌曲音量，播报和收音结束后恢复。

播报文案：`src/resources/voice/prompts.json`；内置音频：`android/app/src/main/res/raw/voice_*.wav`，使用 Microsoft Huihui 中文语音预先合成。修改文案后，在安装有 Huihui 语音的 Windows 电脑运行 `pwsh -File scripts/generate-voice-prompts.ps1` 重新生成并提交音频，无需给手机安装运行时语音合成模型。新增文案时同时添加 `VoiceFeedback.kt` 中的资源映射。缺少播报音频时构建会报错。

支持：搜索晴天、播放周杰伦的晴天、暂停、继续播放、上一首、下一首、收藏这首歌、取消收藏、关闭语音助手。
“搜索”展示聚合搜索结果，“播放”通过现有换源匹配逻辑查找歌曲并立即播放。
没有识别到支持的指令时不会执行播放器操作。语音不上传服务器，不需要识别 API Key。
在线歌曲搜索与播放仍依赖网络和自定义音源。

后台使用安卓麦克风前台服务，持续显示通知，通知按钮可以关闭监听。
只在应用可见时启动服务；未授权不会在启动时自动请求权限。服务不会在强制停止或进程被杀后自动拉起。
从最近任务划掉后能否保持服务取决于手机系统，可能需要手动允许后台运行。
应用内“退出”会停止录音。仅安卓支持，iOS 不显示该设置。

## 准备模型和构建

在项目根目录运行 `npm run voice:prepare`（需要 PowerShell 7）。
脚本从官方发布下载 sherpa-onnx 1.13.8 AAR、中文关键词唤醒模型、SenseVoice INT8 中文识别模型以及 Silero VAD。
下载的二进制文件在 git 中忽略，但随 APK 打包。无需在手机上再次下载。
下载中断可以再次执行，会续传；中文识别模型使用官方 SHA256 校验。
构建前会校验资源是否齐全，防止产出不能识别的 APK。`npm run voice:test` 检查指令解析和业务接入。
模型不压缩打包；APK 大小以实际构建为准。不要提交完整模型到普通 Git 仓库。

当前验证：指令解析与业务联动测试通过、新增 TypeScript/TSX 文件 Lint 通过、
生产 JS bundle 打包通过、VoiceService 使用实际安卓 SDK 和引擎 AAR 独立编译通过。
发布 APK 构建（含 R8 优化和签名打包）及 ARM64 调试 APK 构建已通过，语音服务已使用实际 Android SDK 36 和 sherpa-onnx AAR 编译验证；后台/锁屏唤醒仍需实机验收。默认全架构调试构建目前另有 `react-native-quick-base64` 的 x86 原生库匹配错误，不能将 ARM64 调试验证报告为全架构调试通过。
项目全量 Lint 和 TypeScript 检查存在原有错误，不能将这些检查报告为全量通过。

首次构建或在新电脑检出项目后必须运行 `npm run voice:prepare`。引擎 AAR 和模型文件不在 Git 中，缺少这些文件时 `verifyVoiceAssets` 会阻止构建。

普通发布构建使用 `npm run pack:android`（需要 PowerShell 7）；该命令通过 `scripts/build-android.ps1` 限制 prefab 等子 JVM 的内存，结束后恢复原来的 `JAVA_TOOL_OPTIONS`。Gradle 默认使用 2 GB 堆、单个工作线程、两个可用 CPU，并在同一 JVM 内编译 Kotlin；构建结束后退出，避免空闲守护进程持续占用系统提交内存。若崩溃日志中物理内存仍有余量、`AvailPageFile size` 却接近零，说明 Windows 系统提交内存已耗尽，还需要关闭暂时不用的大型应用或在 Windows 设置中为分页文件启用系统管理大小。

本机内存紧张时已验证的 ARM64 调试构建命令（在项目根目录的 PowerShell 运行）：

```powershell
$env:JAVA_TOOL_OPTIONS = '-Xms64m -Xmx512m'
Push-Location android
try {
  .\gradlew.bat assembleDebug --no-daemon --max-workers=1 '-Dorg.gradle.jvmargs=-Xms64m -Xmx2048m -XX:MaxMetaspaceSize=1024m' '-Pkotlin.compiler.execution.strategy=in-process' '-PreactNativeArchitectures=arm64-v8a'
} finally { Pop-Location }
```

调试 APK 位于 `android/app/build/outputs/apk/debug/`，运行时需要 Metro 开发服务器；发布 APK 位于 `android/app/build/outputs/apk/release/`，已内置 JavaScript bundle 和离线语音模型。

资源来源：

- https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8 （Apache-2.0）
- https://k2-fsa.github.io/sherpa/onnx/kws/pretrained_models/index.html
- https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17 （模型许可见资源目录）
- https://github.com/snakers4/silero-vad （MIT）

## 实机验收

1. 拒绝麦克风权限，应保持关闭；允许后出现常驻通知。
2. 前台、切到其他应用、锁屏分别唤醒，检查振动提示及指令执行。
3. 断网执行暂停、继续、切歌；搜在线歌曲应给出失败或无结果提示。
4. 无当前歌曲时点歌、已有歌曲时点歌，均应播放匹配到的歌曲。
5. 外放音乐、耳机以及安静环境分别检查唤醒误报和识别效果。
6. 通知关闭、设置关闭、应用退出，应停止麦克风占用并恢复播放音量。
7. 授权撤销、其他应用占用麦克风时应退出监听并显示错误。

Android AEC/降噪是设备提供的可选能力，不能保证所有设备都能抑制外放回声。
监听线程只运行小型关键词模型；完整识别模型保持加载但仅在唤醒后运行推理。
录音通过 Silero VAD 检测语音结束，静音等待最长 12 秒；识别期间暂时降低应用播放音量。
