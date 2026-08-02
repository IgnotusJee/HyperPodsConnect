# 多品牌耳机架构重构方案

## 1. 文档目的

本文档给出将当前 OPPO 单品牌、RFCOMM 单传输实现演进为多品牌耳机客户端的架构判断和实施计划。

目标能力：

- 同一应用支持 OPPO、Sony，并可继续增加其他厂商；
- 同时支持 Classic SPP/RFCOMM 和 BLE GATT 控制通道；
- 将蓝牙传输、厂商帧协议、设备会话、功能控制、HyperOS 集成和 UI 明确分层；
- 设备能力由握手、协议响应和兼容档案决定，不再只依赖营销型号名称；
- 协议层可在普通 JVM 中测试，Android/Xposed/HyperOS 仅位于外层；
- 写操作区分“已发送、传输确认、设备确认、状态回读、失败”，避免乐观状态被当成真实状态；
- 通过逐阶段替换维持现有 OPPO 功能，不进行一次性推倒重写。

本文档是迁移基线，不代表当前分支已经实现所有模块。

## 2. 当前源码审计结论

### 2.1 当前运行结构

当前工程只有 `:app` 一个 Gradle 模块。主要运行位置如下：

1. 初始基线的 `HookEntry` 将模块注入 `com.android.bluetooth`、`com.xiaomi.bluetooth` 和
   `com.milink.service`；Phase 11 已增加 `com.android.settings`。
2. `HeadsetStateDispatcher` 在 `com.android.bluetooth` 中监听 A2DP 连接变化。
3. 被识别为 OPPO 的设备交给全局 `RfcommController`。
4. `RfcommController` 在蓝牙进程中建立固定 UUID 的 RFCOMM socket，直接读写 OPPO 帧。
5. 蓝牙进程通过显式广播向应用、MiLink 和小米蓝牙进程分发电量、ANC 等状态。
6. App UI 再维护一份本地状态，并通过广播发送控制请求。
7. HyperOS Hook 各自在自己的进程中维护电量、ANC、地址等缓存副本。

初始审计时 `SettingsHeadsetHook` 在 `HookEntry` 中被注释，没有进入实际加载路径。Phase 11
已经启用该入口并扩展 `scope.list`，真机验证记录见
[`PHASE11_COMPATIBILITY_AND_CLEANUP.md`](PHASE11_COMPATIBILITY_AND_CLEANUP.md)。

### 2.2 主要职责混合

`RfcommController` 同时承担了以下职责：

- RFCOMM socket 创建、连接、读写、关闭；
- 自动重连、连接状态判断和协程生命周期；
- OPPO 握手、查询与通知订阅；
- OPPO 包分发和各功能 parser 调用；
- 电量、ANC、佩戴、游戏模式、EQ、空间音频等状态缓存；
- 能力判断；
- UI 与外部进程广播；
- HyperOS 通知、状态栏图标、MediaRouter 和音频连接；
- 原始 HEX 调试发送。

这使得新增 GATT 或第二个厂商时只能继续增加条件分支，无法安全复用连接、会话和 UI。

### 2.3 已确认的技术问题

#### 设备识别

- 自动连接入口仅检查设备名称是否包含 `oppo`；
- 能力判断主要依赖型号名称白名单和用户覆盖项；
- 未读取 SDP UUID、GATT Service、协议版本或设备报告能力；
- 地址被直接用作设备持久化主键，不足以覆盖 TWS 地址轮换、协调设备和 LE Audio group。

#### 传输层

- 只实现固定 OPPO UUID 的 secure RFCOMM；
- 没有通用 `ByteTransport`；
- 没有 GATT 操作队列、MTU、CCCD、characteristic 分块写入和 notification 流；
- `InputStream.read()` 的一次结果被当作一个完整 OPPO 包，未处理拆包、粘包和流重同步；
- 写入没有统一串行器和请求级 timeout；
- 每次控制可能创建新的临时 `CoroutineScope`，没有完整的结构化并发所有权。

#### 会话层

- `isConnected` 在 RFCOMM 真正连接前即被设为 `true`，其含义更接近“应保持连接”；
- UI 的 connected 又依赖 socket 和电量初始化，协议 ready 与业务数据被混为一体；
- 只有一个全局 socket、一个设备和一套全局状态；
- 没有 connection generation，旧连接 callback 可能污染新连接；
- 断线时无法统一取消 pending request、ACK wait、业务响应 wait 和 feature subscription；
- 协议请求没有统一关联模型。

#### 协议与功能层

- `Packets.kt` 同时包含 OPPO 线级常量、包构建、厂商字段值、通用业务枚举和各功能 parser；
- parser 直接返回 UI/业务数据，缺少“厂商消息 -> 厂商状态 -> 通用领域状态”的映射边界；
- EQ preset ID、ANC 编码等厂商值泄漏到 UI；
- 当前多数 SET 会先更新本地 confirmed 状态，再发送或回读；失败时 UI 可能继续显示错误状态；
- 原始 HEX 控制台直接连接真实设备，不适合在多协议环境默认开放。

#### 跨进程和 UI

- `OppoPodsAction` 包含大量按功能拆分的广播 action，扩展一个功能需要修改多个进程；
- 广播字段没有 contract version、request ID、device ID、vendor ID 和 operation result；
- App、蓝牙进程、MiLink、小米蓝牙各自保存状态副本；
- `MainUI` 同时负责页面状态、广播编解码、命令发送、持久化和业务映射；
- UI 按固定参数渲染功能，而不是按 `FeatureCapability` 动态组合；
- `BatteryParams` 位于 MIUI Toast 包下，Android `Parcelable` 模型被当成了核心领域模型。

### 2.4 可复用资产

以下代码不应丢弃：

- 已验证的 OPPO 命令、解析器和设备兼容经验；
- 已配对设备选择页面；
- HyperOS 蓝牙卡片、融合设备中心、超级岛和 MiLink 适配经验；
- Compose 电量、降噪和设备详情组件；
- 当前跨进程显式广播路径，可作为第一阶段 IPC 兼容层；
- RFCOMM 调试日志和设备图片配置能力。

重构应先用测试固定这些行为，再移动职责。

## 3. 核心架构判断

### 3.1 采用六个模块，而不是继续在 `:app` 内按包堆叠

目标模块：

```text
:core
:protocol:oppo
:protocol:sony
:transport:android
:engine
:app
```

理由：

- `:core`、`:protocol:oppo`、`:protocol:sony` 可以是纯 Kotlin/JVM 模块；
- 编译依赖能强制协议代码不能引用 Android、Xposed、Compose 或 HyperOS；
- SPP/GATT 的 Android callback 复杂度集中在 `:transport:android`；
- 会话选择、驱动注册和连接编排集中在 `:engine`；
- `:app` 只保留应用入口、UI、IPC 客户端、Xposed Hook 与 HyperOS 适配；
- 将来增加 `:protocol:<vendor>` 不需要修改现有厂商协议内部。

不建议一开始拆成十几个极细模块。先使用六个稳定边界，等协议和测试规模增长后再拆 `sony-tandem`、`sony-mdr` 等子模块。

### 3.2 传输与协议必须是组合关系

不能设计成 `SonyGattController`、`SonySppController`、`OppoSppController` 三套完整控制器。应组合为：

```text
Vendor Session
  -> Protocol Codec / Handshake / Features
  -> ByteTransport
       -> SppTransport
       -> GattTransport
```

Sony 的 Tandem/MDR 可运行在 SPP 或 GATT 上；协议层不应知道 Android `BluetoothSocket` 或 `BluetoothGatt`。

