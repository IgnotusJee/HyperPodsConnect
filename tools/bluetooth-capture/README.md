# 蓝牙采集工具链

实现 `docs/reverse-engineering/ROOTED_ANDROID_BLUETOOTH_CAPTURE_PLAN.md` 的执行侧。
当前已交付 **M0：环境和安全门禁**，后续里程碑（M1 HCI 提取、M2 Frida collector、
M3 OPPO fixture）尚未实现。

## 当前状态

| 里程碑 | 状态 |
| --- | --- |
| M0 环境和安全门禁 | 已交付，已在目标机实测通过 |
| M1 HCI 自动提取 | 已交付，已对一条真实 LE 连接完成验收 |
| M2 通用 Frida transport collector | 已交付并全部验收，含 Frida/HCI 字节级关联 |
| M3 OPPO Phase 0 fixture | 已交付，7 份 fixture 全部闭合，含白名单只读探针 |
| M4 Sony 协议发现 | 未开始 |
| M5 白名单 UI 自动化 | 部分交付：`ui-drive.ps1` 可用，场景文件驱动的编排未实现 |

`scenarios/` 下已备好 OPPO Enco Air 5s 的四个 Phase 0 场景。Sony 场景暂缺：其 UI 步骤
序列必须先做一轮实机观察，凭空写入等于猜测。

## 用法

```powershell
.\capture.ps1 -Mode preflight
```

带场景（会额外校验目标 App 是否安装、版本是否匹配静态样本、是否落在注入 denylist 上）：

```powershell
.\capture.ps1 -Mode preflight -Scenario oppo-air5s-anc -CreateOutputRoot -ReportPath .\preflight.json
```

退出码 0 表示全部关键项通过，非 0 表示禁止进入采集阶段。

### HCI 采集会话（M1）

```powershell
.\capture.ps1 -Mode hci-begin -SessionId 20260726-air5s-anc -Scenario oppo-air5s-anc
```

按场景提示完成操作后：

```powershell
.\capture.ps1 -Mode hci-end -SessionId 20260726-air5s-anc
```

`hci-end` 会自动定位窗口内变化的 snoop 文件、以 root 冻结副本后拉取并双端校验
SHA-256、做结构校验与连接句柄映射，最后产出：

```text
raw/<name>.log                      原始捕获（仓库外）
derived/<name>.log.inspect.json     结构校验与连接句柄映射
derived/<name>.protocol-hierarchy.txt
derived/<name>.rfcomm-att.json
derived/<name>.target-only.pcapng   仅已证明的连接句柄
derived/<name>.target-window.pcapng 句柄 + 会话时间窗
```

退出码 1 表示会话 incomplete（窗口内 snoop 被关闭），2 表示窗口内没有任何可证明的
连接，均不得产出 fixture。

单独检查一份已有捕获：

```powershell
python .\analysis\inspect_btsnoop.py <path-to-btsnoop.log>
```

### Frida transport collector（M2）

首次使用需构建 agent（Frida 17 的裸 GumJS 不再自带 Java bridge，必须 frida-compile
打成单文件）：

```powershell
cd agent; npm install; npm run build
```

目标 App 必须已在运行——collector 只 attach 不 spawn，spawn 会改变 PID 并丢掉操作员
刚建立的蓝牙会话：

```powershell
python .\collector\collect.py --package com.sony.songpal.mdr --session-id 20260726-sony --duration 120
```

产物写入同一会话目录，与 M1 的 HCI 材料并存：

```text
raw/frida-events.jsonl   事件流，payload 以 offset + SHA-256 引用
raw/frida-events.bin     原始 payload，按事件顺序拼接，字节级保真
```

对官方 App 从不发送的命令，被动抓包只能证明"没人发过"，不能证明"发了没用"。为此提供
白名单**只读**探针，用本项目生产代码构造的相同包主动发出：

```powershell
python .\collector\collect.py --package com.heytap.headset --session-id probe --duration 25 --probe battery --probe anc
```

这不是 raw console：agent 端只接受两条固定的只读查询帧，其余一律拒绝，且不含任何写
命令。seq 固定 `0xF0`，远离官方 App 当时的 seq 区间，避免设备的响应被 App 误当成对
自己某个未决请求的答复。

验证采集链路本身是否正常（构造一个纯数据对象触发 hook，不产生任何射频操作）：

