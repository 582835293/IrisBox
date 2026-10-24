# Guest download browser / 实例内下载入口

The matched factory ROM contains `org.chromium.webview_shell`, a WebView test app with no DownloadListener or DownloadManager integration. It can display a page but does not enqueue APK downloads. This ordinary Guest app supplies the missing bootstrap path:

`Guest website → download confirmation → Guest Android DownloadManager → Downloads list → Android source permission → system APK installer`.

Via, AppBao and subsequent test apps must use that real in-Guest path for acceptance. Copying a target APK from the computer or running `pm install` is not equivalent evidence. The browser has INTERNET and REQUEST_INSTALL_PACKAGES only, no root or Host controller access. TLS validation remains standard, file/content URL access and mixed content are disabled, and HTTP download links are upgraded to the same HTTPS origin. Downloads remain owned by this Guest and are not shared with Host accounts or other instances.

`build.ps1` builds against SDK30 / target33 with JDK17 and build-tools34. The ignored `build/browser-test.keystore` is retained for updates and is never published. The full product build packages the signed APK into the engine; the trusted Host preparer places it in the private system overlay before releasing mapped init. No ROM installer script runs. A public clone creates a new key: signed browser APK/engine archive hashes consequently differ, while their unsigned APK ZIP entries and other engine files can be compared independently.

匹配ROM的预装浏览器是没有下载功能的测试壳。本组件补齐正常网页下载、下载进度、来源授权及系统安装界面。所有目标应用的验收必须实际在同一实例内从网页下载并经系统安装，不能用电脑离线注入替代。组件是普通实例App，不获取root；账号、下载和来源授权均属于当前实例。私有签名密钥保留在忽略的build目录中，公开构建会生成不同的本地签名。

In the guest, open **IrisBox Browser**, choose **Via website**, tap the site's **Android** link and confirm the download. Open **Downloads**, tap the completed APK, permit this source when Android asks, then use **Install → Open**. For AppBao, open Tencent's page in Via and use **应用宝手机版**; the desktop download button may offer a PC installer instead. Each browser/store needs its own Android source permission. None is granted silently by the engine.

实例中打开 **IrisBox浏览器**，选择 **Via官网**，点击网页的 **Android** 并确认下载。在 **下载** 列表点击完成的APK，按Android提示允许该来源，再点 **安装 → 打开**。在Via中访问腾讯官网时选择 **应用宝手机版**；网页主要下载按钮可能是电脑版入口。各浏览器或商店分别通过Android界面申请安装来源权限，引擎不会静默授予。

The trusted preparer refreshes the package directory timestamp when a newer APK was copied. Android's directory-package cache otherwise retains old manifest metadata after replacing only the APK. This changes neither browser app data nor its download records.
