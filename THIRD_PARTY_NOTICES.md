# Third-Party Notices · 第三方组件声明

**IrisBox / 虹膜空间** is an independent integration project. All third-party components retain their respective upstream licenses; no blanket relicensing of AOSP, the Linux kernel, Raspberry Pi firmware, or Magisk is intended. Project-specific source code currently carries no blanket open-source license grant. Android, Raspberry Pi, Google, and other third-party names identify their respective products and do not imply endorsement.

---

## Component Inventory & Licensing

| Component | Upstream Source, License & Supplied Materials |
| :--- | :--- |
| **AOSP 13 Userspace, DocumentsUI, `init` & Libraries** | [AOSP Licenses](https://source.android.com/docs/setup/about/licenses), baseline `android-13.0.0_r75`. Individual Apache-2.0 / BSD / MIT / other notices are preserved in the image and `reference/licenses/AOSP-NOTICE.xml.gz`. Integration patches are located in `reference/patches/` and `engine/native/inet/`. |
| **Raspberry Pi 4 Device Integration** | [Raspberry Vanilla Android 13 Manifest](https://github.com/raspberry-vanilla/android_local_manifest/tree/android-13.0); exact recorded repository revisions are listed in `reference/host-profile.json`. Uses source-built factory partitions without substituting unrelated third-party prebuilt Android distributions. |
| **Linux Kernel** | `GPL-2.0`, [Linux 5.15.150 rpi commit `b951c20ef4d0`](https://github.com/raspberry-vanilla/android_kernel_brcm_rpi/tree/b951c20ef4d009cbde200a3c4c436210689d1280); [matching build instructions](https://github.com/raspberry-vanilla/android_kernel_manifest/tree/android-13.0). Full source archive is distributed alongside the release. |
| **Broadcom / Raspberry Pi Boot Firmware & Vendor Files** | Governed by their respective notices and redistribution terms, including `reference/licenses/LICENCE.broadcom` and `reference/licenses/vendor-NOTICE.xml.gz`. Firmware is not covered by a project-wide source license. |
| **Mesa / DRM / Gralloc & Connectivity Adaptation** | Matched Android and vendor libraries retaining their individual upstream notices. Integration patches reside under `reference/patches/` with provenance recorded in `reference/`. Building the APK does not claim a from-zero rebuild of every fixed prebuilt library. |
| **Magisk 30.7 Guest Root Component** | [Official Magisk v30.7 Source](https://github.com/topjohnwu/Magisk/tree/v30.7) (`GPL-3.0`). `engine/runtime/root/magisk_30_7/` retains `LICENSE`, `adaptation.json`, native constant patches, `apk_assets.patch`, and `SHA256SUMS`. The bundled Manager is a modified, re-signed container adapter (not an unmodified official binary), with no signature-verification bypass added. Boot flashing and global host policy changes are unsupported inside guests. |
| **BusyBox 1.36.1.1** | `GPL-2.0`; [BusyBox 1.36.1](https://busybox.net/downloads/) built with [topjohnwu's `ndk-box-kitchen`](https://github.com/topjohnwu/ndk-box-kitchen/tree/14d189ea3070a8167b3576bf83fe070d4a3441af) (`reference/licenses/BusyBox-LICENSE.txt`). The unmodified ARM64 binary from Magisk's official `busybox-1.36.1.1` archive is also installed into the project-private host tools directory (`/data/irisbox/host/bin/busybox`). |
| **Mapped-Guest Storage Adapter (`vold`)** | Rebuilt from AOSP `system/vold/Utils.cpp` (`Apache-2.0` notices retained). The patch, hashes, isolated build script, and project-quota limitations are documented in `reference/patches/vold-userns.patch`, `reference/storage-build.json`, and `engine/native/storage/`. |
| **Optional Google Components** | **Excluded from the public ROM and Git repository.** Google programs retain their own licenses. Users must independently supply authorized, hash-matched components; code support does not grant redistribution permission. No accounts, tokens, running-instance data, or extracted user updates are distributed. |

---

## Source Archives & Compliance Notes

- **Source Kits**:
  - `IrisBox-guest-root-sources.zip` bundles the Magisk 30.7 (archived at v30.7 submodule gitlink commits) and BusyBox inputs, licenses, kitchen config/patches, and adaptation metadata.
  - `IrisBox-upstream-sources.zip` additionally bundles the pinned Linux kernel source tree.
  - Both archives include SHA-256 manifests and are listed in [DOWNLOADS.md](DOWNLOADS.md) / [DOWNLOADS.zh-CN.md](DOWNLOADS.zh-CN.md).
- **Rebuilding Notes**: Rebuilding the modified Magisk Manager requires your own signing key and upstream Android build dependencies; bit-for-bit reproduction of upstream signed artifacts is not claimed.
- **中文补充说明**：宿主 Magisk 由使用者自行安装，不集成在宿主刷机镜像内。实例内 Root 保留既有适配组件，其许可证、修改说明与源码包随发行提供。公开镜像与源码包的来源说明不替代各组件的独立许可证；GitHub Releases 与任何网盘镜像均适用同等合规要求。
