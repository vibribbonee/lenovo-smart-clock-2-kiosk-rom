#!/usr/bin/env python3
"""Gets and verifies every APK the ROM needs, into rom/prebuilt/ and out/.

  fetch_inputs.py kiosk                 latest Kiosk Satellite (armeabi-v7a) from its GitHub releases
  fetch_inputs.py keyboard              Simple Keyboard from F-Droid
  fetch_inputs.py webview [--apk FILE] [--from-device] [--skip]
                                        Google Android System WebView: a file you downloaded (APKMirror),
                                        or pulled from the clock over adb, or skipped (keeps stock WebView 74)
  fetch_inputs.py ownapps               ClockBridge, KioskNav, overlay from this project's GitHub release
                                        (only needed without the Android SDK)
  fetch_inputs.py check                 verify everything the build needs is present and valid
Every APK is checked: package name, signing certificate, armeabi-v7a code, Android 10 compatibility.
"""
import glob, json, os, shutil, subprocess, sys, tempfile, urllib.request

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import apkcheck  # noqa: E402

PREBUILT = os.path.join(ROOT, "rom", "prebuilt")
UA = {"User-Agent": "kioskclock-rom-builder"}
REPO = "vibribbonee/lenovo-smart-clock-2-kiosk-rom"

APKS = {
    "kiosk": dict(file="KioskSatellite.apk", package="me.jxl.kiosk_satellite",
                  cert="2a77134ce11f65ba9d08bed669fdcb742a25633c5665a6135aeca30040b068fc"),
    "keyboard": dict(file="SimpleKeyboard.apk", package="rkr.simplekeyboard.inputmethod",
                     cert="9bf99d5cbac7ae0169c8aae03e2b0979464a0787842e3e14d2b5e1fdf6c4680c"),
    "webview": dict(file="GoogleWebView.apk", package="com.google.android.webview",
                    cert="6faf3c4140407473400934d117815a21af1cfefc5c0bee61c858bc3d72ba6fe5"),
}
OWN_CERT = "cd2a24e18ae6346eee70813a1001bb1febb401a384c52cc085e966c9303e4026"
OWN = {"ClockCore.apk": ("org.kioskclock.core", "out/apps"),
       "ClockBridge.apk": ("org.kioskclock.bridge", "out/apps"),
       "KioskNav.apk": ("org.kioskclock.nav", "out/apps"),
       "KioskFrameworkOverlay.apk": ("org.kioskclock.overlay.framework", "out/overlays")}

WEBVIEW_HELP = """
The clock's own WebView (Chromium 74, 2019) is too old for modern dashboards, and Google's WebView
may not be redistributed, so you supply it. Download it from APKMirror:

  https://www.apkmirror.com/apk/google-inc/android-system-webview/

  - pick the latest STABLE release (not Beta / Dev / Canary)
  - variant: armeabi-v7a, nodpi, minimum Android 10 (API 29) or lower
  - the plain .apk (not an .apkm bundle, not "Trichrome")

Then run:  ./clockrom.sh build --webview /path/to/the.apk
(or, if your clock already has a newer WebView installed and is connected over adb: --webview-from-device)
"""


def say(msg): print(msg, flush=True)


def get(url, dst=None):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=60) as r:
        if dst is None:
            return r.read()
        with open(dst, "wb") as f:
            shutil.copyfileobj(r, f, 1 << 20)


def verify(kind, path, quiet=False):
    spec = APKS[kind]
    ok, info, problems = apkcheck.check(path, spec["package"], spec["cert"], "armeabi-v7a", 29)
    if not ok:
        return False, info, problems
    if not quiet:
        say(f"  ✓ {spec['package']} {info.get('versionName')} ({os.path.getsize(path) // 2**20} MB, signer verified)")
    return True, info, []


def install(kind, src):
    os.makedirs(PREBUILT, exist_ok=True)
    dst = os.path.join(PREBUILT, APKS[kind]["file"])
    if os.path.abspath(src) != os.path.abspath(dst):
        shutil.copyfile(src, dst)
    return dst


def fetch_kiosk():
    say("Kiosk Satellite: checking the latest GitHub release …")
    rel = json.loads(get("https://api.github.com/repos/jxlarrea/kiosk-satellite/releases/latest"))
    asset = next((a for a in rel["assets"] if a["name"].endswith(".armeabi-v7a.apk")), None)
    if not asset:
        sys.exit("No armeabi-v7a APK in the latest Kiosk Satellite release.")
    current = os.path.join(PREBUILT, APKS["kiosk"]["file"])
    if os.path.exists(current):
        ok, info, _ = verify("kiosk", current, quiet=True)
        if ok and info.get("versionName") == rel["tag_name"]:
            say(f"  ✓ already have {rel['tag_name']}")
            return
    with tempfile.TemporaryDirectory() as t:
        p = os.path.join(t, asset["name"])
        say(f"  downloading {asset['name']} ({asset['size'] // 2**20} MB)")
        get(asset["browser_download_url"], p)
        ok, _, problems = verify("kiosk", p)
        if not ok:
            sys.exit("Downloaded Kiosk Satellite failed verification: " + "; ".join(problems))
        install("kiosk", p)


