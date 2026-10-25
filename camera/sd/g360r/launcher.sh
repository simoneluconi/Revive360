#!/bin/sh
# Revive360 - launcher (runs from /tmp/g360r, started by start.sh from the
# factory SD hook, or by boot.sh from the modified firmware with G360R_MODE=normal).
#
# Normal mode: the stock app is alive, pets the watchdog and runs its own power
# policy, so steps 0 and 1 are skipped there.
#
# Sequence (every step is logged with a timestamp to /sdcard/g360r/log/g360r.log):
#   0. disable the hardware watchdog (kept off by the supervisor)
#   1. reset the factory-mode low-battery auto-off (vconf, volatile memory/ key)
#   2. status LED blue via `st app 360 led` (never raw led.N), one beep
#   3. optional LED mapping diagnostic (ledtest=1 in g360r.conf)
#   4. wait for wlan0, bring up our softAP (netup.sh: pluggable strategies)
#   5. start the HTTP server (g360rd serve), two beeps
#   6. supervise: restart the server / DHCP server if they die, keep the vconf
#      key at 0, sync the log.
#
# Safety: never touches di-camera-app; writes only to /tmp, /run and the SD.

SD_DIR=/sdcard/g360r
RUN=/tmp/g360r
BIN="$RUN/g360rd"
CONF="$SD_DIR/g360r.conf"
LOGDIR="$SD_DIR/log"
LOG="$LOGDIR/g360r.log"
export PATH=/usr/sbin:/sbin:/usr/bin:/bin

SND_DIR=/usr/apps/com.samsung.di-camera-app/res/sounds/camera
WAV_OK="$SND_DIR/04_N_OK.wav"
WAV_ERR="$SND_DIR/05_N_Cancel.wav"
VCONF_KEY=memory/sysman/battery_skip_low_batt_power_off
WDT=/sys/devices/platform/jack/set_watchdog
MODE=${G360R_MODE:-factory}

# Hardware watchdog off before anything else (start.sh already did it; this
# covers an older start.sh on the card). See watchdog_off below.
[ "$MODE" = factory ] && [ -e "$WDT" ] && echo 0 > "$WDT" 2>/dev/null

mkdir -p "$RUN" "$LOGDIR" 2>/dev/null
echo $$ > "$RUN/launcher.pid"

# Keep the previous boot's log, start a fresh one.
# rm first: the camera's exFAT driver leaves two entries with the same name when
# mv replaces an existing file
if [ -f "$LOG" ]; then
    rm -f "$LOGDIR/g360r.prev.log"
    mv -f "$LOG" "$LOGDIR/g360r.prev.log" 2>/dev/null
fi

# ------------------------------------------------------------ helpers

log() {
    echo "$(date '+%Y-%m-%d %H:%M:%S') [up $(cut -d' ' -f1 /proc/uptime)] L $*" >> "$LOG" 2>/dev/null
    # Synced line by line: Test 1 ended in a reset loop and the unsynced tail was lost
    sync
}

# Run a command, log its output (indented). Never fails the script.
logrun() {
    log "\$ $*"
    "$@" >> "$RUN/cmd.out" 2>&1
    rc=$?
    sed 's/^/    /' "$RUN/cmd.out" >> "$LOG" 2>/dev/null
    rm -f "$RUN/cmd.out"
    [ $rc -ne 0 ] && log "  (exit $rc)"
    return $rc
}

milestone() {
    log "=== $* ==="
    echo "$*" > "$LOGDIR/state.txt" 2>/dev/null
    sync
}

# cfg KEY DEFAULT: last KEY=VALUE in g360r.conf (CR, quotes, spaces stripped).
cfg() {
    v=$(sed -n "s/^[[:space:]]*$1[[:space:]]*=//p" "$CONF" 2>/dev/null | tail -n 1 | tr -d '\r')
    v=$(echo "$v" | sed 's/^[[:space:]]*//; s/[[:space:]]*$//; s/^"\(.*\)"$/\1/; s/^'"'"'\(.*\)'"'"'$/\1/')
    if [ -n "$v" ]; then echo "$v"; else echo "$2"; fi
}

