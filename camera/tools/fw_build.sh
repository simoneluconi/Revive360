#!/bin/sh
# Build a modified Gear 360 firmware: the stock SLP image with files added to its
# rootfs (SLP part 6, lzop'd ext4). Nothing in the stock rootfs is changed or
# removed; every other partition is copied as is. Run inside the fwtools image:
#
#   docker build -t g360r-fwtools:1 -f camera/docker/fwtools.Dockerfile camera/docker
#   docker run --rm -v "$PWD:/w" g360r-fwtools:1 sh camera/tools/fw_build.sh \
#       <stock.bin> <overlay-dir> <out.bin> [workdir]
#
# overlay-dir mirrors the rootfs: overlay/usr/bin/x lands at /usr/bin/x. File
# modes come from overlay/MODES ("<mode> <path>" per line, e.g. "0755 /usr/bin/x");
# files without an entry get 0644, directories 0755. Owner is always root.
# Symlinks are listed in overlay/LINKS ("<target> <link path>").
# With an empty overlay the rebuilt rootfs must decompress to the stock image.
set -eu

STOCK=$1
OVERLAY=$(cd "$2" && pwd)
OUT=$3
WORK=${4:-/tmp/fwbuild}
PART=6
TOOLS=$(dirname "$0")

mkdir -p "$WORK"
echo "== extract rootfs (SLP part $PART)"
python3 "$TOOLS/slp_pack.py" extract "$STOCK" "$PART" "$WORK/rootfs.lzo"
lzop -d -c "$WORK/rootfs.lzo" > "$WORK/rootfs.img"
cp "$WORK/rootfs.img" "$WORK/rootfs.stock.img"
echo "rootfs image: $(wc -c < "$WORK/rootfs.img") bytes"

# debugfs exits 0 even when a path is missing: look at what it prints instead
exists() {
    ! debugfs -R "stat $1" "$WORK/rootfs.img" </dev/null 2>&1 | grep -q "File not found"
}

# debugfs needs every parent directory to exist first: create them top-down
add_dirs() {
    # sh has no locals: these names must not clash with the callers' loop variables
    ad_dir=$1
    path=""
    for part in $(echo "$ad_dir" | tr '/' ' '); do
        path="$path/$part"
        if ! exists "$path"; then
            debugfs -w -R "mkdir $path" "$WORK/rootfs.img" </dev/null >/dev/null
            debugfs -w -R "sif $path mode 040755" "$WORK/rootfs.img" </dev/null >/dev/null
            echo "  mkdir $path"
        fi
    done
}

mode_of() {
    m=$(awk -v p="$1" '$2 == p { print $1 }' "$OVERLAY/MODES" 2>/dev/null | tail -n 1)
    echo "${m:-0644}"
}

echo "== add overlay files"
( cd "$OVERLAY" && find . -type f ! -name MODES ! -name LINKS | sed 's|^\./||' | sort ) | while read -r rel; do
    dst="/$rel"
    add_dirs "$(dirname "$rel")"
    if exists "$dst"; then
        echo "REFUSING to overwrite stock file $dst" >&2
        exit 1
    fi
    debugfs -w -R "write $OVERLAY/$rel $dst" "$WORK/rootfs.img" </dev/null
    debugfs -w -R "sif $dst mode 0100$(mode_of "$dst" | sed 's/^0//')" "$WORK/rootfs.img" </dev/null >/dev/null
    debugfs -w -R "sif $dst uid 0" "$WORK/rootfs.img" </dev/null >/dev/null
    debugfs -w -R "sif $dst gid 0" "$WORK/rootfs.img" </dev/null >/dev/null
    # Read it back: the image must hold exactly the source bytes
    rm -f "$WORK/readback"
    debugfs -R "dump $dst $WORK/readback" "$WORK/rootfs.img" </dev/null >/dev/null 2>&1
    if ! cmp -s "$WORK/readback" "$OVERLAY/$rel"; then
        echo "READBACK MISMATCH for $dst ($(wc -c < "$WORK/readback" 2>/dev/null) bytes vs $(wc -c < "$OVERLAY/$rel"))" >&2
        exit 1
    fi
    echo "  add $dst ($(mode_of "$dst"), $(wc -c < "$OVERLAY/$rel") bytes, verified)"
done
if [ -f "$OVERLAY/LINKS" ]; then
    while read -r target link; do
        [ -n "$link" ] || continue
        add_dirs "$(dirname "${link#/}")"
        if exists "$link"; then
            echo "REFUSING to overwrite stock path $link" >&2
            exit 1
        fi
        debugfs -w -R "symlink $link $target" "$WORK/rootfs.img" </dev/null >/dev/null
        echo "  link $link -> $target"
    done < "$OVERLAY/LINKS"
fi

echo "== check the filesystem"
e2fsck -fn "$WORK/rootfs.img"

echo "== recompress and rebuild the SLP image"
lzop -9 -c "$WORK/rootfs.img" > "$WORK/rootfs.new.lzo"
python3 "$TOOLS/slp_pack.py" replace "$STOCK" "$PART=$WORK/rootfs.new.lzo" "$OUT"
echo "== verify: part $PART of the new image decompresses to the modified rootfs"
python3 "$TOOLS/slp_pack.py" extract "$OUT" "$PART" "$WORK/check.lzo" >/dev/null
lzop -d -c "$WORK/check.lzo" | cmp - "$WORK/rootfs.img"
echo "OK: $OUT"
