#!/bin/bash
# Revive360 installer: one command per step. Each one does all the work on the
# PC and the SD card, then says what to do on the camera. Run from the repository root,
# in Git Bash on Windows or a shell on Linux, with Docker running.
#
#   ./install.sh [model] build              build everything (once, and after an update)
#   ./install.sh [model] flash    [card]    card for the firmware update
#   ./install.sh [model] activate [card]    card for the one-time activation after the update
#   ./install.sh [model] sd       [card]    card for everyday use
#   ./install.sh [model] stock    [card]    card to go back to Samsung's firmware
#   ./install.sh [model] status   [card]    what is on the card now
#
# [model] is c200 (Gear 360 2016, the default) or r210 (Gear 360 2017, first steps:
# docs/firmware/15_r210_port.md). On the r210 the flash card already gets everything
# for everyday use, so there is no sd step after the activation.
# [card] is a drive letter (E:) or a mount point. Left out, the only removable card
# connected is used. The card is never formatted and the camera's photos stay: only
# files of ours are written, and ours that are no longer needed are removed.
set -euo pipefail
cd "$(dirname "$0")"

MODEL=c200
case "${1:-}" in
    c200|r210) MODEL=$1; shift ;;
esac
# Per model: Samsung's last firmware (the only one supported), our image and its name
# on the card, the camera libraries, and what the camera writes in its photos' Exif
if [ "$MODEL" = r210 ]; then
    STOCK=firmware/stock/R210GLU0ARB2_180201_1739_REV00_user.bin
    CARD_IMAGE=smr210.bin
    SYSROOT=camera/.toolchain/arb2/sysroot
    EXIF_MODEL=SM-R210
    EXIF_VERSION=""
    CAMERA_NAME="SM-R210 (Gear 360 2017)"
else
    STOCK=firmware/stock/C200GLU0AQK1_171121_1257_REV00_user.bin
    CARD_IMAGE=smc200.bin
    SYSROOT=camera/.toolchain/aqk1/sysroot
    EXIF_MODEL=SM-C200
    EXIF_VERSION=0.85
    CAMERA_NAME="SM-C200 (Gear 360 2016)"
