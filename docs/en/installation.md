# Installation and host image

[中文](../01_安装与使用.md) · [Downloads](../../DOWNLOADS.md)

## Supported host

Raspberry Pi 4, ARM64, roughly 4 GiB RAM, with this exact matched AOSP 13 fingerprint:

```text
Raspberry/aosp_rpi4/rpi4:13/TQ3A.230901.001/eng.lijiaq.20260916.141216:userdebug/test-keys
```

The kernel needs User/Mount/PID/Network/IPC/UTS/Cgroup namespaces, BinderFS, OverlayFS/ext4, hybrid Android cgroups, BPF socket permission support and the matched GPU stack. User namespace creation is mandatory in 0.1.3, with no Host-UID0 fallback. Connect host Wi-Fi. The preview requires an already configured Permissive environment and host MagiskSU. The App checks capabilities; it does not change SELinux policy or flash partitions. Its verified BusyBox is installed under `/data/irisbox/host/bin`, so a globally installed BusyBox is unnecessary.

## App workflow

Install the APK, open IrisBox, approve its normal host Magisk request, select **Authorize and check**, then **Install engine**. Use **Import ROM** and Android's file picker to select the AOSP `.irisrom` file. Allow at least 8 GiB free space for staging, the ROM library and initial data. Installation/import progress uses a foreground notification; avoid force stopping the App or disconnecting power during copying.

Open **Manage instances**, create an instance, start it and wait for **Desktop ready** before entering. Optional guest Root has its own switch and authorization records. It does not install host Magisk. The public AOSP ROM has no Google files; Google-enabled imports require separately supplied matching components which you have permission to use.

Existing 0.1.2/e13 data requires a maintenance migration before starting with 0.1.3; follow the [stopped-state backup and migration guide](userns.md). Installing the APK alone does not migrate data. On this host, the retained 768 MiB ordinary instance needed about 175 seconds for its first mapped boot, while the 1536 MiB Google instance needed about 78 seconds; allow the first boot to finish.

Google account retention does not establish device certification. Each guest has its own GSF ID; registering the host or an older project's instance does not register a new IrisBox guest. Google's [custom ROM registration page](https://www.google.com/android/uncertified/) describes the official procedure. Keep the ID private and complete login/CAPTCHA yourself. Registration is not manufacturer certification or a Play Integrity guarantee; the current validation record lists any remaining store restriction.

Ordinary instances default to 1280 MiB; Google instances default to 1536 MiB. Memory settings apply while stopped. On the tested 4 GiB host, start the 1536 MiB Google instance before the smaller ordinary instance. Admission control preserves running instances when available memory is insufficient. Configurable limits are not universal workload recommendations.

Closing the App preserves guests. Reopening restores the manager. After a host reboot, open the App and start guests manually. Importing another ROM changes the default for new instances; existing instances keep their recorded ROM. Reset/delete clear the selected instance data, with confirmation; neither is a host troubleshooting step.

The local 0.1.4/e21 candidate adds live orientation selection. Inside a running instance, choose **Orientation → Follow app / Landscape / Portrait** from the top toolbar. Each instance saves its selection and reapplies it on its next boot, without restarting for a live change. The Host window stays landscape and centers portrait content. Apps with fixed layouts can still have unused space; forcing a direction does not add arbitrary aspect-ratio support to an app. See the download page for the published version.

## Clean flash preview

The `.img.xz` is a whole-disk host image, distinct from an importable `.irisrom`. It was assembled from the original factory MBR/boot/system/vendor and **new empty ext4 userdata**. It was not exported from the active Pi SSD. All partition hashes, filesystem checks and the XZ hash are recorded in the release metadata.

Verify SHA-256, extract XZ and select the image in Raspberry Pi Imager's custom-image workflow or an equivalent disk writer. Use a spare USB disk, preferably 16 GB or larger. Writing replaces the selected medium; verify its identity and capacity first. Keep the current disk containing previous projects and accounts untouched.

**A fresh USB-disk flash and first boot have not been tested.** Partition expansion, installing host Magisk on this fresh image, and first installation of the INET dispatcher also remain unverified. Existing-host tests do not substitute for that acceptance test.

Host Magisk is installed by the user. Follow the [official instructions](https://topjohnwu.github.io/Magisk/install.html) and the Pi-specific boot layout: the factory `boot.img` is a FAT partition containing `Image` and `ramdisk.img`, rather than a phone-style Android boot image. Do not apply phone partition-writing commands to this disk or to a guest ROM. The release does not claim a newly tested host-root installation procedure.

The factory fstab is explicitly pinned to `/dev/block/sda2`, `sda3` and `sda4`. This is a **USB-boot image, not an SD/mmc image**. The Pi must already support USB mass-storage boot; see the [official Pi boot documentation](https://www.raspberrypi.com/documentation/computers/raspberry-pi.html#usb-mass-storage-boot). IrisBox does not change EEPROM/boot order.

The original factory userdata was only about 4.6 GiB, too small for compressed ROM plus staging and installation. The release image therefore keeps the original boot/system/vendor partitions but enlarges the newly formatted empty userdata to **12 GiB**, preserving filesystem feature flags. Total raw image size is **15,436,087,296 bytes**; its initial import no longer depends on partition expansion. Physical first boot remains untested.

For this preview, connect only the intended USB boot disk and ensure it enumerates as sda. Multiple disks or mixed SD/USB boot require a separate adapted fstab and validation.
