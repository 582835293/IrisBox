# 映射实例存储适配 · Mapped-Guest Storage (`vold`)

## 1. 问题背景（Linux 5.15 内核限制）

在启用了独立 User Namespace（`0.1.3+`）的实例中，即使已配置 `projid_map`，树莓派 4 匹配的 Linux 5.15 内核仍禁止非初始 User Namespace（`!init_user_ns`）通过 `FS_IOC_FSSETXATTR` 修改 ext4/F2FS 的项目配额 ID（Project Quota ID）及 `FS_XFLAG_PROJINHERIT` 继承标志（参见 [Linux 5.15 `fs/ioctl.c`](https://github.com/torvalds/linux/blob/v5.15/fs/ioctl.c)）。

而原厂 AOSP 13 的 `vold` 在为普通应用准备外部存储目录（如 `getExternalFilesDir` → `StorageManager` → `vold::PrepareAppDirFromRoot` → `SetQuotaProjectId`）时，会将该项目配额设置失败视为致命错误（参见 [AOSP `system/vold/Utils.cpp`](https://android.googlesource.com/platform/system/vold/+/refs/tags/android-13.0.0_r75/Utils.cpp)），导致普通应用无法创建或写入外部存储目录。

---

## 2. 适配方案与安全边界（`v0.1.5 / e25`）

- **精确跳过不支持的配额标记**：保留在 `reference/patches/vold-userns.patch` 中的补丁仅在 `vold` 运行于 **Guest `UID 0` 且内核映射严格匹配 IrisBox 专有 UID 范围**时，跳过项目配额 ID 标记与继承设置。
- **完整保留所有权限检查**：`fs_prepare_dir`、目录创建、UID/GID 属主设置、权限模式（mode）、默认 POSIX ACL、FUSE / 分区存储（Scoped Storage）及包名校验均原样保留；若在宿主 Root 或未知映射下运行，则完全执行原厂代码逻辑。
- **明确边界**：当前适配 Profile **不提供实例内按应用的项目磁盘配额（Per-App Project Disk Quota）**；实例间的 UID 隔离与内存上限属于独立机制。该二进制文件仅在实例启动时覆盖至实例私有 Overlay 写层（`engine/runtime/storage/vold`），**绝不替换宿主 `/system/bin/vold` 或修改旧工程**。

---

## 3. 独立复编步骤与来源核验

`reference/storage-build.json` 记录了原始源码、补丁后源码及最终 ARM64 二进制的 SHA-256 摘要（遵循 AOSP `Apache-2.0` 许可证）。

如需在匹配的 AOSP 13 编译机上复现构建，可将 `build_direct.py` 复制到编译机上的独立临时目录（如 `/tmp/irisbox-storage-<unique>`）并执行：

```sh
python3 /tmp/irisbox-storage-UNIQUE/build_direct.py /path/to/aosp-rpi4 /tmp/irisbox-storage-UNIQUE
```

**构建流程说明**：
1. 脚本以只读方式解析现有 Ninja 编译规则（不触发全量 Ninja 构建），仅单文件编译修补后的 `Utils.cpp`。
2. 在私有临时目录中复制并替换静态库 `libvold.a` 中的 `Utils.o` 成员，使用只读缓存依赖重新链接并 `strip` 生成最终二进制。
3. 生成的二进制与补丁位于临时目录的 `build/` 下。替换引擎载荷前请核对 SHA-256 摘要，随后递增引擎版本号并执行完整 APK 构建（此流程为单组件重链接，不宣称从零全量重编 AOSP）。
