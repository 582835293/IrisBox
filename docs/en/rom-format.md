# ROM package v1

[中文](../03_ROM规范.md)

An `.irisrom` is ZIP/ZIP64, with UTF-8 `rom.json` as its first entry and no enclosing directory. It contains raw ext4 `system.img`, `vendor.img` and matched `compat/` files. Android sparse images, OTA, super and boot images are not accepted.

Required manifest fields include `formatVersion:1`, a unique ID, name, `abi:"arm64-v8a"`, `androidApi:33`, `compatibility:"rpi4-a13-v1"`, the matched `hostFingerprint`, `google` and `files`. Every file has its actual byte size and lowercase SHA-256. `compat/` includes the adapted init, audited RC files, graphics/network libraries and DocumentsUI. These files target this exact build, not arbitrary Android 13.

IDs match `[a-z0-9][a-z0-9._-]{0,63}` without `..`. Paths use restricted ASCII characters and reject absolute paths, backslashes, empty/dot/parent segments, links, directory entries, duplicates, missing and undeclared files. Limits: 512 files, 128 KiB manifest, 4 GiB per file and 6 GiB extracted ROM. The installer creates regular files; it does not execute scripts supplied by the package.

Critical compatibility files must match the APK's pinned profile. After App-side extraction and verification, files move to a root staging directory, are verified again, labeled, synchronized and atomically registered. Same ID/different content is refused. Each instance records its ROM ID permanently; changing the default does not migrate existing upper layers.

```powershell
python tools/make_rom.py --system path/to/system.img --vendor path/to/vendor.img --id rpi4-a13-20260916-aosp --name "IrisBox Raspberry Pi 4 Android 13 AOSP" --out out/IrisBox-rpi4-a13-aosp.irisrom
```

The public package omits `--with-google`. A private Google-enabled build additionally needs the exact separately licensed static component tree and a **different ROM ID**. It must never contain accounts, running instance data or host controllers. Guest Magisk belongs to the engine and is optional per instance.
