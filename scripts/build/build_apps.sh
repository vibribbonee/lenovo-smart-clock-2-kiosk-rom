#!/usr/bin/env bash
# Builds the project's own apps (apps/<Name>) without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Output: out/apps/<Name>.apk. Signing key: keys/kioskclock.jks (created on first run, git-ignored).
# Keep keys/ backed up: future updates of the installed apps must be signed with the same key.
# Code rule: no anonymous or non-static inner classes (use lambdas or static nested classes).
# javac 21 writes a MethodParameters entry without a name for inner-class constructors, and
# d8 from build-tools 34.0.0 crashes on it (NullPointerException in String.length()).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SDK="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/34.0.0"
JAR="$SDK/platforms/android-29/android.jar"
export JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home}"
export PATH="$JAVA_HOME/bin:$PATH"
KEYS="$ROOT/keys"; KS="$KEYS/kioskclock.jks"; PASSFILE="$KEYS/keystore.pass"
mkdir -p "$KEYS" "$ROOT/out/apps"
chmod 700 "$KEYS"

if [ ! -f "$KS" ]; then
  umask 077
  openssl rand -hex 24 > "$PASSFILE"
  keytool -genkeypair -keystore "$KS" -storepass:file "$PASSFILE" -keypass:file "$PASSFILE" \
    -alias kioskclock -keyalg RSA -keysize 4096 -validity 10000 \
    -dname "CN=Kiosk Clock ROM, O=Local build" >/dev/null 2>&1
  echo "Created signing key $KS (back it up together with $PASSFILE)."
fi

build() {
  local name="$1" src="$ROOT/apps/$1" b="$ROOT/out/build/$1"
  rm -rf "$b"; mkdir -p "$b/gen" "$b/classes" "$b/dex"
  # ClockBridge carries a copy of the ROM's provision.json (newer defaults arrive with app updates).
  local res="$src/res"
  if [ "$name" = ClockBridge ]; then
    res="$b/res"; cp -R "$src/res" "$res"; mkdir -p "$res/raw"
    cp "$ROOT/rom/product/etc/kioskclock/provision.json" "$res/raw/provision.json"
  fi
  "$BT/aapt2" compile --dir "$res" -o "$b/res.zip"
  "$BT/aapt2" link -I "$JAR" --manifest "$src/AndroidManifest.xml" -o "$b/unsigned.apk" \
    --java "$b/gen" "$b/res.zip"
  find "$src/src" "$b/gen" -name '*.java' > "$b/sources.txt"
  javac --release 11 -classpath "$JAR" -d "$b/classes" -Xlint:-options @"$b/sources.txt"
  "$BT/d8" --release --min-api 29 --lib "$JAR" --output "$b/dex" $(find "$b/classes" -name '*.class')
  (cd "$b/dex" && zip -q -X "$b/unsigned.apk" classes.dex)
  "$BT/zipalign" -p -f 4 "$b/unsigned.apk" "$b/aligned.apk"
  "$BT/apksigner" sign --ks "$KS" --ks-pass "file:$PASSFILE" --ks-key-alias kioskclock \
    --out "$ROOT/out/apps/$name.apk" "$b/aligned.apk"
  "$BT/apksigner" verify "$ROOT/out/apps/$name.apk"
  echo "built out/apps/$name.apk  sha256=$(shasum -a 256 "$ROOT/out/apps/$name.apk" | cut -c1-16)"
}

for app in "${@:-ClockCore ClockBridge KioskNav}"; do
  for a in $app; do build "$a"; done
done
