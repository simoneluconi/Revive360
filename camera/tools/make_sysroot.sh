#!/bin/sh
# The camera's own system libraries (/lib of the stock rootfs), which the shim and
# the plugin link against (camera/Makefile: SYSROOT). Taken from the stock
# firmware image; runs inside the fwtools container, from the repository root:
#
#   docker run --rm -v "$PWD:/w" -w /w g360r-fwtools:1 \
#       sh camera/tools/make_sysroot.sh <stock.bin> camera/.toolchain/aqk1/sysroot
set -eu

STOCK=$1
OUT=$2
WORK=/tmp/sysroot
PART=6

mkdir -p "$WORK" "$OUT"
echo "== extract rootfs (SLP part $PART)"
python3 "$(dirname "$0")/slp_pack.py" extract "$STOCK" "$PART" "$WORK/rootfs.lzo"
lzop -d -c "$WORK/rootfs.lzo" > "$WORK/rootfs.img"
echo "== copy /lib"
rm -rf "$OUT/lib"
debugfs -R "rdump /lib $OUT" "$WORK/rootfs.img" </dev/null >/dev/null
# GLib and what it needs, for the Bluetooth helper (ble/): the real files, under
# the names the linker looks for
mkdir -p "$OUT/usr/lib"
for f in libgio-2.0.so.0:3200.3 libgobject-2.0.so.0:3200.3 libglib-2.0.so.0:3200.3          libgmodule-2.0.so.0:3200.3 libgthread-2.0.so.0:3200.3 libffi.so.5:0.10; do
    name=${f%%:*}
    debugfs -R "dump /usr/lib/$name.${f#*:} $OUT/usr/lib/$name" "$WORK/rootfs.img" </dev/null >/dev/null 2>&1
    [ -s "$OUT/usr/lib/$name" ] || { echo "missing /usr/lib/$name.${f#*:} in the stock rootfs" >&2; exit 1; }
done
for f in libc.so.6 libdl.so.2 libpthread.so.0 librt.so.1; do
    [ -e "$OUT/lib/$f" ] || { echo "missing $OUT/lib/$f" >&2; exit 1; }
done
echo "OK: $OUT/lib"
