# Contributing

Thanks for helping! A few things that keep this project safe and maintainable:

## Ground rules
- **No Lenovo or Google binaries** in the repository, ever (firmware, APKs, images). Everyone builds from a backup of their own clock; that's what keeps the repo legal.
- **Never commit secrets or personal device data**: `keys/`, `backups/`, `firmware/`, tokens, Wi-Fi details, serials, real IP addresses (use `192.0.2.x` in tests and docs). `.gitignore` covers the folders; check your diff anyway.
- **Flashing changes must stay recoverable.** Anything that writes to the clock goes through `scripts/flash/mtk_session.sh` and needs the user to type a confirmation word.

## Building and testing
- Apps: `scripts/build/build_apps.sh` (Android SDK platform 29, build-tools 34, Java 21). No anonymous or non-static inner classes: d8 34.0.0 crashes on them (see the script's header).
- Whole ROM: `./clockrom.sh build` (needs your own backup).
- Home Assistant integration: `pip install pytest-homeassistant-custom-component`, then `python -m pytest tests/ha -c tests/ha/pytest.ini --rootdir .`. CI runs the same tests plus hassfest and HACS validation.

## Changes on the clock
- Privileged permissions an app requests must be listed in `rom/product/etc/permissions/privapp-permissions-kioskclock.xml`, or Android refuses to boot. `scripts/build/check_privapp.py` checks it during the build.
- Every release ships ClockBridge and KioskNav with `versionName` = the release version (and a higher `versionCode`), even when only docs or the integration changed: clocks only install a higher version, and Home Assistant's update entity compares the clock's ClockBridge version with the latest release tag. Never move or rewrite a published tag; make a new release.
- ClockBridge and KioskNav update over Wi-Fi; ClockCore, overlays and `rom/` changes need a flash (mark those releases **flash** in `CHANGELOG.md`).
- Setup defaults live in `rom/product/etc/kioskclock/provision.json`. Bump `version` to re-run it on existing clocks; a re-run only fills in settings that are still unset.

## Pull requests
Small and focused, with what you tested on a real clock (or that you couldn't). Docs in plain language: describe what the user sees and does.
