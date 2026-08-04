# 文档索引

本目录记录多品牌耳机重构的方案、逆向分析结论和真机证据。若你是第一次接触本项目，
按下面的顺序读。

## 从哪里开始

| 想知道 | 读这份 |
| --- | --- |
| 整体架构要往哪走、分几个阶段 | [architecture/MULTI_BRAND_REFACTOR_PLAN.md](architecture/MULTI_BRAND_REFACTOR_PLAN.md) |
| 当前 OPPO 实现的行为基线与真机验证结论 | [architecture/PHASE0_OPPO_BASELINE.md](architecture/PHASE0_OPPO_BASELINE.md) |
| Sony Classic SPP 只读实现与验收进度 | [architecture/PHASE8_SONY_CLASSIC_SPP_READONLY.md](architecture/PHASE8_SONY_CLASSIC_SPP_READONLY.md) |
| Sony BLE GATT 只读实现与双机验收 | [architecture/PHASE9_SONY_BLE_GATT_READONLY.md](architecture/PHASE9_SONY_BLE_GATT_READONLY.md) |
| Sony 可逆控制与 Phase 10 闭环 | [architecture/PHASE10_SONY_REVERSIBLE_CONTROLS.md](architecture/PHASE10_SONY_REVERSIBLE_CONTROLS.md) |
| Phase 13–16 下一阶段目标与验收顺序 | [architecture/PHASE13_16_NEXT_STAGE_ROADMAP.md](architecture/PHASE13_16_NEXT_STAGE_ROADMAP.md) |
| Phase 13 Sony SPP 当前执行记录 | [architecture/PHASE13_SONY_SPP_CONTROLS.md](architecture/PHASE13_SONY_SPP_CONTROLS.md) |
| Phase 14 多厂商自定义 EQ 执行记录 | [architecture/PHASE14_CUSTOM_EQ.md](architecture/PHASE14_CUSTOM_EQ.md) |
| Phase 15 官方设备图片执行记录 | [architecture/PHASE15_DEVICE_ARTWORK.md](architecture/PHASE15_DEVICE_ARTWORK.md) |
| Phase 16 MiLink 状态桥与耳机弹窗执行记录 | [architecture/PHASE16_MILINK_POPUP.md](architecture/PHASE16_MILINK_POPUP.md) |
| HyperOS 3 ROM API 逆向基线 | [reverse-engineering/HYPEROS3_ROM_API_ANALYSIS.md](reverse-engineering/HYPEROS3_ROM_API_ANALYSIS.md) |
| Sony 佩戴检测的能力与通道门禁 | [architecture/SONY_WEARING_DETECTION.md](architecture/SONY_WEARING_DETECTION.md) |
| 通用耳机超级岛与焦点通知 | [architecture/UNIVERSAL_HEADPHONE_NOTIFICATIONS.md](architecture/UNIVERSAL_HEADPHONE_NOTIFICATIONS.md) |
| 小米原生/OPPO 官方弹窗机制与 Hook 可行性 | [reverse-engineering/MI_OPPO_POPUP_FEASIBILITY.md](reverse-engineering/MI_OPPO_POPUP_FEASIBILITY.md) |
| 新增厂商或型号需要满足哪些边界 | [architecture/VENDOR_EXTENSION_CHECKLIST.md](architecture/VENDOR_EXTENSION_CHECKLIST.md) |
| 官方 App 协议是怎么逆出来的 | [reverse-engineering/OPPO_OFFICIAL_APP_DEXDUMP_ANALYSIS.md](reverse-engineering/OPPO_OFFICIAL_APP_DEXDUMP_ANALYSIS.md) |
| 怎么在真机上抓包取证 | [reverse-engineering/ROOTED_ANDROID_BLUETOOTH_CAPTURE_PLAN.md](reverse-engineering/ROOTED_ANDROID_BLUETOOTH_CAPTURE_PLAN.md) |
| 抓包工具怎么用 | [../tools/bluetooth-capture/README.md](../tools/bluetooth-capture/README.md) |

## 当前进度

Phase 0（基线与保护网）、Phase 1（`:core` 与通用领域模型）、Phase 2（提取 OPPO
流式协议）、Phase 3（提取通用 SPP transport）、Phase 4（建立 OPPO Session 和功能
模块）、Phase 5（引入 engine 和版本化 IPC）、Phase 6（UI 与 HyperOS 去品牌化）、
Phase 7（通用 GATT transport）、Phase 8（Sony Classic SPP 只读 MVP）、Phase 9
（Sony BLE GATT 只读 MVP）与 Phase 10（Sony 可逆控制）**已完成**。Phase 8 已在
WH-1000XM4 / 2.5.1 上完成官方 App 对照和 20/20 次稳定性循环；Phase 9 已在两台
Android 16 / HyperOS 手机上闭环 LinkBuds S 4.2.1 GATT 只读路径；Phase 10 已闭环
NC/ASM 三态、环境声 level `1..20`、NORMAL/VOICE 与全部 12 个官方 EQ preset。

