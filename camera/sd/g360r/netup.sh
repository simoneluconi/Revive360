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
#   - dhcp_check(): called every 5 s by the supervisor; keep DHCP (and hostapd) alive.
#   - Write only under /tmp/g360r and the SD. Nothing under /opt.
#
# Strategy "hostapd": the stock softAP on this build is /usr/sbin/hostapd with
# driver=nl80211 (mobileap-agent _mh_core_enable_softap backend 2). The older WEXT
# private-ioctl path (SIOCSIWPRIV 0x8B0C, ASCII_CMD=AP_CFG/AP_BSS_START) returns
# EOPNOTSUPP on this cfg80211 bcmdhd build, so it is NOT used here. The default wlan_net
# STA firmware does NOT support the AP iftype, so hostapd aborts on it; the stock
# /usr/bin/wlan.sh loads the APSTA firmware image first (wlan.sh stop -> wlan.sh softap,
# which writes firmware_path + nvram and cycles the iface). Before hostapd can own wlan0
# we must release it from any STA-stack unit, unblock rfkill, and load that APSTA image.
# The passphrase is never logged in clear: the conf stays under /tmp and hostapd's own
# debug log is masked when copied to the SD.

# Config (overridable in g360r.conf via the launcher's cfg()):
G360R_STA_IF=wlan0           # the single Broadcom iface; nl80211 makes it the AP iface
G360R_AP_IF=wlan0            # AP runs on wlan0 itself (no wl0.1 with nl80211)
G360R_AP_IP=192.168.107.1
G360R_AP_MASK=255.255.255.0
G360R_DHCP_LO=192.168.107.10
G360R_DHCP_HI=192.168.107.50
G360R_MAC_FILE=/opt/etc/.mac.info
# STA-stack units that may own wlan0 and must be stopped before hostapd takes it.
G360R_STA_UNITS="connman wpa_supplicant net-config mobileap-agent"

_netup_dir=/tmp/g360r
_netup_hostapd_conf=$_netup_dir/hostapd.conf
_netup_hostapd_pid=$_netup_dir/hostapd.pid
_netup_hostapd_log=$_netup_dir/hostapd.log
_netup_hostapd_ctrl=$_netup_dir/hostapd        # hostapd ctrl_interface dir (RW, /tmp)

# SSID: cfg override, else G360R-<last 3 MAC bytes, upper> from the MAC file.
_netup_ssid() {
    s=$(cfg ssid "")
    if [ -n "$s" ]; then echo "$s"; return; fi
    hex=$(tr -cd '0-9A-Fa-f' < "$G360R_MAC_FILE" 2>/dev/null)
    suf=$(printf %s "$hex" | tail -c 6 | tr 'a-f' 'A-F')
    if [ -n "$suf" ]; then echo "G360R-$suf"; else echo "G360R-000000"; fi
}

# --- (1) release wlan0 from the STA stack ------------------------------------------
# Stock keeps wlan0 in STA mode via connman + wpa_supplicant (+ net-config). hostapd
# cannot drive the iface while they hold it, so stop whichever units are present.
_netup_release_sta() {
    logrun sh -c "systemctl list-units 2>/dev/null | grep -iE 'connman|supplicant|wifi|mobileap|net-config' || true"
    for u in $G360R_STA_UNITS; do
        if systemctl list-units --all 2>/dev/null | grep -q "$u"; then
            log "netup: stopping STA-stack unit $u"
            logrun systemctl stop "$u"
        fi
    done
    # connman may have spawned a wpa_supplicant that outlives the unit; note any left.
    if pgrep -f wpa_supplicant >/dev/null 2>&1; then
        log "netup: WARN wpa_supplicant still running after unit stop"
        logrun sh -c 'pgrep -af wpa_supplicant 2>/dev/null || true'
    fi
}

# --- (2) rfkill ---------------------------------------------------------------------
_netup_rfkill() {
    rf=$(command -v rfkill 2>/dev/null); [ -n "$rf" ] || { [ -x /usr/sbin/rfkill ] && rf=/usr/sbin/rfkill; }
    if [ -n "$rf" ]; then
        logrun "$rf" list
        logrun "$rf" unblock wifi
    else
        log "netup: rfkill not present (skipping unblock)"
    fi
}

