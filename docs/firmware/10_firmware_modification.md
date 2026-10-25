# Gear 360 (SM-C200 / SM-R210) – firmware image format and a minimal-change repack
> **Context:** interoperability and repair of the owner's own Samsung Gear 360 (2016/2017),
> a camera the manufacturer abandoned. All work is offline, on firmware the owner already has,
> for the owner's own device. No third-party systems, services, or accounts are involved.

Goal of this study: understand the stock update container and flashing tool well enough to
produce a **repacked stock image that boots `di-camera-app` in its normal mode with a small
helper library loaded**, so capture / live view / BT / the status screen all work through the
stock code paths (avoiding the mid-session relaunch wall in `07_factory_mode_handoff.md`).

Sources: `tools/slp_unpack.py`, the real stock images in `firmware/stock/` in the repo
(git-excluded): `C200GLU0AQK1_171121_1257_REV00_user.bin`, `…APE4…`, `R210GLU0ARB2…`, and the extracted
rootfs (`/usr/sbin/fw_upgrade`, `/usr/lib/libfirmware-upgrade.so`,
`/usr/bin/change-booting-mode.sh`, the systemd units). **Offline only — no camera involved.**

## (a) The SLP container

Verified on the real AQK1 image (`SLP\0`, little-endian):

```
0x00  "SLP\0"                     magic
0x04  version string              e.g. "0.85"
0x0C  model          (16 bytes)   "SMC200"      (R210 image: "SMR210")
0x1C  full fw name   (16 bytes)   "SMC200GLU0AQK1"
0x2C  u32 partition count         10
0x30  build string   (16 bytes)   "VER_Rev0.6"
0x40  partition table, 24 bytes per entry:
        +0x00 u32 size
        +0x04 u32 crc32      (custom table, see below — NOT zlib crc32)
        +0x08 u32 offset     (absolute, into the .bin)
        +0x0C u32 word       (a descending progress/weight word, e.g. ffffffff,7fffffff,
                              65ffffff,543fffff,… — a UI progress hint, not real flags)
        +0x10 8 bytes extra  (usually 0; part 1 carries an ASCII tag, e.g. "08E6824")
```

Data begins right after the table (0x130 for 10 parts) and the file ends exactly at the last
partition — **there is no trailer and no whole-image signature appended.**

AQK1 partition map (index → size → first-bytes magic → identity):

| # | size | magic | identity |
|---|---|---|---|
| 0 | 6.23 MB | `27051956` | uImage (kernel) |
| 1 | 0.05 MB | `…ea` ARM vectors | a bootloader stage (tag "08E6824") |
| 2 | 3.70 MB | `27051956` | uImage (2nd kernel — recovery) |
| 3 | 6.45 MB | `27051956` | uImage (3rd kernel) |
| 4 | 0.12 MB | binary | small blob (device/rom params) |
| 5 | 9.45 MB | `…ea` ARM vectors | a bootloader stage |
| 6 | 187.3 MB | `894c5a4f` ("LZO") | **rootfs**, lzop-compressed ext4 |
| 7 | 4.96 MB | `894c5a4f` | **opt**, lzop-compressed ext4 |
| 8 | 0.02 MB | zeros | params/env |
| 9 | 47.9 MB | `894c5a4f` | hibernation/swap (SWSUSP) image |

