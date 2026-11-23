# Offline image analysis (from Backup #1, 2026-11-24)

Tools (in repo, read-only): `scripts/build/lp_unpack.py` (super/liblp), `scripts/build/avb_info.py` (vbmeta), `scripts/build/axml_dump.py` (compiled XML). ext4 inspection with Homebrew `e2fsprogs` (`debugfs`, `dumpe2fs`, `e2fsck -fn` on a scratch copy).
Outputs: `firmware/original/{system,vendor,product}.img` (read-only, git-ignored), `firmware/extracted/` (props and pulled files, git-ignored).

## super
| | |
|---|---|
| Format | liblp metadata 10.0, 2 slots, metadata_max 64 KiB, block 4 KiB; geometry and metadata checksums **verified** |
| Device size | **2,740 MiB** (group `main`, max = whole device) |
| system | 595.8 MiB, 1 extent |
| vendor | 113.6 MiB, 1 extent |
| product | 424.3 MiB, 4 extents |
| **Free** | **≈ 1,606 MiB** — room for Kiosk Satellite (247 MB), Google WebView (~100–150 MB), helpers, *without removing anything* |

| SHA-256 (first 16 hex) | |
|---|---|
| system.img | 014232bf9f3ec41e |
| vendor.img | 4af996f5889e7c26 |
| product.img | fc20e5d26a127469 |

## Filesystems
All three are plain ext4 (features: ext_attr resize_inode dir_index filetype extent sparse_super large_file huge_file uninit_bg dir_nlink extra_isize — **no `shared_blocks`**), `e2fsck -fn` clean, and nearly full:
| | blocks used / total | inodes used / total |
|---|---|---|
| system | 149,633 / 150,090 | 2,902 / 2,960 |
| vendor | 28,518 / 28,607 | 1,047 / 1,056 |
| product | 106,570 / 106,890 | **233 / 256** |

`product` is inode‑limited too → rebuild it with `mke2fs -d` (or `resize2fs` + inode check) rather than adding files in place. `system` is **system‑as‑root** (contains `init`, `init.rc`, first‑stage layout).

## Verified boot (AVB 1.1, all RSA‑2048, all rollback indexes 0)
```
vbmeta ──chain──> boot (loc 3), recovery (loc 1), vbmeta_system (loc 2), vbmeta_vendor (loc 4)
       └─hash──> dtbo
vbmeta_system ──hashtree+FEC──> system, product
vbmeta_vendor ──hashtree+FEC──> vendor
```
Implications: modifying `product` requires a new `product` hashtree descriptor in `vbmeta_system`. After unlocking we can either (a) set the disable‑verification flag, or (b) **re‑sign `vbmeta_system` with our own key and keep dm‑verity enforcing** (LK on an unlocked device accepts a mismatching key, "orange" state). (b) keeps integrity protection; preferred. `boot`, `vendor`, `vbmeta_vendor`, `dtbo` stay stock and keep verifying with Lenovo's keys through the chain only if the top‑level `vbmeta` is left untouched — to be tested.

## WebView provider list (framework-res `res/xml/config_webview_packages.xml`)
```xml
<webviewproviders>
  <webviewprovider availableByDefault="true" description="Android WebView" packageName="com.android.webview"/>
</webviewproviders>
```
No `<signature>` element. On Android 10 a provider without signatures is accepted only if it is a **system app** → shipping Google WebView in `/product/app` plus an overlay entry for `com.google.android.webview` is sufficient; no Google certificate needs to be pinned. Updates installed over the system copy stay valid (updated system app).
No existing overlay touches this file (product RRO and vendor RROs only override `power_profile.xml`).

## product partition contents
- app: MTPDemo, ModuleMetadata, MtkBrowser, MtkCalendar, MtkGallery2, MtkWallpaperPicker, TalkBack, assistant.launcher, webview (`webview.apk` 56.9 MB)
- priv-app: DuraSpeed, MtkCarrierConfig, MtkContacts, MtkEmergencyInfo, MtkSettings, MtkSimProcessor, **Provision**, mediashell (`148 MB` APK + libs), updater, assistant.core (3.5 MB), com.google.oem
- etc: `default-permissions/`, `permissions/` (incl. `privapp-permissions-google.xml`), `selinux/`, `fs_config_*` (empty)
- media: `bootanimation.zip` (2.4 MB); lib: `libcast_auth.mtk.so`, `libfmjni.so`, `libtz_uree_sys.so`

## Relevant properties (stock)
`qemu.hw.mainkeys=1`, `ro.config.low_ram=true`, `dalvik.vm.heapgrowthlimit=128m`, `heapsize=256m`, `ro.lmk.*` (PSI‑style, `use_minfree_levels=false`), `ro.build.characteristics=aiv`, `ro.product.locale=en-US`, `dalvik.vm.systemservercompilerfilter=speed-profile`, `persist.device_config.runtime_native.usap_pool_enabled=false`.
