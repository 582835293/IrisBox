# 下载与校验 · 虹膜空间 (IrisBox)

[English](DOWNLOADS.md) · **简体中文** · [返回主页](README.zh-CN.md)

> [!WARNING]
> **下载与刷入前必读**
> - **格式区分**：`.irisrom` 是在宿主 App 内导入的实例 ROM 包；`.img.xz` 是刷入 **16 GB 及以上备用 USB 盘**的整盘宿主镜像（仅支持 `USB / sda` 启动，**非 SD 卡 / `mmc` 镜像**，且首次物理启动尚未实测）。两者不可互换。
> - **前置条件**：宿主 Magisk 需由使用者自行安装；公开 ROM 不含任何 Google 应用或账号。

---

## 发行附件与 SHA-256 校验值

| 文件 | 用途说明 | 大小 | SHA-256 |
| :--- | :--- | ---: | :--- |
| [IrisBox-0.1.6.apk](https://github.com/582835293/IrisBox/releases/download/v0.1.6-preview/IrisBox-0.1.6.apk) | 宿主端管理、引擎安装与显示 App（`v0.1.6 / e27`） | 17.1 MiB | `2806b9db48c0e99cd76762adf35d58a0450fc892ff9aaa2cd9af6dc3ec74d053` |
| [IrisBox-rpi4-a13-aosp.irisrom](https://github.com/582835293/IrisBox/releases/download/v0.1.4-preview/IrisBox-rpi4-a13-aosp.irisrom) | 在 App 内导入的树莓派 4 AOSP 13 实例 ROM 包（不含 Google） | 668.0 MiB | `04d586a15459dec7fca963b37450b6b5cc722d3fe9477f58eaa3b5a5411ef629` |
| [IrisBox-host-aosp13-rpi4-20260916-usb-16gb-preview.img.xz](https://github.com/582835293/IrisBox/releases/download/v0.1.4-preview/IrisBox-host-aosp13-rpi4-20260916-usb-16gb-preview.img.xz) | 16 GB+ 备用 USB 盘干净宿主整盘镜像（含 12 GiB 空白 `userdata`） | 514.1 MiB | `e13998996c47136373a0696f9d6424c7129888a91ee7804165f2681508a0fac2` |
| [IrisBox-host-aosp13-rpi4-20260916-usb-16gb-preview.json](https://github.com/582835293/IrisBox/releases/download/v0.1.4-preview/IrisBox-host-aosp13-rpi4-20260916-usb-16gb-preview.json) | 干净宿主镜像分区与文件系统静态校验元数据 | 0.0 MiB | `7e6d474d4e9b44c83f0f76109f8589e46db5ffc2ad1b6b5860affd13ae848717` |
| [IrisBox-guest-root-sources.zip](https://github.com/582835293/IrisBox/releases/download/v0.1.4-preview/IrisBox-guest-root-sources.zip) | 实例 Root（Magisk 30.7）与 BusyBox 源码、许可证及适配补丁包 | 13.0 MiB | `d3125503a65f67b839c371d7b534f68a96747e0c930fe3333a6ca04f4e96e272` |
| [IrisBox-upstream-sources.zip](https://github.com/582835293/IrisBox/releases/download/v0.1.4-preview/IrisBox-upstream-sources.zip) | 固定上游 Linux 内核、Magisk、BusyBox 及依赖完整源码归档 | 205.9 MiB | `9da344cad78f0a747ac7dcd14d0db94c541dff933c1ccc33de9f68af3449e682` |

**校验清单文件**：[SHA256SUMS](https://github.com/582835293/IrisBox/releases/download/v0.1.6-preview/SHA256SUMS) · [release-manifest.json](https://github.com/582835293/IrisBox/releases/download/v0.1.6-preview/release-manifest.json)

---

## 网盘镜像与分发说明

- **GitHub Releases 优先**：当前所有公开资源及附件均托管于 [GitHub Releases](https://github.com/582835293/IrisBox/releases)，直接下载链接仅在公开附件完成服务器大小与 SHA-256 核验后写入。
- **网盘镜像说明**：Google Drive（国际）与夸克网盘（国内）可作为额外镜像渠道，目前尚无分享链接。任何镜像文件均必须与本页 `SHA256SUMS` 严格一致。
- **隐私与合规边界**：Google 应用、个人账号及私有实例数据均不随发行资源分发。详细刷入步骤与边界请参阅 [安装与刷机边界说明](docs/08_国际化与发布.md)。
