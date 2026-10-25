#!/bin/sh
# Puts a new controller on the camera over Wi-Fi, through the phone (no SD swap):
# uploads it to the card's g360r/ and restarts g360rd with it (protocol debug
# endpoints PUT /api/v1/debug/upload, POST /api/v1/debug/restart).
#
#   sh camera/tools/wifi_update.sh [path/to/g360rd]     (from the PC, phone on USB)
#
# The phone must be on the camera's Wi-Fi (wlan1 there; its default route is
# the home network, hence --interface).
set -e
# Git Bash on Windows would turn /data/local/tmp into a Windows path
export MSYS_NO_PATHCONV=1
BIN=${1:-camera/out/sd/g360r/bin/g360rd}
ADB=${ADB:-adb}
CAM=${CAM:-http://192.168.107.1:8360}
IF=${IF:-wlan1}

"$ADB" push "$BIN" /data/local/tmp/g360rd.new >/dev/null
# Expect: 100-continue makes curl send the headers first: the server reads the body itself
"$ADB" shell "curl -s -m 60 --interface $IF -H 'Expect: 100-continue' -T /data/local/tmp/g360rd.new \
    '$CAM/api/v1/debug/upload?name=g360rd.new'; echo; \
    curl -s -m 10 --interface $IF -X POST '$CAM/api/v1/debug/restart?replace=g360rd.new'; echo; \
    i=0; while [ \$i -lt 20 ]; do sleep 1; \
        if curl -s -m 2 --interface $IF -o /dev/null '$CAM/api/v1/info'; then echo \"up after \${i}s\"; break; fi; \
        i=\$((i + 1)); done; \
    rm -f /data/local/tmp/g360rd.new"
