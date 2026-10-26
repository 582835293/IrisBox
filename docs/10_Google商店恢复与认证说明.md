# Google商店恢复与设备认证

[English](en/google-recovery.md) · [验收记录](06_验收记录.md)

## 为什么能下载，但仍未认证

本次恢复的是Google商店访问、账号授权及实际下载。Google的自定义ROM登记提供使用Google应用的途径；设备认证还涉及Google认可的设备兼容性结果。两者有不同的验收出口，所以可以同时出现“商店能安装应用”和“Device is not certified”。

| 检查 | 本次结果（2026-10-24） | 说明 |
|---|---|---|
| 当前实例的GSF ID已登记 | 已由使用者提供的列表核对 | 号码保持私有；宿主和旧实例的登记不能代替它 |
| 账号与商店下载 | 通过 | 实际从Play安装Open Sudoku；服务重建并重启后又安装Google计算器 |
| 设置→About→Play Protect certification | 未通过 | 仍观察到Device is not certified，不能用下载成功代替 |
| Google自带Fix device issue | 未通过 | 曾返回无法修复、建议系统更新；重建服务后再次检查等待未获通过 |
| Play Integrity及其他应用自己的完整性策略 | 未作为通过项 | 与商店下载、恶意软件扫描分别判断 |

官方依据：[设备认证说明](https://support.google.com/googleplay/answer/7165974?hl=zh-Hans)、[自定义ROM登记](https://www.google.com/android/uncertified/warningauto)。登记成功不代表已获得厂商认证，也没有证据证明本机只是等待人工审核。当前树莓派构建不在本轮核对的官方认证设备名单内。

## 普通使用者恢复商店的顺序

以下操作在**实例内部的Android设置**完成。先确认虹膜空间实例编号；本轮保留数据的实例2内部ID为1010。其他安装的内部ID可能不同。

1. 宿主重启后，打开虹膜空间，完成正常root授权并进入管理页。启动目标实例，等待“桌面已就绪”。本轮实例2内存为2048MiB；内存准入拒绝时先正常停止自己不用的实例，保留保护。不要用重置实例来处理内存不足。
2. 在实例设置中检查网络、自动日期和时间，再打开商店。已有下载可用时无需做后面的清理。
3. 商店无法打开或下载时，在实例“设置→应用→查看所有应用”中打开Google Play商店，正常强行停止，并在“存储和缓存”选择**清除缓存**。同样仅清Google Play服务的缓存，随后重新打开商店。清除缓存与清除存储/全部数据是不同操作。
4. 若明确提示未登记，按Google官方登记页使用**当前实例自己的GSF ID**，由本人登录并完成验证码。已有登记先核对号码，不反复清GSF换号。
5. 仍受限时交由维护者执行下节的正常check-in与诊断。可正常停止/启动该实例后复验。记录实际结果，不承诺固定等待时间就会认证通过。
6. 验收时在Play内选择一个免费应用，点安装并打开。再单独检查“头像→Settings→About→Play Protect certification”。横屏时头像菜单可能需要向上滚动才能看到Settings。

本轮首次恢复的组合是：**仅清GMS/商店临时缓存→Google正常check-in→重开商店→实际下载安装**。该组合已实测有效，但不能据此断言其中某一步是唯一原因或以后每次都有效。

## 开发维护：只刷新缓存和正常同步

在工程根目录使用已匹配设备的开发工具。公开源码可设置`IRISBOX_SERIAL`选择自己的ADB设备；首次连接和Host root授权由设备所有者完成。先运行`python tools/device.py state`核对目标编号。下面固定选择实例2，通过`ctl.sh`核验PID:start_tick并进入该Guest；所有Google包操作均发生在该实例。

```powershell
$env:PATH="$env:LOCALAPPDATA\Android\Sdk\platform-tools;"+$env:PATH
$env:IRISBOX_SERIAL='YOUR_DEVICE_SERIAL'
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
am broadcast -a android.provider.Telephony.SECRET_CODE -d android_secret_code://2432546 -n com.google.android.gms/.checkin.CheckinServiceSecretCodeReceiver
''', timeout=60))
'@ | python -
```

广播result=0只表示接收，不是同步或认证成功。维护者应把日志放在`validation/private`，核对实际check-in完成标志及商店页面，再从Play真实下载。原始日志可能含账号和标识，不能提交GitHub。上述操作不清GSF、GMS持久数据、系统账号或游戏。

## 本轮进一步尝试的范围与结果

为排查“设备已认证”这一额外要求，随后做过以下操作；它们**没有证明修好认证**，不是日常恢复必须步骤：

- 临时关闭实例Root并正常重启，确认su/magiskd不在运行；官方修复仍失败。之后已恢复原Magisk，普通应用已有授权保持。
- 在独立备份后仅重新初始化商店数据；GSF ID、Google账号和游戏保留。商店首次重新打开要求确认已有服务条款，认证一度显示暂不可用，不能算通过。
- **使用者另行明确授权后**，卸载GMS更新回预装22.46.19/224619045，重建服务数据，再通过Android安装会话装回备份中11个原版APK，恢复26.39.33/263933029。保留新服务状态，没有恢复旧CE/DE。

恢复原Root后实例约59秒就绪。GSF ID、账号私有HMAC、实例revision和游戏偏好文件SHA-256保持；无需重新登录即可在Play实际下载安装并打开Google计算器9.3（installer=com.android.vending）。**这证明本轮重建后的商店授权可用，认证仍未通过。**

[Google官方服务排障说明](https://support.google.com/googleplay/answer/9037938?hl=zh-Hans)提醒，清服务数据可能删除其保存的信息并要求重新授权。已有账号记录也不等于登录令牌有效。因此GMS数据重建需要备份、明确授权及实际下载复验；不要将“清全部数据”加入常规启动或一键修复。

## 备份、回退与当前状态

开发者本机的私有索引在`validation/private/google-orientation-20261024/`：`google-backup.json`记录Google组件备份，`store-data-refresh-backup.json`记录商店数据备份，`gms-current-version-restored.json`记录新版恢复结果。备份实际保存在新项目Host目录`/data/irisbox/maintenance/`下的独立目录，0700目录/0600文件，并有SHA-256核验；索引和备份均不随公开源码分发。

备份含GMS/商店的CE/DE状态及当时的原版APK，另有GSF SQLite一致性参考。它不等于整个Android账号系统、Keystore和实例的完整快照。数据清理可能使旧密钥失效，直接解压tar不能保证恢复全部授权；需人工分析版本及一致性，必要时由本人重新登录。日常使用保留当前经验证的新服务状态，勿自动覆盖旧tar。

收尾配置：实例2为2048MiB、Magisk恢复、方向跟随应用；Google账号及天天象棋数据保留。实例1停止，旧工程保持原配置/数据且不重启。0.1.4/e21中英文方向菜单及重启保存已通过，发行状态和资源链接见下载页。认证仍是未完成的验收条件，不能把文案改成已通过。
