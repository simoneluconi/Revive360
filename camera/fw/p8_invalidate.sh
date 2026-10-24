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
# Audible progress: 1 beep = started, 2 = done (safe to power off), 3 cancel = aborted
SND=/usr/apps/com.samsung.di-camera-app/res/sounds/camera
beep() {
    n=$1; w=$SND/$2; i=0
    while [ "$i" -lt "$n" ]; do paplay "$w" >/dev/null 2>&1; i=$((i + 1)); sleep 0.3; done
}
log() { echo "$(date '+%H:%M:%S') $*" >> "$LOG"; sync; }
page0_tail() { dd if="$SNAP" bs=4096 count=1 2>/dev/null | tail -c 16 | od -An -c | tr -s ' '; }

beep 1 04_N_OK.wav
log "=== p8 invalidate start (uptime $(cut -d' ' -f1 /proc/uptime))"
log "device: $(ls -la $SNAP 2>&1)"
log "page 0 tail before: $(page0_tail)"

# fw_upgrade's own report of the last flash (read-only)
log "fw_upgrade log (/fw/log/upgrade.txt), last lines:"
tail -n 40 /fw/log/upgrade.txt >> "$LOG" 2>&1
ls -la /fw/log/ >> "$LOG" 2>&1
sync

# Disarm first: whatever happens next, the following boot is a normal one
mv /sdcard/info.tg /sdcard/info.tg.done && log "hook disarmed (info.tg -> info.tg.done)"

# Is the modified rootfs on the eMMC? The resumed factory snapshot carries the
# kernel's old page cache for p9, which hid our blocks in earlier runs: drop the
# caches first, then read the raw partition for a string only our canary has.
# Logged only: the snapshot is invalidated either way, because the cold boot
# that follows is the real test (a stock rootfs simply cold-boots stock).
ROOTFS=/dev/mmcblk0p9
MARK="Revive360 canary, first modified-firmware flash"
sync
echo 3 > /proc/sys/vm/drop_caches 2>/dev/null && log "page cache dropped"
command -v blockdev >/dev/null 2>&1 && blockdev --flushbufs "$ROOTFS" 2>/dev/null && log "block buffers flushed"
log "searching $ROOTFS for the canary marker..."
hits=$(grep -a -c "$MARK" "$ROOTFS" 2>/dev/null)
log "marker hits on $ROOTFS after dropping caches: ${hits:-0}"
log "info.ini now: $(tr '\n' ' ' < /etc/info.ini 2>/dev/null)"
ls -la /fw/log/ /usr/share/g360r/ >> "$LOG" 2>&1

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
beep 2 04_N_OK.wav
