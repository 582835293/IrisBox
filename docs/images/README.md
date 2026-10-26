# 实机图片 / Device captures

2026-10-24，匹配的Raspberry Pi 4 AOSP13，IrisBox 0.1.4/e21；实例2为2048MiB。用户明确授权公开这些使用画面，并允许本地脚本裁切及遮挡隐私。原始材料留在私有验收目录，不随源码提交。

- `play-download.png`：Google计算器在实例Google Play内的实际安装阶段，裁切应用详情区。
- `play-installed.png`：同一应用安装完成后的Open/Uninstall按钮，裁切详情区。安装来源由PackageManager核验为com.android.vending，随后实际打开运行。
- `game-running.png`：用户扫码后实例内天天象棋运行的残局棋盘，裁去含榜单昵称的顶部和系统导航。微信及QQ扫码登录/游玩分别由使用者确认；单张图片不作为联网对局证明。
- `instance-previews.png`：实际管理列表，实例1停止、实例2运行并显示实时预览。裁去宿主系统栏，遮挡预览角落的个人Play Points信息。

图片仅使用确定性裁切、单块不透明遮挡及PNG重新编码，没有重绘其他UI、替换认证结果或伪造游戏状态。Google、腾讯等界面及商标属于相应权利人，图片用于说明本次兼容性测试，不表示合作或背书；Google程序和游戏APK不随项目分发。

These are real device captures published with the owner's explicit permission. Only deterministic cropping, one opaque privacy mask and PNG re-encoding were used; no AI redraw, changed certification label or fabricated game state. Original captures remain private. The list shows one stopped instance and one running preview; the puzzle-board picture alone does not prove an online match. The owner separately confirmed WeChat/QQ QR login and gameplay. Third-party UI/trademarks remain their owners' property; screenshots illustrate compatibility testing, without endorsement or redistribution of Google/game APKs.

## 2026-10-26 新增图片

- `clash-village.png`：实例2《部落冲突》18.647.5的新手村庄与教程对话；实际通过虹膜空间全屏窗口点击推进。未包含年龄页面、账号或玩家标识；不代表部落战、支付或长期对战测试通过。
- `youtube-playback.png`：YouTube 21.42.243 播放 Blender 官方《Big Buck Bunny》的原始视频区域，确定性裁去导航、账号及推荐区。素材归 Blender Foundation，原片和授权信息见 [官方视频](https://www.youtube.com/watch?v=aqz-KE-bpKQ)。
- `dual-instances.png`：0.1.5/e25 两个原实例同时运行，内存上限分别768/2048MiB；两张实时缩略图、桌面状态及原计数8/1可见。仅裁去宿主系统栏。最终e25已重新执行隔离、输入、网络、文件与音频回归；截图后两个预览序列继续增加。

New captures show an interactive Clash of Clans tutorial village, the cropped player area of Blender's official Big Buck Bunny on YouTube, and two simultaneously running instances. The village is not a battle/payment test. Dual-instance capture uses final e25; both preview sequences continued advancing after capture. Only deterministic cropping/re-encoding was applied; age/account screens and original evidence remain private.
