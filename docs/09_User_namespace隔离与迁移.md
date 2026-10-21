# User namespace 隔离与数据迁移

[English](en/userns.md)

## 身份边界

0.1.2/e13及以前没有 User namespace。0.1.3开始要求每个实例使用独立的 User namespace；缺失、损坏或 revision 不匹配的映射直接拒绝启动，没有 Host UID0 回退。

每域映射100000个UID/GID，覆盖Android user0。虹膜空间保留 `[2000000000,2012800000)`，与旧 Iris 的 `[1100000000,2000000000)` 分开。Host root不在映射内。Host保护的 `config/userns_v1/ID.map` 将范围绑定到数据revision；分配用排他锁和原子提交，重置保留范围并更新revision，删除不回收范围。

实例内root仍为0；Host视图为BASE，system为BASE+1000，普通App为BASE+应用UID。UID相同不表示进程相同；每实例init仍有自己的PID namespace及PID1。

## 启动与文件系统

1. Host原生监督器留在委派资源组外，创建User/PID/net/IPC/UTS/cgroup空间。Guest子进程阻塞在管道，尚不执行ROM或data中的代码。
2. Host写入映射，只把Guest子进程加入实例managed组。受信任的 `inner.sh` 在私有挂载视图中准备ROM、RC、Binder和网络。
3. Linux5.15的当前OverlayFS组合不使用idmapped lower。共享system/vendor镜像保持原属主及只读；私有overlay启用 `metacopy=on,index=off`，FD遍历只调整私有视图的元数据，避免复制整份ROM。
4. UID/GID、命名POSIX ACL和v2/v3文件能力一起映射；拒绝外来身份、data中的原始设备和未迁移的多链接普通文件。系统与vendor在放行前封为只读、nosuid。
5. 放行后Guest切到映射root，再创建自己的mount/cgroup空间，切根并关闭3号以上继承FD，执行静态BusyBox入口及适配Android init second_stage。

虹膜空间的Host根目录保持0700。启动器只在已经私有化的准备器/子进程挂载空间内，将备好的实例根临时绑定到 `/mnt` 作为切根入口；Host看不到这个别名，也不创建Host目录或放宽权限。切根后实例的 `/mnt` 仍是自己的tmpfs。

Host监督器采用父pidfd及PDEATHSIG防止死亡竞态。`ctl exec`先核对PID:start_tick和挂载/cgroup归属，打开FD后先进入User空间、切换映射身份，再进入其余空间并关闭Host FD。`verify`检查六种namespace的 `NS_GET_USERNS` 所有者，以及init真实/有效/保存/fs UID和GID。

## Android配套适配

- **网络**：共享INET分派器协议保持；只向已核验的netns登记本项目私有表。表属主来自真实uid_map/gid_map的BASE+1000，不由Guest请求传入。保留原程序/Host表核验及不提供restore的约束。
- **显示与输入**：固定源码经no-follow目录FD发布为映射root所有。连接助手先认证Host控制端，清组、降到BASE并清能力后才读取Guest token及连接socket，再把两个已认证FD交给原网关。像素仍走共享缓冲区。
- **声音**：虹膜空间独有的PCM桥也采用降权连接助手；Host AudioTrack保留在宿主框架，连接Guest socket的身份在Guest看来是root0。只传已认证FD，不允许任意Host root直接连接Guest端。
- **Magisk**：Host准备只读程序暂存区，Guest自行创建自己的 `/sbin` tmpfs，保留既有Root开关、授权数据库及模块数据。宿主Magisk仍由用户安装。
- **系统服务**：Guest使用受委派内存控制器的vmpressure模式；补充CgroupEventControl别名。RTPRIO硬上限为0。闹钟JNI仅在映射Guest的唤醒时钟返回EPERM时改为同类非唤醒时钟，保留原生事件分发。Guest不接管物理蓝牙，输入继续由Host Viewer转发。

闹钟适配库复用旧项目已验证产物，SHA-256 `e4a50b33128b0d92380e5f24bd46890be388e2b9a001a8908cab7c24e9e9a7ae`。补丁在 `reference/patches/userns-alarm.patch`，来源记录在 `reference/userns-provenance.json`。本轮完整构建原生启动器、入口助手、INET、音频、网关、Broker及APK；没有重新编译整个AOSP，也没有修改编译机AOSP源码或宿主system库。

## 从e13维护升级

新安装按正常App创建流程自动分配映射。现有e13数据必须停机备份后迁移，不能只覆盖APK后直接启动。

```powershell
python tools/upgrade_engine.py --from-apk out/IrisBox-0.1.2.apk --apply
python tools/device.py install
# 正常打开新App，确认所有待迁移实例仍停止。
python tools/migrate_userns.py
python tools/migrate_userns.py --apply
```

维护工具在生命周期锁下复制data/upper/vendor.upper/metadata与实例配置，保留mode、属主、SELinux和扩展属性；备份是独立inode，源与备份逐文件SHA256必须一致。备份在 `/data/rootvmos/maintenance/userns_migration_v1/ID_REVISION`，不覆盖已有快照。登记映射不改变实例ID、revision、ROM、Root/Google开关或文件字节；身份元数据在首次映射启动时准备。

**源码、引擎产物和数据回退是三件事。** 第一次映射启动后，data的Host属主已变化；不能仅回退Git或APK后交给旧引擎，也不能对整个/data或共享lower递归chown。需要正常停机、核对身份和挂载、保留迁移后的新数据，再按对应完整备份恢复树和配置。中断可能发生在能力元数据恢复之间，不能承诺任意中断位置都能自动无损续跑；不确定时保留现场并从已核验副本恢复。

## 验收与限制

验收脚本 `tests/userns_regression.py` 读取真实内核身份，核验映射、namespace所有者、错误tick拒绝，并使用虚拟测试文件检查Guest root可读本域文件、不能读取Host UID0或另一映射域的0600文件。它不把真实宿主秘密暴露给Guest。

本轮实际通过项、失败与修复追加到 [06验收记录](06_验收记录.md)。旧项目只读参考；运行状态的正常关闭获得用户单独授权，不修改其源码、配置或实例数据。

User namespace修复root同身份问题，但仍共享Linux内核和GPU驱动，宿主仍为Permissive。没有宣称完整恶意代码沙箱、任意ROM/模块兼容或独立虚拟机安全边界。Time namespace未开启，Guest Android多用户不在本映射范围内。原理参见 [Linux user namespaces](https://man7.org/linux/man-pages/man7/user_namespaces.7.html) 和 [BinderFS](https://docs.kernel.org/admin-guide/binderfs.html)。
