#!/bin/sh
# From the stock firmware to ours in one command: checks that the stock image is
# one the overlay is made for, builds the controller and the shim, stages the
# overlay and rebuilds the image in the fwtools container (fw_build.sh).
#
# The stock image never goes into the repository: download it and put it in
# firmware/stock/ (git ignores firmware/). Then, from the repository root:
#
#   sh camera/tools/make_firmware.sh [firmware/stock/<stock image>.bin]
#
# The result is firmware/out/smc200.bin, the file the flash takes from the SD
# card (docs/INSTALL.md, camera/fw/README.md).
set -eu
cd "$(dirname "$0")/../.."

STOCK=${1:-firmware/stock/C200GLU0AQK1_171121_1257_REV00_user.bin}
OUT=firmware/out
STAGE=camera/out/fw-stage

# The stock images the overlay is built and tested against, by SHA-256
supported() {
    case "$1" in
        150bc48362555a4812e8871ff581c693f50f80e3e31e4647d2b563e9072c48db) echo "SM-C200 C200GLU0AQK1" ;;
        *) return 1 ;;
    esac
}

if [ ! -f "$STOCK" ]; then
    echo "no $STOCK: download the stock firmware and put it in firmware/stock/ (see docs/INSTALL.md)" >&2
    exit 1
fi
SUM=$(sha256sum "$STOCK" | cut -d' ' -f1)
if ! VERSION=$(supported "$SUM"); then
    echo "$STOCK is not a stock image this overlay supports (sha256 $SUM)" >&2
    exit 1
fi
echo "== stock firmware: $VERSION"

echo "== controller and shim"
sh camera/build.sh
sh camera/build.sh shim
sh camera/build.sh ble

echo "== overlay"
sh camera/tools/fw_stage_controller.sh "$STAGE"

if ! docker image inspect g360r-fwtools:1 >/dev/null 2>&1; then
    MSYS_NO_PATHCONV=1 docker build -t g360r-fwtools:1 -f camera/docker/fwtools.Dockerfile camera/docker
fi
mkdir -p "$OUT"
echo "== image"
# The repository is mounted at /w: the stock image, the stage and the output are all inside it
MSYS_NO_PATHCONV=1 docker run --rm -v "$PWD:/w" -w /w g360r-fwtools:1 \
    sh camera/tools/fw_build.sh "$STOCK" "$STAGE" "$OUT/smc200.bin" /tmp/fwbuild
echo "== done: $OUT/smc200.bin"
