# OPPO 官方耳机 App DEX 反编译分析

## 1. 结论摘要

本报告最初对设备 dump 得到的 3 个 DEX 使用 JADX 1.5.6 进行了静态反编译，随后改用完整的 `reference/oppo-app-official/base.apk` 及其 `base-apk-jadx/` 反编译结果。APK 内的 3 个 DEX 与原 dump 逐个通过 SHA-256 一致性校验，APK 反编译树也完整覆盖原 dump 的 8,672 个 Java 文件路径，因此冗余的 dump 压缩包、解压目录和旧 JADX 输出已删除。反编译结果证明，当前项目已经使用的 OPPO 单帧格式和一批核心命令基本正确，但官方实现还包含几项当前项目缺失的关键机制：

1. 协议不是简单的“RFCOMM 一次读取等于一包”，而是“链路帧 + 内层命令包”两层结构。链路层支持 7-bit 变长长度、粘包/拆包重组和分片。
2. 内层命令包的 `seq/transferId` 是按设备循环递增的 8 位序号，官方用“命令号 + 序号”关联响应和超时；当前项目大量使用固定 `0xF0`，无法可靠并发或判定写操作是否成功。
3. 连接后先查询远端协议能力 `0x0100`，把能力位图映射为支持的命令集合，再决定后续查询和可用功能；能力不是只按型号名硬编码。
4. 通知订阅是完整握手：`0x0200` 查询通知能力，支持时用 `0x0205` 批量订阅，否则回退到 `0x0201` 逐项订阅。`0x0204` 是复用的通知事件通道，不应命名为单一的“ANC 通知”。
5. 官方不仅支持 Classic RFCOMM。设备配置可选择不同的 RFCOMM UUID，并存在使用同一协议帧的 SPP-over-GATT 实现。
6. 官方的功能面远大于当前项目，但“代码中存在”不代表所有耳机都支持。功能必须同时受远端能力、产品白名单和运行环境约束。

对当前项目最有价值的工作顺序是：先实现可靠流式解帧和请求关联，再引入能力握手与动态查询，最后才扩充新功能。

## 2. 输入、输出与限制

### 2.1 当前输入 APK

`reference/oppo-app-official/base.apk` 中的 DEX：

| 文件 | 大小 |
| --- | ---: |
| `classes.dex` | 9,937,704 bytes |
| `classes2.dex` | 270,040 bytes |
| `classes3.dex` | 4,464,620 bytes |

APK 清单指定的 Application 类是 `com.heytap.headset.HeyMelodyApplication`，说明样本属于 HeyMelody/欢律系应用。

### 2.2 保留文件与生成目录

- 原始 APK：`reference/oppo-app-official/base.apk`
- JADX 输出：`reference/oppo-app-official/base-apk-jadx/`
- Java 源文件：8,674 个（完整覆盖原 dump 的 8,672 个路径，另含 `R.java` 和 `DebugProbesKt.java`）

本节最初分析的输入只有 DEX；当前保留的完整 APK 和反编译目录还包含 `AndroidManifest.xml`、资源表和 UI 文案。APK 补充分析结果见 `OPPO_BASE_APK_AND_DEVICE_DUMP_GUIDE.md`。

### 2.3 反编译质量

JADX 完成了主要源码输出，但报告 59 个反编译错误。大多数错误位于第三方库或大型混淆方法；与本报告直接相关的一个重要失败点是 `com.oplus.melody.btsdk.protocol.commands.n.D(...)`，它负责轮询响应的一部分分派。文档中的结论分为：

- **已确认**：可由常量、构包、解析或调用链直接验证；
- **高可信推断**：上下游调用明确，但某个大型混淆方法未能完整反编译；
- **待真机验证**：仅靠 DEX 不能确认型号差异或实际响应语义。

## 3. 官方实现的总体结构

核心调用关系可还原为：

```text
UI / Repository
    -> BluetoothService
        -> HeadsetCoreService
            -> Poll / Set / Notification / Request / Upgrade managers
                -> PacketFactory + timeout tracker
                    -> TLVDataProcessor / OPOv1Wrapper
                        -> Classic RFCOMM 或 SPP-over-GATT
```

关键职责如下：

