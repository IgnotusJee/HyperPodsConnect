# Phase 10：Sony 可逆控制

状态：**已闭环；NC/ASM 三态、环境声 level、NORMAL/VOICE 与全部 EQ preset 均已完成**
日期：2026-07-31

## 本次范围

本阶段开放 Sony NCASM 参数 `0x17` 的三个已验证状态、仅在环境声模式下可见的
离散 level `1..20` 与 NORMAL/VOICE（UI 为“通透/人声增强”），以及全部 12 个
官方 EQ preset：

| UI 状态 | `enabled` | `ambient` |
| --- | ---: | ---: |
| 关闭 | `0x00` | 保留当前值；设备读回归一为 `0x00` |
| 降噪 | `0x01` | `0x00` |
| 环境声 | `0x01` | `0x01` |

level 与人声增强控件只在当前 confirmed 模式为环境声且对应能力 writable 时显示。
智能、轻度、中度、深度降噪、EQ band 写入、FOTA、关机、恢复出厂、配对管理、查找设备
和 raw 命令均明确不在 Phase 10 范围内，继续关闭。

## 证据链

静态参考来自 Sony 官方 App 逆向材料
`reference/reverse-engineering-reference/`。动态证据来自 Sony Sound Connect 13.0.8
与 13.2.0、LinkBuds S 4.2.1 的 BLE GATT 会话：

- `GET_PARAM 0x66/0x17` 对应 `RET_PARAM 0x67/0x17`；
- `SET_PARAM 0x68/0x17` 在官方 App 会话中对应独立 Tandem ACK 与
  `NTFY_PARAM 0x69/0x17`；
- 关闭、降噪和环境声均重复观察，且每次操作后恢复到初始关闭状态；
- 官方 App 动作会话 64/64 个 Frida GATT 事件与 HCI 字节级匹配，冷启动会话
  234/234 个匹配，均为零 unmatched。

脱敏帧与条件记录位于
`testdata/fixtures/sony/device-capture/linkbuds-s-4.2.1/`。原始 Frida、HCI、截图和
UI hierarchy 只保留在 Git 不跟踪的位置；本轮使用项目内被 `.gitignore` 保护的
`.codex_tmp/`。

## 写入门禁

只有同时满足下列条件，profile 才从 `READ_ONLY` 升级为 `CONTROLLED`：

- model 精确等于 `LinkBuds S`；
- firmware 精确等于 `4.2.1`；
- transport 为 `BLE_GATT`；
- 协议为 Tandem v2，table 1 可用；
- support-function 包含 `0x17FF`；
- 初始 `0x66/0x17` 读取成功且布局严格合法。

任何条件缺失、未知固件、SPP、畸形响应或初始读取失败都会保持只读。模式写命令只
接受 `OFF`、`NOISE_CANCELLATION`、`TRANSPARENCY`；level 写命令只接受 `1..20`，
NORMAL/VOICE 写命令只接受两个已观察子模式，两者都要求当前 confirmed 模式为环境声。
其他值在协议层拒绝，UI 也严格按 profile `allowedValues` 渲染。

## 操作事务

每次设置遵循同一事务：

1. 必须已有 confirmed NCASM 状态；
2. 基于 confirmed 状态构建 SET；模式切换保留 level 和 ambient sub-mode，level
   调整保留环境声模式和 ambient sub-mode，NORMAL/VOICE 切换只改变 sub-mode 并保留
   level；
3. 发送 SET，等待独立 Tandem ACK；
4. 给业务 NTFY 一个有界 400 ms 宽限期；
5. 无论是否收到 NTFY，都发送显式 GET；
6. 只有 RET 与目标模式、level 或 ambient sub-mode 一致，且未修改字段保持一致，才
   提交 confirmed；
7. timeout、断线、畸形响应或读回不一致均回滚 pending，不修改 confirmed。

官方 App 会话会收到 `0x69/0x17`。项目 App 的早期模式切换会话只收到 ACK，没有业务
NTFY；本次 level 会话则稳定收到 NTFY。实现仍将 NTFY 作为可选的快速确认，将显式
GET/RET 作为不可省略的最终确认，避免把 Tandem ACK 误当成业务成功。

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

