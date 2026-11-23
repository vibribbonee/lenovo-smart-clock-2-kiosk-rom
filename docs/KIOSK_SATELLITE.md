# Kiosk Satellite

The brief mentions a `satellite/` directory; it **does not exist**. Supplied: `kiosk-satellite-2026.11.29.apk` (SHA‑256 `7c74d574…e189`, 258,546,576 bytes). Installed on the device: `me.jxl.kiosk_satellite` **2026.11.24** (versionCode 367) — the local file is newer than what's installed.

## What it is [F/V]
- **Flutter** app (`libflutter.so`, `libapp.so`), package `me.jxl.kiosk_satellite`, minSdk 24, targetSdk 36, APK signature scheme v2+ (one signer).
- Universal APK: `arm64-v8a`, `armeabi-v7a`, `x86_64` native libs, **stored uncompressed** (runs in place). armv7 libs total 66 MB; the rest of the 247 MB is unused ABIs, ML models (ONNX runtime, MediaPipe hand landmarker, BlazeFace, ML Kit barcode, TFLite), and an AEC model (`localvqe` gguf).
- Web rendering: `flutter_inappwebview` → **system WebView** (+ AndroidX WebKit).
- Components: `MainActivity` (LAUNCHER + LEANBACK), **`HomeAlias` (HOME)**, `BootReceiver` (BOOT_COMPLETED, QUICKBOOT_POWERON), `KioskAdminReceiver` (device admin, `isDeviceOwner` checks), `KioskAccessibilityService`, `HeadlessKeyService` (accessibility), `MediaSessionListener` (notification listener), Shizuku plugin, self‑update (`REQUEST_INSTALL_PACKAGES`, ADB/Shizuku "update helper" via `app_process`), voice wake (`VOICE_WAKE`), SendSpin media, camera/proximity (`/sys/class/st_psensor/psensor_value`), brightness control, `hide_navigation_bar` option.
- No Google Play Services classes required for core function (ML Kit barcode is bundled/unbundled model — verify it doesn't try GMS download path).
- Current runtime: 229.6 MB PSS (largest process on the device).

## Permissions of interest
Runtime (granted now, lost on reset unless pre‑granted): CAMERA, RECORD_AUDIO, location, storage. Special access: SYSTEM_ALERT_WINDOW, WRITE_SETTINGS, PACKAGE_USAGE_STATS, notification listener, accessibility services, battery‑optimisation exemption, `UPDATE_PACKAGES_WITHOUT_USER_ACTION`.

## Recommendation: system app (non‑privileged) in `/product/app`
- Survives factory reset; self‑updates continue to work (installed as an update over the system copy — same signer).
- Runtime permissions pre‑granted with `default-permissions` XML (only those you approve).
- Special accesses (overlay, write settings, notification listener, accessibility) set by the provisioner on first boot.
- Not privileged: it doesn't need signature/privileged permissions, and keeping it unprivileged limits blast radius.
- Sole HOME app → automatic start and crash recovery by the framework.

## Answers so far (2026‑11‑23)
- Main use: **voice** (wake word + satellite). The clock has **no camera** → the camera/hand‑landmark/face/barcode models are dead weight (≈30 MB of ML libs + models stay on disk but should never load; verify they don't at runtime).
- Voice needs clean 16 kHz capture, which the MediaTek audio HAL corrupts unless a 48 kHz stereo stream is opened first (upstream issue #24). Removing `assistant.core` stops it from grabbing the mic first. Kiosk Satellite bundles its own AEC (`localvqe`) and wake (`libkiosk_wake_fft`, ONNX) — check which capture format it requests; if 16 kHz mono, we add a "mic anchor" (48 kHz stereo holder, à la MicAnchor) to KioskNav/DockBridge or ask the developer for a 48 kHz capture option.
- Kiosk Satellite already has device integrations for the Echo builds: it binds `com.amazon.biscuit.service.IBiscuitService` (LED ring, volume buttons, mic mute) and `me.jxl.echo.service.IEchoService` (system updates). A **Lenovo dock integration in the same style** (Kiosk Satellite binds our DockBridge, exposes "dock light" and "phone charging" entities to Home Assistant) is the cleanest end state — worth asking the developer about.

## Questions for you / the developer
1. Which features do you actually use (voice wake, camera/hand gestures, barcode, SendSpin)? Unused ML features cost RAM/CPU on this 1 GB device.
2. Is an armeabi‑v7a‑only build available (saves ≈150 MB in `product`)?
3. How is configuration stored, and can it be provisioned/imported after a reset (file, URL, QR)? This decides whether "persistent configuration" needs a ROM‑side default file.
4. Does it already provide a plugin hook we should use for dock control (local HTTP, intent, AIDL)?
