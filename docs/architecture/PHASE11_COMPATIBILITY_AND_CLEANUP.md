# Phase 11：兼容性扩展和清理

状态：执行中。启动日期：2026-08-01。

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
  同时删除了无调用者的 RFCOMM connection observable 与旧 Intent 电量解析器。智能 ANC
  level 暂无通用领域字段，仍保留为单一迁移期事件。

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
3. 将智能 ANC level 纳入通用领域 snapshot 后，删除最后一个逐功能状态事件；当前
   `BatteryParams`/`PodParams` 仍是通知、灵动岛和 Compose 展示模型，不再作为状态 IPC。

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
