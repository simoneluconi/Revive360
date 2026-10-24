#!/bin/sh
# Sim smoke test for g360rd: starts `g360rd serve --sim` on a scratch dir and
# exercises every endpoint of docs/protocol.md with curl.
#
#   sh tools/smoke_test.sh out/g360rd-host [port]
#
# Exit status 0 = all checks passed. Prints a PASS/FAIL line per check.
BIN=${1:-out/g360rd-host}
PORT=${2:-18360}
SIM=$(mktemp -d /tmp/g360r-sim.XXXXXX)
B="http://127.0.0.1:$PORT"
T="$SIM/t"
mkdir -p "$T"
PASS=0
FAIL=0

ok()   { PASS=$((PASS + 1)); echo "PASS  $*"; }
bad()  { FAIL=$((FAIL + 1)); echo "FAIL  $*"; }
# check NAME EXPECTED ACTUAL
check() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1 (expected [$2], got [$3])"; fi; }
# contains NAME FILE STRING
contains() { if grep -q -- "$3" "$2"; then ok "$1"; else bad "$1 (no [$3] in $(head -c 300 "$2"))"; fi; }
# req METHOD PATH [curl args...] -> prints status, body in $T/body, headers in $T/hdr
req() {
    m=$1; p=$2; shift 2
    curl -s --path-as-is -X "$m" -D "$T/hdr" -o "$T/body" -w '%{http_code}' "$@" "$B$p"
}
hdr() { grep -i "^$1:" "$T/hdr" | head -n 1 | cut -d' ' -f2- | tr -d '\r'; }

"$BIN" serve --sim "$SIM" --port "$PORT" --bind 127.0.0.1 --log "$SIM/g360rd.log" --quiet &
SRV=$!
trap 'kill $SRV 2>/dev/null; rm -rf "$SIM"' EXIT
i=0
until curl -s -o /dev/null "$B/api/v1/info" || [ $i -ge 50 ]; do sleep 0.1; i=$((i + 1)); done

echo "== info / status"
check "info 200" 200 "$(req GET /api/v1/info)"
contains "info protocol 1" "$T/body" '"protocol":1'
contains "info adapter sim" "$T/body" '"adapter":"sim"'
contains "info mac" "$T/body" '"mac":"02:00:00:65:ef:1e"'
contains "info name" "$T/body" '"name":"Gear 360 sim (65:EF:1E)"'
contains "info modes" "$T/body" '"modes":\["photo","raw","raw+photo"\]'
contains "info leds unknown" "$T/body" '"id":"led0","label":"LED 0","role":"unknown"'
check "info content-type" "application/json; charset=utf-8" "$(hdr Content-Type)"
check "info connection close" "close" "$(hdr Connection)"
cp "$T/body" "$SIM/info.json"
check "status 200" 200 "$(req GET /api/v1/status)"
contains "status battery" "$T/body" '"percent":87,"charging":false,"status":"discharging","external_power":false,"temperature_c":31.5'
contains "status storage" "$T/body" '"mounted":true'
contains "status busy false" "$T/body" '"busy":false'
echo 100 > "$SIM/sys/class/power_supply/battery/capacity"
echo Charging > "$SIM/sys/class/power_supply/battery/status"
echo 1 > "$SIM/sys/class/power_supply/ac/online"
req GET /api/v1/status >/dev/null
contains "status charging" "$T/body" '"percent":100,"charging":true,"status":"charging","external_power":true'
cp "$T/body" "$SIM/status.json"

echo "== files / thumbnails / downloads"
check "files 200" 200 "$(req GET /api/v1/files)"
contains "files lists seed" "$T/body" '"path":"DCIM/100PHOTO/360_0002.SRW","name":"360_0002.SRW"'
contains "files kind raw" "$T/body" '"kind":"raw"'
check "thumb jpg 200" 200 "$(req GET /api/v1/thumb/DCIM/100PHOTO/360_0001.JPG)"
check "thumb jpg type" "image/jpeg" "$(hdr Content-Type)"
check "thumb cache" "max-age=86400" "$(hdr Cache-Control)"
check "thumb jpg SOI" "ffd8ff" "$(head -c 3 "$T/body" | od -An -tx1 | tr -d ' \n')"
check "thumb srw 200" 200 "$(req GET /api/v1/thumb/DCIM/100PHOTO/360_0002.SRW)"
printf 'not an image' > "$SIM/sdcard/DCIM/100PHOTO/360_0003.MP4"
check "thumb video 404" 404 "$(req GET /api/v1/thumb/DCIM/100PHOTO/360_0003.MP4)"
contains "thumb video no_thumbnail" "$T/body" '"code":"no_thumbnail"'
check "thumb missing 404" 404 "$(req GET /api/v1/thumb/DCIM/100PHOTO/nope.JPG)"
contains "thumb missing not_found" "$T/body" '"code":"not_found"'
# SRW without preview falls back to the sibling JPG's EXIF thumbnail.
printf 'II*\000\010\000\000\000\000\000' > "$SIM/sdcard/DCIM/100PHOTO/360_0001.SRW"
check "thumb srw sibling fallback" 200 "$(req GET /api/v1/thumb/DCIM/100PHOTO/360_0001.SRW)"

