# Phase 13–16：下一阶段路线图

## 1. 目标与顺序

Phase 0–12 已形成可发布的多品牌基线。下一阶段按依赖关系推进：

1. **Phase 13：Sony Classic SPP 功能闭环**；
2. **Phase 14：Sony、OPPO 等设备的自定义 EQ**；
3. **Phase 15：按设备自动解析并缓存官方图片**；
4. **Phase 16：MiLink 状态桥与耳机弹窗**。

Phase 13 先补齐设备控制证据，Phase 14 在其上扩展领域模型。Phase 15 为 App、通知、超级岛和
弹窗提供统一图片来源，Phase 16 最后消费已经稳定的 snapshot、能力与图片，不在 Hook 进程中
另建蓝牙会话。

## 2. Phase 13：Sony Classic SPP 功能闭环

执行记录见
[`PHASE13_SONY_SPP_CONTROLS.md`](PHASE13_SONY_SPP_CONTROLS.md)。当前已完成 v1
capability-gated NC/ASM 与 preset EQ 可逆真机闭环；WH-1000XM4 / 2.5.1 / Classic SPP
已通过 20/20 连接/控制稳定性门禁并达到 `STABLE`，Phase 13 已闭环。

### 完成结果

- WH-1000XM4 / 2.5.1 已完成 SPP NC/ASM、全效果 OFF 与 preset EQ 可逆控制闭环；
- LinkBuds S / 4.2.1 已通过 GATT 闭环 NC/ASM、环境声等级、NORMAL/VOICE 和 12 个官方 EQ preset；
- Sony driver 已具备 Tandem、command table、能力门禁、写后查询和 operation 生命周期；写入
  白名单覆盖已取得真机动态证据的 LinkBuds S 与精确 WH-1000XM4 tuple。

### 已完成目标

- 对 WH-1000XM4 等明确的 Classic SPP 型号采集官方 App 对照 trace；
- 逐型号恢复并验证降噪、环境声、环境声等级、人声增强和 EQ preset 查询/设置；
- 从 support-function/capability 响应建立 SPP 功能矩阵，不从 LinkBuds S 外推；
- 让同一 `SonySession` 功能实现可按 command table 和 transport profile 工作，协议 feature 不复制
  成一套 “SPP 版本”；
- 每个写操作执行 set → ACK/通知 → GET/readback → 恢复原值，失败时回滚 pending 状态。

### 完成标准

- 至少一个 Sony SPP 型号的 NC/ASM 与 EQ 在精确固件白名单内达到 `STABLE`；
- 每项控制均有脱敏 fixture、JVM 回归测试、真机读回和原值恢复记录；
- 20 次连接/断开及模式往返无残留 socket、无旧 generation 回调污染；
- 未验证型号保持 `READ_ONLY`，Release 不允许越过证据门禁。

## 3. Phase 14：多厂商自定义 EQ

执行记录见 [`PHASE14_CUSTOM_EQ.md`](PHASE14_CUSTOM_EQ.md)。14A 已完成通用曲线模型、结构化
capability、兼容 IPC/profile archive、capability 驱动 UI，以及 WH-1000XM4 2.5.1 的 Sony V1
整曲线写入、强制读回和原值恢复。官方静态链路和真机均确认 SET 为
`58 01 FF <band-count> <all bands>`。

### 完成结果

OPPO 官方 App 16.7.1 的自定义 EQ 调用链与 wire contract 已恢复：capability bit 34、GET `0x0122`、
SET `0x0418`，以及槽位、UTF-8 名称、LE16 频率和 signed gain 的精确布局均已有静态向量测试。
session 仅在运行时能力位与有效 GET 同时成立后开放对应槽位，并强制写后读回。Enco Air5s
163.163.102 已完成临时槽位创建、62 Hz +1 dB、强制读回、原值恢复、删除及作用域重启后的零残留
验证。Sony capability 尚未提供频率文字，WH-1000XM4 UI 暂以 `Band 1..6` 展示，不能从其他型号
猜频点。Phase 14 已闭环，后续型号按 14C 的逐型号证据门禁继续扩展。

### 目标

- `:core` 的厂商无关 `EqualizerBandSpec`、`EqualizerCurve`、自定义槽位和
  `SetEqualizerCurve` 保留 preset 与 curve 的明确区别；
- capability 持续补齐每个型号的 band 数、频率标签、编码/显示范围、步进、特殊低频项和可写槽位；
- Sony V1 已基于官方静态链路与 `EQEBB_RET_PARAM` 真机 readback 恢复精确整曲线 payload；
- OPPO 先完成官方 App 静态调用链与真机 command/notification/readback 取证，再增加协议实现；
- UI 只渲染 capability 提供的频段，不假定所有厂商都是五段、六段或相同增益范围；
- 写入按整条曲线关联一次 operation，只有完整 readback 匹配才进入 confirmed。

### 完成标准

- Sony 与至少一个 OPPO 型号各有一条自定义曲线的写入、读回、重连恢复和原值恢复证据；
- 越界值、错误 band 数、跨厂商 curve 和未验证槽位均在发送前拒绝；
- IPC/schema 升级后旧档案仍可读取，旧客户端不会把 curve 错认成 preset；
- 快速拖动使用去抖/合并，不产生并发写风暴。

## 4. Phase 15：官方设备图片自动解析与缓存

