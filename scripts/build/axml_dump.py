#!/usr/bin/env python3
"""Read-only dumper for Android compiled binary XML (AXML) files, e.g. res/xml/*.xml inside an APK.

Usage: axml_dump.py <file.apk> <path/in/apk.xml>
       axml_dump.py <file.xml>
Prints an approximate text XML. Attribute values that are resource references are shown as @0x7f...;
typed values (int/bool) are shown decoded. Good enough for inspection; not a full aapt2 replacement.
"""
import struct, sys, zipfile

RES_STRING_POOL = 0x0001
RES_XML_START_ELEMENT = 0x0102
RES_XML_END_ELEMENT = 0x0103


def strings(data, off):
    _t, hsz, _sz, count, _styles, flags, s_start, _st_start = struct.unpack_from("<HHIIIIII", data, off)
    utf8 = bool(flags & 0x100)
    offs = struct.unpack_from(f"<{count}I", data, off + hsz)
    out = []
    for o in offs:
        p = off + s_start + o
        if utf8:
            n = data[p]; p += 2 if n & 0x80 else 1
            n = data[p]
            if n & 0x80:
                n = ((n & 0x7F) << 8) | data[p + 1]; p += 2
            else:
                p += 1
            out.append(data[p:p + n].decode("utf-8", "replace"))
        else:
            n, = struct.unpack_from("<H", data, p); p += 2
            if n & 0x8000:
                n = ((n & 0x7FFF) << 16) | struct.unpack_from("<H", data, p)[0]; p += 2
            out.append(data[p:p + 2 * n].decode("utf-16le", "replace"))
    return out


def dump(data):
    pos = 8
    pool = []
    depth = 0
    lines = []
    while pos < len(data):
        t, hsz, size = struct.unpack_from("<HHI", data, pos)
        if t == RES_STRING_POOL:
            pool = strings(data, pos)
        elif t == RES_XML_START_ELEMENT:
            ns, name, attr_start, attr_size, attr_count = struct.unpack_from("<iiHHH", data, pos + 16)
            attrs = []
            a = pos + 16 + attr_start
            for i in range(attr_count):
                ans, aname, araw, _vsz, _res0, vtype, vdata = struct.unpack_from("<iiiHBBI", data, a + i * attr_size)
                if araw >= 0:
                    val = pool[araw]
                elif vtype == 0x03:
                    val = pool[vdata]
                elif vtype == 0x12:
                    val = "true" if vdata else "false"
                elif vtype in (0x10, 0x11):
                    val = str(vdata)
                elif vtype == 0x01:
                    val = f"@0x{vdata:08x}"
                else:
                    val = f"(type {vtype:#x}) {vdata:#x}"
                attrs.append(f'{pool[aname]}="{val}"')
            lines.append("  " * depth + f"<{pool[name]} " + " ".join(attrs) + ">")
            depth += 1
        elif t == RES_XML_END_ELEMENT:
            depth -= 1
            _ns, name = struct.unpack_from("<ii", data, pos + 16)
            lines.append("  " * depth + f"</{pool[name]}>")
        pos += size
    return "\n".join(lines)


if __name__ == "__main__":
    if len(sys.argv) == 3:
        data = zipfile.ZipFile(sys.argv[1]).read(sys.argv[2])
    else:
        data = open(sys.argv[1], "rb").read()
    print(dump(data))
