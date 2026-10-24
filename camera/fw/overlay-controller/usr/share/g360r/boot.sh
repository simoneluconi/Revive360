#!/bin/sh
# Revive360 - normal-mode entry point (modified firmware, g360r.service).
#
# The stock camera boots as usual; this starts our controller next to it, the
# same launcher the factory SD hook starts, in normal mode (G360R_MODE=normal:
# the stock app is alive, pets the watchdog and owns the power policy).
#
# Kill switches, both on the SD card: no g360r/g360r.conf (the passphrase) =
# nothing runs; /sdcard/g360r.off = nothing runs. The payload on the SD
# (g360r/bin/g360rd, g360r/launcher.sh, g360r/netup.sh) wins over the copy
# built into the firmware, so a new controller needs only a card, not a flash.
#
# The firmware's boot signature on the LEDs is a separate unit that runs
# before the stock app: boot_leds.sh (g360r-leds.service).

SD_DIR=/sdcard/g360r
SYS_DIR=/usr/share/g360r
RUN=/tmp/g360r
export PATH=/usr/sbin:/sbin:/usr/bin:/bin

# The card is mounted by the stock stack some seconds into the boot
i=0
while [ ! -f "$SD_DIR/g360r.conf" ] && [ $i -lt 120 ]; do sleep 1; i=$((i + 1)); done
[ -f "$SD_DIR/g360r.conf" ] || exit 0
[ -e /sdcard/g360r.off ] && exit 0

mkdir -p "$RUN" "$SD_DIR/log" 2>/dev/null
BOOTLOG="$SD_DIR/log/start.log"
if [ -f "$RUN/launcher.pid" ] && kill -0 "$(cat "$RUN/launcher.pid" 2>/dev/null)" 2>/dev/null; then
    echo "boot.sh: launcher already running (pid $(cat "$RUN/launcher.pid")), exiting" >> "$BOOTLOG"
    exit 0
fi

# pick SD_PATH BUILTIN_PATH: the SD copy if present, else the built-in one
pick() {
    if [ -f "$1" ]; then echo "$1"; else echo "$2"; fi
}
src_bin=$(pick "$SD_DIR/bin/g360rd" "$SYS_DIR/g360rd")
src_launcher=$(pick "$SD_DIR/launcher.sh" "$SYS_DIR/launcher.sh")
src_netup=$(pick "$SD_DIR/netup.sh" "$SYS_DIR/netup.sh")
{
    echo "=== boot.sh (normal mode) $(date '+%Y-%m-%d %H:%M:%S') uptime $(cut -d' ' -f1 /proc/uptime) pid $$"
    echo "card ready after ${i}s"
    echo "firmware: $(cat "$SYS_DIR/VERSION" 2>/dev/null || echo unknown)"
    echo "payload: $src_bin $src_launcher $src_netup"
} > "$BOOTLOG" 2>&1

# Payload to tmpfs, as in the factory path: keeps running if the card is pulled
if ! cp "$src_bin" "$RUN/g360rd" || ! cp "$src_launcher" "$RUN/launcher.sh" || ! cp "$src_netup" "$RUN/netup.sh"; then
    echo "copy to $RUN FAILED" >> "$BOOTLOG"
    exit 1
fi
chmod 0755 "$RUN/g360rd" "$RUN/launcher.sh" 2>/dev/null
sync

export G360R_MODE=normal
exec /bin/sh "$RUN/launcher.sh"
