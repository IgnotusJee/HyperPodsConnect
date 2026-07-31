# Phase 9：Sony BLE GATT 只读 MVP

状态：**已闭环**
日期：2026-07-27

## 证据边界

主要静态证据来自 Sony 官方 App 的本地逆向参考包
`reference/reverse-engineering-reference/`：

- `data/protocol-constants.json` 给出 Tandem v2 HPC service、HPC to/from
  accessory、writable-value-length、determine-MTU 和标准 CCCD；
- `03-reversed-source-map.md` 将 GATT callback/client 与 Tandem GATT stream
  分别定位到 MTU、发现、读写、通知、CCCD、writable length 和分块写实现；
- 参考包指向的完整 JADX 文件不在本仓库，因此初始化顺序同时由常量语义、已有通用
  GATT 契约和 LinkBuds S 真机逐步验证约束。

实现只采用 Sony Tandem v2 HPC 控制通道，不访问 FOTA service，不扫描未知 selector，
不发送 ANC、EQ、配对、关机或其他设置命令。capability-info 响应可能包含设备唯一
标识，运行日志会将该帧完整遮蔽，fixture 也明确排除。

## 已实现

- `TransportSpec.Gatt` 支持由厂商声明的准备步骤；
- 通用 `GattTransport` 在 service discovery 后依次执行：
  1. 为 determine-MTU characteristic 开启 notification/CCCD；
  2. 读取 runtime writable-value-length；
  3. 为 Tandem RX characteristic 开启 notification/CCCD；
- runtime writable length 与协商 ATT payload 取较小值，no-response 写按该值分块并
  本地节流；
- GATT operation 失败时显式 disconnect 后 close，避免失败客户端与下一 generation
  重叠；
- `AndroidBluetoothTransportFactory` 根据 driver 已验证的 `TransportSpec` 分派
  SPP/GATT；
- 每次创建 GATT generation 前，通过公开 `BluetoothLeAudio` API 将已配对 TWS
  成员解析为当前已连接的 group lead；不按同名设备猜测；
- Sony SPP 与 GATT 共用同一 `SonySession`、Tandem codec、MDR 握手和 battery
  feature；
- 精确 `LinkBuds S` 在 GATT 可用时优先走 Tandem v2 GATT，避免平台缓存的 SPP
  UUID 抢占已验证控制通道；其他 Sony 设备仍优先精确 SPP UUID；否则只有显式
  Tandem GATT service，或“已配对 Sony 名称 + GATT service 实际验证”才能进入 GATT；
- GATT 失败不会回退到猜测的 SPP UUID；
- v2 support-function parser 同时支持现代 count 格式；LinkBuds S 实机返回 40 个
  二字节 function code；
- `HeadphoneSessionManager` 串行化 session 切换，并对自动重连执行 cancel-and-join，
  旧重连任务不能跨代启动；
- 点击系统已连接设备时仍发送幂等 control-session 请求：Ready 会话由 engine
  去重，Failed 会话可创建新 generation 恢复。

## 离线验证

执行：

```text
gradle --offline test :app:lintDebug :app:lintRelease \
  :app:assembleDebug :app:assembleRelease
```

结果：

- 全仓 230 个 JVM 测试；
- 0 failure、0 error、0 skipped；
- Debug/Release lint 通过；
- Debug/Release APK assemble 通过；
- `git diff --check` 通过。

新增覆盖包括 GATT 准备顺序、runtime writable length、分块写、失败客户端释放、
SPP/GATT 共享握手、严格 transport selection、GATT 失败不回退 SPP、LinkBuds S v2
support count、重连任务取消，以及脱敏 LinkBuds S 真机帧完整性。

## 第一台手机真机门禁

环境：

- 耳机：Sony LinkBuds S；
- 固件：4.2.1；
- 拓扑：两个已配对成员组成一个 LE Audio coordinated set；
- 手机：Xiaomi 13 Pro；
- OS：Android 16 / API 36；
- 传输：Sony Tandem v2 HPC over BLE GATT；
- Sony Sound Connect 在测试期间保持 force-stopped。

