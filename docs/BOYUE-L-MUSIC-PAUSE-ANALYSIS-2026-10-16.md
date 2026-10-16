# 博越 L 播放后立即暂停：工程与 0.2.13 定制 APK 对比分析

日期：2026-10-16。范围：静态源码、Git 历史、APK 清单与资源分析；本次只新增分析文档。

## 结论

优先恢复旧定制版的“原车蓝牙负责音乐与方控，DiPlay 保留导航”策略，并让音乐输出、音频焦点、媒体键所有权和无线交接使用同一份会话配置。

用户已确认：故障从 CarPlay 界面或 iPhone 点击播放即可触发；正常的 0.2.13 使用“原车蓝牙（音乐与方控）”。因此，优先排查原车蓝牙音乐与 CarPlay 的音源竞争；方向盘单次按键重复转发作为次级排查项。

最符合现有证据的解释是：新版仍按 DiPlay 音乐播放器的方式接管媒体音频或焦点，影响原车蓝牙音乐；原车蓝牙播放器可能随后通过 AVRCP 暂停 iPhone。这个触发链需要车机日志确认，当前证据支持将其列为首要假设。

## 分析对象与证据边界

| 对象 | 已读取的信息 |
| --- | --- |
| 当前工程 | `main`，HEAD `1b1ac5f2`；`mobile/build.gradle.kts` 声明 `0.2.20`，versionCode 39 |
| 用户提供 APK | `G:/迅雷下载/DiPlay_0.2.13_博越全功能版本.apk`，19,611,228 字节 |
| APK 自报版本 | 包名 `com.shihab.diplay`，versionName `0.2.13`，versionCode 32，minSdk 28，targetSdk 37 |
| APK SHA-256 | `84BEF06E82D1B73BDAE53EDB52640F78E7680EB3523C66A3A8354C2EB53320F0` |

使用 JADX 1.5.6 读取 APK。清单中的 Application 为 `com.sagittarius.v6.StubApplication`，加载器声明 `LIBNAME = "baiduprotect"`。静态导出结果包含加固加载器、依赖库和资源，核心 `com.shilapi.xcertplay` 业务源码处于加固保护中；JADX 报告部分反编译错误。

因此，旧版的行为证据来自 APK 的真实资源文本与用户确认；其受保护方法的内部实现仍待取得对应源码或运行时材料。Git 中官方 0.2.13 的代码仅用于辅助比较上游历史。

临时分析材料位于 `%TEMP%/diplay-boyue-analysis/old-apk`，提取的 Manifest 位于其 `resources/AndroidManifest.xml`，字符串位于 `resources/res/values-zh-rCN/strings.xml`。

## 关键差异

### 1. 旧定制版明确提供独立的音乐所有权选项

APK 中文资源第 559–562 行包含：

- `music_playback_carplay`：DiPlay（CarPlay 音乐与方控）。
- `music_playback_oem_bluetooth`：原车蓝牙（音乐与方控）。
- `music_playback_owner`：音乐播放方式。
- `music_playback_owner_description`：默认交给原车蓝牙；要求 iPhone 选择原车蓝牙输出、车机选择蓝牙媒体源；DiPlay 退出音乐播放和音乐方控焦点；导航保留独立声道；无线交接保留 DiPlay 自己的蓝牙引导连接。

APK 的 `simplerbit_adaptation_music` 和 `simplerbit_adaptation_scope` 还明确列出“原车蓝牙音乐与方控协同、无线交接联动”。这些是定制适配的直接资源证据。

### 2. 当前“车辆蓝牙音频”控制全部 CarPlay 音频能力

当前源码中未找到上述 `music_playback_owner` 设置及其文案。已有的相关设置是 `car_bluetooth_audio`，默认值为 false：

