# Tools to rebuild the camera's rootfs partition: lzop (the SLP blobs are lzop),
# e2fsprogs (debugfs adds files to the ext4 image without mounting it), python3
# (camera/tools/slp_pack.py).
FROM debian:bookworm-slim
RUN apt-get update \
 && apt-get install -y --no-install-recommends lzop e2fsprogs python3 \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /w