OPPO 首期仍只声明 SPP transport。如果未来发现 OPPO GATT 控制协议，只增加 transport profile 和对应握手，不复制整个功能层。

### 3.3 核心模型使用设备能力，而不是品牌条件分支

UI 不应出现如下模式：

```kotlin
if (vendor == SONY) { ... } else if (vendor == OPPO) { ... }
```

UI 应读取 `DeviceProfile.features`：

```kotlin
if (profile.features[FeatureId.NOISE_CONTROL]?.canWrite == true) {
    NoiseControlCard(...)
}
```

品牌只用于选择驱动、显示品牌信息、兼容档案和厂商扩展。通用功能根据能力描述渲染。

### 3.4 只允许一个进程拥有真实蓝牙控制会话

短期保留当前行为：由注入 `com.android.bluetooth` 的 runtime 作为唯一 session authority，App 和其他 Hook 只通过 IPC 发命令、收状态。

同时在架构上抽象 `SessionRuntimeHost`，避免核心引擎永久依赖 Xposed。未来可增加 App 前台服务 host，支持非 Xposed 或更标准的 Android 生命周期。

同一时刻必须只启用一种 host，防止 App 服务与蓝牙进程同时争用 SPP/GATT。

### 3.5 第一阶段保留广播，但升级为版本化 IPC

立即迁移到 AIDL 会扩大改动面，且注入蓝牙进程后的服务注册需要额外验证。因此第一阶段采用两个主 action：

```text
ACTION_HEADPHONE_COMMAND
ACTION_HEADPHONE_EVENT
```

统一携带：

- `contractVersion`；
- `requestId`；
- `deviceId`；
- `vendorId`；
- command/event 类型；
- versioned Parcelable 或 JSON payload；
- operation phase、错误码和时间戳。

旧的 `OppoPodsAction` 在迁移期作为兼容桥接层保留，待 App、MiLink 和小米蓝牙全部切换后移除。

## 4. 目标依赖规则

```text
:protocol:oppo ----\
                    \
:protocol:sony ------> :core <------ :app UI models
                    /
:transport:android -/
          ^
          |
       :engine
          ^
          |
        :app (runtime host / IPC / Xposed / HyperOS / Compose)
```

实际 Gradle 依赖建议：

```text
:core                 -> Kotlin stdlib, coroutines-core
:protocol:oppo        -> :core
:protocol:sony        -> :core
:transport:android    -> :core, Android SDK, coroutines-android
:engine               -> :core, :protocol:oppo, :protocol:sony, :transport:android
:app                  -> :core, :engine, Android/Compose/Xposed/MIUIX
```

依赖限制：

- `:core` 不引用 Android；
- `:protocol:*` 不引用 Android、Xposed、UI、ConfigManager；
- `:transport:android` 不引用任何厂商协议；
- `:engine` 不引用 Compose 或 HyperOS hook 类；
- `:app` 可以依赖下层，下层不能反向依赖 `:app`；
- 厂商协议模块之间禁止互相依赖。

## 5. 各层详细设计

### 5.1 `:core`：领域、端口和状态机

建议包结构：

```text
core/
  device/
  transport/
  protocol/
  session/
  feature/
  operation/
  trace/
```

#### 设备身份

```kotlin
@JvmInline
value class DeviceId(val value: String)

@JvmInline
value class VendorId(val value: String)

data class DeviceIdentity(
    val id: DeviceId,
    val vendorId: VendorId?,
    val primaryAddress: String,
    val memberAddresses: Set<String> = emptySet(),
    val groupId: String? = null,
)
```

`DeviceId` 是应用稳定主键，不等同于当前蓝牙地址。OPPO 初期可以由规范化 MAC 生成；TWS/GATT 后续可由 identity address、group、协议设备 ID 或兼容档案修正。

#### 设备候选与检测结果

```kotlin
data class DeviceCandidate(
    val identity: DeviceIdentity,
    val displayName: String?,
    val bonded: Boolean,
    val advertisedUuids: Set<String>,
    val availableTransports: Set<TransportKind>,
)

data class DetectionEvidence(
    val vendorId: VendorId,
    val confidence: DetectionConfidence,
    val reasons: List<String>,
    val preferredTransports: List<TransportSpec>,
)
```

名称匹配只能产生弱证据。UUID、GATT Service 和安全只读握手产生更高证据。

#### 传输端口

```kotlin
interface ByteTransport {
    val kind: TransportKind
    val state: StateFlow<TransportState>
    val incoming: Flow<ByteArray>
    val maxWriteSize: StateFlow<Int>

    suspend fun open()
    suspend fun write(bytes: ByteArray): TransportWriteResult
    suspend fun close(cause: DisconnectCause = DisconnectCause.Requested)
}

interface TransportFactory {
    suspend fun create(
        device: DeviceIdentity,
        spec: TransportSpec,
    ): ByteTransport
}
```

`incoming` 只保证有序字节块，不保证一个块对应一帧。

#### 会话状态机

```text
Idle
  -> Detecting
  -> TransportConnecting
  -> ProtocolHandshaking
  -> LoadingCapabilities
  -> SynchronizingState
  -> Ready
  -> Reconnecting
  -> Disconnecting
  -> Idle

任意活动状态 -> Failed
```

状态必须携带：

- `deviceId`；
- `generationId`；
- transport；
- protocol ready 与否；
- failure category；
- 是否允许重试。

Socket/GATT connected 不得直接映射成 UI Ready。

#### 设备档案

```kotlin
data class DeviceProfile(
    val identity: DeviceIdentity,
    val vendorId: VendorId,
    val model: String?,
    val firmware: String?,
    val topology: DeviceTopology,
    val transport: TransportKind,
    val protocol: ProtocolDescriptor,
    val features: Map<FeatureId, FeatureCapability>,
    val compatibilityLevel: CompatibilityLevel,
)
```

`FeatureCapability` 至少包含：

- `canRead` / `canWrite`；
- 值域或可选项；
- `evidenceLevel`；
- 当前 transport 下是否可用；
- 是否要求 readback；
- 最低固件或兼容档案来源。

#### 通用状态

```kotlin
data class HeadphoneState(
    val batteries: Map<BatteryComponent, BatteryState>,
    val noiseControl: NoiseControlState?,
    val equalizer: EqualizerState?,
    val wearing: Map<WearComponent, WearState>,
    val lowLatency: ToggleState?,
    val spatialAudio: SpatialAudioState?,
    val vendorStates: Map<String, VendorFeatureState> = emptyMap(),
)
```

固定 `left/right/case` 只适用于部分 TWS。使用 component map 后也可表达头戴式单电池、颈挂式和未知部件。

每项可变状态应区分：

- `confirmedValue`；
- `pendingValue`；
- `updatedAt`；
- `source`：RET、NTFY、GET、local pending；
- `stale`。

#### 功能命令和结果

```kotlin
sealed interface FeatureCommand {
    data object RefreshAll : FeatureCommand
    data class SetNoiseControl(val value: NoiseControlTarget) : FeatureCommand
    data class SetEqualizerPreset(val presetId: String) : FeatureCommand
    data class SetLowLatency(val enabled: Boolean) : FeatureCommand
    data class SetSpatialAudio(val mode: SpatialAudioTarget) : FeatureCommand
}

enum class OperationPhase {
    QUEUED,
    SENT,
    TRANSPORT_ACKNOWLEDGED,
    DEVICE_ACCEPTED,
    STATE_CONFIRMED,
    READ_BACK_CONFIRMED,
    FAILED,
    TIMED_OUT,
    CANCELLED,
}
```

