"""Unpack Samsung SLP firmware (Gear 360 / NX series).

Header layout (little endian):
  0x00  "SLP\0" + version string
  0x0C  model, 0x1C full firmware name
  0x2C  u32 partition count
  0x30  build string
  0x40  partition table, 24 bytes each: size, crc32, offset, flags, 8 bytes extra
"""
import struct, sys, zlib, os

def main(path, outdir):
    with open(path, 'rb') as f:
        data = f.read()
    assert data[:3] == b'SLP', 'not an SLP firmware'
    cstr = lambda b: b.split(b'\0')[0].decode('latin1')
    print('version :', cstr(data[4:12]))
    print('model   :', cstr(data[0x0C:0x1C]))
    print('fw name :', cstr(data[0x1C:0x2C]))
    count = struct.unpack_from('<I', data, 0x2C)[0]
    print('build   :', cstr(data[0x30:0x40]))
    print('parts   :', count)
    os.makedirs(outdir, exist_ok=True)
    for i in range(count):
        size, crc, off, flags = struct.unpack_from('<IIII', data, 0x40 + i * 24)
        extra = data[0x40 + i * 24 + 16:0x40 + i * 24 + 24]
        blob = data[off:off + size]
        ok = (zlib.crc32(blob) & 0xffffffff) == crc
        magic = blob[:16]
        print(f'[{i}] off=0x{off:08x} size=0x{size:08x} ({size/1048576:7.2f} MB) '
              f'crc={"OK " if ok else "BAD"} flags=0x{flags:08x} extra={extra.hex()} magic={magic.hex()}')
        with open(os.path.join(outdir, f'part{i:02d}.bin'), 'wb') as o:
            o.write(blob)
    end = 0x40 + count * 24
    last = max(struct.unpack_from('<I', data, 0x40 + i * 24 + 8)[0] + struct.unpack_from('<I', data, 0x40 + i * 24)[0] for i in range(count))
    print(f'file size 0x{len(data):x}, last partition ends 0x{last:x}, trailer {len(data) - last} bytes')
    if len(data) > last:
        print('trailer:', data[last:last + 64].hex())

if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
