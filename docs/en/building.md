# Building, Maintenance and Recovery Guide

**English** · [简体中文](../04_构建与维护.md) · [Back to README](../../README.md)

---

## 1. Current Version & Upgrade Paths (`v0.1.6 / e27`)

| Installed Version | Supported Upgrade Path to `0.1.6 / e27` |
| :--- | :--- |
| **`0.1.5 / e25`**<br>(Already migrated to IrisBox & UserNS) | **No repeated UID or namespace migration needed**:<br>1. Stop all IrisBox instances normally and keep `out/IrisBox-0.1.5.apk`.<br>2. Run `python tools/upgrade_engine.py --from-apk out/IrisBox-0.1.5.apk --apply`.<br>3. Install the same-signed `IrisBox-0.1.6.apk` (see [Swap Settings and Low Memory](low-memory.md)). |
| **`0.1.4 / e21`** | **Do not merely overwrite the APK**: First complete the stopped-state [0.1.5 Iris Namespace Migration](namespace-migration.md) (`com.irisbox.viewer`, `/data/irisbox`), then upgrade to `0.1.6`. |
| **`0.1.2 / e13`** | **Do not merely overwrite the APK**: First complete the stopped-state [User Namespace Isolation and Migration](userns.md) (`migrate_userns.py`). |

---

## 2. Local Build Environment & Commands

### Toolchain Baseline
- **JDK**: JDK 17
- **Android SDK / NDK**: Android SDK Platform `android-30` (compilation baseline; APK targets API `33`), `build-tools 34.0.0`, NDK `28.2.13676358`
- **Scripting**: PowerShell + Python 3

```powershell
$env:JAVA_TOOL_OPTIONS = '-Xmx256m -Xms16m -XX:+UseSerialGC'

# Full build (generates i18n, builds Guest Browser, native helpers, Java services, engine.zip, and signs APK)
./build.ps1 -SdkDir "$env:LOCALAPPDATA/Android/Sdk" -JdkDir 'C:/path/to/jdk-17'

# UI-only build (valid ONLY when engine payloads and ROM profile are completely unchanged)
./build.ps1 -AppOnly -SdkDir "$env:LOCALAPPDATA/Android/Sdk" -JdkDir 'C:/path/to/jdk-17'
```

### Build Pipeline, Signing Keys & Reproducibility
1. **Engine Versioning**: Any change to runtime scripts, `BufferBroker`, `BufferGateway`, the Guest Browser, or native helpers requires bumping the engine version and running a full build. The installer rejects same-version overwrites with differing hashes.
2. **Dual Private Keystores**:
   - Host Viewer keystore: `app/viewer/build/viewer-test.keystore` (git-ignored, kept locally for updates).
   - Guest Download Browser keystore (`0.1.4+`): `app/guest-browser/build/browser-test.keystore` (git-ignored).
   - A fresh public clone generates new local development keys, so its signed browser APK hash (and thus the outer `engine.zip` hash) will differ from the official release build. To verify reproducibility from a public clone, **compare all non-browser engine payloads byte-for-byte and compare the browser APK's unsigned ZIP entries separately** (see [Guest Download Browser Notes](../../app/guest-browser/README.md)).
3. **Source vs. Verified Prebuilts**:
   - All controllers under `app/` and `engine/` compile locally.
   - Matched AOSP 13 compatibility libraries (`iris_init`, no-BPF netd/networkstats, Connectivity, Mesa/Gralloc, `DocumentsUI.apk`, `libalarm_jni.so`, and `storage/vold`) are verified prebuilts whose patches and SHA-256 provenance live under `reference/`. Building the APK is **not** a from-zero rebuild of AOSP.
   - **Optional Remote Producer Rebuild**: Pass `-RebuildProducer -ProducerHost builder@YOUR_HOST -ProducerKey C:/path/to/key -AospDir /path/to/aosp-rpi4` (or set `IRISBOX_BUILD_HOST`, `IRISBOX_BUILD_KEY`, `IRISBOX_AOSP_DIR`) to compile `engine/native/buffer/buffer_producer.cpp` in an isolated `/tmp/irisbox-producer-*` directory against read-only AOSP headers.
   - **Host Image Reference**: Upstream host sources follow the [Raspberry Vanilla Android 13 Manifest](https://github.com/raspberry-vanilla/android_local_manifest/tree/android-13.0) (`reference/host-profile.json`); factory USB boot configs (`cmdline.txt`, `config.txt`, `fstab.rpi4`) are preserved in `reference/host-boot/`.

---

## 3. Transactional Engine Maintenance & Uninstallation

### Developer Maintenance CLI (`tools/upgrade_engine.py` & `tools/device.py`)
Set `IRISBOX_SERIAL` to your ADB device serial before running maintenance or regression scripts. Do not re-run the one-time initial extraction scripts (`extract_reference.py` / `adapt_engine.py`) over the maintained tree, and keep all Android `.sh` / `.rc` files in `LF` format (the packager rejects `CR` bytes).

```powershell
# Transactional engine upgrade (with all IrisBox guests stopped):
python tools/upgrade_engine.py --from-apk out/IrisBox-0.1.5.apk --apply

# Independent rollback if an interrupted upgrade leaves engine-upgrade.pending:
python tools/upgrade_engine.py --recover
```

### Clean Removal Procedure
Uninstalling the host APK preserves `/data/irisbox` so instance data is not lost. To remove IrisBox completely:
1. In the App, set **Swap space** to **Off** and uncheck automatic reboot restore.
2. Stop and delete all IrisBox instances in the App, then stop the IrisBox manager.
3. Verify that all `irisbox_*` cgroups are empty, instance network tables are released, and read-only ROM loop mounts are unmounted before deleting `/data/irisbox`.
4. **Never** delete `/data/guest` (the previous project), flush shared BPF or host firewall tables, or reset userdata to troubleshoot host compatibility errors.

---

## 4. Build & Audit Milestones Across Releases

| Version | Audited Payloads & Public-Snapshot Reproducibility |
| :--- | :--- |
| **`0.1.2 / e13`** | Added EN/ZH localization and private host BusyBox; independent public-snapshot build matched all **44 engine payloads**. |
| **`0.1.3 / e17`** | Integrated static User namespace launcher and entry helpers; independent public-snapshot build matched all **49 engine payloads**. |
| **`0.1.4 / e21`** | Added in-guest download browser (`com.irisbox.browser`) and live orientation (`orientation.sh`). Passed audits for **51 engine**, 116 ROM, 11 Root, and 54 private Google inputs plus 343 locale entries (public-snapshot build matched all 50 non-browser payloads + unsigned browser entries). |
| **`0.1.5 / e25`** | Migrated to `com.irisbox.viewer` and `/data/irisbox`, added mapped-guest `vold` adapter and camera framework fix. Passed audits for **52 engine payloads** (51 non-browser + unsigned browser entries) and 343 locale entries. |
| **`0.1.6 / e27`** | Added host swap controller (`host_swap.sh`) and Magisk boot hook management. Passed audits for **53 engine payloads** (52 non-browser + 3 unsigned browser entries), 116 ROM, 11 Root, 54 private Google inputs, and **372 locale entries**. |