Phase 13 **已完成**：WH-1000XM4 2.5.1 / Sony v1 SPP 的 capability-gated NC/ASM、
全效果 OFF 与 preset EQ 已完成 ACK/通知、强制 GET 回读、原值恢复和 20/20 稳定性门禁，精确
型号/固件/transport tuple 达到 `STABLE`。Phase 14 **已完成**：通用整曲线模型、IPC/UI，
WH-1000XM4 2.5.1 Sony V1，以及 OPPO Enco Air5s 163.163.102 的临时槽位创建、最小修改、强制
读回、原值恢复、删除和重连零残留均已闭环。Phase 15 **已完成**：Air5s、WH-1000XM4 和
LinkBuds S 的官方设备图片自动解析、缓存、模块详情页、跨进程读取与 HyperOS 设置页真机矩阵均已
闭环。Phase 16 **进行中**：16A 已实现完整 `HeadsetInfo` 值对象投影、snapshot 属性通知和精确地址的
MiLink 详情入口；16B 已实现 Ready/精确地址/有效电量/时间去重门禁、同 snapshot 弹窗和通知降级。
两项均等待 OPPO/Sony/小米第一方真机矩阵。静态实现
不会在动态 trace 与真机回归前标记为完成。

Sony 佩戴检测现已接入两条只读来源：MDR table2 `0xF0` 与 Auto Play BLE `0xA2`。两条路径
都必须同时通过设备 capability、协议代际、transport 和实际状态读取门禁，才会向统一 snapshot
公开 `WEAR_DETECTION`；仅有静态能力位、服务缺失或端点不可连接时保持未知，不猜测佩戴状态。
Phase 15 与 Phase 16 之间的通用通知增量已将焦点通知和模块超级岛改为统一 snapshot 驱动，按
TWS/单电池拓扑、实际电量槽和可写 capability 渲染，不再要求 OPPO 式三图资源或固定降噪按钮。

模块结构目前是 `:app`、`:core`、`:engine`、`:protocol:oppo`、`:protocol:sony`、
`:transport:android`。`:core`、`:engine` 与两个 protocol 模块都是纯 Kotlin/JVM
模块，编译期即无法触及 Android、Xposed 与 Compose。蓝牙进程中的
`BluetoothProcessRuntimeHost` 是唯一真实会话 authority，由 `:engine` 的
`HeadphoneSessionManager` 管理 driver、generation、重连和统一 snapshot。
`HeadphonePresentationController` 与 `HeadphoneSessionCoordinator` 负责品牌无关的展示
门禁及 Android/HyperOS 系统副作用；App、MiLink 和小米蓝牙进程通过版本化 IPC 恢复
相同快照，不会各自建立蓝牙会话。
App 页面由 `HeadphoneUiStateStore` 按 capability 动态渲染，HyperOS hook 通过
`HyperOsHeadphoneAdapter` 使用通用状态和 `FeatureCommand`，不再维护 OPPO 地址表或
在 UI 中解释厂商 preset。
旧的 ANC、低延迟、透明人声增强、空间音频、EQ 和双设备“写广播”入口已删除；这些写操作
只能走版本化 `FeatureCommand`。应用已迁移到全新身份 `org.hyperpods.connect`；旧包数据、
旧 action、旧 Provider authority 与旧 LSPosed 入口均不兼容。
连接、电量、佩戴和逐功能状态不再由 adapter 二次广播，跨进程状态统一来自版本化
snapshot；智能 ANC 当前强度也已进入通用 `noiseControlActiveMode` 状态链。
`:transport:android` 同时提供 SPP 与通用 GATT byte transport；GATT 的 UUID、MTU
失败策略、writable length、写入/通知模式和分块策略全部来自 driver profile，所有
callback-backed operation 串行执行并按 connection generation 隔离。

协议 fixture 位于仓库根的 `testdata/`，由 `:app` 与各 protocol 模块共享，
各层实现对着同一份证据校验。Sony 的静态向量位于 `official-static`，WH-1000XM4
与 LinkBuds S 实机 trace 分别位于 `device-capture/wh-1000xm4-2.5.1` 和
`device-capture/linkbuds-s-4.2.1`；capability response 因包含设备唯一标识而被完整排除。

