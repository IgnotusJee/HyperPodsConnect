# Phase 11：兼容性扩展和清理

状态：代码侧完成，真机验收进行中。启动日期：2026-08-01。

## 已完成的第一批基础工作

- `DeviceProfileArchive` schema v1 已落地，包含设备/厂商、型号、固件、当前与历史地址、
  transport、协议版本、command table、逐功能 evidence、兼容等级、最后连接时间、UI 资源
  和用户 override；
- archive 使用 capability fingerprint 防止损坏或被篡改的 profile 静默进入运行环境；
- 无 `schemaVersion` 的 v0 archive 会迁移为 v1，未来版本会 fail closed；
- App 会从 v2 snapshot 自动保存 profile，并一次性复制旧 `PodImagePrefs` 数据；旧 key
  不删除，以保留回滚能力；
- 设置页已提供 JSON 兼容档案导入/导出；导入按 `DeviceId` 合并历史地址、资源和 override；
- 已删除 `HeadphoneIpcEventBridge` 及其 snapshot 到逐功能旧广播的转换；App、MiLink、
  系统设置和上游蓝牙 Hook 直接观察同一份 `HeadphoneUiStore`，HyperOS 必需的旧 DTO
  只在最终适配边界由 `HeadphoneStateProjection` 生成；
- 新增 `STABLE` 等级。未命中精确矩阵的已验证写能力仍为 `CONTROLLED`，只读握手为
  `READ_ONLY`，名称/UUID 提示仍为 `DETECTED`；
- 第一版精确矩阵登记 OPPO Enco Air5s 163.163.102 / SPP，以及 Sony LinkBuds S
  4.2.1 / BLE GATT 为 `STABLE`；Sony WH-1000XM4 2.5.1 / SPP 为 `READ_ONLY`；
- release 构建显式设置 `ALLOW_RAW_PROTOCOL_CONSOLE=false`，runtime host 在进入 session
  前再次拒绝 raw frame。FOTA、关机、恢复出厂、配对管理和查找设备继续编译为禁用；
- 产品显示名称与 Gradle root project 统一为 **HyperPods Connect**，设备选择页不再按
  OPPO 名称优先排序。
- 已删除 `RfcommController` facade：剩余通知、媒体路由、系统电量和兼容广播集中到命名
  明确的 `OppoSystemIntegrationAdapter`；会话 authority、driver 与协议处理继续只属于
  `BluetoothProcessRuntimeHost` / engine / protocol modules。
- 已删除无调用者的六个旧写广播入口（ANC、低延迟、透明人声增强、空间音频、EQ、双设备）；
  UI 与 HyperOS 写操作统一走版本化 `FeatureCommand`。架构测试阻止这些 action 回流；
- 新增厂商接入清单与依赖边界测试已落地，`:core`、`:engine`、`:transport:android` 不得
  依赖 OPPO/Sony protocol module 或 `:app`。
- adapter 不再把 snapshot 重发为连接、电量、佩戴、ANC、低延迟、空间音频、EQ 或双设备
  旧状态广播；App、MiLink、Settings 与 Xiaomi 蓝牙 Hook 的状态来源只剩版本化 snapshot。
  同时删除了无调用者的 RFCOMM connection observable 与旧 Intent 电量解析器；
- 智能 ANC 当前强度已建模为通用 `HeadphoneState.noiseControlActiveMode`，通过版本化
  snapshot 到达 UI，最后一个逐功能状态广播及 OPPO session 专用事件已删除。
- 内部 action 容器已改名为 `LegacyPodsAction`；保留字符串有精确 allowlist 测试，新增
  功能不得再向该容器添加逐功能 command/state action。

## 产品身份决策

本阶段保留 `applicationId = moe.chenxy.oppopods`、Java/Kotlin package、provider authority
和旧 action 字符串。这些值已经是安装升级、LSPosed 模块授权、作用域配置和跨进程 IPC
身份；直接修改会被 Android 视为新应用，并使现有用户配置与授权失联。

因此本阶段只迁移产品显示名称。内部兼容标识可在未来通过“新旧双注册 -> 数据/授权迁移
-> 旧标识退役”的独立版本处理，不与协议重构合并。

## 后续工作

1. 继续缩减 `OppoSystemIntegrationAdapter` 的旧 action 入口；能由版本化 command/snapshot
   覆盖的路径直接删除，只保留确有 HyperOS 兼容需求的最终系统副作用；
2. 对新增型号/固件/OEM 采集动态证据后再扩展精确矩阵；没有真机证据的名称条目不得
   提升为 `READ_ONLY`、`CONTROLLED` 或 `STABLE`；
3. `BatteryParams`/`PodParams` 仍是通知、灵动岛和 Compose 展示模型，不再作为状态 IPC；
   是否进一步替换为通用 presentation model 可单独进行，不影响跨进程状态链。

