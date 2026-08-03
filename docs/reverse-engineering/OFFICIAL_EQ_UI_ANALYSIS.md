# Sony / OPPO 官方 EQ 界面静态分析

状态：静态源码证据已确认；HeyMelody / Air5s 与 Sound Connect / WH-1000XM4 真机界面均已交叉验证。

## OPPO Enco Air5s / HeyMelody

Air5s 的解密白名单条目位于
`reference/oppo-app-official/heymelody_app_whitelist.decrypted.json`，产品 ID 为 `06C810`。
其 `equalizerMode` 只声明三项：

| protocolIndex | modeType | 官方名称 |
| --- | --- | --- |
| 0 | 26 | 至臻原音 / Ultimate sound |
| 2 | 28 | 纯享人声 / Pure vocals |
| 1 | 29 | 澎湃低音 / Thundering bass |

`sources/p224qa/o.java` 把 mode type 26、28、29 分别映射到上述字符串资源。
因此不能沿用 OPPO 通用的五项静态列表，也不能把不属于 Air5s 的丹拿项显示出来。

官方二级页结构来自
`resources/res/xml/melody_ui_equalizer_preference.xml`：

- `key_equalizer_list`：推荐预设；
- `key_equalizer_list_custom`：用户自定义 EQ；
- `key_equalizer_add_custom`：添加均衡器。

`sources/p224qa/j.java` 的 `CustomEqFragment` 进一步确认：

- 推荐预设与自定义项属于两个独立 category；
- 编辑/多选删除模式只遍历自定义 category，推荐预设不可编辑；
- 添加操作在自定义数量达到 `customEqMax` 后隐藏；
- Air5s 白名单未覆盖 `customEqMax`，fragment 默认值是 3；
- 自定义名称可以修改，曲线修改以 action 2 写回。

2026-08-03 在已连接 Air5s 的 HeyMelody 真机页面进一步确认：

- “大师调音”二级页只显示上述三个推荐预设；
- 自定义区没有槽位时只显示“添加均衡器”；
- 点击添加会立即创建并选中“自定义1”，然后打开编辑底板；
- 新建模板是 `31 / 62 / 125 / 250 / 500 / 1k / 2k / 4k / 8k / 16k` 十频段；
- 顶部“编辑”只把自定义项切换成复选框，固定预设仍保持不可编辑。

界面证据：

- `reference/oppo-app-official/screenshots/air5s_equalizer_official.png`
- `reference/oppo-app-official/screenshots/air5s_add_equalizer_official.png`

测试创建的全 0 “自定义1”已通过官方删除流程移除，页面恢复为至臻原音且无自定义项。

## Sony Sound Connect

`resources/res/layout/mdr_second_layer_eq_detail_layout.xml` 是 EQ 二级区域：顶部图形与
`HorizontalTextSlider` 同时承担曲线预览和预设切换，不是详情首页上的下拉框。

`sources/com/sony/songpal/mdr/view/EqResourceMap.java` 给出了预设资源表。普通固定预设之后，
尾部依次出现：

- `CUSTOM`：Manual；
- `USER_SETTING1`：Custom 1；
- `USER_SETTING2`：Custom 2；
- 新协议还可以继续到 User Setting 5。

项目的 V1 capability 只把设备实际报告的 `Manual / Custom 1 / Custom 2` 与
`writableSlotIds` 相交。`SonyV1EqualizerFeature.setCurve` 又要求写入槽与当前活动槽完全一致，
所以固定预设只能切换，只有尾部手动/自定义槽可进入曲线编辑。

官方源码还补充了一个 UI 与写入语义之间的细节：

- `sources/p370nn/C24350n0.java` 的 `m94758Ue()` 只根据设备是否支持自定义 EQ
  （`mo60285c()`）控制“编辑”按钮可见性，不按当前预设过滤；
- 同类的 `m94768ef()` 会从任意当前预设打开 `C24345l` 编辑页；
- `C24345l.m94728uf()` 在滑杆真正发生变化后，取得当前预设和完整频段数组再提交；
- 因此固定预设可以作为编辑器的初始曲线，但不是被原位覆盖的可写槽。

2026-08-03 在 WH-1000XM4（固件 2.5.1）的 Sound Connect 13.2.1 真机页面确认：

- 二级页用左右箭头切换预设，并同时展示 `400 / 1k / 2.5k / 6.3k / 16k`
  五段曲线和独立的 Clear Bass；
- 当前“低音增强”读回为五段全 0、Clear Bass `+7`；
- 固定预设页也显示“编辑”。进入后仍以“低音增强”作为标题和初始曲线；
- 第一次把 400 Hz 从 0 改为 +1 时，标题立即从“低音增强”切换为“手动”；
- 将该频段恢复为 0 并退出编辑后，“手动”保留五段全 0、Clear Bass `+7`，再次向左切换两项可回到未被修改的“低音增强”。

这说明 Sony 的准确行为是“固定预设可作为编辑基线，首个修改自动落入尾部 Manual 槽”，
而不是直接修改固定预设。项目当前把曲线编辑入口限制在 capability 声明的尾部可写槽，
保留了协议层明确槽位和可恢复性；若后续要逐像素复刻官方入口，应把固定预设的“编辑”实现为
显式克隆到 Manual，而不能把固定预设加入 `writableSlotIds`。

界面证据：

- `reference/sony/screenshots/wh1000xm4_equalizer_official.png`
- `reference/sony/screenshots/wh1000xm4_equalizer_edit_official.png`
- `reference/sony/screenshots/wh1000xm4_equalizer_edit_manual_official.png`

临时频段改动已归零，官方页恢复为“低音增强”；释放 Sound Connect 的 Tandem SPP 会话后，
HyperPods 再次读回 `Bass Boost`，连接状态为 `Ready / STABLE`。

## 项目统一模型

详情首页只保留“均衡器 + 当前模式”入口。二级页由同一个 capability 驱动：

- 公共层处理预设选中、当前值和曲线编辑器；
- OPPO 策略拆分“推荐 / 自定义”，Air5s 使用官方三预设并允许最多三个自定义项；
- Sony 策略保持 capability 顺序，仅对 `writableSlotIds` 中的尾部项显示编辑器；已验证的
  六值 V1 布局按 `CLEAR BASS / 400 / 1k / 2.5k / 6.3k / 16k` 映射，并采用五个竖向频段加
  独立 Clear Bass 的官方结构；
- 其他品牌使用同一个基础列表，不套用 Air5s 的名称或分区规则。

## 真机证据边界

两组官方 App 截图均与 `android layout` 的可见层级交叉核对。截图只证明当前设备、固件和
App 版本的可见行为；可写槽集合及协议载荷仍以 capability、反编译调用链和 SET/GET 实机读回
共同约束，不能只根据按钮是否可见推断固定预设可被直接覆盖。
