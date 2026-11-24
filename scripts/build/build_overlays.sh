#!/usr/bin/env bash
# Builds static resource overlays (rom/overlays/<Name>) into out/overlays/<Name>.apk.
# Code-free APKs: aapt2 compile + link against android.jar, zipalign, sign with the project key.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SDK="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/34.0.0"
JAR="$SDK/platforms/android-29/android.jar"
export JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home}"
KS="$ROOT/keys/kioskclock.jks"; PASSFILE="$ROOT/keys/keystore.pass"
[ -f "$KS" ] || { echo "no signing key; run scripts/build/build_apps.sh once first"; exit 1; }
mkdir -p "$ROOT/out/overlays"
for dir in "$ROOT"/rom/overlays/*/; do
  name="$(basename "$dir")"; b="$ROOT/out/build/overlay-$name"
  rm -rf "$b"; mkdir -p "$b"
  "$BT/aapt2" compile --dir "$dir/res" -o "$b/res.zip"
  "$BT/aapt2" link -I "$JAR" --manifest "$dir/AndroidManifest.xml" -o "$b/unsigned.apk" "$b/res.zip"
  "$BT/zipalign" -p -f 4 "$b/unsigned.apk" "$b/aligned.apk"
  "$BT/apksigner" sign --ks "$KS" --ks-pass "file:$PASSFILE" --ks-key-alias kioskclock \
    --out "$ROOT/out/overlays/$name.apk" "$b/aligned.apk"
  echo "built out/overlays/$name.apk"
done
