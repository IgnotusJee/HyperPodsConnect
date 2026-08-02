# Phase 12：发布候选与集成层收尾

状态：审计修复代码、离线门禁与真机矩阵已完成（2026-08-02）

## 目标与边界

本阶段将 Phase 11 已通过真机验收的多品牌架构收敛为发布候选基线。它只清理有静态调用证据
和版本化 IPC 替代链路的兼容代码，不新增协议命令、不外推型号能力，也不改变设备确认状态的
判定规则。

## 发布审计修复

发布候选复核发现原 version 2 广播缺少发送方认证、连接状态依赖 R8 可重命名的类名、自动连接
门禁未接入实际 A2DP 路径，以及 UI 只按同一设备比较 generation。当前代码硬切换到 version 3：

- 所有自定义 exported receiver 使用系统提供的发送包身份和逐 action allowlist，未知或空发送方
  fail closed；所有可信发送端通过 `BroadcastOptions.setShareIdentityEnabled(true)` 显式共享由系统
  认证的包身份；命令另有 30 秒时效、5 秒未来偏差和 256 项 requestId 防重放窗口；
- snapshot 使用稳定的 `IpcConnectionState`，不再序列化 Kotlin 类名；每个 Bluetooth 宿主进程
  生成独立 `hostInstanceId`，同一宿主按全局 generation/时间戳丢弃延迟快照；
- 名称提示只允许用户显式连接；A2DP 自动路径必须达到 transport/protocol evidence；
- CI 在签名和上传前执行全模块测试、Lint、AndroidTest 编译、Debug/Release 构建、whitespace
  与 fixture 隐私检查。

V3 不保留 V2 双栈。更新模块必须安装完整 APK，并重启 Bluetooth、Xiaomi Bluetooth、Settings、
MiLink 四个 LSPosed scope；未重启的旧进程不会与 V3 互通。

## 首批清理

- 删除 `OppoSystemIntegrationAdapter` 中无任何调用者的 `connectAudio` / `disconnectAudio`、
  `MediaRouter2` 扫描与路由缓存；音频路由仍由 Android 系统管理；
- 删除适配器内已由 `HyperOsHeadphoneAdapter` 取代的 MiUI refresh payload 构造器；
- 删除只写不读的 EQ、空间音频、双设备、通透人声增强和 reconnect 状态副本，以及无调用者
  helper；这些功能的唯一跨进程状态源现为 version 3 snapshot；
- 删除 `ACTION_REFRESH_STATUS`。Settings 的周期回读改用 `FeatureCommand.RefreshAll`，其他
  Hook 注册后直接请求 version 3 snapshot；
- 增加架构测试，禁止上述媒体路由分支、重复 payload 构造器和 refresh action 回流。

## 旧 action 保留矩阵

| action 组 | 当前调用者/目标 | 保留原因 | 后续删除条件 |
| --- | --- | --- | --- |
| `PODS_UI_INIT` / `MODULE_BLUETOOTH_SERVICE_ALIVE` | App、Popup、MiLink、Settings ↔ Bluetooth | 在没有 active session/snapshot 时仍可探测 LSPosed 蓝牙 scope 是否已注入 | 建立独立的版本化 runtime health IPC |
| `CONNECT_POD_REQUEST` / `DISCONNECT_POD_REQUEST` | App → Bluetooth | 携带 Android `BluetoothDevice` 并管理 session bootstrap/teardown，不是功能写命令；仅接受模块 App 发送方 | version 3 contract 建模设备候选与 session lifecycle |
| `PODS_UI_CLOSED` | App → Bluetooth | App 离开时锁定 debug raw HEX session，属于安全门禁 | raw gate 改为有租约且可自动过期 |
| `CYCLE_ANC` | Xiaomi 蓝牙通知 → Bluetooth | 系统通知 action 需要基于当前 capability 循环 ANC | 增加并验证类型化 `CycleNoiseControl` command |
| `AUTO_GAME_MODE_CHANGED` / `GAME_MODE_IMPLEMENTATION_CHANGED` / `CONFIG_CHANGED` | App → 各 Hook scope | 同步不属于耳机 snapshot 的模块配置和兼容 override | 建立版本化配置 snapshot |
| RFCOMM log/debug actions | Debug 页面 → Bluetooth | 仅接受模块 App，另有 debug-only raw gate；release 已硬禁用发送 | 建立独立的类型化 debug IPC |
| strong-toast / notification side-effect actions | Bluetooth → Xiaomi 蓝牙 | 携带 Android Parcelable 并触发通知、灵动岛等最终系统副作用 | 系统 UI 副作用迁移为稳定的明确接口 |

