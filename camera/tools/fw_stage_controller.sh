#!/bin/sh
# Stage the controller overlay for fw_build.sh: fw/overlay-controller plus the
# built payload (out/sd/g360r: g360rd, launcher.sh, netup.sh) in /usr/share/g360r
# and the shim (out/libg360r_shim.so) in /usr/lib. Run build.sh and make shim first.
# Usage: sh camera/tools/fw_stage_controller.sh <stage dir> [noshim]
# noshim leaves the shim and its di-camera-app drop-in out: the stock app stays
# exactly as Samsung shipped it (first SM-R210 image, docs/firmware/15_r210_port.md).
set -eu

CAMERA=$(cd "$(dirname "$0")/.." && pwd)
STAGE=$1
SHIM=${2:-shim}
SRC=$CAMERA/out/sd/g360r

[ -f "$SRC/bin/g360rd" ] || { echo "no $SRC/bin/g360rd: run camera/build.sh first" >&2; exit 1; }
rm -rf "$STAGE"
mkdir -p "$STAGE"
cp -R "$CAMERA/fw/overlay-controller/." "$STAGE/"
cp "$SRC/bin/g360rd" "$SRC/launcher.sh" "$SRC/netup.sh" "$STAGE/usr/share/g360r/"
if [ "$SHIM" = noshim ]; then
    rm -rf "$STAGE/usr/lib/systemd/system/di-camera-app.service.d"
    grep -v libg360r_shim "$STAGE/MODES" > "$STAGE/MODES.new" && mv "$STAGE/MODES.new" "$STAGE/MODES"
else
    [ -f "$CAMERA/out/libg360r_shim.so" ] || { echo "no out/libg360r_shim.so: make shim first" >&2; exit 1; }
    cp "$CAMERA/out/libg360r_shim.so" "$STAGE/usr/lib/"
fi
[ -f "$CAMERA/out/g360r-ble" ] || { echo "no out/g360r-ble: make ble first" >&2; exit 1; }
cp "$CAMERA/out/g360r-ble" "$STAGE/usr/share/g360r/"
# Which build this firmware is: logged by boot.sh to g360r/log/start.log
echo "g360r $(git -C "$CAMERA" describe --always --dirty 2>/dev/null || echo nogit) built $(date -u '+%Y-%m-%d %H:%M UTC')"     > "$STAGE/usr/share/g360r/VERSION"
# The camera's shell rejects CRLF
for f in "$STAGE/usr/share/g360r/"*.sh "$STAGE/usr/lib/systemd/system/g360r.service" "$STAGE/MODES" "$STAGE/LINKS"; do
    if grep -q "$(printf '\r')" "$f"; then
        echo "CRLF in $f" >&2
        exit 1
    fi
done
echo "staged in $STAGE:"
( cd "$STAGE" && find . -type f | sort )