# The Wi-Fi passphrase: the camera makes one when the card has none (the phone
# gets it over Bluetooth while pairing is open, nobody types it)
ensure_psk() {
    p=$(cfg psk "")
    if [ ${#p} -ge 8 ] && [ "$p" != CHANGE_ME ]; then
        return 0
    fi
    p=$(tr -dc 'A-Za-z0-9' < /dev/urandom 2>/dev/null | dd bs=16 count=1 2>/dev/null)
    if [ ${#p} -lt 16 ]; then
        log "WARNING: could not make a Wi-Fi passphrase"
        return 1
    fi
    if grep -q '^[[:space:]]*psk[[:space:]]*=' "$CONF" 2>/dev/null; then
        sed "s/^[[:space:]]*psk[[:space:]]*=.*/psk=$p/" "$CONF" > "$CONF.new"
    else
        { cat "$CONF" 2>/dev/null; echo "psk=$p"; } > "$CONF.new"
    fi
    # rm first: the card's exFAT keeps both entries when mv replaces a file
    rm -f "$CONF" && mv "$CONF.new" "$CONF" && sync
    log "new Wi-Fi passphrase made (16 characters)"
}

beep() {
    n=$1; wav=$2; i=0
    [ -x /usr/bin/paplay ] && [ -e "$wav" ] || return 0
    while [ $i -lt "$n" ]; do
        paplay "$wav" >/dev/null 2>&1
        i=$((i + 1))
        sleep 0.3
    done
}

# Hardware watchdog (/sys/devices/platform/jack/set_watchdog, written by
# di-camera-app: "echo %d" arms it with a timeout in seconds, 0 disables). The
# stock app pets it; factory mode does not, so it fired ~30 s in and reset the
# SoC in a loop (Test 1). Kept at 0 for the whole session: re-written on every
# supervisor tick in case di-camera-app re-arms it.
watchdog_off() {
    [ "$MODE" = factory ] || return 0
    [ -e "$WDT" ] || return 1
    echo 0 > "$WDT" 2>/dev/null
}

# Shell-side LED indication through di-camera-app's factory command
#   st app 360 led <status|front|rear> <r|g|b> <on|off>
# which maps logical LEDs to the right GPIOs itself. We never write the raw
# /sys/class/leds/led.N here: they are not identified yet and one may be a GPIO
# that is not an LED. Each st call runs in the background and is killed after
# 5 s, so a stuck message queue cannot block the launcher.
st_bg() {
    [ -x /usr/bin/st ] || return 0
    ( /usr/bin/st "$@" >/dev/null 2>&1 &
      p=$!; sleep 5; kill "$p" 2>/dev/null ) &
}

# status_led starting|ready|error: blue / green / red on the status LED.
# Factory mode only: in normal mode the LEDs are the stock app's (beeps only).
status_led() {
    [ "$MODE" = factory ] || return 0
    case "$1" in
        starting) on=b ;;
        ready)    on=g ;;
        *)        on=r ;;
    esac
    log "status LED: $1 (st app 360 led status $on on)"
    for c in r g b; do
        [ "$c" = "$on" ] || st_bg app 360 led status "$c" off
    done
    st_bg app 360 led status "$on" on
}

# Firmware signature on the LEDs, normal mode only: led.0-2 = charge LED
# red/green/blue, led.3 = lens LED on the display side, led.4 = the other lens
# LED (mapped on the hardware). Run here, with the stock app up: at boot (the
# firmware's g360r-leds.service, ~8 s) the LEDs light nothing yet. Each LED's
# trigger and brightness are read first and put back after, so the stock state
# (lens LEDs red when ready) is not overwritten.
led_signature() {
    [ "$MODE" = normal ] || return 0
    L=/sys/class/leds
    saved=""
    for n in 0 1 2 3 4; do
        d=$L/led.$n
        [ -w "$d/brightness" ] || continue
        t=$(sed -n 's/.*\[\([^]]*\)\].*/\1/p' "$d/trigger" 2>/dev/null)
        b=$(cat "$d/brightness" 2>/dev/null)
        saved="$saved $n:${t:-none}:${b:-0}"
        echo none > "$d/trigger" 2>/dev/null
        echo 0 > "$d/brightness" 2>/dev/null
    done
    log "LED signature (saved state:$saved)"
    # Around the camera: charge LED blue, one lens, the other lens; then all twice
    for n in 2 3 4 2 3 4; do
        echo 255 > "$L/led.$n/brightness" 2>/dev/null; sleep 0.12
        echo 0 > "$L/led.$n/brightness" 2>/dev/null
    done
    for flash in 1 2; do
        for n in 1 3 4; do echo 255 > "$L/led.$n/brightness" 2>/dev/null; done
        sleep 0.15
        for n in 1 3 4; do echo 0 > "$L/led.$n/brightness" 2>/dev/null; done
        sleep 0.1
    done
    for e in $saved; do
        n=${e%%:*}; r=${e#*:}; t=${r%%:*}; b=${r#*:}
        echo "$t" > "$L/led.$n/trigger" 2>/dev/null
        [ "$t" = none ] && echo "$b" > "$L/led.$n/brightness" 2>/dev/null
    done
    # No text on the status display: the app repaints it only on attribute
    # changes, through st, which it ignores in normal mode (g360rd oledtext stays
    # for later)
}

# st_log ARGS: one st command, killed after 5 s; logs what the app answered in
# /tmp/app_result (where st getters print, e.g. eUS_IQ_FINE_RAW)
st_log() {
    rm -f /tmp/app_result
    /usr/bin/st "$@" >"$RUN/st.out" 2>&1 &
    p=$!
    n=0
    while kill -0 "$p" 2>/dev/null && [ $n -lt 50 ]; do sleep 0.1; n=$((n + 1)); done
    kill "$p" 2>/dev/null
    log "st $*: out=[$(tr '\n' ' ' < "$RUN/st.out" 2>/dev/null)] app_result=[$(tr '\n' ' ' < /tmp/app_result 2>/dev/null)]"
}

# RAW diagnostic (normal mode, rawprobe=1 in g360r.conf): does the quality menu
# (0xd0) accept a raw value? Set raw+fine, read it back two ways and from dlog,
# then put sfine back. A *_RAW read-back = not gated: then shoot RAW from the app
# and look for a .SRW. See docs/firmware/08_control_protocol.md.
raw_probe() {
    [ "$MODE" = normal ] && [ "$(cfg rawprobe 0)" = "1" ] || return 0
    milestone "RAW probe"
    st_log app nx capture quality
    st_log app nx capture quality raw+fine
    st_log app nx capture quality
    st_log app nx menu capture
    if command -v dlogutil >/dev/null 2>&1; then
        log "dlog (quality lines):"
        dlogutil -d 2>/dev/null | grep -iE "qual|IQ_|raw" | tail -n 20 | sed 's/^/    /' >> "$LOG"
    fi
    st_log app nx capture quality sfine
    st_log app nx capture quality
    milestone "RAW probe done"
}

# prefs_dump (normal mode, prefsdump=1): copy the camera's settings files to the
# card at every boot, to compare with the ones saved in factory mode during the
# RAW investigation (where is the photo quality kept, does a setting survive the
# power-off?)
prefs_dump() {
    [ "$MODE" = normal ] && [ "$(cfg prefsdump 0)" = "1" ] || return 0
    d=$LOGDIR/prefs_boot
    rm -rf "$d"
    mkdir -p "$d"
    find /opt -xdev -type f \( -iname '*pref*' -o -iname '*user_spec*' -o -iname '*setting*' \) -size -2048k 2>/dev/null |
    while read -r f; do
        md5sum "$f" >> "$d/md5.txt" 2>/dev/null
        cp "$f" "$d/$(echo "$f" | tr '/' '_')" 2>/dev/null
    done
    log "prefs dump: $(wc -l < "$d/md5.txt" 2>/dev/null) files in $d"
}

vconf_reset() {
    [ "$MODE" = factory ] || return 0
    cur=$(vconftool get "$VCONF_KEY" 2>&1)
    case "$cur" in
        *"value = 0"*) return 0 ;;
    esac
    log "vconf: $cur -> setting 0"
    logrun vconftool set -t int "$VCONF_KEY" 0 -f
    log "vconf now: $(vconftool get "$VCONF_KEY" 2>&1)"
}

# ------------------------------------------------------------ start

milestone "launcher start (pid $$, $MODE mode)"
if [ "$MODE" != factory ]; then
    log "watchdog: left to the stock app ($MODE mode)"
elif watchdog_off; then
    log "watchdog: $WDT set to 0 (reads: $(cat "$WDT" 2>&1))"
else
    log "WARNING: watchdog control $WDT missing - expect a reset ~30 s after boot"
fi
log "g360rd: $("$BIN" version 2>&1)"
log "kernel: $(uname -a)"
log "info.ini: $(tr '\n' ' ' < /etc/info.ini 2>/dev/null)"
if [ ! -f "$CONF" ]; then
    log "WARNING: $CONF missing - run the SD preparation script (no passphrase = no softAP)"
fi
ensure_psk
log "config (psk hidden):"
sed 's/^\([[:space:]]*psk[[:space:]]*=\).*/\1********/' "$CONF" 2>/dev/null | grep -v '^[[:space:]]*#' | grep -v '^[[:space:]]*$' | sed 's/^/    /' >> "$LOG"
log "cgroup: $(tr '\n' ' ' < /proc/self/cgroup)"

vconf_reset
status_led starting
led_signature
prefs_dump
raw_probe
# Normal mode: no start beep (the LED signature says it), one beep when ready
[ "$MODE" = factory ] && beep 1 "$WAV_OK"

logrun ls -la /sys/class/leds/
logrun ls /sys/class/power_supply/
for d in /sys/class/power_supply/*; do
    log "power_supply ${d##*/}: $(cd "$d" 2>/dev/null && for f in type status capacity online present temp batt_temp voltage_now health; do [ -f "$f" ] && printf '%s=%s ' "$f" "$(cat "$f" 2>/dev/null)"; done)"
