# Root Android 蓝牙自动测试与抓包计划

记录日期：2026-07-26。环境表与实现约束已于同日在目标机上逐项实测复核。

## 1. 目标

建立一套可重复、可审计、默认只观察的蓝牙测试流水线，将一次耳机操作同时映射到：

1. 用户动作与官方 App UI 状态；
2. 官方 App 内部的协议命令、序号和 payload；
3. Android RFCOMM/GATT API 上的实际收发数据；
4. Bluetooth HCI snoop 中的真实 transport、连接和链路数据；
5. 脱敏后可进入项目 JVM 测试的 device-capture fixture。

第一阶段服务于 Phase 0 OPPO 真机证据闭环，随后复用到 Sony WH-1000XM4 和
Sony LinkBuds S。它不是固件升级、模糊写入、漏洞测试或系统蓝牙栈修改方案。

## 2. 当前已验证环境

下表每一行都在 2026-07-26 由实际命令确认，不是预期值。

| 项目 | 当前状态 | 复核命令 |
| --- | --- | --- |
| 手机 | Xiaomi 13 Pro（`nuwa`，`ro.product.model=2210132C`） | `adb devices -l` |
| Android | Android 16 / API 36 / HyperOS 3 | `getprop ro.build.version.release`、`.sdk` |
| Root | KernelSU，`uid=0(root)`，SELinux 域 `u:r:ksu:s0` | `adb shell su -c id` |
| SELinux | `Enforcing`，测试期间保持不变 | `adb shell getenforce` |
| ABI | `arm64-v8a` | `getprop ro.product.cpu.abi` |
| ADB | 已授权，恰好一台设备 | `adb devices -l` |
| PC Frida | Conda 环境 `frida-17.16.4`，Frida 17.16.4，frida-tools 14.10.4 | 见下方 conda 说明 |
| 手机 Frida | `/data/local/tmp/frida-server-17.16.4`；旧版 `/data/local/tmp/frida-server` 为 17.9.11 | `su -c '<path> --version'` |
| Frida 运行态 | 复核时 server 已在运行，`tcp:27052` 转发已建立 | `su -c 'ps -A'`、`adb forward --list` |
| 测试端点 | 手机 `127.0.0.1:27052`，ADB 转发到 PC `127.0.0.1:27052` | `adb forward --list` |
| Wireshark CLI | `C:\Program Files\Wireshark\tshark.exe` 4.2.3 | `tshark.exe --version` |
| Sony App | `com.sony.songpal.mdr` 13.0.8，已验证 Java Bridge attach/detach | `dumpsys package` |
| HeyMelody | `com.heytap.headset` 当前未安装；静态分析样本为 **16.7.1** | `pm list packages` |
| 本项目模块 | 当前未安装在测试机上，必须保持该状态，理由见 2.2 | `pm list packages` |
| HCI 完整 snoop | **未启用**：`sSnoopLogSettingAtEnable = DISABLED`，三个 `persist.bluetooth.btsnoop*` 均为关闭 | `dumpsys bluetooth_manager` |
| btsnooz 环形日志 | **存在**：`/data/misc/bluetooth/logs/btsnooz_hci.log` 及 `.last`，各约 104 KB | `su -c 'ls -la ...'` |

### 2.1 Frida 与 conda 的调用方式

`conda` 不在 bash 或 PowerShell 的 PATH 上，因此 §7 中 `conda activate frida-17.16.4`
这类写法只适用于用户已初始化 shell 的交互场景，**不能用于自动化脚本**。脚本必须直接
使用绝对路径：

```text
C:\Users\Ignotus\miniconda3\envs\frida-17.16.4\python.exe
C:\Users\Ignotus\miniconda3\envs\frida-17.16.4\Scripts\frida.exe
```

Frida 17.16.4 已在 Sony 用户 App 中完成 Java Bridge 验证。不要把该结论扩展到系统
进程：在当前 ROM 上对 `com.android.settings` 启用 Java Bridge 曾导致该进程 abort。
本计划禁止向 `system_server`、`com.android.bluetooth` 和其他系统进程注入 Frida。

检测 frida-server 存活时不能只按名字子串匹配。KernelSU 环境下 `ps -A` 会同时出现
`[frida-server-17]` 形式的条目和真实进程，需要按可执行文件路径或 PID 存活确认，并在
已有实例时复用，不重复启动。

### 2.2 本项目模块是首要污染源

本项目的 Xposed 模块注入在 `com.android.bluetooth` 内，对任何名称包含 `oppo` 的设备
自动建立 RFCOMM 控制连接（`HeadsetStateDispatcher.kt` 的名称判断）。而本计划 10.1 的
采集目标正是一台 OPPO 耳机。若测试机装有并启用了本模块，会同时造成两种破坏：

1. 与 HeyMelody 争用同一 SPP UUID，导致官方 App 建连失败或行为异常；
2. 把**本项目自己发出的包**混入 HCI 抓包。这是最危险的一种，因为它会形成循环证据：
   用自己的实现产生的流量去"验证"自己的实现，而 fixture 表面上看不出来源。

因此采集期间必须确认本模块未安装，或已在 LSPosed 中对 `com.android.bluetooth` 停用
并重启蓝牙进程。该检查是 preflight 的强制项，不是建议项。

## 3. 自动化边界

### 3.1 默认自动完成

