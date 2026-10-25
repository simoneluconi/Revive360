#!/bin/bash
# Revive360 installer: one command per step. Each one does all the work on the
# PC and the SD card, then says what to do on the camera. Run from the repository root,
# in Git Bash on Windows or a shell on Linux, with Docker running.
#
#   ./install.sh build              build everything (once, and after an update)
#   ./install.sh flash    [card]    card for the firmware update
#   ./install.sh activate [card]    card for the one-time activation after the update
#   ./install.sh sd       [card]    card for everyday use (prints the Wi-Fi passphrase)
#   ./install.sh stock    [card]    card to go back to Samsung's firmware
#   ./install.sh status   [card]    what is on the card now
#
# [card] is a drive letter (E:) or a mount point. Left out, the only removable card
# connected is used. Nothing on the card is ever formatted or deleted: the camera's
# photos stay, files of ours that are no longer wanted are renamed.
set -euo pipefail
cd "$(dirname "$0")"

STOCK=firmware/stock/C200GLU0AQK1_171121_1257_REV00_user.bin
IMAGE=firmware/out/smc200.bin
SYSROOT=camera/.toolchain/aqk1/sysroot
SD_SRC=camera/out/sd
PLUGIN=camera/out/libg360r_plugin.so
export MSYS_NO_PATHCONV=1

say() { printf '\n%s\n' "$*"; }
die() { printf '\nERROR: %s\n' "$*" >&2; exit 1; }
step() { printf '  %s\n' "$@"; }

# ---------------------------------------------------------------- the card

# The card's directory from a drive letter or a mount point, or the one removable card.
card_dir() {
    local arg=${1:-}
    if [ -z "$arg" ]; then
        if command -v powershell >/dev/null 2>&1; then
            local drives
            drives=$(powershell -NoProfile -Command \
                "Get-Volume | Where-Object { \$_.DriveType -eq 'Removable' -and \$_.DriveLetter -and \$_.Size -gt 0 } | ForEach-Object { \"\$(\$_.DriveLetter):\" }" | tr -d '\r')
            case $(printf '%s\n' "$drives" | grep -c .) in
                1) arg=$drives ;;
                0) die "no SD card found: put the card in the PC (or name it: ./install.sh $CMD E:)" ;;
                *) die "more than one removable drive ($(echo $drives)): name the card, e.g. ./install.sh $CMD E:" ;;
            esac
        else
            die "name the card's mount point, e.g. ./install.sh $CMD /media/$USER/CARD"
        fi
    fi
    case $arg in
        [A-Za-z]:|[A-Za-z]:/|[A-Za-z]:\\) arg=$(cygpath -u "${arg:0:2}/") ;;
    esac
    [ -d "$arg" ] || die "$arg is not a mounted card"
    [ -w "$arg" ] || die "$arg is not writable"
    echo "${arg%/}"
}

# Moves a file aside (name.off, or name.N.off when taken): never deletes anything.
put_aside() {
    local f=$1 to="$1.off" n=1
    [ -e "$f" ] || return 0
    while [ -e "$to" ]; do to="$1.$n.off"; n=$((n + 1)); done
    mv "$f" "$to"
    step "moved aside: ${f##*/} -> ${to##*/}"
}

# The factory SD hook's trigger files: only the activation step may have them.
disarm() {
    put_aside "$1/info.tg"
    put_aside "$1/cmd.tg"
}

# Text the camera reads: LF line endings, no BOM.
lf() { tr -d '\r' < "$1" > "$1.lf" && mv "$1.lf" "$1"; }

sha() { sha256sum "$1" | cut -d' ' -f1; }

copy_checked() {
    cp "$1" "$2"
    sync
    [ "$(sha "$1")" = "$(sha "$2")" ] || die "the copy on the card differs from $1: try another card or reader"
    step "copied ${1##*/} -> card/${2##*/} (checked)"
}

# The camera's model and firmware version, from the newest photo on the card (its Exif
# Model and Software fields: SM-C200 / 0.85 is C200GLU0AQK1). Empty when there is none.
camera_of() {
    local jpg head
    jpg=$(ls -t "$1"/DCIM/*/*.JPG "$1"/DCIM/*/*.jpg 2>/dev/null | head -n 1 || true)
    [ -n "$jpg" ] || return 0
    head=$(head -c 4096 "$jpg" | tr -c '[:print:]' '\n')
    printf '%s %s\n' "$(printf '%s\n' "$head" | grep -E -x 'SM-[CR][0-9]+' | head -n 1)" \
        "$(printf '%s\n' "$head" | grep -E -x '[0-9]\.[0-9]{2}' | head -n 1)"
}

