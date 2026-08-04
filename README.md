<div align="center">

<img src="https://github.com/user-attachments/assets/e8a3df6b-6e67-485a-ae1c-018ac24e87d4" width="120" height="120" style="border-radius: 24px;" alt="HyperPods Connect icon"/>

# HyperPods Connect

**System-level multi-brand headphone control for HyperOS devices**

[![GitHub Release](https://img.shields.io/github/v/release/1812z/OppoPods?style=flat-square&logo=github&color=black)](https://github.com/1812z/OppoPods/releases)
![Downloads](https://img.shields.io/github/downloads/1812z/OppoPods/total?style=flat-square)
[![Platform](https://img.shields.io/badge/Platform-Android-green?style=flat-square&logo=android)](https://android.com)
[![LSPosed](https://img.shields.io/badge/Framework-LSPosed-blueviolet?style=flat-square)](https://github.com/LSPosed/LSPosed)
[![HyperOS](https://img.shields.io/badge/ROM-HyperOS%203-orange?style=flat-square)](https://hyperos.mi.com)

**English** | **[简体中文](README_CN.md)**

</div>

HyperPods Connect is an Xposed module that provides system-level, multi-brand headphone control on Xiaomi HyperOS devices. Its presentation layer consumes a vendor-neutral driver capability model; OPPO and Sony drivers are currently bundled.

### Headphone features

- **Noise control** — Switch among Off, Noise Cancellation, Adaptive, and Transparency modes according to the capabilities reported by the connected device
- **Low-latency mode** — Toggle low-latency audio and optionally enable it automatically on connection
- **Battery display** — Show the battery level and charging state of the left earbud, right earbud, and charging case
- **Sony support** — WH-1000XM4 2.5.1 and LinkBuds S 4.2.1 support battery, noise/ambient control, ambient level, voice enhancement, and official equalizer presets according to each device's capabilities

### HyperOS integration

- **Super Island** — Use either HyperOS's official Super Island or the module's built-in island
- **Fusion Device Center** — Display and control supported headphones from the Control Center device card
- **Settings integration** — Project supported headphone information into system Bluetooth settings
- **Device transfer** — Preserve one-tap multi-device transfer in Fusion Device Center
- **ROM presentation compatibility** — Keep the HyperOS presentation gate value isolated from real brands, models, and driver profiles

### Module features

- **Quick popup** — Open a floating panel from the notification or Control Center card to view battery status and control supported features; tap **More** for the full panel
- **Quick navigation** — Open the module or system Bluetooth settings from the notification and headphone card
- **Capability-aware controls** — Hide unsupported features, disable read-only controls, and expose only values declared by the active driver

### Requirements

- A Xiaomi device running **HyperOS** on Android 15 or later; the official Super Island integration requires HyperOS 3
- **LSPosed API 101** or later

### Installation and usage

1. Uninstall the legacy `moe.chenxy.oppopods` package. HyperPods Connect uses the new `org.hyperpods.connect` application identity and does not migrate old settings.
2. Install the full APK.
3. Enable the module in LSPosed and select the recommended scopes.
4. Restart the selected scope processes from the button in the top-right corner of the app.
5. Connect a supported OPPO or Sony headphone through Bluetooth.

For development or local deployment, use:

```powershell
.\gradlew.bat :app:installDebug
```

Android Studio optimized deployment is not sufficient to update an LSPosed module. Restart the selected LSPosed scope processes after installing a new APK.

### Next stage

- **Custom EQ** — Adjust equalizer curves using the frequency bands exposed by each Sony, OPPO, or future vendor driver
- **Automatic device artwork** — Resolve and cache product/color-specific headphone images from verified sources
- **Presentation refinement** — Continue aligning the MiLink bridge, connection popup, and HyperOS presentation with vendor-neutral driver state

See the [Phase 13–16 roadmap](docs/architecture/PHASE13_16_NEXT_STAGE_ROADMAP.md) for detailed goals, evidence gates, and acceptance criteria.

### Credits

- [HyperPods](https://github.com/Art-Chen/HyperPods) by Art_Chen — original project
- [Miuix](https://github.com/YuKongA/miuix) — HyperOS-style Compose UI components
- [OPPOPods](https://github.com/Leaf-lsgtky/OppoPods) by Leaf-lsgtky

### License

GPL-3.0
