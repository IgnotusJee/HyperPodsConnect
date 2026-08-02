# Phase 12：发布候选与集成层收尾

状态：已完成（2026-08-02）

## 目标与边界

本阶段将 Phase 11 已通过真机验收的多品牌架构收敛为发布候选基线。它只清理有静态调用证据
和 version 2 替代链路的兼容代码，不新增协议命令、不外推型号能力，也不改变设备确认状态的
判定规则。

## 首批清理

- 删除 `OppoSystemIntegrationAdapter` 中无任何调用者的 `connectAudio` / `disconnectAudio`、
  `MediaRouter2` 扫描与路由缓存；音频路由仍由 Android 系统管理；
- 删除适配器内已由 `HyperOsHeadphoneAdapter` 取代的 MiUI refresh payload 构造器；
- 删除只写不读的 EQ、空间音频、双设备、通透人声增强和 reconnect 状态副本，以及无调用者
  helper；这些功能的唯一跨进程状态源仍为 version 2 snapshot；
- 删除 `ACTION_REFRESH_STATUS`。Settings 的周期回读改用 `FeatureCommand.RefreshAll`，其他
  Hook 注册后直接请求 version 2 snapshot；
- 增加架构测试，禁止上述媒体路由分支、重复 payload 构造器和 refresh action 回流。

## 旧 action 保留矩阵

| action 组 | 当前调用者/目标 | 保留原因 | 后续删除条件 |
| --- | --- | --- | --- |
| `PODS_UI_INIT` / `MODULE_BLUETOOTH_SERVICE_ALIVE` | App、Popup、MiLink、Settings ↔ Bluetooth | 在没有 active session/snapshot 时仍可探测 LSPosed 蓝牙 scope 是否已注入 | 建立独立的版本化 runtime health IPC |
| `CONNECT_POD_REQUEST` / `DISCONNECT_POD_REQUEST` | App → Bluetooth | 携带 Android `BluetoothDevice` 并管理 session bootstrap/teardown，不是功能写命令 | version 2 contract 建模设备候选与 session lifecycle |
| `PODS_UI_CLOSED` | App → Bluetooth | App 离开时锁定 debug raw HEX session，属于安全门禁 | raw gate 改为有租约且可自动过期 |
| `CYCLE_ANC` | Xiaomi 蓝牙通知 → Bluetooth | 系统通知 action 需要基于当前 capability 循环 ANC | 增加并验证类型化 `CycleNoiseControl` command |
| `AUTO_GAME_MODE_CHANGED` / `GAME_MODE_IMPLEMENTATION_CHANGED` / `CONFIG_CHANGED` | App → 各 Hook scope | 同步不属于耳机 snapshot 的模块配置和兼容 override | 建立版本化配置 snapshot |
| RFCOMM log/debug actions | Debug 页面 → Bluetooth | 控制进程内日志和 debug-only raw gate；release 已硬禁用发送 | 建立受同等 release/session-token 门禁保护的 debug IPC |
| strong-toast / notification side-effect actions | Bluetooth → Xiaomi 蓝牙 | 携带 Android Parcelable 并触发通知、灵动岛等最终系统副作用 | 系统 UI 副作用迁移为稳定的明确接口 |

## 展示 DTO 评估结论

本阶段不以通用 presentation model 替换 `BatteryParams` / `PodParams`。两者虽然不属于协议
领域模型，但仍是 Xiaomi 蓝牙通知、灵动岛及 Compose 展示边界上的 Android `Parcelable`
载体；强行并入 `HeadphoneState` 会把系统 UI 结构重新泄漏到通用核心。

后续只有在通知与灵动岛副作用迁移到稳定接口、跨进程 payload 可独立版本化后，才拆除这两个
DTO。当前保留不构成协议栈双状态源：设备 confirmed 状态仍只来自 version 2 snapshot，DTO
仅由 snapshot 投影生成。

## 验证记录

- `:app:testDebugUnitTest`：通过，116 tests；
- `:core:test`、`:engine:test`、`:protocol:oppo:test`、`:protocol:sony:test`、
  `:transport:android:testDebugUnitTest`：通过；
- `:app:compileDebugAndroidTestKotlin`、`:app:assembleDebug`、`:app:assembleRelease`：通过；
- `:app:lintDebug`：修复两处 `LocalContextGetResourceValueCall` 后通过；
- fixture/测试敏感字段扫描未发现 token 或 ADB serial；一处误用真机 MAC 的身份格式测试已改用
  本地管理的虚构地址 `02:00:00:00:00:01`；
- `git diff --check`：通过；
- 使用 `:app:installDebug` 将完整模块 APK 安装到真机，并按要求重启 Bluetooth、Xiaomi
  Bluetooth、Settings、MiLink 四个 LSPosed scope；四个进程均以新 PID 恢复；
- Air5s 自动恢复 A2DP 与 RFCOMM channel 5 连接；App 显示模块已激活、模块服务已连接，
  耳机快照显示左右耳电量均为 100%；
- 系统 `MiuiHeadsetActivity` 可正常打开并显示左右耳 100%、盒子不可用、当前降噪关闭，
  页面与相关 scope 均无崩溃；
- 广播历史确认 Settings 发送 `HEADPHONE_COMMAND_V2`，Bluetooth 随即返回
  `HEADPHONE_EVENT_V2`；设备保护存储中的最新 snapshot 与页面一致，证明删除
  `ACTION_REFRESH_STATUS` 后的 version 2 回读闭环生效；
- 重启 Bluetooth scope 后，Air5s 仍会报告未佩戴且降噪关闭，这是 Phase 11 已记录的设备行为，
  不属于本阶段兼容清理回归。

## 阶段结论

Phase 12 的静态清理、兼容边界记录、构建门禁和真机最小回归均已完成。当前代码形成发布候选
基线，但本次只关闭重构阶段，不创建发布标签、不生成 Release，也不执行商店或制品发布。
