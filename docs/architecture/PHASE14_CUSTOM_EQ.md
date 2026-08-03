# Phase 14：多厂商自定义 EQ

状态：**Phase 14 已闭环：14A Sony V1、14B OPPO Enco Air5s 均完成真机可逆读回**

## 1. 本阶段拆分

- **14A — 通用模型与 Sony V1**：整条曲线领域模型、能力描述、IPC、UI、Sony 官方协议静态链路和
  WH-1000XM4 真机可逆读回；
- **14B — OPPO**：从 HeyMelody 官方 App 恢复自定义 EQ 调用链和 wire command，完成指定型号真机闭环；
- **14C — 多型号完善**：用各型号 capability 提供真实频率标签、Clear Bass/特殊频段和不同增益范围。

OPPO 实现不按型号名猜测能力：只有设备的 `0x8100` bitmap 明确置位 bit 34，且 `0x8122` 返回完整、
可校验的自定义槽位后，session 才暴露该槽位并允许 `SetEqualizerCurve`。Enco Air5s 163.163.102
已满足该门禁并完成真机验证；其他 OPPO 型号仍不得外推。

## 2. Sony 官方 App 静态证据

本地 Sound Connect 13.2.1 JADX 代码给出了 V1 自定义曲线的完整链路：

- `sources/l20/C22724c.java` 的 `mo60297o(...)` 构造整条 band 数组，并传入
  `EqPresetId.UNSPECIFIED.getTableSet1()`；
- 官方 `EqPresetId.UNSPECIFIED` 的 table-set 1 值为 `0xFF`；
- `sources/se0/C27899t.java` 依次序列化 selector、preset、band count 和全部 band；
- 同一控制器的读取路径把 wire level 转为显示增益：
  `encoded - ((levelCount - 1) / 2)`。

因此 V1 `PRESET_EQ` 的自定义曲线 SET 是：

```text
58 01 FF <band-count> <all encoded bands>
```

`0xFF` 不是一个可选择的用户 preset，而是“编辑当前活动自定义槽位”。实现因此同时要求：当前活动
preset 必须是 capability 声明的可写自定义槽位，command 中的 `curve.slotId` 必须与其精确一致，且
整条曲线通过 band 数、范围和步进校验后才允许发帧。

## 3. 通用实现

`:core` 新增 `EqualizerBandSpec`、`EqualizerCurveSpec`、`EqualizerCurve` 和
`FeatureCommand.SetEqualizerCurve`。preset 仍表示设备的调音模式；curve 单独表示某一明确自定义槽位
的完整 band 值，二者不会在状态、IPC 或归档中互相替代。

`FeatureCapability.equalizerCurveSpec` 描述：

- band 的稳定 ID、显示标签、增益上下界、步进、可选中心频率和特殊类型；
- 可写自定义槽位集合；
- 完整曲线的发送前校验边界。

IPC 以可选字段增加曲线值与结构化 capability，旧 payload 仍可解码；profile archive schema 1 的
新增字段也为可选项，旧档案和既有 fingerprint 不受影响。UI 只在当前 preset 是可写槽位时渲染
capability 提供的滑块，拖动期间只更新本地值，松手后一次发送完整曲线，避免写入风暴。

WH-1000XM4 的 capability 只返回 band 数和 level 数，没有频率文字。初版因此显示
`Band 1..6`。2026-08-03 通过 Sound Connect 13.2.1 实机编辑页、反编译资源和 Bass Boost
`+7, 0, 0, 0, 0, 0` 读回完成交叉确认后，六值布局已修正为 wire 顺序：
`CLEAR BASS / 400 / 1k / 2.5k / 6.3k / 16k`。界面把五个标准频段并排显示，并将 Clear Bass
作为独立横向控件；非六值 Sony V1 capability 仍回退到 `Band N`，不会套用未验证频点。

## 4. WH-1000XM4 真机闭环

2026-08-02 在 Xiaomi 13 Pro / Android 16 上，以完整模块 APK 安装并重启 LSPosed 作用域进程后，
WH-1000XM4 2.5.1 通过 Classic SPP 保持 Ready。活动槽位为 `Custom 2`，初始 wire 曲线为：

```text
0A 13 11 13 12 11  ->  Clear Bass 0；400 +9；1k +7；2.5k +9；6.3k +8；16k +7
```

只把第一频段从 `0` 调到 `+1`：

```text
SET  58 01 FF 06 0B 13 11 13 12 11
ACK
GET  56 01
RET  57 01 A2 06 0B 13 11 13 12 11
```

随后恢复原值：

```text
SET  58 01 FF 06 0A 13 11 13 12 11
ACK
GET  56 01
RET  57 01 A2 06 0A 13 11 13 12 11
```

该固件在这两次自定义曲线 SET 后没有发送 EQ NTFY；实现按既定 grace window 等待后执行强制 GET，
并只在 RET 的活动槽位和完整曲线精确匹配时确认 operation。两次操作均成功，原始曲线已恢复，
SPP/A2DP 全程保持连接。脱敏字节证据位于
`testdata/fixtures/sony/device-capture/wh-1000xm4-2.5.1/wh-1000xm4-v1-custom-eq.hex`。

## 5. 验证与剩余边界

自动化覆盖发送前的错误槽位、错误 band 数、越界和步进拒绝，`0xFF` 精确编码，完整 readback 才确认，
状态 reducer 的 pending/confirmed 语义，IPC 新旧 payload 和 profile archive 兼容，以及假设备可逆恢复。

## 6. OPPO 官方 App 静态链路与实现

