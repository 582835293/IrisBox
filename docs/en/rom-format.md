# IrisBox ROM Package Specification (v1)

**English** · [简体中文](../03_ROM规范.md) · [Back to README](../../README.md)

---

## 1. Archive Layout & Format Rules

An `.irisrom` package is a standard `ZIP` / `ZIP64` archive with **UTF-8 `rom.json` as its very first entry** and **no top-level wrapper directory**. System partitions (`system.img` and `vendor.img`) must be **raw `ext4` images**; Android sparse images, OTA packages, `super.img`, and `boot.img` are rejected.

```text
rom.json                    # Must be entry #1: UTF-8 manifest with exact sizes and SHA-256 digests
system.img                  # Raw ext4 system image
vendor.img                  # Raw ext4 vendor image
compat/iris_init            # Adapted container init binary
compat/guest.rc             # Core guest init configuration
compat/guest_vendor.rc      # Guest vendor init configuration
compat/loaded_files.txt     # Whitelist of stock RC files
compat/rc_candidate/...     # Audited stock RC files
compat/payload/...          # Matched graphics, network, and DocumentsUI payloads
compat/google/...           # [Optional] Static 54-file Google layer (no user accounts)
```

---

## 2. Manifest Schema & Validation Limits

Every file in the archive must be declared in `rom.json` with its exact byte size and 64-character lowercase hexadecimal SHA-256 digest:

```json
{
  "formatVersion": 1,
  "id": "rpi4-a13-20260916-aosp",
  "name": "IrisBox Raspberry Pi 4 Android 13 AOSP",
  "abi": "arm64-v8a",
  "androidApi": 33,
  "compatibility": "rpi4-a13-v1",
  "hostFingerprint": "Raspberry/aosp_rpi4/rpi4:13/TQ3A.230901.001/eng.lijiaq.20260916.141216:userdebug/test-keys",
  "google": false,
  "files": {
    "system.img": {
      "size": 2147483648,
      "sha256": "<64-char-lowercase-hex-sha256>"
    }
  }
}
```

| Validation Rule | Enforced Limit |
| :--- | :--- |
| **ROM ID Syntax** | Must match `[a-z0-9][a-z0-9._-]{0,63}` and must not contain `..`. |
| **Archive Path Safety** | Restricted ASCII paths only; rejects absolute paths, backslashes `\`, empty/`.`/`..` segments, symlinks, directory entries, duplicates, missing files, and undeclared files. |
| **Size & Entry Ceilings** | Max **512 files**; max **128 KiB** manifest; max **4 GiB** per file; max **6 GiB** total extracted size. |
| **No Script Execution** | The installer creates regular files only and **never executes scripts from a ROM package**. |

---

## 3. Compatibility Profile Pinning & Packaging

1. **Pinned APK Profile**: Critical `compat/` files are verified against the SHA-256 profile embedded in the host APK (`assets/rom-profile.json`). After App-side extraction and verification, files are copied to a root staging directory, re-verified, labeled, `sync`ed, and atomically registered. Re-importing the same ROM ID with different content is refused. Each instance permanently binds its creation ROM ID (`config/rom_v1/ID.id`); importing a new ROM only updates the default for future instances.
2. **Building an `.irisrom` Package**:
   ```powershell
   python tools/make_rom.py `
     --system path/to/system.img `
     --vendor path/to/vendor.img `
     --id rpi4-a13-20260916-aosp `
     --name "IrisBox Raspberry Pi 4 Android 13 AOSP" `
     --out out/IrisBox-rpi4-a13-aosp.irisrom
   ```
3. **Optional Google Layer & Guest Root**:
   - The public ROM omits `--with-google`. A private Google-enabled package requires the separately licensed 54-file static tree (`compat/google/`) and **must use a distinct `--id`**. It must never include user accounts, running userdata, or host controllers.
   - Guest Root (Magisk 30.7) is bundled in the IrisBox engine rather than the ROM package and is toggled per instance.
