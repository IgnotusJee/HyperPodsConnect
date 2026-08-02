# Phase 13：Sony Classic SPP 功能闭环

状态：**已闭环（WH-1000XM4 2.5.1 Classic SPP：`STABLE`）**

## 1. 当前结果

Sony MDR v1 的 NC/ASM（含全效果 OFF）与 preset EQ 已完成 capability-gated 读取、精确固件写入白名单、
设备通知确认、强制 GET 回读和原值恢复。白名单只覆盖
`WH-1000XM4 / 2.5.1 / Sony v1 / Classic SPP`，未知型号和固件仍保持只读。

2026-08-02 在 Xiaomi 13 Pro / Android 16 上完成在线可逆测试：

- 初始状态：降噪、环境声偏好 `VOICE/20`、EQ `Custom 2`；
- 人声增强：`VOICE -> NORMAL -> VOICE`；
- 环境声等级：`20 -> 8 -> 20`；
- EQ：`Custom 2 -> Bass Boost -> Custom 2`；
- 全关闭：降噪 -> `OFF` -> 降噪，NC/ASM 其余参数保持不变；
- 最终恢复：环境声 -> 降噪，EQ 保持 `Custom 2`；
- 每次 SET 均收到 Tandem ACK 和耳机 NTFY，随后 GET/RET 与目标状态一致；
- 测试期间 A2DP 保持 Active，LDAC 和 SPP 会话未中断。

随后自动执行 20 次连接/控制稳定性门禁。每轮重启蓝牙作用域、等待完整握手达到 Ready，
再执行 `降噪 -> 通透 -> 降噪`；通透与降噪均要求 SET 和 GET/RET 同时匹配才计为通过。
结果为 `20/20`，无协议 reject、response timeout 或状态恢复失败，最终 EQ 仍为 `Custom 2`。

完整脱敏帧位于
`testdata/fixtures/sony/device-capture/wh-1000xm4-2.5.1/wh-1000xm4-v1-controls.hex`。

## 2. 官方 App 静态证据

项目从作用域测试设备提取 Sony Sound Connect 13.2.1，并使用 JADX 1.5.6 开启反混淆
反编译。原始 APK、反编译源码和逐文件索引保存在被 Git 忽略的 `reference/sony/`；base APK
SHA-256 为 `1F51573F8F52F467BE7BBAC815B7276FD7455143035C11AADE8CB5FB15ADF37C`。

静态调用链确认：

- v1 NC/ASM capability：`60 02`，参数 GET：`66 02`；
- v1 NC/ASM SET：`68 02 <effect> <nc-type> <nc-value> <asm-type> <asm-mode> <level>`；
- v1 preset EQ capability：`50 01 00`，参数 GET：`56 01`；
- v1 preset EQ SET：`58 01 <preset-id> 00`；
- v1/v2 虽共享 `0x50..0x69` 命令区间，但 selector、capability 和参数布局不同，不能把
  LinkBuds S 的 `0x00`/`0x17` payload 用于 WH-1000XM4。

## 3. 实现边界

- capability 必须严格匹配 selector、setting type、枚举、step、band 数和 preset 集合；
- profile 必须同时匹配型号、固件、协议代际、table 及 Classic SPP transport 才开放写入；
- NC、通透、人声增强、环境声等级和 capability 返回的 EQ preset 均执行
  `SET -> ACK/NTFY -> GET/RET`；
- V1 `OFF` 使用 effect `0x00`，同时原样保留当前 NC setting/value 与 ASM
  setting/mode/level；若尚未读到当前状态则拒绝写入，避免猜测 capability 字段；
- LinkBuds S v2/GATT 的 Phase 10 行为保持不变；未知 Sony 型号保持只读基线；
- 单次可逆真机闭环先提升为 `CONTROLLED`；20/20 连接/控制门禁通过后提升为 `STABLE`。

## 4. 自动化验证

`./gradlew :protocol:sony:test` 覆盖：

- v1 combined NC/ASM capability、NC、环境声、VOICE/NORMAL、等级及 OFF 读取解析；
- under-change、capability 不匹配、越界值和非法布局拒绝；
- v1 EQ capability 对 selector、band、level 和 preset 集合的约束；
- 完整 WH v1/SPP 假设备可逆 sequence；
- 45 个真机 Tandem frame 的 checksum、ACK/NTFY/GET/RET、状态顺序和最终恢复断言；
- V1 OFF 精确保留字段编码、无当前状态时拒绝，以及 capability 外 EQ 在发帧前拒绝；
- 既有 LinkBuds S v2/GATT 回归继续通过。

`./gradlew :app:testDebugUnitTest` 和完整 `:app:installDebug` 也必须通过。安装更新后按模块部署
约束重启 `com.android.bluetooth`、`com.milink.service`、`com.xiaomi.bluetooth` 作用域进程，
再确认模块加载与耳机回连。

## 5. 完成结论

精确 `WH-1000XM4 / 2.5.1 / Sony v1 / Classic SPP` 已满足 capability、真机可逆读回、
原值恢复、脱敏 fixture、JVM 回归和 20 次稳定性门禁，兼容级别为 `STABLE`。Phase 13 关闭；
V1 全效果 OFF 已经官方代码静态链路和真机 ACK/NTFY/GET/RET 往返验证并开放。