done
logrun cat /proc/mounts
logrun "$BIN" detect --conf "$CONF"
sync

# ------------------------------------------------------------ LED diagnostic

if [ "$(cfg ledtest 0)" = "1" ]; then
    milestone "LED diagnostic (ledtest=1)"
    "$BIN" ledtest --log "$LOG" --beep "$WAV_OK" >/dev/null 2>&1
    milestone "LED diagnostic done"
    status_led starting
fi

# ------------------------------------------------------------ network

# In normal mode the stock stack may keep Wi-Fi off (no wlan0): netup loads the
# driver itself, so a short wait is enough there
milestone "waiting for wlan0"
wlan_wait=60
[ "$MODE" = factory ] || wlan_wait=5
i=0
while [ ! -d /sys/class/net/wlan0 ] && [ $i -lt $wlan_wait ]; do sleep 1; i=$((i + 1)); done
log "wlan0 present after ${i}s: $([ -d /sys/class/net/wlan0 ] && echo yes || echo NO)"

# The softAP bring-up lives in netup.sh (not part of this delivery yet). It
# must define netup() (returns 0 and sets AP_SSID AP_IF AP_IP AP_STRATEGY) and
# dhcp_check() (called every 5 s by the supervisor).
# wifi=on: the access point comes up now and stays. wifi=ble: it stays off until
# a phone asks over Bluetooth (g360r-ble writes $RUN/wifi.want), and goes off
# again after wifi_idle seconds with no request over it. The supervisor below
# applies what is wanted and reports $RUN/wifi.state (off|starting|on|failed).
NET_ERROR="--net-error"
WIFI_MODE=$(cfg wifi ble)
WIFI_IDLE=$(cfg wifi_idle 600)
wifi_state() { echo "$1" > "$RUN/wifi.state"; }
wifi_up() {
    wifi_state starting
    if netup; then
        NET_ERROR=""
        wifi_state on
        cut -d. -f1 /proc/uptime > "$RUN/wifi.activity"
        milestone "softAP up: ssid=$AP_SSID if=$AP_IF ip=$AP_IP strategy=$AP_STRATEGY"
        return 0
    fi
    wifi_state failed
    milestone "softAP FAILED"
    return 1
}
wifi_down() {
    _netup_teardown
    logrun /usr/bin/wlan.sh stop
    wifi_state off
    milestone "softAP off"
}
if [ -f "$RUN/netup.sh" ]; then
    . "$RUN/netup.sh"
    # The network's name, for the Bluetooth side to hand over while pairing
    _netup_ssid > "$RUN/wifi.ssid" 2>/dev/null
    if [ "$WIFI_MODE" = on ]; then
        echo on > "$RUN/wifi.want"
        wifi_up || milestone "starting the server anyway for the log"
    else
        echo off > "$RUN/wifi.want"
        wifi_state off
        NET_ERROR=""
        milestone "Wi-Fi on demand (wifi=ble): off until a phone asks over Bluetooth"
    fi
