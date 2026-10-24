# Route C: in-process camera helper (LD_PRELOAD)

Status: design and feasibility check against the C200GLU0APE4 firmware image. Nothing has been tested on hardware yet.

## Goal

Control the Gear 360 (SM-C200) from a phone without Samsung Accessory Service, SAP/WSM, or any Samsung code on the phone. A small helper library is loaded into the camera's own UI process (`di-camera-app`). It does two things:

1. Exposes a plain RFCOMM control channel with our own UUID, which the phone opens with a normal `BluetoothSocket`.
2. Calls the camera's own exported C++ methods to bring up the softAP plus the UPnP/RVF stack. These are the same calls the stock BT `cmd-req liveview/mobilelink` handler makes.

All media traffic then uses the stock camera services over Wi-Fi: UPnP SOAP on 7676, live view on 7679, files on 9001 (see app/02).

## Feasibility facts (APE4 image)

| Fact | Evidence |
|---|---|
| `di-camera-app` is ELF32 ARM EXEC, not PIE, base 0x8000, dynamically linked, `/lib/ld-linux.so.3` | `file`, `readelf -l` |
| ABI: ARMv7-A, Thumb-2, VFPv3, **softfp (armel)**; glibc **2.13** | `readelf -A`; `/lib/libc-2.13.so` |
| Launched by systemd unit `di-camera-app.service` (`ExecStart=` the binary, `EnvironmentFile=/run/tizen-mobile-env`, no `Restart=`) | `/usr/lib/systemd/system/di-camera-app.service` |
| The required methods are exported **by name** in the binary's dynamic symbol table, so `dlsym(RTLD_DEFAULT, …)` works with no hard-coded offsets | firmware session, see table below |
| EFL 1.7.99 provides `ecore_main_loop_thread_safe_call_async/sync`, for posting work onto the app's main loop | `nm -D libecore.so.1` |
| Tizen CAPI Bluetooth provides an RFCOMM server: `bt_socket_create_rfcomm`, `bt_socket_listen_and_accept_rfcomm`, `bt_socket_set_data_received_cb`, `bt_socket_send_data`, `bt_socket_set_connection_state_changed_cb` | `nm -D libcapi-network-bluetooth.so.0.1.81` |
| BT lib event path: `btSendEventToUI(ev,a,b,c)` → `gBTAppCallback(ev,a,b,c)` (set by `bt_set_callback`) | disassembly of libdi-network-bt-app.so.0.2.32 @0x6f14 / @0x6828 |
| SD card is vfat/exfat, `uid=0,gid=0,fmask=0022`, **no `noexec`** | deviced mount strings |
| SMACK: no access rules for the `com.samsung.di-camera-app` label (`/opt/etc/smack/accesses.d/com.samsung.di-camera-app` is empty) | firmware `/opt` |

Exported entry points (name → vaddr in APE4):

| Symbol | Demangled | vaddr |
|---|---|---|
| `_ZN14CUINETFuncDLNA4InstEv` | `CUINETFuncDLNA::Inst()` | 0x00264728 |
| `_ZN14CUINETFuncDLNA5StartE20E_UNF_DLNA_FUNC_TYPEb` | `CUINETFuncDLNA::Start(E_UNF_DLNA_FUNC_TYPE, bool)` | 0x002720a4 |
| `_ZN14CUINETFuncDLNA4StopEv` | `CUINETFuncDLNA::Stop()` | 0x0026479c |
| `_ZN14CUINETFuncDLNA13IsStartedFuncE20E_UNF_DLNA_FUNC_TYPEb` | `CUINETFuncDLNA::IsStartedFunc(…)` | 0x00264850 |
| `_ZN13CUINETDlnaMan9StartDlnaEbb` | `CUINETDlnaMan::StartDlna(bool,bool)` | 0x0025e444 |
| `_ZN19CUINETFuncBluetooth4InstEv` | `CUINETFuncBluetooth::Inst()` | 0x0024b2e0 |
| `_ZN19CUINETFuncBluetooth29handle_bt_app_receive_commandEiiPc` | `…::handle_bt_app_receive_command(int,int,char*)` | 0x0025191c |
| `_Z17UI_SoftAP_Managerii` | `UI_SoftAP_Manager(int,int)` | 0x0016910c |

DLNA function types, from the stock BT handler: 1 = RVF (live view, cmd 20), 2 = mobile link / file browse (cmd 7), 3 = FW download (cmd 23). The second argument of `Start` is `useWifiDirect`; pass `false` for softAP.

Calling convention: these are non-virtual member functions, so `this` goes in r0. From C, call `Start` as `int (*)(void *self, int func, int useWifiDirect)`.

## Bootstrap (non-persistent)

The `info.tg` hook runs our script as root inside factory script mode (the "self test" OSD). The bootstrap's job is to hand the camera back to **normal mode** with the helper loaded. Factory-mode gating, the per-boot latch and persistence analysis are in 07_factory_mode_handoff.md.

1. The hook script (child of `dfmsd`, which is a child of `di-camera-app`) first **leaves the di-camera-app.service cgroup**, because `systemctl restart` kills every process in that cgroup (systemd 208, default `KillMode=control-group`). Two ways:
   - preferred: re-exec through a transient unit, `systemd-run --unit=g360r /bin/sh <script> __detached`;
   - fallback: `echo $$ > /sys/fs/cgroup/systemd/tasks`.
   `setsid` alone does not help, and `nohup` does not exist on the device.
