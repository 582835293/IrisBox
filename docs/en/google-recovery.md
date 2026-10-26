# Recovering Google Play access and checking certification

[中文](../10_Google商店恢复与认证说明.md) · [Validation](validation.md)

## Downloads and certification are different results

Google's custom-ROM registration provides a path to use Google apps. Device certification also concerns compatibility results recognized by Google. A guest can therefore download from Play while About still says **Device is not certified**. See Google's [certification guidance](https://support.google.com/googleplay/answer/7165974?hl=en) and [custom-ROM registration guidance](https://www.google.com/android/uncertified/warningauto).

On October 24, 2026, the owner's registration list matched the retained guest GSF ID. Play actually installed Open Sudoku. After Play-services data was rebuilt, the current version restored and the Guest restarted, Play also installed and opened Google Calculator. **The certification requirement has not passed.** Neither check-in success, malware scanning nor a successful download establishes certification or Play Integrity. There is no evidence that this guest merely needs an unspecified manual review.

## User recovery steps

Perform these steps in the selected **Guest's Android settings**. The tested retained instance is number 2, internal ID 1010; other installations can have different IDs.

1. After a Host reboot, open IrisBox, grant normal Host root authorization, enter the manager, start the guest and wait for Desktop ready. This guest uses 2048 MiB. If admission rejects startup, normally stop unused instances while retaining memory protection. Resetting guest data is not a memory fix.
2. Check Guest connectivity and automatic date/time. Open Play. No cleanup is needed if downloads already work.
3. If Play cannot open/download, normally force stop the Guest Store under Settings → Apps → See all apps. Under Storage & cache, choose **Clear cache**. Clear only Play-services cache as well, then reopen Play. Cache and persistent storage/data are different operations.
4. If registration is missing, privately check this Guest's own GSF ID on Google's registration page. The owner handles login/CAPTCHA. Host/legacy guest registration does not register this guest; do not repeatedly clear GSF to generate new IDs.
5. If still blocked, ask the maintainer for a normal check-in and diagnostics as below. A normal Guest stop/start may be tested. Record actual results; no fixed waiting period guarantees certification.
6. In Play, install and open a free app. Separately inspect avatar → Settings → About → Play Protect certification. Landscape account menus may need scrolling to reveal Settings.

The first successful recovery used **GMS/Store cache-only clearing → Google's normal check-in → reopen Play → actual download**. This combination worked in the recorded run; it does not identify one action as the unique cause or guarantee future recovery.

## Maintainer cache-only refresh

Use the matched development tools from the repository root. Public-source tools accept `IRISBOX_SERIAL` for your ADB device. Run `python tools/device.py state` first and check the intended instance. This PowerShell example selects instance 2 through helpers that validate PID:start_tick and enter the selected Guest:

```powershell
$env:PATH="$env:LOCALAPPDATA\Android\Sdk\platform-tools;"+$env:PATH
$env:IRISBOX_SERIAL='YOUR_DEVICE_SERIAL'
@'
import sys
sys.path.insert(0, 'tools')
from regression import target, guest
r = target(2)
assert r['ready'] and not r['busy']
print('Target instance:', r['number'], 'internal ID:', r['id'])
print(guest(r, '''
am force-stop com.android.vending
am force-stop com.google.android.gms
pm clear --user 0 --cache-only com.google.android.gms
pm clear --user 0 --cache-only com.android.vending
am broadcast -a android.provider.Telephony.SECRET_CODE -d android_secret_code://2432546 -n com.google.android.gms/.checkin.CheckinServiceSecretCodeReceiver
''', timeout=60))
'@ | python -
```

Run those operations through the verified **Guest** helper. Broadcast result=0 means reception, not completed check-in or certification. Keep raw logs under `validation/private`, inspect the real completion/result and verify a Play download. Logs can contain private identities. This sequence retains GSF, persistent GMS data, system accounts and game data.

## Additional tests performed in this run

These tests did not establish certification and are not required for routine recovery:

- Temporarily disable Guest Root and restart; verify su/magiskd absent. Google's repair still failed. Original Magisk and ordinary-app authorization were subsequently restored.
- After a separate backup, reinitialize only Store data. GSF/account/game data remained. Fresh Store setup included its existing service terms; Certification status unavailable was not counted as a pass.
- With the owner's **separate explicit authorization**, uninstall GMS updates to factory 22.46.19/224619045, rebuilding service data. Restore all 11 original backed-up APKs through Android's PackageInstaller, returning to 26.39.33/263933029 while retaining fresh state rather than restoring old CE/DE.

After original Root was restored, the Guest booted in about 59 seconds. GSF ID, keyed account comparison, revision and game preference-file SHA-256 values stayed unchanged. No re-login was needed to install/open Google Calculator 9.3 from Play, with installer com.android.vending. About had still shown Device is not certified; no certification success is claimed.

Google's [Play-services troubleshooting guidance](https://support.google.com/googleplay/answer/9037938?hl=en) explains that clearing service data can remove saved information and require reauthentication. Account records alone do not prove valid authorization. Data rebuilding therefore requires backups, authorization and real download validation; it is not an automatic startup repair.

## Backups and current state

The developer's private `validation/private/google-orientation-20261024/` indexes include `google-backup.json`, `store-data-refresh-backup.json` and `gms-current-version-restored.json`. Verified backups are in separate Host-private directories under `/data/irisbox/maintenance/`, with 0700 directories/0600 files and SHA-256 checks. They are absent from public source.

They include GMS/Store CE/DE state, original APKs and a consistent GSF SQLite reference. They are not a complete AccountManager/Keystore/Guest snapshot. Clearing data can invalidate keys; extracting old tar files cannot guarantee authorization recovery. Restoration needs version/consistency analysis and may require owner re-login. Keep the currently validated fresh state rather than automatically overwriting it with old archives.

Instance 2 is at 2048 MiB, original Magisk restored, orientation Follow app, Google account and game data retained. Instance 1 is stopped. Legacy source/config/data remain unchanged and the legacy project is not restarted. Version 0.1.4/e21 passed English/Chinese orientation and restart-persistence tests; see Downloads for release status and resources. Device certification remains an unmet acceptance requirement.