| 层 | 代表源码 | 职责 |
| --- | --- | --- |
| 应用入口 | `com/heytap/headset/HeyMelodyApplication.java` | 应用级初始化 |
| 服务/API | `com/oplus/melody/btsdk/manager/service/BluetoothService.java` | 对上层暴露耳机操作 |
| 会话编排 | `com/oplus/melody/btsdk/multidevice/HeadsetCoreService.java` | 多设备状态、命令路由、连接事件、超时、OTA |
| 命令管理 | `com/oplus/melody/btsdk/protocol/commands/` | Poll、Set、Notification、解析 |
| 内层包 | `p195o8/a.java`、`p195o8/b.java` | command、seq、payload length、响应位 |
| 链路帧 | `E8/b.java`、`F8/b.java` | `0xAA` 帧、变长长度、分片和流重组 |
| Classic 传输 | `p328y8/c.java`、`p341z8/a.java` | RFCOMM 建连、读写和重连 |
| GATT 传输 | `p316x8/h.java`、`p316x8/e.java` | Service/Characteristic、通知、MTU、写队列 |
| 能力/状态 | `com/oplus/melody/btsdk/api/data/DeviceInfo.java`、`p142k8/c.java` | 每设备能力集和运行状态 |
| 产品配置 | `com/oplus/melody/common/data/WhitelistConfigDTO.java`、whitelist repository | 型号、UUID、功能开关、兼容配置 |

这套结构与仓库中 `MULTI_BRAND_REFACTOR_PLAN.md` 提出的“传输、协议、会话、功能/UI 分层”方向一致。官方实现虽然仍有较强 Android/产品耦合，但它已经证明 OPPO 协议不应继续全部堆在一个 `RfcommController` 中。

## 4. 传输和设备识别

### 4.1 Classic RFCOMM

官方 Classic 路径使用 secure RFCOMM：

```java
bluetoothDevice.createRfcommSocketToServiceRecord(uuid)
```

UUID 不是全局固定值：

- 默认回退 UUID：`00001107-D102-11E1-9B23-00025B00A5A5`
- 产品白名单中还明确接受：`0000079A-D102-11E1-9B23-00025B00A5A5`
- `p328y8/c.java` 会从 `SupportDeviceConfig.getUuid()` 读取产品 UUID；为空才使用默认值。

当前项目固定使用 `0000079A-D102-11E1-9B23-00025B00A5A5`。这对已验证设备有效，但无法覆盖官方白名单中使用另一 UUID 的产品。

### 4.2 SPP-over-GATT

官方存在一套使用相同上层包模型的 GATT 通道：

| 用途 | UUID |
| --- | --- |
| Service | `0000079A-D102-11E1-9B23-00025B00A5A5` |
| Characteristic 1 | `0100079A-D102-11E1-9B23-00025B00A5A5` |
| Characteristic 2 | `0200079A-D102-11E1-9B23-00025B00A5A5` |
| CCCD | `00002902-0000-1000-8000-00805F9B34FB` |

该实现包含服务发现、通知开启、MTU 变化、串行写队列、连接重试，以及主/从设备两路 GATT 连接管理。它说明 OPPO 协议层可以复用在 RFCOMM 和 GATT 两种传输上。

### 4.3 BLE 扫描和产品发现

官方 BLE 扫描使用：

- Manufacturer ID `1839`（`0x072F`）；
- Manufacturer ID `1946`（`0x079A`）；
- 部分 Oplus 系统路径还包含 `41992`（`0xA408`）；
- Service UUID `00009801-0000-1000-8000-00805F9B34FB`。

广播解析可得到 productId、广播状态、耳塞状态、电量和颜色。官方随后把 productId、设备名和白名单组合成 `SupportDeviceConfig`，而不是仅靠名称包含 `OPPO`。

对当前项目的意义：设备识别至少应逐步引入 SDP UUID、BLE manufacturer/productId 和协议握手证据，名称/OUI 只作为候选证据。

## 5. 两层协议帧

### 5.1 内层命令包

`p195o8/a.java` 明确解析以下结构：

| 偏移 | 长度 | 含义 |
| ---: | ---: | --- |
| 0 | 2 | command，Little Endian |
| 2 | 1 | seq / transferId |
| 3 | 2 | payload length，Little Endian |
| 5 | N | payload |

响应 command 设置 `0x8000`，即：

```text
responseCommand = requestCommand | 0x8000
```

官方的关联键为：

```text
(seq << 16) | (command & 0x7FFF)
```

因此请求和响应不仅靠命令号匹配，还必须匹配同一个 seq。

