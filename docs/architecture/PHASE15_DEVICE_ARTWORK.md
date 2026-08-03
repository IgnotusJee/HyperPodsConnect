# Phase 15：官方设备图片自动解析与缓存

## 1. 当前状态

2026-08-03 已完成 **15A 静态实现、OPPO Enco Air5s 官方缓存取证与真机显示回归**，并完成
**15B 高清详情资源与动画资源升级**，并完成 **15C Sony 本地官方资源索引静态实现**：

- App 连接到 OPPO 设备后，只按 HeyMelody `melody_equipment` 中唯一的精确设备记录解析图片；
- 图片读取优先级固定为：用户自定义图 > 已验证官方缓存图 > APK 内置通用图；
- 官方图与蓝牙 session 解耦，使用 schema 2 的 `DeviceArtworkDescriptor` 记录厂商、产品、配色、
  型号、固件和每张图的 MIME、尺寸、字节数及 SHA-256；
- 缓存文件使用设备地址摘要与内容 hash 组成的内容寻址名称，不把明文地址写进新文件名。所有资源
  完成校验和原子发布后才更新偏好，失败继续使用旧缓存；
- Android App 数据隔离场景下，数据库与图片均优先从 `/data_mirror/data_ce/null/0` 读取，再回退
  `/data/data`、`/data/user` 和 DE 路径；
- 资源角色不再固定要求 box/left/right 三图，改为按官方配置声明的可选集合，支持仅 detail、single、
  headband 等拓扑安全降级；
- 详情页优先级为：用户自定义图 > 官方 `modelWebp` 动画 > 官方 `detailImageRes` 高清图 >
  box/left/right 拓扑回退 > APK 内置图；通知、超级岛仍使用各自 box/left/right 角色；
- `capsuleVideoRes` 独立缓存为胶囊动画，不会被误放大为详情页主图；
- HyperOS 系统耳机设置页通过 `MiuiHeadsetAnimation.loadDefaultInternal()` 的窄范围 Hook 替换
  `R.id.tic`，使用用户静态图 > 官方 `detailImageRes` > box/left/right 的顺序；动态 WebP 不注入
  系统静态 `ImageView`；
- Sony Sound Connect 13.2.1 的 GraphQL 型号表、本地 SharedPreferences 和图片缓存链路已取得
  独立证据并接入：只读取型号、颜色、SCA 图片 URL、色源及 URL→缓存文件映射，不复制账号字段，
  也不由模块发起网络请求。

Air5s 详情页、图片配置、通知与超级岛均已真机显示官方缓存图，外部进程也已通过同一
ContentProvider 逐字节读取三张图片。Sony 静态解析、单元测试和 WH-1000XM4 真机回归已完成；
LinkBuds S 仍需连接后完成模块内显示和 HyperOS 设置页回归，因此 Phase 15 尚未闭环。

## 2. Air5s 官方 App 证据

测试设备上的 HeyMelody 私有 Room 数据库表定义包含：

```text
melody_equipment(macAddress, productId, colorId, name, ...)
```

当前 Air5s 的唯一记录为：

```text
productId=06C810, colorId=1, name=OPPO Enco Air5s
```

`macAddress` 在官方数据库内为哈希值；模块不复制该字段，也不把官方 App 的账号或认证信息写入
缓存索引。精确资源目录及 `config.json` 声明为：

```text
control_06C810_1/res/image/img_detail.png
control_06C810_1/res/image/img_box.png
control_06C810_1/res/image/img_left.png
control_06C810_1/res/image/img_right.png
control_06C810_1/res/video/capsule.webp
fetch14_06C810_1/res/raw/detail_model.webp
```

真机只读取证据：

