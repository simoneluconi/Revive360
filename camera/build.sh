#!/bin/sh
# Build g360rd for the camera (ARM EABI5, static) and the host (sim), assemble
# camera/out/sd/, verify the ARM binary. Runs the toolchain in Docker.
#
#   camera/build.sh          build
#   camera/build.sh smoke    build + sim smoke test
#   camera/build.sh clean
#
# G360R_SYSROOT picks the camera libraries the shim, plugin and Bluetooth helper
# link against (default .toolchain/aqk1/sysroot, relative to camera/).
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
IMAGE=g360r-build:1

if ! docker info >/dev/null 2>&1; then
    echo "Docker daemon not reachable: start Docker Desktop and retry." >&2
    exit 1
fi
if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
    docker build -t "$IMAGE" "$HERE/docker"
fi

TARGET=${1:-all}
case "$TARGET" in
    smoke) TARGETS="all smoke" ;;
    *)     TARGETS="$TARGET" ;;
esac

# MSYS_NO_PATHCONV keeps git-bash on Windows from rewriting /src.
MSYS_NO_PATHCONV=1 docker run --rm -v "$HERE:/src" -w /src "$IMAGE" make $TARGETS ${G360R_SYSROOT:+SYSROOT=$G360R_SYSROOT}