F=/files/DCIM/100PHOTO/360_0001.JPG
SIZE=$(stat -c %s "$SIM/sdcard/DCIM/100PHOTO/360_0001.JPG")
check "download 200" 200 "$(req GET $F)"
check "download length" "$SIZE" "$(stat -c %s "$T/body")"
cmp -s "$T/body" "$SIM/sdcard/DCIM/100PHOTO/360_0001.JPG" && ok "download bytes identical" || bad "download bytes differ"
check "download accept-ranges" "bytes" "$(hdr Accept-Ranges)"
[ -n "$(hdr Last-Modified)" ] && ok "download last-modified" || bad "download last-modified"
check "head 200" 200 "$(req HEAD $F -I)"
check "head length" "$SIZE" "$(hdr Content-Length)"
check "range a-b 206" 206 "$(req GET $F -H 'Range: bytes=0-99')"
check "range a-b len" 100 "$(stat -c %s "$T/body")"
check "range content-range" "bytes 0-99/$SIZE" "$(hdr Content-Range)"
check "range a- 206" 206 "$(req GET $F -H 'Range: bytes=1000-')"
check "range a- len" $((SIZE - 1000)) "$(stat -c %s "$T/body")"
check "range 416" 416 "$(req GET $F -H "Range: bytes=$SIZE-")"
check "range 416 content-range" "bytes */$SIZE" "$(hdr Content-Range)"
check "multi-range ignored" 200 "$(req GET $F -H 'Range: bytes=0-1,5-6')"
check "download unicode name" 200 "$(cp "$SIM/sdcard/DCIM/100PHOTO/360_0001.JPG" "$SIM/sdcard/DCIM/100PHOTO/caffè 1.JPG"; req GET '/files/DCIM/100PHOTO/caff%C3%A8%201.JPG')"

echo "== path validation"
for p in '/files/DCIM/../etc/passwd' '/files/etc/passwd' '/files/DCIM//x.JPG' '/files/DCIM/./x.JPG' \
         '/files/DCIM/a%5Cb.JPG' '/files/DCIM/a%00.JPG' '/files/DCIM/%2E%2E/x' '/files/DCIM' '/files/DCIM/' \
         '/api/v1/thumb/DCIM/../x.JPG' '/files/DCIM/bad%zz'; do
    check "bad_path $p" 400 "$(req GET "$p")"
done
contains "bad_path code" "$T/body" '"code":"bad_path"'

echo "== routing / limits"
check "unknown 404" 404 "$(req GET /api/v1/nope)"
contains "unknown not_found" "$T/body" '"code":"not_found"'
check "method 405" 405 "$(req POST /api/v1/info -d '{}')"
check "405 allow" "GET" "$(hdr Allow)"
check "head on api 405" 405 "$(req HEAD /api/v1/status -I)"
check "body 413" 413 "$(req POST /api/v1/led -H 'Content-Type: application/json' --data-binary "$(head -c 5000 /dev/zero | tr '\0' 'x')")"
check "header 413" 413 "$(req GET /api/v1/info -H "X-Big: $(head -c 9000 /dev/zero | tr '\0' 'x')")"
check "chunked 400" 400 "$(req POST /api/v1/led -H 'Transfer-Encoding: chunked' -d '{}')"

echo "== LEDs"
check "led blink 200" 200 "$(req POST /api/v1/led -d '{"id":"led0","state":"blink","on_ms":100,"off_ms":100}')"
contains "led blink body" "$T/body" '{"id":"led0","state":"blink"}'
seen0=0; seen1=0; i=0
while [ $i -lt 20 ]; do
    v=$(cat "$SIM/sys/class/leds/led.0/brightness")
    [ "$v" = 0 ] && seen0=1
    [ "$v" = 1 ] && seen1=1
    sleep 0.03; i=$((i + 1))
done
[ $seen0 = 1 ] && [ $seen1 = 1 ] && ok "led0 toggles in sysfs" || bad "led0 not toggling ($seen0/$seen1)"
check "led on 200" 200 "$(req POST /api/v1/led -d '{"id":"led1","state":"on"}')"
sleep 0.2
check "led1 on in sysfs" 1 "$(cat "$SIM/sys/class/leds/led.1/brightness")"
check "led auto 200" 200 "$(req POST /api/v1/led -d '{"id":"led1","state":"auto"}')"
check "led unknown id 400" 400 "$(req POST /api/v1/led -d '{"id":"led9","state":"on"}')"
check "led bad state 400" 400 "$(req POST /api/v1/led -d '{"id":"led0","state":"disco"}')"
check "led bad timing 400" 400 "$(req POST /api/v1/led -d '{"id":"led0","state":"blink","on_ms":10}')"
check "led bad json 400" 400 "$(req POST /api/v1/led -d '{"id":')"

