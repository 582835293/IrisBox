# Installation and Clean Host Image Guide

**English** · [简体中文](../01_安装与使用.md) · [Downloads](../../DOWNLOADS.md) · [Back to README](../../README.md)

---

## 1. Supported Host Profile & Prerequisites

- **Hardware**: Raspberry Pi 4 (ARM64, ~4 GiB physical RAM).
- **Exact Matched Host Fingerprint**:
  ```text
  Raspberry/aosp_rpi4/rpi4:13/TQ3A.230901.001/eng.lijiaq.20260916.141216:userdebug/test-keys
  ```
- **Kernel & Environment Requirements**:
  - Working host **MagiskSU**, an already configured **Permissive** SELinux environment, and connected Wi-Fi (`wlan0`).
  - Kernel support for `User` / `Mount` / `PID` / `Network` / `IPC` / `UTS` / `Cgroup` namespaces, `BinderFS`, `OverlayFS` / `ext4`, hybrid Android cgroups, BPF socket permission hooks, and the matched Mesa/V3D GPU stack.
  - **Mandatory User Namespaces (`0.1.3+`)**: Every instance requires a distinct User namespace with **no Host-UID0 fallback**. Root access alone cannot compensate for missing kernel or driver features.
- **Bundled Host Tools & Disk Headroom**:
  - The App verifies host capabilities and installs its SHA-256 verified BusyBox into `/data/irisbox/host/bin/busybox` without modifying host `/system`.
  - Allow at least **8 GiB free disk space** for staging, the root ROM library, and initial userdata. Installation and import progress appear in a foreground notification—do not force-stop the App or disconnect power during copying.

> [!WARNING]
> **Upgrading Existing Installations**
> - **`0.1.4 / e21` Users**: Complete the stopped-state [0.1.5 Namespace Migration and Recovery](namespace-migration.md) before starting instances on `0.1.5+`.
> - **`0.1.2 / e13` Users**: Complete the stopped-state [User Namespace Isolation and Migration](userns.md) before starting instances on `0.1.3+` (on the tested host, the retained 768 MiB ordinary guest took ~175 seconds for its first mapped boot, and the 1536 MiB Google guest took ~78 seconds—allow the first boot to finish).

---

## 2. Standard App Workflow

1. **Install the Host App**: Install `IrisBox-0.1.6.apk` (`com.irisbox.viewer`), open IrisBox, and approve its standard host Magisk prompt.
2. **Preflight & Engine Installation**: Select **1 · Authorize and check** to verify host capabilities, then select **2 · Install engine**.
3. **Import a Compatible ROM**: Select **3 · Import ROM** and use the Android system file picker to choose `IrisBox-rpi4-a13-aosp.irisrom`.
4. **Create & Start an Instance**: Select **4 · Manage instances** → **New instance** → **Start**, wait for **Desktop ready**, and select **Enter**.
   - **Guest Root vs. Host Magisk**: Optional guest Root (Magisk) has its own per-instance switch and authorization database; it is separate from host Magisk.
   - **Optional Google Layer**: The public AOSP ROM contains no Google files. Google-enabled imports require separately supplied, properly licensed matching components.

---

## 3. Memory Ceilings, Startup Order & Host Swap

- **Default Memory Ceilings**: Ordinary instances default to **1280 MiB (1.25 GiB)**; Google-enabled instances default to **1536 MiB (1.5 GiB)**. Memory limits (`0.5`–`2.5 GiB`) can be modified while an instance is stopped.
- **Recommended Dual-Instance Startup Order**: On a 4 GiB Raspberry Pi 4, start the larger **1536 MiB / 2048 MiB Google instance first**, wait for **Desktop ready**, and then start the smaller ordinary instance. Physical memory admission checks preserve running instances by rejecting new starts when `MemAvailable` is insufficient.
- **Host Swap Space (`v0.1.6+`)**: Select **Swap space** on the home or instance-list screen to configure shared host swap (**Off / `1 GiB` / `2 GiB` / `4 GiB`**) and optional reboot restoration via host Magisk. Always **stop all IrisBox instances normally** before resizing or disabling swap. See [Swap Settings, Low Memory and Game Touch Input](low-memory.md).

---

## 4. Google Registration & Everyday Operations

| Feature | Behavior & Guidelines |
| :--- | :--- |
| **Custom-ROM GSF Registration** | Each guest has an independent GSF ID. If prompted, register the current guest's own GSF ID on Google's [custom-ROM registration page](https://www.google.com/android/uncertified/). Keep the ID private and do not repeatedly clear GSF data. Registration is **not** manufacturer certification or a Play Integrity pass; see [Google Play Recovery](google-recovery.md). |
| **Background Persistence & Reboots** | Closing the host App leaves running guests active; reopening restores the manager connection. After a physical host reboot, open IrisBox and start instances manually. |
| **Live Orientation (`v0.1.4+`)** | Inside a running fullscreen guest, select **Orientation → Follow app / Landscape / Portrait** from the top toolbar. Changes apply live without restarting the guest and persist across reboots (the host window remains landscape and centers portrait frames). |
| **Multiple ROM Imports & Reset/Delete** | Importing another ROM changes the default only for **newly created** instances; existing instances retain their bound ROM ID. **Reset** clears an instance's userdata while keeping its configuration; **Delete** removes the instance completely (neither is a fix for host compatibility errors). |

---

## 5. Clean Host Flash Image Preview (`.img.xz`)

The downloadable `.img.xz` file is a **whole-disk host image** (distinct from an importable `.irisrom` package):

1. **Image Composition & 12 GiB Blank `userdata`**:
   Assembled by `tools/clean_host_image.py` from the unmodified factory `MBR` / `boot` / `system` / `vendor` partitions plus a **newly formatted 12 GiB empty `ext4` `userdata` partition** (enlarged from the factory ~4.6 GiB so ROM staging and installation fit without partition expansion; total raw image size is **`15,436,087,296 bytes`**). It is **not** exported from an active SSD and contains no host Magisk, accounts, Wi-Fi credentials, or private keys.
2. **`USB / sda` Boot Only (Not `SD / mmc`)**:
   The factory `fstab.rpi4` is pinned to `/dev/block/sda2`, `sda3`, and `sda4`. Use a **spare 16 GB or larger USB disk** on a Raspberry Pi 4 configured for [USB Mass Storage Boot](https://www.raspberrypi.com/documentation/computers/raspberry-pi.html#usb-mass-storage-boot), with only the target USB boot disk connected so it enumerates as `sda`.
3. **Host Magisk Installation**:
   Install host Magisk yourself following the [official Magisk instructions](https://topjohnwu.github.io/Magisk/install.html) and the board's boot layout (`boot.img` on this board is a FAT partition containing `Image` and `ramdisk.img`—do not run phone-style `boot` partition flash commands).

> [!CAUTION]
> **Verification Boundary for the Clean Host Image**
> All partition hashes, `e2fsck` checks, and the compressed `.xz` SHA-256 digest are statically verified and recorded in the release manifest, but **a physical flash and first boot on a spare USB disk, fresh host Magisk installation, and first-time INET dispatcher setup on a blank host have not been tested**.
