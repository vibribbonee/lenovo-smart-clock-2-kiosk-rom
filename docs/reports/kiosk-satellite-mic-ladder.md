# Draft issue for jxlarrea/kiosk-satellite (not posted)

**Title:** Mic fallback ladder reopens before releasing, so on MT8167 (Lenovo Smart Clock 2) every retry inherits the first 16 kHz stream — even as the only mic client

Follow-up to #851 with new evidence.

### Summary
On the Smart Clock 2, Kiosk Satellite already detects the MediaTek format bug ("capture delivers ~600% … wrong format under the label") and walks its fallback ladder, including **48 kHz x2 mic**, which the hardware records cleanly. But every step still reports ~600%. The reason is not only another app holding the mic: **Kiosk Satellite opens the next `AudioRecord` before releasing the previous one**, so AudioFlinger attaches the new 48 kHz stereo track to the already-open 16 kHz mono input thread. The HAL stream is never reopened in the clean format.

### Evidence (stock 1.2.2.627, Kiosk Satellite 2026.11.24)
1. Cast receiver (`com.google.android.apps.mediashell`) disabled; Assistant was not a mic client. `dumpsys media.audio_policy` shows Kiosk Satellite (uid 10061) as the **only** record client.
2. Kiosk Satellite restarted. Its first open is `format=16000Hz x1 mic` → `capture delivers 599% of 16000 Hz` → `reopening at 48000Hz x2 mic` → `capture delivers 599% of 48000 Hz` → … → `no other capture format is left`.
3. The audio HAL log at the 48 kHz step shows the new track attached to the existing thread and the HAL reopened at the old config:
   ```
   AF::RecordTrack: mSampleRate 48000, mChannelCount 2, thread … AudioIn_5E   <- the 16 kHz mono thread
   AudioALSACaptureHandlerNormal: +open(), input_source = 0x1, sample_rate=16000, num_channels=1
   AudioALSACaptureDataProviderBase: detach() …   <- old client released only after the new one attached
   ```
4. Control: when a separate app (MicAnchor) opens 48 kHz x2 **first** and Kiosk Satellite starts afterwards, Kiosk Satellite logs `capture delivers 98% of 16000 Hz`, wake word works every time, and Kiosk Satellite's CPU drops from ~85% to ~19% of a core.

### Suggested change (small, generic, no device special-casing)
In the fallback ladder: **stop and release the current `AudioRecord` (and wait for the input to close) before opening the next format**, and when the rate check fails, try **48000 Hz x2 `MIC`** first and downsample in-app. On devices where the HAL stream is shared, release-before-reopen is what lets a new HAL configuration take effect; on others it's harmless.

### Environment
Lenovo Smart Clock 2 (CD-24502F), MT8167S, Android 10 build 1.2.2.627, Kiosk Satellite 2026.11.24 (versionCode 367). Full logs available.
