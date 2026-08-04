# Sony 佩戴检测：能力与通道门禁

## 1. 目标与边界

Sony 佩戴状态进入通用 `DeviceReport.Wearing` 和版本化 snapshot，App 与 HyperOS UI 继续只按
`WEAR_DETECTION` capability 渲染，不识别 Sony 私有字节。实现只读取官方协议中已有的状态，不发送
控制命令，也不使用电量、播放状态或连接方式猜测是否佩戴。

当前保留两条相互独立的准确来源。任一来源完成有效读取即可启用佩戴状态；两条来源均不可用时，
主 Sony 会话仍正常进入 Ready，但不公开 `WEAR_DETECTION`，UI 因而不显示误导性的佩戴图标。

## 2. 通道门禁

| 状态来源 | 静态门禁 | 运行时门禁 | 失败行为 |
| --- | --- | --- | --- |
| MDR table2 | Sony V2、table2 已启用、support-function 包含 `0xF0` | `SYSTEM_GET_STATUS F2 00` 收到并解析 `F3/F5 00 <status>` | 不公开佩戴 capability，主会话继续 |
| Auto Play BLE | 主路由为 BLE GATT、Sony V2、support-function 包含 `0xA2` | 从 Sony `0x012D` 广播按当前 LE Audio 组成员地址摘要找到可连接端点；`F76ACB00` 服务打开；connect 与 status query 均成功 | 关闭辅助 GATT，不影响主 GATT、连接状态或电量 |

profile 只有在至少一次有效状态读取后才以 `VERIFIED` 证据公开只读 `WEAR_DETECTION`。后续刷新复用
已经验证的通道，通知和查询响应都进入同一 wearing state；`vendorStates["sony.wearing.transport"]`
仅用于诊断当前数据来自 `table2` 还是 `auto-play-ble`。

## 3. 协议映射

MDR table2 的 `0xF0` checker 将官方状态映射为左右耳通用状态：`NORMAL` 为两耳佩戴，
`LEFT_SIDE_NOT_WEAR`/`RIGHT_SIDE_NOT_WEAR` 为对应侧取下，`BOTH_NOT_WEAR` 为两耳取下，
`ILLEGAL` 为 unknown。

Auto Play BLE 使用官方 client id `00 7C`：command `CB01` 发送 connect `14 00 7C` 和 status query
`06 00 7C`，response/event `CB02/CB03` 返回状态。状态第二字节 bit 0 表示右耳佩戴，bit 1 表示
左耳佩戴。通用 GATT transport 会转发主 RX 以及 preparation 中订阅的附加 notification
characteristic，因此 response 与 event 共用同一可靠的输入流。

Auto Play 广播匹配只接受 Sony company id `0x012D`、固定 17 字节布局和 `13 00` 前缀；唯一 ID
使用当前已连接 LE Audio 组成员 A2DP 地址大写文本的 SHA-1 前两字节。地址原文和完整摘要不写入
日志、snapshot 或持久化文件。

## 4. LinkBuds S / LE Audio 验证记录

2026-08-04 在 LinkBuds S 4.2.1、Android 16 / HyperOS、LE Audio 保持开启的条件下验证：

- support-function 包含 Auto Play `0xA2`，不包含 MDR table2 wearing `0xF0`；
- support table 同时报告 `0x49FF`（Auto Play 不能与 LE Audio connection 共用）和 `0x4AFF`
  （GATT connectable 不能与 LE Audio connection 共用）；
- 3 秒受限扫描未发现当前组成员对应的可连接 Auto Play 广播端点；绑定/组成员回退端点也不提供
  `F76ACB00` 服务；
- 主 Sony GATT 会话继续 Ready，电量与既有功能正常；佩戴读取没有被验证，因此 snapshot 不公开
  `WEAR_DETECTION`，界面不显示佩戴图标。

本轮按测试约束没有关闭或切换 LE Audio。上述结果只说明“当前连接方式下辅助通道不可用”，不把
LinkBuds S 永久标记为不支持；在经典音频连接或将来出现可连接 Auto Play 广播时，同一能力与运行时
门禁会自动重新验证并启用佩戴状态。

## 5. 回归覆盖

- `SonyParsersTest`：table2 五种状态、错误 table/type/长度，以及 Auto Play client id、bit 映射和
  非法消息拒绝；
- `SonySessionTest`：能力缺失时不查询、有效读取后才公开 capability、通知刷新、辅助通道缺失时
  主会话仍 Ready；
- `GattTransportTest`：preparation 订阅的附加 characteristic 通知可进入 transport 输入流；
- `SonyAutoPlayGattDeviceResolverTest`：地址摘要、广播布局、组成员匹配与错误向量拒绝。
