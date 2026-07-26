# 文档索引

本目录记录多品牌耳机重构的方案、逆向分析结论和真机证据。若你是第一次接触本项目，
按下面的顺序读。

## 从哪里开始

| 想知道 | 读这份 |
| --- | --- |
| 整体架构要往哪走、分几个阶段 | [architecture/MULTI_BRAND_REFACTOR_PLAN.md](architecture/MULTI_BRAND_REFACTOR_PLAN.md) |
| 当前 OPPO 实现的行为基线与真机验证结论 | [architecture/PHASE0_OPPO_BASELINE.md](architecture/PHASE0_OPPO_BASELINE.md) |
| 官方 App 协议是怎么逆出来的 | [reverse-engineering/OPPO_OFFICIAL_APP_DEXDUMP_ANALYSIS.md](reverse-engineering/OPPO_OFFICIAL_APP_DEXDUMP_ANALYSIS.md) |
| 怎么在真机上抓包取证 | [reverse-engineering/ROOTED_ANDROID_BLUETOOTH_CAPTURE_PLAN.md](reverse-engineering/ROOTED_ANDROID_BLUETOOTH_CAPTURE_PLAN.md) |
| 抓包工具怎么用 | [../tools/bluetooth-capture/README.md](../tools/bluetooth-capture/README.md) |

## 当前进度

Phase 0（建立基线与保护网）**已完成**。Phase 1（创建 `:core`）尚未开始。

抓包工具链的里程碑 M0 到 M3 已交付，M5 的 UI 自动化部分交付。M4（Sony 协议发现）
未开始。

## 两类证据不可混淆

这是本项目最重要的一条纪律，贯穿全部文档和测试：

| 类型 | 位置 | 含义 |
| --- | --- | --- |
| `official-source vector` | `app/src/test/resources/fixtures/oppo/official-source/` | 由官方 App 反编译结果静态推导，**不是**抓包 |
| `device-capture fixture` | `app/src/test/resources/fixtures/oppo/device-capture/` | 指定型号与固件的真机实测，附 `.metadata.md` 说明采集条件 |

文件名可直接区分：device-capture 一律带型号前缀。任何 fixture 都不得跨目录复制，
静态推导的向量永远不能改标签充作真机证据。

## Phase 0 得到了什么

对 OPPO Enco Air5s（固件 163.163.102、官方 App 16.7.1、Android 16）完成了完整的真机
证据链，7 份 device-capture fixture 覆盖：

- 冷启动握手：`0x0100` capability、`0x0200/0x0205` 通知订阅；
- 状态通知：`0x0204` 的电量、佩戴、ANC 三种报告类型；
- 写操作：ANC 三模式、EQ 三预设、空间声开关，及各自的响应；
- 读操作：电量查询、ANC 查询各选择符、固件版本、批量状态回读。

73 个单元测试中有 40 个直接跑在真机字节上。

### 由此确认的四项待改动

这些是 Phase 1 设计 `FeatureCapability` 与 `OperationPhase` 时的直接输入，每项都有
fixture 与回归测试钉住：

1. **ANC 查询保持用选择符 `01 01`**。官方 App 用 `02 03`/`02 04`，但实测这两个值在
   不同 ANC 模式下恒为 `06 00`，是静态值，照抄会实现出一个永远不变的"回读"。
2. **批量查询特征表移除 `0x1C`、补入 `0x37`**。设备对不支持的特征静默丢弃而非报错，
   这类问题只能靠逐项比对请求与应答发现。
3. **写入确认必须走 `0x010D` 回读**。所有 set 响应（`0x8403`/`0x8404`/`0x8406`）载荷
   都只有单字节状态、不回显新值，响应到达不等于新值生效。
4. **名称白名单的子串匹配需要收紧**。`OPPO Enco Air5` 规范化后是 `oppoencoair5s` 的
   子串，Air5s 因此继承了空间声开关能力。真机证明结论碰巧正确，但机制会在名称恰好
   延长了已列型号的无关设备上误判。

## 安全边界

抓包与探测遵循计划第 10 节的策略：默认只观察；只读命令允许主动发送，且限定在白名单内；
不提供任意字节控制台；禁止 OTA、恢复出厂、解除配对与设备发声等入口。原始抓包材料一律
留在仓库外，进入仓库的 fixture 必须脱敏并经人工复核。
