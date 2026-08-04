# 通用耳机超级岛与焦点通知

## 1. 范围

本功能位于 Phase 15 与 Phase 16 之间，消费蓝牙进程唯一 session 发布的统一 snapshot，不增加新的
蓝牙连接，也不在 SystemUI 或小米蓝牙进程解释厂商协议。这里的“通用”指已经由 driver 识别、完成
协议握手并允许公开状态的设备；未知普通蓝牙设备继续使用系统原有通知，不被模块接管。

焦点通知对所有上述设备生效。模块超级岛在设置中选择“模块”后，按连接、佩戴、摘下和放回盒中的
既有时机配置生效。“官方”模式在设备 Ready 或真实佩戴状态变化时，把统一 snapshot 桥接到小米蓝牙
服务自己的 `showConnectedToast` 链路；因此 OPPO、Sony 以及后续 driver 支持的设备不再依赖型号自身能否
触发小米入口。未知设备和未完成协议握手的设备不会进入桥接。

## 2. 统一数据链

```text
HeadphoneSessionManager snapshot
  -> Android system integration projection
  +-> topology-aware BatteryParams
  |   -> com.xiaomi.bluetooth notification renderer
  |   -> HyperOS Focus Notification / module Super Island
  +-> official two-slot projection
      -> MiuiBluetoothNotification.showConnectedToast
      -> updateParameters / invokeStatusBar
      -> HyperOS official Super Island
```

投影携带设备名称、拓扑、单电池/左/右/盒电量和 `NOISE_CONTROL` 可写能力。通知不按 OPPO、Sony
名称或型号分支；厂商仅决定上游 driver 如何产生同一领域状态。

## 3. 拓扑与能力规则

| snapshot 状态 | 焦点通知 | 超级岛 |
| --- | --- | --- |
| `SINGLE` / HEADBAND / NECKBAND | 显示“电量”，AOD 使用 `B <level>%` | 单设备图片与单电量槽；有盒状态时盒作为第二槽 |
| LEFT + RIGHT | 分别显示左右耳，AOD 使用 `L/R` | 左右两个槽 |
| 单侧耳机 + CASE | 只显示实际连接侧和盒 | 实际耳机与盒两个槽 |
| CASE 为第三状态 | 通知正文保留盒电量 | 左右耳齐全时岛优先左右耳，不挤入第三槽 |

降噪循环按钮仅在 profile 对 `NOISE_CONTROL` 给出 `isWritable=true` 时创建；广播处理端再次检查同一
capability，不能仅靠构造 Intent 越过门禁。断开按钮与通知点击入口保持通用。

## 4. 图片回退

- 常驻焦点通知：用户 BOX > 官方 BOX > 官方 DETAIL > LEFT/RIGHT > 内置通用图；
- 左右耳超级岛：对应侧图片 > BOX > DETAIL > 内置对应侧图；
- 单电池超级岛：BOX > DETAIL > LEFT/RIGHT > 内置通用图。

因此只有一张官方 DETAIL 图的 Sony 头戴式设备与 LinkBuds S 也会复用 Phase 15 已验证缓存，不再因
缺少 OPPO 式 box/left/right 三图而放弃显示。

## 5. 回归边界

- 纯 Kotlin 投影测试覆盖 TWS 三电量、单电池头戴式、单侧 + 盒、AOD 文本及控制能力；
- profile 名称、拓扑或 capability 晚于电量完成验证时，通知必须刷新，不能保留早期占位状态；
- 主 session 失败或无有效电量时不创建模块通知/超级岛；断开时按精确设备地址取消通知；
- OPPO 与 Sony 使用相同通知 renderer，新增厂商只需产生通用 snapshot。

## 6. 官方超级岛桥接边界

- 桥接前同时要求 `CONNECTED/Ready`、非空 `deviceId`、精确地址匹配和至少一个有效电量，不能靠设备名
  白名单把普通蓝牙设备伪装成受支持耳机；
- 连接 Ready 时显示一次，之后仅真实佩戴状态变化时再次触发，普通电量刷新不会反复弹岛；
- HyperOS 官方佩戴值固定为：未佩戴 `0`、双耳 `1`、左耳 `2`、右耳 `3`、不支持 `4`；没有佩戴
  capability 的设备保留“不支持”，不会根据左右电量猜测佩戴；
- 官方 UI 只有两个电量槽。单电池设备使用左槽并以 `255` 隐藏右槽；TWS 使用左右槽，盒电量仍由模块
  焦点通知完整表达；
- 第六个参数保持空字符串，使用 ROM 内置的官方通用资源，不伪造小米云端 `deviceId`。因此官方视觉由
  当前 HyperOS ROM 决定，设备专属图片仍以模块超级岛/焦点通知路径最完整；
- 小米原生耳机和非目标设备的既有官方路径不被吞掉；只有本 App 当前统一 session 对应的精确地址会被
  snapshot 数据修正。
