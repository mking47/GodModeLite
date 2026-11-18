#!/bin/bash
# Universal minimal APK builder: aapt2 + javac + dx + zipalign + apksigner
set -e
ROOT="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_SDK:-/usr/lib/android-sdk}"
BT="$SDK/build-tools/29.0.3"
AAPT2="${AAPT2:-$BT/aapt2}"
DX="${DX:-$BT/dx}"
AJAR="$SDK/platforms/android-23/android.jar"
ZIPALIGN="$BT/zipalign"
APKSIGNER="$BT/apksigner"
KS="${KEYSTORE:-$ROOT/debug.keystore}"

PROJ="$1"
OUT="$2"
if [ -z "$PROJ" ] || [ -z "$OUT" ]; then echo "usage: build.sh <project-dir> [output.apk]"; exit 1; fi
PROJ="$(cd "$PROJ" && pwd)"
[ -z "$OUT" ] && OUT="$PROJ/build/app.apk"
BUILD="$PROJ/build"
rm -rf "$BUILD"
mkdir -p "$BUILD/res" "$BUILD/gen" "$BUILD/classes"

echo "[0/6] prepare Xposed API (compile-time only, NOT packaged)"
XPOSED_CLASSPATH=""
REAL_API="$ROOT/xposed-api/api-82.jar"
if [ -f "$REAL_API" ]; then
  XPOSED_CLASSPATH="$REAL_API"
  echo "using real Xposed API: $REAL_API"
elif [ -d "$ROOT/xposed-stubs" ]; then
  mkdir -p "$BUILD/stubs"
  find "$ROOT/xposed-stubs" -name '*.java' > "$BUILD/stub_sources.txt"
  javac -source 8 -target 8 -encoding UTF-8 -d "$BUILD/stubs" @"$BUILD/stub_sources.txt"
  (cd "$BUILD/stubs" && jar cf "$BUILD/xposed-api-stubs.jar" .)
  XPOSED_CLASSPATH="$BUILD/xposed-api-stubs.jar"
  echo "using compile-time Xposed stubs"
else
  echo "no Xposed API available"
fi

echo "[1/6] aapt2 compile resources"
if [ -d "$PROJ/res" ] && [ -n "$(ls -A "$PROJ/res" 2>/dev/null)" ]; then
  "$AAPT2" compile --dir "$PROJ/res" -o "$BUILD/res.zip"
else
  echo "no res dir"
fi

echo "[2/6] aapt2 link"
LINK_ARGS=(-o "$BUILD/base.apk" -I "$AJAR" --manifest "$PROJ/AndroidManifest.xml"
  --java "$BUILD/gen" --min-sdk-version 21 --target-sdk-version 28
  --version-code "${VERSION_CODE:-1}" --version-name "${VERSION_NAME:-1.0}")
if [ -d "$PROJ/assets" ]; then LINK_ARGS+=(-A "$PROJ/assets"); fi
if [ -f "$BUILD/res.zip" ]; then LINK_ARGS+=("$BUILD/res.zip"); fi
"$AAPT2" link "${LINK_ARGS[@]}"

echo "[3/6] javac"
SRC_LIST="$BUILD/sources.txt"
find "$PROJ/src" "$BUILD/gen" -name '*.java' > "$SRC_LIST" 2>/dev/null || true
CLASSPATH_EXTRA="$XPOSED_CLASSPATH"
if ! javac -source 8 -target 8 -bootclasspath "$AJAR" -classpath "$CLASSPATH_EXTRA" -encoding UTF-8 \
  -d "$BUILD/classes" @"$SRC_LIST" > "$BUILD/javac.log" 2>&1; then
  echo "==== javac FAILED ===="
  grep -v "bootstrap class path" "$BUILD/javac.log" | head -60
  exit 1
fi
grep -i "warning: \[options\]" "$BUILD/javac.log" | head -3 || true

echo "[4/6] dx -> classes.dex"
"$DX" --dex --min-sdk-version=21 --output="$BUILD/classes.dex" "$BUILD/classes"

echo "[5/6] package"
cp "$BUILD/base.apk" "$BUILD/unsigned.apk"
(cd "$BUILD" && zip -q -j unsigned.apk classes.dex)
"$ZIPALIGN" -f -p 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"

echo "[6/6] sign"
mkdir -p "$(dirname "$OUT")"
"$APKSIGNER" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey --out "$OUT" "$BUILD/aligned.apk"
"$APKSIGNER" verify "$OUT" && echo "OK -> $OUT"
