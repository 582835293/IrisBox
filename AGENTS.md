# 虹膜空间（IrisBox）开发约定

这是独立产品工程。旧 `嵌套安卓虚拟机项目V3.0` 仅作只读参考；不要部署到旧设备目录，不覆盖旧包名/配置/实例。2026-10-15用户明确授权正常关闭旧项目且不用恢复，旧实例和后台已关闭，配置与数据保留；不要擅自重新启动旧项目。

先读README与docs。APK、ROM、账号/实例数据、内置引擎版本分别管理。引擎更改需完整构建并修改版本；仅UI变化可用build.ps1的AppOnly。初始extract_reference/adapt_engine脚本不能作为日常重建入口覆盖现有源码。

2026-10-26用户明确授权内部包名和设备路径也全部迁移，替代先前内部名称保留约定。新设备根用 `/data/irisbox`，包名 `com.irisbox.viewer`，端口19762，ID1007～1134，网卡rv和irisbox资源组。迁移源从已验证旧APK读取，仅用于停机核验、备份与可回退迁移，保留实例revision、UID/GID映射和账号数据。共享INET分派器是明确例外：核验后复用，不抢占、不restore、不删除旧Guest表。

保留PID:start_tick、数据revision、生命周期锁、挂载归属及网络账本核验。导入包不应携带宿主控制器，不执行ROM中的安装脚本。关键兼容材料由APK profile核验。

ROM/密钥/账号/设备原始截图和日志不提交或公开。构建产物放out，原始验收材料放validation/private，结论写docs/06_验收记录.md，区分通过、未验证和限制。发布或推送必须有用户授权。

公开发布采用tools/prepare_public_snapshot.py生成out/publish/IrisBox独立Git历史；原工作区旧开发历史不直接推到公开仓库。旧版来源和回退记录保留真实事实，新运行代码和资源统一Iris命名。大型镜像/源码包在out/release，仅用已核验发行附件或真实网盘链接与SHA-256；不得虚构下载地址。