### 环境声 level 项目 App 闭环

2026-07-31 在同一 Xiaomi 13 Pro 与 LinkBuds S 4.2.1 上完成项目 App 验证：

- 使用 `:app:installDebug` 完整安装模块，重启 Bluetooth 作用域后确认“模块已激活 /
  模块服务已连接”；
- 修复平台缓存的 Sony SPP UUID 抢占问题后，精确 `LinkBuds S` 明确选择
  `BLE_GATT`，完整握手进入 `Ready / CONTROLLED`；
- 初始显式 GET 读回环境声、normal 子模式、level 10；
- 相邻等级执行 `10 -> 11 -> 10`，两次均收到 Tandem ACK、`NTFY 0x69/0x17`，
  随后由 `GET 0x66/0x17` / `RET 0x67/0x17` 完成最终确认；
- 边界等级执行 `10 -> 1 -> 20 -> 10`，三次均收到相同的 ACK、NTFY、GET/RET
  完整事务；
- 最终 UI 与后续周期 GET 均保持环境声、normal 子模式、level 10；
- 全程同一 generation 保持 `Ready / CONTROLLED`，无 timeout、decode reject、
  session failure 或相关 FATAL。

两轮 HCI 原始文件均在项目临时目录完成冻结、拉取和双端 SHA-256 校验，不进入 Git。
结构检查均为零 truncated、零 tail truncation、零 timestamp regression；相邻与边界
会话分别生成 140 帧和 84 帧 target-window，HyperOS 伪 ACL 句柄 `0x0EDC` 已排除。

## 自动化验证

新增或扩展的 JVM 覆盖包括：

- NCASM GET/RET/SET/NTFY 精确布局与 checksum；
- level `1..20` builder/parser、normal/voice 子模式保留与 transient
  `UNDER_CHANGING` 拒绝确认；
- 畸形长度和非法字段拒绝；
- 型号、固件、transport、协议表、support-function 白名单 miss；
- SET 前必须读取；
- 模式与 level 的 pending、ACK、NTFY、GET readback 和恢复原值；
- 缺少 NTFY 时回退到显式 GET；
- timeout/失败不污染 confirmed；
- UI 根据 `allowedValues` 隐藏未验证控制值。

当前已完成：

- 本轮 Sony 协议、App 与 Android transport 共 184 个 Kotlin/JUnit 测试全部通过
  （36 + 109 + 39），另有 core 24 个测试通过；
- Gradle `:protocol:sony:test`、`:app:testDebugUnitTest` 与
  `:transport:android:testDebugUnitTest` 通过；
- `:app:compileDebugAndroidTestKotlin` 完成（当前无 androidTest 源码）；
- Debug lint、assemble 与 `:app:installDebug` 通过；
- 增加“LinkBuds S 在 GATT 可用时不被缓存 SPP UUID 抢占”的回归测试；
- 脱敏 fixture 离线校验与 `git diff --check` 通过。

## Phase 10 结论

Phase 10 已整体完成。已交付的每项能力均具备独立官方 App 动态证据、精确 profile
白名单、SET 前读取、独立 ACK、强制 GET/RET 读回、失败回滚、项目 App 真机验证和
原值恢复。未纳入本阶段的智能/多档降噪、EQ band 编辑、FOTA、关机、恢复出厂、配对
管理、查找设备与 raw 命令不是遗留项，仍保持 fail closed。后续工作进入 Phase 11。

## 环境声 level：官方 App 动态证据

2026-07-31 使用 Sony Sound Connect 13.2.0 与同一 LinkBuds S 4.2.1 完成边界
会话。初始状态为环境声、normal 子模式、level 10；操作序列为
`10 -> 1 -> 20 -> 10`。

结果：

- 官方 UI 的闭区间为 1..20；
- `0x17` 参数在 parameter id 之后依次为
  `ValueChangeStatus / enabled / NCASM mode / ambient sub-mode / level`；
- 拖动过程发送 `ValueChangeStatus=0x00 (UNDER_CHANGING)`，落点提交发送
  `0x01 (CHANGED)`；
