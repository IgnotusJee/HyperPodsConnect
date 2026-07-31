# 新厂商与新型号接入清单

本清单是 Phase 11 之后扩展兼容范围的硬边界。目标是让新增厂商只增加 protocol/driver、
组合根注册与资源，不修改通用领域状态机或 Android transport 的行为。

## 1. 证据先于实现

- 记录型号、固件、Android/ROM、官方 App 版本和 transport；
- 静态推导放入 `official-static`/`official-source`，真机捕获放入 `device-capture`，不得互相
  改标签；
- 排除设备唯一标识、账号、地址和其他敏感载荷；
- 每个写操作记录 SET 前读取、响应、readback、超时和恢复原值过程。

## 2. 模块边界

- 厂商帧、校验、命令表、parser、session 与 capability mapper 放在独立
  `:protocol:<vendor>` Kotlin/JVM module；
- 通用 `:core`、`:engine`、`:transport:android` 不得依赖任何厂商 protocol module 或
  `:app`；
- SPP/GATT 差异通过 transport profile 描述，不在 transport 中按厂商、型号或 UUID
  写分支；
- Android runtime 只在组合根注册 driver/provider，不解释协议字节。

## 3. 能力与兼容等级

- 名称、UUID 或静态表只能产生 `DETECTED`/assumed evidence；
- 完成只读握手和状态 fixture 后才能标为 `READ_ONLY`；
- 写能力必须满足门禁、ACK/业务响应分离、readback 和失败不污染 confirmed，才能标为
  `CONTROLLED`；
- 只有型号 + 固件 + transport + command table 精确命中且真机回归通过，才能进入
  `STABLE` 矩阵；未知固件必须降级，不能继承稳定等级。

## 4. 必需验证

- protocol module：帧 round-trip、任意分片/粘包、错误帧、fixture parser 与 command
  routing 单元测试；
- engine：generation 隔离、重连、operation phase、timeout 与 rollback 测试；
- app：snapshot/command IPC、capability-driven UI、release 危险操作门禁测试；
- 无设备阶段至少运行 protocol/core/engine/app 单元测试和 `:app:assembleRelease`；
- 真机阶段使用完整 APK 安装，重启 LSPosed scope 进程，再完成连接、只读状态、每项可逆
  控制、断线重连和跨进程 UI 一致性验收。
