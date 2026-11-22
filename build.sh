#!/usr/bin/env bash
# Zero-Gradle build script for Parallax Live (Android SDK build-tools + JDK).
#
# Usage:
#   ./build.sh                                    # builds with procedural sample scene
#   ./build.sh OUT_DIR [HOME_BUNDLE] [LOCK_BUNDLE]
#
# Optional release signing via environment variables:
#   KEYSTORE=/path/to/release.jks KEYSTORE_PASS=... KEY_ALIAS=... [KEY_PASSWORD=...] ./build.sh
# KEY_PASSWORD defaults to KEYSTORE_PASS. Passwords are handed to apksigner through the
# environment, so they do not show up in the process list.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$(realpath -m "${1:-$HERE/out}")"
HOME_BUNDLE="${2:-}"
LOCK_BUNDLE="${3:-}"

# Locate Android SDK
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
if [ ! -d "$SDK/build-tools" ] || [ ! -d "$SDK/platforms" ]; then
  echo "error: Android SDK not found at $SDK (set ANDROID_SDK_ROOT)" >&2
  exit 1
fi
BT="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"
ANDROID_JAR="$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)/android.jar"

# Locate javac / keytool (PATH, JAVA_HOME, or Android Studio JBR)
if ! command -v javac >/dev/null 2>&1; then
  for cand in "${JAVA_HOME:-}" "$HOME/android-studio/jbr" /opt/android-studio/jbr; do
    if [ -n "$cand" ] && [ -x "$cand/bin/javac" ]; then
      export PATH="$cand/bin:$PATH"
      break
    fi
  done
fi
command -v javac >/dev/null 2>&1 || { echo "error: javac not found (install a JDK or set JAVA_HOME)" >&2; exit 1; }

# Generate procedural sample bundle + icon if no custom bundle was supplied
if [ -z "$HOME_BUNDLE" ]; then
  HOME_BUNDLE="$OUT/sample-bundle"
  python3 "$HERE/tools/make_sample_bundle.py" "$HOME_BUNDLE" "$HERE/res/drawable/ic_launcher.png"
fi
HOME_BUNDLE="$(realpath "$HOME_BUNDLE")"
LOCK_BUNDLE="$(realpath "${LOCK_BUNDLE:-$HOME_BUNDLE}")"

for b in "$HOME_BUNDLE" "$LOCK_BUNDLE"; do
  if [ ! -f "$b/bg.jpg" ] && [ ! -f "$b/bg.png" ]; then
    echo "error: bundle $b is missing bg.jpg / bg.png" >&2; exit 2
  fi
  if [ ! -f "$b/fg.webp" ] && [ ! -f "$b/fg.png" ]; then
    echo "error: bundle $b is missing fg.webp / fg.png" >&2; exit 2
  fi
done

rm -rf "$OUT/stage"
mkdir -p "$OUT/stage/classes" "$OUT/stage/dex" "$OUT/stage/assets/home" "$OUT/stage/res/drawable"
cp -r "$HERE/res/." "$OUT/stage/res/"

if [ -f "$HOME_BUNDLE/thumb.png" ]; then
  cp "$HOME_BUNDLE/thumb.png" "$OUT/stage/res/drawable/thumb.png"
elif [ -f "$HOME_BUNDLE/bg.png" ]; then
  cp "$HOME_BUNDLE/bg.png" "$OUT/stage/res/drawable/thumb.png"
else
  cp "$HOME_BUNDLE/bg.jpg" "$OUT/stage/res/drawable/thumb.jpg"
fi

# When both slots use the same bundle only "home" is packed; the app falls back to it for the lock screen.
SAME_BUNDLE=0
[ "$HOME_BUNDLE" = "$LOCK_BUNDLE" ] && SAME_BUNDLE=1
[ "$SAME_BUNDLE" = 1 ] || mkdir -p "$OUT/stage/assets/lock"

for f in bg.jpg bg.png fg.webp fg.png sway.png swing.png relief.png meta.json; do
  if [ -f "$HOME_BUNDLE/$f" ]; then cp "$HOME_BUNDLE/$f" "$OUT/stage/assets/home/$f"; fi
  if [ "$SAME_BUNDLE" = 0 ] && [ -f "$LOCK_BUNDLE/$f" ]; then cp "$LOCK_BUNDLE/$f" "$OUT/stage/assets/lock/$f"; fi
done

cd "$OUT/stage"

"$BT/aapt2" compile --dir res -o res.zip
"$BT/aapt2" link -o base.apk -I "$ANDROID_JAR" --manifest "$HERE/AndroidManifest.xml" \
  --min-sdk-version 29 --target-sdk-version 35 --version-code "$(date +%s)" --version-name 1.0.0 -A assets res.zip

mapfile -d '' SOURCES < <(find "$HERE/src" -name '*.java' -print0)
javac --release 8 -cp "$ANDROID_JAR" -d classes "${SOURCES[@]}"
mapfile -d '' CLASSES < <(find classes -name '*.class' -print0)
"$BT/d8" --release --min-api 29 --lib "$ANDROID_JAR" --output dex "${CLASSES[@]}"

python3 - <<'PYEOF'
import zipfile
with zipfile.ZipFile("base.apk", "a") as apk:
    apk.write("dex/classes.dex", "classes.dex")
PYEOF

"$BT/zipalign" -f 4 base.apk aligned.apk

KS="${KEYSTORE:-$HOME/.android/debug.keystore}"
export KS_PASS="${KEYSTORE_PASS:-android}"
export KEY_PASS="${KEY_PASSWORD:-$KS_PASS}"
KS_ALIAS="${KEY_ALIAS:-androiddebugkey}"
if [ ! -f "$KS" ]; then
  KS="$OUT/stage/debug.keystore"
  keytool -genkeypair -keystore "$KS" -storepass "$KS_PASS" -keypass "$KEY_PASS" -alias "$KS_ALIAS" \
    -dname "CN=Parallax Live Local,O=Local,C=US" -keyalg RSA -keysize 2048 -validity 10000 >/dev/null 2>&1
fi

"$BT/apksigner" sign --ks "$KS" --ks-pass env:KS_PASS --key-pass env:KEY_PASS --ks-key-alias "$KS_ALIAS" \
  --out "$OUT/parallax-live.apk" aligned.apk

"$BT/apksigner" verify "$OUT/parallax-live.apk"
echo "Built: $OUT/parallax-live.apk ($(du -h "$OUT/parallax-live.apk" | cut -f1))"
