# Phase 0 OPPO 行为与证据基线

记录日期：2026-07-26。本文只描述当前仓库行为和证据等级，不把 DEX 静态
推导写成真机结论。

## 可行性结论

Phase 0 可在不切换现有 RFCOMM 运行路径、不提前创建新模块的前提下实施，代码风险
可控。理由如下：

| 目标 | 可行性依据 | 对现有功能的影响 |
| --- | --- | --- |
| packet/parser 基线 | `Packets.kt` 的构包和 parser 大部分不依赖 Android，可用固定 HEX 在本地 JVM 回归 | 只增加测试；有效包行为不变 |
| 流式 framing 保护网 | DEX 已给出 `0xAA + varint length` 边界；增量 decoder 可先旁路测试 | Phase 0 不接入 reader，不改变真机收包 |
| capability 名称回归 | 当前白名单和 override 都是确定性纯函数 | 只固定现状，不提升证据等级 |
| 连接状态可观测性 | 在 controller 外增加 Android-free observer/source 接口即可记录状态迁移 | 保留原广播和字符串状态 |
| raw HEX 门禁 | `BuildConfig.DEBUG` 是编译期常量，再叠加页面会话 token 和离页 lock | release 不存在发送能力；普通控制命令不受影响 |
| 真机 fixture | 协议日志和 HCI snoop 均可脱敏保存 | 需要目标耳机/固件，不能由静态源码替代 |

因此，Phase 0 的代码保护网是可行且已实现的。原有两项外部验收中，
debug/release 构建与 Gradle 单测已于 2026-07-26 在本机完成；整体阶段能否
关闭现在只取决于目标型号真机 fixture。该项缺失不阻塞后续继续完善测试代码，
但阻塞“真机确认”和 Phase 0 完成标记。

## 当前运行行为

- 传输：Bluetooth Classic RFCOMM，固定 UUID
  `0000079A-D102-11E1-9B23-00025B00A5A5`；
- 收包：当前运行路径仍把一次 `InputStream.read()` 当成一包；新加入的
  `OppoFrameStreamDecoder` 暂只作为 Phase 0 测试保护网，尚未切换运行路径；
- 初始化：连接后发送电量、通知能力/订阅、ANC、批量状态与按型号启用的 EQ/
  空间功能查询；当前尚未实现官方 `0x0100` capability 初始化；
- 写操作：支持 ANC、游戏/低延迟、通透人声增强、空间音频、EQ、空间声开关和
  双设备开关；部分状态仍采用本地乐观更新或延时查询；
- raw HEX：仅 debug 构建可用，进入调试页后还需逐页面会话显式解锁；离开页面、
  UI 关闭或 controller 收到 lock 后立即失效。

## 当前广播格式基线

所有 App 发往控制端的命令都显式定向 `com.android.bluetooth`。控制端发给 App 的
状态广播定向当前 `applicationId`；部分状态还会分别定向 `com.milink.service`、
`com.xiaomi.bluetooth` 和 `com.android.settings`。当前格式没有 contract version、
request ID、device ID、vendor ID 或操作结果，这是 Phase 5 迁移时必须兼容的旧格式。

### 命令广播

| Action 常量 | extras |
| --- | --- |
| `ACTION_CONNECT_POD_REQUEST` / `ACTION_DISCONNECT_POD_REQUEST` | `device: BluetoothDevice` |
| `ACTION_PODS_UI_INIT` / `ACTION_PODS_UI_CLOSED` / `ACTION_REFRESH_STATUS` | 无 |
| `ACTION_ANC_SELECT` | `status: Int`，UI 值 1..8 |
| `ACTION_GAME_MODE_SET` | `enabled: Boolean` |
| `ACTION_TRANSPARENCY_VOCAL_ENHANCEMENT_SET` | `enabled: Boolean` |
| `ACTION_SPATIAL_AUDIO_SET` | `mode: Int`，0..2 |
| `ACTION_EQ_PRESET_SET` | `preset: Int`，当前集合 0/1/2/3/7 |
| `ACTION_DUAL_DEVICE_CONNECTION_SET` | `enabled: Boolean` |
| `ACTION_CYCLE_ANC` | 无 |
| `ACTION_RFCOMM_DEBUG_UNLOCK` / `ACTION_RFCOMM_DEBUG_LOCK` | `rfcomm_debug_session_token: String` |
| `ACTION_RFCOMM_DEBUG_SEND` | `hex: String`、`rfcomm_debug_session_token: String` |

### 状态广播

