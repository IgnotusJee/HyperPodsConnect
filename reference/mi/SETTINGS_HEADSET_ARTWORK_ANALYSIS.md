# HyperOS Settings 耳机主图加载分析

## 样本

- 包名：`com.android.settings`
- 设备 APK：`/system_ext/priv-app/Settings/Settings.apk`
- 本地样本：`reference/mi/apk/Settings.apk`
- JADX 输出：`reference/mi/jadx/Settings`
- 关键类：
  - `com.android.settings.bluetooth.MiuiHeadsetFragment`
  - `com.android.settings.bluetooth.tws.MiuiHeadsetAnimation`

JADX 完成了关键类和资源的反编译；全包反编译存在 140 个与本功能无关的反编译错误，不能据此把
整个 Settings 样本标记为无错误反编译。

## 结论

`MiuiHeadsetFragment.onCreateView()` 加载 `headsetlayout.xml`，大图是 `R.id.tic`。Fragment 在
`onResume()` 创建 `MiuiHeadsetAnimation(deviceId, context, rootView, ...)`，后者的
`loadDefault()` 首次调用 `loadDefaultInternal()`。

未知或虚拟 deviceId 进入默认分支后，会向主线程 Handler 投递任务：若 Settings 私有目录
`files/bluetooth/fc_resources/<deviceId>/0.*` 没有本地缓存，则调用 `fetchOnlineResource()`，并把
`R.drawable.bt_headset_find_detail` 写入 `R.id.tic`。因此在 Fragment 创建完成后简单设置图片仍可能
被这个异步任务覆盖。

## 模块实现约束

- 仅当 animation 的 `mDeviceId` 等于模块虚拟 deviceId 时考虑替换；
- animation 的 `mRootView` 必须与已确认的 OPPO `MiuiHeadsetFragment.mRootView` 是同一对象；
- 图片通过模块只读 ContentProvider 加载，不复制到 Settings 私有缓存；
- 优先级为用户静态图、官方 detail、官方 box/left/right；
- 系统控件是静态 `ImageView`，不注入 App 详情页所用的动态 WebP；
- 没有可解码缓存时放行 MIUI 原始逻辑。

Air5s 真机验证日志为 `Settings hero replaced ... bitmap=780x780`，并完成系统设置页截图视觉核验。
