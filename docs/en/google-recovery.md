# Recovering Google Play Access & Device Certification Guide

**English** · [简体中文](../10_Google商店恢复与认证说明.md) · [Validation Report](validation.md) · [Back to README](../../README.md)

---

## 1. Play Store Downloads vs. Device Certification

Google's **custom-ROM registration** provides a supported path for uncertified builds to sign in and use Google apps, whereas **Play Protect device certification** reflects manufacturer compatibility certification recognized by Google. Consequently, a guest can download and install apps from Google Play while `Settings → About` still displays **`Device is not certified`**.

| Verification Item | October 24, 2026 Result | Scope & Notes |
| :--- | :---: | :--- |
| **Guest GSF ID Registration** | **Matched** | Privately verified against the owner's registration list (host or legacy guest registrations do not apply to a new guest). |
| **Account Retention & Play Downloads** | **Passed** | Restored the Play catalog and installed **Open Sudoku**; after rebuilding GMS service data and restarting, installed and opened **Google Calculator** (`installer=com.android.vending`) without re-login. |
| **`Settings → About → Play Protect certification`** | **Not Passed** | Still displays `Device is not certified`. Successful downloads do not establish device certification or Play Integrity. |

*Official References: [Play Protect Certification Guidance](https://support.google.com/googleplay/answer/7165974?hl=en) · [Custom-ROM Registration Page](https://www.google.com/android/uncertified/warningauto) · [Play Services Troubleshooting](https://support.google.com/googleplay/answer/9037938?hl=en).*

---

## 2. User Recovery Steps (Inside the Guest)

Perform these steps inside the target **Guest's Android Settings** (tested on retained instance 2, internal ID `1010`):

1. **Start the Target Guest with Sufficient Memory**:
   After a host reboot, open IrisBox, grant normal host root, enter the manager, start the guest (configured at `2048 MiB` or `1536 MiB`), and wait for **Desktop ready**. If memory admission rejects startup, stop unused instances first—never reset userdata to resolve low memory.
2. **Verify Network & Date/Time**:
   Check Guest network connectivity and automatic date/time, then open Google Play. No cleanup is needed if the catalog and downloads already work.
3. **Clear Cache Only (Recommended First Step)**:
   If Play fails to load or download:
   - Open Guest **Settings → Apps → See all apps**.
   - Force-stop **Google Play Store** and **Google Play services**, then open **Storage & cache** for each and tap **Clear cache**.
   - **Important**: Clearing temporary cache is distinct from clearing storage/data. Do not clear GSF or persistent account data during routine recovery.
4. **Verify GSF ID Registration**:
   If prompted as uncertified/unregistered, check this specific Guest's GSF ID on Google's [custom-ROM registration page](https://www.google.com/android/uncertified/) (keep the ID private and do not repeatedly clear GSF to generate new IDs).
5. **Verify Downloads & Inspect Certification**:
   Install and open a free app from Play, then separately inspect `Avatar → Settings → About → Play Protect certification` (scroll the account dialog in landscape mode if `Settings` is below the fold).

> [!NOTE]
> **Proven Recovery Sequence**: The first successful catalog and download recovery on October 24, 2026 used **`GMS & Store cache-only clear → standard Google check-in → reopen Play → actual app download`**.

---

## 3. Maintainer Cache-Only Refresh & Check-In Command

Run from the repository root with `IRISBOX_SERIAL` set to your ADB device. This script verifies `PID:start_tick` for instance 2 before clearing only temporary GMS/Store caches and broadcasting a normal check-in (retaining GSF, persistent GMS data, accounts, and game saves):

```powershell
$env:PATH = "$env:LOCALAPPDATA\Android\Sdk\platform-tools;" + $env:PATH
$env:IRISBOX_SERIAL = 'YOUR_DEVICE_SERIAL'
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
am broadcast -a android.provider.Telephony.SECRET_CODE \
  -d android_secret_code://2432546 \
  -n com.google.android.gms/.checkin.CheckinServiceSecretCodeReceiver
''', timeout=60))
'@ | python -
```

---

## 4. Additional Certification Diagnostics & Private Backups

To investigate whether `About` could show `Device is certified`, the following extra tests were executed on October 24, 2026 (none changed the uncertified status, and they are **not** required for routine store recovery):

- **Temporary Guest Root Disablement**: Stopped the guest, disabled Guest Root (`su`/`magiskd` confirmed absent), restarted, and ran Google's `Fix device issue`—it still failed. Original Magisk and app grants were restored afterward.
- **Store-Only Data Reinitialization**: Backed up and cleared only Play Store data while keeping GSF, accounts, and games; after accepting Store terms, certification remained unpassed.
- **Explicitly Authorized GMS Data Rebuild**: With the owner's separate authorization and full private backups (`/data/irisbox/maintenance/`, indexed under git-ignored `validation/private/google-orientation-20261024/`), uninstalled GMS updates to factory `22.46.19 (224619045)` to rebuild service state, then reinstalled all **11 backed-up original split APKs (`235,572,891 bytes`)** via `PackageInstaller` to restore `26.39.33 (263933029)` with fresh service state. After restoring Guest Magisk and rebooting (~59s), GSF ID, keyed account identity, and game `shared_prefs` SHA-256 digests matched, and Play installed **Google Calculator 9.3** without re-login—while `About` still reported `Device is not certified`.
