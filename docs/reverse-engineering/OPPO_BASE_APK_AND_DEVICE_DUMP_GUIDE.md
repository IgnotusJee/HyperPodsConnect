# OPPO 官方 App 完整 APK 与设备取证指南

## 1. 结论

对 `reference/oppo-app-official/base.apk`（`com.heytap.headset` 16.7.1，versionCode
116007001）解包和 JADX 反编译后，可以补足此前只有 DEX 时缺少的 Manifest、资源和内置产品白名单。

最重要的新结论是：

1. APK 内置白名单确实存在于 `res/raw/heymelody_app_whitelist.json`，文件扩展名虽为
   JSON，内容实际是 `12-byte nonce + AES-256-GCM ciphertext/tag`，解密后再做 GZIP
   解压才是 JSON。
2. 内置白名单的 AES 密钥是 APK 首个签名证书 DER 编码的 SHA-256。它可以完全离线
   解密，不需要真机，也不需要抓包。
3. 已解出的内置兼容白名单有 81 个产品：51 个 OPPO、30 个 OnePlus；62 个使用
   `0000079A-D102-11E1-9B23-00025B00A5A5`，19 个使用
   `00001107-D102-11E1-9B23-00025B00A5A5`。全部声明 `supportSpp=true`。
4. 白名单包含 productId、名称/别名、品牌、产品类型、Classic UUID、RSSI 参数、最低
   App 版本、功能开关、ANC/EQ protocolIndex、固件门槛和部分 UI/兼容参数。
5. 网络更新的白名单不是用 APK 证书密钥。App 每次安装随机生成 32 字节密钥，Base64
   存放在 `melody-model-settings.xml` 的 `whitelistSecretKey` 中，并把密文保存在
   `files/melody-model-whitelist/encrypted.gz`。
6. App 内有可直接返回完整白名单 JSON 的 ContentProvider，但三个 provider 都是
   `exported=false`。普通 `adb shell content query` 无权访问；root、App 进程内 hook 或
   调试注入可以访问。

因此，Phase 0 的流式解帧、固定短帧兼容和离线测试不再依赖抓包；但动态 capability、
实际初始化顺序、通知事件、写命令响应状态和不同固件行为仍必须用真机材料确认。

## 2. 已离线还原的白名单

### 2.1 文件与解密工具

- 加密资源：
  `reference/oppo-app-official/base-apk-jadx/resources/res/raw/heymelody_app_whitelist.json`
- 解密工具：`reference/oppo-app-official/decrypt_whitelist.py`
- 已解密结果：
  `reference/oppo-app-official/heymelody_app_whitelist.decrypted.json`

使用方式：

```powershell
python reference\oppo-app-official\decrypt_whitelist.py `
  reference\oppo-app-official\base-apk-jadx\resources\res\raw\heymelody_app_whitelist.json `
  reference\oppo-app-official\heymelody_app_whitelist.decrypted.json `
  --apk reference\oppo-app-official\base.apk