```powershell
python .\collector\collect.py --package com.sony.songpal.mdr --session-id selftest --duration 5 --self-test
```

退出码非 0 表示进程未存活、PID 变化或自检失败，该会话不可用作证据。

冷启动握手在 App 连接后一秒内就完成，attach 永远来不及，必须 spawn 门控（会改变
PID，故为显式选项）：

```powershell
python .\collector\collect.py --package com.heytap.headset --session-id cold --duration 50 --spawn
```

### 分析与脱敏（M3）

按连接句柄把 Frida 事件与 HCI 做字节级关联：

```powershell
python .\analysis\correlate.py --session D:\HeadphoneCaptures\<id> --handles 6
```

匹配以 payload 字节为主键，时间只作旁证：偏移中位数应等于 btsnoop 时钟偏移，
离散度应在毫秒级。离散度大说明匹配是巧合，工具会告警。

重组 OPOv1 帧、输出命令清单并生成脱敏 fixture：

```powershell
python .\analysis\sanitize.py --session D:\HeadphoneCaptures\<id> --handles 6 --model EncoAir5s --firmware <fw> --scenario cold-init
```

含个人信息的帧会被**整帧剔除**而不是就地涂改——半脱敏的帧看起来仍像完整证据。
实测中多设备连接列表相关帧返回真实蓝牙设备名，属于此类。

产物落在会话的 `sanitized/`，复制进仓库仍是人工步骤，需人工复核。

主要参数：`-OutputRoot`（默认 `D:\HeadphoneCaptures`）、`-FridaHome`、`-Tshark`、
`-ExpectedFridaVersion`、`-FridaPort`、`-MinFreeGiB`。默认值对应当前工作机，换机时覆盖。

## preflight 只读保证

除 `-CreateOutputRoot` 创建输出根目录外，preflight 不写入任何状态。它不启用 HCI snoop、
不写 `persist.bluetooth.*`、不启停应用、不触碰系统日志，也不注入任何进程。

## 五个容易踩的实现陷阱

这三点是实测踩出来的，改动脚本时不要回退。

**root 命令必须一条一个 `su -c`。** adb 不会重新加引号，`adb shell su -c "a; b"` 会让
远端只把 `a` 交给 su，`b` 以 shell 用户执行。表现是莫名其妙的 permission denied，
`dmesg` 里 `scontext=u:r:shell:s0`。`Invoke-RootCommand` 会拒绝含 `;`、`&`、`|` 的命令。

**HCI 文件搜索用 `bt*snoo*`，不是 `*snoop*`。** snoop 关闭时系统仍在写
`/data/misc/bluetooth/logs/btsnooz_hci.log`（**z**），`*snoop*` 匹配不到。该文件带合法
`btsnoop\0` magic，TShark 能直接打开，极易被误当成真实抓包，实际是截断的环形缓冲。
脚本把它单独识别为 WARN 并明确标记不可用作 fixture。

**`persist.bluetooth.btsnoop*` 必须以 root 读。** 以 shell 用户读会静默返回空字符串，
看起来像"属性未设置"。脚本对空值报 WARN 而不是 PASS。

**HCI 分析必须先做连接句柄映射，不能直接按协议过滤。** HyperOS 把厂商 trace 以结构
完全合法的 ACL 记录写进 snoop 流，固定句柄 `0x0EDC`，且没有任何连接建立事件。TShark
会把它解析成大量伪 L2CAP CID 和乱码设备名，看起来像真数据。实测排除该句柄后，同一份
捕获的剩余帧立刻正确解析为 L2CAP 与 ATT。判据只有一个：句柄必须出现在 `0x03`、`0x2C`
或 LE Meta `0x01/0x0A` 建立的映射里。

**btsnoop 时间戳是本地时间，不是规范要求的 UTC。** 实测偏移 28800 秒。按真实墙钟构造
`frame.time` 过滤会静默匹配到零帧。偏移必须每份捕获实测（末帧时间减会话结束时的设备
时钟，向最近 900 秒取整），残差过大时跳过裁剪并告警。另外 `frame.time` 字面量按本地
时间解析，不要格式化成 UTC。

另有一点与协议无关但同样致命：本项目自己的 Xposed 模块会自动连接名称含 `oppo` 的设备，
若在采集期间启用，抓到的包里会混入我们自己发的流量，形成用自己的实现验证自己的循环证据。
preflight 强制检查该模块未安装。