- `common/src/main/java/com/shilapi/xcertplay/AirPlayPersistence.kt:678`。
- `common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt:4921` 读取该值，随后传给 `AirPlayConfig.disableAudioOutput`。
- `shared/src/main/java/com/shilapi/xcertplay/airplay/AirPlayInfoPlist.kt:28` 据此改变音频 feature 位；第 51 行开始的分支控制整个 `audioFormats`、`audioLatencies` 声明。
- 中文设置说明要求音乐、导航、通话均通过原车蓝牙传输。

两种模式的功能边界存在实质区别：旧 APK 声明保留 DiPlay 导航独立声道；当前开关统一撤下 CarPlay 音频声明。当前设置中也没有针对旧定制版音乐所有权的迁移代码。旧版持久化键名和枚举值仍需对应源码或旧版偏好文件确认。

### 3. 当前有两处独立的音频焦点申请

| 路径 | 控制项 | 默认行为 |
| --- | --- | --- |
| `AndroidMediaSink.AudioFocusCoordinator` | `audio_focus_enabled` | 默认 false；开启后为符合条件的音轨申请焦点 |
| `CarPlayMediaKeys.start()` / `regainFocusLocked()` | `disable_media_key_audio_focus` | 默认 false，意味着媒体按键模块可以申请持续 `AUDIOFOCUS_GAIN` |

代码位置：

- `AirPlayPersistence.kt:237`、`:258`。
- `shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt:93`。
- `common/src/main/java/com/shilapi/xcertplay/CarPlayMediaKeys.kt:109`、`:262`、`:285`。

媒体音频流开始后，`AndroidMediaSink.onAudioStarted()` 调用 `onMediaAudioChanged(true)`，媒体按键模块进而创建 MediaSession 并申请焦点。iPhone 的暂停→播放状态转换也可以触发焦点重申请。

所以，关闭普通“音频焦点”后，媒体按键模块仍可成为焦点申请者。永久焦点申请可能促使原车蓝牙播放器让出音源，并间接暂停手机音乐。Android 官方文档说明了焦点申请与原播放器暂停之间的关系；具体吉利播放器的 AVRCP 行为仍需运行时证据。

