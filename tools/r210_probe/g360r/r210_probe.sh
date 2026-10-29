#!/bin/sh
# Revive360 - SM-R210 (Gear 360 2017) read-only probe, run once by the stock
# SD factory hook (info.tg -> cmd.tg -> this script). Writes nothing to the
# camera's eMMC: it only reads system state and logs it to the card, in
# /sdcard/g360r/log/r210_probe.log. See docs/firmware/15_r210_port.md.
#
# Disarms itself (renames /sdcard/info.tg) so the next boot is a normal one.
# Beeps: 1 = started, 2 = done (safe to power off), 3 error = no log written.

LOG=/sdcard/g360r/log/r210_probe.log
export PATH=/usr/sbin:/sbin:/usr/bin:/bin

if [ "$1" != "__detached" ]; then
    setsid /bin/sh "$0" __detached </dev/null >/dev/null 2>&1 &
    exit 0
fi

SND=/usr/apps/com.samsung.di-camera-app/res/sounds/camera
BEEPLOG=/sdcard/g360r/log/r210_beep.log
# paplay alone is silent on the R210: the rt5659 speaker switches ('Ext Spk L/R')
# are only switched on by the camera's own mm-sound player. Switch them on first
# (state logged before), then play; aplay is the fallback.
beep() {
    n=$1; w=$SND/$2; i=0
    amixer sget 'Ext Spk L' 2>&1 | grep -i "playback \[" >>"$BEEPLOG"
    amixer -q sset 'Ext Spk L' on >>"$BEEPLOG" 2>&1
    amixer -q sset 'Ext Spk R' on >>"$BEEPLOG" 2>&1
    while [ "$i" -lt "$n" ]; do
        paplay "$w" >>"$BEEPLOG" 2>&1; r=$?
        echo "paplay $2 exit $r" >>"$BEEPLOG"
        if [ "$r" -ne 0 ]; then
            aplay "$w" >>"$BEEPLOG" 2>&1
            echo "aplay $2 exit $?" >>"$BEEPLOG"
        fi
        i=$((i + 1)); sleep 0.3
    done
}

if ! mkdir -p /sdcard/g360r/log; then
    beep 3 error.wav
    exit 0
fi
beep 1 confirm.wav

# Disarm first: whatever happens next, the following boot is a normal one.
# (rm before mv: on the camera's exFAT driver mv onto an existing name left two
# directory entries with the same name.)
rm -f /sdcard/info.tg.done
mv /sdcard/info.tg /sdcard/info.tg.done

: > "$LOG"
section() { echo; echo "===== $*"; }
run() {
    section "$*"
    "$@" 2>&1 | head -n 400
}
show() {
    for f in "$@"; do
        [ -e "$f" ] || continue
        section "$f"
        head -c 16384 "$f" 2>&1
        echo
    done
}

{
    echo "r210_probe $(date '+%Y-%m-%d %H:%M:%S') uptime $(cut -d' ' -f1 /proc/uptime)"
    show /etc/info.ini /proc/version /proc/cmdline /proc/cpuinfo /proc/meminfo
    show /opt/etc/.mac.info /opt/etc/.cid.info /opt/etc/version /etc/version
    run ls -la /opt/etc /opt/usr /fw/log
    show /fw/log/upgrade.txt

    # Storage and partitions
    run cat /proc/partitions
    run ls -la /dev/mmcblk0p8 /dev/mmcblk0p9 /dev/mmcblk1 /dev/mmcblk1p1
    run cat /proc/mounts
    run df -k
    run ls -la /sdcard/ /sdcard/DCIM

    # LEDs, battery, keys
    section "/sys/class/leds"
    for d in /sys/class/leds/*; do
        echo "$d: max=$(cat "$d/max_brightness" 2>/dev/null) now=$(cat "$d/brightness" 2>/dev/null) trigger=$(cat "$d/trigger" 2>/dev/null)"
    done
    section "/sys/class/power_supply"
    for d in /sys/class/power_supply/*; do
        echo "--- $d"
        for f in "$d"/*; do [ -f "$f" ] && echo "$(basename "$f")=$(head -c 200 "$f" 2>/dev/null)"; done
    done
    show /proc/bus/input/devices
    run ls -la /sys/class/sec/key/

    # Wi-Fi / Bluetooth
    run ifconfig -a
    run ip addr
    run rfkill list
    run lsmod
    section "/sys/module/dhd/parameters"
    for f in /sys/module/dhd/parameters/*; do echo "$(basename "$f")=$(head -c 200 "$f" 2>/dev/null)"; done
    show /opt/var/log/wlanplug_log /etc/hostapd.conf /opt/etc/hostapd.conf
    run ls -la /lib/firmware /opt/etc/nvram

    # Processes and services
    run ps -eo pid,ppid,etime,args
    run systemctl list-units --no-pager --type=service
    run systemctl cat di-camera-app.service

    # Kernel log last
    run dmesg
} >> "$LOG" 2>&1

sync
echo "=== done" >> "$LOG"
sync
beep 2 confirm.wav
