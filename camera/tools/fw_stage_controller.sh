#!/bin/sh
# Stage the controller overlay for fw_build.sh: fw/overlay-controller plus the
# built payload (out/sd/g360r: g360rd, launcher.sh, netup.sh) in /usr/share/g360r.
# Run build.sh first. Usage: sh camera/tools/fw_stage_controller.sh <stage dir>
set -eu

CAMERA=$(cd "$(dirname "$0")/.." && pwd)
STAGE=$1
SRC=$CAMERA/out/sd/g360r

[ -f "$SRC/bin/g360rd" ] || { echo "no $SRC/bin/g360rd: run camera/build.sh first" >&2; exit 1; }
rm -rf "$STAGE"
mkdir -p "$STAGE"
cp -R "$CAMERA/fw/overlay-controller/." "$STAGE/"
cp "$SRC/bin/g360rd" "$SRC/launcher.sh" "$SRC/netup.sh" "$STAGE/usr/share/g360r/"
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