新增厂商或型号必须按
[`VENDOR_EXTENSION_CHECKLIST.md`](VENDOR_EXTENSION_CHECKLIST.md) 完成证据、模块边界、
compatibility level、fixture 和回归验证；不得通过在通用 transport/core 中增加厂商分支
来接入。

## 当前验证

启动批次已通过：

- `:core:test`；
- `:protocol:oppo:test`；
- `:protocol:sony:test`；
- `:app:testDebugUnitTest`；
- `:app:assembleRelease`。

Release 构建仅出现项目既有的 compileSdk 37 / AGP 9.1 支持范围提示、
`extractNativeLibs` manifest 提示及若干 deprecated API warning；无失败。

收尾批次再次通过：

- `:core:test`、`:engine:test`；
- `:protocol:oppo:test`、`:protocol:sony:test`；
- `:transport:android:testDebugUnitTest`、`:app:testDebugUnitTest`；
- `:app:assembleRelease`。

## 2026-08-01 真机验收进度

验证环境为 Xiaomi 2210132C、Android 16 / API 36、LSPosed 2.1.1 (7790) / API 102。
Debug APK 使用 `:app:installDebug` 完整安装，并在安装后重启 `com.android.bluetooth`、
`com.milink.service` 和 `com.xiaomi.bluetooth` 三个 scope。模块页确认模块已激活、模块服务
已连接，蓝牙在 scope 重启后保持开启。

已通过：

- 全量 JVM 测试及 Debug/Release assemble 再次通过；profile archive、Phase 11 依赖边界和
  raw HEX session gate 的定向测试均无失败；
- LinkBuds S 4.2.1 通过 Sony V2 / BLE GATT 完整握手，型号、固件、左右耳与耳机盒电量、
  NC/ambient 和 EQ 初始状态均由真机回包确认，compatibility 为 `STABLE`；
- LinkBuds S 完成 `OFF -> NC -> OFF` 及 `Bass Boost -> Speech -> Bass Boost` 可逆控制。
  两组操作均包含 SET、设备通知、强制 GET/RET 和最终 UI 确认；蓝牙宿主进程未重启，
  session generation 始终为 1；
- 未佩戴时请求 transparency，耳机先 ACK/NTFY 目标值、随后在强制 GET 中返回 OFF；App
  正确判定超时并回滚到设备确认状态。该结果证明失败回滚生效，但不代替佩戴状态下的
  transparency、环境声等级及语音模式验收；
- 佩戴后设备主动进入 transparency。随后完成环境声等级 `20 -> 19 -> 20`、
  `普通通透 -> 人声增强 -> 普通通透` 两组可逆控制，每次均收到 SET、设备通知和强制
  GET/RET；最后恢复为 `OFF / level 20 / Bass Boost`，UI 显示设备已确认更改；
- WH-1000XM4 2.5.1 通过 Sony V1 / Classic SPP 握手，SDP 解析到 RFCOMM channel 9，
  型号、固件和单体电量 100% 均由设备回包确认；最终 compatibility 为 `READ_ONLY`，
  页面只显示电量和型号，未展示任何写控件，握手日志也不存在控制 SET；
- WH-1000XM4 会话建立后仅冷启动 App，App PID 变化但蓝牙宿主 PID 不变；页面仍恢复
  `WH-1000XM4 / 100%` 的只读状态，档案落盘为
  `sony / v1 / CLASSIC_SPP / 2.5.1 / READ_ONLY`；
- 仅 force-stop 并冷启动 App 后，App PID 变化而 `com.android.bluetooth` PID 不变，Sony
  session generation 仍为 1；重新进入耳机页可恢复同一型号、固件、电量、NC 和 EQ 状态；
- schema v1 profile 已真实落盘，Sony 档案为 `BLE_GATT / 4.2.1 / STABLE`；旧
  `earphone_prefs_json` 与一次性迁移标志仍存在。设备端 JSON 导出得到 schema v1、4 个
  profile，随后通过系统文件选择器回导，schema 和 profile 数量保持不变；
- v2 snapshot 的 Android 广播历史确认 App、MiLink、Xiaomi Bluetooth 和 Settings 四个
  目标均已 dispatch/finish。MiLink 进程也实际被 snapshot 广播唤醒；
- Release APK 覆盖安装后不是 debuggable，RFCOMM 调试页只显示 Release 禁用提示，不存在
  解锁、HEX 输入或发送控件；重新用 `:app:installDebug` 安装后，Debug 页在未取得当前页面
  session token 前同样不显示 HEX 输入和发送控件；
- Debug -> Release -> Debug 覆盖安装期间 `applicationId`、first install time、应用数据、
  LSPosed 激活状态与 scope 均保留。
- OPPO Enco Air5s 163.163.102 通过 OPOv1 / Classic SPP 完整握手，左右电量、佩戴、固件、
  ANC、EQ、低延迟、空间音效开关和双设备状态均由设备回包确认，compatibility 为 `STABLE`；
