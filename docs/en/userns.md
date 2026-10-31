# User Namespaces and Data Migration (`v0.1.3+`)

**English** · [简体中文](../09_User_namespace隔离与迁移.md) · [Back to README](../../README.md)

---

## 1. Identity Isolation Model & UID/GID Ranges

IrisBox `0.1.2 / e13` did not isolate user identities. Starting in **`0.1.3 / e17`**, every instance is required to run inside a **distinct User namespace**. Missing, corrupted, or `revision`-mismatched mapping records fail closed with **no Host-UID0 fallback**.

| Property | Design Specification |
| :--- | :--- |
| **Per-Instance Range** | Maps **`100,000` contiguous UIDs/GIDs** covering Android `user 0`. |
| **Host Reserved Pool** | IrisBox reserves **`[2000000000, 2012800000)`**, strictly disjoint from the previous reference project's `[1100000000, 2000000000)`. Initial Host `UID 0` is never mapped. |
| **Identity Translation** | Guest `UID 0 (root)` → Host `BASE`; Guest `UID 1000 (system)` → Host `BASE + 1000`; ordinary apps → Host `BASE + App_UID` (e.g., `2000000000` for instance 1, `2000100000` for instance 2). |
| **Persistence & Lifecycle** | Root-owned `config/userns_v1/ID.map` binds the allocated range to the instance's userdata `revision`. **Reset** retains the range and advances `revision`; **Delete** permanently retires the range so it is never recycled. |

---

## 2. Startup Sequence & Filesystem Metadata Mapping

1. **Host Supervisor & Pipe Hold**: The native host supervisor (`irisbox_userns`) stays outside delegated cgroups, creates the `User` / `PID` / `Net` / `IPC` / `UTS` / `Cgroup` namespaces, and holds the child process on a synchronization pipe before any Guest code executes.
2. **Trusted Host Preparation & `metacopy=on` Overlays**:
   - The host writes `uid_map` / `gid_map` and places only the child into delegated resource groups.
   - Read-only shared `system.img` and `vendor.img` retain their original ownership; private overlays mount with `metacopy=on,index=off`, using bounded no-follow FD traversal to map ownership, named POSIX ACLs, and scoped file capabilities (`v2`/`v3`) without copying full ROM images. System and vendor views are sealed `ro,nosuid` before release.
3. **Private `/mnt` Pivot Alias (Preserving Mode `0700` Host Root)**:
   The host data root (`/data/irisbox`) remains strictly `0700`. Only inside the already-private preparer/child mount namespace is the prepared guest root temporarily bind-mounted over `/mnt` as a traversable `pivot_root` entry—no host-visible directory or weaker permission is introduced, and post-pivot Guest `/mnt` remains its own `tmpfs`.
4. **Release & `NS_GET_USERNS` Verification**:
   The child switches to mapped `root`, creates its own `mount` / `cgroup` namespaces, pivots root, closes inherited FDs (`>= 3`), and launches the adapted Android `init second_stage`. Parent `pidfd` / `PDEATHSIG` guards prevent orphan races, and `verify` confirms `PID:start_tick`, init's four UID/GID fields, and all six non-user namespace owners via `NS_GET_USERNS`.

---

## 3. Subsystem Adaptations

- **Display, Input & Audio Connectors**: Display/input and the 48 kHz PCM audio bridge use credential-dropped connector helpers that authenticate with the host controller, drop supplementary groups, switch to mapped `BASE` UID, and clear capabilities before connecting to Guest sockets and handing verified FDs to `BufferGateway` or host `AudioTrack`.
- **Network BPF Maps & Guest Magisk**: The network helper derives private BPF map ownership (`BASE + 1000`) directly from verified kernel `uid_map`/`gid_map` entries while preserving the shared dispatcher. Guest Magisk mounts its own Guest-owned `/sbin` `tmpfs` and retains its per-instance authorization database and modules.
- **System Services & Alarm JNI**: Includes delegated `vmpressure` handling, a `CgroupEventControl` alias, zero `RTPRIO` hard limit, and non-waking alarm fallback on `EPERM` via the verified `libalarm_jni.so` prebuilt (SHA-256 `e4a50b33128b0d92380e5f24bd46890be388e2b9a001a8908cab7c24e9e9a7ae`, patch in `reference/patches/userns-alarm.patch`, provenance in `reference/userns-provenance.json`). Physical Bluetooth remains on the host.

---

## 4. Stopped-State Migration for Existing `0.1.2 / e13` Installations

New instances receive mappings automatically upon creation. Existing `0.1.2 / e13` userdata must be backed up and migrated while stopped:

```powershell
python tools/upgrade_engine.py --from-apk out/IrisBox-0.1.2.apk --apply
python tools/device.py install
# Open the new App normally and confirm all instances remain stopped, then:
python tools/migrate_userns.py
python tools/migrate_userns.py --apply
```

- **Independent Inode Backups**: `migrate_userns.py` holds the lifecycle lock, copies `data`, `upper`, `vendor.upper`, `metadata`, and config to independent inodes under `/data/irisbox/maintenance/userns_migration_v1/ID_REVISION`, preserves modes/xattrs/SELinux labels, and verifies per-file SHA-256 digests.
- **Rollback Discipline**: **Source rollback, engine rollback, and userdata rollback are three separate operations.** Once a guest boots with mapped ownership, an older APK/engine cannot directly reuse those trees, and you must **never** recursively `chown` host `/data` or shared lower images. Always stop the instance, preserve new state, and restore from the matching verified snapshot under `/data/irisbox/maintenance/userns_migration_v1/ID_REVISION`.

---

## 5. Validation & Security Scope

`tests/userns_regression.py` validates live kernel mappings, `NS_GET_USERNS` ownership, stale-tick rejection, and disposable `0600` file access boundaries (confirming Guest `root` can read its own domain's `0600` file but is denied Host `UID 0` and foreign-instance `0600` files). See the [Validation Report](validation.md).

While User namespaces eliminate shared Host-`UID 0` identity, instances still share the Linux 5.15 kernel and V3D GPU driver on a Permissive host; this is **not** a certified security sandbox against hostile code. Upstream references: [Linux User Namespaces (man7)](https://man7.org/linux/man-pages/man7/user_namespaces.7.html) · [Kernel BinderFS](https://docs.kernel.org/admin-guide/binderfs.html).