def fetch_keyboard():
    say("Simple Keyboard: checking F-Droid …")
    meta = json.loads(get("https://f-droid.org/api/v1/packages/rkr.simplekeyboard.inputmethod"))
    vc = meta["suggestedVersionCode"]
    current = os.path.join(PREBUILT, APKS["keyboard"]["file"])
    if os.path.exists(current):
        ok, info, _ = verify("keyboard", current, quiet=True)
        if ok and info.get("versionCode") == vc:
            say(f"  ✓ already have versionCode {vc}")
            return
    with tempfile.TemporaryDirectory() as t:
        p = os.path.join(t, "kb.apk")
        get(f"https://f-droid.org/repo/rkr.simplekeyboard.inputmethod_{vc}.apk", p)
        ok, _, problems = verify("keyboard", p)
        if not ok:
            sys.exit("Downloaded Simple Keyboard failed verification: " + "; ".join(problems))
        install("keyboard", p)


def webview(args):
    current = os.path.join(PREBUILT, APKS["webview"]["file"])
    if "--skip" in args:
        if os.path.exists(current):
            os.unlink(current)
        say("WebView: skipped — the ROM keeps the stock WebView 74 (modern dashboards may render badly).")
        return
    src = None
    if "--apk" in args:
        src = os.path.expanduser(args[args.index("--apk") + 1])
    elif "--from-device" in args:
        out = subprocess.run(["adb", "shell", "pm", "path", "com.google.android.webview"], capture_output=True, text=True).stdout
        paths = [l.split(":", 1)[1].strip() for l in out.splitlines() if l.startswith("package:")]
        if not paths:
            sys.exit("The connected clock has no Google WebView installed." + WEBVIEW_HELP)
        tmp = tempfile.mkdtemp()
        src = os.path.join(tmp, "webview.apk")
        say(f"WebView: pulling {paths[0]} from the clock …")
        subprocess.run(["adb", "pull", paths[0], src], check=True, capture_output=True)
    else:
        # Accept a matching APK dropped into the project folder or rom/prebuilt.
        cands = sorted(glob.glob(os.path.join(ROOT, "*webview*.apk")) + glob.glob(os.path.join(ROOT, "*WebView*.apk")),
                       key=os.path.getmtime, reverse=True)
        if cands:
            src = cands[0]
        elif os.path.exists(current):
            ok, _, _ = verify("webview", current)
            if ok:
                return
    if not src:
        sys.exit("WebView: no APK given." + WEBVIEW_HELP)
    ok, info, problems = verify("webview", src)
    if not ok:
        sys.exit("WebView: this file can't be used: " + "; ".join(problems) + "\n" + WEBVIEW_HELP)
    install("webview", src)


def ownapps():
    say("Project apps: downloading the prebuilt ClockBridge / KioskNav / overlay from the latest release …")
    rel = json.loads(get(f"https://api.github.com/repos/{REPO}/releases/latest"))
    for name, (pkg, outdir) in OWN.items():
        a = next((x for x in rel["assets"] if x["name"] == name), None)
        if not a:
            sys.exit(f"{name} missing from release {rel['tag_name']}")
        d = os.path.join(ROOT, outdir)
        os.makedirs(d, exist_ok=True)
        p = os.path.join(d, name)
        get(a["browser_download_url"], p)
        ok, info, problems = apkcheck.check(p, pkg, OWN_CERT)
        if not ok:
            sys.exit(f"{name} failed verification: " + "; ".join(problems))
        say(f"  ✓ {pkg} {info.get('versionName')}")


def check_all():
    missing = []
    for kind, spec in APKS.items():
        p = os.path.join(PREBUILT, spec["file"])
        if not os.path.exists(p):
            if kind == "webview":
                say("  ! no Google WebView: the ROM will keep stock WebView 74")
                continue
            missing.append(spec["file"])
            continue
        ok, _, problems = verify(kind, p)
        if not ok:
            sys.exit(f"{spec['file']}: " + "; ".join(problems))
    for name, (pkg, outdir) in OWN.items():
        p = os.path.join(ROOT, outdir, name)
        if not os.path.exists(p):
            missing.append(f"{outdir}/{name}")
    if missing:
        sys.exit("Missing inputs: " + ", ".join(missing) + " (run ./clockrom.sh build, which fetches them)")


if __name__ == "__main__":
    cmd, args = (sys.argv[1] if len(sys.argv) > 1 else "check"), sys.argv[2:]
    cmds = {"kiosk": fetch_kiosk, "keyboard": fetch_keyboard, "webview": lambda: webview(args),
            "ownapps": ownapps, "check": check_all}
    if cmd not in cmds:
        sys.exit(__doc__)
    cmds[cmd]()
