# Build environment for g360rd.
#
# - gcc-arm-linux-gnueabi: Debian armel (ARMv5TE+, soft-float ABI = "softfp"-compatible
#   calling convention, EABI5). The camera is armv7l with glibc 2.13; we link STATICALLY so the
#   binary does not depend on the camera's libc version at all.
# - gcc (native): builds the x86-64 host/sim variant used for the smoke test.
# - curl: used by the smoke test.
# - lzop/e2fsprogs/python3: only for optional firmware inspection (camera/tools/).
FROM debian:bookworm-slim
RUN apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      gcc libc6-dev make file binutils curl ca-certificates \
      gcc-arm-linux-gnueabi libc6-dev-armel-cross binutils-arm-linux-gnueabi \
      lzop e2fsprogs python3 \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /src
