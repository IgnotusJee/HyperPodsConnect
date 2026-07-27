# Phase 8：Sony Classic SPP 只读 MVP

状态：**已闭环**
日期：2026-07-27

## 证据边界

本阶段的主要静态证据来自 Sony 官方 App 13.0.8 的本地逆向参考包：
`reference/reverse-engineering-reference/`。它确认了 Tandem 帧、转义、checksum、
DataType、MDR table 路由和命令索引；具体机器命令载荷在参考包中标为
`index-only`，因此实现只采用可由多份证据互相印证的只读布局。

`testdata/fixtures/sony/official-static/` 中的向量由这些静态定义计算得出，**不是设备
抓包**。实机证据独立存放在
`testdata/fixtures/sony/device-capture/wh-1000xm4-2.5.1/`，没有移动或复制静态向量。
capability response 因包含设备唯一标识而被完整排除，不进入日志或仓库。

公开互操作实现只用于交叉核对 Sony SPP UUID、secure RFCOMM、ACK sequence、v1/v2
协议信息长度和 v2 battery 布局，没有复制其架构或源码。

## 已实现

- 新增纯 Kotlin/JVM 模块 `:protocol:sony`；
- Tandem SOF/EOF/escape、无符号 checksum、u32 big-endian 长度和 64 KiB 安全上限；
- 支持任意分片、粘包、噪声恢复、坏转义、坏长度和坏 checksum 拒绝；
- `DATA_MDR` / `DATA_MDR_NO2` 与 `SHOT` 的 table 路由，并保留未知消息；
- ACK 与业务响应分离，一次仅允许一个需要 ACK 的 Sony GET 在途；
- 严格只接受已广播的两个 Sony SPP UUID：
  - v1 `96CC203E-5068-46AD-B32D-E316F5E069BA`
  - v2 `956C7B26-D49A-4BA8-B03F-B17D393CB6E2`
- secure RFCOMM；设备名只能作为 driver hint，不能触发猜测 UUID；
- protocol / capability / model / firmware / support-function 完整握手；
- RFCOMM 打开后等待 300 ms 再发送首包，并对初始 protocol-info 做 3 次有界尝试；
- v1 COMMON 与 v2 POWER 的 battery GET/RET/NTFY 解析；
- 单体、左右耳与充电盒电量映射，充电状态严格限制在已知枚举；
- capability、support 和 protocol 原始响应只保留 SHA-256 fingerprint，不把未知字节
  当能力解释；
- 全部功能写能力为 false；非 refresh 命令在构帧前返回 `NOT_WRITABLE`；
- 只有完整握手且至少收到一份有效电量后，profile 才从 `DETECTED` 升为
  `READ_ONLY`；
- IPC 在 `READ_ONLY` 前隐藏型号、固件、电量、功能状态和 capability；
- IPC 传递设备拓扑；头戴式单电池在 UI 中显示为单栏“耳机”，不再套用左右耳/盒三栏；
- `Idle` 快照不会让设备选择页误判控制通道仍连接；
- OPPO 旧广播 facade 不消费 Sony snapshot 或 operation。

## 离线验证

执行：

```text
.\gradlew.bat :core:test :engine:test :protocol:oppo:test :protocol:sony:test \
  :transport:android:testDebugUnitTest :app:testDebugUnitTest lintDebug assembleDebug
.\gradlew.bat lintRelease assembleRelease
```

结果：

- `:protocol:sony` 14 个测试；
- 全仓 224 个 JVM 测试，0 failure、0 error、0 skipped；
- Debug/Release lint 通过；
- Debug/Release APK assemble 通过；
- `git diff --check` 通过。

Sony 测试覆盖 framing golden、reserved-byte escape、分片/粘包、坏 checksum、严格
handshake parser、table 路由、未知消息保留、左右/单体/盒电量、完整 ACK+握手、
`READ_ONLY` 晋级、secure UUID 选择、无 UUID 不开 transport，以及写命令零字节下发。

## 真机门禁

测试环境：

- 耳机：Sony WH-1000XM4；
- 固件：2.5.1；
- 手机：Xiaomi 13 Pro，Android 16 / API 36；
- Sony Sound Connect：13.0.8；
- 传输：secure Classic SPP，SDP 实际广播 Sony v1 UUID，RFCOMM channel 9；
- 官方 App 在 OppoPods 握手和 20 次稳定性循环期间保持 force-stopped。

冷连接结果：

- protocol-info 返回 v1 / `0x007000`；
- capability 完成但原始响应被隐私遮蔽；
- 型号返回 `WH-1000XM4`；
- 固件返回 `2.5.1`；
- support-function 返回 22 data bytes，即 11 个二字节 function pair；
- v1 COMMON battery 返回单体 100%、未充电；
- profile 只在完整握手与有效电量后进入 `READ_ONLY`；
- App 显示 `WH-1000XM4` 和单栏“耳机 100%”，没有左右耳/充电盒误标。

Sony Sound Connect 在稳定性门禁结束后单独打开核对，显示型号
`WH-1000XM4`、电量 100%、固件 2.5.1，与 OppoPods 完全一致；核对后再次
force-stop，未触发升级或设置写入。

稳定性结果：

- 20/20 次真实 SPP connect → 完整握手 → `READ_ONLY` → disconnect；
- 20 次 protocol/model/support response，20 个独立 Ready generation（2～21）；
- 0 Failed snapshot；
- 0 ACK timeout、response timeout、decode reject；
- 0 OppoPods/蓝牙相关 FATAL；
- 蓝牙进程 PID 全程不变；
- 日志中只有白名单 GET、Tandem ACK 和 response，没有 SET、FOTA、关机、配对、
  扫描或 raw console 命令。

脱敏实机帧与条件说明位于
`testdata/fixtures/sony/device-capture/wh-1000xm4-2.5.1/`，并由 JVM 测试逐帧校验
checksum、framing 和 capability response 缺失条件。

## 已知限制

- 本地逆向参考包未包含它所引用的完整 APK/JADX 目录，机器控制命令载荷证据仍不完整；
- battery type 只按设备回报的型号拓扑选择已知 GET：WH/WI 查询单体，WF/LinkBuds
  查询左右耳与充电盒，不执行未知 selector 扫描；
- Phase 8 的动态兼容结论仅覆盖 WH-1000XM4 / 2.5.1 / v1 SPP；其他 Sony 型号与固件
  仍需各自门禁；
- Phase 8 不做 Sony GATT fallback；没有已广播 Sony SPP UUID时明确失败；
- Phase 8 不实现 ANC、ASM、EQ 或任何其他控制；这些属于经过真机白名单验证后的
  Phase 10。