```

如果系统的 `python` 没有安装 `cryptography`，需使用带该依赖的 Python 环境。

### 2.2 当前项目已硬编码型号的交叉验证

| 型号 | productId | UUID | 白名单中的关键事实 |
| --- | --- | --- | --- |
| OPPO Enco X3 | `067410` | `0000079A...` | EQ=4、自定义 EQ、空间类型 0/1/2、强降噪实时能力；EQ protocolIndex 0/1/2/3/7 |
| OPPO Enco Free4 | `068C10` | `0000079A...` | 自适应/强降噪、空间类型 0/1、ANC protocolIndex 含 11；EQ 0/1/2 |
| OPPO Enco Free4（丹拿版） | `06C010` | `0000079A...` | 与 Free4 的 ANC 映射一致，额外 EQ protocolIndex 3 |
| OPPO Enco Air5 | `06D810` | `0000079A...` | 强降噪、空间类型 0/1、EQ=4、白噪声、双设备连接 |
| OPPO Enco Air2 Pro | `063810` | `0000079A...` | T2，ANC protocolIndex 0/1/2/3 对应 modeType 5/1/2/6 |

这证明当前项目的型号分支并非完全没有官方依据，但仍不应把产品白名单当作设备实时能力。
官方实现会在连接后用 `0x0100` 返回的 capability 再次收敛可用命令。

## 3. APK 之后仍然缺少什么

| 信息 | APK/白名单能否给出 | 是否仍需真机 |
| --- | --- | --- |
| 型号、productId、Classic UUID、产品类型 | 能 | 只需核对目标设备实际匹配结果 |
| ANC/EQ 产品默认映射与版本门槛 | 能 | 固件是否覆盖/改变仍需验证 |
| 命令号、构包和大部分解析规则 | 能 | 写操作语义仍需响应证据 |
| 远端 `0x0100` capability 原始位图/command set | 不能，这是耳机运行时返回 | 必须 |
| `0x0200` 通知 event IDs 和实际 `0x0204` payload | 只能知道解析代码 | 必须 |
| 官方 App 对某个型号发出的真实初始化命令序列 | 只能静态推导 | 建议抓取 |
| set 响应状态、readback、超时和固件差异 | 不能 | 必须 |
| 目标耳机实际走 Classic 还是 SPP-over-GATT | 白名单仅给候选配置 | 必须 |
| 服务器最新白名单 | APK 只有内置 fallback | 从已联网运行的 App 获取 |

## 4. 设备侧材料：优先级与获取方法

### 4.1 第一优先级：Bluetooth HCI snoop

它是最能补齐协议缺口的材料，可以确认真实 transport、连接顺序、SDP/GATT 服务发现、
RFCOMM/GATT 上的完整收发字节、seq、响应状态、通知和时序。

无需 root 的常见方法：

1. 在开发者选项中启用“蓝牙 HCI 信息收集日志/Bluetooth HCI snoop log”。
2. 关闭再打开蓝牙，清理 App 后从“未连接”开始复现一次。
3. 依次记录：官方 App 建连、进入设备页、切一次 ANC、切一次 EQ、断开。
4. 生成 bugreport：

```powershell
adb bugreport heymelody-bugreport.zip
tar -tf heymelody-bugreport.zip | Select-String -Pattern 'btsnoop|btsnooz|bluetooth'
```

不同 Android/OEM 会把文件命名为 `btsnoop_hci.log` 或 `btsnooz_hci.log`，在 bugreport
中的目录也可能不同。有 root 时还可检查：

```powershell
adb shell su -c 'ls -la /data/misc/bluetooth/logs /data/misc/bluedroid 2>/dev/null'
```

建议每次只执行一个动作并记下时间点，后续在 Wireshark 中按目标设备地址、连接 handle、
RFCOMM channel 或 GATT characteristic 过滤。

### 4.2 第二优先级：服务器最新白名单

在已经联网运行过官方 App 的设备上，优先获取这两个文件：

```text
/data/user/0/com.heytap.headset/files/melody-model-whitelist/encrypted.gz
/data/user/0/com.heytap.headset/shared_prefs/melody-model-settings.xml
```

该 APK 没有 `android:debuggable=true`，同时 `allowBackup=false`，所以普通生产设备上的
`run-as com.heytap.headset` 和 adb backup 基本不可用；通常需要 root 或 App 进程内 hook。

root 设备可先复制到仅用于传输的临时目录：

```powershell
adb shell su -c 'cp /data/user/0/com.heytap.headset/files/melody-model-whitelist/encrypted.gz /data/local/tmp/heymelody-encrypted.gz && chmod 0644 /data/local/tmp/heymelody-encrypted.gz'
adb shell su -c 'cp /data/user/0/com.heytap.headset/shared_prefs/melody-model-settings.xml /data/local/tmp/melody-model-settings.xml && chmod 0644 /data/local/tmp/melody-model-settings.xml'
adb pull /data/local/tmp/heymelody-encrypted.gz
adb pull /data/local/tmp/melody-model-settings.xml
adb shell su -c 'rm /data/local/tmp/heymelody-encrypted.gz /data/local/tmp/melody-model-settings.xml'
```

然后用同一工具解密：

```powershell
python reference\oppo-app-official\decrypt_whitelist.py `
  heymelody-encrypted.gz heymelody-server-whitelist.json `
  --prefs-xml melody-model-settings.xml
