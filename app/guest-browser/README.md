# 实例内下载浏览器 · Guest Download Browser (`com.irisbox.browser`)

## 1. 背景与设计目标

树莓派 4 匹配的出厂 AOSP 13 ROM 仅预装了 `org.chromium.webview_shell`（WebView 测试壳），未实现 `DownloadListener` 或 `DownloadManager` 接入，虽然能渲染网页但无法发起 APK 下载。

为此，`0.1.4` 起新增普通实例级下载入口应用 **`com.irisbox.browser`**，由可信引擎在启动准备阶段预装至实例私有 `system` 写层，补齐完整的实例内自举下载链路：

```text
实例内网页浏览 → 下载确认弹窗 → 实例 Android DownloadManager → 下载列表 → Android 安装来源授权 → 系统 PackageInstaller 安装
```

> [!IMPORTANT]
> **验收与安全边界**
> - **真实实例内下载**：Via、应用宝及后续测试应用必须在同一实例内通过上述真实网页下载与系统安装器完成安装；从电脑通过 ADB 注入 APK 或直接执行 `pm install` 不作为同等验收证据。
> - **最小权限与隔离**：仅声明 `INTERNET` 与 `REQUEST_INSTALL_PACKAGES` 权限，无任何宿主控制权或 Root 权限，不执行静默安装。保持标准 TLS 校验，禁用 `file://` / `content://` 访问与混合内容（Mixed Content），并将同源 HTTP 下载链接自动升级为 HTTPS。下载文件与安装授权完全归当前实例所有，不与宿主或其他实例共享。

---

## 2. 实例内操作指南（中 / 英）

### 简体中文
1. 在实例桌面打开 **IrisBox 浏览器**，点击顶部快捷入口访问 **Via 官网**，点击页面中的 **Android** 下载按钮并确认下载。
2. 打开右上角 **下载** 列表，点击已完成的 APK，按 Android 系统提示允许“来自此来源的应用”，随后点击 **安装 → 打开**。
3. 如需安装应用宝，请在已安装的 Via 浏览器中打开腾讯应用宝官网，务必选择 **“应用宝手机版”**（网页主按钮可能返回 PC 版安装器）。每个浏览器或应用商店均需通过 Android 系统界面独立授予安装来源权限，引擎不会静默代授。

### English
1. Inside the guest, open **IrisBox Browser**, select **Via website**, tap the site's **Android** download link, and confirm the download.
2. Open **Downloads**, tap the completed APK, grant Android's **Install unknown apps** permission for this source when prompted, and select **Install → Open**.
3. For AppBao, open Tencent's official page inside Via and choose **应用宝手机版** (the primary desktop button may offer a Windows installer instead). Each browser or store requires its own standard Android source authorization; none is granted silently by the engine.

---

## 3. 构建、签名与包缓存刷新机制

- **编译环境**：`app/guest-browser/build.ps1` 基于 JDK 17、Android SDK Platform 30（`targetSdkVersion 33`）与 `build-tools 34.0.0` 编译，并作为第 50 项（及后续版本对应项）载荷封装进内置引擎。
- **独立私有签名**：构建使用本地忽略的 `app/guest-browser/build/browser-test.keystore` 进行签名以支持覆盖更新，私钥永不公开。从公开源码仓库克隆构建时会生成新的本地开发密钥，因此重编后的已签名浏览器 APK 及 `engine.zip` 整体哈希会与官方发行包不同；复核可重现性时应**分别比对浏览器 APK 的未签名 ZIP 条目与其余非浏览器引擎载荷**。
- **包解析缓存刷新（`e20+`）**：当可信准备器向已有实例写层更新浏览器 APK 时，会同步刷新其父目录时间戳，确保 Android `PackageManager` 检测到目录变更并重新解析新版 Manifest 元数据（而不改变浏览器的应用数据或下载记录）。