else
    milestone "no netup.sh: network bring-up not installed; server starts in error state"
fi
command -v dhcp_check >/dev/null 2>&1 || dhcp_check() { :; }

# ------------------------------------------------------------ Bluetooth

# g360r-ble: advertises the camera and negotiates the Wi-Fi (protocol section 6).
# From the card, else the firmware's copy; restarted if it dies.
BLE_BIN="$RUN/g360r-ble"
for b in "$SD_DIR/bin/g360r-ble" "$SD_DIR/g360r-ble" /usr/share/g360r/g360r-ble; do
    if [ -f "$b" ]; then
        cp "$b" "$BLE_BIN" && chmod 0755 "$BLE_BIN"
        break
    fi
done
start_ble() {
    [ -x "$BLE_BIN" ] || return 1
    rm -f "$RUN/ble.state"
    setsid "$BLE_BIN" --conf "$CONF" --dir "$RUN" --log "$LOGDIR/ble.log" </dev/null >/dev/null 2>&1 &
    BLE_PID=$!
    log "bluetooth helper started (pid $BLE_PID)"
}
BLE_PID=""
start_ble || log "no g360r-ble: no Bluetooth side"
BLE_STARTED=$(cut -d. -f1 /proc/uptime)

# ------------------------------------------------------------ server