- level 1、20 和恢复后的 10 均收到独立 Tandem ACK 与值完全一致的
  `NTFY_PARAM 0x69/0x17`；
- 最终通知为环境声、normal 子模式、level 10，原值恢复成功；
- HCI 结构检查为 8,931 records、零 truncated、零 tail truncation、零 timestamp
  regression；重连后的真实句柄 `0x0004/0x0005` 与 HyperOS 伪 ACL
  `0x0EDC` 已严格区分。

脱敏证据位于
`testdata/fixtures/sony/device-capture/linkbuds-s-4.2.1/linkbuds-s-ambient-level.*`。
基于该证据，严格 parser/builder、`1..20` 范围门禁、ambient sub-mode 保留、强制
GET readback、超时回滚、能力门禁、IPC/UI 链路和自动化测试已经实现。level 写控件
仍受精确 profile 白名单约束；Gradle/App 构建、完整安装、相邻值、边界值与最终恢复
均已通过，因此环境声 level 切片标记为闭环。

## EQ preset：官方 App 动态证据

2026-07-31 使用 Sony Sound Connect 13.2.0 与同一 LinkBuds S 4.2.1 完成全部
官方预设的可逆会话。项目 LSPosed 模块在采集前停用，Bluetooth 作用域随后重启；
官方 App 成为唯一 Sony 控制会话。初始预设为“低音增强”，自动遍历顺序为：
`演说 -> 手动 -> 自定义 1 -> 自定义 2 -> 关闭 -> 欢快 -> 激昂 -> 醇美 -> 放松 ->
原声 -> 高音增强 -> 低音增强`。

结果：

- 初始化使用 EQEBB `GET_PARAM 0x56 00`，`RET_PARAM 0x57` 严格布局为
  `<type=00> <preset> <count=06> <six encoded band values>`；
- 12 个 preset id 与官方标签一一对应：`00 Off`、`10 Bright`、`11 Excited`、
  `12 Mellow`、`13 Relaxed`、`14 Vocal`、`15 Treble Boost`、`16 Bass Boost`、
  `17 Speech`、`A0 Manual`、`A1 Custom 1`、`A2 Custom 2`；
- 写命令严格为 `SET_PARAM 0x58 00 <preset> 00`；
- 12 次写入均收到独立 Tandem ACK，以及 preset 与目标完全一致、包含六段值的
  `NTFY_PARAM 0x59`；重连后的显式 GET/RET 也确认当时的 `Vocal (0x14)`；
- 最终通知为 `0x16`，与官方 UI 的“低音增强”原值一致；
- HCI 原始文件为 1,392,927 bytes / 8,601 records，零 truncated、零 tail
  truncation；跨累计文件与重连观察到 3 次 timestamp regression，不影响帧完整性或
  SET/ACK/NTFY 关联。target-window 为 1,661 帧，HyperOS 伪 ACL `0x0EDC` 已排除。

脱敏证据位于
`testdata/fixtures/sony/device-capture/linkbuds-s-4.2.1/linkbuds-s-eq-preset.*`。
基于该证据，当前实现按上述固定集合开放全部 12 个官方 preset；未知 preset、非法
六段布局和 band 值越界继续 fail closed。`Manual`、`Custom 1/2` 仅切换到耳机内已有
预设，不开放 band 编辑。实现仍强制在 SET/ACK/可选 NTFY 后发送 `GET_PARAM`，只有
`RET_PARAM` 的 preset 与目标一致且布局严格合法才提交 confirmed。

### EQ preset 项目 App 闭环

同日使用 `:app:installDebug` 完整安装实现，重新启用模块并重启 Bluetooth 作用域后，
项目 App 显示“模块已激活 / 模块服务已连接”，LinkBuds S 会话使用 BLE GATT 完成握手。
验证中同时修复了 LE Audio 成员被解析到当前组主设备后，物理 GATT 地址与逻辑会话
身份被错误等同比较的问题；修复后仍以用户选择的成员作为 session identity，仅将组
主设备作为物理 GATT endpoint，并有独立 transport 回归测试保护。