抓包工具链的里程碑 M0 到 M4 已交付，M5 的安全 UI 自动化部分交付。M4（Sony 协议
发现）现已覆盖 WH-1000XM4 SPP 只读、LinkBuds S GATT 只读与 Phase 10 可逆控制。

## 两类证据不可混淆

这是本项目最重要的一条纪律，贯穿全部文档和测试：

| 类型 | 位置 | 含义 |
| --- | --- | --- |
| `official-source vector` | `testdata/fixtures/oppo/official-source/` | 由官方 App 反编译结果静态推导，**不是**抓包 |
| `device-capture fixture` | `testdata/fixtures/oppo/device-capture/` | 指定型号与固件的真机实测，附 `.metadata.md` 说明采集条件 |

文件名可直接区分：device-capture 一律带型号前缀。任何 fixture 都不得跨目录复制，
静态推导的向量永远不能改标签充作真机证据。

## Phase 0 得到了什么

对 OPPO Enco Air5s（固件 163.163.102、官方 App 16.7.1、Android 16）完成了完整的真机
证据链，7 份 device-capture fixture 覆盖：

- 冷启动握手：`0x0100` capability、`0x0200/0x0205` 通知订阅；
- 状态通知：`0x0204` 的电量、佩戴、ANC 三种报告类型；
- 写操作：ANC 三模式、EQ 三预设、空间声开关，及各自的响应；
- 读操作：电量查询、ANC 查询各选择符、固件版本、批量状态回读。

73 个单元测试中有 40 个直接跑在真机字节上。

### 由此确认的四项待改动

这四项已在 Phase 1 落进 `:core` 模型（见下方"Phase 1 的落点"），每项都有 fixture 与
回归测试钉住：

1. **ANC 查询保持用选择符 `01 01`**。官方 App 用 `02 03`/`02 04`，但实测这两个值在
   不同 ANC 模式下恒为 `06 00`，是静态值，照抄会实现出一个永远不变的"回读"。
2. **批量查询特征表移除 `0x1C`、补入 `0x37`**。设备对不支持的特征静默丢弃而非报错，
   这类问题只能靠逐项比对请求与应答发现。
3. **写入确认必须走 `0x010D` 回读**。所有 set 响应（`0x8403`/`0x8404`/`0x8406`）载荷
   都只有单字节状态、不回显新值，响应到达不等于新值生效。
4. **名称白名单的子串匹配需要收紧**。`OPPO Enco Air5` 规范化后是 `oppoencoair5s` 的
   子串，Air5s 因此继承了空间声开关能力。真机证明结论碰巧正确，但机制会在名称恰好
   延长了已列型号的无关设备上误判。

## Phase 1 的落点

`:core` 用类型把上面四条变成编译期或测试期能挡住的约束，而不是注释里的提醒：

| 发现 | 模型中的体现 |
| --- | --- |
| set 响应不回显值 | `FeatureValue` 分离 `pending` 与 `confirmed`；`StateUpdate.WriteAcknowledged` 对状态是显式 no-op |
| 设备静默丢弃不支持的特征 | `EvidenceLevel.REFUTED` 与 `resolveBatchEvidence(requested, answered)` |
| 名称白名单只是猜测 | 名称推导出的能力标为 `ASSUMED`，`isWritable` 因而为 false |
| 连接不等于就绪 | `SessionState` 把 transport 与协议就绪拆开，`Ready` 位于握手、能力、初始同步之后 |

`SessionState.generationId` 另外解决了旧实现的一类隐患：上一次连接尝试的迟到回调会被
按代次丢弃，不会把已经建立的新会话拖回错误状态。

## Phase 2 的落点

`:protocol:oppo` 把帧与消息解析集中起来，并让第 1 条和第 3 条发现在协议层也成立：

- `OppoMessageCodec` 是唯一理解帧头布局的地方。旧实现每个 parser 各自从原始字节重新
  推导命令、序号与长度，它们对"载荷不足"的判定并不一致；
- `OppoMessage.isComplete` 把"声明长度大于实到字节"与"根本没有这一帧"分开，此前两者
  都表现为 null；
- ANC parser 只接受选择符 `01 01`，拒绝官方 App 那两个恒定值选择符；
- `OppoDomainMapper` 是厂商编码到领域值的唯一交叉点，preset id 带 `oppo:` 命名空间。

真机分片是这一层存在的直接理由：捕获显示一条响应以 3 字节加 15 字节两次读到达，
而旧实现把一次读当成一个包。

