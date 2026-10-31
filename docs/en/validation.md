# Validation Report and Known Boundaries (`v0.1.0` – `v0.1.6`)

**English** · [简体中文验收记录](../06_验收记录.md) · [登录后回归](../07_登录后回归与修复.md) · [Back to README](../../README.md)

> [!IMPORTANT]
> **Validation & Reporting Principles**
> - This report strictly separates **verified on-device results**, **retained intermediate failures and fixes**, and **unverified or unmet boundaries**.
> - All device tests ran on the matched **Raspberry Pi 4 (AOSP 13 / ARM64 / ~4 GiB RAM)** host.
> - The previous reference project (`Project Iris V3.0`) was stopped normally with explicit owner authorization on 2026-10-15 and remains read-only with its configuration and data intact. Raw logs, private keyed account HMACs, and uncropped captures remain in git-ignored `validation/private/`.

---

## Contents

1. [2026-10-27: `v0.1.6 / e27` Host Swap Settings & Simultaneous Heavy Games (Latest)](#1-2026-10-27-v016--e27-host-swap-settings--simultaneous-heavy-games-latest)
2. [2026-10-26–27: `v0.1.5 / e25` Iris Namespace Migration, Storage/Camera Fixes & App Compatibility](#2-2026-10-2627-v015--e25-iris-namespace-migration-storagecamera-fixes--app-compatibility)
3. [2026-10-24: `v0.1.4 / e18–e21` In-Guest Downloads, Xiangqi QR Play, Live Orientation & Play Recovery](#3-2026-10-24-v014--e18e21-in-guest-downloads-xiangqi-qr-play-live-orientation--play-recovery)
4. [2026-10-21: `v0.1.3 / e17` User Namespace Identity Isolation Regression](#4-2026-10-21-v013--e17-user-namespace-identity-isolation-regression)
5. [2026-10-15: `v0.1.0 / e2` – `v0.1.2 / e13` Initial Delivery, Post-Login Media & Localization](#5-2026-10-15-v010--e2--v012--e13-initial-delivery-post-login-media--localization)

---

## 1. 2026-10-27: `v0.1.6 / e27` Host Swap Settings & Simultaneous Heavy Games (Latest)

### Host Swap Controller & Reboot Restoration
- **Feature Scope**: Added the **Swap space** screen (`Off` / `1 GiB` / `2 GiB` / `4 GiB`), live capacity/RAM/disk status reporting, and optional host-Magisk reboot restoration (`0.1.6`, `versionCode 120`, engine `e27`).
- **On-Device State Transitions**:
  - Exercised `1 GiB → 4 GiB → Off → 2 GiB (with auto-restore)` through the real App UI.
  - **Real Host Reboot**: Rebooted the Raspberry Pi 4 and verified that **2 GiB swap restored automatically on boot** without opening the App or running computer commands.
  - **Safe Deactivation & Inode Reuse**: Verified `Auto-enabled → Off` deactivates swap and removes only `/data/adb/service.d/irisbox-swap.sh`; re-enabling the same size reuses the existing file and inode without reformatting. Invalid sizes/flags, symlinks, foreign same-name boot hooks, and active-guest changes were all rejected.
  - **`e26 → e27` Lock FD Fix**: Candidate `e26` safely rejected configuration because Android `mksh` marks extra file descriptors `close-on-exec`; `e27` explicitly passes the lock FD and closes it in long-lived children.
- **Build & Audit**: Independent public-source full build matched **52 non-browser payloads** and **3 unsigned browser entries**; `53 / 116 / 11 / 54` input digests and **372 locale entries** passed. Updating the final APK preserved both running guest sessions.

### Dual Heavy-Game Validation & Boundaries

![Actual simultaneously running games](../images/dual-games.png)

| Dimension | Measured Result & Explicit Boundary |
| :--- | :--- |
| **Test Configuration** | Single 4 GB Raspberry Pi 4 with **2 GiB host swap**; Guest 1 at `1024 MiB` (no Google/Root), Guest 2 at `2048 MiB` (retained Google/Magisk). Not a claim of two 2 GB guests or dual high-fps fullscreen windows. |
| **Clash of Clans `18.647.5`** | Guest 1 installed the 5 on-device, Play-downloaded official split APKs via its `PackageManager` (no cloned account/save or claimed second Play download). Village rendered and real Viewer clicks advanced tutorial dialogue; after a normal idle disconnect, the built-in `Reload Game` flow reconnected prior to the final dual capture. **Long battles, clan wars, and payments are untested.** |
| **Angry Birds 2 `26.7.1`** | Running simultaneously alongside Clash of Clans, real slingshot launches, target destruction, second-room progression, and Red's ability completed the tutorial at **203,332 points** (off-guide angles show a red tutorial cross; aligning with the green guide under `Input: touch` launched normally). |
| **Continuous Observation** | 24 samples over `~146.8s` kept both guests ready; minimum host `MemAvailable` was `809,104 KiB` (`~790.1 MiB`), both `OOM-kill` counters stayed `0`, and both preview sequences advanced. Not a long-run, all-level, or frame-rate guarantee. |
| **Retention & Isolation** | Account fingerprint, guest `revision`s, and both User namespace identities passed re-verification with no Host-UID0 fallback; the legacy project remained stopped and unchanged across host reboot. |

---

## 2. 2026-10-26–27: `v0.1.5 / e25` Iris Namespace Migration, Storage/Camera Fixes & App Compatibility

### Package & Directory Namespace Migration
- Migrated host package to `com.irisbox.viewer`, runtime root to `/data/irisbox`, bundled browser/QA apps to `com.irisbox.*`, and native helpers/services/sockets/cgroups to Iris names while keeping port `19762`, instance IDs, `revision`s, and UID/GID ranges (`2000000000+`).
- Independent stopped-state per-file SHA-256 backups preceded migration. An initial short-variable collision rolled back cleanly with userdata intact; dedicated transaction variables and retirement of the stopped display pointer succeeded on retry.

### Two Compatibility Defects Found & Fixed
1. **YouTube Startup Camera Query ANR (`e24`)**: Replacing framework `cameraserver` with `true` caused YouTube to hang waiting for camera count until ANR. Restoring the stock framework `cameraserver` while keeping physical camera providers disabled properly returns `0` cameras and allows YouTube playback.
2. **Mapped-Guest External Storage Project Quotas (`e25`)**: The matched Linux 5.15 kernel forbids non-initial User namespaces from modifying ext4 project quota flags even when `projid_map` is populated. The `e25` matched `vold` adapter (`engine/native/storage/`) skips **only** project-quota tagging/inheritance for strictly verified IrisBox mapped guests while preserving directory, ownership, mode, ACL, and FUSE checks. Ordinary-app external directory creation and read/write now pass (**boundary: per-app project disk quotas are not enforced on this profile**).

### `v0.1.5 / e25` App Results & *Angry Birds 2* Pre-Swap Baseline

| Test | Observed Result & Scope |
| :--- | :--- |
| **Google Play Downloads** | Installed *Clash of Clans*, *Angry Birds 2*, and *YouTube* directly inside Guest 2 (`installer=com.android.vending`); account fingerprint, GSF ID, and `revision` retained. **Play Protect device certification has still not passed.** |
| **Clash of Clans `18.647.5`** | Tutorial village rendered and real Viewer input advanced dialogue after normal idle-timeout reload. |
| **YouTube `21.42.243`** | Played Blender Foundation's official *Big Buck Bunny* with playback advancing from `1:35` to `3:02`, fullscreen rendering, and non-zero PCM audio (not a 4K/DRM claim). |
| **Angry Birds 2 `26.7.1` (Before Host Swap)** | With `SwapTotal=0` in `0.1.5`, clearing only `com.rovio.baba` (after private backup) reached the `Main` scene but stopped at `"You are offline / Please go online to complete the update"` (below), and a Root-off control run was stopped at 410s by the 512 MiB host memory guard (`OOM-kill=0`). Retained as **not passed in `0.1.5`** until `0.1.6` added 2 GiB host swap. |
| **Languages & Dual Regression** | 343 locale resources audited; English/Chinese selection persisted across App restarts; simultaneous `768 MiB` + `2048 MiB` guests passed strict UserNS, network, file, and audio regression. |

![Angry Birds 2 update remains offline; gameplay has not passed](../images/angry-offline.png)

---

## 3. 2026-10-24: `v0.1.4 / e18–e21` In-Guest Downloads, Xiangqi QR Play, Live Orientation & Play Recovery

- **In-Guest Download Browser (`com.irisbox.browser`, `e18 → e20`)**:
  - Added an ordinary in-guest browser using Android `DownloadManager` and `PackageInstaller` (`e19` preserved `.apk` filenames under `application/octet-stream`; `e20` refreshed parent directory timestamps so `PackageManager` updates system-app metadata).
  - **Via `7.3.3` (Passed)**: Downloaded `2,628,334 bytes` from the official site inside Guest 2, installed via system UI (`UID 10094`), and browsed normally.
  - **Tencent AppBao `9.2.9` (Passed)**: Downloaded `42,202,135 bytes` via Via in Guest 2, installed through the standard system prompt, and loaded its online home page.
  - **Standalone QQ `9.3.75` (Installed; Login Abandoned Due to RAM Guard)**: Downloaded `~374.82 MB` and installed, but heavy DEX compilation and AppBao keepalive processes triggered the 512 MiB host memory guard (`SwapTotal=0` at that time). QQ and AppBao were disabled (`pm disable-user`) with data retained.
  - **Tiantian Xiangqi `4.4.6.2` (Passed)**: Downloaded `198,465,378 bytes` directly inside Guest 2 via Via (MD5 `99B32B44E4176F9157EA72B34A2E0758` matched official HTTPS metadata), installed via system UI (`UID 10097`), and displayed its native QR login screen. **The owner subsequently confirmed both WeChat QR login/gameplay and QQ QR login/gameplay.**
- **Live Orientation (`e21`, Passed)**:
  - Audited 51 engine, 116 ROM, 11 Root, and 54 private Google inputs plus 343 locale entries. Live toolbar switching between landscape (`960×540`) and portrait (`540×960`) preserved the session, counters, GPU rendering, and keyboard input (`Iris9L` / `Iris8P`), and persisted across Guest restart.
- **Google Play Store Recovery vs. Unmet Certification**:
  - Clearing only temporary GMS/Store caches (`pm clear --cache-only`) followed by a standard check-in restored the Play Store catalog and installed **Open Sudoku `4.11.1`** (`UID 10098`).
  - After an explicitly authorized GMS service-data rebuild and restoration of all 11 original split APKs (`26.39.33`) plus Guest Magisk, Play installed and opened **Google Calculator `9.3`** (`UID 10101`, `installer=com.android.vending`) without re-login.
  - **Unmet Requirement**: `Settings → About → Play Protect certification` still displays **`Device is not certified`** (the official 54,261-row certified device list contains no `Raspberry` / `rpi4` / `aosp_rpi4` entry, and the build uses `userdebug/test-keys`). See [Google Play Recovery](google-recovery.md).

---

## 4. 2026-10-21: `v0.1.3 / e17` User Namespace Identity Isolation Regression

- **Strict UID/GID Mapping**: Guest `root (0)` maps to Host `2000000000` (Guest 1) and `2000100000` (Guest 2), with `100,000` UIDs/GIDs per domain and **no Host `UID 0` mapping**. Verified all six non-user namespace owners via `NS_GET_USERNS`, stale-tick rejection, and denial of Host-`UID 0` or cross-instance `0600` test files.
- **Migration & Boot Timings**: Independent stopped-state backups matched per-file SHA-256 digests before migration. First mapped boots took `174,836 ms` (`768 MiB` Guest 1) and `78,097 ms` (`1536 MiB` Guest 2); subsequent boots took `100,613 ms` and `67,905 ms`. A fresh `1024 MiB` pure-AOSP+Root QA instance booted in `86,483 ms` and passed all media/network checks before being reset and deleted.
- **Full Subsystem Regression**: Both instances passed fullscreen FD handoff, mixed-case keyboard input (`Iris42Z`), swipe, ordinary-UID HTTPS/DNS/GPU/PCM, `640×360` H.264 encode/playback, retained Magisk `su`, 51-byte file roundtrips, 10 JPEG previews each, 403 bearer rejection, and `21.44s` audio-helper recovery. All 49 engine payloads matched in an independent sanitized public-snapshot build.

---

## 5. 2026-10-15: `v0.1.0 / e2` – `v0.1.2 / e13` Initial Delivery, Post-Login Media & Localization

- **`v0.1.0 / e2` Initial Acceptance & Host Reboot**:
  - Passed all 11 on-device `PackageArchive` security tests (valid archive, bad hash, missing file, duplicate, undeclared entry, oversize, wrong profile, path traversal, absolute path, wrong first entry, manifest bomb).
  - Verified dual instances (`1007` at `768 MiB`, `1008` at `1024 MiB`), ordinary-UID HTTPS 200, independent counters (`1` and `2`), 51-byte file exchange, temporary `1280 MiB` Google+Magisk instance `1009`, and full recovery across a **real Raspberry Pi physical reboot** (`boot_id` change verified).
- **`v0.1.1 / e9` Post-Login Regression**:
  - Added the 48 kHz PCM audio loopback bridge (`AudioBridge`), restored `mediaextractor` and software H.264 video decoding, masked `Traceur` via pre-init private `tmpfs`, and fixed single-frame preview exit destructors (`_exit(0)`).
  - Verified retained Google login, Play Store downloads of **Chrome** and **Simon Tatham's Puzzles**, 120 consecutive JPEG previews without crash-buffer errors, `11.86s` audio recovery after helper termination, and memory admission protection across host reboot.
- **`v0.1.2 / e13` Localization & Public Snapshot**:
  - Imported the public Google-free `IrisBox-rpi4-a13-aosp.irisrom` via the system picker, booted a clean `1024 MiB` AOSP+Root guest in `64,128 ms`, audited 334 EN/ZH resource keys and 44 engine payloads, and statically validated the 12 GiB blank-`userdata` clean host `.img.xz` preview image (**physical USB flash and first boot remain untested**).
