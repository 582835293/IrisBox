# Architectural Comparison & Learning Route

**English** · [简体中文](../05_对比与学习.md) · [Back to README](../../README.md)

---

## 1. From `Project Iris V3.0` to `IrisBox`

The previous reference project (`Project Iris V3.0`) demonstrated how an Android userspace boots under a shared Linux kernel with graphics, input, networking, and multiple instances. **IrisBox (虹膜空间)** transforms that runtime into a self-contained, installable product with verified package imports, strict identity isolation, and repeatable maintenance.

| Topic | Previous Project (`Project Iris V3.0`) | IrisBox (`虹膜空间`) | Key Source Files |
| :--- | :--- | :--- | :--- |
| **Deployment** | Computer-provisioned runtime scripts | Signed APK installs verified engine & private host BusyBox | `SetupService`, `EngineInstaller`, `HostTools` |
| **ROM Import** | Fixed on-device image paths | SAF URI picker, manifest & per-file SHA-256 checks, isolated library | `PackageArchive`, `make_rom.py` |
| **ROM Adaptation** | Runtime-hardcoded RC & libraries | `compat/` carried by ROM and pinned by APK `rom-profile.json` | `inner.sh`, `package_engine.py` |
| **Instance Registry** | Pre-seeded default instances (`7`, `8`) | Empty initial registry; user-created IDs `1007`–`1134` | `instances.sh`, `InstanceCatalog` |
| **ROM Versioning** | Single global ROM path in `common.sh` | Per-instance `config/rom_v1/ID.id`; new imports only change future defaults | `common.sh`, `instances.sh` |
| **Identity Isolation** | Six namespaces, BinderFS, Overlays, cgroups | Seven namespaces (**including User namespace**) + disjoint IrisBox resources | `irisbox_userns.c`, `wrapper.sh`, `inner.sh` |
| **Network Policy** | Global BPF dispatcher + guest UID maps | Verified dispatcher reuse, private cookie/map pairs (`1007`–`1134`), no `restore` | `network.sh`, `policy.c` |
| **Google Support** | Extracted live from a matching host `/system` | Optional static 54-file ROM layer; host Google installation unnecessary | `guest_layer.sh`, `rom/compat/google/` |
| **Recovery & Audio** | Developer shell scripts | App reconnect, supervised 48 kHz PCM audio bridge, transactional upgrades | `HostRootEntry`, `audio.sh`, `AudioBridge` |
| **Localization** | Chinese-only UI strings | English default, `values-zh` resources, persistent in-App locale selector | `I18n.java`, `translations.json` |

---

## 2. Eight Core Architecture Exercises

1. **Map Execution Contexts**: Label where each component runs (`App UID`, `Host root`, or `Guest PID 1` mapped UID) across `SetupActivity` → `RootShell` → `BufferBroker` → `ctl.sh` → `inner.sh` → `Guest init`. Explain why `su` without `--mount-master` lands in the wrong mount namespace.
2. **Trace a ROM Import**: Follow a `content://` URI from the system file picker through ZIP entry validation, SHA-256 verification, root staging, and atomic registration. Verify that bad hashes and `../` traversal paths are rejected before registration.
3. **Trace Version Bindings**: Track `ROM ID`, userdata `revision`, and running `session (PID:start_tick)` independently. Explain why an existing instance's Overlay `upper` layer cannot be blindly mounted over a different base ROM.
4. **Trace a Cold Boot**: Follow instance creation → lifecycle lock → cgroups → User/mount/PID namespaces → `pivot_root` → stock `init second_stage`. Distinguish a live `init` process from a `Desktop ready` state.
5. **Verify Multi-Instance Isolation**: Compare two running guests' namespaces, private `binderfs` nodes, and `upper`/`data` trees. Stop a disposable test guest and confirm the other guest's session and counters remain untouched.
6. **Separate Routing from Socket Permission**: Explain why IP routing/NAT and DHCP success do not allow an ordinary app without `INTERNET` permission to create sockets, and why BPF maps bind to the kernel `netns cookie` rather than the UI instance number.
7. **Compare Preview vs. Fullscreen Graphics**: Contrast the 1 fps HTTP JPEG snapshot path with the `:display` process's shared hardware-buffer, fence release, and pointer-geometry pipeline.
8. **Practise Non-Destructive Recovery**: Close and reopen the App, stop and restore `BufferBroker`, and test `Reset` / `Delete` in a disposable instance while confirming that active guest sessions and host `boot_id` remain undisturbed.

