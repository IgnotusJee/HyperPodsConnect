# Phase 10：Sony 可逆控制

状态：**进行中；NC/ASM 三态模式切片已闭环**
日期：2026-07-28

## 本次范围

本切片只开放 Sony NCASM 参数 `0x17` 的三个已验证状态：

| UI 状态 | `enabled` | `ambient` |
| --- | ---: | ---: |
| 关闭 | `0x00` | 保留当前值；设备读回归一为 `0x00` |
| 降噪 | `0x01` | `0x00` |
| 环境声 | `0x01` | `0x01` |

环境声 level 虽随帧读取和原样保留，但没有开放 level 写控件。智能、轻度、中度、
深度降噪、EQ、FOTA、关机、恢复出厂、配对管理、查找设备和 raw 命令均不在范围内。

## 证据链

静态参考来自 Sony 官方 App 逆向材料
`reference/reverse-engineering-reference/`。动态证据来自 Sony Sound Connect 13.0.8
与 LinkBuds S 4.2.1 的 BLE GATT 会话：

- `GET_PARAM 0x66/0x17` 对应 `RET_PARAM 0x67/0x17`；
- `SET_PARAM 0x68/0x17` 在官方 App 会话中对应独立 Tandem ACK 与
  `NTFY_PARAM 0x69/0x17`；
- 关闭、降噪和环境声均重复观察，且每次操作后恢复到初始关闭状态；
- 官方 App 动作会话 64/64 个 Frida GATT 事件与 HCI 字节级匹配，冷启动会话
  234/234 个匹配，均为零 unmatched。

脱敏帧与条件记录位于
`testdata/fixtures/sony/device-capture/linkbuds-s-4.2.1/`。原始 Frida、HCI、截图和
UI hierarchy 只保留在仓库外。

## 写入门禁

只有同时满足下列条件，profile 才从 `READ_ONLY` 升级为 `CONTROLLED`：

- model 精确等于 `LinkBuds S`；
- firmware 精确等于 `4.2.1`；
- transport 为 `BLE_GATT`；
- 协议为 Tandem v2，table 1 可用；
- support-function 包含 `0x17FF`；
- 初始 `0x66/0x17` 读取成功且布局严格合法。

任何条件缺失、未知固件、SPP、畸形响应或初始读取失败都会保持只读。写命令只接受
`OFF`、`NOISE_CANCELLATION`、`TRANSPARENCY`；其他枚举值在协议层拒绝，UI 也严格按
profile `allowedValues` 渲染。

## 操作事务

每次设置遵循同一事务：

1. 必须已有 confirmed NCASM 状态；
2. 基于 confirmed 状态构建 SET，保留环境声 level 和未修改字段；
3. 发送 SET，等待独立 Tandem ACK；
4. 给业务 NTFY 一个有界 400 ms 宽限期；
5. 无论是否收到 NTFY，都发送显式 GET；
6. 只有 RET 与目标状态一致才提交 confirmed；
7. timeout、断线、畸形响应或读回不一致均回滚 pending，不修改 confirmed。

官方 App 会话会收到 `0x69/0x17`，但项目真机会话只收到 ACK，没有业务 NTFY。实现
因此将 NTFY 作为可选的快速确认，将显式 GET/RET 作为不可省略的最终确认，避免把
Tandem ACK 误当成业务成功。

## 真机闭环

环境：

- 手机：Xiaomi 13 Pro；
- OS：Android 16 / API 36；
- 耳机：Sony LinkBuds S；
- 固件：4.2.1；
- 传输：Sony Tandem v2 HPC over BLE GATT；
- Sony Sound Connect 在项目验证期间保持 force-stopped。

最终成功路径：

- 完整握手读取型号、固件、support functions、电量和初始 NCASM；
- 初始 `RET 0x67/0x17` 为关闭，profile 进入 `Ready / CONTROLLED`；
- 页面只显示“降噪 / 通透 / 关闭”，不再显示未验证的四档降噪强度；
- 设置降噪收到 Tandem ACK，400 ms 内无 NTFY，随后 GET/RET 确认降噪；
- 设置关闭收到 Tandem ACK，400 ms 内无 NTFY，随后 GET/RET 确认关闭；
- 无 timeout、decode reject、会话失败或 UI 错误，最终状态恢复为关闭。

最终 HCI 窗口包含两组完整 SET/GET/RET，且没有 `0x69/0x17`。结构检查结果为零截断、
零 tail truncation、零 timestamp regression；未映射的 HyperOS trace 伪 ACL 句柄按
工具规则排除，不进入 target-only/window 证据。

## 自动化验证

新增或扩展的 JVM 覆盖包括：

- NCASM GET/RET/SET/NTFY 精确布局与 checksum；
- 畸形长度和非法字段拒绝；
- 型号、固件、transport、协议表、support-function 白名单 miss；
- SET 前必须读取；
- pending、ACK、NTFY、GET readback 和恢复原值；
- 缺少 NTFY 时回退到显式 GET；
- timeout/失败不污染 confirmed；
- UI 根据 `allowedValues` 隐藏未验证控制值。

最终门禁执行离线 `test`、Debug lint 与 Debug assemble，并配合 `git diff --check`
和隐私扫描。

## 剩余 Phase 10 工作

Phase 10 尚未整体完成。后续仍需按同一准入门槛逐项处理：

1. 环境声 level；
2. EQ preset；
3. 其他经风险评估允许的低风险功能。

在获得独立官方 App 动态证据、精确白名单、读回和恢复验证前，这些写能力继续保持
关闭。