# Stops for another camera model, warns for another firmware version or no photo to tell.
check_camera() {
    local found model version
    found=$(camera_of "$1")
    model=${found%% *}
    version=${found#* }
    if [ -z "$model" ]; then
        say "Could not check the camera: no photo of it on the card. Take one photo with the"
        step "camera (with this card in), then run this again to be sure it is a supported one."
        return 0
    fi
    [ "$model" = "SM-C200" ] || die "the photos on this card come from a $model: only the SM-C200 (Gear 360 2016) is supported"
    if [ "$version" = "0.85" ]; then
        step "camera: SM-C200 with firmware C200GLU0AQK1 (0.85), from its photos"
    else
        say "Note: the photos come from an SM-C200 with firmware version ${version:-unknown}, not 0.85"
        step "(C200GLU0AQK1). Our firmware is a complete C200GLU0AQK1 image, so installing it also"
        step "updates the camera to that version, as Samsung's own update did."
    fi
}

flash_instructions() {
    say "On the camera ($1):"
    step "1. Battery charged to near full, USB cable UNPLUGGED."
    step "2. Card in the camera, camera OFF."
    step "3. Hold the Shutter (OK) button and press the power button."
    step "4. \"FW update\" appears: press OK. From now on do not press power: it would cancel."
    step "5. Wait: \"Check for updates\", a progress bar, \"Updated\", then it restarts by itself."
    step "   The 3 red LEDs during the update are normal. Keep the card in until it has restarted."
}

# ---------------------------------------------------------------- steps

cmd_build() {
    docker info >/dev/null 2>&1 || die "Docker is not running: start Docker Desktop and try again"
    [ -f "$STOCK" ] || die "no $STOCK: download Samsung's firmware (see firmware/README.md) and put it in firmware/stock/"
    if ! docker image inspect g360r-fwtools:1 >/dev/null 2>&1; then
        say "== firmware tools (first time only)"
        docker build -t g360r-fwtools:1 -f camera/docker/fwtools.Dockerfile camera/docker
    fi
    if [ ! -e "$SYSROOT/lib/libc.so.6" ]; then
        say "== the camera's libraries, from the stock firmware (first time only)"
        docker run --rm -v "$PWD:/w" -w /w g360r-fwtools:1 sh camera/tools/make_sysroot.sh "$STOCK" "$SYSROOT"
    fi
    say "== camera controller, with its tests"
    sh camera/build.sh smoke
    say "== plugin"
    sh camera/build.sh plugin
    say "== firmware"
    sh camera/tools/make_firmware.sh "$STOCK"
    say "Built. Next: put the SD card in the PC and run ./install.sh flash"
}

cmd_flash() {
    local card
    card=$(card_dir "${1:-}")
    [ -f "$IMAGE" ] || die "no $IMAGE yet: run ./install.sh build first"
    say "Preparing $card for the firmware update"
    check_camera "$card"
    disarm "$card"
    put_aside "$card/smc200.bin.flashed"
    copy_checked "$IMAGE" "$card/smc200.bin"
    : > "$card/fwupgrade.txt"
    step "created fwupgrade.txt"
    sync
    flash_instructions "takes a few minutes"
    say "Then: camera off, card back in the PC, and run ./install.sh activate"
}

cmd_activate() {
    local card
    card=$(card_dir "${1:-}")
    say "Preparing $card for the activation"
    if [ -e "$card/smc200.bin" ]; then
        put_aside "$card/smc200.bin.flashed"
        mv "$card/smc200.bin" "$card/smc200.bin.flashed"
        step "smc200.bin -> smc200.bin.flashed"
    fi
    if [ -e "$card/fwupgrade.txt" ]; then
        mv -f "$card/fwupgrade.txt" "$card/fwupgrade.txt.done"
        step "fwupgrade.txt -> fwupgrade.txt.done"
    fi
    mkdir -p "$card/g360r/p8_hook" "$card/g360r/log"
    cp camera/fw/p8_invalidate.sh "$card/g360r/p8_hook/p8_invalidate.sh"
    lf "$card/g360r/p8_hook/p8_invalidate.sh"
    disarm "$card"
    printf 'cmd.tg\n' > "$card/info.tg"
    printf 'shell script /sdcard/g360r/p8_hook/p8_invalidate.sh\n' > "$card/cmd.tg"
    sync
    step "activation script and its trigger files written"
    say "On the camera (about a minute):"
    step "1. Card in, power on normally. A factory screen with codes and the battery level"
    step "   appears (e.g. QK1 38%): that is expected. Do NOT press any button while it is shown."
    step "2. 1 beep = started, 2 beeps = done. 3 beeps = error: power off and run ./install.sh status"
    step "3. After the 2 beeps: power off, then on again. This first start takes about 30 s with"
    step "   nothing on the screen: wait. From now on every start takes that long."
    say "Then: camera off, card back in the PC, and run ./install.sh sd"
}

cmd_sd() {
    local card
    card=$(card_dir "${1:-}")
    [ -f "$SD_SRC/g360r/bin/g360rd" ] || die "no $SD_SRC yet: run ./install.sh build first"
    [ -f "$PLUGIN" ] || die "no $PLUGIN yet: run ./install.sh build first"
    say "Preparing $card for everyday use"
    disarm "$card"
    mkdir -p "$card/g360r/bin" "$card/g360r/log"
    local f
    for f in "$SD_SRC"/g360r/*; do [ -f "$f" ] && cp "$f" "$card/g360r/"; done
    cp "$SD_SRC/g360r/bin/g360rd" "$card/g360r/bin/g360rd"
    cp "$PLUGIN" "$card/g360r/libg360r_plugin.so"
    step "controller and plugin copied to card/g360r/"
    local conf="$card/g360r/g360r.conf" psk=""
    if [ -f "$conf" ]; then
        psk=$(sed -n 's/^[[:space:]]*psk[[:space:]]*=[[:space:]]*//p' "$conf" | tail -n 1 | tr -d '\r"')
        [ "$psk" = "CHANGE_ME" ] && psk=""
    fi
    if [ ${#psk} -lt 8 ]; then
        psk=$(od -An -N64 -tu1 /dev/urandom | tr -s ' ' '\n' | awk '$1 != "" && $1 < 250 { printf "%d", $1 % 10 }' | cut -c1-8)
        sed "s/^psk=.*/psk=$psk/" "$SD_SRC/g360r/g360r.conf.example" > "$conf"
        step "new Wi-Fi passphrase created"
    else
        step "Wi-Fi passphrase kept"
    fi
    for f in "$conf" "$card"/g360r/*.sh; do lf "$f"; done
    sync
    say "Done. Wi-Fi passphrase: $psk   (the app asks for it once)"
    say "On the camera:"
    step "1. Card in, power on. About 30 s of nothing, then the camera starts."
    step "2. The LEDs go around the camera, then 1 beep: its Wi-Fi is on (3 beeps = error:"
    step "   power off and run ./install.sh status)."
    step "3. Open the app on the phone and connect: the camera's network is called G360R-..."
}

cmd_stock() {
    local card
    card=$(card_dir "${1:-}")
    [ -f "$STOCK" ] || die "no $STOCK: put Samsung's firmware in firmware/stock/"
    say "Preparing $card to go back to Samsung's firmware"
    disarm "$card"
    put_aside "$card/smc200.bin.flashed"
    copy_checked "$STOCK" "$card/smc200.bin"
    : > "$card/fwupgrade.txt"
    step "created fwupgrade.txt"
    sync
    flash_instructions "takes a few minutes; afterwards it starts as it came from Samsung"
    say "Afterwards the g360r folder on the card is no longer used: you can delete it."
}

cmd_status() {
    local card
    card=$(card_dir "${1:-}")
    say "Card $card"
    local cam
    cam=$(camera_of "$card")
    if [ -n "${cam// /}" ]; then step "camera (from its newest photo): $cam"; fi
    local f
    for f in smc200.bin fwupgrade.txt info.tg cmd.tg; do
        if [ -e "$card/$f" ]; then step "$f present"; fi
    done
    if [ -e "$card/info.tg" ]; then
        step "-> set up for the activation step"
    elif [ -e "$card/smc200.bin" ]; then
        if [ -f "$IMAGE" ] && [ "$(sha "$card/smc200.bin")" = "$(sha "$IMAGE")" ]; then step "-> set up to install our firmware"
        elif [ -f "$STOCK" ] && [ "$(sha "$card/smc200.bin")" = "$(sha "$STOCK")" ]; then step "-> set up to go back to Samsung's firmware"
        else step "-> smc200.bin is neither our build nor the stock image"; fi
    elif [ -f "$card/g360r/g360r.conf" ]; then
        step "-> set up for everyday use (passphrase: $(sed -n 's/^psk=//p' "$card/g360r/g360r.conf" | tr -d '\r'))"
    else
        step "-> nothing of ours on it"
    fi
    if [ -d "$card/g360r/log" ]; then
        say "Latest logs (card/g360r/log):"
        for f in p8_invalidate.log state.txt g360r.log; do
            if [ -f "$card/g360r/log/$f" ]; then
                step "== $f"
                tail -n 8 "$card/g360r/log/$f" | sed 's/^/    /'
            fi
        done
    fi
}

CMD=${1:-}
[ $# -gt 0 ] && shift
case "$CMD" in
    build) cmd_build ;;
    flash) cmd_flash "$@" ;;
    activate) cmd_activate "$@" ;;
    sd) cmd_sd "$@" ;;
    stock) cmd_stock "$@" ;;
    status) cmd_status "$@" ;;
    *) sed -n '2,/^set /p' "$0" | grep '^#' | sed 's/^# \{0,1\}//'; exit 2 ;;
esac