- ADB、Root、ABI、SELinux、Frida、TShark 和目标 App 版本检查；
- 会话目录、metadata、时间线和动作 marker 创建；
- Frida server/ADB 转发状态检查；
- 目标 App 启动、用户进程 attach、只观察 Hook 加载；
- logcat、屏幕截图和 UI hierarchy 采集；
- HCI 文件定位、复制和校验；
- TShark 协议过滤、时间窗裁剪和 JSON 导出；
- Frida 与 HCI 数据关联；
- MAC、账号标识和无关流量脱敏；
- `.hex`、metadata 和报告生成；
- fixture 完整性检查和离线解析测试。

### 3.2 默认由用户完成

- 在开发者选项中启用 HCI snoop；
- 配对、佩戴、放回充电盒等物理动作；
- 首次运行官方 App 的协议/隐私确认；
- ANC、EQ 等 UI 操作，按采集器提示逐项执行；
- 对自动生成 fixture 的最终脱敏复核。

### 3.3 后续可选 UI 自动化

可用 UIAutomator 按 resource ID、可访问文本和当前页面状态执行白名单动作。坐标点击
不得作为默认路径。自动化只允许：

- 查询电量和版本；
- ANC：关闭、降噪、通透；
- 切换已知 EQ 预设；
- 打开设备详情；
- 返回上一级。

以下入口即使出现在 UI hierarchy 中也必须拒绝：

- OTA、固件升级和固件下载；
- 恢复出厂设置；
- 解除配对、删除设备；
- 诊断上传、账号和隐私数据导出；
- 查找设备发声；
- 未列入场景文件的实验室功能。

另有一类不破坏设备、但会直接中断采集的入口，同样必须避让。实测踩到的一个：
HeyMelody 设备页的 **"设备管理"** 看起来像设置页，实际是多设备切换入口，点击后弹出
"连接新设备"确认框，确定即把耳机交给另一台手机、断开当前连接。已加入
`ui-drive.ps1` 的避让清单，并对该确认框做出现即停止处理。**"断开连接"** 同理。

命名像设置的入口不等于只读，接入新 App 时应先只读 dump 一遍再决定是否放行。

#### 点击目标必须解析到真正可点击的节点

按可见文本定位到的往往是不可点击的 `TextView` 标签，真正的控件是同容器内的兄弟
节点。实测中 ANC 三档的文本节点 `mode_name` 为 `clickable=false`，可点击的是其上方的
`ImageView`；点在文本上不会报错，只是**静默无效**，表现为"点了但协议层什么都没发"。
定位逻辑必须在标签不可点击时，回退到与其水平投影重叠且垂直距离最近的可点击节点。

反过来也存在：EQ 预设列表整页只有返回键标了 `clickable=true`，预设项用自定义触摸
处理，层级里看不出可点击性。此时只能直接点文本 bounds，**并以协议流量作为生效判据**。
因此"点击是否生效"永远不能只看 UI 属性，必须由 HCI 或 Frida 侧确认有命令下发。

#### 停止规则要区分"不该点"和"整页危险"

最初把两者合并成一张表，导致设备页因为下方列有"查找耳机"就整页拒绝操作，把同页
合法的 ANC 与音效控件一并挡住。正确划分是：denylist 控件**永不点击**（在定位阶段
拒绝），另设一组更窄的页面级标记（恢复出厂、固件升级、解除配对、连接新设备等确认框）
才触发**整体停止**。

## 4. 总体架构

```text
scenario JSON
    |
    v
capture.ps1 ---------------------------------------------+
    |                                                    |
    +--> preflight                                       |
    +--> session/metadata/events.jsonl                   |
    +--> adb logcat + screenshot + UI hierarchy          |
    +--> Frida collector --> app/transport-events.jsonl  |
    +--> HCI snapshot --> btsnoop_hci.log                |
    +--> TShark --> rfcomm.json / att.json               |
    +--> correlator --> correlation.json                 |
    +--> sanitizer --> fixture.hex + metadata.md         |
    +--> verifier --> report.md + test result -----------+
```

计划新增：

```text
tools/bluetooth-capture/
├── capture.ps1
├── README.md
├── requirements.txt
├── scenarios/
│   ├── oppo-air5s-cold-init.json
│   ├── oppo-air5s-battery.json
│   ├── oppo-air5s-anc.json
│   ├── oppo-air5s-eq.json
│   ├── sony-wh1000xm4-discovery.json
│   └── sony-linkbudss-discovery.json
├── agent/
│   ├── package.json
│   ├── agent.ts
│   ├── generic-rfcomm.ts
│   ├── generic-gatt.ts
│   ├── oppo-heymelody.ts
│   └── sony-discovery.ts
├── collector/
│   ├── collect.py
│   ├── event_writer.py
│   └── session.py
└── analysis/
    ├── locate_hci.ps1
    ├── filter_hci.ps1
    ├── correlate.py
    ├── sanitize.py
    └── verify_fixture.py
```

Frida 17 的裸 GumJS runtime 不再自动包含 Java Bridge。`agent.ts` 必须显式：

```typescript
import Java from "frida-java-bridge";
```

并通过 `frida-compile` 生成 collector 加载的单文件 agent。不能把仅在 Frida REPL
中可运行的 `Java.perform(...)` 脚本直接交给 Python `create_script()`。

## 5. 会话和产物

原始材料必须位于仓库外，例如：

