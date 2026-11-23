# Performance baseline — stock firmware + Kiosk Satellite (2026‑11‑23)

Device: stock 1.2.2.627, Google stack running, Kiosk Satellite 2026.11.24 in the foreground, WebView 74, voice enabled, no interaction. All read‑only via ADB. Raw data: `investigation/perf/`.

## CPU
| Metric | Value |
|---|---|
| Cores / clock | 4 × Cortex‑A35, 598 MHz – **1.5 GHz**, `ondemand` governor, all cores online |
| Whole system, idle on dashboard (2 × 40 s, `top`) | **≈ 35–40 % of 4 cores busy** (user 117–133 %, sys 18–22 % out of 400 %) → ≈ 60 % idle |
| Kiosk Satellite | **≈ 85 % of one core continuously**; 69 % in a single `DartWorker` thread, ~4 % in `vsww-mic` (wake word mic), ~9 % main thread |
| Audio HAL + audioserver | ≈ 18 % + 13 % of one core (continuous capture) |
| Cast receiver (mediashell, all procs) | ≈ 8–9 % of one core |

**Reading:** the CPU is not saturated, but one core is permanently busy with always-on voice processing. Interaction, the dashboard, and wake-word work all compete on the remaining ~2.4 cores. Removing Cast/Assistant frees ~10 % CPU, ~200 MB RAM and the second mic client. Whether the always-busy `DartWorker` is wake-word inference or something else (AEC, polling) is a question for the Kiosk Satellite developer. Its cost on an A53 Echo would be ~25 % lower.

## Boot
Kernel start → screen enabled: **17.4 s** (`boot_progress_enable_screen` 17362 ms). Kiosk Satellite was in the foreground at uptime 47 s.

## Memory (≈3 min after boot)
| | |
|---|---|
| MemAvailable | 455 MB of 981 MB (swap in use 175 MB of 540 MB zram) |
| Kiosk Satellite PSS | 268 MB (RES 305 MB) |
| mediashell (5 processes) | ≈ 200 MB PSS |
| assistant.core | 20 MB |
| system_server | 61 MB |

## Rendering (Kiosk Satellite, idle screen, only 244 frames sampled)
Janky 30.7 %, p50 12 ms, p90 34 ms, p95 48 ms, p99 150 ms. The sample is too small to judge, and it was taken on WebView 74. A real dashboard scroll/interaction test is still to do.

