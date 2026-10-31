# Google 商店恢复、数据备份与设备认证说明

**简体中文** · [English](en/google-recovery.md) · [实机验收记录](06_验收记录.md) · [返回主页](../README.zh-CN.md)

---

## 1. 核心概念区分：为什么商店能下载应用，但仍显示“未认证”？

在树莓派 AOSP 自定义 ROM 环境中，**Google Play 商店目录访问/应用下载**与 **Play Protect 设备认证（Device Certification）** 是两条不同的检验路径：

- **自定义 ROM 登记（Custom-ROM Registration）**：通过向 Google 登记实例的 GSF ID，允许未认证设备登录 Google 账号并访问 Google 应用与商店下载。
- **Play Protect 设备认证（Device Certification）**：要求设备硬件型号与系统构建属于 Google 官方认证通过的兼容性列表（CTS / VTS 认证签名版本）。

因此，实例完全可以同时处于 **“能够正常浏览商店并下载安装应用”** 与 **“设置 → 关于（About）中显示 `Device is not certified`”** 的状态。

| 检查维度 | 2026-10-24 实测结果 | 技术说明与边界 |
| :--- | :---: | :--- |
| **当前实例 GSF ID 官方登记** | **已核对相符** | 已由设备所有者提供的登记列表私下核对一致（号码保密；宿主或旧项目实例的登记不能替代当前实例）。 |
| **Google 账号保留与商店实际下载** | **通过** | 恢复商店目录并实际下载安装 **Open Sudoku**；重建 GMS 服务数据并重启后，无需重登再次下载安装并打开 **Google 计算器**（`installer=com.android.vending`）。 |
| **设置 → About → Play Protect certification** | **未通过** | 仍如实显示 `Device is not certified`，**绝不能把商店下载成功宣称为设备认证通过**。 |
| **Google 自带 `Fix device issue`** | **未通过** | 返回无法修复并建议更新系统；重建服务后再次触发检查仍未获认证通过。 |
| **Play Integrity 及第三方应用完整性校验** | **不作通过承诺** | 与商店基础下载分属不同风控机制。 |

