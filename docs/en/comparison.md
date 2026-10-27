# Learning from Project Iris V3.0

[中文](../05_对比与学习.md)

The previous engine established that Android userspace could boot under a shared host kernel with graphics, input, networking and multiple instances. IrisBox turns that engine into an installable App with trusted package import, versioned payloads and repeatable deployment.

| Topic | Previous project | IrisBox | Read |
|---|---|---|---|
| Deployment | Computer-provisioned runtime | Signed APK installs its verified engine and host tools | SetupService, EngineInstaller, HostTools |
| ROM | Fixed device image paths | SAF URI, manifest/SHA validation, independent library | PackageArchive, make_rom.py |
| Adaptation | Runtime-fixed RC/libraries | ROM compat pinned by APK profile | inner.sh, package_engine.py |
| Instances | Pre-existing defaults | Empty registry, explicit creation, IDs 1007 onward | instances.sh, InstanceCatalog |
| Versioning | One fixed ROM | Immutable per-instance ROM ID; default only affects new instances | common.sh, config/rom_v1 |
| Isolation | Namespaces, Binder, overlays, cgroups | Reused mechanisms with separate resource ownership | wrapper.sh, inner.sh |
| Network | Shared host dispatcher and guest maps | Verified reuse, own cookie/map pairs, no restore | network.sh, policy.c |
| Google | Static programs extracted from a matching host | Optional matching ROM layer; host Google unnecessary | guest_layer.sh |
| Recovery | Development workflow | App reconnect, bounded helpers and transactional maintenance | HostRootEntry, audio.sh |
| Internationalization | Chinese-first viewer | EN default, Chinese resources and App locale switch | I18n, translations.json |

## Eight exercises

1. Mark where each process runs: App UID, host root, or guest PID 1. Explain why root in the wrong mount namespace fails.
2. Trace a file from content URI through ZIP validation to root staging and registration. Test a bad SHA and `../` path without touching existing ROMs.
3. Trace ROM ID, data revision and process session independently. Explain why an old overlay cannot be blindly reused with another ROM.
4. Follow creation → lifecycle lock → cgroups → namespaces → pivot_root → init. Distinguish a live init from a ready desktop.
5. Compare two guests' namespaces, Binder devices, data and files. Stop a disposable test guest and verify the other session remains unchanged.
6. Separate network reachability from UID permission. Explain why DHCP success does not prove a normal App can open sockets.
7. Follow JPEG preview separately from fullscreen buffer/fence release and input geometry. Management HTTP is not the fullscreen media path.
8. Reopen the App and restore its manager while retaining guest sessions. Practise reset/delete only in a disposable instance with explicit confirmation.

Additional regression work introduced playback audio supervision, mediaextractor restoration, a private Traceur mask and preview-process exit handling. Kill only an identity-verified audio helper: its replacement and host player should recover while the guest's main session stays unchanged. Encode/play an MP4 from an ordinary UID and inspect actual playback progress, rather than package presence.

For every experiment record the input, identity checks, execution namespace, resources changed, evidence of completion and cleanup behavior. Historical results from the previous project are useful provenance; they do not automatically validate a new ROM import or fresh host.

## Two productization exercises

Trace BusyBox across the boundary: the host uses `/data/irisbox/host/bin/busybox`; after pivot_root only the verified guest copy `/system/bin/busybox` exists. Audit both the post-pivot script and commands passed by host callers through `ctl exec`. A path on the development host is not proof that it exists on a new host or inside a guest.

Boot a ROM which actually omits optional components, with new userdata. Persistent overlays and Google layers can hide missing core directories. The public AOSP test exposed a missing `product/etc/init`; e13 now creates it in the verified private overlay. Test both retained data and a genuinely new instance.

## 0.1.3: follow the upstream identity isolation work

The previous local Iris project integrated User namespaces on 2026-10-20, after the e13 extraction checkpoint. This port follows its complete ownership/ACL/capability, metacopy, cgroup, network, display, Magisk and alarm adaptations. IrisBox additionally needs a private pre-pivot alias for its 0700 Host data root and a mapped PCM connector. Its persistent UID range is disjoint. See [the migration guide](userns.md).

## 0.1.5: real applications expose isolation dependencies

UID/GID maps alone do not establish usable Android storage. Trace an ordinary getExternalFilesDir request through StorageManager, vold PrepareAppDirFromRoot/SetQuotaProjectId and the kernel fileattr check. A populated project-ID map still cannot override the matched kernel's prohibition on noninitial-namespace quota changes. The adapter handles only that unsupported tagging operation, retaining ownership/ACL failures and mapped credentials. Test first directory creation plus write/read, rather than treating map existence as success; see engine/native/storage/README.md.

Physical-camera isolation also needs a functioning framework contract. YouTube queried camera count during startup; removing cameraserver blocked a worker and the main thread until ANR. Retaining the framework service with physical providers disabled returns zero devices. Following the actual ANR lock owner explains the failure better than increasing timeouts or patching YouTube.

Finally, changing a package identity changes its UID and authorization. Validate exclusive ownership before revoking a retired UID, request normal grants for new packages, and check absolute controller paths, display pointers, audio RC, JNI names and generated binaries. Stopped full snapshots and transaction rollback are part of the migration design.


### Host resource management in 0.1.6

Host swap is a global resource; ROMs and guest Root must not supply paths to its controller. The App uses fixed capacities, its own directory, file-identity receipts, an owned Magisk boot hook and shared startup locking. Android mksh marks extra FDs close-on-exec: test inheritance on the device and close it in long-lived children. e26 safely rejected the first attempt; e27 explicitly inherits the lock FD.

An unfired bird alone did not establish a memory/input defect. A held-input native frame showed real slingshot movement and a red tutorial cross; green alignment launched normally. Correlate events, native frames, both sessions, live-preview sequences and OOM counters to distinguish tutorial gates, idle disconnects and runtime failures. A short dual-game test does not establish long-run stability or frame rate.
