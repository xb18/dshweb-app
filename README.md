# dshweb-app

DSH Web 的移动端壳项目：安卓端为最小 WebView 壳，内嵌 `https://dsh.202921.xyz`。
页面逻辑全部在 Web 侧，改 UI 不需要重发 APK；原生只负责加载网页、安全区与 JS 桥。

当前只做安卓，后续 iOS 壳按同一 JS 契约实现即可复用网页代码。

## 目录结构

```
dshweb-app/
├── android/                  # 安卓 WebView 壳（Gradle / Kotlin）
│   └── app/src/main/java/com/xb18/dshweb/
│       ├── MainActivity.kt   # WebView、安全区、window.nativeapp 注入
│       └── Updater.kt        # 更新检查/下载/安装
└── .github/workflows/android.yml
```

## 顶部安全区

- Activity 全屏，`statusBarColor` 透明，内容延伸到状态栏下。
- **原生兜底**：WebView 默认带 `topMargin = statusBarHeight`，即使页面未适配也不会被状态栏遮挡。
- **页面自适配**：网页通过 `window.nativeapp.shell.getInsets()` 或 `onInsetsChange()` 拿到
  `top / bottom / left / right / statusBarHeight / navigationBarHeight`（单位 px）自行布局；
  若页面已自行处理顶部安全区，调用 `window.nativeapp.shell.setContentInsetTop(0)` 关掉原生兜底。

## JS 桥契约（window.nativeapp）

桥接采用合并式注入，不覆盖页面已有对象。原生实现对象为 `DshWebNative`，
JS 侧统一使用 `window.nativeapp`，iOS 可按同一契约实现：

- `shell.getInsets()` / `shell.getInfo()` / `shell.onInsetsChange(cb)`
- `shell.setStatusBarStyle({ light, color })` / `shell.setFullscreen(on)` / `shell.openExternal(url)`
- `shell.setContentInsetTop(px)`
- `update.check(source)` / `update.getAppVersion()` / `update.download(url)` / `update.install()`
  / `update.canInstall()` / `update.onEvent(cb)` 等

`NativeAppReady` 事件在桥注入完成后派发。

## 发版

1. 改 `android/gradle.properties` 的 `shellVersionName`（`webUrl` 一般不变）。
2. 提交后打 `android-v<版本号>` tag 推送。
3. `android.yml` 构建 Debug APK，创建 Release，并通过 WebDAV 发布 APK 与 `dshweb-update.txt` 更新索引。

## 统一分发目录

所有 app 共用一个 AList/WebDAV 挂载与一个蓝奏云分享，不再每个 app 建一套。
CI 把文件上传到统一根下的 `dshweb-app/` 子目录：

```
<WEBDAV_DIR>/                       # 统一可写分发根（账号挂载 /lanzou/appdist，公网分享 59uaf2bR）
├── dshweb-app-1.0.0.apk            # 本 app 的 APK（文件名前缀区分 app）
├── dshweb-app-update.txt           # 本 app 的更新索引
└── <other-app>-1.0.0.apk           # 其他 app 各自用前缀，不建子目录
```

分发目录必须**扁平**：蓝奏云分享挂载对子文件夹的列表与直链有兼容问题，
因此各 app 用文件名前缀区分，不建子目录。

`WEBDAV_DIR` 填 `lanzou/appdist`（账号挂载可写；分享链接挂载只读，不能用于上传）。
`PUBLIC_BASE` 填统一根的公网分享地址（如 `https://pan.202921.xyz/sd/59uaf2bR`），
APK 默认更新源同理。

CI 需要的 Secrets / Variables：

- Secrets：`WEBDAV_URL`、`WEBDAV_USERNAME`、`WEBDAV_PASSWORD`（可选 `ALIST_BASE_URL`）
- Variables：`WEBDAV_DIR` 填统一根（`lanzou/appdist`）、`PUBLIC_BASE` 填统一根公网地址（`https://pan.202921.xyz/sd/59uaf2bR`）（可选 `ALIST_REFRESH=1`、`ALIST_SHARE_DIR`）

更新索引为通用 JSON（与参考壳一致）：

```json
{ "version": "1.0.0", "url": "https://.../dshweb-app-1.0.0.apk", "notes": "...", "size": 1234567 }
```