fi
IMAGE=firmware/out/$CARD_IMAGE
SD_SRC=camera/out/sd
PLUGIN=camera/out/libg360r_plugin.so
BLE=camera/out/g360r-ble
export MSYS_NO_PATHCONV=1
export G360R_SYSROOT=${SYSROOT#camera/}

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

# Removes a file or folder of ours, when there.
drop() {
    [ -e "$1" ] || return 0
    rm -rf "$1"
    step "removed: ${1#"$2"/}"
}

# The factory SD hook's trigger files (only the activation step may have them) and what
# the earlier steps leave behind: the flashed image, the activation script and its marks.
tidy() {
    local f
    for f in info.tg cmd.tg info.tg.done smc200.bin.flashed smr210.bin.flashed fwupgrade.txt.done \
             g360r/p8_hook g360r/p8_invalidate.done; do
        drop "$1/$f" "$1"
    done
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

# The r210 flashes an image under Samsung's own name (R210GL..._user.bin) before
# smr210.bin, whatever smr210.bin holds (docs/firmware/15_r210_port.md): with one on
# the card the update would install that one instead.
no_stock_named_image() {
    local f
    for f in "$1"/R210GL*user.bin "$1"/FTMA_R210GL*eng.bin "$1"/C200GL*user.bin; do
        [ -e "$f" ] || continue
        die "the card has ${f##*/}: the camera would install that one instead. Delete it from the card and run this again"
    done
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
    [ "$model" = "$EXIF_MODEL" ] || die "the photos on this card come from a $model, not an $CAMERA_NAME (other model: ./install.sh c200|r210 $CMD)"
    if [ -z "$EXIF_VERSION" ]; then
        step "camera: $model, from its photos (supported firmware: R210GLU0ARB2)"
    elif [ "$version" = "$EXIF_VERSION" ]; then
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
    step "4. The update starts by itself: from now on do not press any button (power cancels it)."
    step "   Wait: \"Check for updates\", a progress bar, \"Updated\", then it restarts by itself."
    step "   LEDs lit during the update are normal. Keep the card in until it has restarted."
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
    say "== plugin and Bluetooth side"
    # The r210 image has no shim yet, so nothing would load the plugin
    [ "$MODEL" = r210 ] || sh camera/build.sh plugin
    sh camera/build.sh ble
    say "== firmware"
    sh camera/tools/make_firmware.sh "$STOCK"
    say "Built. Next: put the SD card in the PC and run ./install.sh $(model_arg)flash"
}

# "r210 " when that is the model, so the commands we print can be pasted as they are
model_arg() { [ "$MODEL" = c200 ] || printf '%s ' "$MODEL"; }

# The controller, the plugin (c200) and the Bluetooth side in card/g360r/, and the
# settings (kept when the card has some). A new r210 card gets its Wi-Fi passphrase
# here, printed for the app's manual pairing: the r210's Bluetooth side is untested.
copy_payload() {
    local card=$1 f
    [ -f "$SD_SRC/g360r/bin/g360rd" ] || die "no $SD_SRC yet: run ./install.sh $(model_arg)build first"
    [ "$MODEL" = r210 ] || [ -f "$PLUGIN" ] || die "no $PLUGIN yet: run ./install.sh build first"
    [ -f "$BLE" ] || die "no $BLE yet: run ./install.sh $(model_arg)build first"
    mkdir -p "$card/g360r/bin" "$card/g360r/log"
    for f in "$SD_SRC"/g360r/*; do [ -f "$f" ] && cp "$f" "$card/g360r/"; done
    cp "$SD_SRC/g360r/bin/g360rd" "$card/g360r/bin/g360rd"
    cp "$BLE" "$card/g360r/bin/g360r-ble"
    if [ "$MODEL" = r210 ]; then
        step "controller and Bluetooth side copied to card/g360r/"
    else
        cp "$PLUGIN" "$card/g360r/libg360r_plugin.so"
        step "controller, plugin and Bluetooth side copied to card/g360r/"
    fi
    local conf="$card/g360r/g360r.conf"
    if [ -f "$conf" ]; then
        step "settings kept"
    else
        cp "$SD_SRC/g360r/g360r.conf.example" "$conf"
        if [ "$MODEL" = r210 ]; then
            local psk
            psk=$(tr -dc 'A-Za-z0-9' < /dev/urandom | head -c 16 || true)
            sed -i "s/^psk=.*/psk=$psk/" "$conf"
            step "default settings written. Wi-Fi passphrase: $psk"
            step "Keep it: if the app does not find the camera over Bluetooth, pair it by hand with"
            step "this passphrase (network G360R- plus the last 6 digits of the camera's Wi-Fi MAC)."
        else
            step "default settings written"
        fi
    fi
    for f in "$conf" "$card"/g360r/*.sh; do lf "$f"; done
}

cmd_flash() {
    local card
    card=$(card_dir "${1:-}")
    [ -f "$IMAGE" ] || die "no $IMAGE yet: run ./install.sh $(model_arg)build first"
    say "Preparing $card for the firmware update ($CAMERA_NAME)"
    check_camera "$card"
    no_stock_named_image "$card"
    tidy "$card"
    copy_checked "$IMAGE" "$card/$CARD_IMAGE"
    : > "$card/fwupgrade.txt"
    step "created fwupgrade.txt"
    # The r210's card is taken out as seldom as possible: everything for everyday use
    # goes on it now (the stock firmware ignores it)
    if [ "$MODEL" = r210 ]; then copy_payload "$card"; fi
    sync
    flash_instructions "takes a few minutes"
    say "Then: camera off, card back in the PC, and run ./install.sh $(model_arg)activate"
}

cmd_activate() {
    local card
    card=$(card_dir "${1:-}")
    say "Preparing $card for the activation"
    # The update is done: its image and trigger would start it again
    drop "$card/$CARD_IMAGE" "$card"
    drop "$card/fwupgrade.txt" "$card"
    tidy "$card"
    mkdir -p "$card/g360r/p8_hook" "$card/g360r/log"
    cp camera/fw/p8_invalidate.sh "$card/g360r/p8_hook/p8_invalidate.sh"
    lf "$card/g360r/p8_hook/p8_invalidate.sh"
    printf 'cmd.tg\n' > "$card/info.tg"
    printf 'shell script /sdcard/g360r/p8_hook/p8_invalidate.sh\n' > "$card/cmd.tg"
    sync
    step "activation script and its trigger files written"
    say "On the camera (about a minute):"
    if [ "$MODEL" = r210 ]; then
        step "1. Card in, power on normally, and do not press any button."
    else
        step "1. Card in, power on normally. A factory screen with codes and the battery level"
        step "   appears (e.g. QK1 38%): that is expected. Do NOT press any button while it is shown."
    fi
    step "2. 1 beep = started, 2 beeps = done. 3 beeps = error: power off and run ./install.sh $(model_arg)status"
    step "3. After the 2 beeps: power off, then on again. This first start takes about 30 s with"
    step "   nothing on the screen: wait. From now on every start takes that long."
    if [ "$MODEL" = r210 ]; then
        step "4. The card already has everything for everyday use: leave it in. Open the app and"
        step "   tap Find the camera (or pair by hand with the passphrase the flash step printed)."
    else
        say "Then: camera off, card back in the PC, and run ./install.sh sd"
    fi
}

cmd_sd() {
    local card
    card=$(card_dir "${1:-}")
    say "Preparing $card for everyday use"
    tidy "$card"
    copy_payload "$card"
    sync
    say "Done."
    say "On the camera and the phone:"
    step "1. Card in, power on. About 30 s of nothing, then the camera starts; the LEDs go"
    step "   around it and 1 beep says it is ready (3 beeps = error: power off and run"
    step "   ./install.sh status)."
    step "2. Within 3 minutes of switching it on, open the app and tap Find the camera: it"
    step "   finds it over Bluetooth and gets its Wi-Fi by itself. Nothing to type."
    step "3. Later the app switches the camera's Wi-Fi on over Bluetooth whenever it needs it."
}

cmd_stock() {
    local card
    card=$(card_dir "${1:-}")
    [ -f "$STOCK" ] || die "no $STOCK: put Samsung's firmware in firmware/stock/"
    say "Preparing $card to go back to Samsung's firmware"
    no_stock_named_image "$card"
    tidy "$card"
    copy_checked "$STOCK" "$card/$CARD_IMAGE"
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
    for f in smc200.bin smr210.bin fwupgrade.txt info.tg cmd.tg; do
        if [ -e "$card/$f" ]; then step "$f present"; fi
    done
    if [ -e "$card/info.tg" ]; then
        step "-> set up for the activation step"
    elif [ -e "$card/$CARD_IMAGE" ]; then
        if [ -f "$IMAGE" ] && [ "$(sha "$card/$CARD_IMAGE")" = "$(sha "$IMAGE")" ]; then step "-> set up to install our firmware"
        elif [ -f "$STOCK" ] && [ "$(sha "$card/$CARD_IMAGE")" = "$(sha "$STOCK")" ]; then step "-> set up to go back to Samsung's firmware"
        else step "-> $CARD_IMAGE is neither our build nor the stock image"; fi
    elif [ -f "$card/g360r/g360r.conf" ]; then
        step "-> set up for everyday use"
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
