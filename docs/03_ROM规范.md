# RootVMOS ROM 包规范 v1

扩展名 `.irisrom`，内容为普通 ZIP/ZIP64，第一项必须是UTF-8 `rom.json`，不含顶层目录。镜像为raw ext4，不接受Android sparse、super、boot、OTA刷机包。

```text
rom.json
system.img
vendor.img
compat/iris_init
compat/guest.rc
compat/guest_vendor.rc
compat/loaded_files.txt
compat/rc_candidate/...
compat/payload/...
compat/google/...       可选；包含完整静态rootfs，不含账号
```

清单示意（实际每个文件都必须列出真实size和64位小写sha256）：

```json
{
  "formatVersion": 1,
  "id": "rpi4-a13-20260916",
  "name": "树莓派4 Android13",
  "abi": "arm64-v8a",
  "androidApi": 33,
  "compatibility": "rpi4-a13-v1",
  "hostFingerprint": "固定适配宿主指纹，见使用说明",
  "google": false,
  "files": {
    "system.img": {"size": 2147483648, "sha256": "真实摘要"}
  }
}
```

ID仅允许`[a-z0-9][a-z0-9._-]{0,63}`，不能含`..`。归档路径只能包含受限ASCII字符，不能有绝对路径、反斜杠、空路径段、`.`/`..`段；拒绝目录条目、重复条目和未声明项。最多512个文件，清单最多128KiB，单文件最多4GiB，ROM解压总大小最多6GiB。

当前profile仅接受已提取并核验的A13适配材料。适配材料由ROM携带，关键摘要同时封存在APK的`rom-profile.json`中；修改适配需开发者更新profile并重新构建APK，不允许导入包自行替换宿主控制器。

`compat/payload`包括DocumentsUI、gralloc唯一ID库、no-BPF网络库和Connectivity兼容产物；`compat/rc_candidate`及loaded_files是原厂RC白名单。这些材料不是适用于任意Android13的通用补丁。

可选Google层使用`compat/google/{component.id,component_manifest.json,files.sha256,rootfs/...}`。当前为已固定的54项静态文件，由ROM携带，启用后作为system lower的前置层；不再调用从宿主`/system`复制的prepare路径。新实例必须独立登录，组件完整不证明认证或商店长期可用。

实例Magisk组件属于引擎可选功能，默认关闭；Host Magisk授权并不自动打开Guest root。

制作命令：

```powershell
python tools/make_rom.py --system out/rom-input/system.img --vendor out/rom-input/vendor.img --with-google --out out/rpi4-a13-google.irisrom
```

省略`--with-google`生成不含Google的包。不要以相同ROM ID导入不同内容；同一底包带/不带Google两个版本应使用不同`--id`。发行包和账号/实例备份必须分开。原始镜像和Google载荷没有加入Git。