**Integrity = CRC32 only, with a custom table.** `fw_upgrade` logs `[IMG-CRC32] Set: 0x%x,
Fw: 0x%x` and uses `fw_crc32_table` / `fw_upgrade_crc32` (a non-zlib polynomial — zlib.crc32
does not match the stored field). There is a `bulk_read,no_chk_data_crc` mode that skips the
check. **No RSA/signature/secure-boot verification appears anywhere** in `fw_upgrade` or
`libfirmware-upgrade.so` (no `rsa`/`sign`/`verif` strings, no image trailer). This matches the
fact that the stock `.bin` can be reflashed from SD (KieronQuinn's Gear360_OSS) with no
re-signing. **So a repacked image needs only correct per-partition custom-CRC32 + a rebuilt
SLP table — no key material.**

## (b) The flashing tool and update paths

`/usr/sbin/fw_upgrade` (+ `libfirmware-upgrade.so`) is the flasher. It writes **eMMC**
(`/dev/mmcblk0pN`) and can also drive NAND/MTD (`nandwrite`, `flash_erase`) on other boards.
Mechanics seen in its strings:
- Per partition: `pv … --offset … --length … | lzop -d -c | dd of=/dev/mmcblk0pN …` (LZO
  parts decompressed on the fly) or a plain `dd` for raw parts.
- Bootloader: written to the eMMC **boot0 hardware partition** — `echo 1 >
  /sys/block/mmcblk0boot0/force_ro`, write `bootloader.bin` to `/dev/mmcblk0boot0`.
- Full re-partition path exists (`dd if=/dev/zero of=/dev/mmcblk0 bs=1M count=1`,
  `parted -s /dev/mmcblk0 mktable gpt`, `mkpart …`) — the destructive "flash everything".
- `/opt` is `/dev/mmcblk0p10`; rootfs is mounted at `/mnt/root` for mkfs+write. Preferences
  are preserved (`/opt/pref/default/pref.bin`, `/backup/pref/pref_default.bin`). Ends with
  `reboot -f`.
- A fixed SLP-index → device table lives inside `fw_upgrade` (`fw_get_part_info`); it maps each
  SLP partition to its `mmcblk0pN`.

Entry points:
1. **SD card:** `di-camera-app` looks for `/sdcard/smc200.bin` (and `/sdcard/smc200_eng.bin`
   for engineering) and, on user confirm, reboots into the upgrade path where `fw_upgrade`
   flashes it. This is the owner-facing reflash (Kieron's README) and our recovery net.
2. **FOTA / recovery:** `change-booting-mode.sh --fota` writes the delta path to
   `/opt/data/recovery/DELTA.PATH` and reboots into the **recovery kernel** (one of the uImage
   partitions) which runs the updater. `--fus` triggers FUS (Firmware Update Service, the
   USB/Odin-style download). `--update` sets a developer-update state. The camera's
   `firmware-upgrade-display.service/.path` shows the progress UI.
3. **CS firmware (`CS_FW`/`nx_cs.adj`):** the engineering/CS path (see `03_sd_card_hook.md`).

**Partial (rootfs-only) update: YES.** `fw_upgrade` flashes per-partition from the SLP table,
so a crafted SLP that contains only the rootfs partition (or a direct
`lzop -d | dd of=/dev/mmcblk0p<rootfs>` of a modified rootfs, with the matching custom CRC32)
updates just the rootfs and leaves kernels/bootloader untouched. This is the minimal, lowest-
risk change for our purpose.

## (c) Brick risk and recovery

- **The bootloader is in eMMC `boot0`, a separate hardware partition**, and the **recovery
  kernel is a separate SLP/eMMC partition** (parts 0/2/3 are three uImages: boot + recovery +
  one more). A bad **rootfs** therefore does **not** touch the bootloader or the recovery
  kernel — the safety net survives.
- **Recovery is reachable without a good main rootfs:** the FUS/FOTA/SD-update path runs from
  the recovery kernel + `/opt/data/recovery`, independent of the main rootfs. So the stock
  AQK1 `.bin` can be reflashed from SD even after a bad rootfs flash (exactly Kieron's
  documented recovery).
- **Not classic A/B banks**, but the separate recovery kernel is the equivalent safety net.
- A hardware **download/upload mode** (FUS) exists and is software-triggerable; a power-on
  button combo for it is likely but unconfirmed (to verify on the owner's unit before relying
  on it). The USB FUS path is the ultimate recovery if SD-update ever fails.
- **Rule for us:** only ever repack/flash the **rootfs** (and keep a verified copy of the
  stock `.bin` on an SD as the one-step restore). Never touch bootloader/boot0 or the
  partition table. With that rule the brick risk is ~nil: worst case, reflash stock from SD.

## (d) Minimal-change design (rootfs-only)

What we add to the rootfs partition, nothing else:
1. **Our helper `libg360r.so`** at `/usr/lib/libg360r.so` (armel softfp, glibc-2.13 ABI — same
   as the stock binaries; see `06_firmware_variants.md`).
2. **Load it into `di-camera-app` at boot** via `LD_PRELOAD`. How env reaches the app:
   `di-camera-app.service` has `EnvironmentFile=/run/tizen-mobile-env` and
   `ExecStart=/usr/apps/com.samsung.di-camera-app/bin/di-camera-app`; the app is also forked by
   the AUL `launchpad_preloading_preinitializing_daemon`, whose `launchpad-preload.service`
   reads the **same** `/run/tizen-mobile-env`. So set `LD_PRELOAD` in a place both honour:
   - cleanest: a systemd drop-in `/etc/systemd/system/di-camera-app.service.d/10-g360r.conf`
     with `Environment=LD_PRELOAD=/usr/lib/libg360r.so`, **and** the same on
     `launchpad-preload.service` (so the AUL-forked instance inherits it);
   - or append `LD_PRELOAD=…` to the generator that writes `/run/tizen-mobile-env`.
   SMACK is inactive (no smackfs), so `LD_PRELOAD` is not blocked (confirmed, doc 07).
   Because this applies at **boot**, the app comes up through its full normal lifecycle with
   our code loaded — it sidesteps the mid-session `systemctl restart` / `launch_app` wedge
   (doc 07 §5-6).
3. **Optionally a tiny extra service** for our own controller (networking/HTTP/capture glue):
   `/usr/lib/systemd/system/g360r.service` + a `default.target.wants` symlink, ordered
   `After=di-camera-app.service`. Lighter alternative to LD_PRELOAD if we only need a sidecar
   and not in-process hooks.
4. Leave `/opt` (p10) and prefs untouched so calibration/user settings persist.

Repack procedure (rootfs-only SLP):
1. `lzop -d` the stock rootfs partition, mount, add the four files above, unmount.
2. Re-create the ext4 image at the stock size, `lzop` it.
3. Recompute the partition's CRC32 with the **custom `fw_crc32_table`** (extract the table from
   `fw_upgrade`; reimplement in the packer).
4. Rebuild the SLP: either a single-partition SLP (rootfs only) or the full table with only the
   rootfs blob replaced and its size/crc/offset fixed and following offsets shifted.
5. Name it `smc200.bin`, put on SD, flash through the stock SD-update path.
Keep the untouched stock `.bin` on a second SD as the one-step restore.

## (e) R210 / APE4 implications

- Same SLP container format across all three (`SMC200`/`SMR210` model string, same header and
  24-byte table). The packer is model-agnostic; only the per-model rootfs/kernels differ.
- The control stack (`di-camera-app`, `dfmsd`, `libfactory-mode.so`) was byte-identical
  APE4↔AQK1 for the pieces we rely on, so a C200 helper built once should load on both C200
  firmwares. **R210 is a different model/board** (different sensor/mechanics) — treat it as a
  separate target: rebuild from the R210 rootfs and re-verify the helper's symbol offsets/ABI
  against R210 binaries before flashing.
- Build the helper against the matching sysroot per model; recompute CRC32 per repacked
  partition; never cross-flash a C200 image to an R210 or vice-versa.

## Bottom line

A **rootfs-only repack** is low-risk and sufficient: no signing to defeat (CRC32 only, no RSA),
the bootloader + recovery kernel are on separate partitions that we never touch, and the stock
`.bin` on SD is a guaranteed one-step restore. The one real build task is reimplementing the
custom `fw_crc32_table`. The payload is a single `LD_PRELOAD` helper (+ optional sidecar
service) placed in the rootfs, loaded at boot through the stock AUL/systemd lifecycle — which
is also what unblocks capture/live view/BT that the runtime factory-mode route could not reach.

## Two flashing risks, closed (and the CRC + partition map)

### CRC32 — exact formula (verified on the real AQK1 image)
The per-partition checksum is **standard CRC-32 (reflected, poly 0xEDB88320, init
0xFFFFFFFF) with NO final one's-complement**. All 10 AQK1 partitions match
`stored = zlib.crc32(blob) ^ 0xFFFFFFFF` exactly. So the repack tool needs no custom table:
```python
stored_crc = zlib.crc32(partition_bytes) ^ 0xFFFFFFFF
```
(`fw_upgrade` accumulates init 0xFFFFFFFF over the payload and stores the raw accumulator;
that equals zlib's result XOR 0xFFFFFFFF. The `bulk_read,no_chk_data_crc` mode skips it.)
The container magic may be `"SLP\0"` or `"DRM5"`.

### Partition map — from /etc/parttab (authoritative; fw_get_part_info parses it)
```
bootloader    /dev/mmcblk0boot0   bootloader.bin   raw   (eMMC boot0 hw partition)
devicem4      /dev/mmcblk0boot1   devicem4.bin     raw   (eMMC boot1)
adj           /dev/mmcblk0p1      none             raw   (calibration/adj)
pref          /dev/mmcblk0p2      none             raw
pref_default  /dev/mmcblk0p3      pref_default.bin raw
pref_recovery /dev/mmcblk0p4      none             raw
uImage        /dev/mmcblk0p5      uImage           raw   (kernel)
rtos          /dev/mmcblk0p6      rom.bin          raw   (DRIMe5 A7 ISP RTOS)
rtos_data     /dev/mmcblk0p7      none             raw
snapshot      /dev/mmcblk0p8      snapshot.img     lzo   (hibernation image, 128M)
platform      /dev/mmcblk0p9      rootfs.img       lzo   ext4  → /         (ROOTFS)
opt           /dev/mmcblk0p10     opt.img          lzo   ext4  → /opt
pcache        /dev/mmcblk0p11     pcache.list      raw
rImage        /dev/mmcblk0p12     rImage           raw   (RECOVERY kernel)
opt-usr       /dev/mmcblk0p13     none             ext4  → /opt/usr
```
Kernel cmdline (from the SPL): `root=/dev/mmcblk0p9 rw rootfstype=ext4 rootwait
hibernate=nocompress`.

### Risk 1 — HIBERNATION (closed)
The camera boots in ~1.2 s by **resuming a prebuilt hibernation snapshot**. The snapshot is a
real firmware partition: `snapshot.img` → `/dev/mmcblk0p8` (lzo). di-camera-app (pid 252) is
*inside* that image. **Consequence: flashing a modified rootfs (p9) while restoring the stock
snapshot (p8) would resume the OLD di-camera-app from RAM and ignore the new rootfs / our
LD_PRELOAD.**
Mechanism that lets us force a rebuild:
- `fw_upgrade` already **skips the snapshot partition** in the SD/normal path (for the
  `"snapshot"` entry, when `ss_upgrade==0` it zeroes the part info so it is not flashed).
- `hibernation.sh` cold-boots when `/sys/power/bootmode==4`, when `/etc/snapshot` is absent,
  or when there is no valid resume image on p8; then userspace runs and a fresh snapshot is
  built from the current rootfs.
- di-camera-app also invalidates on upgrade: DB `0xaac0` + `UI_Hib_Check_Fw_Upgrade()` →
  `fw_clear_snapshot_request()`.
**Rule for our repack: never restore the stock snapshot — omit the snapshot partition (or zero
p8).** The first boot is then a normal cold boot (~27 s) that loads our payload and rebuilds
the snapshot; later boots are fast *and* contain our code.

### Risk 2 — recovery with a broken rootfs (partially closed; one hardware check needed)
What survives a bad **rootfs** (p9): the SPL + kernel (p5) + the separate **recovery kernel
`rImage` (p12)** + the bootloader (boot0) are untouched if we only reflash p9. But the
owner-facing reflash triggers live on the main rootfs: `di-camera-app` detects
`/sdcard/smc200.bin`, and `change-booting-mode.sh`/systemd `recovery-mode`/`fota-mode` run from
p9. The SPL cmdline hardcodes `root=/dev/mmcblk0p9` with **no visible fallback to rImage**, and
**no power-on button-combo download mode was found in the extracted SLP partitions** (it would
be in `bootloader.bin` on eMMC boot0, which is not a normal data partition in our extract).
So before relying on bootloader-level recovery, it must be confirmed on hardware.
**Design that removes the dependency on finding the combo:**
1. Prefer a **separate sidecar systemd service** over LD_PRELOAD, so `di-camera-app` stays 100%
   stock and always boots → the stock SD `smc200.bin` reflash always works.
2. If in-process hooks are needed, make LD_PRELOAD **self-disabling**: gate it on a marker
   (e.g. load `libg360r.so` only if `/mnt/mmc/g360r.on` exists), so pulling the SD boots the
   stock app clean and restores SD-reflash.
3. Only ever reflash **p9** (never bootloader/boot0, kernels, or the GPT). Keep the stock
   `.bin` on a second SD.
4. Belt-and-suspenders, to verify on hardware (read-only): does a power-on button combo (with
   USB attached) enter a download/recovery screen, and does the `rImage` recovery kernel look
   for a firmware on SD by itself? If yes, that is a rootfs-independent restore.
With (1)/(2)/(3), a bad flash is recoverable via the stock SD path even if our payload
misbehaves, so the residual brick risk is confined to corrupting p9's ext4 itself (avoided by
doing the repack correctly: mount, add files, fsck, re-image at the stock size).

## CORRECTION (hardware-grounded): the snapshot is BOOTLOADER-restored, not kernel-resumed

Fact-check from the AQK1 rootfs + bootloader: `/etc/snapshot` does NOT exist, the kernel
cmdline carries `noresume`, and `hib_env.sh` therefore never exports `HIB=a` and
`hibernation.sh` exits early. Yet every boot shows "Restarting tasks ... done" at ~1.23 s and
di-camera-app is always **pid 252** with a frozen ~27.5 s uptime. Reconciliation:

**The SPL/bootloader (partition `part01`) restores a factory-made snapshot image from p8 into
DDR and resumes it — independently of the kernel.** Bootloader strings:
`> snapshot boot`, `snapshot img_load start!/end!(%d.%dMB..)`, `snapshot resume start!`,
`Resume address = `, `Resume point broken!`, `Invalid snapshot image.`, `erase snapshot image.`,
`warmboot resume start!`. The kernel's `noresume` is deliberate: the bootloader already did
the restore, so the kernel must not double-resume. The frozen ~27.5 s uptime and deterministic
pid 252 are the factory capture state carried inside that image.

Consequences (these REVISE the earlier "omit the snapshot partition" advice):
- On a normal boot, **all userspace (systemd, di-camera-app, every service) runs from the
  factory snapshot on p8 — NOT from the rootfs on p9.** So any change we make to the rootfs
  (a sidecar unit, an LD_PRELOAD drop-in, modified binaries) is **invisible** until the device
  cold-boots from the rootfs instead of restoring p8.
- The runtime snapshot-*creation* path is DORMANT on AQK1 (`/etc/snapshot` absent, `HIB` never
  set), so nothing rebuilds the image — the factory snapshot is restored read-only, forever.
- **Merely omitting the snapshot partition from the repacked SLP is NOT enough** — that just
  skips *writing* p8, leaving the OLD factory image in place to be restored. The flash must
  **actively invalidate p8** (zero at least the swsusp header / first page; the bootloader then
  prints "Invalid snapshot image." and cold-boots). `fw_upgrade` has an "erase snapshot image"
  routine and `dd if=/dev/zero` paths for exactly this.

### Two mod strategies
1. **SIMPLE / SAFE (recommended for the first flash):** flash the modified rootfs to p9 and
   **erase p8**. With p8 invalid and `/etc/snapshot` absent, **every boot is a cold boot (~27 s)**
   from our rootfs. di-camera-app + our sidecar launch fresh every time; our changes always
   apply; nothing re-creates a stale snapshot. Cost: we lose the ~1.2 s instant-on. Deterministic
   and easy to reason about.
2. **OPTIMAL (later, needs a hardware check):** erase p8 AND add `/etc/snapshot` to the modified
   rootfs. First boot cold-boots (our code runs) and, with `HIB=a` now set, di-camera-app's
   `UI_Hib()->hib()` path re-creates a snapshot on p8 that contains our modified running system;
   subsequent boots are instant-on **with** our code baked in. Requires validating that the
   runtime snapshot-creation path still works on AQK1 (it is all present, just gated off).

### Canary caveat (for the first flash)
A sidecar with `Before=di-camera-app.service` will NOT run if the device restores the factory
snapshot — it was never in that factory image, and a bootloader restore does not re-run systemd.
So the canary is only a valid test **if the same flash erases p8**. Otherwise its silence is a
false negative (snapshot restored), not proof the service failed.

## SD flashing trigger (AQK1), confirmed
- di-camera-app (`__ui_check_body_fw_available`, FUN_0011c9f4) checks **`access("/mnt/mmc/smc200.bin", F_OK)`**
  — existence only — with `/opt/storage/sdcard/smc200.bin` when the alt storage flag (UI 0x83e)
  is set, and **`smc200_eng.bin`** as the engineering fallback. **No companion file is required.**
- Precondition: **battery level must be > 2** (`__ui_check_fw_upgrade_battery_condition`:
  `return 2 < UI_Get_Value(0x7d4)`), i.e. not low — charge before flashing.
- On detection it shows a confirm/upgrade UI (`UI_Display_Manage(0x19e,...)`), sets the upgrade
  state (UI 0x27b=0xe), drives a status-LED pattern (`UI_Manage_Status_Led(3,1,...)`), and
  reboots into the upgrade path where `/usr/sbin/fw_upgrade` validates the SLP ("SLP\0"/"DRM5"
  magic + per-partition CRC32) and flashes per `/etc/parttab`; `fw_upgrade_display.sh` draws the
  progress GUI (triggered by `/opt/nx/fw/snapshot`). Model/version/CRC validation is inside
  `fw_upgrade`, not in the di-camera-app pre-check.

## CORRECTION: the SD-upgrade needs a companion file + a power-on key combo (no version check)

First real attempt (smc200.bin alone, header unchanged, normal power-on) gave NO prompt. Cause
found: it is NOT a version comparison (di-camera-app does no equal/older-version skip anywhere
in the trigger or `UI_Start_Body_Fw_Upgrade`; the model/CRC check is inside `fw_upgrade`, after
the reboot). The auto-trigger has extra gates.

`UI_Done_File_List_Init` → `FUN_0013087c` (`__ui_done_file_list_init_normal_Power_on`) runs the
auto-upgrade only when BOTH hold:
```
if (UI_Get_Value(0x83c) == 1 && access("/mnt/mmc/fwupgrade.txt", F_OK) == 0)
    UI_Start_Body_Fw_Upgrade(false);
```
- **`/mnt/mmc/fwupgrade.txt`** must exist — the "CS fw enable file" (log string: `cs fw enable
  file : not fwupgrade.txt`). smc200.bin alone is not enough.
- **UI 0x83c==1** is set at boot only when `UI_Devman_Get_Boot_FW_Update_Key_State()` returns 7,
  i.e. a power-on **button combo** — it reads `device_get_property(0xd,1)=KEY_STATE_SHUTTER` and
  `device_get_property(0xd,0xd)=KEY_STATE_WIFI`, so **hold Shutter + Wi-Fi while powering on**.
Battery gate (`2 < level`, level ≥ 3 / MIDBAR2 on the 0-5 scale) still applies on top.

So the two ways to start the SD flash on a stock camera:
1. **Menu:** the firmware-update menu entry (menu id 0x1b in `UI_Manage_Common_Menu_Info_By_GUI`)
   → `UI_Start_Body_Fw_Upgrade` → checks smc200.bin + battery + CRC → reboots to flash. Works
   live in the running app (resume-safe, no combo, no fwupgrade.txt).
2. **Unattended / CS path:** put **smc200.bin + fwupgrade.txt** on the SD root and power on
   **holding Shutter + Wi-Fi** (→ boot key state 7 → 0x83c=1); file-list-init then auto-starts.
No header/version change is needed either way.

(Note for our modified-image flow: on a snapshot-resume boot the file-list-init auto-check may
not re-run from the factory image, so the combo/menu — which also sets boot key state at the
actual power-on — is the reliable trigger. The user triggers the first flash manually by one of
the two methods above.)
