# Building and maintenance

[中文](../04_构建与维护.md)

Use JDK 17, Python 3, Android SDK platform android-30, build-tools 34.0.0 and NDK 28.2.13676358. The APK targets API 33; SDK 30 is the compilation interface baseline.

```powershell
$env:JAVA_TOOL_OPTIONS='-Xmx256m -Xms16m -XX:+UseSerialGC'
./build.ps1 -SdkDir "$env:LOCALAPPDATA/Android/Sdk" -JdkDir 'C:/path/to/jdk-17'
```

The full build generates EN/ZH resources, packages the fixed host BusyBox, compiles native helpers, AudioBridge, Broker and Gateway, normalizes JAR times, generates engine/profile hashes, compiles Viewer JNI/Java, links resources with generated R.java, aligns and signs the APK. Output: `out/IrisBox-0.1.2.apk`. `-AppOnly` is appropriate only when the engine is unchanged and already packaged. Engine changes require a new engine version and full build.

The local signing key is generated under the ignored App build directory. Preserve the private key for updates; it is not published. A clone creates a different development key and cannot overwrite a release-signed installation. This preview keeps its original development signing identity; there is no automated release-signing service.

Some matched Android libraries, init, input/file clients and DocumentsUI are fixed prebuilt compatibility inputs. Their patches and source references are included. Building the host APK is **not** a from-zero rebuild of every Android component. The producer can be rebuilt against the matched AOSP private headers and screencap Ninja flags using `engine/native/buffer/build_direct.py`. `tools/build_producer.py` uses a separate temporary build directory, leaving AOSP inputs read-only.

Host construction uses the [Android13 Raspberry Vanilla manifest](https://github.com/raspberry-vanilla/android_local_manifest/tree/android-13.0) and `lunch aosp_rpi4-userdebug`, then `make bootimage systemimage vendorimage` and the board's image script. Exact recorded repositories are in `reference/host-profile.json`; current local experimental changes are not asserted to reproduce factory image bytes. Kernel source/build instructions are linked separately in third-party notices.

## Upgrade and recovery

There is no automatic cross-version engine upgrade UI yet. Stop every IrisBox guest normally and retain the APK corresponding to the installed engine. The development maintenance tool verifies that baseline, backs up fixed engine files, updates them transactionally and preserves config/ROM/instance data:

```powershell
python tools/upgrade_engine.py --from-apk out/qa/IrisBox-0.1.2-e12.apk --apply
```

An incomplete upgrade leaves `engine-upgrade.pending`; the App refuses startup. The tool attempts rollback, or `--recover` uses the retained transaction recovery script. This backup is not a userdata backup. Sudden power loss was not simulated.

Uninstalling the APK preserves `/data/rootvmos`. Complete removal requires stopping/deleting only this project's instances, verifying no owned mounts/cgroups remain and stopping its manager before targeted cleanup. Never remove `/data/guest`, flush shared BPF or firewall tables, or reset user data to resolve host compatibility errors.

Keep Android scripts LF. The packager rejects CR bytes. Do not rerun the initial extraction/adaptation scripts over the maintained source tree. Raw logs, screenshots, account baselines and signing keys stay private.

## Device test configuration

Set `IRISBOX_SERIAL` to your own ADB serial before using `tools/device.py` or the regression tools. The public source snapshot removes private build-machine and device defaults. Pass your own `--host`, `--key` and `--aosp` for remote producer builds. Initial migration/export scripts are historical recipes, not normal deployment steps.

The reviewed public snapshot was independently fully built with the documented toolchain: all 44 engine payload hashes matched the tested local engine. The APK signature differs when a new development key is generated.

For the optional remote producer rebuild, pass `-RebuildProducer -ProducerHost builder@YOUR_HOST -ProducerKey C:/path/to/key -AospDir /path/to/aosp-rpi4`, or set `IRISBOX_BUILD_HOST`, `IRISBOX_BUILD_KEY` and `IRISBOX_AOSP_DIR`. These are your own build-machine parameters; no developer machine address or key is bundled.

Exact factory USB boot inputs are retained in `reference/host-boot/`: cmdline.txt, config.txt and fstab.rpi4. Compare/apply these only in your own isolated device-tree checkout before building; do not overwrite an active project. The factory fstab uses USB sda2/3/4, while an upstream tree may target SD/mmc. The clean-image utility preserves the static partitions and changes only the blank userdata partition size and its MBR length.
