# g360rd build (runs inside the g360r-build Docker image, see build.ps1 / build.sh).
#
#   make            -> out/g360rd        ARM EABI5 static (camera)
#                      out/g360rd-host   x86-64 (sim/smoke test)
#                      out/sd/           ready-to-copy SD card tree
#   make check      -> verify the ARM binary (file/readelf)
#   make smoke      -> run the sim smoke test (tools/smoke_test.sh)

CROSS   ?= arm-linux-gnueabi-
CC_ARM  ?= $(CROSS)gcc
CC_HOST ?= gcc
STRIP_ARM ?= $(CROSS)strip

SRCS := $(wildcard src/*.c)
HDRS := $(wildcard src/*.h)

# _FILE_OFFSET_BITS=64: SD files (videos) can exceed 2 GiB on this 32-bit target.
CFLAGS_COMMON := -std=gnu99 -Os -Wall -Wextra -Wno-unused-parameter -Wno-format-truncation \
                 -D_FILE_OFFSET_BITS=64 -D_LARGEFILE64_SOURCE -pthread
# Static: no dependency on the camera's glibc 2.13 (the toolchain's static
# glibc requires kernel >= 3.2; the camera runs 3.5.0). Debian armel targets
# ARMv5TE+ with the soft-float calling convention, which runs on the armv7l
# SoC and is self-contained when linked statically.
CFLAGS_ARM  := $(CFLAGS_COMMON) -static -ffunction-sections -fdata-sections
LDFLAGS_ARM := -static -Wl,--gc-sections -pthread
CFLAGS_HOST := $(CFLAGS_COMMON) -g -O1

OUT := out

.PHONY: all check smoke sd clean shim plugin

all: $(OUT)/g360rd $(OUT)/g360rd-host sd check

$(OUT)/g360rd: $(SRCS) $(HDRS)
	@mkdir -p $(OUT)
	$(CC_ARM) $(CFLAGS_ARM) -o $@ $(SRCS) $(LDFLAGS_ARM)
	$(STRIP_ARM) $@

$(OUT)/g360rd-host: $(SRCS) $(HDRS)
	@mkdir -p $(OUT)
	$(CC_HOST) $(CFLAGS_HOST) -o $@ $(SRCS) -pthread

# SD tree: trigger files at the root, everything else under g360r/.
sd: $(OUT)/g360rd
	@rm -rf $(OUT)/sd
	@mkdir -p $(OUT)/sd/g360r/bin $(OUT)/sd/g360r/log
	cp sd/info.tg sd/cmd.tg $(OUT)/sd/
	cp sd/g360r/*.sh sd/g360r/g360r.conf.example $(OUT)/sd/g360r/
	cp $(OUT)/g360rd $(OUT)/sd/g360r/bin/g360rd
	@echo "SD tree ready in $(OUT)/sd"

check: $(OUT)/g360rd
	@echo "--- file"
	@file $(OUT)/g360rd
	@echo "--- ELF header"
	@$(CROSS)readelf -h $(OUT)/g360rd | grep -E 'Class|Machine|Flags|Type'
	@echo "--- ABI tag / interpreter / dynamic section"
	@$(CROSS)readelf -n $(OUT)/g360rd | grep -A1 'ABI' || true
	@$(CROSS)readelf -l $(OUT)/g360rd | grep -i interpreter || echo "no interpreter (static)"
	@$(CROSS)readelf -d $(OUT)/g360rd 2>&1 | head -3
	@ls -l $(OUT)/g360rd

smoke: $(OUT)/g360rd-host
	sh tools/smoke_test.sh $(OUT)/g360rd-host

clean:
	rm -rf $(OUT)

# libg360r_shim.so: LD_PRELOADed into di-camera-app (fw/overlay-controller).
# Linked against the camera's own glibc 2.13 (extracted to SYSROOT from the
# stock rootfs, see fw/README.md), so it only needs symbol versions the camera
# has; every camera library is looked up with dlsym at run time.
SYSROOT ?= .toolchain/aqk1/sysroot
shim: $(OUT)/libg360r_shim.so
$(OUT)/libg360r_shim.so: shim/g360r_shim.c
	@mkdir -p $(OUT)
	$(CC_ARM) -shared -fPIC -O2 -Wall -Wextra -o $@ $< -nostdlib -Wl,--no-undefined 		$(SYSROOT)/lib/libc.so.6 $(SYSROOT)/lib/libdl.so.2 $(SYSROOT)/lib/libpthread.so.0 $(SYSROOT)/lib/librt.so.1
	@$(CROSS)readelf -V $@ | grep -oE "GLIBC_[0-9.]+" | sort -uV | tr '\n' ' '; echo "<- glibc versions needed (camera has 2.13)"

# libg360r_plugin.so: dlopened by the shim from the card (no flash to change it).
plugin: $(OUT)/libg360r_plugin.so
$(OUT)/libg360r_plugin.so: shim/g360r_plugin.c
	@mkdir -p $(OUT)
	$(CC_ARM) -shared -fPIC -O2 -Wall -Wextra -o $@ $< -nostdlib -Wl,--no-undefined $(SYSROOT)/lib/libc.so.6 $(SYSROOT)/lib/libdl.so.2

# g360r-ble: the Bluetooth LE side (ble/g360r_ble.c), a dynamic program on the
# camera's own glibc 2.13 and GLib 2.32 (SYSROOT, with /usr/lib's GLib family);
# its own entry point (ble/start.S): our toolchain's crt1 wants a newer glibc.
ble: $(OUT)/g360r-ble
$(OUT)/g360r-ble: ble/g360r_ble.c ble/start.S ble/glib_min.h
	@mkdir -p $(OUT)
	$(CC_ARM) -O2 -Wall -Wextra -fno-stack-protector -U_FORTIFY_SOURCE -no-pie -nostdlib -nostartfiles \
		-o $@ ble/start.S ble/g360r_ble.c -Wl,--dynamic-linker=/lib/ld-linux.so.3 -Wl,--no-undefined \
		-Wl,-rpath-link=$(SYSROOT)/lib:$(SYSROOT)/usr/lib \
		$(SYSROOT)/usr/lib/libgio-2.0.so.0 $(SYSROOT)/usr/lib/libgobject-2.0.so.0 $(SYSROOT)/usr/lib/libglib-2.0.so.0 \
		$(SYSROOT)/lib/libc.so.6 $(SYSROOT)/lib/ld-linux.so.3 -lgcc
	@$(CROSS)readelf -V $@ | grep -oE "GLIBC_[0-9.]+" | sort -uV | tr '\n' ' '; echo "<- glibc versions needed (camera has 2.13)"
