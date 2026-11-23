# Hardware — Lenovo Smart Clock 2 (CD-24502F, "Helios")

Status legend: **[V]** verified on this device (ADB, read-only, 2026-11-23) · **[F]** derived from this device's firmware files · **[3P]** third-party report, not yet verified · **[?]** unknown

Raw evidence: `investigation/device/` (getprop, cpuinfo, meminfo, mounts, by-name, dumpsys output, pulled framework files). That directory contains the device serial — keep it private.

| Item | Value | Source |
|---|---|---|
| Model | `LenovoCD-24502F` (`ro.product.model`), codename/device `Helios`, OTA model `LenovoCD-24502F-ROW` | [V] |
| SoC | MediaTek **MT8167S** (`/proc/cpuinfo Hardware`), `ro.board.platform=mt8167` | [V] |
| CPU | 4 × ARMv7 cores, CPU part `0xd04` (= Cortex‑A35), features incl. neon, vfpv4, aes, sha2, crc32 | [V] |
| Kernel arch | `armv7l` — **32‑bit kernel** (`uname -a`) | [V] |
| Userspace ABIs | `ro.product.cpu.abilist=armeabi-v7a,armeabi`; `abilist64` empty → **32‑bit only. arm64 APKs/libs cannot run.** | [V] |
| RAM | `MemTotal 1,005,036 kB` (1 GB). `ro.config.low_ram=true` (Android Go‑style config), zram swap ≈552 MB | [V] |
| Storage | eMMC, `soc/11120000.mmc`. `/data` = 4.2 GB f2fs. Third‑party mtkclient log reports user area `0x1d2000000` (≈7.8 GB, "8 GB"), boot1/boot2 4 MiB each, RPMB 4 MiB, eMMC ID `08A391` | [V] partial / [3P] sizes |
| Display | 800 × 480 px, density 240 (hdpi), `ro.sf.lcd_density=240` | [V] |
| GPU | **Imagination PowerVR** (Rogue GE8300 per MediaTek spec [3P]); PowerVR DDK 1.11@5425693 on this exact build per smartclock2tool [3P]; GLES `ro.opengles.version=196610` (0x30002 = **GLES 3.2**), no Vulkan prop | [V] props |
| Touchscreen | Input devices `fts_ts` (FocalTech) **and** `mtk-tpd` both registered; which one drives the panel not yet determined | [V] |
| Other input | `odm:gpio-keys` (volume keys), `mtk-pmic-keys` (power), `Accelerometer`, **`heliosbase-status`** (dock status; emits KEY codes `0xfa`, `0xfb`) | [V] |
| Mic mute | Hardware slide switch on the bottom PCB ("turns the microphone on and off") | [3P] XDA |
| Wi‑Fi | MediaTek connectivity (`wlan0`, `p2p0`, `ap0`), country `EU`. 802.11 b/g/n per XDA (2.4 GHz only?) | [V] ifaces / [3P] spec |
| Bluetooth | MediaTek BT (`MtkBluetooth`, `bluetooth-1-0` HAL), BT 4.2 per XDA | [V] / [3P] |
| Battery | None (`present: false`). Mains powered, 12 V on the pogo connector | [V] / [3P] 12 V |
| USB | Pogo pads on the base: DP, DM, GND×2, VBUS, 12 V, DET, VBAT. **Dual‑role** USB (`supported_modes=dual`); currently `ufp/device/sink` with the DIY cable | [V] role / [3P] pinout |
| Dock link | The dock's MCU appears to the clock as a **USB CDC‑ACM device `/dev/ttyACM0`** (clock = USB host). ⇒ **Dock and USB‑ADB use the same pogo USB lines and cannot be used at the same time.** | [F] HAL strings + [3P] XDA |
| Audio | MediaTek audio HAL 5.0. Known vendor bug: capture at 16 kHz mono is corrupted by the BesRecord SRC; 48 kHz stereo is clean (upstream issue #24) | [3P] |
| Proximity | Kiosk Satellite reads `/sys/class/st_psensor/psensor_value` (ST proximity sensor) — presence not yet verified | [?] |
| Buttons at boot | Vol+ while powering → bootloader/recovery menu; Vol− → safe mode; BROM: Vol+ (± Vol−) while applying power with USB attached | [3P] XDA/upstream |

## Implications
1. **Everything we ship must be armeabi‑v7a.** Universal APKs work but waste ~180 MB of storage on unused ABIs (Kiosk Satellite: 247 MB APK, only 66 MB is armv7 native code).
2. **1 GB RAM with low_ram=true**: the Google Cast/Assistant stack (mediashell ≈250 MB PSS across 5 processes) is the largest removable consumer.
3. **Dock testing needs ADB over Wi‑Fi**, because the dock occupies the USB data lines.

## CPU headroom vs. the Echo Show (LineageOS Kiosk Satellite Edition)
jxlarrea's [lineageos-echo-kiosk-satellite](https://github.com/jxlarrea/lineageos-echo-kiosk-satellite) runs the full Kiosk Satellite on the Echo Show 5 (1st gen, `checkers`): MT8163, quad Cortex‑A53, 1 GB RAM, **64‑bit kernel** (its patches touch `arm64` configs/DTS). This clock is the closest comparable device but is weaker on CPU:

| | Echo Show 5 1st gen (MT8163) | Smart Clock 2 (MT8167S) |
|---|---|---|
| Cores | 4 × Cortex‑A53 | 4 × Cortex‑A35 [V] |
| Per‑clock performance | 1.0 | ≈0.75–0.8 (Arm's own A35 positioning) [3P] |
| Instruction set in use | AArch64 | **AArch32 only** (32‑bit kernel) — ML runtimes (ONNX/TFLite) are typically slower in armv7 builds |
| RAM | 1 GB | 1 GB |
| GPU | Mali‑T720 MP2 | PowerVR GE8300 (likely faster for WebView compositing) |

**Estimate (not measured): CPU‑bound work — wake‑word inference, AEC, JS — ≈30–45 % slower than on the Echo Show 5 1st gen.** Rendering may hold up better thanks to the GPU. The stock firmware also burns CPU on things our ROM removes (Assistant's always‑on 16 kHz hotword capture, the Cast receiver's 5 processes), so today's experience is a *pessimistic* baseline.

Measurements to settle it (Stage 2, read‑only): `cpufreq` max clock, `top` CPU % of `me.jxl.kiosk_satellite` idle and while listening, wake‑word latency/false‑reject over N tries, dashboard scroll jank (`dumpsys gfxinfo`), then repeat with the Google stack disabled.

## Still to verify
- Exact eMMC size and partition sizes (needs `mtk printgpt`; sysfs is root‑only).
- Which touchscreen driver is active (`getevent` while touching).
- Wi‑Fi/BT chip part number (MT6627/MT6630 family? — check `/vendor/firmware` names and `dmesg` with root).
