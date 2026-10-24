#!/bin/sh
# Revive360 - one-shot: invalidate the factory snapshot on p8, so the
# bootloader cold-boots the (modified) rootfs from now on. Run once through the
# SD factory hook AFTER flashing the modified firmware.
#
# Same as stock hibernation.sh: the swsusp signature sits at the end of page 0,
# so it takes mkswap (not a 512-byte dd). Reversible: reflashing the stock
# firmware writes the factory snapshot back.
#
# Disarms itself (renames /sdcard/info.tg) so the next boot is a normal one.

SNAP=/dev/mmcblk0p8
LOG=/sdcard/g360r/log/p8_invalidate.log
export PATH=/usr/sbin:/sbin:/usr/bin:/bin

if [ "$1" != "__detached" ]; then
    setsid /bin/sh "$0" __detached </dev/null >/dev/null 2>&1 &
    exit 0
fi

mkdir -p /sdcard/g360r/log
log() { echo "$(date '+%H:%M:%S') $*" >> "$LOG"; sync; }
page0_tail() { dd if="$SNAP" bs=4096 count=1 2>/dev/null | tail -c 16 | od -An -c | tr -s ' '; }

log "=== p8 invalidate start (uptime $(cut -d' ' -f1 /proc/uptime))"
log "device: $(ls -la $SNAP 2>&1)"
log "page 0 tail before: $(page0_tail)"

# Disarm first: whatever happens next, the following boot is a normal one
mv /sdcard/info.tg /sdcard/info.tg.done && log "hook disarmed (info.tg -> info.tg.done)"

if [ -b "$SNAP" ] && command -v mkswap >/dev/null 2>&1; then
    mkswap "$SNAP" >> "$LOG" 2>&1
    log "mkswap exit $?"
else
    log "ERROR: $SNAP or mkswap missing; nothing written"
fi
sync
log "page 0 tail after: $(page0_tail)"
log "=== done: power off, then power on without info.tg for a cold boot"
echo done > /sdcard/g360r/p8_invalidate.done
sync
