# Validation and remaining work

[中文 records](../07_登录后回归与修复.md) · [Release-specific record](../08_国际化与发布.md)

The retained test setup has an ordinary instance 1 (768 MiB, Root/Google off) and user-created instance 2 (1536 MiB, Magisk on, Google signed in). Testing preserves their data revisions and compares account identity through a private keyed digest; account names, credentials and raw screens are not published.

The previous 0.1.1/e9 acceptance covered real host reboot/recovery, retained Google login, ordinary-UID HTTPS/DNS/GPU checks, denied network for an App without INTERNET, guest Root approval, file isolation, H.264 encoding and playback, playback PCM and audio-helper recovery, preview stress, and unchanged old-project files. See the dated Chinese historical records for details.

The 0.1.2 release adds EN/ZH resources and locale selection, the IrisBox / 虹膜空间 name and a verified project-private host BusyBox. Host/guest tool paths are explicitly separated across pivot_root, resolution, fullscreen, preview and file exchange. Release-specific measured results are recorded alongside the downloadable artifact hashes after regression.

The clean host image is **statically validated only**: original factory static partitions match, userdata is newly formatted and contains only filesystem initialization entries, filesystem checks pass and the composed partition/XZ hashes match. The active Pi disk was not flashed or exported. First boot on a spare card, expansion, first host Magisk installation and dispatcher creation on a host with no previous project remain unverified.

Other hardware/kernel/ROM profiles, Enforcing SELinux, cameras/microphones, protected video/DRM, Play Integrity, arbitrary modules, account synchronization under multiple workloads, physical speaker listening and long-duration stress are not claimed. Tests on this permissive shared-kernel setup do not establish hostile-workload isolation. Maintenance rollback was exercised on normal failures; abrupt power loss was not injected.