### 5.2 外层 OPOv1 链路帧

`E8/b.java` 负责构帧，`F8/b.java` 负责拆帧和重组。链路帧包含：

```text
SOF(AA)
+ encodedLength(7-bit varint，1~2 bytes in current implementation)
+ control/fragment state
+ reserved / FSN
+ optional fragment sequence
+ inner packet bytes
```

对最常见的未分片、总长度小于 128 的包，它退化为当前项目熟悉的格式：

```text
AA + TotalLen + 00 00 + CmdLE(2) + Seq(1) + PayloadLenLE(2) + Payload
```

所以当前 `OppoPackets.buildPacket()` 对短单帧的布局是正确的，但不能把这个特例当成完整协议。

### 5.3 流式处理要求

官方 `F8/b.java` 的 `spliceMTUPackage()` 能处理：

- 一次收到半帧：缓存剩余长度；
- 一次收到多帧：按 encodedLength 逐帧切分；
- 前一帧残片与新数据拼接；
- `0xAA` 帧头检查；
- 链路分片的首包、中间包和末包重组。

当前 `RfcommController.startPacketReader()` 把每次 `InputStream.read(buffer)` 的结果直接交给所有业务 parser。TCP/RFCOMM 流不保证 read 边界等于协议帧边界，因此会出现：

- 半包被业务 parser 丢弃；
- 两包粘在一起时只处理第一包或整体误判；
- 长包或多分片功能不可用；
- 遇到损坏字节后无法重同步。

这是当前实现优先级最高的可靠性问题。

## 6. 序号、响应和超时

`p195o8/b.java` 为每个设备地址维护独立的 8 位 seq，从 0 循环到 255。`HeadsetCoreService.u0()` 在发送请求时建立超时项：

- 普通设备默认 5 秒；
- 部分产品路径使用 10 秒；
- 收到带 `0x8000` 响应位、相同 command 和 seq 的包后取消超时；
- Set manager 再根据响应状态决定是否更新或通知业务层。

当前项目的默认 seq 是固定 `0xF0`，部分包使用 `0x00` 或 `0x57`，并且写入后常通过延时重新查询来猜测结果。建议改成：

1. 每个 session 一个 `SequenceAllocator`；
2. `pending[(baseCommand, seq)] = request`；
3. 收到响应才返回 confirmed；
4. 没有响应语义的命令明确返回 sent-unconfirmed；
5. 超时、断连时统一完成或取消 pending 请求。

## 7. 连接初始化和能力协商

### 7.1 已确认的初始化主链

Classic/GATT 控制通道连接成功后，官方调用 Poll manager 发送 `0x0100`。它的响应包含远端能力位图：

1. `0x0100`：查询远端能力；
2. 响应状态成功后，把每一能力 bit 映射为一个或多个命令号；
3. 能力集合按设备地址保存到 `p142k8/c.java`；
4. 触发“命令能力初始化完成”；
5. 查询通知能力并注册通知；
6. 查询升级属性和产品实际需要的状态项；
7. 注册完成后才把 `DeviceInfo.initCmdCompleted` 设为 true。

能力 bit 到 command 的映射位于 `p008a8/a.java`。例如，同一个功能 bit 可以同时开放 query 和 set command，说明“能力”是协议操作集合，不只是 UI 布尔值。

### 7.2 白名单和协议能力的关系

官方同时使用两类信息：

- **产品白名单**：型号/productId、UUID、预期 UI 功能、兼容参数；
- **远端能力位图**：本次连接的设备实际声称支持哪些 command。

批量状态查询 `0x010D` 也不是固定请求所有字段。官方先放入基础项，再依据白名单功能动态追加字段，最后还检查命令能力。

当前项目的固定 `QUERY_STATUS` 请求 11 个字段：

```text
05 04 0B 11 13 18 06 1B 1C 27 28
```

这些字段可在官方动态构造逻辑中一一找到，例如 game、multi-connect、spatial、high quality、adaptive volume 等。字节本身有来源，但把它们对所有型号无条件发送不等同于官方行为。

## 8. 通知模型

### 8.1 命令

| 命令 | 语义 | 已确认行为 |
| --- | --- | --- |
| `0x0200` | 查询通知能力 | 响应 `0x8200` 返回状态、数量、event IDs |
| `0x0201` | 注册单个通知 | 老设备回退路径 |
| `0x0204` | 通知事件上报 | payload 首字节是 event ID，后续是事件数据 |
| `0x0205` | 批量注册通知 | payload 为 count + event IDs，响应 `0x8205` |