## Phase 3 的落点

`:transport:android` 提供通用 `SppTransport` 和 Android socket adapter：

- connect/write 均有超时，write 由 mutex 串行化；
- transport 自己持有结构化 coroutine scope，并显式区分主动关闭与链路失败；
- close 会先关闭 socket，再取消并等待 reader/write job，避免遗留阻塞任务；
- Android 集成层不再直接持有 `BluetoothSocket`、`InputStream` 或 `OutputStream`；
  transport 与重连由 runtime/engine 管理，系统副作用隔离在明确的 adapter 中。

2026-07-26 已在 Xiaomi 13 Pro（Android 16 / API 36、LSPosed API 102）与
OPPO Enco Air5s 上完成 20 次连续连接/断开。每轮 OPPO RFCOMM channel 5 都完整经历
CONNECTING → CONNECTED → DISCONNECTED，蓝牙进程全程稳定，最终无残留连接。

## Phase 4 的落点

`:protocol:oppo` 现在提供 `OppoDriverProvider` 与 `OppoSession`，并将 battery、
noise control、EQ、low latency、spatial 和 dual-device 拆为独立 feature。Session
统一拥有通知订阅、初始查询、能力证据合并、协议状态归约和写操作生命周期。

能力判断集中到 compatibility registry：型号命中和用户覆盖只产生 advertised/assumed
证据，实际查询结果会将能力提升为 verified 或降为 refuted；型号匹配使用规范化后的
精确名称，避免 Air5/Air5s 一类子串误继承。

写操作不再在发送时修改 confirmed 值。`FeatureCommand` 依次产生 queued、sent、
transport acknowledged、device accepted 和 read-back confirmed；设备拒绝或超时会
回滚 pending。`OppoSystemIntegrationAdapter` 只处理迁移期旧 UI 广播和最终 HyperOS
系统输出，其中不保留 OPPO 命令常量、帧编解码、parser、driver 或 transport。

2026-07-27 在 Xiaomi 13 Pro（Android 16 / API 36、LSPosed 2.1.1 API 102）与
OPPO Enco Air5s 上完成真机回归：Session 通过 OPPO RFCOMM channel 5 收到电量、ANC、
功能表与低延迟状态；旧游戏模式广播可触发低延迟开启并恢复原值，两次操作均到达
`DEVICE_ACCEPTED` 后再由批量状态回读进入 `READ_BACK_CONFIRMED`。全工程 158 个单元
测试、`lintDebug`、debug/release assemble 全部通过。

## Phase 5 的落点

新增纯 Kotlin/JVM 的 `:engine`，其中 `DriverRegistry` 负责无副作用的驱动识别，
`HeadphoneSessionManager` 负责唯一 active session、连接 generation、有限指数重连、
状态/操作收集和迟到事件隔离。每份 `HeadphoneSnapshot` 同时携带设备、profile、
connection、领域 state 和最近 operation，成为各进程恢复状态的唯一来源。

蓝牙进程中的 `BluetoothProcessRuntimeHost` 持有唯一 manager，并通过 version 2
command/event IPC 接收命令、发布完整 JSON snapshot。App、MiLink、小米蓝牙和 Settings
侧安装事件桥；旧 action 现在集中在 `LegacyPodsAction` 兼容边界，Parcelable 电量/耳机
展示模型仍被保留，
因此迁移期旧 UI 与 HyperOS 接入无需同时改完。

2026-07-27 在 Xiaomi 13 Pro（Android 16 / API 36、LSPosed 2.1.1 API 102）与
OPPO Enco Air5s 上完成真机闭环：App 连续冷启动三次时蓝牙进程 PID 与 RFCOMM 建连
时间戳均不变，界面可仅凭统一快照恢复电量、ANC、低延迟、空间声、EQ 与型号；
version 2 命令和旧游戏模式广播均完成
QUEUED → SENT → TRANSPORT_ACKNOWLEDGED → DEVICE_ACCEPTED → READ_BACK_CONFIRMED，
并恢复原值。全工程 176 个单元测试零失败，`lintDebug`、debug/release assemble 与
`git diff --check` 均通过。

## 安全边界

抓包与探测遵循计划第 10 节的策略：默认只观察；只读命令允许主动发送，且限定在白名单内；
不提供任意字节控制台；禁止 OTA、恢复出厂、解除配对与设备发声等入口。原始抓包材料保留在
Git 不跟踪的位置（仓库外或被忽略的项目内临时目录），进入仓库的 fixture 必须脱敏并经人工复核。