执行记录见 [`PHASE15_DEVICE_ARTWORK.md`](PHASE15_DEVICE_ARTWORK.md)。15A 已完成 Air5s 的
HeyMelody `melody_equipment` 精确 tuple 取证、版本化 descriptor、安全原子缓存、连接后自动解析、
详情页、通知和超级岛真机显示与跨进程 provider 读取。Sony 资源索引仍待完成，Phase 15 尚未闭环。

### 设计原则

- 解析键使用厂商产品 ID、颜色/变体、型号与固件证据，不用模糊设备名直接匹配；
- 优先级固定为：用户自定义图片 > 已验证官方本地缓存 > 模块内置通用回退；
- 官方图片只保存到用户设备的模块私有缓存，不进入 Git、APK 或发行制品；
- 下载或导入必须校验来源、大小、MIME、像素上限、hash 和解码结果，失败保持旧缓存；
- 图片解析与蓝牙 session 解耦，通过版本化 `DeviceArtworkDescriptor` 供 App、通知、超级岛和弹窗复用。

### 厂商落点

- **OPPO**：已把原先需要 root 手动选择 `melody-model-download/control_*/res/image/` 的导入路径升级为
  productId/colorId 精确匹配、自动复制和缓存失效管理；参考 HeyMelody 的
  `popup_<productId>_<colorId>`/`popup_<productId>_<colorId>_normal` 资源选择。
- **Sony**：单独审计 Sound Connect 13.2.1 的设备资源索引与缓存路径；在确认 model/color 映射和
  资源使用条件前只使用内置通用图，不根据文件名猜测。
- **Xiaomi/HyperOS**：研究 `fc_resources/<deviceId>/resource_record.xml` 和云资源同步只作为系统弹窗
  兼容参考，不把小米 deviceId 或第一方图片错误套给 Sony/OPPO。

### 完成标准

- 每个已验证设备重连后无需用户选择即可得到稳定的 box/left/right 或 single/headband 图片；
- 离线、官方 App 未安装、缓存损坏和颜色未知均有确定回退；
- 缓存索引不保存明文 MAC、账号 token 或官方 App 私有认证信息；
- 至少覆盖 LinkBuds S、WH-1000XM4、OPPO Enco Air5s 的真机显示回归。

## 5. Phase 16：MiLink 状态桥与耳机弹窗

详细静态论证见
[`../reverse-engineering/MI_OPPO_POPUP_FEASIBILITY.md`](../reverse-engineering/MI_OPPO_POPUP_FEASIBILITY.md)。

### 已确认边界

- 小米近场/开盖弹窗主链位于 `com.xiaomi.bluetooth`：BLE scan → 广播校验 →
  `MiuiFastConnectService` → `MiuiFastConnectActivity`；
- MiLink 的 `HeadsetInfo`、`ProfileContext`、`AncBatteryController` 负责已连接耳机的身份、电量、ANC、
  空间音频、通知与控制面，本身不是近场弹窗触发器；
- 直接伪造 `checkIsMiTWS` 或 `deviceId` 不足以得到第一方弹窗。原生 Activity 还依赖真实 BLE 广播、
  peer address、连接状态、云端 deviceId 白名单和 `fc_resources`。

### 分阶段目标

1. **16A — MiLink 状态完整化**：把现有 target-address 精确 Hook 扩展到完整 `HeadsetInfo` 更新、
   `switchToHeadsetActivity` 和 snapshot 驱动的属性通知，继续由蓝牙进程持有唯一 session。
2. **16B — 已连接弹窗**：在 A2DP/LE Audio Ready、snapshot Ready 和去重门禁都满足后触发模块弹窗，
   视觉和状态转换参考小米/OPPO 官方实现；先保证锁屏、横竖屏、多窗口和重复连接行为正确。
3. **16C — 原生近场实验**：只有取得 Sony/OPPO 实际 BLE 广播与小米第一方完整动态 trace 后，才评估
   在 `MiuiFastConnectService` 内做受控 ScanResult 适配。不得为了显示弹窗伪造配对成功、写入未知
   account key，或把非目标设备全局标记为 Mi TWS。

### Hook 约束

- 仅在 `com.xiaomi.bluetooth`/`com.milink.service` 的精确进程和目标地址生效；
- ROM/version/class/method signature 形成兼容档案，反射查找失败时记录并跳过；
- 大部分 Hook 放在最终 classloader 可用的 package-ready 阶段；若现有 API 只能使用早期入口，需有
  单独理由和回归测试；
- 弹窗触发必须具备 generation、连接边沿、时间去重、当前用户和前后台/锁屏门禁；
- 不从 Hook 进程建立第二条 SPP/GATT 链路，不绕过 `FeatureCommand` 和 readback。

### 完成标准

- OPPO 与 Sony 各至少一个型号在连接边沿只出现一次弹窗，状态和图片均来自同一 snapshot；
- MiLink 控件修改 ANC/空间音频能完成 command → device accepted → readback confirmed；
- 小米第一方耳机行为不受影响，非目标蓝牙设备不被识别为 Mi TWS；
- 目标类缺失、系统升级或资源未就绪时安全降级到模块弹窗或无弹窗，不导致作用域进程崩溃。

## 6. 全阶段证据纪律

- 官方 App 反编译只提供静态证据，不能替代真机 trace；
- 原始 APK、反编译代码和未脱敏抓包留在被忽略的 `reference/`/私有采集目录；
- 进入 Git 的只有分析结论、脱敏 fixture、协议实现和可复现测试；
- 仍禁止 OTA、恢复出厂、解除配对、任意字节发送和未知 account-key 写入。
