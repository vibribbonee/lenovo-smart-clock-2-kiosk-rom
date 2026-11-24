#!/usr/bin/env python3
"""Make firmware/work/super.img: stock super with ONE partition replaced by a new image.

Usage: lp_repack.py <stock super.bin> <out super.img> <partition name> <new image>

- system/vendor extents and data are left exactly where they are (byte-identical).
- The new image is written into free space after the last used sector, 1 MiB aligned, as a single
  linear extent; the partition's old extents are dropped from the metadata (their bytes are left as-is).
- New liblp metadata (same header version, recomputed tables + SHA-256 checksums) is written to
  slot 0 (primary + backup); slot 1 is left as in the stock image. Geometry is unchanged.
Refuses to run if the output exists or the image doesn't fit.
"""
import hashlib, os, shutil, struct, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lp_unpack as L

SECTOR = 512
ALIGN_SECTORS = 2048  # 1 MiB


def main():
    stock, out, name, image = sys.argv[1:5]
    if os.path.exists(out):
        sys.exit(f"{out} exists — refusing to overwrite")
    with open(stock, "rb") as f:
        geo = L.read_geometry(f)
        f.seek(L.LP_RESERVED + 2 * 4096)
        raw = f.read(geo["metadata_max_size"])
        md = L.read_metadata(f, geo)

    magic, major, minor, header_size = struct.unpack_from("<IHHI", raw, 0)
    header = bytearray(raw[:header_size])
    descs = [list(struct.unpack_from("<III", raw, 80 + 12 * i)) for i in range(4)]
    tables = raw[header_size:header_size + struct.unpack_from("<I", raw, 44)[0]]
    def entries(i):
        o, n, sz = descs[i]
        return [bytearray(tables[o + k * sz:o + (k + 1) * sz]) for k in range(n)]
    parts, exts, groups, devs = entries(0), entries(1), entries(2), entries(3)
    part_sz, ext_sz = descs[0][2], descs[1][2]

    names = [p[:36].split(b"\0")[0].decode() for p in parts]
    if name not in names:
        sys.exit(f"partition {name} not in metadata: {names}")

    img_size = os.path.getsize(image)
    if img_size % geo["logical_block_size"]:
        sys.exit("image size is not a multiple of the logical block size")
    img_sectors = img_size // SECTOR

    # last used sector over all extents, then align
    end = max(e["data"] + e["sectors"] for e in md["extents"] if e["type"] == 0)
    start = (end + ALIGN_SECTORS - 1) // ALIGN_SECTORS * ALIGN_SECTORS
    dev_sectors = md["block_devices"][0]["size"] // SECTOR
    if start + img_sectors > dev_sectors:
        sys.exit(f"does not fit: needs sectors {start}..{start + img_sectors}, device has {dev_sectors}")

    # rebuild extent table: keep others' extents in their order, the target gets one new extent
    new_exts, new_parts = [], []
    for p, pname in zip(parts, names):
        attrs, first, num, group = struct.unpack_from("<IIII", p, 36)
        if pname == name:
            e = bytearray(ext_sz); struct.pack_into("<QIQI", e, 0, img_sectors, 0, start, 0)
            mine = [e]
        else:
            mine = exts[first:first + num]
        struct.pack_into("<II", p, 40, len(new_exts), len(mine))
        new_exts += mine
        new_parts.append(p)

    blobs = [b"".join(new_parts), b"".join(new_exts), b"".join(groups), b"".join(devs)]
    off = 0
    for i, b in enumerate(blobs):
        descs[i][0] = off
        descs[i][1] = len(b) // descs[i][2]
        off += len(b)
    new_tables = b"".join(blobs)
    for i in range(4):
        struct.pack_into("<III", header, 80 + 12 * i, *descs[i])
    struct.pack_into("<I", header, 44, len(new_tables))
    header[48:80] = hashlib.sha256(new_tables).digest()
    header[12:44] = b"\0" * 32
    header[12:44] = hashlib.sha256(bytes(header)).digest()
    metadata = bytes(header) + new_tables
    if len(metadata) > geo["metadata_max_size"]:
        sys.exit("metadata too large")

    shutil.copyfile(stock, out)
    with open(out, "r+b") as o:
        base = L.LP_RESERVED + 2 * 4096
        mm, slots = geo["metadata_max_size"], geo["slot_count"]
        # Slot 0 only (primary + backup copy). This device is non-A/B and always uses slot 0;
        # stock slot 1 holds an older factory layout, which is left byte-identical.
        for copy in (base, base + slots * mm):
            o.seek(copy); o.write(metadata + b"\0" * (mm - len(metadata)))
        o.seek(start * SECTOR)
        with open(image, "rb") as i:
            shutil.copyfileobj(i, o, 1 << 24)
    print(f"{name}: {img_size} bytes at sector {start} (offset {start * SECTOR / 2**20:.1f} MiB), "
          f"ends {(start + img_sectors) * SECTOR / 2**20:.1f} MiB of {dev_sectors * SECTOR / 2**20:.1f} MiB")
    print(f"wrote {out}")


if __name__ == "__main__":
    main()
