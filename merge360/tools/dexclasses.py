"""列出 APK 裡所有 classes*.dex 定義的類別（descriptor）。用法：dexclasses.py <apk>"""
import struct, sys, zipfile

def uleb(b, o):
    r = s = 0
    while True:
        x = b[o]; o += 1
        r |= (x & 0x7f) << s; s += 7
        if x < 0x80: return r, o

def classes(dex):
    sid_n, sid_off, tid_n, tid_off = struct.unpack_from('<IIII', dex, 0x38)
    cd_n, cd_off = struct.unpack_from('<II', dex, 0x60)
    def string(i):
        off = struct.unpack_from('<I', dex, sid_off + 4 * i)[0]
        _, o = uleb(dex, off)
        e = dex.index(b'\0', o)
        return dex[o:e].decode('utf-8', 'replace')
    out = []
    for i in range(cd_n):
        cidx = struct.unpack_from('<I', dex, cd_off + 32 * i)[0]
        out.append(string(struct.unpack_from('<I', dex, tid_off + 4 * cidx)[0]))
    return out

z = zipfile.ZipFile(sys.argv[1])
for n in sorted(x for x in z.namelist() if x.startswith('classes') and x.endswith('.dex')):
    for c in classes(z.read(n)):
        print(n, c)
