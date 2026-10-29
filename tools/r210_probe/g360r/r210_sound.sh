#!/bin/sh
# Revive360 - SM-R210 sound test, run once by the stock SD factory hook
# (info.tg -> sound.tg). sound.tg first asks dfmsd to play its "low_battery"
# sound through mm-sound (the camera app's own player), then runs this script,
# which logs the audio state and tries two more players, each with a different
# sound so they can be told apart by ear:
#   1. dfmsd "sound play low_battery"  (13_N_NoBattery.wav)  from sound.tg
#   2. paplay 10_N_Shutter_30f_1sec.wav                       ~5 s later
#   3. aplay  06_N_SButton.wav                                ~5 s after that
# Read-only for the camera; logs to /sdcard/g360r/log/r210_sound.log.

LOG=/sdcard/g360r/log/r210_sound.log
export PATH=/usr/sbin:/sbin:/usr/bin:/bin

if [ "$1" != "__detached" ]; then
    setsid /bin/sh "$0" __detached </dev/null >/dev/null 2>&1 &
    exit 0
fi

mkdir -p /sdcard/g360r/log
rm -f /sdcard/info.tg.done
mv /sdcard/info.tg /sdcard/info.tg.done

SND=/usr/apps/com.samsung.di-camera-app/res/sounds/camera
section() { echo; echo "===== $*"; }
{
    echo "r210_sound $(date '+%Y-%m-%d %H:%M:%S') uptime $(cut -d' ' -f1 /proc/uptime)"
    section "pactl info";        pactl info 2>&1
    section "pactl list sinks";  pactl list sinks 2>&1
    section "pactl list cards";  pactl list cards 2>&1 | head -n 120
    section "amixer scontents";  amixer scontents 2>&1 | head -n 300
    section "aplay -l";          aplay -l 2>&1

    sleep 5
    section "paplay 10_N_Shutter_30f_1sec.wav"
    paplay "$SND/10_N_Shutter_30f_1sec.wav" 2>&1; echo "exit $?"
    sleep 5
    section "aplay 06_N_SButton.wav"
    aplay "$SND/06_N_SButton.wav" 2>&1; echo "exit $?"
    section "pactl list sinks (after)"; pactl list sinks 2>&1 | grep -iE "name:|state:|mute:|volume:"
    echo "=== done"
} >> "$LOG" 2>&1
sync