当前项目已实现 `0x0200 -> 0x0205` 的基本流程，这是正确方向；需要补齐以下细节：

- 在流式 decoder 之后再解析通知；
- 只有能力声称支持 `0x0205` 时才批量订阅，否则逐项 `0x0201`；
- 不要把 `0x0204` 常量命名为 `ANC_MODE_NOTIFY`，它同时承载电量、佩戴、降噪状态和其他事件；
- 订阅成功响应是 session ready 的一部分，不应发送后立即视为完成。

## 9. 与当前项目已知命令的交叉验证

### 9.1 核心命令

| 命令 | 十进制 | 官方语义/证据 | 当前项目状态 |
| --- | ---: | --- | --- |
| `0x0100` | 256 | 远端能力初始化 | 缺失，建议首先补充 |
| `0x0102` | 258 | remote VID | 缺失 |
| `0x0103` | 259 | remote PID | 缺失 |
| `0x0106` | 262 | 电量查询 | 已实现 |
| `0x010C` | 268 | 降噪相关查询，payload 选择子项 | 已实现部分子项 |
| `0x010D` | 269 | 批量状态查询 | 已实现固定字段列表 |
| `0x010F` | 271 | EQ 模式查询 | 已实现 |
| `0x012F` | 303 | Multi-SPP 批量命令 | 未实现，可后置 |
| `0x0200` | 512 | 通知能力查询 | 已实现 |
| `0x0204` | 516 | 通知事件通道 | 已解析部分事件，命名需修正 |
| `0x0205` | 517 | 批量通知注册 | 已实现 |
| `0x0400` | 1024 | 查找设备模式 | 未实现 |
| `0x0403` | 1027 | feature switch | 已用于游戏/双连/空间开关 |
| `0x0404` | 1028 | 当前降噪设置 | 已实现 |
| `0x0406` | 1030 | EQ 模式设置 | 已实现 |
| `0x0422` | 1058 | headset spatial type | 已作为空间音频模式使用 |

响应命令使用 `request | 0x8000`，所以当前项目的 `0x8106`、`0x810C`、`0x810D`、`0x810F`、`0x8403` 和 `0x8422` 与官方规则一致。

### 9.2 已确认的设计修正

| 当前实现 | 官方证据 | 建议 |
| --- | --- | --- |
| 固定 seq `0xF0` | 每设备滚动 0..255，并以 command+seq 关联响应 | 引入 seq allocator 和 pending map |
| 每次 read 当成完整包 | OPOv1 wrapper 有流拼接、切包和分片 | 先做增量 decoder |
| 固定 RFCOMM UUID | 白名单 UUID + `0x1107...` 默认；另有 GATT | 使用 transport profile/registry |
| 固定 batch query 字段 | 官方根据产品功能动态构造 | 按能力和兼容档案生成 |
| 型号/名称主导能力 | 官方先有白名单，再用远端 capability 收敛 | 建立 evidence-based capability |
| 写后本地乐观更新 | 官方处理 set response 和超时 | confirmed/readback 后更新状态 |

## 10. 官方功能面清单

从数据模型、命令 API 和 UI 包可以确认官方 App 至少覆盖以下功能族：

- 基础状态：左右耳/盒电量、充电、佩戴、版本、产品信息；
- 降噪：关闭、通透、主动降噪、智能/自适应、个性化降噪、通透人声增强；
- 声音：预设 EQ、自定义 EQ、Bass Engine、高清编码、游戏音效；
- 控制：按键动作、敲击等级、长按音量、入耳检测、语音助手；
- 游戏：低延迟、游戏主开关、游戏 EQ/音效；
- 空间音频：固定/头部跟踪、head motion、手机侧空间音频协同；
- 多设备：双设备连接、已连接设备列表、连接/断开/优先级；
- 查找与交互：耳机播放声音、拍照控制、设备丢失提醒；
- 健康与听力：贴合度检测、听力增强/检测、脊柱健康、睡眠/跌落检测；
- 新交互：AI 摘要、AI 翻译、会议辅助、语音多轮对话；
- 内容和维护：提示音/主题、Zen Mode 内容、固件 OTA、诊断和日志收集。

