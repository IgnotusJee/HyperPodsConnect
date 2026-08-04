<div align="center">

<img src="https://github.com/user-attachments/assets/e8a3df6b-6e67-485a-ae1c-018ac24e87d4" width="120" height="120" style="border-radius: 24px;" alt="HyperPods Connect 图标"/>

# HyperPods Connect

**为 HyperOS 设备提供系统级多品牌耳机控制**

[![GitHub Release](https://img.shields.io/github/v/release/1812z/OppoPods?style=flat-square&logo=github&color=black)](https://github.com/1812z/OppoPods/releases)
![Downloads](https://img.shields.io/github/downloads/1812z/OppoPods/total?style=flat-square)
[![Platform](https://img.shields.io/badge/Platform-Android-green?style=flat-square&logo=android)](https://android.com)
[![LSPosed](https://img.shields.io/badge/Framework-LSPosed-blueviolet?style=flat-square)](https://github.com/LSPosed/LSPosed)
[![HyperOS](https://img.shields.io/badge/ROM-澎湃OS3-orange?style=flat-square)](https://hyperos.mi.com)

**[English](README.md)** | **简体中文**

</div>

HyperPods Connect 是为小米 HyperOS 设备提供系统级多品牌耳机控制的 Xposed 模块。展示层统一消费品牌无关的驱动能力模型，目前内置 OPPO 与 Sony 驱动。

### 耳机功能

- **降噪控制** — 根据当前设备声明的能力，在关闭、降噪、自适应和通透模式之间切换
- **低延迟模式** — 控制低延迟音频，并可在连接时自动开启
- **电量显示** — 显示左耳、右耳和充电盒的实时电量及充电状态
- **Sony 支持** — WH-1000XM4 2.5.1 与 LinkBuds S 4.2.1 根据各自设备能力支持电量、降噪/环境声、环境声等级、人声增强和官方均衡器预设

### HyperOS 集成

- **超级岛** — 支持 HyperOS 官方超级岛或模块内建超级岛
- **融合设备中心** — 在控制中心设备卡片中展示并控制受支持耳机
- **设置集成** — 将受支持耳机的信息投影到系统蓝牙设置
- **设备流转** — 保留融合设备中心内的多设备一键流转能力
- **ROM 展示兼容** — 将 HyperOS 展示门禁兼容值与真实品牌、型号及驱动配置完全隔离

### 模块功能

- **快捷弹窗** — 从通知或控制中心耳机卡片打开浮窗，查看电量并控制受支持功能；点击「更多」进入完整面板
- **快捷跳转** — 从通知或耳机卡片进入模块或系统蓝牙设置
- **能力感知控件** — 隐藏不支持的功能、禁用只读控件，并且只展示当前驱动声明的合法值

### 系统要求

- 小米设备，运行 Android 15 或更高版本的 **HyperOS**；官方超级岛集成仅支持 HyperOS 3
- **LSPosed API 101** 或更高版本

### 安装与使用

1. 卸载旧包 `moe.chenxy.oppopods`。HyperPods Connect 使用新的 `org.hyperpods.connect` 应用身份，不迁移旧设置。
2. 安装完整 APK。
3. 在 LSPosed 中启用模块并勾选推荐作用域。
4. 使用应用右上角按钮重启所选作用域进程。
5. 通过蓝牙连接受支持的 OPPO 或 Sony 耳机。

本地开发或部署请使用：

```powershell
.\gradlew.bat :app:installDebug
```

Android Studio 优化部署不能作为 LSPosed 模块已更新的依据。安装新 APK 后，需要重启所选 LSPosed 作用域进程。

### 下一阶段

- **自定义 EQ** — 按 Sony、OPPO 及未来厂商驱动声明的频段能力调整均衡器曲线
- **自动设备图片** — 从经过验证的来源解析并缓存对应产品和颜色的耳机图片
- **展示完善** — 继续统一 MiLink 桥接、连接弹窗和 HyperOS 展示所消费的品牌无关驱动状态

详细目标、证据门禁与验收标准见 [Phase 13–16 路线图](docs/architecture/PHASE13_16_NEXT_STAGE_ROADMAP.md)。

### 致谢

- [HyperPods](https://github.com/Art-Chen/HyperPods) by Art_Chen — 原始项目
- [Miuix](https://github.com/YuKongA/miuix) — HyperOS 风格 Compose UI 组件
- [OPPOPods](https://github.com/Leaf-lsgtky/OppoPods) by Leaf-lsgtky

### 许可证

GPL-3.0