| Action 常量 | extras |
| --- | --- |
| `ACTION_PODS_CONNECTION_STATE_CHANGED` | `address?: String`、`device_name?: String`、`state: String` |
| `ACTION_PODS_CONNECTED` | `address: String`、`device_name: String` |
| `ACTION_PODS_DISCONNECTED` | `address: String` |
| `ACTION_PODS_BATTERY_CHANGED` | `address?: String`、`status: BatteryParams`，以及 left/right/case 的 `*_battery`、`*_charging`、`*_connected` |
| `ACTION_PODS_WEAR_STATUS_CHANGED` | `address?: String`、`left_wear_status/right_wear_status/case_wear_status: Int` |
| `ACTION_PODS_ANC_CHANGED` | `address?: String`、`status: Int` |
| `ACTION_PODS_SMART_ANC_LEVEL_CHANGED` | `ordinal: Int` |
| `ACTION_PODS_GAME_MODE_CHANGED` | `enabled: Boolean` |
| `ACTION_PODS_TRANSPARENCY_VOCAL_ENHANCEMENT_CHANGED` | `enabled: Boolean` |
| `ACTION_PODS_SPATIAL_AUDIO_CHANGED` | `mode: Int` |
| `ACTION_PODS_EQ_PRESET_CHANGED` | `preset: Int` |
| `ACTION_PODS_DUAL_DEVICE_CONNECTION_CHANGED` | `enabled: Boolean` |

## 当前名称档案

| 名称匹配 | 当前特殊能力 | 证据等级 |
| --- | --- | --- |
| OPPO Enco Free4 | Adaptive、空间声开关 | 代码现状；待型号/固件抓包 |
| OPPO Enco X3 | 空间音频、EQ 页面能力 | 代码现状；待型号/固件抓包 |
| OPPO Enco Air5 | 空间声开关 | 代码现状；待型号/固件抓包 |
| OPPO Enco Air2 Pro | compatible ANC 映射 | 代码现状；待型号/固件抓包 |

名称会先转小写并去除标点，再使用“完整型号规范名包含于设备名”的规则匹配；
用户 override 可以覆盖自动判断。该表不是官方完整白名单。

## 已由官方 DEX 静态确认

- inner packet 为 `cmdLE(2) + seq(1) + payloadLenLE(2) + payload`；
- response command 为 `request | 0x8000`，seq 按设备循环递增；
- OPOv1 外层使用 `0xAA + 7-bit varint length + control/reserved + data`；
- 链路层需要处理拆包、粘包、分片和重组；
- `0x0100` 返回 capability 位图，并映射到可用 command 集合；
- 通知握手为 `0x0200 -> 0x8200 -> 0x0205/0x0201 -> response`；
- 电量 `0x0106`、ANC `0x010C/0x0404`、EQ `0x010F/0x0406` 的命令族存在；
- set response 的首个状态字节参与成功判断，写入 socket 不等于设备接受；
- UUID、SPP/GATT、ANC/EQ protocol index 和固件门槛可被产品白名单覆盖。

## 仍需真机抓包

每个目标型号/固件至少采集以下闭环：

1. 冷连接至初始化完成：`0x0100/0x8100`、`0x0200/0x8200`、
   `0x0205/0x8205` 或逐项订阅，以及随后实际初始查询顺序；
2. 电量：`0x0106/0x8106` 与一次 `0x0204` 主动通知；
3. ANC：`0x010C/0x810C`，每个支持模式一次 `0x0404/0x8404`，以及通知或
   readback；Air2 Pro 需单独验证 compatible 映射；
4. EQ：`0x010F/0x810F`，至少一次 `0x0406/0x8406` 与通知或 readback；
5. 元数据：型号、固件、手机、Android 版本、抓取时间、实际 transport 和 UUID。

抓包脱敏时移除真实 MAC、账号/token 和与协议无关的设备标识。只有这些记录可放入
`device-capture fixture`；DEX 推导输入统一称为 `official-source vector`。

## Phase 0 验收状态

- 代码已完成：名称 capability/override 回归、独立 official-source fixture、
  当前全部 UI 功能的 builder/parser 行为回归、流式 framing 边界测试、连接状态
  source/observable、raw HEX 双层门禁；
- 已修复：raw HEX unlock/lock action 已加入蓝牙进程动态 receiver filter；失败状态和
  声明长度不完整的通知/EQ 响应不再被 parser 当成有效状态；
- 已验证：`:app:testDebugUnitTest` 在正式 Gradle 工具链（Gradle 9.4.1 +
  AGP 9.1.0 + 本机 Android SDK）下运行 33 个测试，0 失败；
