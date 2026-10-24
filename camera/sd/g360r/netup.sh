# shellcheck shell=sh
# Revive360 - netup.sh  (SOURCED by launcher.sh; defines netup() + dhcp_check())
#
# Context: runs on the owner's own Samsung Gear 360 (abandoned by the maker),
# started from the microSD factory trigger. On-device only; no third-party systems.
#
# Contract (see launcher.sh "network" section + README "Network bring-up"):
#   - This file is SOURCED. No top-level actions, no `set -u`, no `exit`.
#   - Do NOT redefine log()/logrun()/milestone()/cfg(); call them (provided by launcher).
#   - netup(): return 0 on success and set AP_SSID AP_IF AP_IP AP_STRATEGY.
#   - dhcp_check(): called every 5 s by the supervisor; keep the DHCP server alive.
#   - Write only under /tmp/g360r and the SD. Nothing under /opt.
#
# Strategy "dhd-privcmd": the Broadcom dhd brings up the softAP via a wireless-ext
# private ioctl (0x8B0C = SIOCSIWPRIV) carrying ASCII AP commands. g360rd exposes
# the primitives (privcmd/ifaddr/ifup/waitif). The AP/STA firmware must be loaded
# first (direct sysfs). The HW watchdog is handled by the launcher, not here.
# Never touch wlan0 before the firmware is loaded; the SSID suffix comes from the
# MAC file, not from an ioctl on wlan0.

# Config (overridable in g360r.conf via the launcher's cfg()):
G360R_STA_IF=wlan0           # primary Broadcom iface (STA firmware name)
G360R_AP_IF=wl0.1            # softAP virtual iface that AP_BSS_START creates
G360R_AP_IP=192.168.107.1
G360R_AP_MASK=255.255.255.0
G360R_DHCP_LO=192.168.107.10
G360R_DHCP_HI=192.168.107.50
G360R_MAC_FILE=/opt/etc/.mac.info

# SSID: cfg override, else G360R-<last 3 MAC bytes, upper> from the MAC file.
# Never query wlan0 for the MAC (ioctl on wlan0 before firmware is loaded is unsafe).
_netup_ssid() {
    s=$(cfg ssid "")
    if [ -n "$s" ]; then echo "$s"; return; fi
    hex=$(tr -cd '0-9A-Fa-f' < "$G360R_MAC_FILE" 2>/dev/null)
    suf=$(printf %s "$hex" | tail -c 6 | tr 'a-f' 'A-F')
    if [ -n "$suf" ]; then echo "G360R-$suf"; else echo "G360R-000000"; fi
}

# --- firmware: load the concurrent AP/STA dhd image (mirrors stock wlan.sh softap,
#     but direct-sysfs so nothing is written under /opt). ---
_netup_load_apsta_fw() {
    fwp=/sys/module/dhd/parameters/firmware_path
    nvp=/sys/module/dhd/parameters/nvram_path
    chip=bcm4354
    rev=$(cat /sys/devices/platform/dw_mmc_sdio.0/board_revision 2>/dev/null)
    [ "$rev" = "0" ] && chip=bcm4339
    fw="/lib/firmware/wlan_apsta_${chip}.bin"
    nv="/opt/etc/nvram/nvram_net_${chip}.txt"
    log "netup: AP/STA firmware $fw (chip $chip, board_rev=${rev:-?})"
    logrun cat "$fwp"
    if [ -f "$fw" ] && [ -w "$fwp" ]; then
        echo "$fw" > "$fwp" 2>/dev/null
        [ -f "$nv" ] && echo "$nv" > "$nvp" 2>/dev/null
        # firmware_path only takes effect on a down->up cycle
        "$BIN" ifdown "$G360R_STA_IF" 2>/dev/null
        "$BIN" ifup   "$G360R_STA_IF" 2>/dev/null
    else
        log "netup: WARN cannot set firmware_path ($fw present? $( [ -f "$fw" ] && echo y || echo n ))"
    fi
    "$BIN" waitif "$G360R_STA_IF" 10
    # evidence that the firmware switch took and which ifaces exist
    logrun cat "$fwp"
    logrun "$BIN" ifflags "$G360R_STA_IF"
    logrun ls /sys/class/net
}

# privcmd wrapper. $2="mask" logs a copy with KEY=******** (never log the PSK) and
# runs the real command WITHOUT logrun so the passphrase never reaches the SD log.
_netup_privcmd() {
    cmd=$1
    if [ "$2" = mask ]; then
        masked=$(printf %s "$cmd" | sed 's/KEY=[^,]*/KEY=********/')
        log "netup: \$ privcmd $G360R_STA_IF $masked"
    else
        log "netup: \$ privcmd $G360R_STA_IF $cmd"
    fi
    out=$("$BIN" privcmd "$G360R_STA_IF" "$cmd" 2>&1); rc=$?
    # g360rd privcmd echoes reply=[<buf>]; dhd may leave the AP_CFG (with KEY=) in it.
    [ "$2" = mask ] && out=$(printf %s "$out" | sed 's/KEY=[^,]*/KEY=********/')
    log "netup: privcmd rc=$rc${out:+ $out}"
    return $rc
}