不提供可由普通 UI 调用的通用 raw byte command。厂商实验命令必须位于开发构建、安全策略和设备白名单之后。

### 5.2 `:transport:android`：Android 蓝牙实现

#### SPP/RFCOMM

`SppTransport` 负责：

- secure RFCOMM socket；
- 明确的连接 timeout；
- 单 reader coroutine；
- write mutex 和有序发送；
- coroutine 取消时关闭 socket；
- 将任意 `read()` 结果原样作为字节块发布；
- EOF、IOException、adapter off 转成统一 `TransportFailure`；
- 不解析 OPPO 或 Sony 帧；
- 不负责 A2DP/HFP 音频 profile。

需要为“主动断开”和“异常断开”提供不同 cause，供 session 决定是否重连。

#### BLE GATT

`GattTransport` 负责：

- 使用 `connectGatt(..., TRANSPORT_LE)`；
- 每个连接唯一 generation；
- `requestMtu`、`discoverServices`、characteristic read/write、descriptor write 的单队列串行化；
- callback -> suspend operation 的一一对应和 timeout；
- CCCD notification/indication 开启；
- write-with-response 与 write-without-response；
- 按协商 MTU 或厂商 writable length 分块；
- write-without-response 本地节流；
- notification 字节块按原顺序发布；
- 忽略旧 generation callback；
- GATT 133、8、19 等错误分类及有界退避；
- adapter off、bond lost 和权限错误归一化。

厂商提供 `GattTransportSpec`，包含 service、TX/RX characteristic、CCCD、初始化步骤和 writable length 来源。GATT 实现本身不能硬编码 Sony UUID。

### 5.3 `:protocol:oppo`：现有协议迁移

建议包结构：

```text
oppo/
  frame/OppoFrameCodec
  message/OppoCommand
  message/OppoMessage
  handshake/OppoHandshake
  session/OppoSession
  feature/battery
  feature/noisecontrol
  feature/equalizer
  feature/latency
  feature/spatial
  compatibility/OppoCompatibilityRegistry
```

#### 首先修复流式 framing

OPPO frame decoder 应：

1. 累积任意字节块；
2. 搜索 `0xAA`；
3. 读取 1～3 字节的 7-bit little-endian varint encoded length；
4. 等待完整 `1 + varintSize + encodedLength` 字节；
5. 一次吐出零到多帧；
6. 对非法长度设置上限并重同步；
7. 保留未知完整帧供 trace。

这里的 encoded length 包含 control/reserved 与 inner packet，但不包含 `0xAA`
和 varint 自身。短包时 varint 只有一个字节，才退化为旧实现中的
`AA + TotalLen + 00 00 + ...`。链路分片的 control/FSN 解析与 inner packet
解析必须作为后续独立步骤，不能把一次 `InputStream.read()` 当成一个包。

所有现有 parser 先接收完整 `OppoMessage`，不再各自重复解析 header、command 和 payload length。

#### 领域模型迁移

- `NoiseControlMode`、`WearState` 等移入 `:core`；
- `AncMode`、OPPO command code、feature ID 留在 `:protocol:oppo`；
- `EqPreset` 的数值 ID 留在 OPPO，转换成通用 preset descriptor；
- `BatteryParser` 返回 OPPO 解析结果，再由 mapper 转换为通用 `BatteryState`；
- `DeviceCapabilities.kt` 拆成协议报告能力和 `OppoCompatibilityRegistry`；
- 用户 capability override 位于外层配置，不进入协议模块。

#### OPPO 写操作

所有写操作统一为：

```text
校验 capability/value
-> 设置 pending
-> 串行发送
-> 等待响应或 notification
-> 必要时 GET readback
-> 更新 confirmed 或回滚 pending
```

如果某个现有 OPPO 命令没有可验证响应，必须明确标为 `SENT_UNCONFIRMED`，不能冒充 confirmed。

### 5.4 `:protocol:sony`：Tandem/MDR

首期结构：

```text
sony/
  tandem/
    TandemEncoder
    TandemStreamDecoder
    TandemFrame
    TandemAckController
  mdr/
    DataType
    CommandTable
    MdrMessage
    MdrCommandRegistry
  handshake/
    SonyHandshake
    SonyCapabilityLoader
  session/SonySession
  feature/battery
  feature/ncasm
  feature/equalizer
  compatibility/SonyCompatibilityRegistry
```

Tandem codec 必须覆盖：

- SOF/EOF；
- escape/unescape；
- u32be payload length；
- checksum；
- sequence；
- ACK-required DataType；
- ACK timeout、有界重试和关闭取消；
- 任意拆包、粘包和错误后重同步。

Sony session 初始化顺序：

```text
open transport
-> Tandem ready
-> GET_PROTOCOL_INFO
-> determine command table
-> GET_CAPABILITY_INFO
-> GET_DEVICE_INFO
-> GET_SUPPORT_FUNCTION
-> query sub-capabilities
-> build DeviceProfile
-> register feature modules
-> initial GET state
-> Ready
```

第一阶段只发送证据充分的只读命令。NC/ASM、EQ 等写操作必须按型号、固件、command table 和 transport 白名单逐项开放。

### 5.5 `:engine`：驱动注册、检测和会话编排

核心接口：

```kotlin
interface HeadphoneDriverProvider {
    val vendorId: VendorId

    fun inspect(candidate: DeviceCandidate): DetectionEvidence?

    suspend fun probe(
        candidate: DeviceCandidate,
        transportFactory: TransportFactory,
        safetyPolicy: ProtocolSafetyPolicy,
    ): ProbeResult

    suspend fun createSession(context: DriverSessionContext): HeadphoneSession
}

interface HeadphoneSession {
    val connection: StateFlow<SessionState>
    val profile: StateFlow<DeviceProfile?>
    val state: StateFlow<HeadphoneState>
    val operations: Flow<OperationEvent>

    suspend fun connect()
    suspend fun refresh(featureIds: Set<FeatureId> = emptySet())
    suspend fun execute(command: FeatureCommand): OperationResult
    suspend fun disconnect(cause: DisconnectCause)
}
```

`HeadphoneSessionManager` 负责：

- 维护 `DeviceId -> session`；
- 当前阶段限制一个 active control session，但数据结构不写死单例设备；
- 合并用户选择、A2DP 事件、bond、SDP 和 GATT service 证据；
- 调用 driver registry；
- 去重同一设备连接请求；
- 创建 generation；
- 管理重连策略；
- 将 profile/state/operation 发布给 runtime host；
- 连接切换时完整关闭旧 session；
- 不直接调用 Compose、Toast 或 HyperOS API。

#### 检测策略

检测分三级：

1. `Hint`：名称、BluetoothClass、历史档案；禁止自动发送命令。
2. `Transport evidence`：SDP UUID、GATT Service；可选择对应驱动和 transport。
3. `Protocol evidence`：安全只读 handshake；确认 vendor、协议和能力。

用户明确选择未知已配对设备时，允许执行受限 probe。自动 A2DP 连接只对已经保存且至少达到 Detected 的档案启动控制连接，避免扫描式探测所有耳机。

#### 重连策略

- session ready 前后的失败分别计数；
- 指数退避并设置最大次数；
- 用户命令可触发一次立即重连，但命令必须排队或明确失败；
- adapter off、bond removed、permission denied 不自动循环重连；
- 每次新连接增加 generation，旧 callback 和旧 operation 自动失效；
- 不使用“电量收到”作为连接 ready 的唯一条件。