PORT=$(cfg port 8360)
start_server() {
    "$BIN" serve --conf "$CONF" --log "$LOG" --port "$PORT" --bind 0.0.0.0 --quiet $NET_ERROR \
        </dev/null >/dev/null 2>>"$LOGDIR/g360rd.stderr" &
    SERVER_PID=$!
    echo "$SERVER_PID" > "$RUN/g360rd.pid"
    log "server started (pid $SERVER_PID, port $PORT)"
}
start_server
sleep 2
if kill -0 "$SERVER_PID" 2>/dev/null; then
    if [ -z "$NET_ERROR" ]; then
        milestone "READY: join Wi-Fi '$AP_SSID' and open http://$AP_IP:$PORT/api/v1/info"
        status_led ready
        if [ "$MODE" = factory ]; then beep 2 "$WAV_OK"; else beep 1 "$WAV_OK"; fi
    else
        status_led error
        beep 3 "$WAV_ERR"
    fi
else
    milestone "server died immediately (see g360rd.stderr)"
    status_led error
    beep 3 "$WAV_ERR"
fi

# ------------------------------------------------------------ self-test

# http_local METHOD PATH BODY OUTFILE: one request to our own server over
# loopback using bash's /dev/tcp (no curl/nc on the camera). The server always
# closes the connection after the response, so cat terminates.
http_local() {
    exec 3<>"/dev/tcp/127.0.0.1/$PORT" || return 1
    printf '%s %s HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: %d\r\nConnection: close\r\n\r\n%s' \
        "$1" "$2" "${#3}" "$3" >&3
    cat <&3 > "$4"
    exec 3<&- 3>&-
    log "selftest: $1 $2 -> $(head -n 1 "$4" | tr -d '\r')"
}

