# 来源与组件演进说明 · Source Provenance

本文档记录 **虹膜空间（IrisBox）** 从参考工程提取、演进及各版本适配组件的真实来源与校验依据。

---

## 1. 基础提取与基线核验

- **初始来源**：本项目从用户本地只读参考工程 `Project Iris V3.0` 提取控制脚本、`Viewer` / `Broker` / `Gateway` 源码及固定恢复包中的配套组件。`reference/source-inputs.json` 完整记录了最初 **185 项输入文件**的相对路径与 SHA-256 摘要；后续演进以本仓库源码为准。
- **版本对齐策略**：参考工程运行源码已包含 2026-10-14 的网络账本修复，而其早期冻结归档包停留在 2026-10-10。因此提取时采用**按明确清单用最新运行脚本/载荷覆盖冻结包**的策略，而非简单将旧归档改名。此外，Connectivity Guest 联网库直接取自设备已验证的静态产物，并与本地构建产物逐字节核对一致。

---

## 2. 上游组件与可选层来源

| 组件类别 | 来源与合规边界 |
| :--- | :--- |
| **基础系统与内核** | AOSP Android 13（`android-13.0.0_r75`）及 Raspberry Vanilla 树莓派 4 板卡驱动、Linux 5.15.150 内核、BusyBox 1.36.1.1。 |
| **实例 Root 组件** | Magisk 30.7（`engine/runtime/root/magisk_30_7/`），保留上游 `LICENSE`、`adaptation.json`、`apk_assets.patch` 与 `SHA256SUMS`。其中的 Manager 与二进制为容器适配并独立重签名的组件，不可直接用官方未修改原版替换。 |
| **系统与网络补丁** | 系统兼容补丁位于 `reference/patches/`；BPF 源码、预编译指令头（`policy_insns.h`）及 Guest 联网补丁位于 `engine/native/inet/`。 |
| **可选 Google 静态层** | 沿用参考工程针对 `NikGapps core arm64 Android 13`（固定包日期 `20260204`）提取的 54 项静态文件清单与 SHA-256 摘要，仅包含未运行的静态程序层，**绝不复制任何个人账号、运行期更新或实例 `data`**。公开 AOSP ROM 与公开 Git 仓库均不包含 Google 二进制文件。 |

---

## 3. 各版本新增与重编组件记录

### v0.1.1 / e9：图形 Producer 重编与音频桥
- **图形 Producer（`engine/native/buffer/`）**：提取 C++ 源码与直接编译脚本，修复单帧预览进程退出时的静态析构竞争问题，并在经授权的匹配 AOSP 13 编译机独立临时目录（`/tmp/irisbox-producer-*`）完成重编，摘要记录于 `reference/producer-build.json`。
- **音频桥（`engine/audio/AudioBridge.java`）**：新增实例 PCM 回环采集与宿主 `AudioTrack` 监督播放实现，不从旧实例提取任何运行期数据。

### v0.1.3 / e17：User Namespace 身份隔离
- **参考与独立适配**：参考旧项目 2026-10-20 的 User Namespace 实现（输入摘要见 `reference/userns-provenance.json`），将本项目映射更改为互不重叠的独立 UID/GID 范围（`2000000000` 起），并专门适配 `0700` 宿主私有根目录、私有 BusyBox、独立音频桥降权连接与维护升级事务。
- **闹钟兼容库（`libalarm_jni.so`）**：复用已核验的固定产物，附带 AOSP 源码位置与补丁（`reference/patches/userns-alarm.patch`）；未修改旧工程或宿主系统库，亦不宣称全量重编 AOSP。

### v0.1.5 / e25：映射存储（`vold`）与相机框架恢复
- **映射实例存储适配（`engine/native/storage/`）**：在匹配 AOSP 编译机的独立临时目录中仅重编 `system/vold/Utils.cpp` 并基于只读依赖重链接 `vold`。仅在严格匹配的实例映射身份下跳过内核禁止非初始 User Namespace 修改的项目配额（Project Quota）标签/继承，完整保留目录、UID/GID、权限模式与默认 ACL 检查。补丁、摘要与复编脚本分别见 `reference/patches/vold-userns.patch`、`reference/storage-build.json` 与 `engine/native/storage/`。
- **相机框架服务恢复**：恢复实例内原厂 `cameraserver` 框架服务以正常返回 `0` 台摄像头（解决 YouTube 启动查询相机数量阻塞导致的 ANR），同时保持所有物理相机 Provider 禁用。
