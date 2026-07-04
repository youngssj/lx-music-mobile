# CLAUDE.md

本项目是 **lx-music-mobile**（洛雪音乐移动端），基于 React Native 的音乐播放器，支持安卓 / iOS。主仓库 [lyswhut/lx-music-mobile](https://github.com/lyswhut/lx-music-mobile)，当前版本 1.8.2。

## 技术栈

- React Native **0.73.11** + React 18.2，Hermes 引擎，TypeScript
- 导航：**react-native-navigation 7.39**（RNN，不是 react-navigation）
- 播放器：**react-native-track-player 2.1.2**（lyswhut fork），通过 `patch-package` 在官方版上叠加定制（均衡器、setPitch、offload 协调）
- 状态管理：自研轻量 store（每个域 = `state.ts` + `action.ts` + `hook.ts`），非 Redux
- 多个原生模块走 lyswhut fork：`react-native-track-player`、`react-native-background-timer`、`react-native-file-system`、`react-native-local-media-metadata`

## 常用命令

| 命令 | 用途 |
|---|---|
| `npm run dev` | 调试安装到已连接设备（`react-native run-android --active-arch-only`） |
| `npm start` / `npm run sc` | 启动 Metro（`sc` 带 `--reset-cache`） |
| `npm run pack:android` | 构建 Release APK（输出在 `android/app/build/outputs/apk/release/`，4 个 ABI + universal） |
| `npm run clear` | Gradle clean |
| `npm run lint` / `lint:fix` | ESLint |
| `postinstall` | 自动跑 `patch-package` 应用补丁 |

> ⚠️ **在 Claude Code 的 bash 工具里 `npm run pack:android` 会失败**（npm 起的 cmd 解析不了 `gradlew.bat`，GBK 报错）。改用：`cd "D:/claude/music/lx-music-mobile/android" && ./gradlew assembleRelease`（git bash 跑 unix `gradlew`，会用 `$JAVA_HOME` 的 JDK 17）。

## 目录结构

```
src/
├── app.ts                  # 应用入口
├── core/                   # 业务核心（无 UI）
│   ├── player/             #   播放控制（player.ts 播放/切歌，playInfo, playList, playedList, tempPlayList）
│   ├── music/              #   音源 URL / 歌词 / 封面获取
│   ├── list/ search/ leaderboard/ songlist/   #   各列表业务
│   ├── init/               #   启动初始化序列（theme, i18n, player, deeplink ...）
│   ├── common.ts           #   updateSetting / initSetting / exitApp 等公共方法
│   ├── theme.ts lyric.ts sync.ts ...
├── plugins/
│   ├── player/             # TrackPlayer 封装（utils.ts=原生调用, index.ts=初始化, playList.ts, hook.ts）
│   ├── storage.ts lyric.ts sync/
├── store/                  # 状态：setting/ player/ theme/ common/ list/ search/ ... 各含 state+action+hook
├── config/                 # defaultSetting.ts, setting.ts(mergeSetting), migrateSetting.ts
├── screens/                # 页面；PlayDetail/components/SettingPopup/settings/ 下是各设置项组件
├── navigation/             # RNN 屏幕注册与路由
├── lang/                   # 多语言（zh-cn.json, en-us.json）
├── components/             # 通用组件（Text, Slider, CheckBox ...）
├── theme/                  # 主题（`npm run build:theme` 生成）
└── utils/ event/ types/ resources/
android/                    # 原生工程；签名用 android/app/release.keystore
patches/                    # patch-package 补丁（最重要的：react-native-track-player+2.1.2.patch）
```

## 关键架构

### react-native-track-player 定制
`patches/react-native-track-player+2.1.2.patch` 在官方 2.1.2 上叠加了：
- **自建 20 段均衡器** `EqualizerAudioProcessor`（软件 audio processor 链，biquad 滤波）
- `setPitch`（变速变调，官方 2.1.2 没有）
- **audio offload 协调**（均衡器 / 变速 / 变调与硬件 offload 直通互斥，见下）

改 `node_modules/react-native-track-player/` 下任何文件后，必须重新生成 patch：
```bash
rm -rf node_modules/react-native-track-player/android/{build,.gradle,.cxx}   # build 产物会污染 diff
npx patch-package react-native-track-player
```

### audio offload 与软件 audio processor 互斥 ⚠️
均衡器、`rate≠1`、`pitch≠1` 都依赖软件 audio processor 链（均衡器 / Sonic），与硬件 **audio offload 直通互斥**。`player.isEnableAudioOffload` 默认 true。

- `MusicModule.updateAudioOffload(rate, pitch)` 统一协调：仅当「用户偏好开 + 均衡器关 + rate==1 + pitch==1」时才启用 offload。
- **关键陷阱**：media3 的 offload→软件切换**不会在流内即时生效**（要等下一曲或 re-prepare）。设非默认 `PlaybackParameters` 前，必须先 `ExoPlayback.ensureSoftwareAudioPathIfNeeded`（关 offload + `seekTo(当前位置)` 强制 re-prepare），否则在走了 offload 的歌曲上会让播放器进入**不可恢复的错误状态**（播放完全不可用，只能重启）。
- 新增任何改 `PlaybackParameters` 或 audio processor 的功能，setter 都要走 `ensureSoftwareAudioPathIfNeeded`，仅调 `updateAudioOffload` 不够。

### 设置持久化
`src/config/setting.ts` 的 `mergeSetting` 合并默认设置与持久化设置——**已支持原始类型 + 数组**（旧逻辑只接受原始类型，导致 `player.equalizerBands`（`number[]`）无法持久化、进设置页不回显）。改默认值看 `src/config/defaultSetting.ts`，类型看 `src/types/app_setting.d.ts`。

调用链：`updateSetting`（`src/core/common.ts`）→ `settingActions.updateSetting`（`src/store/setting/action.ts`，内部仍调 `mergeSetting`）→ throttle 写存储。`mergeSetting` 同时被启动 `initSetting` 和运行时 `updateSetting` 复用。

### 播放器初始化
`src/core/player/player.ts` 的 `handlePlay`：首次播放时调 `playerInitial(...)`（`src/plugins/player/index.ts`），顺序为 setupPlayer → updateOptions → setVolume → setPlaybackRate → setPitch(条件) → 均衡器。

## 开发注意事项

- 改 `node_modules/react-native-track-player/` 任何文件后重新生成 patch（见上）。
- 改 node_modules 的 **JS** 文件后，Gradle 的 `createBundleReleaseJsAndAssets` 可能命中缓存打出旧 bundle；需清 `node_modules/.cache/metro` + `android/app/build/.../createBundleReleaseJsAndAssets` 再打包，并在日志里确认该任务**不是** `UP-TO-DATE`。
- Android 构建：JDK 17（`JAVA_HOME`，不是 PATH 上报的 1.8）；大陆网络下 Maven Central 易超时，已用 `~/.gradle/init.gradle` 重定向到阿里云镜像。
- 提交前跑 `npm run lint`。项目用 `eslint-config-standard-with-typescript`。
- 启动时已跳过「谨防被骗」「许可协议」弹窗（`src/core/init/index.ts` 强制 `common.isAgreePact = true`）。
