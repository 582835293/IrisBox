# User namespaces and migration

[中文](../09_User_namespace隔离与迁移.md)

IrisBox 0.1.2/e13 did not isolate user identities. Version 0.1.3 requires a distinct User namespace for every instance. Missing, damaged or revision-mismatched mappings fail closed; there is no Host-UID0 fallback.

Each instance maps 100,000 UID/GIDs for Android user0. IrisBox reserves `[2000000000,2012800000)`, disjoint from the previous Iris project's `[1100000000,2000000000)`. Guest UID0 becomes Host BASE; system becomes BASE+1000. Initial Host UID0 is not mapped. Root-owned, private `config/userns_v1/ID.map` records bind the range to the data revision. Allocation is locked and committed atomically; reset retains its range and updates the revision, while deletion never recycles a range.

## Startup and compatibility

The native Host supervisor creates the User/PID/net/IPC/UTS/cgroup namespaces and holds the child on a pipe. Trusted Host preparation sets up ROM overlays, RC, devices and networking without executing Guest programs. Only the child enters delegated resource groups. Read-only shared images keep their original ownership; private overlays use `metacopy=on,index=off`, with bounded no-follow FD traversal for ownership, named POSIX ACLs and scoped file capabilities. System/vendor views are sealed read-only and nosuid before release.

The released child switches to mapped root, creates its own mount/cgroup namespaces, pivots root, closes inherited FDs and runs the adapted Android init second stage. Parent pidfd/PDEATHSIG checks protect supervisor death. Exec entry opens validated handles, enters User first, switches identity, enters the remaining namespaces and closes Host FDs. Verification checks PID:start_tick, init's four UID/GID fields, mapping records and all six namespace owners through NS_GET_USERNS.

The Host data root remains mode 0700. Only inside the already-private preparer/child mount namespace, the prepared root is temporarily bound over `/mnt` as an accessible pivot entry. No Host-visible directory or weaker permission is introduced. After pivot, Guest `/mnt` remains its own tmpfs.

Display and input use a credential-dropped connector and authenticated FD handoff; fullscreen still uses shared buffers. IrisBox's separate audio bridge also connects as mapped root before passing its PCM FD to Host AudioTrack. The network helper derives private-map ownership from verified kernel maps, preserves the shared dispatcher and never restores policy or removes previous-project entries. Magisk creates its own Guest-owned `/sbin` mount while retaining its existing per-instance authorization and module data.

Android compatibility includes delegated vmpressure handling, a CgroupEventControl alias, zero realtime priority, and non-waking alarm-clock fallback only after EPERM in mapped guests. Physical Bluetooth remains on the Host; Viewer forwards input. The fixed JNI library is copied only into private Guest overlays, never Host system. Its source patch and SHA-256 are recorded in `reference/patches/userns-alarm.patch` and `reference/userns-provenance.json`. The complete local build recompiles the launcher, entry helper, INET helper, audio, Gateway, Broker and APK; it is not a full AOSP rebuild.

## Existing e13 installations

New instances receive mappings through the ordinary App creation flow. Stop existing IrisBox instances before maintenance:

```powershell
python tools/upgrade_engine.py --from-apk out/IrisBox-0.1.2.apk --apply
python tools/device.py install
# Open the new App normally; keep the instances stopped.
python tools/migrate_userns.py
python tools/migrate_userns.py --apply
```

The migration tool holds the lifecycle lock, copies data/upper/vendor.upper/metadata and configuration to independent inodes, preserves metadata/xattrs, and compares source and backup file SHA-256 lists. Backups remain under `/data/irisbox/maintenance/userns_migration_v1/ID_REVISION` and are never overwritten. Instance IDs, revisions, ROM bindings, Root/Google settings and file bytes are retained; ownership preparation occurs on first mapped boot.

**Source rollback, engine rollback and data rollback are separate operations.** After mapped boot, Host ownership has changed. An old APK or Git checkout cannot safely reuse those trees as if migration never happened. Stop and verify the instance, retain any new data, and restore the matching verified tree/config snapshot. Never recursively chown Host `/data` or shared image lower directories. Arbitrary interruption between metadata operations is not guaranteed to resume losslessly; preserve evidence and recover from the verified copy when uncertain.

## Validation and limits

`tests/userns_regression.py` verifies actual mappings, namespace ownership and stale-tick rejection. Disposable dummy files test that Guest root can read its own 0600 file and is denied Host-UID0 and foreign-range files. Real Host secrets are never used. Results and retained failures are recorded in the [validation report](validation.md).

This removes the shared-root-identity defect. The kernel and GPU driver remain shared, the tested Host remains Permissive, and this is not certification of a hostile-code security sandbox. Time namespaces, Android guest multi-user, arbitrary ROMs/modules and Enforcing are outside the validated scope. Upstream references: [User namespaces](https://man7.org/linux/man-pages/man7/user_namespaces.7.html), [BinderFS](https://docs.kernel.org/admin-guide/binderfs.html).
