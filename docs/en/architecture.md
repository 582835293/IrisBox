# Architecture and isolation

[中文](../02_技术架构.md)

```mermaid
flowchart TD
  A[Host Android App] --> B[MagiskSU mount-master]
  A --> C[Foreground installer and SAF import]
  C --> D[Manifest and file SHA-256 validation]
  D --> E[Root-owned ROM library]
  B --> F[Authenticated localhost Broker]
  F --> G[Lifecycle scripts and namespace helper]
  G --> H[Read-only ROM plus private overlays and data]
  H --> I[Adapted stock init second stage]
  I --> J[Android framework and Launcher]
  J --> K[Producer and shared-buffer display]
```

The regular host APK requests root only through Magisk. A root `app_process` Broker listens on localhost port 19762 with a random bearer token. HTTP 202 means accepted, not completed; state polling verifies the actual guest session and desktop.

Instances share a Linux kernel. Each has User/mount/PID/network/IPC/UTS/cgroup namespaces, private BinderFS, system/vendor overlay upper/work directories, userdata and metadata. Since 0.1.3, Guest UID0 maps to a dedicated unprivileged Host range. There is no Host-UID0 fallback, CPU emulation or separate guest kernel. See [identity isolation and migration](userns.md). The root mount becomes private before pivoting; guest init manages services normally. Guest Root and Permissive do not constitute a security boundary against hostile workloads.

`revision` identifies a data generation; `session=PID:start_tick` identifies a running generation. Lifecycle locks, process identity checks, cgroup membership and mount ownership prevent operations on recycled IDs or stale sessions. Destructive requests also require the current revision and instance confirmation number.

## Separate resources

| Resource | Previous Iris | IrisBox |
|---|---|---|
| Host package | com.iris.viewer.buffer | com.irisbox.viewer |
| Runtime | /data/guest/iris_runtime_v1 | /data/irisbox/runtime |
| Manager port | 18762 | 19762 |
| Internal IDs | 7, 8, 23, etc. | 1007–1134 |
| veth | iris* | rv* |
| cgroup prefix | iris_runtime_ | irisbox_ |

The explicit shared exception is `/sys/fs/bpf/iris_inet_v1`, the host-global socket permission dispatcher. IrisBox verifies its identity and the host maps, then registers only its own netns-cookie/private-UID-map pairs. It never restores an old policy, removes another guest map or flushes host firewall rules. Fresh-host dispatcher creation is a separate unverified installation path.

## Display and media

List previews use bounded JPEG snapshots. Fullscreen uses the Producer/Gateway/JNI shared-buffer pipeline, fences and explicit release/acknowledgment; fullscreen frames do not travel through management HTTP. Input is bound to the same session with geometry checks and heartbeat cleanup.

AudioPolicy loopback captures guest playback as 48 kHz stereo PCM. Root-only token-protected sockets connect per-guest capture to a host AudioTrack; the host mixes outputs. Helpers are supervised and session-bound. Physical ALSA devices are not exposed, and microphone capture is not implemented. `audio.ready` proves active capture/playback progress, not an acoustic listening test.

The adapter restores mediaextractor and excludes unavailable V4L2 codecs from each private vendor view. Tested video uses software codecs. A private pre-init Traceur mask avoids unsupported kernel tracing; the original host APK and read-only ROM remain intact. See [SELinux](https://source.android.com/docs/security/features/selinux) and [BinderFS](https://docs.kernel.org/admin-guide/binderfs.html) for upstream behavior.

## Board profiles and kernel adaptation (0.2.0+)

Since `0.2.0`, hardware differences are declared in `engine/boards/<profile>/board.json` and synchronized into runtime scripts and constants via `tools/board_profiles.py sync`:

- **Two verified profiles**: `rpi4-a13-v1` (Raspberry Pi 4 / AOSP 13 / Mesa V3D / Android 13 BPF layout) and `opi5-a12-v1` (Orange Pi 5 RK3588S / Android 12 / Mali-G610 / Android 12 BPF layout), covering GPU device nodes, Vulkan stripping, GLES properties, memory limits, and media codec filters.
- **Enforced external SSD isolation**: When `requireExternalMount` is enabled (`opi5-a12-v1`), `storage_ssd.sh`, `HostStorage.java`, and `preflight.sh` verify block-device UUID and `.irisbox_ssd_marker` under `/data/irisbox` before any engine/ROM write, backed by a Magisk `service.d` boot-restore script.
- **Linux 5.10 / 5.15 differences**: `irisbox_userns` falls back gracefully when `mount_setattr` is absent on Linux 5.10; OverlayFS retains explicit runtime `metacopy=on`; `LinuxTelemetry` prioritizes hierarchical cgroup keys (`total_rss`, `total_cache`, `total_pgmajfault`).

## Uplink probing, egress policy & telemetry (0.2.1–0.3.0)

- **Dynamic uplink & DNS**: Discovers active `wlan0`/`eth0`/VPN `tun*` tables and applies per-instance direct or proxy egress plus hot-switched `auto` / `domestic` / `overseas` / custom DNS without altering the shared eBPF `INTERNET` permission dispatcher.
- **Fast fullscreen & live HUD**: Parallelized `display.sh` + `BufferGateway` + `DisplayActivity` polling cuts display handoff latency under 1s (`~0.65s–0.85s` on OPi5, `~0.93s` on RPi4) without startup log flicker. `LinuxTelemetry.java` + `buffer_viewer.cpp` `liveMetrics()` drive host/guest resource bars, a 1s auto-refreshing monitor modal, a persistent fullscreen FPS/Fence HUD, and a fullscreen Recents action.
- **Cross-instance app migration & ban-risk gate**: `HostAppImporter.java` supports streaming APK / `Android/data` / `/data/data` migration with UID/GID and SELinux context remapping. To mitigate anti-fraud account locks when moving state across isolated `SSAID`/MAC identities, `ListActivity.java` locks `/data/data` migration behind a typed confirmation phrase and blocks `/data/data` import for high-risk messaging packages (WeChat / QQ / TIM / WeWork).
