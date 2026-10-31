# IrisBox ROM 包规范（v1）

**简体中文** · [English](en/rom-format.md) · [返回主页](../README.zh-CN.md)

---

## 1. 归档结构与格式要求

IrisBox ROM 包使用 `.irisrom` 扩展名，底层为标准 `ZIP` / `ZIP64` 归档，**不包含顶层外包目录**，且归档中的**第一个条目必须是 UTF-8 编码的 `rom.json` 清单文件**。系统镜像（`system.img`、`vendor.img`）必须为 **raw `ext4` 镜像**，拒绝 Android sparse 镜像、`super.img`、`boot.img` 或 OTA 刷机包。

```text
rom.json                    # 必须作为 ZIP 第一项：UTF-8 清单与全文件大小/SHA-256 列表
system.img                  # Raw ext4 系统镜像
vendor.img                  # Raw ext4 厂商镜像
compat/iris_init            # 容器适配的 Android init 入口
compat/guest.rc             # 实例核心 init 配置
compat/guest_vendor.rc      # 实例 vendor init 配置
compat/loaded_files.txt     # 原厂 RC 加载白名单
compat/rc_candidate/...     # 经审计的原厂 RC 候选文件集合
compat/payload/...          # 图形/网络/DocumentsUI 等适配载荷
compat/google/...           # [可选] 静态 Google 组件层（含完整静态 rootfs，不含任何个人账号）
```

---

## 2. `rom.json` 清单规范

清单中声明的每一个文件都必须包含其真实的字节大小（`size`）与 64 位小写十六进制 `sha256` 摘要：

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

### 安全校验与容量硬限制

| 校验维度 | 规范限制 |
| :--- | :--- |
| **ROM ID 格式** | 仅允许匹配正则 `[a-z0-9][a-z0-9._-]{0,63}`，严禁包含 `..`。 |
| **归档路径安全** | 仅允许受限 ASCII 字符；严禁绝对路径、反斜杠 `\`、空路径段、`.` 或 `..` 路径穿越段；拒绝目录条目、符号链接、重复条目与清单未声明文件。 |
| **条目与体积上限** | 单包最多 **512 个文件**；`rom.json` 最大 **128 KiB**；单个文件最大 **4 GiB**；解压后总大小最大 **6 GiB**。 |

---

## 3. 兼容载荷锁定与可选组件边界

1. **APK Profile 双向锁定**：当前 `rpi4-a13-v1` Profile 仅接受经提取核验的树莓派 4 AOSP 13 适配材料。虽然 `compat/` 材料随 ROM 包携带，但其关键文件的 SHA-256 摘要同时硬编码封存在宿主 APK 的 `assets/rom-profile.json` 中。任何适配层修改均需开发者更新 Profile 并重新构建签名 APK，**严禁外部导入包自行替换宿主控制器或执行自定义脚本**。
2. **`compat/` 载荷组成**：
   - `compat/payload/`：包含适配后的 `DocumentsUI.apk`、Mesa/Gralloc 唯一实例 ID 库（`libminigbm_gralloc_gbm_mesa_guest_ids.so`）、no-BPF 网络统计/netd 库以及 Connectivity 联网兼容产物。
   - `compat/rc_candidate/` 与 `loaded_files.txt`：针对该特定 AOSP 13 构建裁剪并审计的 RC 白名单（非任意 Android 13 通用补丁）。
3. **可选 Google 静态层（`compat/google/`）**：
   - 结构为 `compat/google/{component.id, component_manifest.json, files.sha256, rootfs/...}`，包含固定的 54 项静态文件。
   - 启用后将作为实例 `system` Overlay 的前置只读 `lower` 层挂载，不再从宿主 `/system` 复制文件。
   - 新实例需由使用者独立登录 Google 账号；包含完整静态组件**不代表通过 Google 设备认证（Play Protect Certification）**。
4. **实例 Root（Magisk）归属引擎**：实例内的 Magisk 30.7 组件属于内置引擎的可选功能（默认关闭），不包含在 `.irisrom` 中；授予宿主 Magisk Root 不会自动开启实例内的 Root。

---

## 4. ROM 打包命令

使用仓库提供的 `tools/make_rom.py` 封装 `.irisrom`：

```powershell
# 打包公开的不含 Google 的纯 AOSP ROM
python tools/make_rom.py `
  --system out/rom-input/system.img `
  --vendor out/rom-input/vendor.img `
  --id rpi4-a13-20260916-aosp `
  --name "IrisBox Raspberry Pi 4 Android 13 AOSP" `
  --out out/IrisBox-rpi4-a13-aosp.irisrom

# 打包包含私有静态 Google 层的 ROM（必须使用不同的 --id）
python tools/make_rom.py `
  --system out/rom-input/system.img `
  --vendor out/rom-input/vendor.img `
  --with-google `
  --id rpi4-a13-20260916 `
  --out out/rpi4-a13-google.irisrom
```

> [!CAUTION]
> **版本管理铁律**：切勿使用相同的 `--id` 打包不同内容的 ROM；同一底包带 Google 与不带 Google 的两个版本**必须使用不同的 `--id`**。发行 ROM 包与个人账号/实例数据备份必须严格分离，原始镜像与私有 Google 载荷绝不提交至 Git 仓库。
