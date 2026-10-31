# 0.1.5: Iris Namespace Migration and Recovery

**English** · [简体中文](../11_Iris命名迁移.md) · [Back to README](../../README.md)

---

## 1. Scope of the `0.1.5 / e25` Migration

Version `0.1.5 / e25` unifies all product-owned package names, device directories, native helpers, audio services, sockets, and cgroup resource groups under the **IrisBox (`Iris`)** namespace:

- **Host Package Name**: `com.irisbox.viewer` (bundled guest download browser: `com.irisbox.browser`)
- **Device Root Directory**: `/data/irisbox` (`/data/irisbox/runtime`)
- **Preserved State**: Manager port `19762`, instance numbers, userdata `revision`s, ROM bindings, User namespace UID/GID ranges (`2000000000+`), Google accounts, and third-party app/game data.
- **Shared Exception**: The verified root-cgroup BPF socket dispatcher (`/sys/fs/bpf/iris_inet_v1`) continues to be shared safely; its path and other projects' tables are never moved or deleted.

> [!WARNING]
> **Existing `0.1.4 / e21` Installations Must Migrate While Stopped**
> - Because the package name changes to `com.irisbox.viewer`, Android treats it as a new application requiring a normal **host Magisk root grant**.
> - **Do not** merely install the new APK without migrating, **do not** copy Magisk grant databases manually, and **do not** clear Google services or recreate instances. (Fresh installations simply follow the [Installation Guide](installation.md).)

---

## 2. Maintainer Stopped-State Migration Procedure

`tools/migrate_namespace.py` supports only the verified **`0.1.4 / e21 → 0.1.5 / e25`** transition. Retain the exact installed `IrisBox-0.1.4.apk`, private signing keys, and sufficient free disk space before starting (the separate previous `Project Iris` remains read-only reference material and is never used as a migration source):

1. **Stop All Instances**:
   Normally stop every instance in the old App, wait for display, network, and lifecycle cleanup to finish, and close the old App UI.
2. **Select Your Device & Verify Preconditions**:
   Set `IRISBOX_SERIAL` to your ADB serial. The script reads the previous package and root path from `--from-apk` and verifies installed APK bytes, every engine payload SHA-256, `PID:start_tick`, empty resource groups, network ownership, and read-only lower mounts.
3. **Run Dry-Run Check, Then Apply**:
   ```powershell
   # 1. Dry-run verification (no changes written)
   python tools/migrate_namespace.py `
     --from-apk out/IrisBox-0.1.4.apk `
     --to-apk out/IrisBox-0.1.5.apk

   # 2. Stopped-state full backup and atomic namespace migration
   python tools/migrate_namespace.py `
     --from-apk out/IrisBox-0.1.4.apk `
     --to-apk out/IrisBox-0.1.5.apk `
     --apply
   ```
4. **What the Transaction Performs**:
   - Creates independent stopped copies (distinct inodes, not hard links) of each instance's `data`, `upper`, `vendor.upper`, and `metadata` with per-file SHA-256 verification, plus configuration and engine rollback materials.
   - Releases verified owned read-only lower mounts, disables the old entry point, installs the `0.1.5` trusted engine, and moves the product root to `/data/irisbox` (retiring stale display pointers and audio paths into the private backup).
5. **Install the New APK & Authorize Host Root**:
   Install `IrisBox-0.1.5.apk` (or a same-signed `0.1.6` APK), open **1 · Authorize and check**, approve the standard host Magisk prompt, and start your retained instances. Retire/uninstall the old APK only after verifying userdata, accounts, display, input, networking, and isolation.

*Note: Private transaction receipts are saved to `out/maintenance/namespace-transition.json` and on-device snapshots reside under `/data/irisbox/maintenance/` (never publish receipts, account fingerprints, or raw logs; on-device snapshots do not replace external backups). The bundled browser's package change (`com.irisbox.browser`) means its old private `DownloadManager` history stays in the snapshot, while third-party Google/game packages and UIDs remain unchanged.*

---

## 3. Rollback & Recovery Rules

- **Never Force Startup If `engine-upgrade.pending` Exists**: Preserve the receipt and console output, verify all guests and display processes are stopped, and inspect the recorded `recover.sh`.
- **Automatic Transaction Rollback**: During initial device testing, a sourced script overwrote a short variable name; the transaction automatically rolled back with all userdata intact, and dedicated variables plus subshell checks resolved the issue on the next run.
- **Scope of `recover.sh`**: The generated recovery script applies strictly to that **stopped directory/engine transaction**, not arbitrary later userdata writes. If the old APK was uninstalled, reinstall the exact old APK before restoring its entry point and grant.

---

## 4. Validated Results

Both retained instances migrated with their IDs, `revision`s, and UID/GID ranges intact. Instance 2 retained its private Google account fingerprint and GSF ID and installed additional Play Store apps (`Clash of Clans`, `Angry Birds 2`, `YouTube`) after restart. Normal host Magisk authorization, EN/ZH language persistence, and fullscreen game input passed (see the [Validation Report](validation.md)).
