# Backup and recovery

**Highest priority. Nothing in this document has been executed yet.** Every step that touches the device needs your explicit approval.

## 1. Recovery paths available on this device (ranked by depth)

| Level | Path | What it can fix | Requirements / caveats |
|---|---|---|---|
| L0 | Android/ADB (current) | Package state, settings | Device boots. Read‑only inventory done this way. |
| L1 | Stock recovery (Vol+ menu) | Factory reset (wipe data/cache) | Recovery partition intact. Stock reset may hit the dead Lenovo update server during Google‑Home setup (see FIRMWARE.md). |
| L2 | Fastboot (`adb reboot bootloader` / Vol+ menu) | Flash boot, vbmeta, super, recovery | Requires **unlocked** bootloader to flash. LK must be intact. |
| L3 | **MediaTek BROM + mtkclient** (Kamakiri2 exploit) | Read/write **any** eMMC area incl. GPT, preloader (boot1), LK, seccfg | BROM is in mask ROM → available even when preloader/LK/boot are broken, as long as the hardware and the pogo USB connection work. Timing‑sensitive; mtkclient version matters (XDA/upstream: v1.52 worked for several users where 2.0 failed; we have 1.6.3). |

L3 is the real safety net. **A backup is only as good as our ability to re‑enter BROM**, so Stage 1 proves BROM read access first, and Stage 1b proves a write of a harmless partition before we rely on it (see TEST_PLAN T‑R*).

Known brick reports for this model (XDA): stuck in "FASTBOOT mode…" after flashing a patched boot; "orange state" boot loop; stuck at Lenovo logo after flashing a Magisk‑patched boot (newer Magisk ≥30.x reportedly patches this boot.img incorrectly). All were LK/boot‑level and recoverable only with BROM + a correct original image.

## 2. What will be backed up

Store under `backups/<YYYYMMDD>-<build>/` — **outside git**, never uploaded, read‑only after creation (`chmod -R a-w`), and copied to a second disk.

| Set | Contents | Method |
|---|---|---|
| A. Metadata | GPT (primary+backup), partition table text, eMMC CID/sizes, mtkclient log | `mtk printgpt`, `mtk r gpt` |
| B. Hardware areas | `boot1` (preloader), `boot2`, **`rpmb`** (if mtkclient can read it on this DA) | `mtk rf --parttype boot1/boot2`, `mtk da rpmb r` |
| C. Every named partition except userdata | proinfo, nvram, protect1/2, persist, seccfg, lk, lk2, boot, recovery, para, logo, dtbo, expdb, frp, tee1/2, kb, dkb, md_udc, metadata, nvdata, vbmeta, vbmeta_system, vbmeta_vendor, super, cache | `mtk rl <dir> --skip userdata` (one file per partition) |
| D. Full user area | Single raw image of the whole user area (≈7.8 GB) incl. userdata | `mtk rf full_user.bin` — redundancy for C; userdata is FBE‑encrypted and only meaningful on this exact device with the same TEE/RPMB state |
| E. Logical images | `system.img`, `vendor.img`, `product.img` extracted from `super.bin` | `lpunpack` on the Mac (offline) — these become `firmware/original/` |
| F. Android‑level | `adb` file pulls of `/system`, `/vendor`, `/product` (world‑readable parts), package list, settings, getprop | Already partly in `investigation/device/` |

For each file: SHA‑256, size in bytes, expected size from GPT, and the exact command + mtkclient version + date are written to `MANIFEST.tsv` by `scripts/backup/verify_backup.sh`.

## 3. Verification (evidence, not assumption)
1. Size of each dump == GPT size × 512.
2. SHA‑256 recorded, then **a second read of the identity set** (nvram, nvdata, proinfo, persist, protect1/2, seccfg, kb, dkb) must produce identical hashes (proves stable reads; flaky pogo contact caused silent 50 % failures for XDA users).
3. `super.bin`: `lpdump` parses; `lpunpack` extracts; each ext4 image passes `e2fsck -n`; `build.prop` inside system.img matches the running fingerprint.
4. `boot.img` / `recovery.img`: `unpack_bootimg`/`avbtool info_image` parse; vbmeta digests consistent with `ro.boot.vbmeta.digest` (`4e872197…0a39`).
5. Full image (D) region at each partition offset hashes equal to the per‑partition files (C).

