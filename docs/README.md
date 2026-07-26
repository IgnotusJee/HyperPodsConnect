# 文档索引

本目录记录多品牌耳机重构的方案、逆向分析结论和真机证据。若你是第一次接触本项目，
按下面的顺序读。

## 从哪里开始

| 想知道 | 读这份 |
| --- | --- |
| 整体架构要往哪走、分几个阶段 | [architecture/MULTI_BRAND_REFACTOR_PLAN.md](architecture/MULTI_BRAND_REFACTOR_PLAN.md) |
| 当前 OPPO 实现的行为基线与真机验证结论 | [architecture/PHASE0_OPPO_BASELINE.md](architecture/PHASE0_OPPO_BASELINE.md) |
| 官方 App 协议是怎么逆出来的 | [reverse-engineering/OPPO_OFFICIAL_APP_DEXDUMP_ANALYSIS.md](reverse-engineering/OPPO_OFFICIAL_APP_DEXDUMP_ANALYSIS.md) |
| 怎么在真机上抓包取证 | [reverse-engineering/ROOTED_ANDROID_BLUETOOTH_CAPTURE_PLAN.md](reverse-engineering/ROOTED_ANDROID_BLUETOOTH_CAPTURE_PLAN.md) |
| 抓包工具怎么用 | [../tools/bluetooth-capture/README.md](../tools/bluetooth-capture/README.md) |

## 当前进度

Phase 0（基线与保护网）、Phase 1（`:core` 与通用领域模型）、Phase 2（提取 OPPO
流式协议）、Phase 3（提取通用 SPP transport）、Phase 4（建立 OPPO Session 和功能
模块）**已完成**。下一阶段是 Phase 5：引入 engine 和版本化 IPC。

模块结构目前是 `:app`、`:core`、`:protocol:oppo`、`:transport:android`。
`:core` 与 `:protocol:oppo` 都是纯 Kotlin/JVM 模块，编译期即无法触及 Android、
Xposed 与 Compose。运行路径仍保留 `RfcommController` 作为旧广播与 HyperOS 集成的
兼容 facade；真实 socket 生命周期与字节收发由 `SppTransport` 管理，拆包/粘包、握手、
能力发现、初始同步、状态归约和功能命令则由 `:protocol:oppo` 的 `OppoSession` 管理。

协议 fixture 位于仓库根的 `testdata/`，由 `:app` 与 `:protocol:oppo` 共享，
新旧两套实现对着同一份真机证据校验。

抓包工具链的里程碑 M0 到 M3 已交付，M5 的 UI 自动化部分交付。M4（Sony 协议发现）
未开始。

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
- `RfcommController` 不再直接持有 `BluetoothSocket`、`InputStream` 或
  `OutputStream`，通过 `RfcommTransportBridge` 保留原广播、重连和 UI 行为。

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
回滚 pending。`RfcommController` 只把旧 UI 广播翻译为 `FeatureCommand`，并把领域状态
翻译回原广播与 HyperOS 输出，其中不再保留 OPPO 命令常量、帧编解码或 parser。

2026-07-27 在 Xiaomi 13 Pro（Android 16 / API 36、LSPosed 2.1.1 API 102）与
OPPO Enco Air5s 上完成真机回归：Session 通过 OPPO RFCOMM channel 5 收到电量、ANC、
功能表与低延迟状态；旧游戏模式广播可触发低延迟开启并恢复原值，两次操作均到达
`DEVICE_ACCEPTED` 后再由批量状态回读进入 `READ_BACK_CONFIRMED`。全工程 158 个单元
测试、`lintDebug`、debug/release assemble 全部通过。

## 安全边界

抓包与探测遵循计划第 10 节的策略：默认只观察；只读命令允许主动发送，且限定在白名单内；
不提供任意字节控制台；禁止 OTA、恢复出厂、解除配对与设备发声等入口。原始抓包材料一律
留在仓库外，进入仓库的 fixture 必须脱敏并经人工复核。