HeyMelody 16.7.1 的完整 APK/JADX 代码已恢复以下调用链：

- `CustomEqActivity` 加载 `p224qa.j`；默认频点为
  `62 / 250 / 1000 / 4000 / 8000 / 16000 Hz`，白名单可覆盖频点、增益范围和 UI 版本；
- 新增、更新/选择、删除分别调用 repository action `1 / 2 / 3`；滑块更新经 300 ms debounce 后走 action 2；
- repository action 1018 进入 `BluetoothService`，最终由 `HeadsetCoreService.G0()` 发送命令
  `0x0418`；GET all custom EQ 的 action 1017 最终发送 `0x0122`；
- capability 表 `p008a8/a.java` 的 bit 34 同时映射 `0x0122` 与 `0x0418`。Enco Air5s 的
  `FF 75 52 EA A4 0E 07 0F` 已置位 bit 34，并已补齐下述 GET/SET 真机闭环。

`0x8122` 成功响应的槽位结构为：

```text
status, slotCount,
repeated(selected, min, max, eqId, nameLen, UTF-8 name,
         bandCount, repeated(frequency LE16, gain int8))
```

`0x0418` SET payload 为：

```text
action, min, max, eqId, nameLen, UTF-8 name,
bandCount, repeated(frequency LE16, gain int8)
```

实现严格校验状态、长度、UTF-8、槽位/频段唯一性、增益范围和完整尾部；写成功后仍强制 GET
`0x0122`，只有选中槽位和完整曲线精确匹配才确认。静态向量位于
`testdata/fixtures/oppo/official-source/`，明确不是设备捕获。

自定义槽位生命周期也沿用官方动作：重命名使用 action 2，并原样携带设备刚读回的频点与增益；删除使用
action 3。两者只接受 `0x8122` 中真实存在的自定义 `eqId`，固定预设不会暴露编辑入口。重命名以同一槽位
的新名称读回为成功条件；删除则要求槽位从 `0x8122` 消失，并追加内置 EQ 查询以清除已删除槽位的旧选中
状态。名称按官方字段限制为非空且最多 128 个 UTF-8 字节。

## 7. OPPO Enco Air5s 真机闭环

2026-08-03 在 Xiaomi 13 Pro / Android 16 上，以完整模块 APK 安装并重启全部 LSPosed 作用域后，
OPPO Enco Air5s 163.163.102 通过 Classic SPP 进入 `Ready/STABLE`。初始 GET 返回成功且槽位数为 0：

```text
TX AA0700002201F00000
RX AA0900002281F002000000
```

测试创建临时槽位 `CodexTmp`，耳机分配 `eqId=4`，并读回六个频点
`62 / 250 / 1000 / 4000 / 8000 / 16000 Hz`、范围 `-6..+6 dB` 和全零曲线。随后只把 62 Hz
从 0 改为 +1；强制 `0x0122` 读回得到 `[1, 0, 0, 0, 0, 0]` 且状态来源为 `READ_BACK`。
同一路径再恢复 `[0, 0, 0, 0, 0, 0]` 并读回确认。

最后用 action 3 删除临时槽位，并按 HeyMelody 官方实现等待一秒后刷新；结果为 `ids=[]、curve=null`。
重启蓝牙及其他 LSPosed 作用域、session 再次进入 Ready 后，重复 GET 仍为 `ids=[]、curve=null`，耳机
没有遗留测试槽位。删除空槽位时 reducer 会显式清除旧 curve，避免 capability 已无槽位但 UI 仍显示
旧曲线。真机证据位于
`testdata/fixtures/oppo/device-capture/enco-air5s-163.163.102/`；文件只记录实际观察或发送的帧，未伪造
未捕获的 RX 字节。

同日后续用 HeyMelody 16.7.1 真机界面复核发现，上述六频段是早期测试主动构造并被耳机接受的可变布局；
Air5s 白名单 `customEqUiVersion=2` 的官方“添加均衡器”模板实际为十频段：
`31 / 62 / 125 / 250 / 500 / 1000 / 2000 / 4000 / 8000 / 16000 Hz`。点击添加会先创建并选中
“自定义1”，再打开十频段编辑底板。协议解析仍保留对设备返回可变频段布局的支持，产品新建模板则改为
官方十频段。界面层级和截图证据记录在
`docs/reverse-engineering/OFFICIAL_EQ_UI_ANALYSIS.md`。

追加生命周期实测时，耳机已有用户槽位“自定义1”。测试另建的 `HyperPods Custom` 先通过产品界面改名为
`CodexTmp`，`0x8122` 读回后列表立即显示新名称；随后经二次确认使用 action 3 删除，列表只剩“自定义1”。
最后重新选中该用户槽位，十段曲线 `[-6, +6, -6, +6, -4, +2, +5, +6, -5, +5]` 原样读回，证明测试
没有改写或删除既有用户数据。

真机测试还发现 HyperOS 可能在 instrumentation 活跃时把测试目标进程标记为 frozen，并以
`Skip broadcast to frozen process` 丢弃读回事件；硬件测试执行器因此需要在运行期间解除测试进程冻结。
hidden include-background 标志经复测仍无法绕过该厂商策略，故没有作为产品 workaround 保留。

## 8. Phase 14 结论与剩余边界

Phase 14 的完成标准已由 Sony WH-1000XM4 与 OPPO Enco Air5s 两条真机闭环满足。14C 继续作为多型号
完善工作：新增型号仍必须取得自身 capability、频点/范围、最小改动、恢复和重连证据，不能继承 Air5s
或 WH-1000XM4 的稳定性结论。
