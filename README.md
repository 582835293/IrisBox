# IrisBox · Android Container

[简体中文](README.zh-CN.md) · English

IrisBox (**虹膜空间**) runs multiple Android userspaces inside a rooted Raspberry Pi 4 Android host. Install the APK, grant host Magisk root, import a compatible ROM, create an instance and start it.

This preview supports one matched **Raspberry Vanilla AOSP 13 / ARM64 / Raspberry Pi 4** host profile. Instances share the host Linux kernel, with separate namespaces, Binder devices, overlays and app data. Root access alone does not make other devices or arbitrary Android ROMs compatible.

## Get started

1. Read the [host and installation guide](docs/en/installation.md). The host needs the matched build, kernel capabilities, Wi-Fi and an already configured Permissive environment.
2. Install **host Magisk yourself**, following its upstream installation instructions and the board's boot layout. IrisBox never installs or patches host Magisk.
3. Install `IrisBox-0.1.4.apk`. Open it and choose **Authorize and check**, then **Install engine**.
4. Choose **Import ROM**, select `IrisBox-rpi4-a13-aosp.irisrom`, then **Manage instances → New instance → Start → Enter**.
5. Use **EN / 中文** to choose System default, English or 简体中文. This changes the host App UI only; each guest keeps its own Android language and data.

[Downloads and SHA-256](DOWNLOADS.md) · [GitHub Releases](https://github.com/582835293/IrisBox/releases)

![IrisBox illustrated usage workflow](docs/images/usage.en.svg)

The image illustrates the workflow; it contains no account or device data.

## Included

- ROM import with path, size, manifest and per-file SHA-256 checks; no installer scripts from ROM packages.
- Independent instance data, memory ceilings, resolution presets, lifecycle controls and optional guest Root.
- Live list previews, shared-buffer fullscreen display, mouse/keyboard/touch input and file exchange.
- Per-instance playback audio and software video support on the tested profile.
- Matched host image preparation, source provenance, build instructions and comparison with the previous Project Iris engine.

The public ROM contains AOSP and compatibility files, **without Google apps or accounts**. Optional Google support remains available for separately supplied, appropriately licensed matching components. Existing private Google-enabled instances retain their ROM and data. Guest Root uses the existing bundled component and per-instance switch; this is separate from installing host Magisk.

The published 0.1.3 test was blocked by a Play Protect certification warning. On October 24, the local 0.1.4 candidate restored the catalog and actually installed Open Sudoku from Play; **device certification still has not passed**. See the [current results](docs/en/validation.md). Each guest needs its own custom-ROM registration where applicable; registration does not establish device certification.

Version 0.1.4/e21 adds **Orientation → Follow app / Landscape / Portrait** to the fullscreen toolbar, with live changes and saved per-instance selections. English/Chinese menus, rotated input and persistence across Guest restart passed device tests. Existing 0.1.3 users need the stopped-state engine maintenance upgrade described in [building and recovery](docs/en/building.md).

## Device screenshots

| Play installation in progress | Installed from Play | Tiantian Xiangqi running |
|---|---|---|
| ![Actual Play installation](docs/images/play-download.png) | ![Installed calculator with Open button](docs/images/play-installed.png) | ![Actual game puzzle board](docs/images/game-running.png) |

![Instance list with a live preview](docs/images/instance-previews.png)

Real device captures, cropped and privacy-redacted with owner permission; no AI redraw. The game image shows a puzzle board. The owner separately confirmed WeChat and QQ QR login/gameplay. In this list capture, instance 1 is stopped and instance 2 has a live preview. Screenshots do not establish Google device certification. [Image details](docs/images/README.md).

## Documentation

| English | 简体中文 |
|---|---|
| [Installation and clean host image](docs/en/installation.md) | [安装与使用](docs/01_安装与使用.md)、[发布与刷机](docs/08_国际化与发布.md) |
| [Architecture and isolation boundaries](docs/en/architecture.md) | [技术架构](docs/02_技术架构.md) |
| [ROM format](docs/en/rom-format.md) | [ROM 规范](docs/03_ROM规范.md) |
| [Build, maintenance and recovery](docs/en/building.md) | [构建与维护](docs/04_构建与维护.md) |
| [Comparison and learning route](docs/en/comparison.md) | [对比与学习](docs/05_对比与学习.md) |
| [Validation and limitations](docs/en/validation.md) | [首次验收](docs/06_验收记录.md)、[登录后回归](docs/07_登录后回归与修复.md) |
| [Google Play recovery and certification](docs/en/google-recovery.md) | [Google商店恢复、备份与认证区别](docs/10_Google商店恢复与认证说明.md) |

## Root identity isolation

Version 0.1.3 requires a distinct User namespace per instance. Guest root maps to a dedicated unprivileged Host UID range, with no Host-UID0 fallback. Existing e13 installations need a stopped-state backup and migration; installing the APK alone is insufficient. See the [migration and isolation guide](docs/en/userns.md).

## Preview boundaries

The clean flash image has new, empty userdata and unmodified factory boot/system/vendor partitions. Static checks are recorded; **a fresh USB-disk flash and first boot have not been tested**. Existing-host validation does not establish a complete installation on a host that never ran the previous project. Other kernels/devices, Enforcing SELinux, camera/microphone, protected video, Play Integrity and arbitrary third-party Root modules are not covered.

This is an independent project. VMOS is mentioned only as a user-experience comparison; there is no affiliation. Internal names such as `com.rootvmos.viewer`, `/data/rootvmos` and `.irisrom` are retained to preserve upgrades and protocol compatibility.

See [third-party notices](THIRD_PARTY_NOTICES.md) and [source provenance](reference/NOTICE.md). Publishing this repository does not relicense third-party components; project-specific code currently has no blanket license grant. Signing keys, user data, accounts, raw device logs and screenshots are excluded.

The clean host preview is **USB/sda only**, with 12 GiB empty userdata for a 16 GB or larger USB disk. It is not an SD/mmc image.
