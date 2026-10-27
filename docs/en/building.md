# Building and maintenance

Current **0.1.6/e27** adds App host-swap settings. Already mapped 0.1.5/e25 data needs no repeated UID or namespace migration: normally stop all new guests, retain the exact previous APK, run `python tools/upgrade_engine.py --from-apk out/IrisBox-0.1.5.apk --apply`, then install the same-signed 0.1.6 APK. Automatic swap restoration passed a real host reboot; fresh flashing retains its separate unverified boundary. See [swap/recovery](low-memory.md).


Current version: **0.1.5/e25**. Existing 0.1.4/e21 installations require the stopped-state [Iris namespace migration](namespace-migration.md). Historical upgrade examples below do not replace this procedure.

[中文](../04_构建与维护.md)

Use JDK 17, Python 3, Android SDK platform android-30, build-tools 34.0.0 and NDK 28.2.13676358. The APK targets API 33; SDK 30 is the compilation interface baseline.

```powershell
$env:JAVA_TOOL_OPTIONS='-Xmx256m -Xms16m -XX:+UseSerialGC'
./build.ps1 -SdkDir "$env:LOCALAPPDATA/Android/Sdk" -JdkDir 'C:/path/to/jdk-17'
```

The full build generates EN/ZH resources, packages the fixed host BusyBox, builds the ordinary Guest download browser, compiles native helpers, AudioBridge, Broker and Gateway, normalizes JAR times, generates engine/profile hashes, compiles Viewer JNI/Java, links resources with generated R.java, aligns and signs the APK. Current development output: `out/IrisBox-0.1.5.apk`. `-AppOnly` is appropriate only when the engine is unchanged and already packaged. Engine changes require a new engine version and full build.

The local signing key is generated under the ignored App build directory. Preserve the private key for updates; it is not published. A clone creates a different development key and cannot overwrite a release-signed installation. This preview keeps its original development signing identity; there is no automated release-signing service.

The Guest browser has its own ignored `app/guest-browser/build/browser-test.keystore`. Retain it for browser updates as well. A new public clone's signed browser APK differs because of its key. Compare the non-browser files and the browser's unsigned ZIP entries separately. The e20 full public-source build passed its other 49 files and unsigned browser entries. The e21 candidate adds `orientation.sh` for 51 payloads; its independent full public-source build matched all 50 non-browser payloads and unsigned browser entries. This replaces the all-payload-hashes comparison for earlier versions. See the [browser source notes](../../app/guest-browser/README.md).

Some matched Android libraries, init, input/file clients and DocumentsUI are fixed prebuilt compatibility inputs. Their patches and source references are included. Building the host APK is **not** a from-zero rebuild of every Android component. The producer can be rebuilt against the matched AOSP private headers and screencap Ninja flags using `engine/native/buffer/build_direct.py`. `tools/build_producer.py` uses a separate temporary build directory, leaving AOSP inputs read-only.