- Air5s 完成智能、轻度、中度、深度、普通通透、人声增强和关闭的完整 ANC 可逆矩阵；
  智能 ANC 的当前强度辅助高亮也收到设备通知。游戏模式、空间音效和双设备均完成
  `OFF -> ON -> OFF`，最后恢复为 `Authentic / 游戏关闭 / 空间音效关闭 / 双设备关闭`；
- Air5s 的 5 个 EQ 预设 `Authentic / Detail / Vocal / Bass / Dynaudio` 均完成写入和强制
  回读。真机暴露出兼容表把非连续的 Dynaudio 协议 ID `7` 误写为 `4`，现已修正为
  `oppo:7` 并增加回归测试；
- Air5s 佩戴通知原本只更新统一状态，未把 profile 中的 `WEAR_DETECTION` 从 `ASSUMED`
  提升为 `VERIFIED`。现已在成功解析设备通知后提升证据并增加 session 回归测试，真机
  archive 已确认 `WEAR_DETECTION / VERIFIED`；
- HyperOS 系统耳机通知/灵动岛显示 Air5s 左右电量，媒体路由选中蓝牙 A2DP；Bluetooth
  Hook 持续向 App、MiLink、Settings 和 Xiaomi Bluetooth 分发 v2 snapshot。空间音效切换未影响
  OPPO 控制闭环，目标进程缺少的 MiLink API 仍按设计安全跳过；
- 两次蓝牙 scope 重启和一次整机重启后 Air5s 均自动重连。仅重启 App 时 App PID 改变而
  Bluetooth PID 不变；整机重启后 schema v1 的 4 个 profile、Air5s `STABLE`、固件、
  transport、5 个 EQ ID 和佩戴 `VERIFIED` 全部恢复；
- 在真机 preferences 中注入严格匹配旧格式的无 `schemaVersion`、空 fingerprint archive，
  App 自动迁移到 schema v1，4 个 profile 全部保留并重新生成非空 fingerprint；
- 从 Git 历史独立构建真正的 2.0.6 / versionCode 14，使用保留数据的降级覆盖后再通过
  `:app:installDebug` 升回 2.0.7 / versionCode 15。两端模块均激活、模块服务均连接，
  first install time、applicationId、LSPosed scope、4 个 profile 和旧偏好均保持不变。
- 整机重启后耳机主动回报左右未佩戴并把 ANC 置为关闭，App 正确接受设备真值；重新佩戴后
  左右 `WEARING` 回报恢复，已再次写入降噪模式，并在等待设备回报后确认“降噪 / 深度”高亮，
  完成本轮开始状态的恢复。
- `SettingsHeadsetHook` 已进入 `HookEntry` 和 `scope.list`，LSPosed Manager 真机 scope 也已
  勾选 Settings。重启 Settings 后模块实际注入 `MiuiHeadsetActivity`，进程持续存活；v2 snapshot
  广播完成 dispatch/finish。首次投影与 App 一致为左右 100%、盒未连接、ANC 关闭、通透人声
  增强关闭；Settings 进程被系统重建后，重新进入耳机详情页会主动请求当前 snapshot，最终页面
  与 DE preferences 均恢复为左右 100%、盒未连接、`anc=8`，并确认“降噪 / 深度”高亮。

本批次发现的平台限制：

- 当前 HyperOS 的部分 MiLink 进程不存在
  `ProfileContext.setAudioEffectState(BluetoothDevice, String, int)`，hook 已按设计跳过并记录
  `NoSuchMethodException`。Air5s 空间音效控制、设备回读与跨进程 snapshot 回归均已通过，
  但该 ROM 不具备此特定 MiLink API，因此无法验证对应的系统 API 副作用；
- 当前 Settings ROM 的 `IMiuiHeadsetService.Stub.Proxy` 不存在无参 `getDeviceInfo` 和
  `isSupportAudioSwitch`，对应可选 hook 按设计安全跳过；Activity、snapshot receiver、状态投影
  与进程存活均不受影响。

## 真机验收清单

1. [x] 使用 `.\gradlew.bat :app:installDebug` 安装完整 APK，并重启已选 LSPosed scope 进程；
2. [x] OPPO Enco Air5s：连接、初始状态、ANC/EQ/空间音频/低延迟/双设备、智能 ANC 当前强度、
   通知/灵动岛、媒体路由和断线重连；
3. [x] Sony WH-1000XM4：SPP 只读状态回归，确认仍为 `READ_ONLY` 且不展示写控件；
4. [x] Sony LinkBuds S：GATT 连接、只读状态及 Phase 10 全部可逆控制回归；
5. [x] App、MiLink、Settings、Xiaomi 蓝牙 Hook 跨进程 snapshot 一致性与蓝牙服务存活检测；
6. [x] profile 在进程/设备重启后的持久化，schema v0→v1 迁移，以及 JSON 导入/导出；
7. [x] 从旧版本覆盖安装，确认 applicationId、LSPosed 授权、作用域和旧偏好不丢失；
8. [x] Release 构建无法解锁或发送 raw HEX，Debug 构建必须使用当前页面 session token。