```text
D:\HeadphoneCaptures\
└── 20260726-170000-oppo-air5s-anc\
    ├── session.json
    ├── events.jsonl
    ├── raw\
    │   ├── btsnoop_hci.log
    │   ├── frida-events.bin
    │   ├── frida-events.jsonl
    │   ├── logcat.txt
    │   ├── bluetooth-manager.txt
    │   └── screenshots\
    ├── derived\
    │   ├── rfcomm.json
    │   ├── att.json
    │   ├── target-only.pcapng
    │   └── correlation.json
    ├── sanitized\
    │   ├── scenario.hex
    │   └── scenario.metadata.md
    └── report.md
```

只有 `sanitized` 中人工复核后的文件可以复制到：

```text
docs/reverse-engineering/fixtures/oppo/device-capture/
```

`session.json` 至少记录：

- 会话 ID、场景 ID、开始/结束时间和时区；
- 手机型号、Android/API、Root 和 SELinux 状态；
- 目标耳机型号、固件和拓扑；
- 官方 App 包名、versionName、versionCode 和 APK 路径摘要；
- Frida client/server 版本、agent 构建哈希；
- HCI 文件原始 SHA-256；
- 实际 transport、UUID、connection handle 或逻辑连接 ID；
- HCI、Frida、logcat 和 UI 自动化是否成功；
- 脱敏规则版本和人工 reviewer。

不得把完整 ADB serial、真实 MAC、LinkKey、LTK、IRK、账号 token、手机号、邮箱或
耳机序列号写入仓库内 metadata。

## 6. 场景定义

示例：

```json
{
  "id": "oppo-air5s-anc",
  "deviceAlias": "oppo-air5s-01",
  "appPackage": "com.heytap.headset",
  "mode": "guided",
  "transportCandidates": ["RFCOMM", "GATT"],
  "steps": [
    {
      "id": "baseline",
      "prompt": "保持当前模式，不操作",
      "settleSeconds": 5
    },
    {
      "id": "anc_on",
      "prompt": "切换到降噪",
      "settleSeconds": 8
    },
    {
      "id": "transparency",
      "prompt": "切换到通透",
      "settleSeconds": 8
    },
    {
      "id": "anc_off",
      "prompt": "关闭降噪",
      "settleSeconds": 8
    }
  ],
  "repeat": 3,
  "forbiddenUiTerms": [
    "固件升级",
    "恢复出厂设置",
    "解除配对",
    "删除设备"
  ]
}
```

每一步执行：

1. 写入 host event；
2. 执行 `adb shell log -t BT_CAPTURE "MARK <step> BEGIN"`；
3. 截图和保存 UI hierarchy；
4. 提示用户或执行白名单 UI 操作；
5. 等待稳定时间；
6. 再次截图和保存 UI hierarchy；
7. 写入 `MARK <step> END`；
8. 检查目标 App、Frida server 和蓝牙连接是否仍存活。

动作失败时不自动重试点击。先停止场景并保存当前材料，避免重复操作污染时间窗。

## 7. Preflight

每次会话开始前必须通过：

```text
[ ] 恰好一个 ADB device，状态为 device
[ ] su -c id 为 uid=0
[ ] ABI 包含 arm64-v8a
[ ] SELinux 为 Enforcing
[ ] Conda 环境中的 frida 为 17.16.4（按绝对路径调用，不依赖 conda activate）
[ ] 手机 server 为 17.16.4
[ ] frida-server-17.16.4 正在运行（按路径确认，不按名字子串）
[ ] tcp:27052 ADB forward 存在
[ ] frida-ps 能找到目标 App
[ ] TShark 可执行且能读取测试文件
[ ] 本项目 Xposed 模块未安装或已对 com.android.bluetooth 停用
[ ] HCI snoop 已启用（按 7.1 的确定性断言判定，不只看文件是否存在）
[ ] 会话窗口内 HCI 文件字节数确实增长
[ ] 目标官方 App 版本与预期一致（OPPO 场景要求 com.heytap.headset 16.7.1）
[ ] 目标耳机已配对，但实验开始时无其他耳机控制 App 竞争
[ ] 输出目录不在 Git 工作区
[ ] 磁盘空间满足至少 2 GiB
```

任一关键项失败时不得进入采集阶段。

### 7.1 snoop 启用的确定性断言

原先"出现可增长的日志文件"是事后观察，会让用户做完整轮操作后才发现窗口是空的。
本机存在可在采集前判定的只读信号，必须优先使用：

```powershell
adb shell dumpsys bluetooth_manager   # 读取 sSnoopLogSettingAtEnable
adb shell getprop persist.bluetooth.btsnooplogmode
adb shell getprop persist.bluetooth.btsnoopenable
adb shell getprop persist.bluetooth.btsnoopdefaultmode
```

当前未启用时的实测基线为 `sSnoopLogSettingAtEnable = DISABLED`，三个属性分别为
`disabled`、`false`、`disabled`。preflight 断言 `sSnoopLogSettingAtEnable` 不为
`DISABLED`；属性值仅作为辅助证据记录进 `session.json`。

脚本只读这些属性。**不得写入** `persist.bluetooth.*`，理由见 8.1。

### 7.2 Root 命令调用约定

`adb shell su -c "cmd1; cmd2"` 只把 `su` 应用于第一条命令，其余回落到 shell 域执行。
实测中这会产生具有误导性的失败：读取 `/data/misc/bluedroid` 报 permission denied，而
`dmesg` 显示 `avc: denied ... scontext=u:r:shell:s0`——真正的 root 读取该目录完全正常。

因为 HCI 定位、临时副本创建和 preflight 全部依赖 root 调用，脚本必须遵守：