这些功能不能直接全部移植。应先用 capability bit、通知 event 和真机抓包建立“型号—命令—payload—响应”的证据矩阵。

## 11. 对当前架构计划的影响

### 11.1 应保留的既有判断

`MULTI_BRAND_REFACTOR_PLAN.md` 中以下方向得到官方实现支持：

- transport 与 protocol 应组合而非互相内嵌；
- 一个 session 维护设备能力、序号和 pending 请求；
- 状态应由协议响应/通知驱动；
- OPPO decoder 必须是流式的；
- 设备识别不能只依赖营销名称；
- OPPO 的 command/feature ID 应留在厂商协议模块。

### 11.2 需要新增或强化的设计点

1. **OPPO 链路帧不能只实现固定一字节长度。** decoder 应支持 7-bit varint，至少接受两字节长度。
2. **显式区分 link frame 和 inner packet。** `OppoLinkFrameDecoder` 输出完整 inner bytes，再由 `OppoMessageCodec` 解析 command/seq/payload。
3. **增加分片状态。** 即使第一阶段不发送超长命令，也应正确接收官方分片；OTA、诊断、听力和内容传输会依赖它。
4. **能力模型应保存原始 command set。** 通用 `DeviceCapabilities` 之外保留 `OppoProtocolCapabilities(supportedCommands, notificationEvents)`，便于诊断和兼容。
5. **TransportProfile 应允许产品覆盖。** 例如 Classic `0x079A...`、Classic `0x1107...`、GATT service/characteristics。
6. **通知订阅完成是 ready 条件之一。** socket connected 不能直接等同于 session ready。

建议的 OPPO session 初始化状态：

```text
TransportConnected
  -> QueryingProtocolCapabilities (0x0100)
  -> RegisteringNotifications (0x0200 -> 0x0205/0x0201)
  -> QueryingInitialState (capability-driven)
  -> Ready
```

## 12. 建议实施顺序

### P0：协议可靠性

1. 新建纯 Kotlin `OppoLinkFrameDecoder`，实现增量 feed、varint 长度、粘包/拆包、帧头重同步和长度上限。
2. 新建 `OppoMessageCodec`，只处理 command/seq/payload。
3. 增加每 session 的序号分配器和 pending request tracker。
4. 给现有电量、ANC、EQ、游戏和空间音频 parser 增加“半包、粘包、垃圾前缀、连续多帧”测试。

### P1：能力和连接档案

1. 实现 `0x0100/0x8100` 能力握手及原始 command set；
2. 完善 `0x0200 -> 0x0205/0x0201` 通知注册状态机；
3. 根据能力动态生成 `0x010D` 请求；
4. 把两个 Classic UUID 和 GATT UUID 放入 OPPO transport profile；
5. 将 productId、SDP UUID、广播证据和用户 override 纳入兼容档案。

### P2：功能扩展

优先选择协议简单、可回读、风险低的能力：

1. 查找设备；
2. 按键配置和佩戴检测；
3. 多设备连接状态；
4. 自定义 EQ/更多 EQ 元数据；
5. 固件信息只读。

OTA、诊断、内容传输、听力检测和账号相关功能应后置，因为它们涉及大包分片、文件校验、设备安全或隐私数据。

## 13. 测试与验证清单

### 13.1 离线测试

- 单帧长度 `<128` 和 `>=128`；
- 每个字节单独 feed；
- 一次 feed 多个完整帧；
- 前缀垃圾 + 合法 `0xAA`；
- 非法长度、超限长度、截断帧；
- seq 回绕 `0xFF -> 0x00`；
- 同 command 多个并发 seq；
- response command 的 `0x8000` 归一化；
- 通知 `0x0204` 与普通响应不进入同一 pending 流程。

### 13.2 真机验证

每个型号记录：

- productId、名称、地址脱敏值；
- SDP UUID 和 BLE manufacturer data；
- 实际使用 Classic 还是 GATT；
- `0x0100` 原始能力响应；
- `0x0200` 通知 event IDs；
- 初始查询列表和不支持响应；
- 每个 set command 的响应状态与 readback；
- 断连、重连、双设备争用和 seq 超时行为。

## 14. 关键源码索引

以下路径均位于 `reference/oppo-app-official/base-apk-jadx/sources/`：