## ⚠ Microphone path (affects voice quality more than CPU)
The active capture stream is **16 kHz, mono, `AUDIO_SOURCE_VOICE_COMMUNICATION`**, with two clients: Kiosk Satellite (uid 10061, VOICE_COMMUNICATION) and **Cast/mediashell** (uid 10048, MIC). That 16 kHz path goes through the MediaTek BesRecord resampler, which is known to corrupt audio (upstream issue #24). The HAL's signal‑power history shows a constant −11.4 dB for seconds, which is suspicious. **Wake‑word accuracy on this clock may be limited by corrupted audio rather than by CPU.** Fix candidates: open a 48 kHz stereo anchor stream before anyone else (MicAnchor approach), remove mediashell/assistant as mic clients, ask the developer whether Kiosk Satellite can capture at 48 kHz stereo and downsample itself.

## Wake-word test 1 — stock, Google stack running (2026-11-23 23:07)
**Result: 0 detections**, also after changing the wake word. No wake/pipeline events in the log (`investigation/perf/voice_test_baseline.log`).

Kiosk Satellite's `MicRecorder` diagnoses the cause itself: every format it tries (16 kHz mono mic, **48 kHz stereo mic**, 48 kHz mono, 16 kHz voice_recognition, 16 kHz voice_communication) "delivers ~595-619 % of the rate it was opened at (wrong format under the label)", and it periodically reads only zeros for 30 s. About 6x is 48 kHz stereo raw data labelled as 16 kHz mono, which is the MediaTek BesRecord bug. Even its 48 kHz stereo request fails, because the HAL input stream is already open at 16 kHz mono by another client (Cast/mediashell, uid 10048) and AudioFlinger shares that single HAL stream.

**Conclusion: voice is currently broken by the audio path, not by the CPU.** The CPU numbers above include Kiosk Satellite processing this corrupted audio and retrying.

## Wake-word test 2 — Cast disabled (2026-11-23 23:10)
Change made (approved): `pm disable-user --user 0 com.google.android.apps.mediashell` (undo: `adb shell pm enable com.google.android.apps.mediashell`). `am force-stop` alone was not enough: Cast restarted within a second and reopened the mic; Assistant (device owner) ignored force-stop but was not a mic client.

Result: Kiosk Satellite is now the **only** mic client, and capture is **still ~6x** in every format. Log: `investigation/perf/voice_after_cast_disabled.log`.

Root cause from the HAL log (`AudioALSACaptureHandlerNormal`, `AudioSPELayer`, `AF::RecordTrack`):
1. Kiosk Satellite's capture ladder starts with **16 kHz x1 `mic`**. That first open creates the HAL input stream at 16 kHz mono with BesRecord enabled (`mRecordSampleRate=48000` internally, SRC to 16 kHz), which is the buggy path.
2. On each retry it opens the new AudioRecord **before releasing the old one**, so AudioFlinger attaches the new 48 kHz stereo `RecordTrack` to the **same 16 kHz mono input thread** (`AudioIn_5E`). The HAL re-opens with `sample_rate=16000, num_channels=1` regardless.
3. Result: every format, including 48 kHz stereo, inherits the broken 16 kHz stream.
4. Separately, the echo-reference capture (`AudioALSACaptureDataProviderEchoRef`) floods `pcm_read() error -9`. That's MediaTek's hardware AEC reference, which is broken or absent here; Kiosk Satellite's own AEC (LocalVQE) doesn't depend on it.

Fix candidates (in order of preference):
- **Kiosk Satellite change (developer):** on MT8167 / this model, open **48 kHz x2 `mic` first**, and release the old AudioRecord *before* opening the next format. The app already detects "wrong format under the label", so this is a small change to the ladder.
- **Mic anchor:** a tiny service that opens 48 kHz stereo before Kiosk Satellite at boot (MicAnchor approach) so the shared HAL stream is created in the clean format. Worth testing; in the ROM it can live in KioskNav/DockBridge.
- Never let Cast/Assistant be mic clients (ROM removes them anyway).

## Wake-word test 3 — MicAnchor first, Cast disabled (2026-11-23 23:14)
Change made (approved): installed MicAnchor v1.0.0 (`com.micanchor`, CI-built release asset, SHA-256 `aea01419…c4e3`, matches GitHub's recorded digest; permissions RECORD_AUDIO, FOREGROUND_SERVICE, RECEIVE_BOOT_COMPLETED, WAKE_LOCK, no INTERNET). Granted RECORD_AUDIO. Undo: `adb uninstall com.micanchor`.

Ordering matters. Started while Kiosk Satellite already held the mic, the anchor just joined the 16 kHz stream. It worked when both were stopped and the anchor was started first (Kiosk Satellite relaunches as HOME about a second later):
- HAL input thread: **48000 Hz, 2 ch, AUDIO_SOURCE_MIC** (anchor uid 10068); Kiosk Satellite (uid 10061) attached through AudioFlinger's resampler.
- Kiosk Satellite: `capture delivers 98% of 16000 Hz` (was 595–619 %).
- **Result: wake word and commands worked "fully consistently"** (user, ~20 tries). Log: `investigation/perf/voice_test_micanchor.log` (wake chime and TTS playback events present).

CPU while listening, clean audio (30 s `top`):
| | Before (broken audio) | After (anchor) |
|---|---|---|
| Whole system | ≈ 35–40 % of 4 cores | **≈ 7.5 %** (24 % user + 5 % sys of 400 %) |
| Kiosk Satellite | 85 % of one core | **19 %** |
| Audio HAL + audioserver | 18 % + 13 % | 3 % + 2 % |

**Conclusion: the CPU is adequate.** The earlier load came from Kiosk Satellite processing and re-trying corrupted audio. With the right capture format the clock has plenty of headroom (Cast was also disabled, so part of the drop is Cast's ~9 %).

Not yet robust: after a reboot, whichever of MicAnchor or Kiosk Satellite opens the mic first decides the format. The ROM must guarantee the anchor wins (start it from init or a direct-boot-aware service before HOME starts, or have Kiosk Satellite open 48 kHz stereo first).

## Reboot test (2026-11-23 23:26)
`boot_completed` about 30 s after `adb reboot`. **Kiosk Satellite won the race**: it opened 16 kHz mono at 23:26:33.8; MicAnchor's BOOT_COMPLETED receiver only started at 23:26:39.0 → broken audio (599 %) after every reboot. Restarting only Kiosk Satellite did not help (it reattached to the poisoned 16 kHz thread). Stopping both and starting the anchor first restored clean audio ("99 % of 16000 Hz").

**Requirement for the ROM:** the anchor must hold the mic *before* the HOME app starts — e.g. a direct-boot-aware (`LOCKED_BOOT_COMPLETED`) persistent priv-app, or an `init` service, or the anchor delaying/restarting Kiosk Satellite once it holds the stream. Simplest permanent fix remains a Kiosk Satellite change (open 48 kHz stereo first on this device).

## Still to measure
- Wake‑word hit rate and latency over ~20 tries, then again with Cast/Assistant disabled and with a 48 kHz anchor.
- Dashboard interaction jank (scroll, open more‑info dialogs) on WebView 74 vs 156.
- Thermal behaviour during a 24 h run (thermal zones are readable).
