# CoderLin99 博越 L 适配迁移评估

日期：2026-10-16。结论：可按功能迁移，首批建议覆盖 OneOS 输入服务、按键标准化与去重、蓝牙模式下的媒体控制所有权。

本次执行源码、Git 历史和调用链分析，只新增本报告。运行时效果需要后续车机确认。

## 比较基线

| 项目 | 固定版本 |
| --- | --- |
| 当前工程 | `G:/code/DiPlay`，HEAD `1b1ac5f27986ce83b6d17fdd8c4d1be852fbbb70`，移动端版本 0.2.20 |
| 参考分支 | `CoderLin99/DiPlay`，HEAD `138baaf8a71ce57433538801162c599e9061b745`，版本 0.2.12-geely.1 |
| 共同祖先 | `2fc876e578eba3905a5b873e3c2dbd74f498433a` |
| 参考分支目标 | 2023 款博越 L，银河 OS 2.5.0，Android 11 / FX11 |

参考仓库：[CoderLin99/DiPlay](https://github.com/CoderLin99/DiPlay/tree/138baaf8a71ce57433538801162c599e9061b745)。临时源码位于 `%TEMP%/diplay-coderlin99-review`。

两条开发线在共同祖先后各自积累了改动。采用逐项移植，保留当前工程的 API 25 兼容、缓冲音乐、音频时钟修整、焦点回调归属和重连恢复等能力。

## 可迁移项

| 功能 | 参考实现 | 当前工程情况 | 建议 |
| --- | --- | --- | --- |
| OneOS 方控服务 | 绑定 `com.geely.service.oneosapi.OneOSApiService`，向服务管理器取得输入服务 | 绑定 `com.geely.inputservice.InputService` | 高优先级；增加可选后端，根据服务能力选定一个输入来源 |
| 切歌与播放暂停键码 | 兼容 85/87/88 与 200085/200087/200088，并处理 SEEK 别名 | 吉利专用后端只处理厂商媒体码 | 高优先级；迁移别名标准化及原始事件记录 |
| 重复事件处理 | 按下/抬起状态去重；原始事件后抑制重复手势；发送端跨来源 120 ms 去重 | 存在多个独立的按键接收路径，吉利后端主要依赖短按回调 | 高优先级；统一去重入口，保留事件来源、会话和时间信息 |
| 媒体键所有权 | `forwardMedia`、`requireMediaAudio`、`mediaAudioEstablished` 共同决定是否转发 | 吉利监听器在 attach 时启动，媒体命令发送入口缺少同等完整的策略约束 | 最高优先级；在原车蓝牙模式统一让原车负责音乐控制 |
| 媒体焦点归属 | 吉利由渲染器侧管理焦点；媒体键模块使用 `manageAudioFocus=false` | 渲染器和媒体键模块有各自的焦点申请逻辑 | 最高优先级；按音频模式决定焦点负责模块 |
| 原车音频用途与录音源 | 读取框架 `AUDIO_USAGE_CP_*` / `AUDIO_SOURCE_*CP_*`，再读取 vendor 配置，再走标准回退 | 使用标准用途、legacy stream 及部分吉利麦克风特例 | 第二阶段；限制在已识别且具备对应能力的吉利设备 |
| 导航物理输出设备 | 枚举 AudioDeviceInfo，保存偏好，正式播放和试听均调用 `setPreferredDevice()` | 提供 stream 0–20 和导航衰减 | 可迁移；实际扬声器归属由车机确认 |
| Opus 软件下行解码 | `SoftwareOpusDecoder` 使用 Concentus，当前参考分支对 Opus 下行直接走软件解码 | Opus 下行使用 MediaCodec；麦克风上行已有软件编码支持 | 可选迁移；建议保留平台解码，并补软件回退 |
| 通话后恢复 | 等待通话音轨及麦克风结束，再恢复模式、焦点和音乐音轨 | 已有独立演进的通话路由、焦点及资源生命周期实现 | 按具体缺口移植，重点核对关闭顺序 |
| 原厂蓝牙交接 | 默认关闭；用户开启且收到手机交接命令后，单会话尝试断开目标手机 A2DP sink | 当前没有同一套主动 A2DP 断开模块 | 放在独立实验项；原车蓝牙音乐模式保持关闭 |

## 1. OneOS 服务与去重

参考文件：

- [GeelySteeringWheelInputChannel.kt](https://github.com/CoderLin99/DiPlay/blob/138baaf8a71ce57433538801162c599e9061b745/common/src/main/java/com/shilapi/xcertplay/GeelySteeringWheelInputChannel.kt)
- [GeelySteeringEventDecoder.kt](https://github.com/CoderLin99/DiPlay/blob/138baaf8a71ce57433538801162c599e9061b745/common/src/main/java/com/shilapi/xcertplay/GeelySteeringEventDecoder.kt)

参考后端通过 `IServiceManager` 的 transaction 2、service type 8 取得输入管理器，再使用 transaction 3/4 注册和注销监听器。当前 AIDL 的输入管理器同样将注册、注销放在 transaction 3/4。可以复用当前类型化接口，并新增服务管理器接口或封装能力探测。

当前后端保留对 `com.geely.inputservice` 的支持；OneOS 后端补充对 `com.geely.service.oneosapi` 的支持。每个会话选择一个后端，统一进入相同事件解码、归属判断和发送入口。

参考解码器会统一键码别名、抑制重复 ACTION_DOWN，并在原始按键事件结束后 1500 ms 内抑制同键的手势副本。参考发送端还有跨来源 120 ms 去重。这些时间窗口属于参考策略，迁移时应结合真实时间戳记录调整；Siri 继续保留当前长按触发规则，单独处理短按和长按的区别。

参考服务采用被动监听，原车自己的按键处理仍会执行。因此，按键所有权和蓝牙 AVRCP 协同必须与服务接入一起处理。

## 2. 播放暂停与蓝牙音乐所有权

参考文件：[CarPlayMediaKeys.kt](https://github.com/CoderLin99/DiPlay/blob/138baaf8a71ce57433538801162c599e9061b745/common/src/main/java/com/shilapi/xcertplay/CarPlayMediaKeys.kt)。

参考 `CarPlayHostActivity` 在创建媒体控制时传入：

```kotlin
forwardMedia = !airPlayConfig.disableAudioOutput
requireMediaAudio = geelyFactory != null
manageAudioFocus = geelyFactory == null && !AirPlayPersistence.loadAudioFocusEnabled(this)
```

它解决三个具体问题：

1. 原车蓝牙音频模式将音乐按键交回原车，减少 AVRCP 与 CarPlay 双发。
2. 吉利视频会话先等待实际 CarPlay 音乐流建立；建立之后，通过 `mediaAudioEstablished` 在暂停期间保留控制资格。
3. 吉利媒体键模块退出独立焦点申请，避免和音轨渲染器同时管理焦点。

当前工程已有蓝牙模式下仅注册吉利 Siri 键的逻辑，也已有“禁用媒体按键音频焦点”开关；迁移重点是把这些规则统一到会话策略，并约束全部媒体命令入口。媒体键监听、Android MediaSession、窗口和其他来源都应遵守同一归属判断。

音乐状态、实际媒体音轨与控制权限分别维护，避免每次暂停都撤销下一次播放的控制资格。新旧会话的异步回调继续使用当前工程的 controller / generation 归属保护。

这组改动最贴近本次“点击播放立即暂停”的问题。原车 AVRCP 导致暂停的具体事件链仍需车辆日志确认。

## 3. 蓝牙音频模式的已有能力

参考分支使用持久化键 `bluetooth_audio_output`，当前工程使用 `car_bluetooth_audio`。两边都通过 `AirPlayConfig.disableAudioOutput` 控制整个 CarPlay 音频能力声明；开启后均移除 `audioFormats`、`audioLatencies` 并调整音频 feature 位。

因此，当前工程已经具备参考分支蓝牙兼容模式的主要协商处理。其可补充价值集中在媒体命令、MediaSession 和焦点的联动。

此前提供的 0.2.13 SimplerBit APK 资源声明“原车蓝牙音乐＋DiPlay 独立导航＋保留蓝牙引导连接”；本次 CoderLin99 分支是另一个适配实现。其蓝牙音频开关采用全部音频交给蓝牙的方式，两者的导航能力边界需要分别对待。

## 4. 原车音频路由与麦克风

参考文件：[GeelyFactoryCarPlay.kt](https://github.com/CoderLin99/DiPlay/blob/138baaf8a71ce57433538801162c599e9061b745/shared/src/main/java/com/shilapi/xcertplay/vehicle/GeelyFactoryCarPlay.kt)。

它读取 `/vendor/etc/carplay/carplay_config.json` 的公开路由配置，并优先尝试固件框架提供的 CarPlay usage/source 字段。无效字段、模板占位值、拒绝的用途或录音源走标准回退。

迁移时需要贯穿 `AudioChannelPreview`、`AndroidMediaSink`、`MicrophoneUplink` 和设置持久化，确保试听与正式播放采用同样路由。显式保存的 stream 设置继续具有明确的优先级；设备编号在每次连接时重新解析，记录实际 routedDevice。

参考分支的焦点协调器还改变了导航、通话、Siri 和铃声优先级，并在吉利失焦时静音自身输出。当前工程已有来电处理、媒体静音和焦点恢复修复。建议在现有协调器中增加吉利策略，按设备能力控制启用范围，保留当前所有权和旧回调隔离机制。

## 5. 主动蓝牙交接的适用边界

`GeelyBluetoothAudioGuard` 的工作是通过 A2DP sink profile 11 对目标手机调用一次 `disconnect()`。前提包括用户开启 `geely_bluetooth_handoff`、使用 CarPlay 音频、收到手机交接请求且目标设备匹配。

这是把音乐从原车蓝牙交给 CarPlay 的路径。用户当前正常使用方式是原车蓝牙负责音乐，对应策略应继续保留 A2DP 连接。当前工程的蓝牙引导传输释放和整机蓝牙暂停也需独立记录，三种操作分别控制。

## 迁移实施顺序

1. 在现有吉利模块中增加 OneOS 服务后端、别名解码、事件去重和必要诊断；每个会话选定一个后端。
2. 统一原车蓝牙模式的媒体控制与焦点策略，覆盖发送、注册、重申请和会话切换。
3. 增加原车 usage/source 和导航输出设备，保留标准路由及当前用户配置。
4. 根据设备解码日志补 Opus 下行软件回退，再针对通话结束恢复补足生命周期差异。

设置按当前工程的信息架构放置：方向盘控制进入 Vehicle；普通路由选择进入 Audio；固件相关音频策略和主动 A2DP 交接进入 Advanced。新字符串覆盖所有现有语言；影响下一次连接的配置保存后显示重连提示。

参考仓库包含去重和媒体归属相关测试，可作为后续验证案例来源。首批实车验收建议覆盖触屏播放、iPhone 播放、方控切歌/播放暂停、连续暂停后再播放、原车蓝牙音源切换、来电挂断以及断线重连。参考仓库说明的构建记录与目标车辆画面反馈，各自只证明对应范围；音乐、方控和音频路由的车机效果继续以实车记录为准。

建议首批迁移“一条输入后端＋统一按键去重＋蓝牙音乐归属”这组互相关联的功能，再扩展原车音频路由。
