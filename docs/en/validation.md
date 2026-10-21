# Validation and remaining work

[中文 records](../07_登录后回归与修复.md) · [Release-specific record](../08_国际化与发布.md)

The retained test setup has an ordinary instance 1 (768 MiB, Root/Google off) and user-created instance 2 (1536 MiB, Magisk on, Google signed in). Testing preserves their data revisions and compares account identity through a private keyed digest; account names, credentials and raw screens are not published.

The previous 0.1.1/e9 acceptance covered real host reboot/recovery, retained Google login, ordinary-UID HTTPS/DNS/GPU checks, denied network for an App without INTERNET, guest Root approval, file isolation, H.264 encoding and playback, playback PCM and audio-helper recovery, preview stress, and unchanged old-project files. See the dated Chinese historical records for details.

The 0.1.2 release adds EN/ZH resources and locale selection, the IrisBox / 虹膜空间 name and a verified project-private host BusyBox. Host/guest tool paths are explicitly separated across pivot_root, resolution, fullscreen, preview and file exchange. Release-specific measured results are recorded alongside the downloadable artifact hashes after regression.

The clean host image is **statically validated only**: original factory static partitions match, userdata is newly formatted and contains only filesystem initialization entries, filesystem checks pass and the composed partition/XZ hashes match. The active Pi disk was not flashed or exported. First boot on a spare USB disk, expansion, first host Magisk installation and dispatcher creation on a host with no previous project remain unverified.

Other hardware/kernel/ROM profiles, Enforcing SELinux, cameras/microphones, protected video/DRM, Play Integrity, arbitrary modules, account synchronization under multiple workloads, physical speaker listening and long-duration stress are not claimed. Tests on this permissive shared-kernel setup do not establish hostile-workload isolation. Maintenance rollback was exercised on normal failures; abrupt power loss was not injected.

## Final 0.1.2 / e13 results — 2026-10-15

The public AOSP ROM was imported through the App's normal file picker and verified. A new 1024 MiB AOSP+Root test instance reached its desktop in 64,128 ms without Google packages; ordinary-UID networking, GPU, audio and 640×360 H.264 encoding/playback passed. Only that owned temporary instance was deleted afterward.

Original instance 2 reached the desktop in 56,902 ms and instance 1 in 61,813 ms. Their original ROM bindings and data revisions remained unchanged. The Google account baseline matched; the ordinary test App still obtained uid 0 through its retained Magisk approval. Actual fullscreen visual inspection confirmed the signed-in Play Store catalog. An ordinary UID without INTERNET received SecurityException for HTTPS, the Google endpoint and DNS.

Final dual-instance media, separate 51-byte file roundtrips, ten JPEG captures per instance and bearer rejection checks passed. Audio recovered from an injected helper termination in 11.36 seconds with the main guest session unchanged. App reopening and manager restoration preserved sessions, data and active audio. Guest crash buffers were clear; both cgroup OOM-kill counts were zero.

All 334 locale resource keys/references and 11 archive-safety cases passed. Audits matched 11 Root files, 116 ROM compatibility files and 44 engine payloads; the private Google input audit matched 54 files. The sanitized public source fully rebuilt with identical engine file hashes. Fifty-two previous-project local source inputs and the old device configuration/scripts/registry remained unchanged; the old guests remain stopped. The global host BusyBox remained unchanged.

GitHub hosts the final APK and small guest Root/BusyBox source kit with metadata/checksums. Large ROM, clean host image and full upstream/kernel sources are prepared for owner-provided Drive mirrors; download pages explicitly mark missing links. No accounts, private keys or raw screens/logs are published. Clean host flashing remains untested.

## 0.1.3 / e17 User namespace results — 2026-10-21

Both retained instances now run with separate User namespaces: guest root 0 maps to Host 2000000000/2000100000, with 100000 UID/GID values per domain and no Host UID 0 mapping. Tests verified the owning User namespace of all six other namespaces, all four init UID/GID fields, stale-tick rejection, and denial of dummy Host-root/foreign-domain 0600 files. These checks do not establish a complete hostile-code sandbox on the shared kernel/GPU and Permissive host.

Before migration, independent stopped-state backups of each private tree/config passed per-file SHA-256 comparison. IDs, ROM bindings, revisions, counters 8/1 and the private keyed Google-account comparison remained unchanged. Original memory limits stayed 768/1536 MiB. First mapped boots took 174,836/78,097 ms; later starts took 100,613/67,905 ms. A new public AOSP+Root QA instance booted in 86,483 ms, passed media/network tests without Google, then alone was reset/deleted; its UID range remained reserved.

Both instances passed actual fullscreen FD handoff, mixed-case keyboard input, swipe, return/re-entry, ordinary-UID HTTPS/DNS/GPU/PCM, 640×360 H.264 encode/render/playback, retained Magisk approval, separate 51-byte file roundtrips and ten JPEG previews each. Apps without INTERNET were denied HTTPS, the Google endpoint and DNS. Missing/invalid bearer requests returned 403. Targeted audio-helper termination recovered in 21.44 seconds without restarting the main guest. App reopen and manager restoration preserved sessions and audio; checked crash buffers were clear and OOM-kill counts were zero.

EN/ZH pages and all 335 resources/references passed; guest locales/sessions stayed unchanged. The production Android archive tests passed 11/11 cases. Input audits matched 54 private Google, 11 Root, 116 ROM compatibility and 49 engine files. A separate full build of the sanitized public snapshot produced identical hashes for all 49 engine payloads. The old project's current-run script/config/registry/identity baseline, Host boot/socket policy and global BusyBox stayed unchanged; old guests were stopped with explicit user authorization.

Candidate failures are retained in the Chinese record: Root staging order (e14), private 0700 path traversal (e15), and BusyBox BRE differences (e16) were fixed in the tested e17 engine. Extra Guest UiAutomator instrumentation during live fullscreen exhausted the low-memory margin once and the existing guard normally stopped the Google guest; tests now read bounds before opening fullscreen. Original budgets/guards and the bounded input queue were retained. A video encoding attempt exceeded its 20-second test budget under startup load; the same configuration passed a retry and subsequent dual-instance regression.

**Google Store catalog/download validation is blocked by the visible Play Protect certification warning.** The GSF ID and byte-identical SSAID file match the pre-migration backup, which already contained an uncertified-status cache; the account comparison also matches. This does not prove the warning's cause. No Google data was cleared, certification result overridden or device fingerprint forged. The owner subsequently confirmed registration of the current instance and explicitly authorized a normal check-in. A check-in success marker was observed, but the actual Store still displayed the warning with no catalog. The owner deferred further investigation; no server-review cause or eventual recovery is claimed. Registration is not manufacturer certification or a Play Integrity guarantee.

No Host reboot or physical flash was performed in this run. Earlier reboot success does not prove mapped-version reboot recovery. Fresh-host dispatcher installation, Enforcing, other ROMs/kernels, Android multi-user, arbitrary modules, DRM and long-duration hostile workloads remain unverified. README usage images are illustrations, without raw device/account data. See the [migration guide](userns.md) before updating e13 data.
