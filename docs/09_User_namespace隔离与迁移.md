# User Namespace 身份隔离与数据迁移指南（v0.1.3+）

**简体中文** · [English](en/userns.md) · [返回主页](../README.zh-CN.md)

---

## 1. 身份隔离模型与 UID/GID 映射边界

`0.1.2 / e13` 及更早版本未启用 User Namespace。自 **`0.1.3 / e17`** 起，虹膜空间强制要求每个实例运行在**独立的 User Namespace** 中；凡映射记录缺失、损坏或与实例数据 `revision` 不匹配时，启动器直接拒绝启动（Fail-Closed），**绝不提供任何回退至宿主 `UID 0`（Host Root）的路径**。

| 属性维度 | 设计规范与边界 |
| :--- | :--- |
| **每实例映射跨度** | 每个实例独立映射 **`100,000` 个连续 UID/GID**，完整覆盖 Android `user 0` 的系统与应用 UID 空间。 |
| **宿主保留地址池** | 虹膜空间专用范围为 **`[2000000000, 2012800000)`**，与旧参考工程的 `[1100000000, 2000000000)` 完全隔离、互不重叠；宿主 `root (UID 0)` 不在任何实例映射表内。 |
| **内外身份换算** | 实例内部视图：`root = 0`，`system = 1000`，普通应用 = `10000+`。<br>宿主内核视图：`Guest root = BASE`，`Guest system = BASE + 1000`，普通应用 = `BASE + App_UID`（例如实例 1 `BASE = 2000000000`，实例 2 `BASE = 2000100000`）。 |
| **持久化绑定与防重用** | 宿主受保护的 `config/userns_v1/ID.map` 将实例分配到的 UID 范围与数据 `revision` 原子绑定：重置实例（Reset）时保留原 UID 范围并递增 `revision`；删除实例（Delete）后该 UID 范围永久退役、不再回收分配给新实例。 |

---

## 2. 启动时序与文件系统元数据映射

1. **宿主非委派监督与管道同步**：宿主原生监督器（`irisbox_userns`）留在委派 cgroup 之外，调用 `clone()` 创建 `User` / `PID` / `Net` / `IPC` / `UTS` / `Cgroup` 命名空间；此时 Guest 子进程阻塞在同步管道上，尚未执行任何 ROM 或实例 `data` 中的代码。
2. **宿主侧可信准备**：宿主写入 `uid_map` / `gid_map`，仅将 Guest 子进程移入实例受控 cgroup；受信任的 `inner.sh` 在已设为 `private` 的挂载命名空间中准备 ROM 挂载、RC 配置、私有 `binderfs` 与虚拟网卡。
3. **`metacopy=on` 零拷贝 Overlay 元数据映射**：
   - 树莓派 4 的 Linux 5.15 内核下，共享只读 `system.img` / `vendor.img` 保持原始属主与只读状态；实例私有 Overlay 挂载启用 `metacopy=on,index=off`。
   - 启动器通过受限的不跟随符号链接（no-follow）目录 FD 遍历，仅调整实例私有视图的 UID/GID、命名 POSIX ACL（`system.posix_acl_*`）与 `v2`/`v3` 文件能力（`security.capability`），无需复制整份只读 ROM 数据。
   - 遍历时严格拒绝外来未知 UID、实例 `data` 中的原始设备节点（raw device nodes）及未迁移的多硬链接普通文件；放行前将 `system` 与 `vendor` 视图重新封锁为 `ro,nosuid`。
4. **私有 `/mnt` 切根别名（保持宿主 `/data/irisbox` `0700` 权限）**：
   虹膜空间的宿主数据根目录 `/data/irisbox` 严格保持 `0700`（仅 Host `root` 可穿越）。为使映射后的非特权 Guest 身份能完成 `pivot_root`，启动器**仅在已经私有化的准备器/子进程挂载命名空间内**，将准备好的实例根临时 `mount --bind` 到 `/mnt` 作为切根入口——宿主命名空间完全看不到该绑定，也无需放宽任何宿主目录权限；完成 `pivot_root` 后，实例内的 `/mnt` 依然是其独立的 `tmpfs`。
5. **切换身份与 `init` 引导**：解除管道阻塞后，子进程切换至映射 `root (UID 0)`，创建实例自己的 `mount` / `cgroup` 空间，执行 `pivot_root`，关闭 `FD 3` 及以上的所有继承文件描述符，最终拉起静态 BusyBox 入口与适配后的 Android `init second_stage`。
6. **防死亡竞态与安全 `exec` 进入**：宿主监督器通过父进程 `pidfd` 与 `PR_SET_PDEATHSIG` 防止孤儿进程竞态；维护命令 `ctl.sh exec` 先核验目标 `PID:start_tick` 与挂载/cgroup 归属，打开命名空间 FD 后**必须先进入 `User` 空间并切换为映射身份，再进入其余命名空间并关闭宿主 FD**。`verify` 子命令通过内核 `NS_GET_USERNS` 强校验其余六类命名空间的所有者均为该实例的 User Namespace。

---

## 3. Android 核心子系统配套适配