## 4. Restoration procedures (to be rehearsed, see TEST_PLAN)
- **Partial (most likely):** BROM → `mtk w boot,vbmeta,vbmeta_system,vbmeta_vendor,super <files>` → `mtk reset`.
- **Re‑lock to stock:** restore all of set C except identity partitions (they were never changed) + original `seccfg` → device returns to green/locked. Userdata must then be wiped (`md_udc`, `userdata`) because keys change on lock state change.
- **Full:** `mtk wf full_user.bin` restores the whole user area (including identity partitions — only if they were damaged).
- **Preloader damage:** `mtk w preloader` via boot1 from set B. Only if needed.

## 6. Backup #1 — 2026-11-23 23:39 (done, verified)
Location: `backups/20261123-233949-1.2.2.627/` (git-ignored). Manifest with sizes and SHA-256: `backups/20261123-233949-1.2.2.627.MANIFEST.tsv`.
How: `sudo I_APPROVE_BROM_READ=yes scripts/backup/mtk_backup_readonly.sh backups` (mtkclient 1.6.3, one BROM session, Kamakiri2). **sudo is required on macOS**, otherwise libusb cannot detach Apple's CDC driver ("Access denied") and the BROM device is seen but never opened. BROM entry: power off, hold both volume keys, apply power with the pogo USB attached (the window lasts about 6 s; the Mac sees `0e8d:0003`).

| Item | Status |
|---|---|
| GPT (primary + backup) | ✅ 28 partitions, eMMC user area 0x1d2000000 (7.8 GB), boot1/boot2/RPMB 4 MiB each, eMMC ID 08A391 |
| boot1 (preloader, starts with `EMMC_BOOT`) / boot2 | ✅ 4 MiB each |
| All 27 partitions except userdata | ✅ size == GPT length for every one |
| Second independent read | ✅ All 27 partitions **byte-identical** to the corresponding slice of the full-image read |
| Full user-area image | ⚠ interrupted at 26 % (3.63 GB) when the clock was bumped; it still covers everything up to and including `super` and `cache`. Only `userdata` was never read (intentionally excluded and FBE-encrypted). File `hw/full_user.bin` is **partial**. |
| Partitions that are entirely zero | protect1, protect2, persist, expdb, dkb, metadata (unused on this device; consistent across both reads) |

Not yet done: restore rehearsal (Stage 1b), offline `lpunpack` of `super.bin`, second copy on another disk, read-only permissions (files are owned by root because the script ran with sudo and aborted before its chown step).

## 5. Limitations (honest)
- No official Lenovo factory image is currently available → our dump is the only source.
- RPMB / TEE state cannot necessarily be restored; anti‑rollback counters and keymaster keys live there. Unlocking or downgrading may invalidate userdata regardless of backups.
- BROM access depends on the pogo connection and USB timing; if VBUS/DP/DM contacts fail (one XDA user measured 0 V on VBUS) we have no path.
- A hardware fault, eMMC wear‑out, or a blown eFuse is not recoverable by any backup.
- mtkclient behaviour changes between versions; we pin the version used for the backup and record it.
- Restoring someone else's dump would clone their identity partitions — never do that, and never share ours.

## 7. Restore rehearsal (Stage 1b) — 2026-11-24 01:26, PASSED
`sudo I_AM_AT_THE_CLOCK=yes scripts/flash/mtk_session.sh rehearsal`: wrote the backup's `logo.bin` to `logo` (sector 291328, 16384 sectors) and read it back in the same BROM session. Read-back (`backups/flash-logs/20261124-012622-rehearsal/logo.readback.bin`, 8,388,608 bytes) is byte-identical to the backup (`cmp`; SHA-256 `efca8863…b05f`). The write path (this Mac, pogo cable, mtkclient 1.6.3 with sudo) is proven.
mtkclient prints "Failed to dump sector …" after a successful read to a file: in 1.6.3 `xflash_lib.readflash()` returns an empty bytearray when a filename is given, which the caller treats as failure. Cosmetic; always verify by hash.
