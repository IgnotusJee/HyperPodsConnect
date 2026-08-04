# Phase 16：MiLink 状态桥与耳机弹窗

## 1. 当前状态

Phase 16 已进入实现阶段，按 16A → 16B → 16C 顺序推进。2026-08-04 的代码基线完成了
16A 的静态实现、JVM/架构回归和安装/作用域重启冒烟，并完成 16B 的代码与 JVM 门禁；目标耳机与
小米第一方设备的跨设备矩阵尚未完成，因此 16A/16B 均标记为 **待真机闭环**，Phase 16 也不标记为完成。

Phase 15 与 Phase 16 之间已经交付的通用焦点通知、模块超级岛和小米官方已连接岛桥接仍属于前置
增量。它们复用统一 snapshot，但不能替代本阶段的 MiLink 详情入口、模块连接弹窗和真机门禁。

## 2. 16A：MiLink 状态完整化

### 已实现

- `ProfileContext`、`AncBatteryController` 与 MxBluetooth SDK 的身份、电量、ANC 读取继续只对当前
  snapshot 的精确地址生效；空间音频按实机 APK 的二值
  `getAudioSpatialEffectState(BluetoothDevice)` / `setAudioEffectState(String,int)` 契约接入；
- `HeadsetInfo` 的十参数构造结果会投影 snapshot 的 `deviceId`、六项电量、ANC、设备类型、开关和
  音效状态。这里修改值对象的 backing fields，而不只 Hook getter，保证 Parcelable/JSON 跨 host
  分发也读取同一状态；地址、名称、音量和 wired state 保留 ROM 原值；
- snapshot 电量、ANC 或空间音频变化会分别触发 MiLink 的 4/8/9 属性通知，不建立第二条蓝牙连接；
- `ProfileContext.switchToHeadsetActivity(BluetoothDevice)` 对精确目标设备打开模块 `PopupActivity`。
  该入口使用强制模块弹窗标志，不受“通知点击去向”配置影响；
- 模块 Activity 启动失败时不吞掉 Hook，继续执行 ROM 原路径。小米第一方耳机、普通蓝牙设备和非当前
  地址从不进入模块分支；
- ANC 与空间音频写操作仍转换为版本化 `FeatureCommand`，最终显示值由 snapshot readback 覆盖。

### 自动回归

- `Phase5ArchitectureTest` 固定了构造值对象投影、精确地址门禁、模块详情入口和 ROM 回退；
- `MiLinkDevicePresentationTest` 固定 TWS/单电池类型、capability 驱动卡片与 ANC 映射；
- 2026-08-04 运行全量 `:app:testDebugUnitTest`、`:app:assembleDebug` 与 `git diff --check`，均成功。

### 2026-08-04 真机冒烟

- 已用 `:app:installDebug` 向 Xiaomi 13 Pro / Android 16 安装完整 APK；
- 已重启 Bluetooth、Xiaomi Bluetooth 和 MiLink scope，三个主进程均以新 PID 恢复；MiLink 主进程中
  未出现 `HeadsetInfo` 构造 Hook 或 `switchToHeadsetActivity` 签名缺失日志；
- 用显式强制模块弹窗 Intent 验证 `PopupActivity` 可冷启动并成为 top resumed Activity；
- 本次未连接目标耳机，因此尚不能把 getter/backing-field 投影、MiLink 卡片点击以及控制 readback 标记为
  真机通过，下面的设备矩阵仍是 16A 闭环条件。

### 2026-08-04 LinkBuds S 在线验证

- 系统 LE Audio 组的两个成员均进入 `CONNECTED`；模块控制会话以 generation 1、Sony `BLE_GATT`、
  `CONTROLLED` 进入 `Ready`，识别型号 LinkBuds S、固件 4.2.1，电量为左右耳 100%、盒子 83%；
- MiLink 注入进程保存的 snapshot 投影同样为左右耳 100%、盒子 83%、TWS、ANC off，证明版本化广播已
  到达 MiLink 状态桥。MiLink 设备中心在模块会话 Ready 前曾先用系统 SDK 建立 `[100,100,100]` 缓存；
  Ready 后 provider 受签名权限保护，当前卡片最终渲染值与详情点击仍需屏幕人工确认，不能据此关闭
  16A；
- 仅重启 `com.milink.service` 后主 PID 从 1372 更新为 6560，Bluetooth PID 保持 1287，LinkBuds S 的
  LE Audio 连接和模块 Ready 会话均未中断；不包含目标类的 MiLink 子进程按设计记录 Hook skip，未出现
  FATAL/ANR。

### 真机闭环门禁

