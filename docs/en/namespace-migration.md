# 0.1.5: Iris namespace migration and recovery

[简体中文](../11_Iris命名迁移.md)

Version 0.1.5/e25 uses `com.irisbox.viewer` and `/data/irisbox`. Product-owned helpers, audio services, sockets and resource groups also use Iris names. Instance numbers, data revisions, ROM bindings and User namespace UID/GID ranges stay unchanged. The verified shared INET dispatcher remains shared; its existing path and other projects' tables are not moved or removed.

An existing 0.1.4/e21 installation needs a **stopped-state migration**, not just an APK update. The new package needs a normal host Magisk grant. Do not copy root grant databases, clear Google services or recreate instances. Fresh installations follow the normal installation guide.

## Maintainer procedure

Only the verified 0.1.4/e21 → 0.1.5/e25 transition is supported. Keep the exact installed old APK, private signing keys and sufficient independent backup space. The separate previous Project Iris is read-only reference material, not a migration source.

1. Normally stop every instance of this product and wait for display, network and lifecycle cleanup. Close the previous entry point.
2. Select your ADB device (`IRISBOX_SERIAL` in the public snapshot). The tool reads the previous package/root from the supplied APK and checks installed APK bytes, every engine hash, PID:start_tick, empty resource groups, network ownership and read-only lower mounts. It refuses mismatches.
3. Check, then apply:

   ```powershell
   python tools/migrate_namespace.py --from-apk out/IrisBox-0.1.4.apk --to-apk out/IrisBox-0.1.5.apk
   python tools/migrate_namespace.py --from-apk out/IrisBox-0.1.4.apk --to-apk out/IrisBox-0.1.5.apk --apply
   ```

4. The tool makes independent stopped copies of each instance's data, upper, vendor.upper and metadata, checks per-file SHA-256 and retains configuration/engine recovery materials. These are not hard links. It releases only verified owned lower mounts, disables the previous entry point, installs the trusted engine and moves the product root. Stopped display pointers and old audio files are retained privately for recovery instead of reusing stale absolute paths.
5. Install the new APK, run **Authorize and check**, allow its normal host Magisk request and start the retained instance. The tool does not uninstall the old APK. Retire the previous entry point only after backing it up and validating data, account identity, display, input, network and isolation.

The private receipt `out/maintenance/namespace-transition.json` records the actual stage and full snapshots. Backups move with the product root, under `/data/irisbox/maintenance/` or the recorded transaction directory. Do not publish receipts, account fingerprints, raw logs or device captures. An on-device snapshot does not replace an external backup.

The bundled download browser also changes package identity. Its old private cache/DownloadManager history does not automatically become the new UID's history; original downloaded files and old state remain in the full snapshot. Third-party Google/game package identities and their existing UID/data layouts stay unchanged.

## Recovery

Do not force startup while `engine-upgrade.pending` exists. Retain the receipt and failure output, confirm all guests/display tasks are stopped, then inspect the recorded `recover.sh`. Do not guess paths, delete the marker, recursively change ownership or rerun initial extraction scripts.

The transaction attempts rollback on apply failure. Device testing caught a sourced library overwriting a short transaction variable; dedicated variables and subshell checks fixed it. The failed attempt rolled back with data retained, and the subsequent migration passed verification.

The generated recovery script covers that **stopped directory/engine transaction**, not arbitrary later userdata changes. If the old entry point has since been uninstalled, reinstall the exact old APK before restoring its entry point and normal grant. Full snapshot restoration also requires matching revisions, UID mappings, mounts and configuration. Never overwrite later user changes blindly or combine an old engine with incompatible ownership. For Google usage, follow [Google recovery](google-recovery.md) before considering any data reset; preserve GSF identity.

Both existing instances were migrated with IDs, revisions and UID/GID ranges retained. Instance 2 kept its private Google account fingerprint and GSF ID and installed further Play apps after restart. Normal host root authorization, EN/ZH settings and real fullscreen game input passed. The separate previous project was only normally stopped as authorized; its data/configuration remain. See [validation](validation.md) for exact app and dual-instance results. Users still install host Magisk themselves. Google device certification has not passed.