```

也可尝试 root 下直接查询 App 内部 provider：

```powershell
adb shell su -c 'content query --uri content://com.heytap.headset.alive.WhitelistProvider/whitelist_content'
adb shell su -c 'content query --uri content://com.heytap.headset.alive.WhitelistProvider/find_whitelist?productId=068C10&deviceName=OPPO%20Enco%20Free4'
```

`content` 输出可能转义或截断大 JSON，所以“密文 + preferences key”通常更可靠。

### 4.3 第三优先级：已安装 APK 与 split APK

先确认设备实际运行版本是否与当前 `base.apk` 一致，并拉取全部 split：

```powershell
adb shell dumpsys package com.heytap.headset | Select-String -Pattern 'versionName|versionCode|codePath|split'
adb shell pm path com.heytap.headset
```

对 `pm path` 返回的每个 `package:/...apk` 路径分别执行 `adb pull`。这能排除当前样本与
真机版本不一致，也能补齐可能放在 feature/config split 中的代码和资源。

### 4.4 第四优先级：App 数据库和状态文件

Room 数据库名已由源码确认是：

```text
/data/user/0/com.heytap.headset/databases/melody-model.db
```

同时获取存在的 `melody-model.db-wal` 和 `melody-model.db-shm`，否则最近记录可能只在 WAL
中。数据库可帮助确认目标设备的 productId、名称、MAC 对应关系、历史连接设备和临时
白名单，但它不是协议字节的替代品。

root 下可复制：

```powershell
adb shell su -c 'cp /data/user/0/com.heytap.headset/databases/melody-model.db* /data/local/tmp/ && chmod 0644 /data/local/tmp/melody-model.db*'
adb pull /data/local/tmp/melody-model.db
adb pull /data/local/tmp/melody-model.db-wal
adb pull /data/local/tmp/melody-model.db-shm
```

某些设备没有 WAL/SHM，单个 `adb pull` 报不存在可以忽略。获取前最好强制停止 App，避免
数据库复制过程中三个文件不一致。

### 4.5 无 root 也有价值的状态和日志

```powershell
adb shell dumpsys bluetooth_manager > bluetooth-manager.txt
adb shell dumpsys activity services com.heytap.headset > heymelody-services.txt
adb shell dumpsys package com.heytap.headset > heymelody-package.txt
adb logcat -c
# 在手机上完成一次“启动官方 App -> 连接 -> 切换一个功能 -> 断开”
adb logcat -d -v threadtime > heymelody-logcat.txt
```

这些材料可确认 App/服务生命周期、连接异常、超时、设备 UUID 缓存和部分命令分派，但
release 版本日志不保证包含原始 payload，因此优先级低于 HCI snoop。

### 4.6 root 蓝牙配置：只做定向摘取

常见位置：

```text
/data/misc/bluedroid/bt_config.conf
/data/misc/bluetooth/bt_config.conf
```

它可帮助确认已配对设备名称、地址、缓存的服务 UUID 和 profile。不要提交或分享整个
文件：其中可能包含 Classic LinkKey、LE LTK/IRK 等配对密钥。只摘取目标设备段，并删除
`LinkKey`、`LE_KEY_*`、`LTK`、`IRK`、账号标识和完整 MAC。

### 4.7 App 进程内 hook：无 HCI 文件时的替代方案

如果设备可以使用 LSPosed/Frida，最有价值的 hook 点是：

- `HeadsetCoreService.u0(address, p195o8.a)`：发送前的内层 command/seq/payload；
- `HeadsetCoreService.j0(address, p195o8.a)`：收到并解析后的内层包；
- `E8.b.c(p195o8.a, byte[])`：外层 OPOv1 构帧；
- `F8.b.e(byte[])`：输入流切帧；
- `MelodyAliveProvider.query(...)` 或 whitelist repository 的 `h()/g()/c()`：完整运行时白名单。

这种方式能得到已经去掉 RFCOMM/GATT 外壳的协议包，分析效率通常高于 HCI；缺点是它
无法单独证明底层实际 transport、SDP/GATT 建连和链路分片边界，最好与 HCI 配合。

## 5. 建议一次最小采集集

针对每个需要支持的目标耳机，最小且足够有价值的一组材料是：

1. 型号、固件版本、手机型号、Android 版本、官方 App 版本；
2. 解密后的服务器最新白名单中该 productId 的单条配置；
3. 一次从未连接到 Ready 的 HCI snoop；
4. 原始 `0x0100/0x8100`、`0x0200/0x8200`、通知注册和第一轮 `0x010D/0x810D`；
5. ANC、EQ 各做一次 set，并保留响应与 readback；
6. `dumpsys bluetooth_manager` 中目标设备的脱敏 UUID/profile 信息。

其中 3～5 可以由 HCI snoop或进程内 hook取得。只要这组材料齐全，就足以把目前仍然是
“官方静态代码推断”的部分升级为可用于回归测试的真机证据。

## 6. 隐私和提交要求

- MAC 地址至少脱敏后三个字节，并对同一设备保持一致映射；
- 删除账号 token、手机号、邮箱、精确位置、设备 SN；
- 不分享任何蓝牙 LinkKey/LTK/IRK；
- bugreport 和完整 App 数据目录不应直接提交仓库；
- 建议只保留目标连接的 HCI 片段、单条白名单配置和必要日志；
- 真机原始帧作为 fixture 时，应另附型号、固件、transport、UUID 和采集动作说明。