2. It copies `libg360r.so` from the SD card to `/tmp/g360r/`, so the library keeps working if the card is removed or remounted.
3. It renames `/sdcard/info.tg` → `info.tg.g360r`, so the restarted app does not enter factory mode again.
4. It writes a **runtime** systemd drop-in, `/run/systemd/system/di-camera-app.service.d/g360r.conf`, containing `[Service]` `Environment=LD_PRELOAD=/tmp/g360r/libg360r.so`. Then it runs `systemctl daemon-reload && systemctl restart di-camera-app`. Probe 2 verified on AQK1 that `systemctl` works from the hook context and that `/run/systemd/system` exists.
5. Once the new app process is up, it renames `info.tg` back. The factory check is latched per process at init, so this only arms the next power-on.
6. Everything lives on tmpfs (`/run`, `/tmp`). Nothing is written to rootfs (mounted read-only on AQK1), `/opt` or the swap/hibernation partition. Removing the SD card and rebooting returns the camera to stock.

## Helper library design (`libg360r.so`, C, no C++ runtime)

- **Constructor**: start a worker thread, so the app's startup is not blocked.
- **Init**:
  - wait until `CUINETFuncDLNA::Inst()` and the BT adapter are ready (poll with a timeout);
  - register an RFCOMM server with our own UUID through CAPI `bt_socket_create_rfcomm` and `bt_socket_listen_and_accept_rfcomm`.
- **Protocol**: newline-delimited JSON over RFCOMM, our own schema, versioned. For example:
  - `hello` returns model, fw, battery, storage;
  - `wifi.start {mode:"rvf"|"browse"}` calls `CUINETFuncDLNA::Start(inst, 1|2, false)` on the main loop through `ecore_main_loop_thread_safe_call_async`, then replies with `{ssid, passphrase, ip, descUrl}`;
  - `wifi.stop` calls `Stop()`;
  - `shot` and `record.start` / `record.stop` call `st app capture` / `st app mov …`, or the internal handler once it is reversed;
  - `set {key,value}` uses the `st app set …` mapping.
- **SoftAP credentials**: read them after activation from the camera settings (UI getters, or `wpa_cli`/tethering API). The firmware persists the password (setting 0xc4e), so it is stable across sessions.
- **Threading rule**: every call into `di-camera-app` C++ code runs on the EFL main loop. Only the RFCOMM I/O stays on the worker thread.

## Build

- Toolchain: `arm-linux-gnueabi-gcc`, flags `-march=armv7-a -mthumb -mfloat-abi=softfp -mfpu=vfpv3 -fPIC -shared`.
- Link against a sysroot taken from the firmware rootfs (glibc 2.13, libecore, libcapi-network-bluetooth, libdlog), so we never reference glibc symbol versions newer than 2.13.
- Resolve all camera-internal symbols with `dlsym` at runtime and link no camera C++ symbols, so the same binary works on APE4 and AQK1 as long as the names exist. **TODO:** verify the names in AQK1.

## Phone side (new app)

1. Pair over standard BT bonding.
2. Open `BluetoothDevice.createRfcommSocketToServiceRecord(OUR_UUID)` and exchange JSON.
3. Send `wifi.start`. Join with `WifiNetworkSpecifier(ssid, passphrase)` (a CompanionDeviceManager association skips the dialog after the first time) and bind sockets to that network.
4. Use UPnP/HTTP as in app/02.

## Risks and open questions

1. **Pairing confirmation**: does di-camera-app auto-accept bonding (`bt_set_pairing_confirm`) or show a prompt? Also, does it refuse non-SAP peers?
2. **BT power policy**: when does the camera turn BT off (auto power-off, modes)? Our service must re-register after the adapter is re-enabled.
3. **Restart side effects**: restarting di-camera-app after the fast-boot resume (SWSUSP snapshot). Check camera, display and LED state after restart. Fallback: start the helper as a separate process for RFCOMM only and trigger DLNA via the `st` IPC, if that turns out to reach CUINETFuncDLNA (pending in the firmware session).
4. `Start()` state: confirm that `Start(inst,1,false)` takes the softAP branch. `start_network` also reads `CUINETFuncBluetooth[0x662]` (use_wifidirect, normally set by the BT `info` message), so check its default value.
5. `device-desc-url` is normally pushed over BT. Without SAP, get it from SSDP M-SEARCH on the softAP, or build it from the known form `http://<ip>:7676/smp_2_`.
6. SMACK label of files copied to `/tmp`: confirm that `LD_PRELOAD` mapping is allowed in practice.

## Hardware test sequence

1. Harmless `info.tg` script that only writes a marker plus a system info dump to the SD (firmware session, see 03).
2. Script that runs `st app softap start` and dumps the softAP SSID, passphrase and IP. Check that the phone can join with `WifiNetworkSpecifier`.
3. Preload a library that only logs (dlog plus a file on SD) and resolves the symbols above, without calling them.
4. Call `CUINETFuncDLNA::Start(inst, 2, false)` from the main loop. Check that `http://<ip>:7676/smp_2_` answers.
5. Add the RFCOMM server and test from a minimal Android test tool.