# selftest=1: exercise the API on the camera itself (works without the softAP)
# and save every response under log/selftest/. Takes up to ~2 minutes.
if [ "$(cfg selftest 0)" = "1" ] && kill -0 "$SERVER_PID" 2>/dev/null; then
    ST_DIR="$LOGDIR/selftest"
    mkdir -p "$ST_DIR"
    milestone "self-test start"
    http_local GET /api/v1/info "" "$ST_DIR/01_info.txt"
    http_local GET /api/v1/status "" "$ST_DIR/02_status.txt"
    http_local GET /api/v1/files "" "$ST_DIR/03_files_before.txt"
    http_local POST /api/v1/capture '{"mode":"photo","quality":"sfine"}' "$ST_DIR/04_capture_photo.txt"
    sleep 3
    http_local POST /api/v1/capture '{"mode":"raw+photo","quality":"sfine"}' "$ST_DIR/05_capture_rawphoto.txt"
    sleep 3
    http_local POST /api/v1/capture '{"mode":"raw"}' "$ST_DIR/06_capture_raw.txt"
    http_local GET /api/v1/files "" "$ST_DIR/07_files_after.txt"
    http_local GET /api/v1/status "" "$ST_DIR/08_status.txt"
    # Thumbnail of the newest file of each kind, saved raw for inspection.
    for ext in JPG SRW; do
        p=$(grep -o "\"path\":\"[^\"]*\.$ext\"" "$ST_DIR/07_files_after.txt" | head -n 1 | sed 's/"path":"\(.*\)"/\1/')
        [ -n "$p" ] && http_local GET "/api/v1/thumb/$p" "" "$ST_DIR/09_thumb_$ext.http"
    done
    milestone "self-test done (see log/selftest/)"
    beep 1 "$WAV_OK"
fi

# ------------------------------------------------------------ supervise

restarts=0
ble_restarts=0
tick=0
while :; do
    sleep 1
    tick=$((tick + 1))
    # What a phone asked over Bluetooth
    want=$(cat "$RUN/wifi.want" 2>/dev/null)
    have=$(cat "$RUN/wifi.state" 2>/dev/null)
    if [ "$want" = on ] && [ "$have" != on ] && [ "$have" != starting ] &&        { [ "$have" != failed ] || [ $((tick % 30)) -eq 0 ]; }; then
        wifi_up
    elif [ "$want" = off ] && [ "$have" = on ]; then
        wifi_down
    fi
    if [ $((tick % 5)) -ne 0 ]; then
        continue
    fi
    # Without the Bluetooth side nobody could switch the Wi-Fi on: then it stays on
    if [ "$WIFI_MODE" != on ] && [ "$(cat "$RUN/wifi.want" 2>/dev/null)" != on ]; then
        up=$(cut -d. -f1 /proc/uptime)
        if [ "$(cat "$RUN/ble.state" 2>/dev/null)" = failed ] || \
           { [ "$(cat "$RUN/ble.state" 2>/dev/null)" != ready ] && [ $((up - BLE_STARTED)) -gt 60 ]; }; then
            log "the Bluetooth side is not working: Wi-Fi on for good"
            WIFI_MODE=on
            echo on > "$RUN/wifi.want"
        fi
    fi
    # wifi=ble: off again after wifi_idle s with no request over it
    if [ "$WIFI_MODE" != on ] && [ "$(cat "$RUN/wifi.state" 2>/dev/null)" = on ]; then
        last=$(cat "$RUN/wifi.activity" 2>/dev/null || echo 0)
        # Seconds since boot on both sides: setting the clock must not count as idle time
        if [ $(( $(cut -d. -f1 /proc/uptime) - last )) -gt "$WIFI_IDLE" ]; then
            log "Wi-Fi unused for ${WIFI_IDLE}s: off"
            echo off > "$RUN/wifi.want"
        fi
    fi
    if [ -n "$BLE_PID" ] && ! kill -0 "$BLE_PID" 2>/dev/null; then
        ble_restarts=$((ble_restarts + 1))
        if [ $ble_restarts -le 10 ]; then
            log "bluetooth helper died, restarting"
            start_ble
        fi
    fi
    watchdog_off
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then
        wait "$SERVER_PID" 2>/dev/null
        log "server (pid $SERVER_PID) died with status $?"
        restarts=$((restarts + 1))
        if [ $restarts -le 20 ]; then
            sleep $((restarts > 5 ? 10 : 1))
            start_server
        elif [ $restarts -eq 21 ]; then
            milestone "server keeps dying; giving up restarts"
        fi
    fi
    if [ -z "$NET_ERROR" ] && [ "$(cat "$RUN/wifi.state" 2>/dev/null)" = on ]; then
        dhcp_check
    fi
    # Every minute: keep low-battery auto-off enabled, flush the log.
    if [ $((tick % 60)) -eq 0 ]; then
        vconf_reset
        sync
    fi
done