双耳基线：

- 完成 MTU、service discovery、determine-MTU CCCD、writable length read 和
  Tandem RX CCCD；
- protocol-info 返回 v2 / `0x030020150000`；
- 型号 `LinkBuds S`、固件 `4.2.1`、support count 40；
- 左 87%、右 100%、盒 96%，进入 `Ready / READ_ONLY`；
- 页面自动 refresh 后仍为同一 generation。

单耳与入盒：

- 右耳入盒会使原 picker 成员离线，而同组左耳 group lead 保持活动；
- 修复后新 GATT generation 解析到当前 group lead，完整握手并进入
  `Ready / READ_ONLY`；
- 设备回报左 78%（后续 77%）、右不可用 0%、盒 96%；
- 没有 SPP fallback、额外并发 GATT client 或 SET。

完整断线：

- 双耳全部入盒后，Ready generation 进入 `Failed → Reconnecting`；
- 按 1 s、2 s、4 s 进行恰好三次有界 BLE GATT 重试；
- 每次连接操作在 5 s 内明确失败，第四 generation 停止在 `Failed`；
- 没有 SPP fallback、无限循环、decode reject 或相关进程崩溃。

恢复：

- 左耳重新连接后，点击系统已连接的设备行发出幂等 control-session 请求；
- generation 5 完成完整握手并恢复 `Ready / READ_ONLY`；
- 回报左 71%、右不可用 0%、盒 94%。

脱敏帧和条件记录位于
`testdata/fixtures/sony/device-capture/linkbuds-s-4.2.1/`。仓库不保留蓝牙地址、手机
序列号、账号、LE Audio group/member 标识或 capability-info 原始响应。

## 第二台手机真机门禁

环境：

- 手机：Xiaomi 17 Pro；
- OS：HyperOS 3.0 / OS3.0.315.0.WBLCNXM；
- Android：16 / API 36；
- LSPosed：2.1.1-it / API 102；
- 耳机与固件：Sony LinkBuds S / 4.2.1；
- Sony Sound Connect 在测试期间保持 force-stopped。

结果：

- 安装当前构建并重启蓝牙作用域后，两个 LE Audio 成员自动回连，coordinated set
  恢复 Active；
- App 显示模块已激活、模块服务已连接；
- 从已配对设备行发起 generation 1，严格选择 `sony / BLE_GATT`，依次完成 transport
  connection、Tandem v2 handshake、capability load 和 initial state sync；
- 型号 `LinkBuds S`、固件 `4.2.1`、support count 40；
- 左 80%、右 100%、盒 93%，进入 `Ready / READ_ONLY`；
- 后续固件与电量 refresh 保持同一会话，且收到设备主动 battery notification；
- 没有 SPP fallback、SET、decode reject 或 App/蓝牙进程相关 FATAL。

## 闭环结论

Phase 9 已在两台 Android 16 / HyperOS 手机完成 LinkBuds S BLE GATT 只读验证。第一台
覆盖双耳、单耳、全部入盒、三次有界重试和恢复；第二台覆盖独立系统蓝牙栈重载、TWS
自动回连、完整握手与状态同步。所有完成标准均满足。此处“写能力保持关闭”是 Phase 9
冻结时的阶段结论；后续 Phase 10 已按功能完成独立证据、白名单、读回与恢复验证，详见
[PHASE10_SONY_REVERSIBLE_CONTROLS.md](PHASE10_SONY_REVERSIBLE_CONTROLS.md)。

## 已知限制

- 动态结论目前覆盖两台手机，但耳机型号和固件仍只覆盖 LinkBuds S / 4.2.1；
- TWS 设备列表仍显示两个平台成员；控制通道会在连接时解析当前 group lead，但 UI
  尚未把两个成员合并成单个稳定 DeviceId；
- 入盒状态目前由 battery response 的 0%/不可用值体现，尚未实现 Sony wearing
  feature；
- 官方 App 并发不在本阶段支持范围；
- 本阶段保持严格只读，所有 Sony 写能力仍为 false。