Host construction uses the [Android13 Raspberry Vanilla manifest](https://github.com/raspberry-vanilla/android_local_manifest/tree/android-13.0) and `lunch aosp_rpi4-userdebug`, then `make bootimage systemimage vendorimage` and the board's image script. Exact recorded repositories are in `reference/host-profile.json`; current local experimental changes are not asserted to reproduce factory image bytes. Kernel source/build instructions are linked separately in third-party notices.

## Upgrade and recovery

There is no automatic cross-version engine upgrade UI yet. Stop every IrisBox guest normally and retain the APK corresponding to the installed engine. The development maintenance tool verifies that baseline, backs up fixed engine files, updates them transactionally and preserves config/ROM/instance data:

```powershell
python tools/upgrade_engine.py --from-apk out/IrisBox-0.1.2.apk --apply
python tools/device.py install
# Open the App with all legacy instances stopped, then:
python tools/migrate_userns.py
python tools/migrate_userns.py --apply
```

An incomplete upgrade leaves `engine-upgrade.pending`; the App refuses startup. The tool attempts rollback, or `--recover` uses the retained transaction recovery script. This backup is not a userdata backup. Sudden power loss was not simulated.

The e13 → e17 upgrade additionally requires the independent, stopped-state userdata backup and ownership migration described in [User namespace migration](userns.md). After the first mapped boot, restoring an older engine alone is unsafe. The original full data snapshot must remain available. Fresh instances receive mappings automatically.

Uninstalling the APK preserves `/data/irisbox`. Complete removal requires stopping/deleting only this project's instances, verifying no owned mounts/cgroups remain and stopping its manager before targeted cleanup. Never remove `/data/guest`, flush shared BPF or firewall tables, or reset user data to resolve host compatibility errors.

Keep Android scripts LF. The packager rejects CR bytes. Do not rerun the initial extraction/adaptation scripts over the maintained source tree. Raw logs, screenshots, account baselines and signing keys stay private.

## Device test configuration

Set `IRISBOX_SERIAL` to your own ADB serial before using `tools/device.py` or the regression tools. The public source snapshot removes private build-machine and device defaults. Pass your own `--host`, `--key` and `--aosp` for remote producer builds. Initial migration/export scripts are historical recipes, not normal deployment steps.

The 0.1.3 public snapshot was independently fully built with the documented toolchain: all 49 engine payload hashes matched the tested local engine. The APK signature differs when a new development key is generated. The fixed alarm JNI library is a verified prebuilt from the previous project's User namespace integration; its source patch and hash are recorded in `reference/userns-provenance.json`. This does not claim a full AOSP rebuild.

For the optional remote producer rebuild, pass `-RebuildProducer -ProducerHost builder@YOUR_HOST -ProducerKey C:/path/to/key -AospDir /path/to/aosp-rpi4`, or set `IRISBOX_BUILD_HOST`, `IRISBOX_BUILD_KEY` and `IRISBOX_AOSP_DIR`. These are your own build-machine parameters; no developer machine address or key is bundled.

Exact factory USB boot inputs are retained in `reference/host-boot/`: cmdline.txt, config.txt and fstab.rpi4. Compare/apply these only in your own isolated device-tree checkout before building; do not overwrite an active project. The factory fstab uses USB sda2/3/4, while an upstream tree may target SD/mmc. The clean-image utility preserves the static partitions and changes only the blank userdata partition size and its MBR length.

## Local 0.1.4 / e21 candidate

Live orientation uses a trusted Host `orientation.sh` and authenticated manager action. `config/orientation_v1/ID.mode` accepts only auto/landscape/portrait. The lifecycle lock, PID:start_tick, instance revision and previous configuration are checked before changing the verified Guest WindowManager. Boot monitoring reapplies saved selections before marking the desktop ready. Actual frame geometry drives display and input; held events are cancelled and input pauses during a change. Imported ROMs do not supply this controller.

The complete local e21 build passed audits for 51 engine, 116 ROM, 11 Root and 54 private Google inputs, plus 343 Host locale entries. `tests/orientation_regression.py` passed real toolbar selection, rotated keyboard input, GPU and stale-session checks; a separate Guest-restart check retained portrait selection. A separate sanitized 329-file source snapshot fully rebuilt, matching 50 non-browser payloads and unsigned browser ZIP entries. Its 51/116/11 input and 343 locale audits passed; private Google files and original signing keys were absent. Raw evidence remains private; see Downloads for release status.

Already mapped 0.1.3/e17 data does not need another ownership migration. Keep the previous APK and data backup, normally stop all IrisBox guests, put the current 0.1.4 APK in out, then run `python tools/upgrade_engine.py --from-apk out/IrisBox-0.1.3.apk --apply`. Install the same-signer 0.1.4 APK and reopen the App to restore its manager. Do not repeat the old e13 ownership migration over mapped data. Legacy project directories remain read-only.