| 资源 | 尺寸 | 字节数 | SHA-256 |
| --- | ---: | ---: | --- |
| detail | 780×780 | 296,409 | `22ca02412345ca108b2fb3c7a206b738040fd01b4f877f00dc97d47df99d620c` |
| detail model WebP | 780×780 | 4,739,932 | `ecfc26d7941e737cbec938de727b6b47c8a62d461cb5a09047e0758d6332544d` |
| box | 216×216 | 28,675 | `0e9b0b40b984f24ffb5526b428285b3f21153b253b0ff0549b6d0f2406b29868` |
| left | 408×660 | 90,131 | `e0e0edfb642cede9b8f02243c3d2613f48c459165f42d7cefdbe07688c100fec` |
| right | 408×660 | 90,287 | `c7585d7cc4d30a0c35a4417505095ce65f0f28f904c58869c2abe8362679eee9` |
| capsule WebP | 148×148 | 391,714 | `8290bbd9b8fb2777f4073e1a9703d1d8d15e2c529f1c14f0fa6a5e115e74b321` |

官方配置的 `leftImageRes` 与 `rightImageRes` 分别映射到 left/right，不再沿用旧手动导入中的交叉
映射。

## 3. Sony Sound Connect 官方 App 证据

Sound Connect 13.2.1 每日从生产 GraphQL 端点取得型号表，保存到
`cloud_model_info_preference.xml` 的 `MODEL_INFO_LIST_JSON`。当前 schema 的图片字段为
`sca_image_image_url` 和 `sca_anime_image_url`。图片由官方 `DownloadedFileManager` 写入
`files/modelimage`：`CachedUrlList` 保存 CDN URL，实际文件名是 URL UTF-8 字节的 SHA-1。

设备上的只读取证确认：

| 型号/色源 | 官方颜色 ID | URL SHA-1 文件 | 资源 |
| --- | --- | --- | --- |
| WH-1000XM4 / `FF494948` | `0x00`, `0x01`（共用视觉资源） | `8dddcc4924069ced14e7fb59bae94a82e4909476` | PNG 720×720 / 86,669 bytes |
| LinkBuds S / `FFAAB8D8` | `0x05` | `70fd86b90cebda991778abddffaa5c8553d4b762` | PNG 720×720 / 95,301 bytes |

当前设备色源来自官方 `jp.co.sony.autoplay.android.preferences.xml`，先按蓝牙地址和完整型号匹配。
云型号行再按完整型号、色源以及本地确实存在的缓存 URL 收敛。WH-1000XM4 的 `0x00/0x01`
虽然色号不唯一，但二者 URL、色源、model id 和 model number 完全相同，因此作为一个视觉资源组
保存两个色号；若候选对应不同图片 URL，则拒绝自动选择。

Sony 当前目标资源均为单张 `DETAIL`，不伪造耳塞的 LEFT/RIGHT/BOX 拓扑图。若将来某型号的
`sca_anime_image_url` 同时存在于官方本地缓存，则独立映射为 `HERO_ANIMATION`。所有资源继续经过
统一的格式、尺寸、大小、解码与 SHA-256 校验后原子发布。

## 4. 解析与回退规则

1. vendor 为 `oppo` 或 `sony` 且 snapshot 已 Ready 时尝试各自的严格解析器；
2. 优先用当前设备地址绑定查询官方数据库；地址无结果时回退完整 `deviceName`，结果必须只有一个
   不同的 `productId/colorId/model` tuple；
3. 产品 ID、颜色 ID、目录和图片路径分别通过白名单正则，不接受路径片段；
4. 从 `control_<productId>_<colorId>/config.json` 解析 detail/box/left/right/capsule 的实际相对路径，
   从 `fetch14_<productId>_<colorId>/config.json` 解析 `modelWebp`；不再假定固定文件名；
5. 资源角色可按拓扑缺省，但配置已声明的全部资源必须同时可读并通过格式、8 MiB 大小、4096 边长、
   16M 像素和 SHA-256 校验；
6. 任一步失败均不修改偏好，继续读取旧官方缓存或内置图；
7. 手动“导入图片”入口改为同一精确解析器的缓存刷新入口，不再列出所有 `control_*` 供模糊手选。

## 5. 验证记录

