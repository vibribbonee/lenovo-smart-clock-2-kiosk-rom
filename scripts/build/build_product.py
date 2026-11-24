#!/usr/bin/env python3
"""Build firmware/work/product.img from firmware/original/product.img + rom/manifest.json.

Never touches the original image. Steps:
  1. copy the ext4 part of the original (without its AVB hashtree/FEC footer) to firmware/work/
  2. e2fsck, resize2fs to manifest.product_size_mib
  3. debugfs: remove the listed directories (recursively), add the listed files with
     owner 0:0, dirs 0755 / files 0644 and the same SELinux label as the stock files
  4. e2fsck -fn and a listing of every changed path, written to firmware/work/product.changes.txt
The AVB hashtree footer and vbmeta_system are produced afterwards by build_vbmeta.sh.
"""
import hashlib, json, os, shutil, struct, subprocess, sys, tempfile

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
E2 = "/opt/homebrew/opt/e2fsprogs/sbin"
SYSTEM_LABEL = "u:object_r:system_file:s0"


def run(*cmd, check=True):
    r = subprocess.run(cmd, capture_output=True, text=True)
    if check and r.returncode != 0:
        sys.exit(f"command failed ({r.returncode}): {' '.join(cmd)}\n{r.stdout}\n{r.stderr}")
    return r


def debugfs(img, cmds, write=False):
    with tempfile.NamedTemporaryFile("w", delete=False, suffix=".cmds") as f:
        f.write("\n".join(cmds) + "\n")
        path = f.name
    try:
        args = [f"{E2}/debugfs"] + (["-w"] if write else []) + ["-f", path, img]
        r = run(*args)
        bad = [l for l in (r.stdout + r.stderr).splitlines()
               if any(w in l for w in ("Couldn't", "not found", "File exists", "No free space", "error", "Error"))]
        if bad:
            sys.exit("debugfs reported problems:\n" + "\n".join(bad[:20]))
        return r.stdout
    finally:
        os.unlink(path)


def ext4_size(img):
    with open(img, "rb") as f:
        f.seek(1024)
        sb = f.read(1024)
    blocks_lo, = struct.unpack_from("<I", sb, 4)
    log_block, = struct.unpack_from("<I", sb, 24)
    blocks_hi, = struct.unpack_from("<I", sb, 0x150)
    return (blocks_lo | (blocks_hi << 32)) * (1024 << log_block)


def tree(img, path):
    """Return (dirs, files) under path, deepest first, via debugfs ls -p."""
    dirs, files, todo = [], [], [path]
    while todo:
        d = todo.pop()
        dirs.append(d)
        out = debugfs(img, [f"ls -p {d}"])
        for line in out.splitlines():
            # format: /<inode>/<mode octal>/<uid>/<gid>/<name>/<size>/
            parts = line.split("/")
            if len(parts) < 7 or not parts[1].isdigit() or parts[5] in (".", ".."):
                continue
            mode, name = int(parts[2], 8), parts[5]
            child = d.rstrip("/") + "/" + name
            (todo if (mode & 0o170000) == 0o040000 else files).append(child)
    return list(reversed(dirs)), files


def main():
    man = json.load(open(os.path.join(ROOT, "rom", "manifest.json")))
    src = os.path.join(ROOT, "firmware", "original", "product.img")
    work = os.path.join(ROOT, "firmware", "work")
    os.makedirs(work, exist_ok=True)
    out = os.path.join(work, "product.img")

    # 1. ext4 part only
    fs = ext4_size(src)
    with open(src, "rb") as i, open(out, "wb") as o:
        left = fs
        while left:
            b = i.read(min(left, 1 << 24)); o.write(b); left -= len(b)
    print(f"copied ext4 part: {fs} bytes")

    # 2. check + grow
    run(f"{E2}/e2fsck", "-fy", out)
    run(f"{E2}/resize2fs", out, f"{man['product_size_mib']}M")
    print(f"resized to {man['product_size_mib']} MiB")

    changes = []
    # 3a. removals
    for r in man["remove"]:
        dirs, files = tree(out, r["path"])
        cmds = [f"rm {f}" for f in files] + [f"rmdir {d}" for d in dirs]
        debugfs(out, cmds, write=True)
        changes += [f"- {f}" for f in files] + [f"- {d}/" for d in dirs]
        print(f"removed {r['path']} ({len(files)} files)")

    # 3b. additions
    label_files = {}
    def label_file(label):
        if label not in label_files:
            p = os.path.join(work, "label-" + hashlib.sha1(label.encode()).hexdigest()[:8])
            with open(p, "wb") as f:
                f.write(label.encode() + b"\0")
            label_files[label] = p
        return label_files[label]

    for a in man["add"]:
        s = os.path.join(ROOT, a["src"])
        if not os.path.isfile(s):
            sys.exit(f"missing input {a['src']}")
        dst, label = a["dst"], a.get("label", SYSTEM_LABEL)
        cmds = []
        parent = os.path.dirname(dst)
        probe = run(f"{E2}/debugfs", "-R", f"stat {parent}", out, check=False)
        if "Inode:" not in probe.stdout:
            cmds += [f"mkdir {parent}", f"sif {parent} mode 040755", f"sif {parent} uid 0", f"sif {parent} gid 0",
                     f"ea_set -f {label_file(SYSTEM_LABEL if '/overlay' not in parent else label)} {parent} security.selinux"]
            changes.append(f"+ {parent}/")
        cmds += [f"write {s} {dst}", f"sif {dst} mode 0100644", f"sif {dst} uid 0", f"sif {dst} gid 0",
                 f"ea_set -f {label_file(label)} {dst} security.selinux"]
        debugfs(out, cmds, write=True)
        h = hashlib.sha256(open(s, "rb").read()).hexdigest()
        changes.append(f"+ {dst}  ({os.path.getsize(s)} bytes, sha256 {h})")
        print(f"added {dst}")

    # 4. verify
    r = run(f"{E2}/e2fsck", "-fn", out, check=False)
    print(r.stdout.strip().splitlines()[-1])
    if r.returncode != 0:
        sys.exit("e2fsck found problems:\n" + r.stdout + r.stderr)
    with open(os.path.join(work, "product.changes.txt"), "w") as f:
        f.write("\n".join(changes) + "\n")
    for l in label_files.values():
        os.unlink(l)
    print(f"done: {out} ({os.path.getsize(out)} bytes); changes in firmware/work/product.changes.txt")


if __name__ == "__main__":
    main()
