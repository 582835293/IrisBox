# Mapped-guest storage / 映射实例存储

The matched Linux kernel rejects project-quota ID/inheritance changes outside its initial User namespace, even with a populated `projid_map`. Stock Android 13 vold treats that failure as an app-directory setup failure. See the [kernel check](https://github.com/torvalds/linux/blob/v5.15/fs/ioctl.c) and [AOSP vold](https://android.googlesource.com/platform/system/vold/+/refs/tags/android-13.0.0_r75/Utils.cpp).

The retained patch skips **only project quota tagging/inheritance** when vold runs as Guest UID0 with the exact IrisBox mapped range. `fs_prepare_dir`, UID/GID ownership, modes, default ACLs, FUSE/scoped storage and package checks stay active. Host root and unknown mappings follow the original code. Guest per-app project disk quotas are not enforced on this profile; UID isolation and memory ceilings are separate mechanisms. Do not run this binary as a Host replacement.

`reference/storage-build.json` records original/patched source and binary SHA-256. The engine contains the matched ARM64 `storage/vold`; startup copies it only into the Guest's overlay. AOSP/Host partitions and the previous project are not modified. Apache-2.0 AOSP notices remain applicable; the patch is in `reference/patches/vold-userns.patch`.

To reproduce, copy `build_direct.py` to a new `/tmp/irisbox-storage-<unique>` directory on your matched AOSP build machine, then run:

```sh
python3 /tmp/irisbox-storage-UNIQUE/build_direct.py /path/to/aosp-rpi4 /tmp/irisbox-storage-UNIQUE
```

This streams the existing Ninja rules without running a Ninja build, compiles only `Utils.cpp`, copies/replaces its member in a private `libvold.a`, relinks using read-only cached dependencies and strips the output. Results and the exact generated patch are under that temporary directory's `build/`. Compare hashes before replacing the engine input; bump the engine version and perform a full APK build. This does not claim a from-zero AOSP rebuild.

中文：项目 ID 映射本身不足以解除内核对项目配额修改的限制。本适配仅在严格匹配的实例身份中跳过项目配额标签/继承，不忽略目录、属主、权限或 ACL 错误，也不回退到宿主 UID0。因此当前 profile 不提供实例内按应用的项目磁盘配额。二进制只覆盖实例写层中的 vold；宿主与旧工程保持原样。复编使用匹配 AOSP 的只读缓存及独立临时目录，源码、补丁和二进制摘要均有记录。
