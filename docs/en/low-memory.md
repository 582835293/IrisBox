# Host Swap Settings, Low Memory & Game Touch Input (`v0.1.6+`)

**English** · [简体中文](../12_低内存与临时交换空间.md) · [Validation Report](validation.md) · [Back to README](../../README.md)

---

## 1. Overview & Physical RAM Boundaries

On a 4 GB Raspberry Pi 4, host Android, Google services, graphics buffers, and all IrisBox instances share ~3.69 GiB of physical RAM. A `2048 MiB` instance setting is a **memory ceiling**, not dedicated or newly created RAM.

**Version `0.1.6 / e27`** introduces App-managed **shared host swap**: inactive memory pages are written to disk to relieve physical RAM pressure during heavy single-instance or dual-instance workloads, trading storage writes and latency for stability. **Swap is not physical RAM and does not guarantee that every game or prolonged dual-game session will run smoothly.**

---

## 2. Configuring Host Swap & Game Touch Mode in the App

1. **Stop All IrisBox Instances First**:
   Normally stop all running IrisBox instances before changing swap size or turning swap off (do not reset instances, re-import ROMs, or clear Google/game data).
2. **Select Swap Capacity & Optional Boot Restoration**:
   - Open **Swap space** from the home screen or instance list and choose **Off**, **1 GiB**, **2 GiB**, or **4 GiB** (shared across the host, separate from per-instance memory ceilings).
   - Check **Restore after reboot (host Magisk)** if you want swap re-enabled automatically on boot, then tap **Apply swap settings**.
   - Once complete, verify the **Configured**, **Active**, and **Used** swap values alongside **Host MemAvailable**, **Free disk**, and **Allocated files** (use **Refresh status** at any time).
3. **Launch Instances & Enable Touch Emulation for Games**:
   - Start your instance and wait for **Desktop ready** before entering.
   - **Unity / Slingshot Controls**: In the fullscreen top toolbar, switch the pointer toggle to **Input: touch** (`Touch emulation`). Standard mouse pointer mode may not activate Unity buttons or pull the slingshot in games like *Angry Birds 2*.

> [!NOTE]
> **Safe Deactivation & Guard Rejections**: Selecting **Off** safely deactivates this project's swap file and removes its own boot script without touching instance data. If any instance is still running, file ownership is invalid, free disk headroom is below **2 GiB**, or physical RAM cannot safely reclaim swapped pages while keeping **512 MiB physical headroom**, the controller rejects the change with an explicit reason rather than force-stopping instances or lowering memory guards.

---

## 3. Reboot Restoration, File Reuse & Clean Uninstall

- **Automatic Boot Restoration (Requires User-Installed Host Magisk)**:
  Enabling auto-restore installs `/data/adb/service.d/irisbox-swap.sh`, which activates only the verified, saved swap file during host boot (it never auto-starts instances, never modifies `fstab` or `/system`, and refuses to overwrite any foreign same-name script). If auto-restore is unchecked, swap stays inactive after a host reboot until re-applied in the App.
- **Swap File Retention & Fast Reuse**:
  Deactivated swap files are kept under `/data/irisbox/host/swap/` so re-selecting the same size reuses the existing file and inode without rewriting gigabytes to disk. Switching across all three sizes (`1` / `2` / `4 GiB`) can retain up to **7 GiB** total on disk (reported under **Allocated files**). Never delete an active swap file manually.
- **Clean Uninstall**:
  Uninstalling the APK alone leaves `/data/irisbox` and boot settings intact. Before complete removal, set **Swap space** to **Off** and uncheck automatic restore in the App, then follow the [Building and Maintenance Guide](building.md).

---

## 4. Ownership Guards & Startup Serialization

- **Strict Path & Ownership Checks**: Swap files use fixed paths under `/data/irisbox/host/swap/` (`0700` directory, `0600` file, `root:root` owner) and verify byte size, device/inode identity, link count, and private receipts (`boot.log` stays private; boot restore is skipped if `engine-upgrade.pending` is present). No file path is ever accepted from ROM packages or UI text input.
- **Shared Startup Lock & Physical Admission Guard**: Swap reconfiguration and guest startup share an exclusive/shared file lock so swap cannot be resized mid-boot. Host/guest `swappiness`, instance ceilings, and the 512 MiB host guard remain unchanged—startup admission still checks physical `MemAvailable` rather than treating disk swap as physical RAM.

---

## 5. Reproducing the *Angry Birds 2* & Dual-Game Test

On October 27, 2026, enabling **2 GiB host swap** allowed a `2048 MiB` guest to run *Angry Birds 2* (`26.7.1`) through its tutorial with real slingshot launches, target destruction, and Red's ability (scoring `93,382` with Guest Root off, `102,356` after restoring Guest Magisk, and **`203,332` tutorial completion** while running alongside *Clash of Clans* with ~1.2–1.3 GiB host `MemAvailable`, ~1.1 GiB swap used, and zero guest OOM-kills):

1. **Recommended Dual-Game Startup Order**: With **2 GiB host swap** active, **start the `2048 MiB` instance 2 first and wait for `Desktop ready`, then start the `1024 MiB` instance 1**. Open the games only after both desktops are ready (launching a heavy game before starting the second instance can reduce physical `MemAvailable` enough for admission control to reject the second boot).
2. **Tutorial Aiming Tip**: In the *Angry Birds 2* tutorial, aiming away from the guided trajectory displays a **red cross** and refuses release—align with the **green guide line** under **Input: touch** to launch normally.
3. **Scope Boundary**: These results establish short tutorial interaction only, not long-term battles, payments, all levels, stable frame rates, or Google Play Protect device certification (see [Google Play Recovery](google-recovery.md)).