- 每条需要 root 的命令单独 `su -c`，或统一写成 `su -c 'sh -c "..."'`；
- 不把多条 root 命令用 `;`、`&&` 拼进同一个 `su -c` 字符串；
- 判定 root 失效前先检查 AVC 记录的 `scontext`，为 `u:r:shell:s0` 说明是调用方式
  错误，不是 Root 失效，不应触发 13 节的"停止"处置。

### 7.3 当前环境的恢复命令

```powershell
$FridaHome = 'C:\Users\Ignotus\miniconda3\envs\frida-17.16.4'

adb shell su -c '/data/local/tmp/frida-server-17.16.4 -D -l 127.0.0.1:27052'
adb forward tcp:27052 tcp:27052
& "$FridaHome\Scripts\frida-ps.exe" -H 127.0.0.1:27052 -ai
```

若已有 PID 或 forward，preflight 应复用，不重复启动。

## 8. HCI 采集

### 8.1 启用原则

优先通过开发者选项启用 Bluetooth HCI snoop，再关闭和开启蓝牙。不要在首版脚本中
自动写入 `persist.bluetooth.*` 属性，因为 OEM 对属性名、重启范围和日志模式有差异。

Root 用于定位和只读复制日志。实测的搜索根目录：

```text
/data/misc/bluetooth/          # 777，含 logs/ 子目录，必须递归搜索
/data/misc/bluedroid/          # 770 bluetooth:bluetooth，root 可读，须按 7.2 单独 su -c
```

#### btsnooz 与 btsnoop 必须区分

搜索模式**不能**写成 `*snoop*`。本机在 snoop 关闭状态下仍持续写入
`/data/misc/bluetooth/logs/btsnooz_hci.log` 和 `.last`（各约 104 KB），文件名是
`btsnooz`（**z**），`find -iname '*snoop*'` 实测返回空，会直接漏掉。正确模式为
`bt*snoo*`，且必须递归。

更重要的是这个文件带有合法的 `btsnoop\0` magic header，TShark 和 Wireshark 能直接
打开，因此**极易被误当成真实抓包**。它实际是 snoop 关闭时仍在滚动的截断环形缓冲，
内容不完整。处置规则：

- 文件名匹配 `btsnooz*` 的来源一律**拒绝**进入 `derived` 和 `sanitized`；
- 允许在 `raw` 中保留一份并在 `session.json` 标注 `role: "ring-buffer-not-capture"`，
  仅用于对照排查；
- `session.json` 必须同时记录采集窗口两端的 `sSnoopLogSettingAtEnable`，任一端为
  `DISABLED` 的会话标记为 incomplete，不产出 fixture。

对每个命中文件记录路径、inode、大小、mtime 和 SHA-256。不得删除、截断或 chmod 原始
系统日志。

### 8.2 时间窗

会话开始时记录 HCI 文件大小和主机/手机双时间；结束后复制完整文件到
`/data/local/tmp/<session>-btsnoop.log`，只修改临时副本权限，再 `adb pull`。拉取成功
并校验 SHA-256 后删除该精确临时文件。

后处理按 `events.jsonl` 的最早 `BEGIN - 10 秒` 到最晚 `END + 10 秒` 裁剪。保留完整
原始 HCI 于仓库外，`derived` 中仅保留目标时间窗和目标连接。

### 8.3 本机 ROM 的两个必须校正项

这两项都在 2026-07-26 实测确认，未校正时 TShark 输出看起来正常但完全不可信。

#### 厂商 trace 伪装成 ACL

HyperOS 的 snoop logger 会把厂商 trace 记录以**结构完全合法**的 H4 ACL 形式写入
snoop 流，固定使用高位句柄 `0x0EDC`。实测一份 7756 条记录的捕获中，5534 条 ACL 属于
该句柄，且：

- btsnoop 记录框架完全正常：0 截断、0 时间戳回退；
- 每条 ACL 记录的 `5 + dataTotalLength == recordLength` 全部自洽；
- 但**没有任何对应的 Connection Complete 或 LE Connection Complete 事件**。

TShark 会把它们当作真实 L2CAP 解析，产出约 30 个互不相同、低字节恒为 `0xff` 的
伪 CID，以及乱码的设备名。这些输出足以骗过肉眼审阅。

唯一可靠的判别依据是连接建立事件：先从 `0x03`、`0x2C` 和 LE Meta `0x01/0x0A` 建立
句柄到对端地址的映射，只承认映射内的句柄，其余一律排除。实测同一份数据在排除
`0x0EDC` 后，剩余 61 帧立刻正确解析为 L2CAP 与 ATT，可见
`Read By Type Request, Device Name` 和 `LE Credit Based Connection Request`。

因此**不得**先用 `-Y 'btrfcomm || btatt'` 之类的协议过滤直接得出结论，必须先做句柄
映射。该逻辑实现在 `tools/bluetooth-capture/analysis/inspect_btsnoop.py`。

#### btsnoop 时间戳写入的是本地时间

btsnoop 规范要求时间戳字段为 UTC，但本机 Android 写入的是**本地时间**。实测偏移为
28800 秒（正好一个 UTC+8 时区偏移），即 TShark 显示的帧时间比真实墙钟时间晚 8 小时。

