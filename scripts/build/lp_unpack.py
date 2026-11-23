#!/usr/bin/env python3
"""Minimal, read-only extractor for Android dynamic-partition `super` images (liblp format).

Usage:
  lp_unpack.py <super.bin> --list
  lp_unpack.py <super.bin> <outdir> [name ...]

Implements the on-disk layout from AOSP system/core/fs_mgr/liblp/include/liblp/metadata_format.h:
  geometry at 4096 (backup at 8192), metadata slot 0 at 4096 + 2*4096.
Verifies the geometry and metadata header/table SHA-256 checksums before extracting.
Never writes to the input file.
"""
import argparse, hashlib, os, struct, sys

LP_RESERVED = 4096
GEOMETRY_MAGIC = 0x616C4467
HEADER_MAGIC = 0x414C5030
SECTOR = 512


def read_geometry(f):
    f.seek(LP_RESERVED)
    raw = f.read(4096)
    magic, struct_size = struct.unpack_from("<II", raw, 0)
    if magic != GEOMETRY_MAGIC:
        sys.exit(f"bad geometry magic {magic:#x}")
    checksum = raw[8:40]
    meta_max, slots, lbs = struct.unpack_from("<III", raw, 40)
    blob = bytearray(raw[:struct_size]); blob[8:40] = b"\0" * 32
    if hashlib.sha256(blob).digest() != checksum:
        sys.exit("geometry checksum mismatch")
    return dict(metadata_max_size=meta_max, slot_count=slots, logical_block_size=lbs)


def read_metadata(f, geo, slot=0):
    off = LP_RESERVED + 2 * 4096 + slot * geo["metadata_max_size"]
    f.seek(off)
    raw = f.read(geo["metadata_max_size"])
    magic, major, minor, header_size = struct.unpack_from("<IHHI", raw, 0)
    if magic != HEADER_MAGIC:
        sys.exit(f"bad metadata magic {magic:#x}")
    hdr_checksum = raw[12:44]
    tables_size, = struct.unpack_from("<I", raw, 44)
    tables_checksum = raw[48:80]
    descs = [struct.unpack_from("<III", raw, 80 + 12 * i) for i in range(4)]  # partitions, extents, groups, block_devices
    hdr = bytearray(raw[:header_size]); hdr[12:44] = b"\0" * 32
    if hashlib.sha256(hdr).digest() != hdr_checksum:
        sys.exit("metadata header checksum mismatch")
    tables = raw[header_size:header_size + tables_size]
    if hashlib.sha256(tables).digest() != tables_checksum:
        sys.exit("metadata tables checksum mismatch")

    def table(i):
        o, n, sz = descs[i]
        return [tables[o + k * sz:o + (k + 1) * sz] for k in range(n)]

    parts = []
    for e in table(0):
        name = e[:36].split(b"\0")[0].decode()
        attrs, first, num, group = struct.unpack_from("<IIII", e, 36)
        parts.append(dict(name=name, attrs=attrs, first_extent=first, num_extents=num, group=group))
    extents = []
    for e in table(1):
        nsec, ttype, tdata, tsrc = struct.unpack_from("<QIQI", e, 0)
        extents.append(dict(sectors=nsec, type=ttype, data=tdata, source=tsrc))
    groups = []
    for e in table(2):
        name = e[:36].split(b"\0")[0].decode()
        flags, maxsize = struct.unpack_from("<IQ", e, 36)
        groups.append(dict(name=name, flags=flags, maximum_size=maxsize))
    devs = []
    for e in table(3):
        first_sec, align, align_off, size = struct.unpack_from("<QIIQ", e, 0)
        name = e[24:60].split(b"\0")[0].decode()
        devs.append(dict(name=name, first_logical_sector=first_sec, size=size))
    return dict(version=f"{major}.{minor}", partitions=parts, extents=extents, groups=groups, block_devices=devs)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("super")
    ap.add_argument("outdir", nargs="?")
    ap.add_argument("names", nargs="*")
    ap.add_argument("--list", action="store_true")
    a = ap.parse_args()
    with open(a.super, "rb") as f:
        geo = read_geometry(f)
        md = read_metadata(f, geo)
        print(f"liblp metadata {md['version']}, slots={geo['slot_count']}, metadata_max={geo['metadata_max_size']}, block={geo['logical_block_size']}")
        for d in md["block_devices"]:
            print(f"block device {d['name']}: size {d['size']} ({d['size']/2**20:.1f} MiB), first logical sector {d['first_logical_sector']}")
        for g in md["groups"]:
            print(f"group {g['name']}: max {g['maximum_size']} ({g['maximum_size']/2**20:.1f} MiB)")
        for p in md["partitions"]:
            ex = md["extents"][p["first_extent"]:p["first_extent"] + p["num_extents"]]
            size = sum(e["sectors"] for e in ex) * SECTOR
            print(f"partition {p['name']:<10} group={md['groups'][p['group']]['name']:<12} size={size} ({size/2**20:.1f} MiB) extents={len(ex)} attrs={p['attrs']:#x}")
        if a.list or not a.outdir:
            return
        os.makedirs(a.outdir, exist_ok=True)
        for p in md["partitions"]:
            if a.names and p["name"] not in a.names:
                continue
            out = os.path.join(a.outdir, p["name"] + ".img")
            if os.path.exists(out):
                sys.exit(f"{out} exists — refusing to overwrite")
            ex = md["extents"][p["first_extent"]:p["first_extent"] + p["num_extents"]]
            with open(out, "wb") as o:
                for e in ex:
                    n = e["sectors"] * SECTOR
                    if e["type"] == 1:  # zero target
                        o.write(b"\0" * n)
                        continue
                    f.seek(e["data"] * SECTOR)
                    while n:
                        b = f.read(min(n, 1 << 24))
                        if not b:
                            sys.exit("short read")
                        o.write(b); n -= len(b)
            print(f"wrote {out}")


if __name__ == "__main__":
    main()