参考：[Android 音频焦点管理](https://developer.android.com/media/optimize/audio-focus)。

另一个独立现象是 `AudioFocusCoordinator` 在临时失焦时将媒体音轨音量设为 0。它能解释“播放进度继续但无声”；iPhone 播放状态实际转为暂停时，应继续追踪原车 AVRCP 或发给 iPhone 的媒体命令。

### 4. 吉利方控已有一部分蓝牙协同逻辑

`common/src/main/java/com/shilapi/xcertplay/GeelyWheelKeys.kt:105` 根据 `car_bluetooth_audio` 选择注册的按键。开启时只注册 Siri 键，让原车处理音乐键；关闭时注册播放暂停与切歌键。

`GeelyWheelKeyCodes.kt` 定义了这套选择规则。它说明当前工程已有避免蓝牙与 CarPlay 方控双发的意识，但这条逻辑由“全部音频交给蓝牙”的开关驱动，尚缺旧定制版“仅音乐归原车”的统一策略。

当前 `CarPlayMediaCallback.onPause()` 会转发明确暂停；`CarPlayMediaButton.forKeyCode()` 把标准硬件 PLAY、PAUSE 映射为切换键。这套映射在上游 0.2.13 已存在。它属于收到相关事件后可能放大问题的路径；本次用户从触屏或手机即可复现，因此其优先级低于音乐所有权与焦点竞争。

### 5. 无线交接策略存在差异

当前 `shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt:1746` 在无线 iAP2 隧道就绪后，统一调用 `closeBluetoothBootstrapTransport()`，释放 DiPlay 的 CSM、RFCOMM stream 和 socket。

旧 APK 的音乐所有权描述声明：原车蓝牙音乐模式会保留自己的蓝牙引导连接。当前代码缺少按音乐所有权选择该策略的分支。

RFCOMM 引导连接、A2DP 音乐和 HFP 通话属于不同连接用途。源码中的释放调用直接作用于 DiPlay 持有的引导对象；它对这台博越 L 的原车音乐连接是否产生连带影响，需要结合连接时间与蓝牙 profile 日志判断。仅凭这一处 close 调用，无法认定整个车机蓝牙被关闭。

另有独立选项 `bt_suspend_during_carplay`，开启后通过 ADB 暂停蓝牙适配器。原车蓝牙音乐模式应让这一选项保持关闭，并在后续实现中加入所有权联动约束。

## 当前版本可先尝试的设置组合

在设置搜索中定位以下选项：

1. 开启“通过车辆蓝牙播放 CarPlay 音频（实验性）”。
2. 开启“禁用媒体按键音频焦点（实验性）”。
3. 关闭“音频焦点”。
4. 关闭“CarPlay 期间暂停车机蓝牙”。
5. 由用户重新连接 CarPlay，在 iPhone 音频输出中选原车蓝牙，在车机中选蓝牙音乐源。

这是利用现有设置进行音源隔离的组合。它会把导航和通话也交给原车蓝牙，适合先确认音乐能否恢复；旧定制版保留独立导航声道的能力需要代码适配。

## 建议的正式修复范围

1. 增加统一的音乐所有权配置，例如 `MusicPlaybackOwner.CARPLAY` / `OEM_BLUETOOTH`。用户选定后在会话创建时固定快照；音乐输出、焦点、媒体键订阅和无线交接共同读取它。保留当前“全部音频交给车辆蓝牙”的功能语义。取得旧版持久化定义后再做准确迁移。
2. 原车蓝牙音乐模式下，让原车持有音乐音源与音乐键；DiPlay 保留所需的 NowPlaying/导航状态同步。媒体键模块退出音乐焦点申请及自动重申请，并退出与 AVRCP 重复的音乐控制。Siri 与通话控制使用各自明确的策略。
3. 媒体渲染器按流类别处理：OEM 模式的媒体音乐退出本地 AudioTrack 输出及音乐焦点；导航保留独立输出。同步覆盖普通音频和 type 103 缓冲音乐路径，妥善消费或结束协议数据，避免网络队列堆积。协商字段与 iPhone 实际 A2DP 输出选择需要联调确认。
4. 无线交接增加按会话配置选择的引导连接保留策略，并确保退出、失败和下一次连接都正确回收。该分支应结合车辆记录启用，防止旧引导连接与新隧道重复处理控制消息。
5. 日志统一记录音乐所有者、音乐输出路径、焦点申请来源、媒体命令来源、iPhone 播放状态变化及引导连接释放时刻，用事件顺序确定实际触发者。

新增模式可以先放在 Advanced 的现有音视频卡片，使用现有实验性命名、多语言与重连提示规则。保存时调用 `markReconnectNeeded()`，由用户点击重连后应用。

## 车机复现时用于确定原因的证据

本次完成静态分析；尚缺故障时的车机和 iPhone 状态记录。

| 事件顺序 | 支持的原因 |
| --- | --- |
| 点击播放 → DiPlay 获得焦点 → 原车蓝牙暂停/AVRCP pause → iPhone 暂停 | 音源/焦点竞争 |
| 点击播放 → `media key pause -> CarPlay 2` 或 `media key ... -> CarPlay 3` → iPhone 暂停 | DiPlay 收到并转发了暂停或切换命令；继续查命令来源 |
| 点击播放 → `Audio: focus change=-2` → 进度继续、声音消失 | 临时失焦触发本地静音 |
| 无线交接释放引导连接 → A2DP/HFP profile 随后断开 → iPhone 暂停 | 无线交接与蓝牙音乐共存问题 |

当前诊断报告通过 `WheelKeyLog` 收集媒体键和媒体键焦点事件。建议复现后立即保存诊断报告，同时记录精确时间；原车 AVRCP 和 A2DP/HFP 变化通常还需系统 logcat 或蓝牙状态材料。

建议实施顺序：先用现有设置确认原车蓝牙音乐能够持续播放，再补齐统一音乐所有权和独立导航输出；根据无线连接日志决定引导连接保留分支。