### 5.6 `:app`：运行宿主、IPC、HyperOS 和 UI

建议包边界：

```text
app/runtime/bluetoothprocess
app/ipc
app/integration/hyperos
app/integration/milink
app/integration/toast
app/ui
app/config
app/deviceprofile
```

#### 蓝牙进程 runtime

`BluetoothProcessRuntimeHost` 替代 `RfcommController` 的全局入口：

- 在 AdapterService 可用后初始化；
- 持有唯一 `HeadphoneSessionManager`；
- 将 A2DP/LE Audio visibility 事件转换成 engine event；
- 注册 versioned command receiver；
- 发布 snapshot/operation event；
- 本身不包含 OPPO/Sony 判断。

#### HyperOS 适配

现有 Hook 重命名并改为品牌无关适配器：

- `BluetoothUpstreamHeadsetHook` -> `HyperOsBluetoothHeadsetAdapter`；
- `MiLinkServiceHook` -> `HyperOsMiLinkAdapter`；
- `MiBluetoothToastHook` -> `HyperOsToastAdapter`。

所有适配器只读取通用 `DeviceProfile` 和 `HeadphoneState`，并将 HyperOS 的 ANC 指令转换成通用 `FeatureCommand`。厂商值转换只能存在于 `:protocol:*`，小米值转换只能存在于 HyperOS adapter。

对于 HyperOS 无法表达的功能，只在应用详情页展示，不伪造系统能力。

#### UI 状态

从 `MainUI` 中提取 `HeadphoneUiStore`/ViewModel：

- 接收完整 snapshot，而不是十多个功能广播；
- 发送带 `requestId` 的命令；
- 显示 pending、confirmed、failed；
- 根据 `FeatureCapability` 动态显示卡片；
- preset、ANC level 等选项来自 profile；
- 设备选择页面显示 Detected/Read-only/Controlled/Unknown 等兼容级别；
- 未验证设备默认只读；
- 断线保留最后状态但标记 stale，不与当前连接状态混淆。

#### 持久化

新增 `DeviceProfileRepository`，按稳定 `DeviceId` 保存：

- vendor、model、firmware；
- 当前和历史地址；
- transport、协议版本和 command table；
- capability fingerprint；
- 每个功能的验证级别；
- 最后连接时间；
- 自定义图片和用户 override。

现有 `PodImagePrefs` 数据通过一次迁移关联到新的 `DeviceId`，不删除原数据。

## 6. 当前文件到目标职责的映射

| 当前文件 | 目标位置/职责 |
|---|---|
| `pods/RfcommController.kt` | 拆为 runtime host、session manager、SPP transport、OppoSession、各 feature controller、HyperOS adapter |
| `pods/Packets.kt` | OPPO frame/message/feature parser；通用枚举移入 `:core` |
| `pods/DeviceCapabilities.kt` | `FeatureCapability` + `OppoCompatibilityRegistry` + 外层用户 override |
| `pods/RfcommLog.kt` | 通用 `ProtocolTraceRecorder` 与 IPC trace publisher |
| `hook/HeadsetStateDispatcher.kt` | `BluetoothProfileObserver` + `BluetoothProcessRuntimeHost` |
| `OppoPodsAction.kt` | versioned `HeadphoneIpcContract`；旧 action 暂时桥接 |
| `ui/MainUI.kt` | UI 组合 + ViewModel/UiStore；移除广播编解码和厂商值 |
| `BluetoothUpstreamHeadsetHook.kt` | 品牌无关的 HyperOS 蓝牙适配器 |
| `MiLinkServiceHook.kt` | 品牌无关的 MiLink 状态/命令适配器 |
| `MiBluetoothToastHook.kt` | 通用电量/ANC toast 适配器 |
| `BatteryParams.kt` | 仅保留为 HyperOS IPC DTO；核心改用 `BatteryState` |
| `PodImagePrefs.kt` | 迁移到 `DeviceProfileRepository` 的 UI 资源扩展 |

## 7. 分阶段实施计划

每个阶段必须可独立构建、测试和回退。不要同时迁移 OPPO、实现 GATT 和加入 Sony。

### Phase 0：建立基线和保护网

改动：

- 记录当前 OPPO 功能行为、已知型号和广播格式；
- 为 OPPO packet builder/parser 添加黄金字节测试；
- 添加拆包、粘包、非法长度测试，先暴露当前 reader 缺陷；
- 为当前 capability 名称匹配补齐回归测试；
- 保存脱敏的 OPPO handshake/battery/ANC/EQ fixture；
- 为现有 `RfcommController` 的连接状态定义测试替身或可观测接口；
- 将 raw HEX 功能限制为 debug build + 显式风险开关。

完成标准：

- 当前 OPPO 支持功能有可重复的单元测试或 fixture；
- debug/release 都能构建；
- 不改变现有用户功能。

#### Phase 0 证据与 fixture 边界

Phase 0 使用两类不可混淆的测试输入：

- `official-source vector`：由官方 App DEX 中的构包、解析和常量静态推导，
  可立即用于 packet/framing/parser 单元测试，但不得标注为真机抓包；
- `device-capture fixture`：来自指定型号和固件的脱敏实测记录，是 handshake、
  set response、通知与 readback 的最终验收依据。

静态源码足以启动并完成大部分保护网，不要求等待抓包后才编码；但缺少
`device-capture fixture` 时，Phase 0 仍不能整体标记完成，也不能把某型号的
写能力提升为“真机确认”。每份真机 fixture 必须附带型号、固件、传输类型、
实际 UUID、手机/Android 版本和抓取时间。

**Phase 0 已于 2026-07-26 完成。** 详细结论见
`PHASE0_OPPO_BASELINE.md`，抓包工具链见 `tools/bluetooth-capture/`。

代码保护网与真机证据均已就位：73 个单元测试，其中 40 个直接跑在
OPPO Enco Air5s（固件 163.163.102）的真机字节上，7 份 device-capture fixture 覆盖
握手、通知、ANC、EQ、固件、空间声开关与批量回读。

Phase 0 同时产出四项对本方案的直接修正，应在 Phase 1 设计 `FeatureCapability` 与
`OperationPhase` 时一并纳入：

1. 主动查询要用能反映当前状态的选择符。官方 App 在 ANC 写入后查询的两个选择符实测
   为静态值，不能用作回读；
2. 能力探测不能只看"响应是否成功"。设备对不支持的特征**静默丢弃**，必须逐项比对
   请求与应答，这直接影响 `FeatureCapability` 的 `evidenceLevel` 判定方式；
3. set 响应只带状态字节、不回显新值，因此 `DEVICE_ACCEPTED` 与 `STATE_CONFIRMED`
   必须是两个独立阶段，`confirmedValue` 只能由回读或通知更新——本方案原有的这一设计
   已获真机验证；
4. 名称白名单使用子串包含匹配，会让名称延长了已列型号的设备意外继承能力。真机证明
   当前这次继承结论正确，但机制本身应收紧。

历史实施记录：

- 已加入短包及 7-bit varint builder 的 official-source golden tests；
- 已加入拆包、逐字节输入、粘包、垃圾前缀、非法/超限长度回归测试；
- 已将电量、ANC、EQ、通知握手的 source-derived vectors 保存为独立 fixture；
- 已加入当前 UI 暴露的 ANC、游戏模式、佩戴、空间音频、EQ、双设备等行为回归；
- capability 名称匹配和 override 已成为 Android-free 纯逻辑并完成回归；
- 已加入连接状态 observable/test double 接口；
- raw HEX 已限制为 debug build，且必须在调试页逐页面会话显式解锁；
- 测试已在正式 Gradle 工具链（`:app:testDebugUnitTest`）下运行，0 失败；
- `:app:assembleDebug` 与 `:app:assembleRelease` 已在本机构建通过；
- 真机 handshake/battery/ANC/EQ fixture 已于同日采集完成并入库。

