# 虹膜空间 · IrisBox

**简体中文** · [English](README.md) · [下载与校验 (SHA-256)](DOWNLOADS.zh-CN.md) · [GitHub Releases](https://github.com/582835293/IrisBox/releases)

**虹膜空间（IrisBox）** 是一款运行在已获取 Root 权限的树莓派 4 Android 宿主上的多实例 Android 容器应用。各实例共享宿主 Linux 内核，同时拥有独立的命名空间（User / Mount / PID / Network / IPC / UTS / Cgroup）、私有 BinderFS、OverlayFS 写入层与应用数据目录。

> [!IMPORTANT]
> **当前预览版支持边界（v0.1.6 / 引擎 e27）**
> - **唯一适配宿主**：树莓派 4（ARM64，约 4 GiB 内存）+ 指定匹配的 **Raspberry Vanilla AOSP 13** 构建 + 已开启 Permissive 的 SELinux 环境。仅具备 Root 权限无法使其他设备或任意手机刷机包兼容。
> - **宿主 Magisk 需自行安装**：IrisBox 仅申请标准宿主 Root 授权，绝不自行安装或修补宿主 Magisk，也不修改宿主物理分区。
> - **存量版本升级注意**：
>   - `0.1.4 / e21` 用户升级前必须先按 [0.1.5 命名迁移与恢复指南](docs/11_Iris命名迁移.md) 执行停机备份与目录迁移，并为新包名 `com.irisbox.viewer` 重新授予宿主 Root。
>   - `0.1.2 / e13` 用户升级前必须先按 [User Namespace 隔离与迁移说明](docs/09_User_namespace隔离与迁移.md) 完成停机备份与 UID/GID 映射迁移。

---

## 核心特性

| 模块 | 能力说明 |
| :--- | :--- |
| **多实例与资源隔离** | 独立实例数据、可配置内存上限（`0.5`–`2.5 GiB`）、分辨率预设、实时横竖屏切换（跟随应用 / 横屏 / 竖屏）及生命周期控制。 |
| **Root 身份隔离** | 自 `0.1.3` 起强制启用每实例独立 User Namespace；实例内 `root (UID 0)` 映射至专有宿主非特权 UID 范围（`2000000000` 起），**无 Host UID 0 回退**。 |
| **安全 ROM 导入** | 严格校验归档路径、容量上限、清单格式与逐文件 SHA-256 摘要；仅解包普通文件，绝不执行 ROM 包内的任何安装脚本。 |
| **宿主交换空间（v0.1.6）** | 支持在 App 内配置宿主共享 Swap（关闭 / `1 GiB` / `2 GiB` / `4 GiB`），并可通过用户已安装的宿主 Magisk 实现重启后自动恢复。 |
| **图形、输入与多媒体** | 实例列表提供约 1 秒/帧的实时低频预览；全屏采用独立共享图形缓冲区管线，支持键鼠/触屏输入模式切换、单向 PCM 音频混音输出与软件视频解码。 |
| **文件交换与多语言** | 内置基于 Android SAF 的单实例文件导入/导出（`Download/IrisExchange`）；支持宿主 App 界面语言切换（跟随系统 / English / 简体中文），不影响实例内部系统语言。 |
| **可选组件支持** | **实例 Root（Magisk）**：内置适配组件并提供每实例独立开关及救援启动模式。<br>**Google 组件**：公开 AOSP ROM 不含 Google 应用或账号；支持使用者自行提供合法授权且摘要匹配的静态组件层，现有私人 Google 实例升级时完整保留原 ROM 与登录状态。 |

---

## 快速开始

![虹膜空间使用流程示意](docs/images/usage.zh-CN.svg)

*注：上图为操作流程示意，不包含真实设备截图、账号或私密数据。*

1. **检查宿主环境**：阅读 [安装与使用说明](docs/01_安装与使用.md) 及 [宿主镜像说明](docs/08_国际化与发布.md)，确认构建指纹、内核命名空间、GPU 驱动、cgroup 资源组、Wi-Fi 及 Permissive 状态符合要求。
2. **准备宿主 Root**：按照树莓派启动分区布局与 Magisk 官方说明**自行安装宿主 Magisk**。
3. **安装并初始化引擎**：安装 `IrisBox-0.1.6.apk`，打开应用后依次点击 **“1 · 授权与检查”** → **“2 · 安装内置引擎”**。
4. **导入适配 ROM**：点击 **“3 · 导入 ROM”**，选择 `IrisBox-rpi4-a13-aosp.irisrom` 完成校验入库。
5. **创建并运行实例**：点击 **“4 · 管理实例”** → **“新增实例”** → **“启动”**，待状态显示“桌面已就绪”后点击 **“进入”**。

---

## 实机运行与验收画面

> [!NOTE]
> 以下均为经设备所有者授权公开、通过确定性脚本裁切并遮挡隐私信息的实机运行画面（无 AI 重绘）。详细背景与边界说明请参阅 [实机图片说明](docs/images/README.md) 与 [实机验收记录](docs/06_验收记录.md)。

### 1. 实例双开与大型游戏并发（v0.1.6 / e27）

| 双实例桌面并发预览（768 MiB + 2048 MiB） | 双游戏同屏并发（1024 MiB 部落冲突 + 2048 MiB 愤怒的小鸟 2） |
| :---: | :---: |
| ![两个实例同时运行的实时缩略图](docs/images/dual-instances.png) | ![部落冲突与愤怒的小鸟2同时运行的真实双开图](docs/images/dual-games.png) |

- **测试条件**：4 GB 树莓派 4 宿主开启 **2 GiB 共享交换空间**，实例 1（1024 MiB）运行《部落冲突》新手村庄，实例 2（2048 MiB，保留 Google 与实例 Magisk）运行《愤怒的小鸟 2》。
- **交互结果**：两款游戏均通过真实全屏窗口触控交互，《愤怒的小鸟 2》完成拉弓发射、击碎目标与 Red 技能触发，教程结算得分 **203,332**；《部落冲突》在正常空闲断线后按原版 `Reload Game` 流程重连并推进对话。
- **验收边界**：此项为短时教程交互验收，**不代表长期对战、部落战、支付或稳定高帧率承诺**。

### 2. 应用、多媒体与宿主交换空间

| Google Play 实际下载中 | Google Play 安装完成 | 《天天象棋》实机残局棋盘 |
| :---: | :---: | :---: |
| ![Play实际安装过程](docs/images/play-download.png) | ![已安装并显示打开按钮](docs/images/play-installed.png) | ![实例内残局棋盘](docs/images/game-running.png) |

| 《部落冲突》新手村庄交互 | YouTube 实际视频播放 | 《愤怒的小鸟 2》目标击碎 | 宿主交换空间设置（中文） |
| :---: | :---: | :---: | :---: |
| ![部落冲突村庄与教程](docs/images/clash-village.png) | ![YouTube播放](docs/images/youtube-playback.png) | ![愤怒的小鸟2实际发射](docs/images/angry-gameplay.png) | ![交换空间中文页面](docs/images/swap-settings-zh.png) |

- **Google Play 下载与认证说明**：`0.1.4` 实测已恢复商店目录并实际下载安装 Open Sudoku 与 Google 计算器，但设置页 **Play Protect 设备认证仍未通过（`Device is not certified`）**。自定义 ROM 的 GSF ID 登记不等于设备认证通过，详见 [Google 商店恢复与认证说明](docs/10_Google商店恢复与认证说明.md)。
- **扫码登录确认**：《天天象棋》残局棋盘为实机截图；微信及 QQ 扫码登录与对局游玩已由设备所有者另行亲测确认（单张棋盘截图本身不作为联网对局证明）。

---

## 文档导航

| 类别 | 中文文档 | English Documentation |
| :--- | :--- | :--- |
| **安装与发行** | [安装与使用指南](docs/01_安装与使用.md) · [国际化、发布与干净镜像](docs/08_国际化与发布.md) | [Installation and Clean Host Image](docs/en/installation.md) |
| **架构与规范** | [技术架构与隔离边界](docs/02_技术架构.md) · [ROM 包规范 v1](docs/03_ROM规范.md) | [Architecture and Isolation](docs/en/architecture.md) · [ROM Format v1](docs/en/rom-format.md) |
| **迁移与隔离** | [0.1.5 Iris 命名迁移与恢复](docs/11_Iris命名迁移.md) · [User Namespace 隔离与迁移](docs/09_User_namespace隔离与迁移.md) | [0.1.5 Namespace Migration](docs/en/namespace-migration.md) · [User Namespaces](docs/en/userns.md) |
| **内存与商店** | [交换空间设置、低内存与恢复](docs/12_低内存与临时交换空间.md) · [Google 商店恢复与认证说明](docs/10_Google商店恢复与认证说明.md) | [Swap Settings and Low Memory](docs/en/low-memory.md) · [Google Play Recovery](docs/en/google-recovery.md) |
| **构建与演进** | [构建、升级与维护](docs/04_构建与维护.md) · [与旧项目对比及学习路线](docs/05_对比与学习.md) | [Building and Maintenance](docs/en/building.md) · [Comparison and Learning](docs/en/comparison.md) |
| **实机验收记录** | [首次实机验收与历次回归](docs/06_验收记录.md) · [登录后回归与修复](docs/07_登录后回归与修复.md) | [Validation Report and Boundaries](docs/en/validation.md) |

---

## 已知边界与合规声明

1. **干净宿主刷机镜像边界**：随附的 `.img.xz` 整盘镜像基于原始 `boot` / `system` / `vendor` 分区与全新格式化（12 GiB 空白 `userdata`）合成，固定仅支持 **USB / `sda` 启动**（适用 16 GB 及以上 USB 盘，**不可作为 SD 卡 / `mmc` 镜像使用**）。目前已通过分区与文件系统静态校验，**尚未在备用 USB 盘上验证物理刷入与首次启动**。
2. **未承诺范围**：非匹配内核/设备、Enforcing SELinux、物理摄像头/麦克风直通、DRM 受保护视频、Play Integrity、实例内按应用项目磁盘配额（Project Quota）以及任意第三方 Root 模块均不在支持承诺范围内。
3. **独立工程与开源许可**：本项目使用包名 `com.irisbox.viewer` 与设备目录 `/data/irisbox` 独立维护，`.irisrom` 格式保持兼容，旧参考工程保持只读。第三方组件保留各自上游许可证（见 [第三方组件声明](THIRD_PARTY_NOTICES.md) 与 [来源说明](reference/NOTICE.md)）；项目自有代码暂未授予统一开源许可证。签名私钥、账号、实例数据及原始未脱敏日志与截图均不随仓库公开。
