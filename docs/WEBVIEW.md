# WebView

## Current state [V]
- `dumpsys webviewupdate`: only valid provider is **`com.android.webview` 74.0.3729.186** (AOSP, 2019). Fallback logic off, multiprocess off. Minimum versionCode 373018605.
- `com.google.android.webview` **156.0.8078.50** (armeabi‑v7a, minSdk 29, targetSdk 36) is installed in `/data/app` but **not listed** as a valid provider, because the framework's `config_webview_packages` only allows `com.android.webview`. AnyWebView (an LSPosed hook) is installed but cannot work without root.
- Kiosk Satellite renders through `flutter_inappwebview` → **system WebView**. With Chromium 74 most modern dashboards (CSS nesting, `:has()`, container queries, modern JS) fail or degrade.

## Requirements check
| Requirement | Why it matters here |
|---|---|
| API 29 | Chrome/WebView ≥139 require Android 10 (API 29) — supported today; the next cut‑off (Android 10 → 11) has not been announced but **will come**. The version current at that time will be the last for this device. |
| ABI armeabi‑v7a | 32‑bit only device. Need a 32‑bit (or "trichrome/monochrome" 32‑bit) build. |
| Signature | The provider list entry contains the package's signing certificate; the framework rejects a package whose signature does not match. |
| No GMS | Google WebView runs without GMS (Safe Browsing and some telemetry silently disabled). Verified indirectly: 156 is installed and passes the package checks (it is just not whitelisted). |

## Options
| Option | Maintained | 32‑bit ARM | GMS needed | Verdict |
|---|---|---|---|---|
| **Google Android System WebView** (`com.google.android.webview`, Google‑signed) | Yes (stable channel every ~4 weeks) | Yes (156 installed and running on this device as an app) | No | **Recommended** |
| Cromite SystemWebView | Yes | README lists only arm64/x64 WebView builds | No | Not usable (no armv7 WebView) — re‑check per release |
| Mulch WebView (DivestOS) | **Discontinued** (Dec 2024, repo archived 2025) | — | — | Reject |
| Bromite SystemWebView | Dead since 2023 | — | — | Reject (upstream guide used it; obsolete) |
| Vanadium | GrapheneOS only, arm64 | No | — | Reject |
| AOSP `com.android.webview` rebuilt | Would need our own Chromium build for armv7 + signing with the stock key we don't have | — | — | Not feasible |

## Recommended design
1. **Framework RRO overlay** in `/product/overlay/KioskWebViewOverlay.apk` that overrides `xml/config_webview_packages` with:
   - `com.google.android.webview` (Google release cert SHA‑256 to be extracted from the APK we ship), `availableByDefault=true`
   - `com.android.webview` as fallback (`isFallback` not set — avoid it being force‑disabled).
   This is exactly how the community "bromite‑webview‑overlay" Magisk module worked on this device, so the mechanism is proven on this build; we just ship it in the image instead of via Magisk.
2. Ship a pinned, tested Google WebView APK in `/product/app/GoogleWebView/` so it **survives factory reset**.
3. **Updates without ROM rebuild:** `adb install -r` (or a Kiosk Satellite/F‑Droid/Aurora update) of a newer Google‑signed WebView installs as an update over the system copy. Factory reset rolls back to the ROM version — acceptable and predictable.
4. Set `webview_provider` via provisioning only if the framework does not pick it automatically (it will choose the first valid provider in the list).
5. Keep Kiosk Satellite's own update channel independent.

## Version pinning / testing
- Start with 156.0.8078.50 (already proven to install on this device). Before baking, verify: JS, CSS (`:has`, nesting, container queries), cookies/localStorage/IndexedDB persistence across reboot, local network HTTP (cleartext to LAN host — check Kiosk Satellite's network security config), WebGL / hardware acceleration (`chrome://gpu` via WebView DevTools), input/keyboard, memory (renderer RSS).
- Record the exact APK SHA‑256 and cert in `rom/manifest.yaml`.
- Watch for the Chrome Android 10 deprecation announcement; when it comes, freeze the last supported version.

## Open questions
- Google WebView APK source: APKMirror (Google‑signed; verify cert fingerprint) vs extracting the 156 already installed on the device (`adb pull` of base.apk — **preferred**, it's the one proven to work; check whether it is a split/monochrome bundle).
- Whether 32‑bit WebView builds will continue as long as Android 10 support (Chromium still ships armeabi‑v7a for Android Go devices).
