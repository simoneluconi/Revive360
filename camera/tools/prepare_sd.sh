#!/bin/sh
# Prepare a microSD card for Revive360 (Linux/macOS/git-bash; run after build).
#
#   sh camera/tools/prepare_sd.sh <card mount dir> [--new-psk] [--ledtest on|off] [--selftest on|off]
#
# Same behaviour as prepare_sd.ps1: trigger files at the root, payload in
# <card>/g360r/, g360r.conf with a random 8-digit passphrase on first use.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
SRC="$HERE/../out/sd"
CARD=$1
[ -n "$CARD" ] && [ -d "$CARD" ] || { echo "usage: $0 <card mount dir> [--new-psk] [--ledtest on|off] [--selftest on|off]" >&2; exit 2; }
shift
NEWPSK=0; LEDTEST=""; SELFTEST=""
while [ $# -gt 0 ]; do
    case "$1" in
        --new-psk) NEWPSK=1 ;;
        --ledtest) LEDTEST=$2; shift ;;
        --selftest) SELFTEST=$2; shift ;;
    esac
    shift
done
[ -f "$SRC/g360r/bin/g360rd" ] || { echo "build output missing: run camera/build.sh first" >&2; exit 1; }

mkdir -p "$CARD/g360r/bin" "$CARD/g360r/log"
cp "$SRC/info.tg" "$SRC/cmd.tg" "$CARD/"
for f in "$SRC"/g360r/*; do [ -f "$f" ] && cp "$f" "$CARD/g360r/"; done
cp "$SRC/g360r/bin/g360rd" "$CARD/g360r/bin/g360rd"

CONF="$CARD/g360r/g360r.conf"
PSK=""
if [ -f "$CONF" ] && [ $NEWPSK = 0 ]; then
    PSK=$(sed -n 's/^[[:space:]]*psk[[:space:]]*=[[:space:]]*//p' "$CONF" | tail -n 1 | tr -d '\r"')
    [ "$PSK" = "CHANGE_ME" ] && PSK=""
fi
if [ -z "$PSK" ] || [ ${#PSK} -lt 8 ]; then
    PSK=$(od -An -N64 -tu1 /dev/urandom | tr -s ' ' '\n' | awk '$1 != "" && $1 < 250 { printf "%d", $1 % 10 }' | cut -c1-8)
    sed "s/^psk=.*/psk=$PSK/" "$SRC/g360r/g360r.conf.example" > "$CONF"
fi
# Older configs may lack a key: append it so the switches below always apply.
for k in ledtest selftest; do grep -q "^$k=" "$CONF" || echo "$k=0" >> "$CONF"; done
case "$LEDTEST" in
    on)  sed -i.bak 's/^ledtest=.*/ledtest=1/' "$CONF" && rm -f "$CONF.bak" ;;
    off) sed -i.bak 's/^ledtest=.*/ledtest=0/' "$CONF" && rm -f "$CONF.bak" ;;
esac
case "$SELFTEST" in
    on)  sed -i.bak "s/^selftest=.*/selftest=1/" "$CONF" && rm -f "$CONF.bak" ;;
    off) sed -i.bak "s/^selftest=.*/selftest=0/" "$CONF" && rm -f "$CONF.bak" ;;
esac
# LF line endings for everything the camera reads as text.
for f in "$CONF" "$CARD/info.tg" "$CARD/cmd.tg" "$CARD"/g360r/*.sh; do
    [ -f "$f" ] && tr -d '\r' < "$f" > "$f.lf" && mv "$f.lf" "$f"
done
sync

echo
echo "SD card $CARD prepared."
echo "  Wi-Fi passphrase : $PSK   (enter it once in the app)"
echo "  SSID             : G360R-XXXXXX (last 6 hex digits of the camera's Wi-Fi MAC)"
echo "  LED diagnostic   : $(grep -q '^ledtest=1' "$CONF" && echo 'ON (about 75 s at boot)' || echo off)"
echo "  API self-test    : $(grep -q "^selftest=1" "$CONF" && echo ON || echo off)"
echo "  Logs after a run : $CARD/g360r/log/"
