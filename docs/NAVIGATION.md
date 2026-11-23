# Navigation

## Key finding: there is no SystemUI on this firmware [V]
- `pm list packages -u` contains **no `com.android.systemui`** and no AOSP launcher/Quickstep.
- `qemu.hw.mainkeys=1` → the framework does not reserve a navigation bar at all.
- The `statusbar` binder service exists in system_server, but no SystemUI client is registered.
- The current "swipe from the edge" behaviour comes from **`com.google.assistant.core`**: it owns two overlay windows, a 12‑px strip at the bottom (`[0,468][800,480]`) and a 1‑px window at the top. Removing assistant.core removes those gestures.

### Why Android 10 native gesture navigation cannot simply be enabled
Android 10 gesture navigation is split between **SystemUI** (`NavigationBarFragment`, `EdgeBackGestureHandler`, `OverviewProxyService`) and **Launcher3 Quickstep** (`TouchInteractionService` for swipe‑up‑home/recents). Both are missing. Setting `qemu.hw.mainkeys=0` or changing `navigation_mode` overlays would do nothing without them.

Porting AOSP 10 SystemUI would mean building it against this MTK/Lenovo‑modified framework (framework.jar contains vendor additions), signing it with the platform key (we don't have Lenovo's), and giving it platform permissions — high effort and high risk of boot loops. **Rejected for the stock‑based ROM.**

(A phh Android 12 Go GSI reportedly boots on this device [XDA, Feb 2026] and has real SystemUI; see IMPLEMENTATION_PLAN "Alternative B" for why that is not the primary plan.)

## Recommended design: small dedicated "Kiosk Nav" system app
A ~100 KB app, separate from Kiosk Satellite so it keeps working when the kiosk crashes:
- **AccessibilityService** with a `TYPE_ACCESSIBILITY_OVERLAY` edge strip (~12 px, bottom edge, configurable). Accessibility overlays don't need `SYSTEM_ALERT_WINDOW` and sit above normal apps.
- **Swipe up from the bottom** → a translucent button bar slides in with **Back · Home · Settings** (optionally Wi‑Fi and Volume/Brightness); auto‑hides after a few seconds or on outside touch. This matches the requested "buttons appear on swipe from bottom".
- Back → `performGlobalAction(GLOBAL_ACTION_BACK)`; Home → `GLOBAL_ACTION_HOME` (both handled by system_server without SystemUI). Recents → *not available* (needs SystemUI) — deliberately omitted.
- Settings → `startActivity(Settings.ACTION_SETTINGS)`; optionally guarded by a long‑press or PIN so casual users don't land in Settings.
- Optional volume/brightness mini‑panel to replace the assistant's volume UI (XDA: after removing assistant the volume keys lose their default behaviour).
- Installed as a **privileged system app** in `/product/priv-app/KioskNav/` with `WRITE_SECURE_SETTINGS` (privapp-permissions XML) so it can (re)enable its own accessibility service on first boot and after a reset — no ADB needed.
- Safety: if the service crashes, Android restarts it; Home also always returns to Kiosk Satellite (the only HOME app).

Existing third‑party alternatives considered: VirtualSoftKeys (used by upstream guide; overlay‑permission based, unmaintained since ~2020), Key Mapper (good for buttons, not for an edge gesture bar). A purpose‑built app is small and auditable; we can start the PoC with VirtualSoftKeys to validate the UX before writing code.

## Reliability rules
- Kiosk Nav must never be the HOME app.
- Kiosk Satellite's `hide_navigation_bar`/immersive settings don't conflict (there is no nav bar), but verify edge strip does not steal swipes needed by dashboards (keep the strip thin; require a minimum upward distance).
- A physical fallback: long‑press of Vol‑ (or Vol+ & Vol−) → Settings, via the same accessibility service's key filtering (`FLAG_REQUEST_FILTER_KEY_EVENTS`). To be decided.