### Phase 1：创建 `:core` 和通用领域模型（已完成，2026-07-26）

`:core` 为纯 Kotlin/JVM 模块，编译期即无法解析 Android、Xposed、Compose 类型，
依赖规则由构建强制而非靠评审。22 个 core 测试，另有 9 个 app 侧 adapter 测试。

Phase 0 的四项发现已落进模型：

- `FeatureValue` 把 `pending` 与 `confirmed` 分开，`confirmed` 只能由
  `DeviceReported` 更新；`WriteAcknowledged` 对状态是显式 no-op，因为真机 set 响应
  只有状态字节、不回显值；
- `EvidenceLevel` 增加 `REFUTED`，`resolveBatchEvidence` 把"请求了但未出现在成功
  响应里"判为不支持——这是发现设备静默丢弃特征的唯一途径；
- `FeatureCapability.isWritable` 要求证据至少为 `ADVERTISED`，因此名称白名单推导出的
  能力（`ASSUMED`）只能决定显示什么，不能决定发送什么；
- `SessionState` 把 transport 连通与协议就绪彻底分开，`Ready` 只能在握手、能力加载和
  初始同步之后到达；`generationId` 使旧连接的迟到回调被丢弃。

运行路径仍走 `RfcommController`，adapter 已建立并测试但未接入，因此 UI 行为不变。

改动：

- 增加多模块 Gradle 设置；
- 创建 DeviceId、VendorId、DeviceProfile、FeatureCapability；
- 创建 HeadphoneState、FeatureCommand、OperationResult；
- 创建 Transport/Session/Driver 接口；
- 添加状态 reducer 和 session state machine 单元测试；
- App 继续使用旧 controller，通过 adapter 映射到新模型。

完成标准：

- `:core` 不含 Android import；
- 新旧模型 adapter 测试通过；
- UI 行为不变。

### Phase 2：提取 OPPO 流式协议（已完成，2026-07-26）

`:protocol:oppo` 为纯 Kotlin/JVM 模块，仅依赖 `:core`，编译期无法触及 Android、
Xposed、Compose 或 `ConfigManager`。26 个测试，全部由真机 fixture 驱动。

要点：

- `OppoMessageCodec` 是唯一理解帧头布局的地方。旧实现让每个 parser 各自从原始字节里
  重新推导命令、序号与长度，结果是它们对"载荷不足"的判定并不一致；
- `OppoMessage.isComplete` 区分"声明长度大于实到字节"与"根本没有这一帧"，两者此前都
  表现为 null；
- 未知 command 正常解码并保留载荷，不再需要在分派处特判；
- ANC parser 只接受选择符 `01 01`。官方 App 在每次写入后查询的 `02 03`/`02 04` 返回
  恒定值，把它们当读回会得到一个永不变化的"当前模式"；
- 电量与佩戴按设备实际上报的组件返回，不补齐缺失的仓组件；
- `OppoDomainMapper` 是厂商编码到领域值的唯一交叉点，preset id 带 `oppo:` 命名空间，
  上层看不到 OPPO 命令码或预设数值。

fixture 移到仓库根的 `testdata/`，`:app` 与 `:protocol:oppo` 共享同一份，避免两侧
证据漂移。运行路径仍是 `RfcommController` 与旧 `Packets.kt`，UI 行为不变。

改动：

- 创建 `:protocol:oppo`；
- 实现 `OppoFrameStreamDecoder`；
- 将 header/command/payload 解析集中到 `OppoMessageCodec`；
- 迁移现有 parser；
- 将厂商值映射到通用领域状态；
- 为所有已支持功能建立 fragmentation/coalescing 测试。

完成标准：

- 任意分片输入产生相同消息；
- 多帧单次输入可逐帧解析；
- parser 不依赖 Android、ConfigManager 或 UI；
- 未知 command 被保留并记录，不导致崩溃。

### Phase 3：提取通用 SPP transport（已完成，2026-07-26）

`:transport:android` 已建立，依赖 `:core` 并实现 `ByteTransport`。Android
`BluetoothSocket` 被封装为 `SppSocket`，`SppTransport` 统一负责连接、读取、串行写入、
超时、失败分类和有序关闭。App 侧通过 `RfcommTransportBridge` 保留旧 controller 的
回调与重连语义；controller 已不再直接访问 socket 或输入输出流。运行时字节流接入
`:protocol:oppo` 的 `OppoFrameStreamDecoder`，旧包解释逻辑继续保留到 Phase 4。

验证记录：

- `:transport:android` 16 个单元测试覆盖分片原样交付、EOF、连接/写入超时与取消、
  权限失败、并发写串行化、失败原因、幂等关闭，以及关闭后 reader/write job 回收；
- `:core` 22 个、`:protocol:oppo` 26 个、`:app` 82 个、transport 16 个测试全部通过，
  共 146 个测试；
- `lintDebug`、debug/release assemble 与架构依赖检查通过；
- Xiaomi 13 Pro（Android 16 / API 36、LSPosed 2.1.1 API 102）连接
  OPPO Enco Air5s，连续 20 次连接/断开均通过；OPPO RFCOMM channel 5 每轮完整进入
  CONNECTING、CONNECTED、DISCONNECTED，蓝牙进程 PID 全程稳定，尾检无残留链路或
  transport 异常。

改动：

- 创建 `:transport:android`；
- 实现 `SppTransport`；
- 引入结构化 coroutine scope、write mutex、timeout 和 close cause；
- 让旧 controller 通过 compatibility adapter 使用新 transport；
- 删除 controller 对 InputStream/OutputStream 的直接访问。

完成标准：

- OPPO 真机连接、断开、重连行为不回退；
- 连续连接/断开 20 次；
- reader/write job 在关闭后全部结束；
- 拆包/粘包由 OPPO decoder 正确处理。

### Phase 4：建立 OPPO Session 和功能模块（已完成，2026-07-27）

`:protocol:oppo` 已建立 `OppoDriverProvider`、`OppoSession` 和按功能拆分的 command/
parser。Session 是 OPPO 协议运行时的唯一所有者，负责 SPP transport、流式解码、握手、
通知订阅、初始同步、请求关联、能力证据和领域状态归约。App 中的
`RfcommController` 已收敛为 Android/旧广播兼容 facade，不再解释 OPPO 字节。

验证记录：

- `:core` 23 个、`:protocol:oppo` 34 个、`:transport:android` 16 个、`:app` 85 个
  测试全部通过，共 158 个测试；
- Session 测试覆盖“协议响应后才 Ready”、ACK 不确认状态、显式回读确认、设备拒绝回滚、
  不支持能力拒写；架构测试约束 controller 不得出现 OPPO 常量、parser 或十六进制帧，
  并确认所有旧 UI 写入口都映射为 `FeatureCommand`；
- `lintDebug`、debug/release assemble、`git diff --check` 与模块依赖边界检查通过；
- Xiaomi 13 Pro（Android 16 / API 36、LSPosed 2.1.1 API 102）连接
  OPPO Enco Air5s 后，OPPO RFCOMM channel 5 保持连接，电量、ANC、功能表和低延迟查询
  均收到设备响应；旧游戏模式广播完成开启与恢复原值，两次均按
  QUEUED → SENT → TRANSPORT_ACKNOWLEDGED → DEVICE_ACCEPTED → READ_BACK_CONFIRMED
  闭环，confirmed 值来自后续批量状态回读。

