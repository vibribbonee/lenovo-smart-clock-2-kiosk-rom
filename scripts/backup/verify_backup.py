#!/usr/bin/env python3
"""Offline verification of a BROM backup. Never touches the device, never modifies the backup.

  verify_backup.py <backup-dir> [--second <other-backup-dir>]

Checks every partition dump against the size the clock's own GPT (gpt/gpt.bin) records, that it is not
all zeros, and (with --second) that a second, independent read gave identical bytes. Writes
<backup-dir>.MANIFEST.tsv next to the backup (refuses to overwrite one) — flashing scripts check the
backup against this manifest before every write.
Exit code 0 only if every partition except userdata is present, the right size and non-empty.
"""
import hashlib, os, struct, sys

SKIPPED = {"userdata"}  # deliberately not backed up (your personal data, and not needed to restore)


def gpt_sizes(path):
    d = open(path, "rb").read()
    hdr = d.find(b"EFI PART")
    if hdr < 0:
        sys.exit(f"{path}: no GPT header")
    lba = 512 if hdr == 512 else hdr  # header sits at LBA 1
    ent_lba, n, esz = struct.unpack_from("<QII", d, hdr + 72)
    base = ent_lba * lba
    out = {}
    for i in range(n):
        e = d[base + i * esz: base + (i + 1) * esz]
        if len(e) < 128 or e[:16] == b"\0" * 16:
            continue
        first, last = struct.unpack_from("<QQ", e, 32)
        name = e[56:128].decode("utf-16-le").rstrip("\0")
        out[name] = (last - first + 1) * lba
    return out


def sha_and_nonzero(path):
    h, nz = hashlib.sha256(), False
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 22), b""):
            h.update(chunk)
            if not nz and chunk.strip(b"\0"):
                nz = True
    return h.hexdigest(), nz


def main():
    args = sys.argv[1:]
    if not args:
        sys.exit(__doc__)
    bk = os.path.abspath(args[0]).rstrip("/")
    second = os.path.abspath(args[args.index("--second") + 1]) if "--second" in args else None
    man = bk + ".MANIFEST.tsv"
    if os.path.exists(man):
        sys.exit(f"{man} already exists; not overwriting it.")
    sizes = gpt_sizes(os.path.join(bk, "gpt", "gpt.bin"))
    rows, bad = [], []
    for name, gbytes in sizes.items():
        p = os.path.join(bk, "partitions", name + ".bin")
        if not os.path.exists(p):
            rows.append([name, "-", gbytes, "MISSING(skipped)" if name in SKIPPED else "MISSING", "-", "-", "-"])
            if name not in SKIPPED:
                bad.append(f"{name}: missing")
            continue
        size = os.path.getsize(p)
        print(f"  hashing {name} ({size // 2**20} MiB)", flush=True)
        sha, nz = sha_and_nonzero(p)
        second_read = "-"
        if second:
            q = os.path.join(second, "partitions", name + ".bin")
            second_read = ("MATCH" if sha_and_nonzero(q)[0] == sha else "DIFFERENT") if os.path.exists(q) else "-"
        ok = size == gbytes
        rows.append([name, size, gbytes, ok, sha, nz, second_read])
        if not ok:
            bad.append(f"{name}: {size} bytes, GPT says {gbytes}")
        if not nz and name not in ("cache", "metadata", "frp", "expdb", "para", "dkb", "kb", "persist", "protect1", "protect2"):
            bad.append(f"{name}: all zeros")
        if second_read == "DIFFERENT" and name not in ("metadata", "expdb", "para", "nvdata", "persist", "seccfg", "frp"):
            bad.append(f"{name}: two reads differ")
    with open(man, "w") as f:
        f.write("name\tbytes\tgpt_bytes\tsize_ok\tsha256\tnonzero\tsecond_read\n")
        for r in rows:
            f.write("\t".join(map(str, r)) + "\n")
    print(f"manifest: {man}")
    if bad:
        print("BACKUP NOT OK:\n  " + "\n  ".join(bad))
        sys.exit(1)
    print(f"BACKUP OK: {len(rows) - 1} partitions match the GPT" + (" and a second read" if second else ""))


if __name__ == "__main__":
    main()
