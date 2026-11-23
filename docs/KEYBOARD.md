# Keyboard

## Finding
The `apk/` directory described in the brief **does not exist**. The project folder contains only `F-Droid.apk` (the F‑Droid store client 2.x, signed by F‑Droid's key `43:23:8D:51…CC:AB`, multi‑ABI) and `kiosk-satellite-2026.11.29.apk`. **No keyboard APK was supplied.**

The device already runs **Simple Keyboard** (`rkr.simplekeyboard.inputmethod` 6.8, versionCode 149, from F‑Droid) as the active IME:
- `default_input_method=rkr.simplekeyboard.inputmethod/.latin.LatinIME` [V]
- Requests only `android.permission.VIBRATE` — **no INTERNET**, fully offline [V]
- minSdk 24, pure Java/Kotlin (no native libs → ABI‑independent) [V]
- 4.2 MB PSS when loaded [V]
- No Google services dependency (F‑Droid build).

Simple Keyboard is a good fit for an 800×480 hdpi kiosk: small, no suggestions engine, adjustable key height, theme support. **Please confirm this is the keyboard you meant**, or supply the intended APK.

## Integration plan
1. Ship the F‑Droid‑signed APK in `/product/app/SimpleKeyboard/` (survives reset; F‑Droid updates still install over it because the signature matches).
2. Make it the default after reset:
   - With only one system IME present Android selects it automatically (`InputMethodUtils` picks enabled system IMEs matching the locale). Verify after reset.
   - Fallback: the first‑boot provisioner sets `enabled_input_methods` / `default_input_method`.
3. Key Mapper's IME (`KeyMapperImeService`) is currently also enabled; it will not be in the ROM.

## Tests (see TEST_PLAN K‑*)
Text field in Kiosk Satellite native UI, WebView `<input>`, `<textarea>`, `type=password` (no suggestions, no learning), `inputmode=numeric`, dismiss via keyboard key and via Back from the nav helper, landscape 800×480 layout height vs. WebView resize (`adjustResize`), works with Wi‑Fi disconnected.