- 已验证：`:app:assembleDebug` 与 `:app:assembleRelease` 构建通过，release
  构建中 raw HEX 门禁依赖的 `BuildConfig.DEBUG` 为编译期 false；
- 部分完成：**首份真机 fixture 已入库**，见
  `app/src/test/resources/fixtures/oppo/device-capture/encoair5s-cold-init.hex`
  及同名 metadata。采集于 OPPO Enco Air5s + HeyMelody 16.7.1 + Android 16，
  HCI 与 Frida 双链路字节级互证（7 个 TX payload 一致，时间偏移离散 5 毫秒）。
  `DeviceCaptureRegressionTest` 的 8 项回归全部通过，其中包含真机分片流经
  `OppoFrameStreamDecoder` 的逐字节还原验证。

第二份 fixture `encoair5s-anc-eq.hex` 在**双耳佩戴状态**下采得，覆盖 ANC 与 EQ 的
完整设置闭环，`DeviceCaptureAncEqTest` 7 项回归通过。

真机侧已闭合：

- `0x0100` capability 与 8 字节能力位图；
- `0x0200 -> 0x8200 -> 0x0205 -> 0x8205` 通知握手；
- `0x0204` 电量、佩戴与 ANC 状态主动通知；
- `0x0404/0x8404` ANC 设置与成功状态回报，三种模式各自验证；
- `0x0406/0x8406` EQ 设置与成功状态回报，三个预设各自验证；
- `0x810F` EQ 查询响应。

**写入路径已确认与本项目实现一致**：`Enums.ANC_OFF`、`ANC_NOISE_CANCEL`、
`ANC_TRANSPARENCY` 与真机命令除设备分配的 seq 外字节相同，`Enums.eqPresetPacket`
包布局相同。`AncModeParser` 能解出三种真机 ANC 通知，`EqPresetParser` 能解出三个
真机预设。

**固件版本已闭合**：`163.163.102`，双来源一致。协议侧命令 `0x0105`，响应 `0x8105`
载荷为 `00 04` + ASCII `1,2,163,2,2,163,3,1,01,3,2,102`，解读为
`component,field,value` 三元组（左耳 163、右耳 163、仓 102）；UI 侧为官方 App 首页
最底部"耳机固件更新 / 当前版本 163.163.102"。fixture 见 `encoair5s-firmware.hex`。

该响应载荷是纯版本号 ASCII，脱敏工具按"含可读文本即整帧剔除"的保守规则会剔除它，
本份 fixture 中的帧经人工复核后保留，其中不含设备名或任何个人可识别信息。

**空间声开关已闭合**：Air 5s 确实支持，命令为 `0x0403`，payload `<featureId> <value>`
（featureId `0x1B`），响应 `0x8403` 为单字节状态。本项目 `Enums.spatialSoundSwitchPacket`
与官方 App 除 seq 外字节一致。fixture 见 `encoair5s-spatial-switch.hex`。

这也回答了名称白名单的子串继承问题：白名单项是 `OPPO Enco Air5`，规范化后是
`oppoencoair5s` 的子串，Air 5s 因此**继承**而非被显式列出。真机证明这次继承的结论
是对的，但产生它的规则仍是前缀匹配，遇到名称恰好延长了已列型号的无关设备会误判。

由此暴露两项与实现的差异：

- `0x8403` 响应只有状态字节、不回显新值，现有 `SpatialAudioParser` 期望载荷含
  feature 与 value，因此**无法仅凭响应确认写入结果**，必须依赖后续回读；
- 官方 App 的批量状态回读查询 12 项特征（`0x0C`），本项目 `Enums.QUERY_STATUS`
  查询 11 项，缺 `0x1D`、`0x1E`、`0x37`，多出 `0x13`、`0x1C`。

**电量读取路径已查清，但结论与预期不同**：官方 App **从不轮询电量**。一份 12 MB、
覆盖冷启动到 ANC/EQ/固件/开关全部操作的捕获中，`0x0106` 一次都没有出现。电量完全由
订阅的 `0x0204` 通知推送（该份捕获中出现 104 次），而 `0x010D` 批量查询返回的是功能
开关状态表，不含电量。

官方 App 不用它，不代表设备不支持——被动抓包只能证明"没人发过"，不能证明"发了没用"。
两者的区别由主动只读探针查清，结论见下文"主动探针结论"。

`0x0204` 电量通知的真机结构已闭合：`type=0x01` + count + `(component, level)` 序列，
level 的最高位为充电标志。已采到无仓组件（两组件）与含仓组件（三组件、右耳充电中）
两种形态。