改动：

- 创建 `OppoDriverProvider` 和 `OppoSession`；
- 迁移 notification subscription、初始查询和状态 reducer；
- battery、noise control、EQ、low latency、spatial 独立 feature；
- 写命令增加 pending/confirmation/readback；
- 能力判断迁入 profile/compatibility registry；
- 原 `RfcommController` 变为兼容 facade，逐步清空。

完成标准：

- OPPO 的所有现有 UI 功能走 `FeatureCommand`；
- confirmed 状态只能由协议响应或明确 readback 更新；
- 旧广播继续工作；
- `RfcommController` 不再包含协议常量和 parser。

### Phase 5：引入 engine 和版本化 IPC（已完成，2026-07-27）

新增纯 Kotlin/JVM 的 `:engine`。`DriverRegistry` 汇总各厂商 provider 的检测证据，
`HeadphoneSessionManager` 统一管理唯一 active session、连接 generation、有限指数
重连、状态/操作收集和完整快照。新连接会显式关闭被替代会话，所有 collector 都同时
按 generation 与 session identity 过滤，因此旧连接迟到的状态和操作事件不能污染
新会话。

`com.android.bluetooth` 中的 `BluetoothProcessRuntimeHost` 是唯一真实蓝牙控制
authority。App、MiLink、小米蓝牙与 Settings 只通过 version 2 command/event 广播
收发命令和完整 snapshot；App Activity 重建只请求当前快照，不会重建 socket 或
Session。旧 `OppoPodsAction` 由双向 bridge 继续兼容，包含旧 UI 依赖的
`BatteryParams`/`PodParams` Parcelable。

验证记录：

- `:core` 23 个、`:engine` 11 个、`:protocol:oppo` 34 个、
  `:transport:android` 16 个、`:app` 92 个测试全部通过，共 176 个测试；
- engine 测试覆盖同设备连接去重、设备切换、旧 generation 状态/操作隔离、完整快照、
  stale disconnect、有限重连与不可重试失败；IPC 测试覆盖全部 `FeatureCommand`
  round-trip、非法/未知消息拒绝与完整 snapshot round-trip；
- `lintDebug`、debug/release assemble、`git diff --check` 与架构约束全部通过；
- Xiaomi 13 Pro（Android 16 / API 36、LSPosed 2.1.1 API 102）连接
  OPPO Enco Air5s 后，App 连续三次 force-stop/cold-start 前后蓝牙进程 PID 均为
  5870，RFCOMM channel 5 的 CONNECTED 时间戳保持为 00:46:27.727，证明 App 重启仅
  恢复 snapshot、没有重建蓝牙连接；
- App 可从统一 snapshot 恢复左右耳电量、ANC、低延迟、空间声、EQ 与型号；version 2
  命令及旧游戏模式 action 均完成
  QUEUED → SENT → TRANSPORT_ACKNOWLEDGED → DEVICE_ACCEPTED →
  READ_BACK_CONFIRMED，并在验证后恢复低延迟原值。

改动：

- 创建 `:engine` 和 `HeadphoneSessionManager`；
- 创建 driver registry；
- 引入 generation、重连策略和 active session 规则；
- 创建 `BluetoothProcessRuntimeHost`；
- 增加新的 command/event IPC；
- 为旧 `OppoPodsAction` 提供双向 bridge；
- snapshot 中携带 profile、state、connection、operation。

完成标准：

- 蓝牙真实连接只有一个 authority；
- App 重启不重建不必要的蓝牙连接；
- MiLink/小米蓝牙/App 能从统一 snapshot 恢复状态；
- 旧 generation 的事件被测试证明不会污染新会话。

### Phase 6：UI 与 HyperOS 去品牌化（已完成，2026-07-27）

App 新增 `HeadphoneUiStateStore`，把 version 2 snapshot 归约为与厂商无关的连接、
电量、佩戴、功能和操作状态，并同时按 generation 与 snapshot 时间拒绝迟到状态。
页面仅消费 `FeatureCapability` 提供的可读/可写、枚举值及展示标签；EQ 的 OPPO
preset ID 留在 driver/profile 内部，Compose 页面不再知道厂商编码。

`HyperOsHeadphoneAdapter` 成为 HyperOS 整数接口与领域命令之间的唯一边界。小米蓝牙、
Settings、MiLink 和上游耳机 hook 均从统一 profile/snapshot 判断当前设备并发送
`FeatureCommand`，不再维护 `knownOppoAddresses` 或构造 OPPO 功能广播。迁移期仍保留
原 application ID、旧 action bridge，以及首个 engine snapshot 到达前的 OPPO 名称
bootstrap fallback。

验证记录：

- `:core` 23 个、`:engine` 11 个、`:protocol:oppo` 34 个、
  `:transport:android` 16 个、`:app` 103 个测试全部执行并通过，共 187 个测试、0
  skipped；
- fake driver 测试覆盖相同 UI、battery/ANC/EQ/失败状态、unsupported 隐藏、read-only
  明示，以及 pending/timeout/confirmed 区分；架构测试约束 UI 无 OPPO preset/旧写
  action，HyperOS hook 无已知 OPPO 地址表；
- capability 枚举标签完成 snapshot IPC round-trip；UiStore 测试证明旧 generation
  和同 generation 的迟到 snapshot 都不能覆盖新状态；
- `lintDebug`、debug/release assemble、`git diff --check` 与静态去品牌约束通过；
- Xiaomi 13 Pro（Android 16 / API 36、LSPosed 2.1.1 API 102）连接
  OPPO Enco Air5s 后，能力驱动页面从统一 snapshot 恢复左右耳 100% 电量、ANC、
  低延迟、空间音效和动态 EQ 选项；低延迟开启及恢复关闭都显示“设备已确认更改”；
- App 连续三次 force-stop/cold-start 前后蓝牙进程 PID 均为 20798，RFCOMM channel 5
  建连记录数保持 2 → 2，耳机仍为 A2DP Connected，且 App/蓝牙进程无 scoped FATAL，
  证明 UI 重建不会抢占或重建真实连接。

改动：

- 提取 UiStore/ViewModel；
- 页面根据 capability 动态渲染；
- UI 不再引用 OPPO command ID 或 preset 数值；
- HyperOS adapter 使用通用状态和命令；
- `knownOppoAddresses` 改为受支持 `DeviceId`/profile 判断；
- 保留现有 app ID 和旧 action，避免迁移期破坏外部集成；
- 产品改名与资源替换单独决策，不与协议重构绑定。

完成标准：

- 切换 OPPO driver 不改变 UI；
- 用 fake driver 可完整演示电量、ANC、EQ 和失败状态；
- 未支持功能不显示或明确只读；
- pending/timeout/confirmed 可被用户区分。

### Phase 7：实现通用 GATT transport（已完成，2026-07-27）

`:transport:android` 新增可由 JVM fake 的 `GattClient` 平台端口、
`GattOperationQueue` 和 `GattTransport`。连接、MTU、service discovery、
characteristic read/write 与 CCCD descriptor write 共用唯一串行队列；每个连接分配
独立 generation，Android 旧 `BluetoothGatt` 的迟到 callback 无法完成新连接上的
operation。notification 走独立有序 channel，保持平台交付的任意字节分片。

