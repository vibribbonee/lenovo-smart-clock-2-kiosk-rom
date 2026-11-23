# Partition layout

Source: `/dev/block/by-name` and `/vendor/etc/fstab.mt8167` read over ADB [V]. **Sizes are not yet known**: `/proc/partitions` and `/sys/block` are root‑only. Sizes will come from `mtk printgpt` during Stage 1 (see `BACKUP_AND_RECOVERY.md`).

eMMC hardware areas (third‑party mtkclient log, to verify): `boot1` 4 MiB (preloader), `boot2` 4 MiB, `rpmb` 4 MiB, user area ≈ 7.8 GB.

| # | Name | Mount / use | Category | Notes |
|---|---|---|---|---|
| p1 | `proinfo` | `/proinfo` raw | **Unique – identity** | Product info / serial, factory data. Never modify. |
| p2 | `nvram` | `/nvram` raw | **Unique – calibration/radio** | MTK NVRAM (Wi‑Fi/BT MAC, calibration). Never modify. |
| p3 | `protect1` | (MTK protect_f) | **Unique – radio** | Never modify. |
| p4 | `protect2` | (MTK protect_s) | **Unique – radio** | Never modify. |
| p5 | `persist` | — | **Unique** | Persist data (keys/calibration). Never modify. |
| p6 | `seccfg` | `/seccfg` raw | **Security config** | Holds lock state. Changed by *unlock*. Back up before unlock. |
| p7 | `lk` | `/bootloader` | Bootloader | Little Kernel. |
| p8 | `lk2` | `/bootloader2` | Bootloader | Backup LK. |
| p9 | `boot` | `/boot` | Kernel + ramdisk | Upstream report: sector 159232, 65536 sectors (32 MiB) [3P]. |
| p10 | `recovery` | `/recovery` | Stock recovery | Used by factory reset. |
| p11 | `para` | `/misc` | BCB / misc | Boot control messages. |
| p12 | `logo` | `/logo` | Boot logo | |
| p13 | `dtbo` | `/dtbo` | DT overlays | |
| p14 | `expdb` | `/expdb` | Crash logs | |
| p15 | `frp` | `/persistent` | Factory reset protection | No Google account → normally empty. |
| p16 | `tee1` | `/tee1` | TEE (Trustonic/kinibi or similar) | |
| p17 | `tee2` | `/tee2` | TEE backup | |
| p18 | `kb` | — | Key blob (MTK) | **Unique – likely device keys.** Never modify. |
| p19 | `dkb` | — | Key blob backup | **Unique.** Never modify. |
| p20 | `md_udc` | **`/metadata`** (ext4) | FBE metadata | fstab mounts *md_udc* at /metadata. |
| p21 | `metadata` | not mounted | MTK "metadata" | Name is misleading; not the Android /metadata. |
| p22 | `nvdata` | `/mnt/vendor/nvdata` (ext4) | **Unique – calibration/radio** | Never modify. |
| p23 | `vbmeta` | — | AVB root | Upstream report: sector 496128, 8192 sectors (4 MiB) [3P]. |
| p24 | `vbmeta_system` | — | AVB chain for system | |
| p25 | `vbmeta_vendor` | — | AVB chain for vendor | |
| p26 | `super` | logical: `system` (586 MB, ext4), `vendor` (111 MB), `product` (417 MB) — all 100 % full | OS | Dynamic partitions. No `odm` logical partition (`/odm` is a directory on system/vendor). |
| p27 | `cache` | `/cache` (ext4, 103 MB) | Cache / OTA | |
| p28 | `userdata` | `/data` (f2fs, 4.2 GB, FBE) | User data | Encrypted with keys bound to this device's TEE. |

Logical partitions are formatted ext4 and are completely full, which suggests they were built with deduplicated/shared blocks; they must be unshared and resized (`e2fsck -E unshare_blocks`, `resize2fs`) before modification, and `super` metadata must be regenerated with `lpmake` within the `super` size limit.

## Identity / calibration set (never write without explicit approval)
`proinfo, nvram, nvdata, protect1, protect2, persist, kb, dkb, seccfg, tee1, tee2, lk, lk2, boot1 (preloader), rpmb`.