## 展示 DTO 评估结论

本阶段不以通用 presentation model 替换 `BatteryParams` / `PodParams`。两者虽然不属于协议
领域模型，但仍是 Xiaomi 蓝牙通知、灵动岛及 Compose 展示边界上的 Android `Parcelable`
载体；强行并入 `HeadphoneState` 会把系统 UI 结构重新泄漏到通用核心。

后续只有在通知与灵动岛副作用迁移到稳定接口、跨进程 payload 可独立版本化后，才拆除这两个
DTO。当前保留不构成协议栈双状态源：设备 confirmed 状态仍只来自 version 3 snapshot，DTO
仅由 snapshot 投影生成。

## 原始候选验证记录（V2，审计前）

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

## 审计修复验收状态

- [x] 全模块 JVM test、Android lint/compile、Debug/Release assemble 通过；
- [x] fixture 隐私扫描与 `git diff --check` 通过；
- [x] 完整 APK 安装并重启四个 LSPosed scope；
- [x] App、MiLink、Settings、Xiaomi Bluetooth 的 V3 snapshot 闭环及 App command 往返通过；
- [x] shell 伪造 V3/legacy/debug 广播全部被拒绝；
- [x] Bluetooth 宿主重启后新的 hostInstanceId/generation 1 被接受；
- [x] OPPO Air5s 与 minified Release 的 Idle/Ready 连接状态回归通过；
- [x] Sony WH-1000XM4 SPP 与 minified Release Ready 状态回归通过；
- [x] Sony LinkBuds S GATT 与 minified Release Failed/Ready 状态回归通过。

真机安全结果：完整 Debug APK 安装后，Bluetooth、Xiaomi Bluetooth、Settings、MiLink 均以新 PID
恢复；App 发送端开启 identity sharing 后启动阶段没有可信广播被误拒，随后 shell V3 广播立即以
未知发送方被拒。V3 command/event、connect/disconnect、UI init、ANC、strong-toast、RFCOMM
unlock/raw HEX 的 shell 伪造均在 payload 解析前被拒；测试前后 App profile 文件 SHA-256 不变，
Bluetooth 保持断开。

Air5s 在线后以 OPOv1 / Classic SPP 自动进入 `Ready / STABLE`，固件为 163.163.102，左右耳
100% 且均为 `WEARING`。App、MiLink、Settings、Xiaomi Bluetooth 均收到 Bluetooth 宿主发出的
V3 event；App 的降噪关闭 -> 深度往返写入与设备/UI 回读一致，Settings 显示 100% 深度，Xiaomi
Bluetooth 通知/灵动岛显示左右电量，可信流程未产生 `OppoPods-IpcSecurity` 拒绝。

Bluetooth PID 从 4111 重启为 8678 时 App PID 保持 5951；新宿主从 generation 1 依次进入
`Detecting`、`TransportConnecting`、`ProtocolHandshaking`、`LoadingCapabilities` 和 `Ready`，
旧 App 进程接受新快照并继续显示 Air5s、左右 100%、`WEARING` 和深度降噪，证明新
`hostInstanceId` 允许 generation 归一而未被旧宿主顺序阻塞。