后果是按真实墙钟构造的 `frame.time` 过滤会匹配到零帧，而且不会报错。校正方式不应
假设时区，而应每份捕获实测：取捕获最后一帧时间与会话结束时读到的设备时钟之差，
向最近的 900 秒取整（任何真实 UTC 偏移都是 15 分钟的整数倍）。残差超过 450 秒时
判定为无法可靠确定，此时跳过时间窗裁剪并显式告警，不得静默输出一个错误的窗口。

另需注意 `frame.time` 字面量按**本地时间**解析，构造过滤器时不能格式化成 UTC 字符串。

#### 产物拆成两个文件

时间窗为空不应丢失证据，因此按句柄过滤和按句柄加时间窗过滤分别输出：

```text
<name>.target-only.pcapng      # 仅按已证明的连接句柄过滤，证据文件
<name>.target-window.pcapng    # 句柄 + 会话时间窗，场景作用域文件
```

### 8.4 TShark

固定使用：

```powershell
$Tshark = 'C:\Program Files\Wireshark\tshark.exe'
```

第一遍发现：

```powershell
& $Tshark -r .\btsnoop_hci.log -q -z io,phs
& $Tshark -r .\btsnoop_hci.log -Y 'btrfcomm || btatt'
```

第二遍根据已经确认的地址、handle、RFCOMM channel、Service UUID 和 Characteristic
UUID 定向过滤。过滤规则必须进入 `session.json`，不能只保存在 shell history。

如果目标包走 RFCOMM，应导出可重组的 RFCOMM payload；如果走 GATT，应保留写请求、
写响应、通知、indication、MTU、service discovery 和 CCCD 配置事件。

## 9. Frida Hook 层

所有 Hook 默认只观察，调用原方法并保持参数、返回值、异常和线程语义。

### 9.1 通用 RFCOMM

候选入口：

- `BluetoothSocket.connect()`；
- `BluetoothSocket.getInputStream()`；
- `BluetoothSocket.getOutputStream()`；
- 实际 `BluetoothInputStream.read(byte[], int, int)`；
- 实际 `BluetoothOutputStream.write(byte[], int, int)`。

记录：

- wall-clock、monotonic time、PID、TID；
- direction、offset、length、原始 bytes；
- socket/stream 的会话 ID；
- 目标 UUID 和脱敏地址；
- 采样调用栈哈希。

只 Hook 最终带 offset/length 的重载，避免 `write(byte[])` 内部转调造成重复事件。

### 9.2 通用 GATT

同时覆盖新旧 API：

- `BluetoothGatt.writeCharacteristic(characteristic)`；
- `BluetoothGatt.writeCharacteristic(characteristic, byte[], writeType)`；
- `BluetoothGattCharacteristic.setValue(byte[])`；
- `BluetoothGatt.writeDescriptor(...)`；
- `BluetoothGatt.requestMtu(...)`；
- `BluetoothGatt.discoverServices()`。

RX 需要 Hook 目标 App 的实际 `BluetoothGattCallback` 子类，而不是只 Hook 基类：

- `onCharacteristicChanged` 的新旧重载；
- `onCharacteristicRead`；
- `onDescriptorWrite`；
- `onServicesDiscovered`；
- `onMtuChanged`；
- `onConnectionStateChange`。

每个 `BluetoothGatt` 实例分配稳定的 session ID，记录 service、characteristic、
descriptor UUID 和 write type。

### 9.3 OPPO 语义层

静态样本固定为 `com.heytap.headset` **16.7.1**（`reference/oppo-app-official/base.apk`，
分析见 `OPPO_BASE_APK_AND_DEXDUMP` 两份文档）。混淆名只对该版本有效，因此：

- 采集前必须确认设备上安装的就是 16.7.1，版本号与 APK SHA-256 一并写入 `session.json`；
- 测试机当前未安装该 App，从应用商店安装到的版本大概率不是 16.7.1，应直接安装参考 APK；
- 版本不符时不得沿用下列混淆名。

候选入口按证据强度分两级。**已由 DEX 分析交叉确认**：

| 入口 | 职责 |
| --- | --- |
| `HeadsetCoreService.u0(address, packet)` | 发送内层包，并建立请求超时项 |
| `E8/b` | OPOv1 构帧与分片 |
| `F8/b` | 变长长度、流拼接与切包 |
| `p341z8/a` | Classic RFCOMM 建连与读循环 |
| `p316x8/h`、`p316x8/e` | GATT service/characteristic、通知、MTU、写队列 |

**尚未确认，attach 时须先验签**：

| 入口 | 状态 |
| --- | --- |
| `HeadsetCoreService.j0(address, packet)` | 接收路径的推测入口，DEX 分析文档中查无记录 |
| `F8.b.e(byte[])` | 方法名与分析文档记载的 `spliceMTUPackage()` 不一致，需确认是否为同一方法的混淆名 |

attach 前必须验证类和方法签名；不匹配时只启用通用 transport Hook 和调用栈发现，
不做模糊猜测。上表第二组在验签通过前不得写进 agent 的默认 Hook 集合。

记录四级事件：

```text
TX_INNER -> TX_LINK -> RX_LINK -> RX_INNER
```

用于证明 command、seq、payload、外层 framing、实际 transport 和 response 关联。

### 9.4 实现中确认的三项约束

**目标进程必须按包名从 application 列表解析。** frida-server 的
`enumerate_processes()` 对应用返回的是**显示名**（Sony App 返回 `Sound Connect`），
不是包名，按包名匹配会静默找不到进程并误报"目标未在运行"。可靠做法是用
`enumerate_applications()` 匹配 `identifier`，进程名匹配只作为非应用进程的兜底。