`0x010D/0x810D` 批量回读同样闭环，且两份响应仅在 `0x1B` 一个字节上不同，正是空间声
开关切换后的回读证据。现有 `GameModeParser.parseStatus` 能正确解析真机响应。

## 主动探针结论：两条待验证读命令均可用

被动抓包对"官方 App 从不发送"的命令天然无能为力。为此实现了白名单**只读**探针
（`tools/bluetooth-capture` 的 `--probe`，仅接受两条固定读命令，拒绝任意字节），
用本项目生产代码构造的完全相同的包主动发出，seq 固定 `0xF0`。两条命令的请求与响应
都同时出现在 App 的 socket 流与原始 btsnoop 字节中，fixture 见
`encoair5s-read-probe.hex`。

| 命令 | 结论 |
| --- | --- |
| `0x0106` 电量查询 | **设备支持**。响应 `0x8106` 回显 seq `0xF0`，载荷 `00 02 01 64 02 64`，`BatteryParser.parse` 正确解出左右各 100% |
| `0x010C` ANC 查询，选择符 `01 01` | **设备支持**。响应 `0x810C` 回显 seq `0xF0`，载荷 `00 01 01 10 00`，`AncModeParser` 解出深度降噪，与当时 App 显示一致 |

因此本项目这两条读路径**都是有效的**，此前标记的"未验证"属于保守判断，现已消除。
官方 App 只是选择了别的路径：电量靠通知推送，ANC 用选择符 `02 03`。

## `0x010C` 选择符语义

`0x010C` 的请求载荷是两字节选择符，响应载荷为
`<status> <选择符两字节> <val1> <val2>`，`status` 非零表示该选择符不受支持。
逐个探测的结果（fixture 见 `encoair5s-selectors.hex`）：

| 选择符 | 响应 | 含义 |
| --- | --- | --- |
| `01 01` | `00 01 01 10 00` | **当前 ANC 模式**，随模式变化。本项目使用的就是它，正确 |
| `02 03` | `00 02 03 06 00` | 静态值。深度降噪与通透两种状态下均为 `06 00` |
| `02 04` | `00 02 04 06 00` | 静态值，同上 |
| `03 01` | `01 03 01 00 00` | 本机**不支持**，status=0x01 |

由此纠正一个容易犯的推断：官方 App 每次设置 ANC 后都查询 `02 03` 与 `02 04`，看起来
像回读，但它们的值与当前模式无关，**不能用作写入确认**。真正能反映当前模式的是
`01 01`。

## 批量状态查询的特征差异

用本项目 `Enums.QUERY_STATUS` 原文探测，设备正常应答，但对不支持的特征**静默丢弃**
而非报错：请求 11 项、只回 10 项。

- `0x13`：本项目请求、官方 App 不请求，**设备支持**，返回值 `01`；
- `0x1C`：本项目请求，**设备不支持**，被静默丢弃，属无效请求；
- `0x37`：官方 App 请求并获得应答，**设备支持**，而本项目从不请求，属遗漏；
- `0x1D`、`0x1E`：官方 App 请求，设备不支持，同样被丢弃。

因此本项目的特征表应移除 `0x1C`、补入 `0x37`。由于设备静默丢弃，不能靠"响应是否报错"
发现这类问题，只能逐项比对请求与应答。

## 写入确认只能靠 `0x010D` 回读

`0x8403` 与 `0x8404`、`0x8406` 一样，载荷只有单字节状态、不回显新值，因此**响应到达
不等于新值已生效**。唯一可用的确认途径是随后的 `0x010D` 批量回读——两份仅在 `0x1B`
一个字节上不同的响应已经证明该途径有效。这与重构方案中"ACK 不等于业务成功"的要求
一致，现有真机依据。

至此 Phase 0 要求的真机证据全部闭合，且此前列为"待定"的三项均已定性。

早先在空间音效页只观察到 `0x0122/0x8122` 返回恒定 `00 00`，一度误判为空间音效查询。
经确认 `0x0122` 实为自定义 EQ 相关（响应中出现用户创建的 EQ 预设名），与空间声开关
无关。

已发现的实现差异：官方 App 的 `0x010C` ANC 查询用选择符 `02 03`（及 `02 04`），响应
回显该选择符（`00 02 03 06 00`）；本项目发送 `01 01` 且 parser 扫描 `01 01` 标记，
解不出该响应。由于状态通知路径工作正常，实际功能不受影响，但主动查询路径应按真机
选择符修正。该差异由回归测试显式固定。

耳机在充电盒中时，官方 App 的 ANC 控件可见且 `enabled=true`，但点击**不会下发任何
命令**。ANC/EQ 采集必须在佩戴状态下进行。
