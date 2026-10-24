#!/bin/sh
# Revive360 - SD bootstrap.
#
# Reached as root through the factory hook validated on the SM-C200 (AQK1):
#   di-camera-app -> dfmsd (script mode, /sdcard/info.tg names cmd.tg)
#   -> cmd.tg line "shell script /sdcard/g360r/start.sh" -> system() -> here.
#
# Our job here is only to get out of the way quickly and safely:
#   1. detach (dfmsd waits for system() to return),
#   2. leave di-camera-app's cgroup (probe 3 method: write our PID to the root
#      name=systemd cgroup) so nothing that happens to that service takes us down,
#   3. copy the payload to tmpfs (/tmp/g360r) so it keeps running even if the
#      card is pulled, and start the launcher from there.
#
# Nothing persistent is written outside the SD card. Never restarts or kills
# di-camera-app (doing so hangs the camera).

SD_DIR=/sdcard/g360r
RUN=/tmp/g360r
export PATH=/usr/sbin:/sbin:/usr/bin:/bin

# First thing: disable the hardware watchdog. The stock app pets it; in factory
# mode nobody does, and the SoC resets ~30 s later (the Test 1 reset loop).
# Same command di-camera-app uses (UI_Devman_Disable_Watchdog). The launcher
# repeats it and keeps it off.
WDT=/sys/devices/platform/jack/set_watchdog
[ -e "$WDT" ] && echo 0 > "$WDT" 2>/dev/null

if [ "$1" != "__detached" ]; then
    setsid /bin/sh "$SD_DIR/start.sh" __detached </dev/null >/dev/null 2>&1 &
    exit 0
fi

mkdir -p "$RUN" "$SD_DIR/log" 2>/dev/null
BOOTLOG="$SD_DIR/log/start.log"
{
    echo "=== start.sh $(date '+%Y-%m-%d %H:%M:%S') uptime $(cut -d' ' -f1 /proc/uptime) pid $$"
    echo "watchdog: $WDT $([ -e "$WDT" ] && echo "present, disabled (reads: $(cat "$WDT" 2>&1))" || echo MISSING)"
    echo "cgroup before:"; cat /proc/self/cgroup
    if grep -q "di-camera-app" /proc/self/cgroup 2>/dev/null; then
        if echo "$$" > /sys/fs/cgroup/systemd/tasks; then
            echo "moved to the root systemd cgroup"
        else
            echo "cgroup move FAILED (continuing)"
        fi
    fi
    echo "cgroup after:"; cat /proc/self/cgroup
} > "$BOOTLOG" 2>&1

# Single instance: the hook can in principle fire again on a card event.
if [ -f "$RUN/launcher.pid" ] && kill -0 "$(cat "$RUN/launcher.pid" 2>/dev/null)" 2>/dev/null; then
    echo "launcher already running (pid $(cat "$RUN/launcher.pid")), exiting" >> "$BOOTLOG"
    sync
    exit 0
fi

# Payload to tmpfs.
if ! cp "$SD_DIR/bin/g360rd" "$SD_DIR/launcher.sh" "$RUN/" >> "$BOOTLOG" 2>&1; then
    echo "copy to $RUN FAILED" >> "$BOOTLOG"
    sync
    exit 1
fi
# Optional network bring-up script (see launcher.sh).
[ -f "$SD_DIR/netup.sh" ] && cp "$SD_DIR/netup.sh" "$RUN/" >> "$BOOTLOG" 2>&1
chmod 755 "$RUN/g360rd" "$RUN/launcher.sh" "$RUN/netup.sh" 2>/dev/null
echo "payload copied to $RUN, starting launcher" >> "$BOOTLOG"
sync

# Children inherit our (root) cgroup; the launcher stays in this session.
exec /bin/sh "$RUN/launcher.sh"