*官方参考依据：[Google Play 设备认证说明](https://support.google.com/googleplay/answer/7165974?hl=zh-Hans) · [自定义 ROM 设备登记页](https://www.google.com/android/uncertified/warningauto)。经核查 [Google 官方认证设备名单](https://storage.googleapis.com/play_public/supported_devices.html)（54,261 行），其中不含 `Raspberry` / `rpi4` / `aosp_rpi4`，且当前系统为 `userdebug/test-keys` 构建。*

---

## 2. 普通使用者恢复 Google Play 下载的操作步骤

以下操作均在**目标实例内部的 Android 系统设置**中完成（实测保留数据的 Google 实例编号为 `2`，内部 ID 为 `1010`）：

1. **启动目标实例并留足内存**：
   宿主重启后，打开虹膜空间授予正常宿主 Root 并进入管理页。启动目标 Google 实例（建议内存上限设为 `2048 MiB` 或至少 `1536 MiB`），等待状态显示 **“桌面已就绪”**（若启动被物理内存准入拒绝，请先正常停止其他不用的实例，**切勿通过“重置实例”来解决内存不足**）。
2. **检查基础网络与时间**：
   进入实例内部的 Android“设置”，确认网络连接正常且已开启“自动确定日期和时间”，随后打开 Google Play 商店。若商店目录已正常显示且可下载，则无需执行下方清理步骤。
3. **仅清除商店与 GMS 的临时缓存（推荐首选）**：
   若商店无法加载目录或提示错误：
   - 进入实例内部 **设置 → 应用 → 查看所有应用**。
   - 分别找到 **Google Play 商店** 与 **Google Play 服务（Google Play services）**，点击 **强行停止（Force stop）**，然后进入 **存储和缓存（Storage & cache）** 选择 **清除缓存（Clear cache）**。
   - **注意**：“清除缓存”与“清除存储空间/全部数据”完全不同，切勿在日常排障中点击清除全部存储数据或清空 Google 服务框架（GSF）。
4. **核对当前实例的 GSF ID 登记**：
   若提示设备未登记，请查询**当前实例自己的 GSF ID**，由本人登录 [Google 自定义 ROM 官方登记页](https://www.google.com/android/uncertified/) 完成登记（已有登记请先核对号码，切勿反复清空 GSF 刷取新号）。
5. **触发正常 Check-in 与下载复核**：
   若清除缓存后仍未刷新，可由维护者执行第 3 节的正常 Check-in 广播并重启该实例。复核时请在 Play 商店内实际搜索并安装一个免费应用（如计算器），再单独查看 `右上角头像 → Settings → About → Play Protect certification`（横屏模式下头像菜单需向上滑动才能看到 `Settings`）。

> [!NOTE]
> **实测有效恢复链路**：2026-10-24 首次成功恢复商店下载的操作组合为 **`仅清除 GMS 与 Play 商店临时缓存 → 触发 Google 正常 Check-in → 重新打开商店 → 实际下载安装应用`**。该组合在实机上验证有效，但不据此断言其中某一步为唯一决定因素，也不承诺固定等待时间后能通过设备认证。

---

## 3. 开发者维护命令：仅刷新缓存并触发正常 Check-in

在工程根目录下使用已匹配设备的开发脚本（公开源码可通过环境变量 `IRISBOX_SERIAL` 指定您的 ADB 设备序列号）。以下命令通过 `regression.py` 校验实例 2 的 `PID:start_tick` 后进入该实例内部执行缓存清理与标准 Check-in 广播（**不会清除 GSF、GMS 持久数据、系统账号或游戏数据**）：

```powershell
$env:PATH = "$env:LOCALAPPDATA\Android\Sdk\platform-tools;" + $env:PATH
$env:IRISBOX_SERIAL = 'YOUR_DEVICE_SERIAL'
@'
import sys
sys.path.insert(0, 'tools')
from regression import target, guest

r = target(2)
assert r['ready'] and not r['busy']
print('Target instance:', r['number'], 'internal ID:', r['id'])
print(guest(r, '''
am force-stop com.android.vending
am force-stop com.google.android.gms
pm clear --user 0 --cache-only com.google.android.gms
pm clear --user 0 --cache-only com.android.vending
am broadcast -a android.provider.Telephony.SECRET_CODE \
  -d android_secret_code://2432546 \
  -n com.google.android.gms/.checkin.CheckinServiceSecretCodeReceiver
''', timeout=60))
'@ | python -
```

*注：广播返回 `result=0` 仅代表广播已发出，维护者应在私有日志目录 `validation/private/` 下核对实际 Check-in `SUCCESS` 标记与商店页面，并通过真实下载应用完成验证。*

---

## 4. 进阶认证排查尝试记录与私有备份说明

为排查用户提出的“设置页显示设备已认证”要求，2026-10-24 曾在完整备份前提下额外执行过以下测试（**这些操作均未使 `Device is not certified` 变为已认证，不属于日常恢复步骤**）：

1. **临时关闭实例 Root 对照**：正常停机关闭实例 2 的 Magisk Root 并重启，确认实例内无 `su` 与 `magiskd` 进程后运行 Google 自带修复，结果依然未认证（随后已恢复实例 Magisk，原有普通应用 `su` 授权保持不变）。
2. **单独重新初始化商店数据**：在独立备份后仅对 Play 商店执行 `pm clear`（保留 GSF ID、Google 账号与游戏数据），重开商店确认服务条款后一度显示 `Certification status unavailable`，触发检查后仍恢复为未认证。
3. **经用户明确授权的 GMS 服务数据重建与版本恢复**：
   - 先卸载 GMS 更新退回ROM 预装的 `22.46.19 (224619045)` 并重建服务数据（核对 GSF ID 与账号私有 HMAC 不变）。
   - 随后通过标准 Android `PackageInstaller` 会话装回备份的 **11 个原版分包 APK（共 `235,572,891 字节`）**，将 GMS 恢复至 `26.39.33 (263933029)`，保留新建的服务状态而不回灌旧 `CE` / `DE` 目录。
   - **验证结果**：恢复实例 Magisk 并重启（约 59 秒就绪）后，游戏 `shared_prefs` 文件 SHA-256 完好如初，且**无需用户重新登录即从 Play 商店成功下载、安装并打开 Google 计算器 9.3**（证明重建后的商店授权有效，但 `About` 页仍显示 `Device is not certified`）。正如 [Google 官方 Play 服务排障指南](https://support.google.com/googleplay/answer/9037938?hl=zh-Hans) 所述，清除服务数据可能影响已保存的令牌，因此绝不能将“清除全部 GMS 数据”写成自动启动修复逻辑。
4. **私有备份归档说明**：
   维护者在宿主私有目录 `/data/irisbox/maintenance/`（权限 `0700` 目录 / `0600` 文件，附 SHA-256 校验，索引位于本地忽略的 `validation/private/google-orientation-20261024/`）保留了当时的 GMS/商店 `CE`/`DE` 快照与原版 APK。由于清理数据可能使旧密钥失效，直接解压旧 `tar` 归档不能保证无损恢复全部授权；日常使用应保持当前已验证通过的新服务状态，切勿盲目用旧归档覆盖。