# --- (3) load the APSTA firmware via the stock wlan.sh ------------------------------
# The default wlan_net STA image does not support the AP iftype. Stock softAP uses
# /usr/bin/wlan.sh: `stop` (ifconfig wlan0 down) then `softap`, which writes the APSTA
# image (wlan_apsta_bcm4354.bin, _4339 if board_revision==0) to firmware_path + nvram
# and brings the iface up. softap()/stop() write nothing to /opt. Then prove it loaded.
_netup_load_apsta_fw() {
    if [ -x /usr/bin/wlan.sh ]; then
        logrun /usr/bin/wlan.sh stop
        logrun /usr/bin/wlan.sh softap
    else
        log "netup: WARN /usr/bin/wlan.sh not present - APSTA firmware will not load"
    fi
    logrun cat /sys/module/dhd/parameters/firmware_path
    if [ -x /usr/bin/wl ]; then
        logrun /usr/bin/wl ver      # firmware build string (apsta vs sta)
        logrun /usr/bin/wl cap      # capabilities: look for "ap"/"apsta"
    fi
    logrun sh -c 'dmesg 2>/dev/null | tail -n 12'
}

# --- (4) hostapd.conf (passphrase only on disk under /tmp, never logged) ------------
_netup_write_hostapd_conf() {
    mkdir -p "$_netup_dir" "$_netup_hostapd_ctrl" 2>/dev/null
    HIDE=$(cfg hide 0)
    # A 64-hex secret is a pre-computed PMK (wpa_psk=); anything else is a passphrase.
    case "$PSK" in
        *[!0-9A-Fa-f]* ) key_line="wpa_passphrase=$PSK" ;;
        ????????????????????????????????????????????????????????????????) key_line="wpa_psk=$PSK" ;;
        * ) key_line="wpa_passphrase=$PSK" ;;
    esac
    {
        echo "interface=$G360R_AP_IF"
        echo "driver=nl80211"
        echo "ctrl_interface=$_netup_hostapd_ctrl"
        echo "ssid=$AP_SSID"
        if [ "$BAND" = 5 ]; then
            # 5 GHz, 802.11n/ac on a 20 MHz channel: the AP firmware's driver refuses
            # HT40 ("Driver does not support configured HT capability [HT40*]"), and
            # so 80 MHz; 5 GHz is still far less crowded than 2.4
            echo "channel=$CH"
            echo "hw_mode=a"
            echo "ieee80211n=1"
            echo "ieee80211ac=1"
            echo "wmm_enabled=1"
            echo "ht_capab=[SHORT-GI-20]"
            echo "vht_oper_chwidth=0"
        else
            echo "channel=$CH"
            echo "hw_mode=g"
            echo "ieee80211n=1"
        fi
        echo "max_num_sta=1"
        echo "ignore_broadcast_ssid=$HIDE"
        echo "wpa=2"
        echo "wpa_key_mgmt=WPA-PSK"
        echo "rsn_pairwise=CCMP"
        echo "$key_line"
    } > "$_netup_hostapd_conf" 2>/dev/null
    chmod 600 "$_netup_hostapd_conf" 2>/dev/null
    log "netup: wrote $_netup_hostapd_conf (ssid=$AP_SSID band=$BAND ch=$CH hide=$HIDE, key masked)"
}

# Copy hostapd's -dd log into the SD log, masking the passphrase (literal) AND any
# line that could carry derived key material (PSK/PMK/PTK/GTK/nonce/...).
_netup_import_hostapd_log() {
    [ -f "$_netup_hostapd_log" ] || return 0
    log "netup: --- hostapd.log (masked, tail) ---"
    tail -n 160 "$_netup_hostapd_log" 2>/dev/null | awk -v p="$PSK" '
    {
        line=$0
        if (p != "") {                      # literal (regex-safe) redaction of the secret
            out=""; rest=line
            while ((i=index(rest,p))>0) { out=out substr(rest,1,i-1) "********"; rest=substr(rest,i+length(p)) }
            line=out rest
        }
        if (line ~ /[Pp]assphrase|PSK|PMK|PTK|GTK|GMK|[Nn]once|pairwise|master key|wpa_psk/) line="    [redacted: key material]"
        print "    " line
    }' | while IFS= read -r l; do log "$l"; done
    log "netup: --- end hostapd.log ---"
}

_netup_hostapd_running() {
    if [ -f "$_netup_hostapd_pid" ]; then
        p=$(cat "$_netup_hostapd_pid" 2>/dev/null)
        [ -n "$p" ] && [ -d "/proc/$p" ] && return 0
    fi
    pgrep -f "hostapd .*$_netup_hostapd_conf" >/dev/null 2>&1
}

_netup_hostapd_up_in_log() {
    grep -qE "AP-ENABLED|Setup of interface done|Using interface $G360R_AP_IF" \
        "$_netup_hostapd_log" 2>/dev/null
}

