"""Read, verify and rebuild Samsung SLP firmware images (Gear 360 SM-C200 / SM-R210).

Layout (little endian), verified on C200GLU0AQK1, see docs/firmware/10_firmware_modification.md:
  0x00  magic "SLP\\0" (or "DRM5"), 0x04 version, 0x0C model, 0x1C firmware name,
  0x2C  u32 partition count, 0x30 build string
  0x40  partition table, 24 bytes per entry: u32 size, u32 crc, u32 offset,
        u32 progress word, 8-byte tag
  then the partition blobs, contiguous, in table order; no trailer, no signature.
  crc = standard CRC-32 of the blob WITHOUT the final xor (zlib.crc32 ^ 0xFFFFFFFF).

Usage:
  slp_pack.py info      <in.bin>
  slp_pack.py roundtrip <in.bin> <out.bin>            rebuild unchanged; must be byte-identical
  slp_pack.py extract   <in.bin> <index> <out.blob>
  slp_pack.py replace   <in.bin> <index>=<blob> [...] <out.bin>
"""
import os
import struct
import sys
import zlib

HEADER_LEN = 0x40
ENTRY_LEN = 24
MAGICS = (b'SLP\0', b'DRM5')


def slp_crc(blob):
    return (zlib.crc32(blob) & 0xFFFFFFFF) ^ 0xFFFFFFFF


class Image:
    def __init__(self, path):
        with open(path, 'rb') as f:
            self.data = f.read()
        if self.data[:4] not in MAGICS:
            raise SystemExit(f'{path}: not an SLP image (magic {self.data[:4]!r})')
        self.header = self.data[:HEADER_LEN]
        count = struct.unpack_from('<I', self.data, 0x2C)[0]
        self.entries = []
        for i in range(count):
            base = HEADER_LEN + i * ENTRY_LEN
            size, crc, off, progress = struct.unpack_from('<IIII', self.data, base)
            tag = self.data[base + 16:base + 24]
            self.entries.append({'size': size, 'crc': crc, 'offset': off, 'progress': progress, 'tag': tag})

    def blob(self, i):
        e = self.entries[i]
        return self.data[e['offset']:e['offset'] + e['size']]

    def text(self, start, end):
        return self.data[start:end].split(b'\0')[0].decode('latin1')


def build(img, blobs):
    """The image with every partition's blob from `blobs`: offsets and CRCs recomputed."""
    count = len(img.entries)
    table = bytearray()
    offset = HEADER_LEN + count * ENTRY_LEN
    for e, blob in zip(img.entries, blobs):
        table += struct.pack('<IIII', len(blob), slp_crc(blob), offset, e['progress']) + e['tag']
        offset += len(blob)
    return img.header + bytes(table) + b''.join(blobs)


def cmd_info(path):
    img = Image(path)
    print(f'magic {img.data[:4]!r} version {img.text(4, 12)} model {img.text(0x0C, 0x1C)} '
          f'firmware {img.text(0x1C, 0x2C)} build {img.text(0x30, 0x40)}')
    bad = 0
    for i, e in enumerate(img.entries):
        blob = img.blob(i)
        ok = slp_crc(blob) == e['crc']
        bad += not ok
        kind = 'lzop' if blob[:4] == b'\x89LZO' else blob[:4].hex()
        tag = e['tag'].rstrip(b'\0').decode('latin1')
        print(f'[{i}] size {e["size"]:>10} crc {"OK " if ok else "BAD"} progress 0x{e["progress"]:08x} '
              f'tag {tag!r:12} {kind}')
    return 1 if bad else 0


def cmd_roundtrip(path, out):
    img = Image(path)
    rebuilt = build(img, [img.blob(i) for i in range(len(img.entries))])
    with open(out, 'wb') as f:
        f.write(rebuilt)
    same = rebuilt == img.data
    print(f'rebuilt {len(rebuilt)} bytes: {"IDENTICAL" if same else "DIFFERENT"} to the input')
    return 0 if same else 1


def cmd_extract(path, index, out):
    img = Image(path)
    with open(out, 'wb') as f:
        f.write(img.blob(int(index)))
    print(f'part {index}: {img.entries[int(index)]["size"]} bytes -> {out}')
    return 0


def cmd_replace(path, specs, out):
    img = Image(path)
    blobs = [img.blob(i) for i in range(len(img.entries))]
    for spec in specs:
        index, _, blob_path = spec.partition('=')
        with open(blob_path, 'rb') as f:
            blobs[int(index)] = f.read()
        print(f'part {index} <- {blob_path} ({len(blobs[int(index)])} bytes)')
    with open(out, 'wb') as f:
        f.write(build(img, blobs))
    return cmd_info(out)


def main(argv):
    if len(argv) < 3:
        print(__doc__)
        return 2
    cmd = argv[1]
    if cmd == 'info':
        return cmd_info(argv[2])
    if cmd == 'roundtrip' and len(argv) == 4:
        return cmd_roundtrip(argv[2], argv[3])
    if cmd == 'extract' and len(argv) == 5:
        return cmd_extract(argv[2], argv[3], argv[4])
    if cmd == 'replace' and len(argv) >= 5:
        return cmd_replace(argv[2], argv[3:-1], argv[-1])
    print(__doc__)
    return 2


if __name__ == '__main__':
    sys.exit(main(sys.argv))