- `:app:compileDebugKotlin`：通过；
- `:app:testDebugUnitTest`：通过；
- `DeviceArtworkCacheDeviceTest` 源码编译通过，新增按拓扑发布单一 detail 的覆盖；本轮真机运行器在
  安装测试 APK 后无结果回传并超时，未将其记为通过；
- `:app:installDebug`：完整 APK 安装成功；
- 安装后已重启 `com.android.bluetooth`、`com.milink.service`、`com.xiaomi.bluetooth` 作用域进程；
- Air5s 真机 snapshot：`Ready / STABLE`，型号 `OPPO Enco Air5s`，固件 `163.163.102`；
- 自动生成 schema 2 descriptor：`vendorId=oppo`、`productId=06C810`、`colorId=1`，六个语义资源完整；
- 真机缓存确认 `HERO_ANIMATION=780×780/4,739,932 bytes`、`DETAIL=780×780/296,409 bytes`，
  `BOX=216×216` 继续保留给通知与缩略图；
- 私有缓存三文件的字节数和 SHA-256 与 HeyMelody 原图完全一致；
- 详情页显示官方盒图，图片配置界面的盒/左/右三项均标记“官方设备图片（自动匹配）”；
- 以模块外 shell 身份读取三个 `content://moe.chenxy.oppopods.podimages/...` URI，所得 SHA-256
  仍与官方源一致，验证 Hook、通知和超级岛共用的跨进程图片通道可用；
- 重连 Air5s 后，系统通知实际显示官方盒图、左右电量与控制按钮；临时切换模块超级岛模式后，
  超级岛实际显示官方左右耳图和 `100% / 100%`，验证完已恢复原“官方”模式与空显示时机；
- 当前 ROM 的 Settings APK 已提取到 `reference/mi/apk/Settings.apk` 并由 JADX 反编译到
  `reference/mi/jadx/Settings`；逆向确认默认分支会异步回写 `bt_headset_find_detail`，因此 Hook 在
  原加载入口前拦截并精确匹配 Fragment 根 View；
- Air5s 系统设置页真机日志记录 `Settings hero replaced ... bitmap=780x780`，截图视觉核验显示
  OPPO 官方高清盒图且未被后续 MIUI 刷新覆盖；
- 单元测试覆盖 PNG 元数据、超限/未知格式拒绝、用户图优先级和官方设备记录严格解析。
- Sony 单元测试覆盖 SharedPreferences XML 转义、按地址读取色源、GraphQL 型号过滤、共用视觉
  色号折叠、不同 URL 歧义拒绝以及官方 URL SHA-1 文件名。
- WH-1000XM4 真机安装使用完整 `:app:installDebug`，随后重启 `com.android.bluetooth`、
  `com.android.settings`、`com.milink.service` 和 `com.xiaomi.bluetooth` 四个 LSPosed 作用域；
- WH-1000XM4 重连后 snapshot 为 `Ready / STABLE / Classic SPP / 2.5.1`，电量 90%，验证期间无
  Sony frame decode reject；
- 自动生成 Sony schema 2 descriptor：`productId=0x31:0x05`、`colorId=0x00,0x01`、
  `DETAIL=image/png 720×720 / 86,669 bytes`；
- Sound Connect 原图、模块原子缓存和非 root shell 通过 ContentProvider 读取所得 SHA-256 均为
  `9e3bcf848f400fb0f1fb6e8ab610611604ef055fdf5b98e5aab2c613d314d936`；
- HyperOS 设置页真机日志确认 `Settings hero replaced ... bitmap=720x720`，地址来自当前 Sony
  snapshot，证明同一 Sony 官方图片已被 Settings 进程解码并设置到静态 Hero ImageView。

## 6. 后续门禁

1. 如需人工 UI 回归，验证用户自定义图覆盖、清除后回落官方图；损坏输入保留旧缓存已由
   instrumentation 覆盖；
2. 对 LinkBuds S 重复 Sony descriptor、App 详情页、ContentProvider 和 HyperOS 设置页验证；
3. LinkBuds S 通过后完成三型号真机显示矩阵并关闭 Phase 15。