_netup_start_hostapd() {
    [ -x /usr/sbin/hostapd ] || { log "netup: /usr/sbin/hostapd not present"; return 1; }
    # Foreground (-dd) backgrounded by the shell, NOT -B: if hostapd aborts before it
    # would daemonize, with -B that early stdout/stderr is lost; here it all lands in the
    # log so we capture the real error. We record the shell's $! as the pid. The log is
    # masked on import; if a build is found to dump the passphrase despite the mask,
    # lower -dd to -d here.
    log "netup: \$ /usr/sbin/hostapd -dd $_netup_hostapd_conf > $_netup_hostapd_log 2>&1 &"
    /usr/sbin/hostapd -dd "$_netup_hostapd_conf" > "$_netup_hostapd_log" 2>&1 &
    echo $! > "$_netup_hostapd_pid"
    i=0
    while [ "$i" -lt 8 ]; do
        _netup_hostapd_running || { log "netup: hostapd exited during startup"; return 1; }
        _netup_hostapd_up_in_log && return 0
        sleep 1
        i=$((i + 1))
    done
    log "netup: hostapd did not report AP-ENABLED within ~8 s"
    _netup_hostapd_running   # alive but unconfirmed -> let caller decide via wl
}

# --- (6) confirm AP mode via the wl tool (authoritative on this driver) -------------
# Returns: 0 = confirmed AP, 1 = confirmed NOT AP, 2 = wl unavailable (unknown).
_netup_wl_is_ap() {
    [ -x /usr/bin/wl ] || return 2
    logrun /usr/bin/wl ap
    logrun /usr/bin/wl status
    ap=$(/usr/bin/wl ap 2>/dev/null | tr -cd '0-9')
    [ "$ap" = "1" ] && return 0
    /usr/bin/wl status 2>/dev/null | grep -qiE "BSS .*(up|started)|AP .*up|SSID.*$AP_SSID" && return 0
    return 1
}

# One access point attempt on $BAND/$CH: conf, hostapd, the checks. 0 = up on that
# band; else hostapd is stopped again for the next attempt.
_netup_try_ap() {
    _netup_write_hostapd_conf
    _netup_start_hostapd
    _netup_import_hostapd_log     # copy masked hostapd log regardless of outcome
    # Success requires hostapd alive AND AP mode confirmed (wl, or hostapd's own
    # AP-ENABLED when wl is unavailable).
    if ! _netup_hostapd_running; then
        log "netup: hostapd not running ($BAND GHz)"
        return 1
    fi
    _netup_wl_is_ap; wl_rc=$?
    case "$wl_rc" in
        0) log "netup: AP confirmed by wl" ;;
        1) log "netup: hostapd up but wl reports NOT in AP mode ($BAND GHz)"; _netup_stop_hostapd; return 1 ;;
        2) if ! _netup_hostapd_up_in_log; then
               log "netup: wl unavailable and hostapd never reported AP-ENABLED ($BAND GHz)"
               _netup_stop_hostapd
               return 1
           fi
           log "netup: wl unavailable; accepting hostapd AP-ENABLED as confirmation" ;;
    esac
    # On 5 GHz the radio must really be there ("Using interface" comes before the channel)
    if [ "$BAND" = 5 ] && [ -x /usr/bin/wl ] && ! /usr/bin/wl status 2>/dev/null | grep -q "5GHz"; then
        log "netup: the AP did not come up on 5 GHz"
        _netup_stop_hostapd
        return 1
    fi
    return 0
}

_netup_stop_hostapd() {
    [ -f "$_netup_hostapd_pid" ] && { p=$(cat "$_netup_hostapd_pid" 2>/dev/null); [ -n "$p" ] && kill "$p" 2>/dev/null; }
    pkill -f "hostapd -dd $_netup_hostapd_conf" 2>/dev/null
    rm -f "$_netup_hostapd_pid" 2>/dev/null
    sleep 1
}

netup() {
    AP_STRATEGY="hostapd"
    AP_IF="$G360R_AP_IF"
    AP_IP="$G360R_AP_IP"

    AP_SSID=$(_netup_ssid)
    PSK=$(cfg psk "")
    # band=5 (default) tries 5 GHz first and falls back to 2.4 GHz; band=2.4 only that
    BAND=$(cfg band 5)
    CH5=$(cfg channel_5g 36)
    CH24=$(cfg channel 6)
    # Refuse an unprotected AP: it would expose camera control to anyone in range.
    if [ -z "$PSK" ]; then
        log "netup: no psk in g360r.conf - refusing to start an open AP"
        return 1
    fi

    mkdir -p "$_netup_dir" 2>/dev/null

    _netup_release_sta            # (1) free wlan0 from any STA-stack unit
    _netup_rfkill                 # (2) unblock the radio
    _netup_load_apsta_fw          # (3) wlan.sh stop + softap: load the APSTA firmware

    # (4) conf under /tmp (key not logged), hostapd, checks: 5 GHz first unless told not to
    if [ "$BAND" = 5 ]; then
        CH=$CH5
        if ! _netup_try_ap; then
            log "netup: 5 GHz failed, falling back to 2.4 GHz"
            BAND=2.4
        fi
    fi
    if [ "$BAND" != 5 ]; then
        BAND=2.4
        CH=$CH24
        if ! _netup_try_ap; then
            log "netup: FAIL - no access point on 2.4 GHz either"
            logrun ls /sys/class/net
            return 1
        fi
    fi
    G360R_AP_BAND=$BAND
    log "netup: access point on $BAND GHz, channel $CH"

    # (5) address + DHCP on wlan0
    logrun "$BIN" ifaddr "$AP_IF" "$G360R_AP_IP" "$G360R_AP_MASK"
    logrun "$BIN" ifup   "$AP_IF"
    logrun "$BIN" ifflags "$AP_IF"
    _netup_start_dhcp || { log "netup: DHCP failed to start"; return 1; }

    case "$("$BIN" ifflags "$AP_IF" 2>/dev/null)" in
        *UP*) log "netup: AP up ssid=$AP_SSID if=$AP_IF ip=$AP_IP (hostapd/nl80211)"; return 0 ;;
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
        pgrep -f "dnsmasq -C $_netup_dhcp_conf" >/dev/null 2>&1 && return 0
    fi
    log "netup: /usr/bin/dnsmasq not usable"
    return 1
}