| 主题 | 路径 |
| --- | --- |
| 内层包解析 | `p195o8/a.java` |
| seq 分配与响应构包 | `p195o8/b.java` |
| 链路帧构建和分片 | `E8/b.java` |
| 变长长度、流拼接、切包 | `F8/b.java` |
| Classic socket 建立和读循环 | `p341z8/a.java` |
| 默认 Classic UUID | `p341z8/b.java` |
| 按产品选择 UUID | `p328y8/c.java` |
| GATT service/characteristics | `p316x8/h.java` |
| capability bit 到 command | `p008a8/a.java` |
| capability 解析 | `com/oplus/melody/btsdk/protocol/commands/n.java` |
| 动态批量状态查询 | `com/oplus/melody/btsdk/protocol/commands/n.java` |
| 通知能力与注册 | `com/oplus/melody/btsdk/protocol/commands/f.java` |
| 请求超时与响应关联 | `com/oplus/melody/btsdk/multidevice/HeadsetCoreService.java` |
| BLE 扫描过滤/广播解析 | `p065e8/b.java`、`p065e8/e.java` |
| 设备状态模型 | `com/oplus/melody/btsdk/api/data/DeviceInfo.java` |

## 15. 不应过度解读的部分

- DEX 中的 UI 目录名可以证明功能存在，但不能证明当前连接的耳机支持该功能。
- 混淆后的短类名和部分日志 Supplier 被跨模块复用，不能只凭类名命名协议字段。
- DEX-only 样本本身没有明文白名单；后续已从完整 APK 中确认并解密内置 fallback 白名单（81 个产品）。服务器最新白名单仍需从运行中的 App 私有缓存或进程内 provider 获取。
- 固件升级和诊断路径虽然可见，但在没有真机、固件格式和失败恢复验证前不应照搬。
- 本报告是静态分析结果；协议写操作仍应以真机响应和脱敏抓包作为最终证据。

## 16. HeyMelody 16.7.1 自定义 EQ 精确链路

完整 APK 的 JADX 输出补足了早期 DEX dump 只能确认 UI 存在、无法确认 wire contract 的缺口：

| 环节 | 官方代码证据 | 结论 |
| --- | --- | --- |
| UI 入口 | `com/oplus/melody/ui/component/detail/equalizer/CustomEqActivity.java`、`p224qa/j.java` | 默认六频段为 62、250、1000、4000、8000、16000 Hz；白名单可覆盖 |
| 数据对象 | `p132j9/b.java`、SDK `EqInfo` | selected、signed min/max、eqId、UTF-8 name、frequency[]、dbValue[] |
| UI 到服务 | `p224qa/j.java` → `p132j9/c.e().i(...)` → `p132j9/e.java` | action 1/2/3 为新增、更新或选择、删除；service action 1018 为 SET |
| SET | `HeadsetCoreService.G0()` | request `0x0418`，response `0x8418` |
| GET | service action 1017 → `HeadsetCoreService.C()` | request `0x0122`，response `0x8122` |
| parser | `com/oplus/melody/btsdk/protocol/commands/c.java` | 逐槽解析选择状态、范围、名称及 LE16 frequency + signed gain |
| capability | `p008a8/a.java` | bit index 34 同时映射 GET `0x0122` 与 SET `0x0418` |

SET payload 的精确顺序是：

```text
action, min(int8), max(int8), eqId(uint8), nameLen(uint8), name(UTF-8),
bandCount(uint8), repeated(frequency(uint16 LE), gain(int8))
```

GET response 在首部增加 `status, slotCount`，随后重复同一槽位结构，并在每个槽位前增加
`selected`。Enco Air5s 的 bitmap `FF 75 52 EA A4 0E 07 0F` 已置位 bit 34。2026-08-03 的补充
真机测试进一步确认：初始 `0x8122` 为成功且零槽位；action 1 创建后耳机分配 ID 4；action 2 将
62 Hz 从 0 改为 +1 并由 `0x8122` 完整读回；随后恢复、action 3 删除，并在蓝牙作用域重启后确认
`ids=[]、curve=null`。因此该精确型号/固件已完成动态写入闭环，但不能外推到其他 OPPO 型号。

项目中的 `testdata/fixtures/oppo/official-source/custom-eq-*.hex` 只用于验证本地 codec 与上述官方
静态布局一致。它们不是蓝牙抓包，不进入真机兼容性证据计数。

Air5s 动态证据单独保存在
`testdata/fixtures/oppo/device-capture/enco-air5s-163.163.102/`，与官方静态向量明确分开。
