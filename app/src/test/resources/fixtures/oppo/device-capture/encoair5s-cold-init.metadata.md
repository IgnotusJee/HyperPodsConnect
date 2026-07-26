# encoair5s-cold-init

## 采集元数据

| 项目 | 值 |
| --- | --- |
| 型号 | OPPO Enco Air5s（设备名 `OPPO Enco Air5s`） |
| 固件 | **未采集**，见下方"未闭合项" |
| 拓扑 | TWS 双耳 + 充电盒 |
| 传输 | Bluetooth Classic RFCOMM，BR/EDR ACL 句柄 `0x0006`，RFCOMM DLCI `0x0A` |
| 手机 | Xiaomi 13 Pro（`nuwa` / `2210132C`） |
| 系统 | Android 16 / API 36 / HyperOS 3 |
| 官方 App | `com.heytap.headset` 16.7.1（与静态分析样本一致） |
| 采集时间 | 2026-07-26 |
| 采集方式 | HCI snoop（`sSnoopLogSettingAtEnable = FULL`）+ Frida spawn 门控 |
| 工具 | `tools/bluetooth-capture/`，M0 preflight 全绿 |

## 证据强度

这些帧同时出现在两条独立链路上：

- HCI btsnoop：RFCOMM 数据帧，来自蓝牙控制器；
- Frida：官方 App 的 `BluetoothSocket` 输入/输出流。

两者经 `analysis/correlate.py` 做字节级比对，7 个 TX payload 完全一致，时间偏移
离散度 5 毫秒，因此可确认这些字节确实由官方 App 发出并经射频传输，而非任何一侧的
推测。接收方向经分片重组后流级一致。

采集期间本项目 Xposed 模块未安装，不存在自证据污染。

## 已确认的协议行为

- `0x0100` capability 查询返回 `status=0x00` + 8 字节能力位图；
- 通知握手为 `0x0200 -> 0x8200 -> 0x0205 -> 0x8205`，本机返回 9 个通知 ID
  （`01 02 03 04 08 0B F1 F2 F3`），`0x8205` 逐项回报订阅状态；
- `0x0204` 是复用的通知事件通道，payload 首字节为报告类型：`01` 电量、`02` 佩戴；
- 电量报告为 `type + count + (component, level)` 序列，本次为左右各 `0x64`（100%），
  未出现充电位，且**没有仓组件**；
- 佩戴报告结构相同，本次为 `(01,05) (02,05) (03,04)`；
- 响应 command 为 `request | 0x8000`，seq 与请求一致。

## 与本项目实现的差异

`0x010C` ANC 查询：官方 App 发送 payload `03 01`，响应 payload 为 `01 03 01 00 00`；
本项目 `Cmd.QUERY_ANC_MODE` 发送的是 `01 01`，`AncModeParser` 扫描 `01 01 <v1> <v2>`
模式。因此本 fixture 的 ANC 响应**不能**被现有 parser 解出模式值，这是预期结果，不是
回归失败。该差异需要一次显式的 ANC 模式切换抓包才能定性，见未闭合项。

## 未闭合项

本 fixture 不足以关闭 Phase 0，仍缺：

1. 固件版本。本项目要求 UI 与协议双来源，当前两者都未取得；
2. `0x0106/0x8106` 电量查询闭环（本次只捕获到 `0x0204` 主动通知）；
3. `0x010F/0x810F` 与 `0x0406/0x8406` EQ 闭环；
4. `0x0404/0x8404` ANC 设置及每个支持模式的响应与回读；
5. Air5s 空间声开关（feature `0x1B`）的真实读写行为，用于判定名称白名单的
   子串匹配是否产生了误继承。

## 脱敏说明

- 原始 HCI 与 Frida 材料保留在仓库外的会话目录；
- 多设备连接列表相关帧（`0x0105` / `0x0112` / `0x012F` 响应及部分 `0x0204`）返回
  真实蓝牙设备名，含个人可识别信息，已由 `analysis/sanitize.py` **整帧剔除**，
  不做就地涂改，避免半脱敏的帧仍被当作完整证据；
- 本文件不含蓝牙地址、LinkKey、账号 token、序列号。