# --- resume-safety ------------------------------------------------------------------
# This controller is frozen into the hibernation snapshot and thawed on every boot
# (see docs/firmware/10). Across snapshot->resume the device power-cycles, so the dhd
# chip / wlan0 / hostapd are RESET: a frozen hostapd PID reappears in /proc but the
# radio is dead. So the health check must verify AP RADIO state, not pid-alive, and on
# failure tear down and re-run the FULL bring-up fresh (idempotent).

# Quiet radio check (no logrun; used on every supervisor tick). 0=AP up, 1=not, 2=wl absent.
_netup_wl_ap_quiet() {
    [ -x /usr/bin/wl ] || return 2
    ap=$(/usr/bin/wl ap 2>/dev/null | tr -cd '0-9')
    [ "$ap" = "1" ] && return 0
    /usr/bin/wl status 2>/dev/null | grep -qiE "BSS .*(up|started)|AP .*up|SSID.*$AP_SSID" && return 0
    return 1
}

# True only if the AP is really serving: hostapd alive AND iface UP AND radio in AP mode
# (or hostapd's own AP-ENABLED when wl is unavailable) AND dnsmasq alive.
_netup_ap_healthy() {
    _netup_hostapd_running || return 1
    case "$("$BIN" ifflags "$G360R_AP_IF" 2>/dev/null)" in *UP*) : ;; *) return 1 ;; esac
    # `wl ap` is advisory only: its answer under cfg80211/hostapd is not confirmed on the
    # hardware, and a false "not AP" here would drop the phone every 5 s
    if ! _netup_wl_ap_quiet && [ "$_netup_wl_warned" != 1 ]; then
        log "netup: wl does not report AP mode (advisory, AP kept)"
        _netup_wl_warned=1
    fi
    _netup_dhcp_running || pgrep -f "dnsmasq -C $_netup_dhcp_conf" >/dev/null 2>&1 || return 1
    return 0
}

# Idempotent tear-down: kill our hostapd + dnsmasq so the fresh bring-up owns the iface.
# (wlan.sh stop is left to netup()'s _netup_load_apsta_fw, which runs it before softap.)
_netup_teardown() {
    log "netup: tearing down AP for a fresh bring-up"
    [ -f "$_netup_hostapd_pid" ] && { p=$(cat "$_netup_hostapd_pid" 2>/dev/null); [ -n "$p" ] && kill "$p" 2>/dev/null; }
    pkill -f "hostapd -dd $_netup_hostapd_conf" 2>/dev/null
    [ -f "$_netup_dhcp_pid" ] && { p=$(cat "$_netup_dhcp_pid" 2>/dev/null); [ -n "$p" ] && kill "$p" 2>/dev/null; }
    pkill -f "dnsmasq -C $_netup_dhcp_conf" 2>/dev/null
    rm -f "$_netup_hostapd_pid" "$_netup_dhcp_pid" 2>/dev/null
}

# Supervisor tick (called every ~5 s): verify AP radio health and, if down, tear down +
# re-run the full bring-up. No wall-clock heuristic: the app sets the camera clock on
# every connection (POST /api/v1/time), a jump of years that must not drop the AP.
_netup_wl_warned=0
_netup_last_reinit=0

dhcp_check() {
    if ! _netup_ap_healthy; then
        now=$(cut -d. -f1 /proc/uptime)
        # At most one full re-init a minute (monotonic uptime, not the wall clock)
        [ $((now - _netup_last_reinit)) -lt 60 ] && return 1
        _netup_last_reinit=$now
        log "netup: AP not healthy (radio/iface/dhcp down) - re-bringing up"
        _netup_teardown; netup; return $?
    fi
    return 0
}