- 使用 `:app:installDebug` 完整安装 APK，并重启 `com.android.bluetooth`、
  `com.xiaomi.bluetooth`、`com.milink.service` 和本次实际选择的其他 LSPosed scope；
- 分别用 OPPO Enco Air5s 与 Sony WH-1000XM4/LinkBuds S 验证 MiLink 卡片名称、电量、ANC、空间音频、
  设备类型和详情入口；
- ANC/空间音频至少各执行一次最小可逆修改，日志必须到达 `DEVICE_ACCEPTED` 和
  `READ_BACK_CONFIRMED`，随后恢复原值；
- 同时连接或切换到小米第一方耳机与普通蓝牙设备，确认原 `switchToHeadsetActivity` 未被吞掉；
- 人为制造模块 Activity 不可用或单个契约组签名缺失场景，确认仅该组输出一次 `DISABLED`，MiLink
  进程存活且 ROM 原路径生效。

### 2026-08-04 ROM 契约校正

本轮从当前手机重新拉取 MiLink、BluetoothExtension、Bluetooth、Settings 与 MiuiSystemUI APK，并以
反编译结果替换了此前“缺失 API 可在任意 MiLink 子进程逐项跳过”的结论。Hook 现在只进入 MiLink
`:core`/`:ui`，使用独立契约组失败关闭；不存在的 spatial/model callback、旧官方岛请求字段、旧 Binder
类名和 Hook 私有状态偏好文件均已删除。详细版本、哈希和签名见
[HyperOS 3 ROM API 逆向基线](../reverse-engineering/HYPEROS3_ROM_API_ANALYSIS.md)。

## 3. 16B：已连接模块弹窗

### 已实现

自动连接边沿弹窗由蓝牙进程的统一 snapshot 适配层触发，独立 coordinator 同时要求：

- A2DP 或 LE Audio 已连接；
- session 为 `Ready` 且具有精确 `deviceId`、地址和至少一项有效电量；
- 当前 generation 首次进入 Ready，且地址十秒时间去重门禁通过；尝试在启动 Activity 前即被消费，
  Android 拒绝后台启动时不会形成重试循环；
- 用户已解锁、屏幕可交互且当前不在通话/通信音频模式；锁屏可交互时 Activity 使用
  `setShowWhenLocked(true)`，但不会主动点亮屏幕；
- 图片只使用同一 snapshot 对应的用户图片、Phase 15 官方缓存或内置回退。

模块设置新增“连接弹窗”开关，默认开启。自动入口携带预期 generation、emitted-at、deviceId 和地址；
`PopupActivity` 仅在 IPC 返回的已连接 snapshot 同时匹配这些身份/时序门禁时显示，两秒内未匹配就
直接结束，不展示旧 host 或旧设备状态。启动被
系统限制时沿用已存在的焦点通知入口，不循环重试、反复点亮屏幕或借用 `MiuiFastConnectActivity`
伪造配对流程。

### 自动回归与剩余门禁

- `ConnectedPopupCoordinatorTest` 覆盖启用开关、传输连接、Ready、身份、电量、generation 和时间去重；
- `Phase5ArchitectureTest` 固定触发层、精确 snapshot Activity 门禁和无重试降级约束；
- 2026-08-04 已再次通过 `:app:testDebugUnitTest`、`:app:assembleDebug` 与 `git diff --check`，并用
  `:app:installDebug` 完整安装；Bluetooth/Xiaomi Bluetooth/MiLink scope PID 均已更新且未出现新增
  Hook/FATAL 日志；不匹配的自动 Intent 会在两秒门禁后结束，手动强制入口仍可成为 top resumed；
- LinkBuds S 的解锁、亮屏、竖屏首次连接场景已通过：引擎在 15:52:26.788 进入 Ready，Bluetooth UID
  在 15:52:26.810 以系统允许的 allowlisted-component 路径启动 `PopupActivity`；Activity 成为可见前台
  任务，随后正常关闭。后续同 generation 多次 Ready snapshot 未再次启动 Activity；焦点通知同时显示
  相同的左右耳 100%、盒子 83%；
- Sony 的快速重连/横屏/锁屏/通话场景，以及 OPPO 全套场景仍需继续执行，完成后 16B 才能关闭。

## 4. 16C：原生近场实验

保持证据门禁，当前不实现。只有取得 Sony/OPPO 实际 BLE 广播以及小米第一方耳机从 scan 到
`MiuiFastConnectActivity` 的完整动态 trace 后，才评估精确型号的受控 `ScanResult` 适配。不得写入未知
account key、伪造配对成功或全局改写 `checkIsMiTWS`。
