# 实机运行截图说明 · Device Captures & Provenance

本文档记录仓库 `docs/images/` 目录下所有实机运行截图的测试环境、裁剪脱敏方式与真实验收边界。

> [!IMPORTANT]
> **图片处理与合规原则**
> - **真实实机画面**：所有截图均来自匹配的 **Raspberry Pi 4 (AOSP 13 / ARM64)** 实机运行画面，经设备所有者明确授权公开。
> - **确定性脱敏**：仅使用本地脚本执行确定性区域裁切、单块不透明隐私遮挡与 PNG 重编码；**无任何 AI 重绘、未修改设备认证文字、未伪造游戏状态或拼接不同时段的运行画面**。原始未裁切证据保留在私有目录 `validation/private/` 中，不随源码提交。
> - **商标与分发声明**：Google、腾讯、Supercell、Rovio、Blender 等界面与商标归各自权利人所有，截图仅用于说明兼容性测试结果，不代表合作或背书；Google 程序与第三方游戏 APK 均不随本项目分发。

---

## 1. 2026-10-24：Google Play 下载、象棋残局与多实例预览（`v0.1.4 / e21`）

**测试基线**：实例 1（768 MiB，停止状态）、实例 2（2048 MiB，运行状态）。

| 文件名 | 画面内容与脱敏说明 | 验收边界 |
| :--- | :--- | :--- |
| `play-download.png` | Google 计算器在实例 2 的 Google Play 商店内实际下载安装过程（仅裁切应用详情区域）。 | 证明商店目录恢复与实际下载能力，**不代表 Play Protect 设备认证通过**。 |
| `play-installed.png` | 同一应用安装完成后的 `Open` / `Uninstall` 按钮画面（`PackageManager` 核验安装来源为 `com.android.vending`，随后实际打开运行）。 | 同上，下载安装成功与设备认证分属不同检查项。 |
| `game-running.png` | 用户扫码后实例内《天天象棋》运行的残局棋盘画面（裁去顶部含榜单昵称区域与底部系统导航栏）。 | 微信与 QQ 扫码登录及对局游玩已由设备所有者另行亲测确认；单张棋盘截图本身不作为联网对局证明。 |
| `instance-previews.png` | 宿主 App 实例管理列表：实例 1 停止、实例 2 运行并显示实时预览（裁去宿主系统栏，单块不透明遮挡预览角落的个人 Play Points 信息）。 | 如实反映单停止 + 单运行的管理页状态。 |

---

## 2. 2026-10-26：部落冲突、YouTube 播放与双实例并发（`v0.1.5 / e25`）

**测试基线**：实例 1（768 MiB）与实例 2（2048 MiB）在最终 `e25` 引擎下同时运行。

| 文件名 | 画面内容与脱敏说明 | 验收边界 |
| :--- | :--- | :--- |
| `clash-village.png` | 实例 2 中《部落冲突》（`18.647.5`）的新手村庄与教程对话；在正常重连后通过虹膜空间全屏窗口点击推进欢迎对话（不包含年龄确认页、账号或玩家标识）。 | 仅验证新手村庄渲染与真实窗口点击交互，**不代表部落战、充值支付或长期对战通过**。 |
| `youtube-playback.png` | 实例内 YouTube（`21.42.243`）播放 Blender 官方开源影片《Big Buck Bunny》的视频区域（确定性裁去导航栏、账号与推荐列表；素材归 Blender Foundation，见 [官方原片](https://www.youtube.com/watch?v=aqz-KE-bpKQ)）。 | 证明软件视频解码、全屏显示与音频输出正常，不承诺 4K 或 DRM 受保护内容播放。 |
| `dual-instances.png` | `0.1.5 / e25` 两个原实例同时运行（768 MiB / 2048 MiB），两张实时缩略图、桌面状态及原计数 `8` / `1` 可见（仅裁去宿主系统栏；截图后两实例预览序列继续递增）。 | 已通过隔离、输入、网络、文件与音频回归测试。 |
| `angry-offline.png` | 《愤怒的小鸟 2》重建获准的首次运行数据后进入 `Main` 场景，但提示 `You are offline / Please go online to complete the update`（无账号、年龄或玩家标识，仅 PNG 重编码）。 | **未通过关卡验收的真实记录画面**，绝不作为游玩成功证明。 |

---

## 3. 2026-10-27：宿主交换空间与双游戏同屏并发（`v0.1.6 / e27`）

**测试基线**：4 GB 树莓派 4 宿主启用 **2 GiB 共享交换空间**（开启重启自动恢复），实例 1（1024 MiB）与实例 2（2048 MiB）同屏运行。

| 文件名 | 画面内容与脱敏说明 | 验收边界 |
| :--- | :--- | :--- |
| `swap-settings-zh.png`<br>`swap-settings-en.png` | 宿主 App 内真实的 **2 GiB 交换空间 + 重启后自动恢复（宿主 Magisk）** 中英文设置页（确定性裁去宿主系统栏与导航栏，无账号或设备标识）。 | 展示 `0.1.6 / e27` 真实生效的宿主交换空间状态。 |
| `angry-gameplay.png`<br>`angry-tutorial.png` | `e27` 双游戏运行期间《愤怒的小鸟 2》的 Red 技能触发与实际建筑击碎画面（`angry-tutorial.png` 为第一段获得 99,792 分后进入第二段教程房间的画面）。 | 证明在 2048 MiB 实例 + 2 GiB 宿主 Swap 下可完成真实拉弓、瞄准与技能交互。 |
| `dual-games.png` | 同一宿主同时运行两个实例的真实单次同屏截图（非两次独立运行拼接）：左侧为 1024 MiB 实例运行《部落冲突》新手村庄，右侧为 2048 MiB 实例完成《愤怒的小鸟 2》教程结算（**203,332 分**）。《部落冲突》空闲断线后已按游戏原有 `Reload Game` 重连。 | 属于短时教程交互验收，**不代表长期对战、所有关卡、高帧率稳定运行或 Google 设备认证通过**。 |

---

## English Summary

All images in `docs/images/` are genuine captures from the matched **Raspberry Pi 4 (AOSP 13 / ARM64)** host running IrisBox (`0.1.4/e21` through `0.1.6/e27`), published with the device owner's explicit permission:

- **Deterministic Processing Only**: Only deterministic cropping, a single opaque privacy mask (for Play Points in `instance-previews.png`), and RGB PNG re-encoding were applied. No AI redraw, altered certification labels, fabricated game states, or composite splicing of separate runs was used.
- **Key Captures**:
  - `play-download.png` & `play-installed.png`: Real Google Play installation of Google Calculator (`installer=com.android.vending`).
  - `game-running.png`: Tiantian Xiangqi puzzle board (WeChat/QQ QR login and gameplay were separately confirmed by the owner).
  - `clash-village.png` & `youtube-playback.png`: Interactive Clash of Clans tutorial village and cropped playback of Blender Foundation's *Big Buck Bunny* on YouTube.
  - `angry-offline.png`: Retained record of the unresolved offline update prompt prior to `0.1.6` swap configuration (not a passed gameplay claim).
  - `swap-settings-en.png`, `swap-settings-zh.png`, `angry-gameplay.png`, `angry-tutorial.png` & `dual-games.png`: Actual `0.1.6/e27` captures showing 2 GiB host swap configuration, real Angry Birds 2 target destruction (99,792 intermediate / 203,332 final tutorial score), and simultaneous execution alongside Clash of Clans.
- **Boundaries**: These captures do not establish long-term battle stability, guaranteed frame rates, or Google Play Protect device certification. Third-party trademarks belong to their respective owners, and no Google or game APKs are redistributed.
