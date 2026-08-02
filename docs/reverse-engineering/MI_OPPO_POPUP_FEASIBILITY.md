# 小米原生与 OPPO 官方耳机弹窗可行性分析

## 1. 分析范围

本轮对照了三组代码：

- Xiaomi 13 Pro / HyperOS 3.0.303.0 上的 `com.xiaomi.bluetooth` 版本 16；
- 同设备的 `com.milink.service` 版本 `17.2.0.1.2601081906`；
- OPPO HeyMelody `com.heytap.headset` 版本 16.7.1 的既有反编译材料。

小米 APK 通过 ADB 从设备只读拉取，使用 JADX 1.5.6 `--deobf` 反编译。APK、完整输出、JOBF
映射、hash 和逐文件源码地图位于本地忽略目录 `reference/mi/`；官方二进制与反编译源码不进入 Git。

## 2. 小米原生弹窗的实际链路

### 2.1 发现入口在 Xiaomi Bluetooth，不在 MiLink

`MiuiFastConnectService.handleScanCallBack()` 把 BLE `ScanResult` 交给
`handlFastConnectScanResult()`。后者读取完整广播、提取 vendor adv、校验设备 ID/SDP、更新当前与
peer 地址，并经过 RSSI、机型白名单、弹窗时间和连接状态门禁后进入
`checkAndStartConnecting()`。

`startProductActivity()` 最终构造 `MiuiFastConnectActivity` Intent，附带：

- `BluetoothDevice`；
- current/peer address；
- 解析后的 `headset_miui_data` 与原始 adv bytes；
- RSSI、layout type、当前 A2DP 数量和插件能力；
- 与 deviceId 对应的云资源/`resource_record.xml` 可用性。

Activity 使用 MIUI `PairingDialog` 展示，并写入全局“当前已有弹窗”状态防止重复显示。因此原生弹窗是
“广播协议 + 设备目录 + 资源 + 配对/连接状态机”的结果，不是一次简单的 `startActivity()`。

### 2.2 MiLink 是已连接耳机的状态与控制面

MiLink 的 `HeadsetInfo` 包含 address、name、deviceId、六项 power/mode/volume/type/switch/wired/
audio-effect 状态。`ProfileContext` 和 `AncBatteryController` 从 MxBluetooth SDK 取得 deviceId、电量、
ANC、空间音频并向 discovery/remote host 分发更新；`switchToHeadsetActivity()` 也只是委托给
`MxBluetoothManager`。

当前项目已经 Hook 了上述若干 getter/setter，并从版本化 snapshot 投影电量、ANC 和空间音频。
这能让 MiLink 把 Sony/OPPO 当作可控制耳机，但不会自动让 Xiaomi Bluetooth 的 fast-connect
状态机认为收到了合法的小米近场广播。

## 3. OPPO 官方弹窗的对应结构

HeyMelody 采用相同的职责分离思路：发现/重连事件进入 discovery action/view-model，随后显示
`DiscoveryDialogActivity`。Activity 根据 `AppConstant$DiscoverOpType` 处理 connecting、connected、
back-connect 和 error 状态，而不是由控制页直接弹窗。

图片资源独立于连接状态机：官方代码按 productId/colorId 选择
`popup_<productId>_<colorId>`，不存在时回退到
`popup_<productId>_<colorId>_normal`；设备档案还保存 `popTheme` 与
`reconnectPopupSwitch`。仓库当前 root 导入器已经能读取 HeyMelody 的
`melody-model-download/control_*/res/image/img_detail|left|right.png`，但仍需要用户手动挑选，
尚未把 product/color 与当前 profile 自动关联。

## 4. 可行性结论

| 路径 | 可行性 | 主要问题 | 建议 |
| --- | --- | --- | --- |
| 完善 MiLink `HeadsetInfo`/属性通知 | 高 | ROM API 会变化 | 先做，按版本签名失败关闭 |
| 连接后显示模块自有弹窗 | 高 | 需要系统级窗口/锁屏行为回归 | 作为首个可交付 |
| 直接启动 `MiuiFastConnectActivity` | 低 | 缺少合法 adv、peer、deviceId、云资源和内部状态 | 不作为正式方案 |
| 合成小米 BLE adv/`ScanResult` 注入 fast-connect | 中低、实验性 | 可能误触配对、污染 account key、影响第一方耳机 | 取得动态 trace 后再评估 |
| 完整复刻 HeyMelody discovery Activity | 中 | 资源授权、状态机复杂、跨 ROM 不一致 | 只参考状态转换与资源模型 |

推荐架构是“双通道”：

```text
BluetoothProcessRuntimeHost (唯一真实 session/snapshot)
        ├─ MiLink state adapter -> HeadsetInfo / ANC / spatial / switch activity
        └─ Popup coordinator -> module PopupActivity (首选)
                               -> native fast-connect adapter (实验、证据门禁)
```

这样既能获得接近第一方的系统体验，又不要求 Sony/OPPO 假装发出了它们实际上不会发送的小米 BLE
广播。后续若确有“开盖但尚未连接”弹窗需求，必须先采集目标耳机广播、第一方小米耳机完整触发 trace，
再决定是否做只对精确型号生效的转换层。

## 5. 推荐 Hook 点

### MiLink

- `ProfileContext.getDeviceId/getBatteryLevel/getAncState/getSwitchState`：继续按目标地址投影 snapshot；
- `HeadsetInfo` 生产/更新或 `DiscoveryHost` 的更新分发：补齐一次性完整状态通知；
- `AncBatteryController.setAncStateBlock` 与空间音频 setter：转换为 `FeatureCommand`；
- `switchToHeadsetActivity`：对目标设备跳转模块详情页/弹窗，其余设备调用原实现。

### Xiaomi Bluetooth

- 连接后路径优先观察 `MiuiBluetoothNotification.showConnectedToast` 及连接状态 handler，建立稳定的
  “一次连接边沿一次弹窗”触发器；
- `MiuiFastConnectService.handleScanCallBack/startProductActivity` 只用于日志与实验性取证，默认不改写；
- 所有 Hook 必须同时检查 package/process、目标地址、profile、generation 和 ROM 兼容签名。

## 6. 动态验证清单

1. 录制小米第一方耳机从开盖到 Activity 展示的 logcat、BLE adv、Intent extras 和资源读取；
2. 分别录制 Sony/OPPO 从开盖、A2DP connecting 到 snapshot Ready 的时间线；
3. 验证息屏、锁屏、横竖屏、多窗口、通话、连续开合盖和快速重连；
4. 验证第一方小米耳机与目标第三方耳机同时存在时互不抢占；
5. 验证 Hook 签名缺失、资源缺失和 MiLink 进程重启时安全降级；
6. 安装更新后按项目要求重启 `com.android.bluetooth`、`com.xiaomi.bluetooth`、
   `com.milink.service` 等所选 LSPosed scope，再进行真机结论判定。

## 7. 证据限制

- JADX 对 BluetoothExtension 的 8,732 个类报告 12 个方法错误，对 MiLink 的 16,684 个类报告
  61 个方法错误；关键 scan、Intent、Activity、HeadsetInfo 与 controller 路径均成功生成；
- 本文是静态可行性论证，尚未向系统进程注入新的弹窗 Hook，也没有把静态结论标记为真机闭环；
- Android 包中保留了历史包名字符串，实施时必须以运行时 classloader 枚举与 method signature 为准，
  不能只依赖反编译文件路径。