- 初始 `GET_PARAM 0x56 00` / `RET_PARAM 0x57` 读回 `0x16`，UI 显示
  `Bass Boost`；
- 通过 UI 自动化遍历全部 12 个 preset。HCI 中出现完整 id 集合
  `00,10,11,12,13,14,15,16,17,A0,A1,A2`，共 13 个 SET（`0x16` 包含中途一次及
  最终恢复一次）；
- 每个 id 都有目标一致的 `NTFY_PARAM 0x59` 和随后强制 GET 得到的
  `RET_PARAM 0x57`，六段值也与官方 App 动态证据一致；
- 最终 UI 显示 `Bass Boost` 与“设备已确认更改”，原值恢复成功；
- 项目 HCI 原始文件为 746,262 bytes / 4,548 records，target-window 433 帧；冻结、
  拉取、双端 SHA-256 与结构检查通过，零 truncated、零 tail truncation。累计日志有
  1 次 timestamp regression，真实 LE 句柄 `0x0002/0x0003` 与伪 ACL `0x0EDC`
  已严格区分；
- Gradle `:transport:android:testDebugUnitTest`、`:protocol:sony:test`、
  `:app:testDebugUnitTest`、`:app:assembleDebug` 与 `:app:installDebug` 全部通过。

因此全部官方 EQ preset 切换已闭环。写能力仍仅对 LinkBuds S 4.2.1、BLE GATT、
Tandem v2 table 1 且初始 EQ 严格读取成功的 profile 开放；其他固件、传输、未知
preset、畸形 band 布局与任何 band 编辑继续 fail closed。

## 环境声 NORMAL/VOICE：官方与项目 App 闭环

2026-07-31 使用 Sony Sound Connect 13.2.0 在环境声、level 20、NORMAL 基线上完成
三轮 `NORMAL -> VOICE -> NORMAL`。官方帧确认 NCASM `0x17` 中 ambient sub-mode
`0x00` 为 NORMAL、`0x01` 为 VOICE；写入分别为
`68 17 01 01 01 00 14` 与 `68 17 01 01 01 01 14`。六次 SET 均收到独立 Tandem
ACK 和值完全匹配的 `NTFY 0x69/0x17`，其他字段不变，最终恢复 NORMAL 与 level 20。
官方 HCI 为 904,224 bytes / 5,793 records，零 truncated、零 tail truncation、零
timestamp regression。

随后通过 `:app:installDebug` 完整安装项目模块，重新启用 LSPosed 并重启 Bluetooth、
Bluetooth Extension 与设备互联作用域。Sony Sound Connect 保持 force-stopped，项目
App 经 BLE GATT 完整握手进入 `Ready / CONTROLLED`：

- UI 仅在通透模式展示“通透 / 人声增强”二态控件，初始为通透、level 20；
- 自动执行三轮 `NORMAL -> VOICE -> NORMAL`，六次写入的 payload 与官方证据完全一致；
- 每次 SET 都收到独立 Tandem ACK；设备未发送业务 NTFY，实现在 400 ms 宽限期后强制
  发送 `GET 66 17`，六次均由 `RET 67 17` 精确确认目标 sub-mode 与保留的 level 20；
- 最终 UI 为通透、level 20、人声增强关闭，无失败或超时；
- 项目 HCI 为 840,725 bytes / 5,315 records，target-window 130 帧，零 truncated、
  零 tail truncation；累计日志因连接重建有 3 次 timestamp regression，不影响完整
  SET/ACK/GET/RET 关联，HyperOS 伪 ACL `0x0EDC` 已排除。

脱敏 fixture 与条件记录位于
`testdata/fixtures/sony/device-capture/linkbuds-s-4.2.1/linkbuds-s-ambient-voice-mode.*`。
实现复用跨品牌 `TRANSPARENCY_VOCAL_ENHANCEMENT` 状态、命令、IPC 与 UI 链路；Sony
profile 仍受 LinkBuds S 4.2.1 / BLE GATT / Tandem v2 table 1 / `0x17FF` / 初始严格
读取的同一精确门禁约束。
