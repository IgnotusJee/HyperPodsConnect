# HyperOS 3 ROM API 逆向基线（2026-08-04）

## 设备与样本

基线设备为 Xiaomi 13 Pro（2210132C）、Android 16、HyperOS
`OS3.0.303.0.WMBCNXM`。APK 由设备直接拉取，使用 JADX 1.5.6 反编译。APK 和 JADX 输出位于忽略的
`build/rom-analysis-20260804/`，不进入仓库。

| APK | 包/版本 | SHA-256 |
| --- | --- | --- |
| MiLinkOS3Cn.apk | `com.milink.service` 17.2.0.1.2601081906 (170020001) | `FB4C45401D7F4C23658B294402795C136F9F6DFCE81F2F70F24F5C33DAB18D04` |
| BluetoothExtension.apk | `com.xiaomi.bluetooth` 16 (36) | `6191EDAE9E6D41D8028836D42CB36AF676DA26BA8C0CA0BD28C9C9DC9AFE988C` |
| Bluetooth.apk | Android Bluetooth 16 (36) | `8B55AB96912FDFC985D974E5BE5DC8FB00D8C40A0673C9D7A8983DF03C597E45` |
| Settings.apk | `com.android.settings` 16 (36) | `3D7CADAE00611C4CCD586BD9ADB4FC234CDDC19CEE10A7093C1495E5D8314FFE` |
| MiuiSystemUI.apk | SystemUI 16.03.251211.r (202501210) | `2909668F7718806720DA7DE71FDCD5C84FA2B4801FA09FFD076629F674662A38` |

## 已验证契约

- MiLink 空间音频是二值音效接口：
  `ProfileContext.getAudioSpatialEffectState(BluetoothDevice)` 与
  `setAudioEffectState(String,int)`；底层 `VolumeController` 只接受 `-1/0/1`。
- MiLink 详情入口为 `ProfileContext.switchToHeadsetActivity(BluetoothDevice)`。卡片能力使用
  `HeadSetsDetail` 的公开 visible setter 和稳定资源 ID；只读卡片保留显示并禁用交互。
- 官方岛最终处理入口为
  `MiuiBluetoothNotification.handleShowConnectedToast(int,int,int,int,BluetoothDevice,String)`；主动请求使用
  `MiuiBluetoothNotificationApi.addConnectManager(...)` 和 `showNewConnectedToast(...)`。
- 蓝牙 Binder 从 `BluetoothHeadsetService.onBind(Intent)` 的真实返回对象发现，不绑定混淆类名。
  当前混淆别名仅包括 `B`、`O0`、`z1`、`m0`，并始终优先匹配语义方法名和完整签名。
- Settings Proxy 的 `getDeviceInfo` 和 `isSupportAudioSwitch` 均接收一个 `String address`。
- SystemUI 接受 `status_bar_strong_toast`、`island_param` 和 `strong_toast_action`；当前无需 SystemUI Hook。

## 已废弃假设

当前 APK 不存在旧实现使用的 Mx spatial/run-info、AncBattery spatial/model callback、旧官方岛请求类字段，
也不应使用预置 Binder 混淆类名或 Stub `onTransact` 猜测。兼容层按功能组解析上述契约；缺失时只关闭对应
组并输出一次 `HYPEROS_CONTRACT ... status=DISABLED`，其余 ROM 行为保持原样。

MiLink、Settings 和蓝牙消费者只使用版本化 snapshot 的进程内状态，不再创建 Hook 私有状态偏好文件。
Hook 只安装到 Bluetooth、Xiaomi Bluetooth、Settings 主进程以及 MiLink `:core`/`:ui`。

## 实机回归

完整 `installDebug` 安装并重新启用静态作用域后，实机日志确认：Xiaomi Bluetooth 中的
`bluetooth-binder`、`official-island`，Settings 中的 `settings-headset`，MiLink `:core` 中的
`milink-core`/`milink-spatial` 以及 MiLink `:ui` 中的 `milink-ui` 均为 `ACTIVE`。AOSP Bluetooth
主进程不包含 Xiaomi 扩展类，因此对应两组只输出一次 `DISABLED` 并交还 ROM；没有缺失 API、Hook
私有 SharedPreferences 解锁或异常刷屏。

Air5s 在作用域重启后重新进入 `Ready/CONTROLLED`，固件为 `163.163.102`。Settings 耳机详情页和
MiLink 详情卡均显示真实名称、左右耳 100% 电量、未知盒电量和驱动允许的通透/降噪/关闭模式。
官方岛在本次 Ready 上只桥接一次，最终参数为左右耳 100%、佩戴值 0、展示类型 `01010607`。

Air5s 的 `SET_EQ` 应答会直接回显新预设；这不是独立回读。session 现在即使已从该应答观察到目标值，
仍继续发送 `QUERY_EQ`，最终以同一 requestId 返回 `READ_BACK_CONFIRMED`。内置 EQ 切换、低延迟开关、
空间音效开关以及临时十段自定义 EQ 的创建、单频点修改、重命名和删除均通过可逆 instrumentation，
每次都收到设备回读后恢复原值。当前实机把 `SPATIAL_AUDIO` 上报为可读不可写，把
`SPATIAL_SOUND_SWITCH` 上报为可读可写；MiLink 因此显示前者但不拦截 ROM 写入。ANC 能力和三种合法模式
投影已验证。在左右耳均报告 `WEARING` 后，可逆写入完成 `TRANSPARENCY → OFF → TRANSPARENCY`，两次终态
均保持外层 Intent 与 operation requestId 一致并返回 `READ_BACK_CONFIRMED`。同次佩戴过程的官方岛投影按
设备实际序列 `wear=0 → 3 → 1` 各发送一次，最终左右耳均为佩戴状态；后续 ANC 和低延迟状态变化没有重复
触发官方岛。

注意：`connectedDebugAndroidTest` 结束时会卸载本项目的全新应用身份，从而清除 LSPosed 激活记录。
硬件测试结束后必须再次执行 `:app:installDebug`，从 LSPosed 的“模块尚未激活”通知进入模块详情，重新
启用四个静态作用域，再重启五个实际进程；不能把 instrumentation 完成时的安装态作为最终部署态。