**只 attach，不 spawn。** spawn 会重启目标、改变 PID，并丢弃操作员刚建立好的蓝牙
会话，而 M2 的验收标准恰恰是 PID 前后不变。目标未运行时应直接失败并提示手动启动。

**流类名必须运行时发现，且不能只靠工厂方法。** `BluetoothSocket.getInputStream()` /
`getOutputStream()` 背后的具体流类是包级私有的，且在 AOSP 各版本间改过名，不能硬编码。
基本做法是 hook 这两个工厂方法，从返回对象上取实际类名再挂钩。

但只有这一条路径时，attach 到**已经建立连接的 App** 会完全失效：socket 和流早在 attach
之前就取好了，工厂方法不会再被调用，于是一个 hook 都装不上，采集看起来一切正常却始终
零事件。实测本机为 `android.bluetooth.BluetoothInputStream` /
`BluetoothOutputStream`。因此还必须在安装阶段用 `Java.enumerateLoadedClassesSync()`
扫描 `android.bluetooth.*Stream*` 并直接挂钩，工厂方法路径退化为对后续加载类的补充。

同理，GATT 的接收侧必须从 `connectGatt` 的实参上取得 App 真实的
`BluetoothGattCallback` 子类——App 通常重写回调且不调用 super，只挂基类观察不到任何事件。

写入侧只挂带 offset/length 的重载：无 offset 的变体内部转调它们，两个都挂会让每个
字节重复计数。

### 9.5 Sony 发现层

第一轮不假设 Sony 协议类名：

1. 同时启用通用 RFCOMM/GATT Hook；
2. 对每种操作保存有限调用栈；
3. 聚类出反复出现的 Sony App 类和 native library；
4. 第二轮为稳定入口增加语义 Hook；
5. 第三轮才生成协议 fixture。

不要从 OPPO 命令结构类推 Sony 帧结构。

## 10. 目标设备测试矩阵

### 10.1 OPPO Enco Air 5s

前置：安装参考版本 HeyMelody 16.7.1 并记录版本与 APK SHA-256；确认本项目模块未启用
（见 2.2）。

固件版本必须双来源记录，缺一不可：官方 App 设备详情页截图，以及协议侧设备信息响应。
只有 UI 截图而无协议来源时，`session.json` 的固件字段标记为 `ui-only`，该会话不满足
15 节的完成标准。

必须完成：

1. `cold-init`：未连接到官方 App ready；
2. `battery`：查询响应和一次主动通知；
3. `anc`：关闭、降噪、通透，每项三次；
4. `eq`：默认、一个预设、恢复默认，每项三次。

重点验证：

- 实际 Classic RFCOMM 或 SPP-over-GATT；
- 实际 UUID；
- `0x0100/0x8100` capability；
- `0x0200/0x8200` 通知能力；
- `0x0205/0x8205` 或逐项 `0x0201`；
- `0x0106/0x8106` 和 `0x0204` 电量通知；
- `0x010C/0x810C`、`0x0404/0x8404` ANC；
- `0x010F/0x810F`、`0x0406/0x8406` EQ；
- response 状态、seq 和 readback。

顺带解决一个既有的名称匹配隐患：`DeviceCapabilities.kt` 的
`isDeviceInCapabilityList` 使用规范化子串包含匹配，白名单项 `OPPO Enco Air5` 规范化
后为 `oppoencoair5`，是 `oppoencoair5s` 的子串，因此 **Air 5s 会自动继承 Air5 的空间
声开关能力**，而该继承从未被验证过。本场景额外增加一项验收：确认空间声开关
（feature `0x1B`）在 Air 5s 上的真实读写行为，并据此决定白名单匹配是否需要改为精确
匹配或加词边界。

### 10.2 Sony WH-1000XM4

执行：

1. 冷启动 Sound Connect；
2. 建连到设备页稳定；
3. ANC 开/关；
4. 环境声两个相邻等级；
5. EQ 默认、一个预设、恢复默认。

第一轮目标是确认 transport、连接入口、帧边界和调用栈，不要求立即完成协议命名。

### 10.3 Sony LinkBuds S

执行与 WH-1000XM4 相同的基础场景，并额外记录：

- GATT service discovery；
- MTU；
- CCCD；
- 所有通知 characteristic；
- 是否存在左右耳或控制/音频多个逻辑连接。

transport 必须由 HCI 与 Hook 共同证明，不依据产品形态预设。

## 11. 关联规则

每个 Frida 数据事件至少用以下证据与 HCI 对齐：

1. 方向相同；
2. payload 完全相等，或能在链路分片重组后完全相等；
3. 时间差在可配置阈值内，初始阈值 500 ms；
4. transport、UUID/channel 和连接会话一致；
5. 同一 payload 重复时使用 seq、前后包和动作时间窗消歧。

OPPO inner packet 还需验证：

```text
responseCommand = requestCommand | 0x8000
request.seq = response.seq
declaredPayloadLength = actualPayloadLength
```

set 操作只有在 response 状态成功，并出现通知或 readback 一致时，才能标记为
`confirmed`。写入 API 返回成功只能标记为 `sent`。

## 12. 脱敏和 fixture

自动脱敏：

- 将真实 MAC 映射为同会话稳定别名；
- 删除 ADB serial、手机蓝牙地址、账号和 token 字段；
- 只保留目标连接和目标时间窗；
- 排除 audio payload、固件和 OTA 数据；
- 对输出执行敏感字段扫描。