`TransportSpec.Gatt` 由 driver 提供 service/TX/RX/CCCD UUID，并声明 MTU 失败策略、
writable length、with/without-response、notification/indication、拆分/拒绝策略及
no-response throttle。Android 37 使用 `BluetoothGattConnectionSettings` 与
Executor，Android 35/36 保留兼容连接入口；读写和 notification callback 均采用 API
33+ 的 memory-safe value overload。transport 内没有厂商 UUID 或 Sony/OPPO 常量。

验证记录：

- `:core` 23 个、`:engine` 11 个、`:protocol:oppo` 34 个、
  `:transport:android` 36 个、`:app` 103 个 JVM 测试全部执行并通过，共 207 个、
  0 skipped；
- 17 个 GATT fake callback 测试覆盖完整 open/CCCD 顺序、无并发 operation、MTU
  成功与两种失败策略、协商 MTU 与 vendor writable length 分块、超长拒绝、
  no-response throttle、read/notification 原序、timeout、operation 中断线、旧
  generation callback、status 19、permission、adapter off、bond removed、主动关闭
  operation 和缺失 attribute；3 个架构测试约束串行队列、memory-safe Android API
  与无厂商常量；
- `transport:android` 与 App 的 `lintDebug`、instrumentation APK、debug/release
  assemble、`git diff --check` 全部通过；
- Xiaomi 13 Pro（Android 16 / API 36）上通过 ADB 手动安装最终 instrumentation APK，
  `AndroidGattSmokeTest` 使用真实 `BluetoothManager`/`BluetoothDevice` 创建
  vendor-neutral GATT transport，1/1 通过；测试未连接或探测任意外设，完成后测试包
  已卸载；
- 最终 App APK 重载蓝牙作用域后，OPPO Enco Air5s 仍为 A2DP Connected，
  RFCOMM channel 5 于 12:06:15.011 成功连接；低延迟开启和恢复关闭均完成“设备已确认
  更改”，蓝牙 PID 保持稳定且 App/蓝牙进程无 scoped FATAL，证明现有 OPPO SPP 无回退。

改动：

- 实现串行 `GattOperationQueue`；
- 实现 MTU、service discovery、CCCD、读写和 notification；
- 增加 writable length 和 chunk policy；
- fake callback 测试成功、timeout、断线、旧 callback 和 status error；
- 增加 Android instrumentation smoke test。

完成标准：

- 没有并发 GATT operation；
- MTU 成功和失败都可按 profile 继续或明确失败；
- notification 任意分片无丢失；
- disconnect during operation 不遗留 continuation；
- GATT transport 不含 Sony 常量。

### Phase 8：Sony Classic SPP 只读 MVP

当前状态（2026-07-27）：**已闭环**。WH-1000XM4 / 2.5.1 / Sony v1 SPP 已完成
官方 App 对照、20/20 次控制会话稳定性循环和脱敏实机 fixture。完整证据见
[PHASE8_SONY_CLASSIC_SPP_READONLY.md](PHASE8_SONY_CLASSIC_SPP_READONLY.md)。

改动：

- 创建 `:protocol:sony`；
- 实现 Tandem codec、ACK 和 MDR routing；
- 实现协议、型号、固件、support function、capability 握手；
- 实现 battery GET/RET/NTFY；
- 建立 Sony profile 和 trace fixture；
- 默认关闭全部写命令。

完成标准：

- [x] 指定已配对 Sony SPP 型号可稳定连接；
- [x] 型号、固件、电量与官方 App 一致；
- [x] 20 次连接/断开；
- [x] 官方 App 关闭时测试；
- [x] 设备达到 Read-only 后才在 UI 展示状态。

### Phase 9：Sony BLE GATT 只读 MVP

当前状态（2026-07-27）：**已闭环**。LinkBuds S / 4.2.1 已在 Xiaomi 13 Pro 与
Xiaomi 17 Pro 两台 Android 16 / HyperOS 手机完成 BLE GATT 只读验证；第一台覆盖
双耳、单耳/入盒、完整断线、有界重试和恢复，第二台覆盖独立蓝牙作用域重载、TWS
自动回连、完整握手与状态同步。完整证据见
[PHASE9_SONY_BLE_GATT_READONLY.md](PHASE9_SONY_BLE_GATT_READONLY.md)。

改动：

- 定义 Sony GATT profile；
- 实现 MTU、writable length、Tandem notification 准备流程；
- SonySession 在 SPP/GATT 上复用同一 Tandem/MDR；
- 建立 transport selection 和 fallback 策略；
- 记录 TWS 地址/group 行为。

完成标准：

- [x] 至少一款 Sony GATT 设备完成只读 handshake/battery；
- [x] 至少两台 Android/HyperOS 设备验证（2/2）；
- [x] 单耳、双耳、入盒、断线场景有记录；
- [x] GATT 失败不会错误回退到无证据的 SPP UUID。

### Phase 10：Sony 可逆控制

当前状态（2026-07-31）：**已闭环**。LinkBuds S / 4.2.1 的关闭、降噪、环境声三态、
环境声 level `1..20`、NORMAL/VOICE（通透/人声增强）与全部 12 个官方 EQ preset 均已
完成官方 App 动态证据、严格白名单、SET 前读取、独立 ACK、通知宽限、强制 GET
readback、脱敏 fixture、项目 App 真机验证与原值恢复。完整 Gradle/App 构建、
`installDebug` 和自动化回归通过。危险或不可逆功能继续保持关闭，不计为 Phase 10
遗留项。
完整记录见
[PHASE10_SONY_REVERSIBLE_CONTROLS.md](PHASE10_SONY_REVERSIBLE_CONTROLS.md)。

逐项添加：

1. [x] NC 开关；
2. [x] ASM 开关与 level `1..20`；
3. [x] 全部 12 个官方 EQ preset；
4. [x] 环境声 NORMAL/VOICE（通透/人声增强）。

每项准入要求：

- builder/parser 静态证据；
- 官方 App 抓包；
- 型号 + 固件 + transport + command table 白名单；
- SET 前读取；
- ACK 与业务响应分离；
- RET/NTFY 或 GET readback；
- timeout 不修改 confirmed；
- 可恢复原值。

完成标准：

- [x] 已交付切片只有达到 Controlled 的设备显示写控件；
- [x] 已交付切片无 FOTA、关机、恢复出厂、配对管理和 raw 扫描；
- [x] NC/ASM 三态模式有真机记录和 fixture；
- [x] ASM level、NORMAL/VOICE 与 EQ 按同一门禁逐项闭环。

### Phase 11：兼容性扩展和清理

当前状态（2026-08-02）：**已闭环**。schema v1、兼容档案迁移与导入导出、首版精确
兼容矩阵、`STABLE` 分级、产品显示名称决策和 release/raw 显式门禁已落地；旧广播
bridge 与 `RfcommController` facade 已删除，剩余系统副作用集中到显式的
`OppoSystemIntegrationAdapter`；旧功能写 action 与 snapshot 到逐功能状态广播均已删除，
智能 ANC 当前强度也已进入通用 snapshot。OPPO Air5s、Sony LinkBuds S、WH-1000XM4、
四个跨进程 snapshot 消费者、持久化/迁移/升级和 release raw gate 已完成真机验收。执行记录见
[`PHASE11_COMPATIBILITY_AND_CLEANUP.md`](PHASE11_COMPATIBILITY_AND_CLEANUP.md)。

改动：

- 扩展设备/固件/OEM 矩阵；
- profile schema 版本化；
- 数据迁移与兼容档案导入导出；
- 移除旧广播 bridge 和空的 `RfcommController` facade；
- 清理 OPPO 命名泄漏；
- 决定产品名称、applicationId 是否需要单独迁移。

