# Firmware variants: APE4 vs AQK1 vs R210 (route C prerequisites)

Images (SHA256 matches KieronQuinn/Gear360_OSS):

| Variant | File | SLP ver | Model | Tizen |
|---|---|---|---|---|
| C200 APE4 | C200GLU0APE4_160519_1848_REV00_user.bin | 0.70 | SMC200 | 2.2.0 Magnolia |
| C200 AQK1 (user's camera) | C200GLU0AQK1_171121_1257_REV00_user.bin | 0.85 | SMC200 | 2.2.0 Magnolia |
| R210 ARB2 | R210GLU0ARB2_180201_1739_REV00_user.bin | 0.81 | SMR210 | 2.2.0 Magnolia |

All three use the same SLP container with 10 partitions and the same layout: part06 = rootfs (ext4, lzop), part07 = opt (ext4, lzop), part09 = SWSUSP image. `slp_unpack.py` handles all of them unchanged.

## Common to all three

- `di-camera-app` is an ELF32 ARM EXEC (non-PIE), ARMv7-A softfp, linked against glibc 2.13.
- Identical systemd unit: `ExecStart=/usr/apps/com.samsung.di-camera-app/bin/di-camera-app`, `EnvironmentFile=/run/tizen-mobile-env`, no `Restart=`.
- Tizen CAPI Bluetooth RFCOMM server API is present: `bt_socket_create_rfcomm`, `bt_socket_listen_and_accept_rfcomm`, `bt_socket_send_data`, `bt_socket_set_data_received_cb`.
- EFL 1.7.99 `ecore_main_loop_thread_safe_call_async/sync` is present.
- SD hook: `libfactory-mode.so` exports `dfms_check_factory_mode` and `dfms_launch_daemon`, `di-camera-app` imports both, and `/sdcard/info.tg` plus dfmsd "shell script" are present.
- **APE4 and AQK1 ship byte-identical `dfmsd` and `libfactory-mode.so`** (md5 `ed467c90…` and `ae57c255…`), so the gating confirmed on APE4 (03_sd_card_hook.md) applies to AQK1 unchanged. The R210 binaries differ but expose the same functions and strings. Its gating logic still needs a quick check.

## C200 (APE4 and AQK1): same entry points, different addresses

Resolve every symbol by name with `dlsym`. Never hard-code addresses.

| Symbol | APE4 | AQK1 |
|---|---|---|
| `_ZN14CUINETFuncDLNA4InstEv` | 0x00264728 | 0x00221298 |
| `_ZN14CUINETFuncDLNA5StartE20E_UNF_DLNA_FUNC_TYPEb` | 0x002720a4 | 0x0022dedc |
| `_ZN14CUINETFuncDLNA4StopEv` | 0x0026479c | 0x00221308 |
| `_ZN14CUINETFuncDLNA13IsStartedFuncE20E_UNF_DLNA_FUNC_TYPEb` | 0x00264850 | 0x002213bc |
| `_ZN13CUINETDlnaMan9StartDlnaEbb` | 0x0025e444 | 0x002478d0 |
| `_ZN19CUINETFuncBluetooth4InstEv` | 0x0024b2e0 | 0x0023a4e4 |
| `_ZN19CUINETFuncBluetooth29handle_bt_app_receive_commandEiiPc` | 0x0025191c | 0x00241b08 |
| `_ZN19CUINETFuncBluetooth25handle_bluetooth_callbackEiiiPc` | 0x002520a0 | 0x002423dc |
| `_Z17UI_SoftAP_Managerii` | 0x0016910c | 0x000f9c28 |
| `_Z21UI_WifiDirect_Managerii` | 0x001688d0 | 0x000f940c |

Call `Start(inst, func, useWifiDirect=0)` with func 1 = RVF (live view), 2 = mobile link, 3 = FW download. It copies arg3 into `this[0x5f2]`, so it takes the softAP branch whatever the previous BT state (firmware session, APE4; AQK1 still to be confirmed by disassembly).

## R210: rewritten network layer (`AppNet::` namespace)

**Confirmed by extraction (part06):** R210 `/etc/info.ini` = `Model=SMR210; Build=R210GLU0ARB2;
Date=2018.02.01; Time=17:41:25`. `/bin/sh` = bash. Toolset parity with C200 for route C:
`st`, `dfmsd`, `mobileap-agent`, `hostapd`, `udhcpd`, `dnsmasq`, `aplay`, `wpa_supplicant`,
`toybox` all present; **no** `nc`/`busybox`/`python`. So the SD→dfmsd hook, `st` capture, the
dhd-private-command softAP, and the no-shell-server constraint all port to R210; only the
deferred live-view (BT/DLNA `AppNet::` internals) differs.


The C200 names are absent. Equivalent exported entry points:

| Purpose | Symbol (demangled) | vaddr |
|---|---|---|
| DLNA singleton object (function-local static, exported as an object) | `CUINETFuncDLNA::getInstance()::inst` (`_ZZN14CUINETFuncDLNA11getInstanceEvE4inst`); guard `_ZGVZN14CUINETFuncDLNA11getInstanceEvE4inst` | 0x0038bc28 |
| Start | `CUINETFuncDLNA::start(AppNet::Function::Type, bool, bool)` | 0x0019fbd4 |
| Stop | `CUINETFuncDLNA::stop()` | 0x0017240c |
| Android-flavoured starter | `CUINETFuncDLNAManager::startAndroid(AppNet::Function::Type, bool, bool)` (weak) | 0x0022fb80 |
| High-level launcher used by the BT/SDK paths | `AppNet::Function::CManager::launch(Type, CallBy, bool, bool)` / `dismiss(Type, CallBy)` | 0x0022f9ec / 0x0022faf8 |
| DLNA manager singleton | `CUINETDlnaMan::getInstance()::inst` | 0x003aad98 |

Notes:
- The singleton objects are function-local statics, so `&inst` is the instance pointer. Before using one, check that its guard variable is non-zero (object already constructed).
- The R210 also has a "SDK" BT command path (`AppNet::Bluetooth::CSDKCommandManager::on_process_command_activate_upnp_req`, `on_process_command_connect_req`). This is Samsung's 360 SDK channel, and it may offer a cleaner command interface on that model.
- **TODO:** the `AppNet::Function::Type` enum values (RVF, ML, FW) and the meaning of the two bools. They come from disassembling `CSDKCommandManager::on_process_command_activate_upnp_req` and the R210 BT liveview handler.

## Helper design implication

There is one `libg360r.so` with a small per-model adapter, picked at runtime from `/etc/info.ini` (`Model=`, `Build=`) or from symbol presence:

- adapter `c200`: `CUINETFuncDLNA::Inst()` + `Start(E_UNF_DLNA_FUNC_TYPE,bool)`;
- adapter `r210`: `&CUINETFuncDLNA::getInstance()::inst` + `start(AppNet::Function::Type,bool,bool)`, or `Function::CManager::launch`.

The RFCOMM server, JSON protocol, softAP credential retrieval and EFL main-loop trampoline are shared.

## Safety note before the first hardware test

The C200 `di-camera-app` (APE4 and AQK1) contains `cp /mnt/mmc/info.tg /mnt/ubi1/alt/`. Before running the SD probe, confirm under which condition this runs, so the trigger file is never copied into internal storage and does not persist after the SD card is removed. The firmware session is checking this.
