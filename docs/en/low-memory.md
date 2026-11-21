# Swap settings, low memory and game touch input

[简体中文](../12_低内存与临时交换空间.md)

Version 0.1.6 adds host swap settings. Host Android, Google services, graphics and all guests share the Pi's physical RAM. A 2048-MiB guest setting is a ceiling, not additional RAM. Swap writes temporarily inactive memory pages to disk, relieving pressure at the cost of latency and storage writes. It is not physical RAM or a guarantee that all games or two heavy games will work.

## Set it in the App

1. Normally stop all IrisBox guests. Do not reset guests, reimport their ROM or clear Google/game data.
2. Choose **Swap space** on the home or instance-list page. Select **Off**, **1 GiB**, **2 GiB** or **4 GiB**. This is shared host swap, separate from each guest's RAM ceiling.
3. Select **Restore after reboot (host Magisk)** if wanted, then **Apply swap settings**. Wait and check Configured/Active capacity. Status also shows used swap, available host RAM, free disk and allocated files. Use **Refresh status** to reread actual values.
4. Start a guest and wait for Desktop ready. Use **Input: Touch** in the fullscreen toolbar for Unity controls and the slingshot. Mouse mode may not activate those controls. A short drag was tested.

**Off** safely disables this project's swap and removes its own boot hook without deleting guest data. Running guests, unsafe file ownership, insufficient disk or unsafe memory reclaim cause rejection with a reason. The operation does not stop guests, suspend host Google or lower memory guards.

## Reboot, recovery and uninstall

Automatic restore requires the user's normally installed, working host Magisk. Enabling it installs the project's own `/data/adb/service.d/irisbox-swap.sh`, which only enables the saved, verified swap file; it does not start guests. No fstab or system partition is changed. An unrelated same-name boot script is rejected rather than overwritten. With automatic restore unchecked, reboot deactivates swap; apply the setting again in the App.

After host reboot, check Active capacity before starting guests. Memory pressure does not establish damaged game data. If restore fails, retain the error and check host root, kernel and storage support. Private receipts live under `/data/irisbox/host/swap/`; boot results are in its `boot.log`. Keep original records private. Pending engine maintenance prevents boot restore; recover the transaction using the [maintenance guide](building.md).

Disabled files are retained and reused when selecting the same size, avoiding repeated allocation. Different sizes can leave multiple files; Allocated files reports their total. The three sizes total at most 7 GiB, excluding interrupted temporary allocations. Creation also requires 2 GiB disk headroom. Never delete active swap. Cleanup requires prior deactivation and ownership checks.

Uninstalling only the APK leaves retained data and boot settings. Before complete removal, normally disable swap and automatic restore in the App, then follow the project's maintenance procedure. Do not delete other projects or disable their swap.

## Ownership and guards

Files have fixed paths under `/data/irisbox/host/swap/`, root ownership, mode0600 and a mode0700 directory. Size, device/inode identity, link count and receipts are checked. No file path comes from ROM or UI input. Only registered files are controlled; other swap is untouched. Shared/exclusive locking serializes guest startup against swap reconfiguration. Disabling swap preserves 512 MiB of physical headroom.

Host/guest swappiness, guest ceilings and low-memory guards are unchanged. Admission still checks physical MemAvailable rather than treating swap capacity as RAM. Increasing swap does not permit arbitrary guest memory budgets.

## Actual game conditions

On October 27, 2026, an explicitly authorized 2-GiB temporary swap file let a 2048-MiB guest run Angry Birds 2 26.7.1's tutorial: a bird launched, targets broke, score reached 93,382 and the next room appeared with guest Root off. After restoring original Magisk, another launch scored 102,356 and Red's ability interaction worked. The earlier Root-off run without swap was stopped by the host guard; these runs do not isolate Root as the cause.

Successful play had about 1.2–1.3 GiB host MemAvailable, about 1.1 GiB swap used and zero guest OOM-kills. Startup and some actions still required waiting. That manual setup was then turned into the App feature above. See [validation](validation.md) for current capacity, boot-restore and dual-game results. Screenshots establish the observed interaction, not long-term progress, payments, all levels or stable frame rates.

Actual Play downloads and device certification remain separate results; see [Google recovery](google-recovery.md).


To reproduce the dual test, start 2048-MiB guest 2 first and wait for ready, then 1024-MiB guest 1; launch games only after both desktops are ready. A heavy game opened first can make admission reject the second guest. Keep the physical-memory guard. In the Angry Birds 2 tutorial, adjust a red-cross aim to the green guide before release; an off-guide rejection does not establish broken touch input.
