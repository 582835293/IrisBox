# 实机图片 / Device captures

2026-10-24，匹配的Raspberry Pi 4 AOSP13，IrisBox 0.1.4/e21；实例2为2048MiB。用户明确授权公开这些使用画面，并允许本地脚本裁切及遮挡隐私。原始材料留在私有验收目录，不随源码提交。

- `play-download.png`：Google计算器在实例Google Play内的实际安装阶段，裁切应用详情区。
- `play-installed.png`：同一应用安装完成后的Open/Uninstall按钮，裁切详情区。安装来源由PackageManager核验为com.android.vending，随后实际打开运行。
- `game-running.png`：用户扫码后实例内天天象棋运行的残局棋盘，裁去含榜单昵称的顶部和系统导航。微信及QQ扫码登录/游玩分别由使用者确认；单张图片不作为联网对局证明。
- `instance-previews.png`：实际管理列表，实例1停止、实例2运行并显示实时预览。裁去宿主系统栏，遮挡预览角落的个人Play Points信息。

图片仅使用确定性裁切、单块不透明遮挡及PNG重新编码，没有重绘其他UI、替换认证结果或伪造游戏状态。Google、腾讯等界面及商标属于相应权利人，图片用于说明本次兼容性测试，不表示合作或背书；Google程序和游戏APK不随项目分发。

These are real device captures published with the owner's explicit permission. Only deterministic cropping, one opaque privacy mask and PNG re-encoding were used; no AI redraw, changed certification label or fabricated game state. Original captures remain private. The list shows one stopped instance and one running preview; the puzzle-board picture alone does not prove an online match. The owner separately confirmed WeChat/QQ QR login and gameplay. Third-party UI/trademarks remain their owners' property; screenshots illustrate compatibility testing, without endorsement or redistribution of Google/game APKs.

## 2026-10-26 新增图片

- `clash-village.png`：实例2《部落冲突》18.647.5的新手村庄与教程对话；最终e25正常重连后，通过虹膜空间全屏窗口点击，将欢迎对话推进到下一句。未包含年龄页面、账号或玩家标识；不代表部落战、支付或长期对战测试通过。
- `youtube-playback.png`：YouTube 21.42.243 播放 Blender 官方《Big Buck Bunny》的原始视频区域，确定性裁去导航、账号及推荐区。素材归 Blender Foundation，原片和授权信息见 [官方视频](https://www.youtube.com/watch?v=aqz-KE-bpKQ)。
- `dual-instances.png`：0.1.5/e25 两个原实例同时运行，内存上限分别768/2048MiB；两张实时缩略图、桌面状态及原计数8/1可见。仅裁去宿主系统栏。最终e25已重新执行隔离、输入、网络、文件与音频回归；截图后两个预览序列继续增加。

New captures show an interactive Clash of Clans tutorial village, the cropped player area of Blender's official Big Buck Bunny on YouTube, and two simultaneously running instances. The village is not a battle/payment test. Dual-instance capture uses final e25; both preview sequences continued advancing after capture. Only deterministic cropping/re-encoding was applied; age/account screens and original evidence remain private.

- `angry-offline.png`：Angry Birds 2重建获准的首次运行数据后进入Main场景，但更新提示离线；这是未通过项目的实际画面，不作为游玩成功证明。无账号、年龄或玩家标识，只有PNG重新编码。

`angry-offline.png` records the unresolved official-update offline prompt, not successful gameplay; no account/age/player identifier is present.


## 2026-10-27：0.1.6/e27 交换空间与双游戏

- `swap-settings-zh.png`、`swap-settings-en.png`：真实App的2GiB/重启自动恢复设置，确定性裁去宿主系统栏及导航区域；没有账号或设备标识。
- `angry-gameplay.png`：e27双游戏期间的Red技能与实际目标破坏；`angry-tutorial.png`为第一段99,792分后进入第二段的画面。此前e25单实例条件也通过，当前公开文件更新为e27真实验收。
- `dual-games.png`：同一宿主同时运行的两实例，左侧1024MiB部落冲突新手村庄，右侧2048MiB愤怒的小鸟2教程结算203,332分；宿主2GiB交换空间。仅裁去宿主系统栏和底部导航，没有拼接两次单独运行的图片。部落冲突曾因空闲断线，原提示保留私有记录；公开双开图是按原有Reload Game重连后拍摄。

The new EN/ZH swap settings and dual-game captures are actual 0.1.6/e27 device frames. Only deterministic cropping/RGB PNG re-encoding was used. The paired list is one real simultaneous capture, not a composite of separate runs. Clash’s normal idle-disconnect evidence stays private; the published pair follows its original reload flow. Angry Birds 2 shows real target destruction, then a second tutorial room at 99,792 and a final 203,332 result. No age, account, player identifier or personal avatar is present. This does not establish long-term battles, stable frame rates or device certification. Game/Google APKs are not distributed.