echo "== capture"
check "capture bad json 400" 400 "$(req POST /api/v1/capture -d 'nope')"
check "capture no mode 400" 400 "$(req POST /api/v1/capture -d '{"quality":"fine"}')"
check "capture bad mode 501" 501 "$(req POST /api/v1/capture -d '{"mode":"video"}')"
check "capture bad quality 501" 501 "$(req POST /api/v1/capture -d '{"mode":"photo","quality":"ultra"}')"
check "capture resolution not settable 501" 501 "$(req POST /api/v1/capture -d '{"mode":"photo","resolution":"3072x1728"}')"
# Photo capture in the background; meanwhile: busy, 409, delete 409, 4 parallel downloads.
curl -s -o "$T/cap1" -w '%{http_code}' -X POST -d '{"mode":"photo","quality":"sfine"}' "$B/api/v1/capture" > "$T/cap1.code" &
CAP=$!
sleep 0.4
req GET /api/v1/status >/dev/null
contains "status busy during capture" "$T/body" '"busy":true'
check "second capture 409" 409 "$(req POST /api/v1/capture -d '{"mode":"photo"}')"
contains "409 code busy" "$T/body" '"code":"busy"'
check "delete during capture 409" 409 "$(req DELETE /files/DCIM/100PHOTO/360_0001.JPG)"
for k in 1 2 3 4; do
    curl -s -o "$T/par$k" -w '%{http_code}' "$B$F" > "$T/par$k.code" &
done
wait $CAP
for k in 1 2 3 4; do :; done
sleep 0.5
check "capture photo 200" 200 "$(cat "$T/cap1.code")"
contains "capture photo file" "$T/cap1" '"path":"DCIM/100PHOTO/360_0004.JPG","name":"360_0004.JPG"'
contains "capture duration" "$T/cap1" '"duration_ms":'
n=0; for k in 1 2 3 4; do [ "$(cat "$T/par$k.code")" = 200 ] && cmp -s "$T/par$k" "$SIM/sdcard/DCIM/100PHOTO/360_0001.JPG" && n=$((n + 1)); done
check "4 parallel downloads during capture" 4 "$n"
check "thumb of captured photo" 200 "$(req GET /api/v1/thumb/DCIM/100PHOTO/360_0004.JPG)"
check "capture raw+photo 200" 200 "$(req POST /api/v1/capture -d '{"mode":"raw+photo","quality":"fine"}' --max-time 45)"
contains "raw+photo SRW" "$T/body" '360_0005.SRW'
contains "raw+photo JPG" "$T/body" '360_0005.JPG'
cp "$T/body" "$SIM/capture.json"
check "capture raw 200" 200 "$(req POST /api/v1/capture -d '{"mode":"raw"}' --max-time 45)"
contains "raw SRW" "$T/body" '"kind":"raw"'
req GET /api/v1/files >/dev/null
check "files newest first" '"path":"DCIM/100PHOTO/360_0006.SRW"' "$(grep -o '"path":"[^"]*"' "$T/body" | head -n 1)"

echo "== delete"
check "delete 200" 200 "$(req DELETE /files/DCIM/100PHOTO/360_0004.JPG)"
contains "delete body" "$T/body" '{"deleted":"DCIM/100PHOTO/360_0004.JPG"}'
check "delete again 404" 404 "$(req DELETE /files/DCIM/100PHOTO/360_0004.JPG)"
check "delete dir 404" 404 "$(req DELETE /files/DCIM/100PHOTO)"

echo "== log"
check "log 200" 200 "$(req GET '/api/v1/log?lines=5')"
check "log 5 lines" 5 "$(wc -l < "$T/body" | tr -d ' ')"
check "log type" "text/plain; charset=utf-8" "$(hdr Content-Type)"
check "log bad lines 400" 400 "$(req GET '/api/v1/log?lines=abc')"

echo "== capture timeout (30 s)"
touch "$SIM/SIM_CAPTURE_TIMEOUT"
check "capture 504" 504 "$(req POST /api/v1/capture -d '{"mode":"photo"}' --max-time 45)"
contains "504 code" "$T/body" '"code":"capture_timeout"'
rm -f "$SIM/SIM_CAPTURE_TIMEOUT"

echo "== no storage"
mv "$SIM/sdcard" "$SIM/sdcard.off"
req GET /api/v1/status >/dev/null
contains "status unmounted" "$T/body" '"mounted":false,"total_bytes":0,"free_bytes":0,"used_bytes":0'
check "files 503" 503 "$(req GET /api/v1/files)"
contains "503 code" "$T/body" '"code":"no_storage"'
check "capture 503" 503 "$(req POST /api/v1/capture -d '{"mode":"photo"}')"
mv "$SIM/sdcard.off" "$SIM/sdcard"

echo
echo "sample info:    $(cat "$SIM/info.json")"
echo "sample status:  $(cat "$SIM/status.json")"
echo "sample capture: $(cat "$SIM/capture.json")"
echo
echo "RESULT: $PASS passed, $FAIL failed"
[ $FAIL -eq 0 ]