minified Release 已完整安装并重启四作用域。Release 内部 `SessionState` 运行时类名被 R8 混淆，
但耳机页仍正确显示 Air5s、左右 100%、`WEARING` 和深度降噪，A2DP/RFCOMM 与固件回读正常，
验证 V3 稳定连接状态值不依赖类名。随后已恢复完整 Debug APK、再次重启全部作用域，确认应用为
`DEBUGGABLE` 且 Air5s 再次从 generation 1 进入 `Ready / STABLE`。

WH-1000XM4 在线后通过 Sony v1 / Classic SPP channel 9 完成握手，固件 2.5.1、单体电量 100%，
进入 `Ready / READ_ONLY`；App 只显示只读状态，不展示 ANC、通透、EQ、低延迟或空间音效写控件。
首轮 Release scope 重启暴露出 A2DP hook 在统一证据门禁前仍用 `isOppoPod` 提前返回，导致 Sony
只能通过用户显式连接进入会话。现已移除厂商短路，所有 A2DP 设备先由
`DriverRegistry.canAutoConnect()` 检查 transport/protocol evidence；拒绝候选不会修改 adapter 或
engine 状态，并增加源码架构守卫。修复后的 minified Release 和恢复后的 Debug 均在未启动 App、
未发送显式 connect 的条件下，从新 Bluetooth 进程的 generation 1 自动建立 Sony channel 9，
再次回读 2.5.1 / 100% / `READ_ONLY`。

LinkBuds S 在线后，Debug 从已连接设备行发起受限显式连接，TWS 成员被解析为当前 LE Audio
group lead；控制会话严格选择 Sony v2 / BLE GATT，依次完成 MTU 512、service discovery、
determine-MTU 与 Tandem RX 两段 CCCD、协议握手和 40 项能力加载，固件为 4.2.1，首次回读
左/右/盒 93% / 99% / 84%，进入 `Ready / STABLE`，未发生 SPP fallback。当前降噪原值为关闭，
仅执行关闭 -> 降噪 -> 关闭的最小可逆写入，两次均显示“设备已确认更改”并完成强制 GET 回读；
最终原值和 Bass Boost EQ 均保持不变。LinkBuds snapshot 同样由 Bluetooth 宿主以 V3 投递到
App、MiLink 与 Xiaomi Bluetooth，App command 往返正常且无可信流程鉴权拒绝。

minified Release 安装并重启 scope 后，耳机一度主动断开 LE 链路；GATT 连接按 5 秒门限失败并
执行有界重试，Release UI 正确显示“连接失败，请确认设备已开机且在附近”。此时 engine 日志中的
`SessionState` 类名已被 R8 混淆，仍不影响 V3 `FAILED` 展示。耳机重新连接且两个加密 LE Audio
成员恢复为 active group 后，generation 5 再次完成 Sony v2 GATT 握手，回读 4.2.1、
93% / 100% / 83% 并进入 `Ready / STABLE`；Release UI 的电量、降噪三态和 Bass Boost 均正确。
随后恢复完整 Debug APK，重启 Bluetooth、Xiaomi Bluetooth、Settings 以及 MiLink 的全部驻留
子进程；新 Bluetooth 宿主从 generation 1 再次进入 `Ready / STABLE`，最终回读
4.2.1、93% / 99% / 83%。

离线结果：281 tests、0 failures、0 skipped；Lint 为 0 errors、75 warnings、33 hints；
Debug/Release APK 均成功生成。Release `classes.dex` 只包含 V3 action，并保留显式
`TRANSPORT_CONNECTING`、`PROTOCOL_HANDSHAKING`、`LOADING_CAPABILITIES`、
`SYNCHRONIZING_STATE`、`RECONNECTING`、`DISCONNECTING` 等稳定序列化值；主源码不存在
V2 command/event action。

## 阶段结论

Phase 12 原始静态清理、发布审计修复、离线质量门与真机矩阵均已完成。当前代码形成可发布候选
基线；本轮未创建发布标签、Release 或商店制品。
