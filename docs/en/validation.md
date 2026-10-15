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