人工复核：

- 不包含 LinkKey/LTK/IRK；
- 不包含手机号、邮箱、SN 和账号 ID；
- 不包含其他已配对设备；
- `.hex` 中 TX/RX 顺序和方向正确；
- metadata 与真实型号、固件、transport 和场景一致。

fixture 格式沿用：

```text
<model>-<firmware>-<scenario>.hex
<model>-<firmware>-<scenario>.metadata.md
```

会话目录内使用的短名（5 节的 `scenario.hex`）只是工作文件名。复制进
`docs/reverse-engineering/fixtures/oppo/device-capture/` 时必须重命名为上述完整格式，
`verify_fixture.py` 强制校验型号与固件前缀存在且与 metadata 一致，否则拒绝。

这条规则的目的是让两类证据在文件名层面就不可混淆：`app/src/test/resources/fixtures/
oppo/official-source/` 下是 `battery-response.hex` 这类无型号前缀的 DEX 推导向量，
带型号加固件前缀的一律是真机抓包。任何 fixture 都不得跨目录复制。

## 13. 故障与恢复

| 故障 | 处理 |
| --- | --- |
| ADB unauthorized | 停止，不撤销全局授权；提示用户在手机确认 |
| Root 失效 | 停止，不尝试切换 Root 实现 |
| root 命令报 permission denied | 先查 AVC `scontext`：为 `u:r:shell:s0` 说明违反 7.2 的调用约定，修正命令而非判定 Root 失效 |
| 只找到 `btsnooz_hci.log` | 视为 snoop 未启用，停止场景；该文件不得进入 derived/sanitized |
| 本项目模块处于启用状态 | 停止，不采集；先停用模块并重启蓝牙进程 |
| HeyMelody 版本不是 16.7.1 | 停止语义 Hook，仅保留通用 transport Hook 与调用栈发现 |
| Frida 版本不一致 | 停止，报告 client/server 两端版本 |
| server 不存在 | 停止，不自动从非官方来源下载 |
| Java Bridge 加载失败 | 卸载当前 agent；检查是否通过 frida-compile 打包 |
| 目标 App 崩溃 | 保存材料，停止场景；不循环重启 |
| 系统进程匹配 Hook 目标 | 拒绝 attach |
| HCI 文件不增长 | 停止场景，提示重新启用 snoop 和重启蓝牙 |
| 耳机断连 | 保存当前窗口，标记 incomplete |
| UI 控件不唯一 | 停止自动点击，回退 guided 模式 |
| 检测到 OTA/恢复出厂页面 | 立即停止 UI 自动化 |

回退 Frida 时保留版本化文件。当前旧版仍为：

```text
/data/local/tmp/frida-server
17.9.11
```

不得在自动脚本中执行 `setenforce 0`、修改系统蓝牙库、覆盖原 server、删除系统 HCI
日志或清空 App 私有数据。

## 14. 实施里程碑

### M0：环境和安全门禁

交付：

- `capture.ps1 preflight`；
- Conda/Frida/TShark/ADB/Root 检查，全部按绝对路径调用（见 2.1）；
- 按 7.2 约定实现的单条 root 调用封装；
- 按 7.1 实现的 snoop 确定性断言；
- 本项目模块启用状态检查（见 2.2）；
- 系统进程 denylist；
- 输出目录和隐私门禁。

验收：不连接耳机也能生成完整 preflight 报告；任何关键项失败时不启动采集。

### M1：HCI 自动提取（已交付，2026-07-26）

交付：

- HCI 文件发现，支持本机 ROM 的轮转与时间戳文件名；
- 会话前后快照，按路径、大小和 inode 判定窗口内变化，能识别窗口中途新建的文件；
- Root 临时副本、chmod 仅作用于副本、pull、SHA-256 双端比对、删除该精确临时文件；
- btsnoop 结构校验与连接句柄映射（见 8.3）；
- TShark 初筛、时间窗裁剪和两级 target 产物。

验收：已达成。对一条真实 LE 连接（句柄 `0x0003`）生成了 61 帧的 target-only
pcapng，Wireshark 与 TShark 均可正常打开，协议层级解析为 59 帧 L2CAP、16 帧 ATT。

实现位于 `tools/bluetooth-capture/capture.ps1` 的 `hci-begin` / `hci-end` 模式，
结构校验位于 `analysis/inspect_btsnoop.py`。

### M2：通用 Frida transport collector（已交付，2026-07-26）

交付：

- Frida 17 TypeScript agent，经 frida-compile 打成单文件，Java bridge 显式导入；
- RFCOMM/GATT 通用只观察 Hook；
- 二进制 message channel；
- JSONL 事件流与原始 payload 分离落盘，按 offset 与 SHA-256 引用；
- attach/detach 健康检查与 payload 链路自检。

验收状态：

- **已达成**：Sony App（`com.sony.songpal.mdr` 13.0.8，Android 16/API 36）attach
  前后 PID 均为同一值，进程存活无 ANR/FATAL，agent 报告 rfcomm 与 gatt 两组 hook
  均安装成功；payload 链路自检字节级一致，覆盖 `0x00`/`0x80`/`0xFF` 等符号边界。
- **待硬件**：`同一 TX payload 能在 HCI 中找到` 需要目标耳机实际连接后才能验证。
  无连接设备时官方 App 不发起任何蓝牙传输，因此该项与 M3 同受配对阻塞。

实现要点见 9.1 与 9.2；实测确认的三项约束记录在 9.4。