完成标准：

- 新增厂商只需新增 protocol module/driver 和资源，不修改 transport 或核心状态机；
- OPPO 与 Sony 均有 Stable/Controlled/Read-only 明确分级；
- release build 禁止危险协议操作。

### Phase 12：发布候选与集成层收尾（已完成，2026-08-02）

Phase 12 不扩展协议命令或无真机证据的型号矩阵，先把 Phase 11 已验证的架构收敛为可发布、
可持续维护的基线。首批工作删除 `OppoSystemIntegrationAdapter` 中无调用者的媒体路由控制、
重复 MiUI payload 构造和无消费者状态缓存，并把旧 `ACTION_REFRESH_STATUS` 完整迁移到
version 3 `FeatureCommand.RefreshAll`。

剩余旧 action 必须有明确的跨进程生命周期、系统 UI 副作用、配置同步或 debug/release 门禁
职责；不能仅因名称含 `legacy` 就删除。执行记录和保留矩阵见
[`PHASE12_RELEASE_HARDENING.md`](PHASE12_RELEASE_HARDENING.md)。

完成标准：

- 无调用者的 Android 集成分支被删除并有架构测试防回流；
- 可由 version 3 command/snapshot 覆盖的旧 action 不再注册或发送；
- 保留 action 的调用者、目标进程和不可替代职责均有文档记录；
- JVM、Android lint/compile、debug/release 构建、依赖边界与 fixture 脱敏检查通过；
- 需要真机验证的系统副作用在发布候选安装后完成最小回归。

审计修复结论：V3 发送方认证（可信端显式共享系统认证身份）、稳定连接状态、宿主实例快照排序、
自动连接证据门禁和 CI 质量门均已完成。离线门禁、完整多进程 scope 重启、shell 广播伪造、
四客户端 snapshot 闭环、宿主重启 generation 归一、Air5s 可逆 ANC、WH-1000XM4 Sony v1 SPP
自动连接与只读 UI，以及 LinkBuds S Sony v2 GATT 的 Debug/Release Ready 和 minified Release
Failed 均已通过。A2DP hook 的旧 OPPO 厂商短路已修复，Release 混淆后的内部状态类名不会改变
V3 Idle/Failed/Ready 展示。本阶段恢复为已完成，但本轮未创建发布标签或 Release。
`BatteryParams` / `PodParams` 继续作为 Android 系统 UI 边界上的 `Parcelable` 展示 DTO 保留，
设备 confirmed 状态只来自 version 3 snapshot。

## 8. 测试策略

### 8.1 纯 JVM 测试

- frame encode/decode round trip；
- 任意分片和粘包；
- checksum/length/escape 错误；
- command routing；
- payload parser；
- state reducer；
- capability gate；
- operation correlation；
- timeout、disconnect、generation invalidation；
- fake transport contract；
- unknown message preservation。

### 8.2 Android transport 测试

- socket connect/cancel/EOF；
- write serialization；
- GATT operation queue；
- callback timeout；
- notification/descriptor；
- adapter off；
- bond removed；
- stale callback；
- permission denied。

### 8.3 真机矩阵

每条档案记录：

```text
vendor + model + firmware + topology
+ phone/OEM + Android/HyperOS version
+ SPP UUID/GATT services
+ transport + protocol version/table
+ capability fingerprint
+ feature read/write evidence
```

最低覆盖：

- 现有已支持 OPPO 设备；
- OPPO legacy ANC 型号；
- Sony Classic SPP；
- Sony BLE GATT/TWS；
- 至少两个手机/OEM 蓝牙栈；
- 官方 App 关闭；
- 官方 App 并发作为单独实验，不默认支持。

### 8.4 每阶段 CI 门槛

- 所有纯 JVM test；
- Android lint/compile；
- debug assemble；
- release assemble；
- protocol module 禁止 Android import 的架构检查；
- fixture 不含真实 MAC、账号 token 或固件 binary。

## 9. 日志与诊断

统一 trace 记录：

- timestamp；
- hashed DeviceId；
- generation；
- vendor/transport/protocol；
- direction；
- raw frame（需用户启用）；
- decoded message；
- confidence/evidence；
- requestId/operation phase；
- timeout/disconnect cause。

默认日志不记录明文 MAC，不包含账号、位置或固件二进制。协议 raw trace 设保留期限并由用户主动导出。

## 10. 安全策略

```kotlin
data class ProtocolSafetyPolicy(
    val allowReadCommands: Boolean = true,
    val allowVerifiedWritesOnly: Boolean = true,
    val allowRawConsole: Boolean = false,
    val allowPowerOff: Boolean = false,
    val allowFactoryReset: Boolean = false,
    val allowPairingMutation: Boolean = false,
    val allowFirmwareUpdate: Boolean = false,
)
```

约束：

- 未识别设备不自动发送厂商命令；
- 未完成 capability 的设备不发送功能命令；
- 未验证写命令不进入 release；
- 禁止枚举值暴力扫描；
- 禁止 FOTA；
- raw console 只能在 debug + 明确设备白名单 + 二次确认下使用；
- ACK 不等于业务成功。

## 11. 迁移与回滚策略

- 每个 Phase 独立提交；
- 先添加新路径，再通过 feature flag 切换；
- OPPO 真机通过前保留旧 facade；
- 新 IPC 与旧广播并行一个迁移周期；
- 新 profile repository 只复制旧数据，不立即删除旧 key；
- GATT 和 Sony 默认关闭，通过开发开关逐设备启用；
- 出现回归时可按 driver/transport feature flag 回退，而不是回退整个架构；
- 不在同一提交中做产品改名、applicationId 迁移和协议重构。

建议 feature flags：

```text
engine_v2_enabled
oppo_session_v2_enabled
ipc_v2_enabled
gatt_transport_enabled
sony_read_only_enabled
sony_verified_writes_enabled
```

## 12. 已交付的多品牌里程碑

截至 Phase 11，已交付范围为：

```text
现有 OPPO 功能无回退
+ 通用 SPP/GATT transport
+ 品牌无关的设备 profile/state/UI
+ Sony 已知、已配对设备的 SPP/GATT 只读连接
+ LinkBuds S 4.2.1 的 NC/ASM、环境声 level、NORMAL/VOICE 与全部官方 EQ preset
+ 指定型号电量读取与精确写入白名单
+ 完整 trace 和兼容等级
+ schema v1 profile 持久化、迁移和导入导出
+ App、MiLink、Settings、Xiaomi Bluetooth 的版本化 snapshot 一致性
+ release raw gate、旧广播 bridge 清理和覆盖升级兼容性
```

不在首个里程碑承诺：

- Sony 全型号；
- Sony TWS/LE Audio 全场景；
- 完整替代官方 App；
- 固件升级；
- 多个控制会话同时在线；
- 官方 App 并发稳定性。

## 13. 下一步执行顺序

Phase 0–12 已完成。当前不进入发布流程，后续转为证据驱动的维护与独立演进：

1. 仅在取得新增型号、固件或 OEM 的动态证据后扩展精确兼容矩阵；
2. runtime health、session lifecycle、配置同步和系统 UI 副作用如需版本化，分别建立独立设计与
   真机验收计划，不回填到已经关闭的 Phase 12；
3. 新增型号仍必须逐设备满足动态证据、精确白名单、读回与恢复验证，不从现有型号外推；
4. 发布、版本标签与制品分发由后续明确指令单独启动。