---

## 3. Five Productization & Debugging Case Studies

### 1. Supervised Audio Recovery & Media Restoration (`v0.1.1`)
Terminate only an identity-verified guest audio helper process and observe guest `init` restart the capture helper while the host supervisor replaces `AudioTrack` and resumes PCM playback—all without changing the main guest `PID:start_tick`. Encode and play back an H.264 MP4 under an ordinary UID to verify `mediaextractor` and software codec restoration.

### 2. BusyBox Paths Across `pivot_root` & Pure-AOSP Cold Boot (`v0.1.2`)
- **Path Boundary**: Host scripts invoke `/data/irisbox/host/bin/busybox`, whereas post-`pivot_root` guest commands must use `/system/bin/busybox`. Audit both guest RC scripts and commands passed from the host via `ctl.sh exec`.
- **Unmasked Base ROMs**: Booting a pure AOSP ROM without a Google layer or pre-existing overlay exposed a missing `product/etc/init` directory, prompting `e13` to create required core directories inside the verified private overlay. Always test both retained userdata and fresh pure-AOSP instances.

### 3. Porting User Namespaces (`v0.1.3`)
Following the reference project's 2026-10-20 User namespace integration required coordinated handling of ownership/ACL/capability mapping, OverlayFS `metacopy=on,index=off`, delegated cgroups, BPF map ownership, credential-dropped display/audio connectors, Magisk `/sbin` tmpfs mounting, and non-waking alarm fallback (`libalarm_jni.so`), plus a private `/mnt` bind alias so the host's `0700` `/data/irisbox` directory remains unweakened (see [User Namespaces and Migration](userns.md)).

### 4. Real Apps Expose Deep Isolation Dependencies (`v0.1.5`)
- **External Storage Project Quotas (`vold`)**: Tracing `getExternalFilesDir → StorageManager → vold::PrepareAppDirFromRoot → SetQuotaProjectId → kernel fileattr_set_prepare` revealed that even with `projid_map` populated, the matched Linux 5.15 kernel forbids non-initial User namespaces from changing project quota flags. The `e25` `vold` adapter skips **only** unsupported project-quota tagging/inheritance for strictly matched IrisBox mapped guests while preserving all ownership, mode, and ACL checks (see [`engine/native/storage/README.md`](../../engine/native/storage/README.md)).
- **Camera Framework Contract (YouTube Startup ANR)**: Disabling physical cameras by replacing `cameraserver` with `true` caused YouTube to block indefinitely in `Camera.getNumberOfCameras()` until ANR. Restoring the stock framework `cameraserver` while keeping physical providers disabled properly returns `0` cameras.
- **Package Identity Migration**: Changing the host package name to `com.irisbox.viewer` assigns a new Android UID requiring a normal host Magisk grant, full stopped-state snapshots, and updates across native JNI signatures, sockets, and controller paths.

### 5. Host Swap Locking & Game Input Diagnostics (`v0.1.6`)
- **`mksh` Lock Descriptor Inheritance**: Host swap is a global resource serialized against guest startup via shared file locks. Because Android `mksh` marks extra file descriptors `close-on-exec` by default (`e26` safely rejected configuration), `e27` explicitly passes the lock FD and closes it in long-lived child processes.
- **Correlating Input Events with Native Frames**: When diagnosing an unfired bird in *Angry Birds 2*, capturing a native frame while holding touch input revealed real slingshot movement and the game's red tutorial cross rejecting an off-guide angle—aligning with the green trajectory guide launched normally. Always correlate input events, native frames, preview sequences, and OOM counters before blaming runtime or input channels.
