# Downloads & Checksums · IrisBox

**English** · [简体中文](DOWNLOADS.zh-CN.md) · [Back to README](README.md)

> [!WARNING]
> **Important Notes Before Downloading**
> - **Format Distinction**: Import `.irisrom` inside the IrisBox App. The `.img.xz` file is a whole-disk host image for a spare **16 GB or larger USB disk** (`USB / sda` boot only, **not SD / `mmc`**, and physical first boot is untested). These two formats are not interchangeable.
> - **Prerequisites**: Install host Magisk yourself. The public ROM excludes Google apps and accounts.

---

## Release Artifacts & SHA-256 Digests

| File | Purpose | Size | SHA-256 |
| :--- | :--- | ---: | :--- |
| [IrisBox-0.1.6.apk](https://github.com/582835293/IrisBox/releases/download/v0.1.6-preview/IrisBox-0.1.6.apk) | Host manager, engine installer, and fullscreen viewer App (`v0.1.6 / e27`) | 17.1 MiB | `2806b9db48c0e99cd76762adf35d58a0450fc892ff9aaa2cd9af6dc3ec74d053` |
| [IrisBox-rpi4-a13-aosp.irisrom](https://github.com/582835293/IrisBox/releases/download/v0.1.4-preview/IrisBox-rpi4-a13-aosp.irisrom) | Importable Raspberry Pi 4 AOSP 13 guest ROM package (no Google apps) | 668.0 MiB | `04d586a15459dec7fca963b37450b6b5cc722d3fe9477f58eaa3b5a5411ef629` |
| [IrisBox-host-aosp13-rpi4-20260916-usb-16gb-preview.img.xz](https://github.com/582835293/IrisBox/releases/download/v0.1.4-preview/IrisBox-host-aosp13-rpi4-20260916-usb-16gb-preview.img.xz) | Clean whole-disk host image for a 16 GB+ spare USB disk (12 GiB blank `userdata`) | 514.1 MiB | `e13998996c47136373a0696f9d6424c7129888a91ee7804165f2681508a0fac2` |
| [IrisBox-host-aosp13-rpi4-20260916-usb-16gb-preview.json](https://github.com/582835293/IrisBox/releases/download/v0.1.4-preview/IrisBox-host-aosp13-rpi4-20260916-usb-16gb-preview.json) | Static partition and filesystem verification metadata for the clean host image | 0.0 MiB | `7e6d474d4e9b44c83f0f76109f8589e46db5ffc2ad1b6b5860affd13ae848717` |
| [IrisBox-guest-root-sources.zip](https://github.com/582835293/IrisBox/releases/download/v0.1.4-preview/IrisBox-guest-root-sources.zip) | Guest Root (Magisk 30.7) and BusyBox sources, licenses, and adaptation patches | 13.0 MiB | `d3125503a65f67b839c371d7b534f68a96747e0c930fe3333a6ca04f4e96e272` |
| [IrisBox-upstream-sources.zip](https://github.com/582835293/IrisBox/releases/download/v0.1.4-preview/IrisBox-upstream-sources.zip) | Pinned upstream Linux kernel, Magisk, BusyBox, and dependency source archive | 205.9 MiB | `9da344cad78f0a747ac7dcd14d0db94c541dff933c1ccc33de9f68af3449e682` |

**Verification Manifests**: [SHA256SUMS](https://github.com/582835293/IrisBox/releases/download/v0.1.6-preview/SHA256SUMS) · [release-manifest.json](https://github.com/582835293/IrisBox/releases/download/v0.1.6-preview/release-manifest.json)

---

## Drive Mirrors & Distribution Policy

- **GitHub Releases**: All current public assets are available on [GitHub Releases](https://github.com/582835293/IrisBox/releases); direct download links are recorded only after public assets pass server size and SHA-256 checks.
- **External Mirrors**: Google Drive (international) and Quark Drive (China) may provide supplemental mirrors in the future (no share links are published yet). Every mirror must match `SHA256SUMS` byte-for-byte.
- **Exclusions**: Google apps, user accounts, and private instance data are excluded from all public distributions. See [Installation and Flash Boundaries](docs/en/installation.md) for detailed instructions.
