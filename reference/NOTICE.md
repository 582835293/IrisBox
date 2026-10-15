# 来源与组件说明

本项目从用户本地 Project Iris V3.0 提取当前控制脚本、Viewer/Broker/Gateway源码，以及固定恢复包中的配套组件。`source-inputs.json`保存185项最初输入的路径及SHA-256；后续改动以本项目源码为准。

参考运行源码已包含2026-10-14网络账本修复；旧冻结包本身是2026-10-10版本。因此采用“明确清单中的当前脚本/载荷覆盖固定包”，不是把旧tar直接改名。额外Connectivity Guest联网库来自设备现用静态产物，并与本地对应build产物核对。

关键上游：AOSP Android13及树莓派板卡驱动、Linux/BusyBox、Magisk30.7、NikGapps core arm64 Android13（固定包日期20260204）。Google层沿用旧项目固定manifest和摘要，提取的是已准备的静态程序层；没有复制账号、运行中更新或实例data。

Magisk组件内保留原LICENSE、adaptation.json、apk_assets.patch和摘要。其Manager/二进制为旧项目适配并配套签名的组件，不代表官方原版可直接替换。系统兼容补丁在`reference/patches`，BPF源码、固定指令头和Guest库patch在`engine/native/inet`。

当前公开发行使用不含 Google 的 AOSP ROM 和重新创建空白 userdata 的宿主镜像。第三方许可、对应源码索引、镜像验证边界见仓库 THIRD_PARTY_NOTICES.md 与 docs/08_国际化与发布.md。原私人 Google 包和所有用户数据不公开。

本轮将当前producer C++源码及直接编译脚本提取至`engine/native/buffer`，修复后在用户已有AOSP13环境的独立临时目录重编；摘要见`producer-build.json`。新增Java音频桥和Host监督脚本为本项目实现，不从旧实例提取运行数据。