netup() {
    AP_STRATEGY="dhd-privcmd"
    AP_IF="$G360R_AP_IF"
    AP_IP="$G360R_AP_IP"

    AP_SSID=$(_netup_ssid)
    PSK=$(cfg psk "")
    CH=$(cfg channel 6)
    # Refuse to bring up an unprotected AP: it would expose camera control to anyone.
    if [ -z "$PSK" ]; then
        log "netup: no psk in g360r.conf - refusing to start an open AP"
        return 1
    fi
    SEC=wpa2-psk
    HIDE=0

    _netup_load_apsta_fw

    # AP_CFG then AP_BSS_START over the dhd private ioctl (AP_CFG masked in the log).
    sl=$(printf %s "$AP_SSID" | wc -c | tr -d ' ')
    kl=$(printf %s "$PSK"     | wc -c | tr -d ' ')
    _netup_privcmd "ASCII_CMD=AP_CFG,SSID_LEN=$sl,SSID=$AP_SSID,SEC=$SEC,KEY_LEN=$kl,KEY=$PSK,CHANNEL=$CH,PREAMBLE=0,MAX_SCB=10,HIDE=$HIDE,END" mask
    _netup_privcmd "ASCII_CMD=AP_BSS_START"

    # The AP BSS iface (wl0.1) usually appears after AP_BSS_START; fall back to wlan0.
    if "$BIN" waitif "$G360R_AP_IF" 5; then
        AP_IF="$G360R_AP_IF"
    else
        AP_IF="$G360R_STA_IF"
        log "netup: $G360R_AP_IF did not appear; using $G360R_STA_IF"
    fi

    "$BIN" ifaddr "$AP_IF" "$G360R_AP_IP" "$G360R_AP_MASK"
    "$BIN" ifup   "$AP_IF"
    logrun "$BIN" ifflags "$AP_IF"

    _netup_start_dhcp || { log "netup: DHCP failed to start"; return 1; }

    # sanity: the AP iface must be UP with our IP
    case "$("$BIN" ifflags "$AP_IF" 2>/dev/null)" in
        *UP*) log "netup: AP up ssid=$AP_SSID if=$AP_IF ip=$AP_IP"; return 0 ;;
        *)    log "netup: $AP_IF not UP after bring-up"; return 1 ;;
    esac
}

# --- DHCP: dnsmasq (2.57 on device); conf + leases under /tmp only (rootfs is RO). ---
_netup_dhcp_conf=/tmp/g360r/dnsmasq.conf
_netup_dhcp_pid=/tmp/g360r/dnsmasq.pid
_netup_dhcp_lease=/tmp/g360r/dnsmasq.leases

_netup_write_dhcp_conf() {
    {
        echo "interface=$AP_IF"
        echo "bind-interfaces"
        echo "except-interface=lo"
        echo "no-resolv"
        echo "no-hosts"
        echo "port=0"                                  # DHCP only, no DNS
        echo "user=root"                               # default 'nobody' may not exist on Tizen
        echo "dhcp-authoritative"
        echo "dhcp-leasefile=$_netup_dhcp_lease"       # default path is on RO rootfs
        echo "dhcp-range=$G360R_DHCP_LO,$G360R_DHCP_HI,$G360R_AP_MASK,12h"
        echo "dhcp-option=3,$G360R_AP_IP"              # router
        echo "dhcp-option=6"                           # no DNS server
        echo "pid-file=$_netup_dhcp_pid"
    } > "$_netup_dhcp_conf" 2>/dev/null
}

_netup_dhcp_running() {
    [ -f "$_netup_dhcp_pid" ] || return 1
    p=$(cat "$_netup_dhcp_pid" 2>/dev/null)
    [ -n "$p" ] && [ -d "/proc/$p" ]
}

_netup_start_dhcp() {
    _netup_write_dhcp_conf
    _netup_dhcp_running && return 0
    if [ -x /usr/bin/dnsmasq ]; then
        logrun /usr/bin/dnsmasq -C "$_netup_dhcp_conf"
        _netup_dhcp_running && return 0
        # some builds background differently; accept if the binary launched
        pgrep -f "dnsmasq -C $_netup_dhcp_conf" >/dev/null 2>&1 && return 0
    fi
    log "netup: /usr/bin/dnsmasq not usable"
    return 1
}

dhcp_check() {
    _netup_dhcp_running && return 0
    pgrep -f "dnsmasq -C $_netup_dhcp_conf" >/dev/null 2>&1 && return 0
    log "netup: dnsmasq not running, restarting"
    _netup_start_dhcp
}
