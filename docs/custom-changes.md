# 自定义修改记录

本文件记录基于上游 [lyswhut/lx-music-mobile](https://github.com/lyswhut/lx-music-mobile) v1.8.2 的自定义改动。仅记录改动要点与关键架构，便于后续维护。

---

## 1. Android 版本号统一为 74003

把所有 Android 包（universal + 各 ABI）的 `versionCode` 统一为 **74003**。

- `package.json`：`versionCode` 由 `74` 改为 `74003`（`version` 显示版本号仍为 `1.8.2`）。
- `android/app/build.gradle`：移除按 ABI 拆分 versionCode 的逻辑（原 `versionCode * 1000 + 1/2/3/4`），改为各 ABI 直接使用 `defaultConfig.versionCode`：
  ```groovy
  output.versionCodeOverride = defaultConfig.versionCode
  ```
  并删除随之失效的 `versionCodes` ABI 映射表。

结果：universal / armeabi-v7a / x86 / arm64-v8a / x86_64 五个包的 versionCode 均为 74003。

---

## 2. 桌面歌词浮窗改造（核心）

浮窗是 Android 原生 `TYPE_APPLICATION_OVERLAY` 窗口（`android/app/src/main/java/cn/toside/music/mobile/lyric/`），仅在我们的 App 退到后台时显示。本轮对其做了多项增强。

### 2.1 边缘 / 角落长按拖拽调整大小

- **交互**：长按窗口左/右**边缘**约 300ms → 触感震动 + 高亮边框 → 进入「调整大小」模式，拖动改**宽度**；长按四个**角落** → 同时改**宽高**。中间区域拖动仍为移动，轻点仍打开 App。
- **宽度**：按屏幕宽度百分比，松手持久化到 `desktopLyric.width`（10~100）。
- **高度**：窗口高度模型始终为 `fontHeight × maxLineNum`，所以垂直缩放换算成行数 `maxLineNum` 并对齐到整行，松手持久化到 `desktopLyric.maxLineNum`（最小 1 行，上限由屏幕高度决定，**不再固定 8 行**）。
- **关键陷阱（已处理）**：
  - 窗口很扁/很窄时，边缘判定按「是否过半」划分上下/左右，避免整面落进同一个边缘区导致对侧角抓不到（拉到最扁后无法再调高度）。
  - 高度只影响「歌词区」，控制栏高度固定不参与缩放。
- 实现：`LyricView.java` 的 `onTouch`（DOWN 判定边缘/角落 + 启动长按定时器、MOVE 区分 resize/move、UP 上报）、`onEdgeLongPress`、`handleResize`、`buildResizeBackground`，以及新增事件 `set-width` / `set-max-line-num`。

### 2.2 位置 / 尺寸即时持久化

原来 `updateSetting` 的磁盘写入是 100ms 节流（`setTimeout`），应用在后台时该定时器会被系统推迟，杀进程即丢失最新位置。

- 新增 `saveSettingNow()`（`src/core/common.ts`）：立即把 `settingState.setting` 写盘（绕过节流）。
- 桌面歌词的**位置 / 宽度 / 最大行数**三个回调（`src/core/init/player/lyric.ts`）在 `updateSetting` 之后各调一次 `saveSettingNow()`，确保拖完即存、杀进程不丢。

### 2.3 修复：首次开启桌面歌词不显示

**根因**：首次安装无悬浮窗权限 → `checkOverlayPermission` reject → 弹授权提示 → 用户去系统授权后返回 App，但**返回后没有任何代码再调 `showDesktopLyric()`**，窗口从未创建，需重启 App 才生效。

- `src/components/DesktopLyricEnable.tsx`：点「同意」跳转授权页时置 `pendingPermissionRef = true`；新增 `AppState` 监听，用户返回（app 重新 `active`）时若已授权则补调 `showDesktopLyric()`，仍未授权则回退 `enable=false`。
- 顺手修复 `LyricModule.checkOverlayPermission` 在 `reject` 后又 `resolve` 的双重 settle（加 `return`）。

### 2.4 浮窗新增播放控制栏 + 关闭按钮

浮窗结构改为组合根容器：

```
WindowManager → rootView (FrameLayout)
                  ├─ contentLayout (LinearLayout 纵向)
                  │    ├─ textView (LyricSwitchView，歌词区，高度 = fontHeight×maxLineNum)
                  │    └─ controlBar (LinearLayout 横向，固定 44dp)：上一首 / 播放暂停 / 下一首
                  └─ closeButton (ImageButton，右上角 ✕)
```

- **控制栏**（始终显示在歌词下方，按钮水平+垂直居中）：上一首 / 播放-暂停 / 下一首；点击 → 上报 `control` 事件 `{action}` → JS 调 `handlePlayerAction(skipPrev/skipNext/togglePlay)`。播放/暂停图标由 JS 根据播放状态推送（`setPlaying`）。
- **关闭按钮**（右上角 ✕）：点击弹**原生 overlay 二次确认**「确认关闭桌面歌词？」（应用在后台时 JS/React 弹窗不可见，必须用原生 overlay）。确认 → 上报 `close` 事件 → JS `hideDesktopLyric()` + `desktopLyric.enable=false`（即时落盘）。
- **触摸**：`OnTouchListener` 绑在 `rootView` 上，按钮自身消费点击，不会误触发拖动/打开 App；整个卡片（含控制栏空隙）仍可拖动/缩放。
- **锁定态**：浮窗 `FLAG_NOT_TOUCHABLE` 时控制栏/关闭按钮无法点击，自动隐藏（`setVisibility(GONE)`）。
- **高度模型**：窗口总高 = 歌词区 + 控制栏；`setLayoutParamsHeight`、缩放、`setLyric` 的行数计算、当前行居中（`scrollApplyListener` 的 `viewCenter`）均以「歌词区高度」为准。
- 新增矢量图标 `res/drawable/ic_lyric_{prev,play,pause,next,close}.xml`（白色，运行时按已播放色着色）。
- 新增事件 `control` / `close`；新增 `@ReactMethod setPlaying`。

### 2.5 修复：控制按钮无效

**根因**：JS 的 `onControl` 监听漏取字段，把整个事件对象 `{action:"prev"}` 当成 action 传入，导致 `if (action === 'prev')` 永不成立。

- `src/utils/nativeModules/lyricDesktop.ts`：`onControl` 改为 `handler((event as {action}).action)`，与 `onWidthChange` 读 `.width` 一致。

### 2.6 控制按钮水平居中

`LyricView.buildControlBar()` 的 `setGravity` 由 `CENTER_VERTICAL` 改为 `CENTER`，三个按钮作为一个整体在控制栏内水平居中。

---

## 3. 涉及文件清单

**原生（Android Java）**
- `android/app/src/main/java/cn/toside/music/mobile/lyric/LyricView.java` — 浮窗核心（根容器结构、缩放、控制栏、关闭确认、高度模型、事件上报等）。
- `android/app/src/main/java/cn/toside/music/mobile/lyric/Lyric.java` — 控制器，新增 `setPlaying` 透传。
- `android/app/src/main/java/cn/toside/music/mobile/lyric/LyricModule.java` — RN bridge，新增 `@ReactMethod setPlaying`；修复 `checkOverlayPermission` 双重 settle。
- `android/app/src/main/java/cn/toside/music/mobile/lyric/LyricEvent.java` — 新增事件 `set-width` / `set-max-line-num` / `control` / `close`。
- `android/app/src/main/res/drawable/ic_lyric_{prev,play,pause,next,close}.xml` — 新增矢量图标。
- `android/app/build.gradle` — versionCode 不再按 ABI 拆分。

**JS / TS**
- `package.json` — `versionCode: 74003`。
- `src/core/common.ts` — 新增 `saveSettingNow()`。
- `src/core/desktopLyric.ts` — 重新导出新增事件 / `setDesktopLyricPlaying`；显示时推送初始播放状态。
- `src/core/init/player/lyric.ts` — 订阅位置/宽度/行数（即时持久化）、控制/关闭、播放状态同步。
- `src/utils/nativeModules/lyricDesktop.ts` — `setWidth`/`onWidthChange`、`onMaxLineNumChange`、`setPlaying`/`onControl`/`onClose` 等。
- `src/components/DesktopLyricEnable.tsx` — 授权返回后补显示桌面歌词。

> 所有原生改动均在项目 `android/app/...` 源码内（**非 node_modules**），**不需要重新生成 patch**；原生改动需重新打包 APK 才能生效（仅跑 Metro 不够）。

---

## 4. 打包 / 验证

- Android 打包（Claude Code 的 bash 里 `npm run pack:android` 会失败，改用）：
  ```bash
  cd "D:/AI/lx-music-mobile/lx-music-mobile/android" && NODE_OPTIONS=--max-old-space-size=8192 ./gradlew assembleRelease
  ```
- 编译检查：`./gradlew :app:compileDebugJavaWithJavac`。
- Lint：`npx eslint <改动的 ts 文件>`。