| 子系统 | User Namespace 适配方案 |
| :--- | :--- |
| **网络 BPF 权限表** | 保持共享 INET 分派器协议不变；联网助手直接从经核验的内核真实 `uid_map` / `gid_map` 读取 `BASE + 1000` 作为私有 BPF Map 属主（绝不信任 Guest 传入参数），仅注册本实例 `netns cookie` 映射。 |
| **显示与输入网关** | 固定客户端经 no-follow 目录 FD 发布为映射 `root` 属主；连接助手先认证宿主控制端，清空附加组、降权至实例 `BASE` UID 并清除特权 Capability 后，才读取实例 Token 并连接 Socket，再将已认证的两个 FD 移交 Gateway（全屏像素仍走共享图形缓冲区）。 |
| **音频回环桥（PCM）** | 虹膜空间独有的音频桥同样采用降权连接助手：以实例映射 `root` 身份连接 Guest 音频 Socket，验证通过后再将已认证的 PCM 文件描述符交由宿主 `AudioTrack` 进程播放。 |
| **实例 Magisk Root** | 宿主准备只读二进制暂存区，由实例自行挂载其属主的 `/sbin` `tmpfs`，完整保留每实例独立的 Root 开关、Superuser 授权数据库与模块目录（宿主 Magisk 仍由用户自行安装）。 |
| **系统服务与闹钟 JNI** | 启用委派内存控制器的 `vmpressure` 模式并补充 `CgroupEventControl` 别名；`RTPRIO` 硬上限设为 `0`。闹钟 JNI（`libalarm_jni.so`，SHA-256 `e4a50b33128b0d92380e5f24bd46890be388e2b9a001a8908cab7c24e9e9a7ae`，补丁见 `reference/patches/userns-alarm.patch`）仅在映射实例设置唤醒时钟返回 `EPERM` 时自动降级为同类非唤醒时钟。物理蓝牙保留在宿主，不交由实例接管。 |

---

## 4. 从 `0.1.2 / e13` 存量数据的停机迁移流程

新建实例通过宿主 App 正常创建流程会自动分配 User Namespace 映射；而 **`0.1.2 / e13` 的存量实例数据必须在停机状态下完成完整备份与属主迁移，严禁仅覆盖安装 APK 后直接启动**：

```powershell
# 1. 在 App 中正常停止所有实例，保留已安装的 0.1.2 APK，执行引擎升级：
python tools/upgrade_engine.py --from-apk out/IrisBox-0.1.2.apk --apply

# 2. 安装新 APK，打开 App 确认所有待迁移实例处于停止状态：
python tools/device.py install

# 3. 先执行只读预检，确认无误后附加 --apply 执行备份与映射登记：
python tools/migrate_userns.py
python tools/migrate_userns.py --apply
```

### 备份机制与回退铁律
- **独立 Inode 全量校验备份**：`migrate_userns.py` 在持有生命周期锁的前提下，将每个实例的 `data`、`upper`、`vendor.upper`、`metadata` 与配置文件复制至 `/data/irisbox/maintenance/userns_migration_v1/ID_REVISION`（独立 inode，完整保留权限模式、属主、SELinux 标签与 xattr，并逐文件比对源与备份的 SHA-256 摘要，绝不覆盖已有快照）。
- **源码、引擎与数据回退严格区分**：实例完成首次映射启动后，其 `data` 与 `upper` 在宿主视角下的 UID/GID 已变更为 `2000000000+` 范围。此时**绝不能仅回退 APK 或旧引擎就直接启动，更严禁对宿主 `/data` 或共享只读 `lower` 镜像目录执行递归 `chown`**。如需回退，必须先正常停止实例、核对挂载状态、保留新产生的数据，再从 `/data/irisbox/maintenance/userns_migration_v1/ID_REVISION` 中的核验快照恢复目录树与配置。

---

## 5. 验收测试与安全边界

- **自动化内核身份回归**：`tests/userns_regression.py` 实机读取 `/proc/<pid>/status` 与命名空间 ioctl，核验 UID/GID 映射表、六类命名空间的 `NS_GET_USERNS` 归属、过期 `start_tick` 拒绝，并使用一次性虚拟测试文件验证实例内 `root` 可读写本域 `0600` 文件，但访问宿主 `UID 0` 或其他实例映射域的 `0600` 文件均被内核拒绝（测试全程不暴露任何真实宿主凭据）。
- **已知安全边界**：启用 User Namespace 消除了“容器 `root` 即宿主 `UID 0`”的身份重叠缺陷，但所有实例仍共享同一个树莓派 Linux 5.15 内核与 V3D GPU 驱动，且宿主处于 Permissive SELinux 模式。因此本设计**不宣称构成针对恶意代码的强安全沙箱或硬件虚拟化隔离边界**（Time Namespace 与实例内 Android 多用户亦不在支持范围内）。原理参考：[Linux User Namespaces (man7)](https://man7.org/linux/man-pages/man7/user_namespaces.7.html) 与 [Kernel BinderFS](https://docs.kernel.org/admin-guide/binderfs.html)。