### M3：OPPO Phase 0 fixture（已交付，2026-07-26）

交付：

- HeyMelody 版本签名检查（preflight 强制比对 16.7.1）；
- OPOv1 帧重组与内层解码（`analysis/oppo_frames.py`）；
- Frida 与 HCI 字节级关联（`analysis/correlate.py`）；
- 脱敏与命令清单（`analysis/sanitize.py`）；
- 首份 device-capture fixture 与 metadata 入库；
- `DeviceCaptureRegressionTest` 8 项 JVM 回归。

验收状态：**全部闭合**。共产出 7 份 device-capture fixture，覆盖冷启动握手、通知
订阅、电量（通知与主动查询两条路）、佩戴、ANC 三模式设置与状态通知与各选择符查询、
EQ 三预设、固件版本双来源、空间声开关、批量状态回读。关键帧均在 HCI 与 Frida 两条
独立链路上字节级一致。详细协议结论见 `PHASE0_OPPO_BASELINE.md`。

耳机取出佩戴后，ANC 与 EQ 已全部采到并入库为第二份 fixture
`encoair5s-anc-eq.hex`：ANC 三模式设置与响应、三种状态通知、EQ 三预设设置与响应及
查询响应，`DeviceCaptureAncEqTest` 7 项回归通过。写入路径确认与本项目实现字节一致。

仍缺固件版本、`0x0106` 电量查询与空间声开关行为。

以下为佩戴前的排查记录，保留以说明该前提条件：

`ui-drive.ps1` 已能按 resource-id 正确解析并点中 ANC 三档的可点击节点（四次切换全部
执行成功），但 HCI 侧 RFCOMM 帧数纹丝不动，App 一条命令都没发。同时 UI dump 显示三档
始终 `selected=false`。当时佩戴状态为 `(01,05) (02,05) (03,04)`，即两只耳机都不在佩戴
状态、盒子在位。

结论：**耳机需从充电盒取出并佩戴，ANC/EQ 控制才会真正下发命令。** 这是 3.2 划归用户
完成的物理动作，不是脚本可以绕过的。EQ 与固件版本入口也需在该状态下重新定位——本轮
在设备页未找到耳机固件版本，App 的"关于"页只有 App 自身版本 16.7.1。

冷启动握手必须用 `--spawn` 门控采集：握手在 App 连接后一秒内完成，attach 永远来不及。
spawn 会改变 PID，因此该模式与 M2 的 PID 稳定性验收互斥，是显式选项。

### M4：Sony 协议发现

交付：

- WH-1000XM4 和 LinkBuds S transport 证据；
- 稳定调用栈聚类；
- 第一版命令差分矩阵；
- 可重复的 ANC/EQ 场景材料。

验收：同一动作重复三次得到稳定候选帧，且反向恢复操作也得到可解释差分。

### M3 补充：白名单只读探针

被动抓包对官方 App 从不发送的命令天然无能为力——它只能证明"没人发过"，不能证明
"发了没用"。为区分这两者，`collector/collect.py` 提供 `--probe`：

- agent 端只接受**固定白名单内的只读查询帧**，其余一律拒绝，不含任何写命令；
- 帧内容与本项目生产代码逐字节相同，用于验证本项目自己的读路径；
- seq 固定 `0xF0`，远离官方 App 当时的 seq 区间，避免设备响应被 App 误当成对自己
  未决请求的答复。

这符合 10 节 `allowReadCommands = true` 的约定，且明确**不是** raw console。经此手段
查清了三项被动抓包无法判定的问题，结论见 `PHASE0_OPPO_BASELINE.md`。

### M5：白名单 UI 自动化（部分交付，2026-07-26）

`ui-drive.ps1` 已实现并在 ANC、EQ、空间音效场景中实际使用：按可见文本加 resource-id
从实时 UIAutomator dump 定位，坐标每次重新解析；标签不可点击时回退到同容器内可点击
节点；危险词永不点击，页面级危险流程标记触发整体停止；控件不唯一时按 13 节停止。

尚未实现的是场景文件驱动的自动编排与页面断言，目前仍由调用方按步骤传入动作列表。

交付：

- resource ID/text 驱动；
- 页面断言；
- 危险词 denylist；
- 截图和 UI hierarchy 审计。

验收：App 小版本、语言或布局变化时安全停止，不发生猜测点击。

## 15. Phase 0 完成标准

OPPO Enco Air 5s 只有同时满足以下条件，才能把真机部分标记为完成：

```text
[ ] 型号、固件、手机、Android、App 版本完整
[ ] 固件版本具备 UI 与协议双来源，非 ui-only
[ ] 采集窗口两端 sSnoopLogSettingAtEnable 均非 DISABLED
[ ] 证据来自完整 snoop，不含任何 btsnooz 环形缓冲内容
[ ] 采集期间本项目模块确认未启用
[ ] transport 和实际 UUID 已由 HCI 证明
[ ] 0x0100 capability 闭环
[ ] 通知能力与注册闭环
[ ] 电量查询与主动通知闭环
[ ] ANC set/response/readback 或 notify 闭环
[ ] EQ set/response/readback 或 notify 闭环
[ ] Frida inner/link 与 HCI payload 可关联
[ ] 原始材料位于仓库外
[ ] fixture 已脱敏并人工复核
[ ] fixture 进入 JVM 回归且测试通过
```

任何仅来自 DEX、白名单、UI 显示或单边 TX 的信息，都不能替代上述真机闭环。
