# Security notes for the current device state

## smartclock2tool (`pl.mateusz.clockadbprobe` v2.21.0) is installed
Source: [SychPL/smartclock2tool](https://github.com/SychPL/smartclock2tool), reviewed 2026‑11‑23. It is an owner/research tool, not malware, but it changes the device's security posture:

| Fact | Consequence |
|---|---|
| Runtime **kernel exploit** in the PowerVR driver writes `modprobe_path` and `selinux_enforcing` → root in `kernel_t`, **SELinux permissive** until reboot | While active, any process that reaches the root channel is root. The write can **panic the kernel**. |
| **ADB over Wi‑Fi with authentication disabled** (`ro.adb.secure=0`) when toggled on | Any host on the LAN gets `shell`, and `shell` can reach root via `run-as` (debug APK). If Home Assistant drives the dock over ADB, this is probably how it's connected today. |
| **HTTP control agent on port 8555** started at every boot by its BootReceiver (token‑protected; `/agent/exec`, file push/pull, dex plugins) | Always‑on LAN attack surface whose security rests on one token. |
| **AUTO ROOT** option re‑runs the exploit after each boot | If enabled, the clock re‑roots itself automatically every boot. |
| Report uploader: the README says the endpoint ships empty, but **the installed APK and the current source both contain `https://recon.robot.org.pl/`** | Upload is manual (button), but the README is wrong about it. Don't press "upload". |

Recommendations:
- Keep it as a **lab tool only**. It will not be part of the ROM.
- After Stage 1 (backup), uninstall it (needs your approval since it changes the device), or at minimum turn off AUTO ROOT and ADB Wi‑Fi when you aren't using them.
- In our ROM, Home Assistant gets dock control without unauthenticated ADB (see `DOCK.md`). If ADB over Wi‑Fi stays, it uses normal key authentication.

Useful side effect: with runtime root, `dd` from `/dev/block/by-name/*` gives a **second, independent backup path** that doesn't need BROM. We use it only as a cross‑check of the BROM dump (identical hashes = strong evidence the dumps are correct), and only with your approval, because the exploit can panic the kernel.

## Other observations
- Security patch level is 2020‑10‑05; the device should not be exposed to untrusted networks either way.
- `assistant.core` is Device Owner on stock; in our ROM there is no Google Device Owner.
