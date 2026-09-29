#!/usr/bin/env bash
# Builds Pfand Counter into build/pfand.apk without Gradle: aapt2 for resources,
# javac + d8 for code (including the ZXing jar in libs/), apksigner for a signature
# the phone will accept.
#
#   ./build.sh              signed with keys/debug.keystore → build/pfand.apk
#   ./build.sh --unsigned   aligned but not signed → build/pfand-unsigned.apk, for F-Droid,
#                           which builds from source and signs with its own key
set -euo pipefail
cd "$(dirname "$0")"

UNSIGNED=0
for arg in "$@"; do
  case "$arg" in
    --unsigned) UNSIGNED=1 ;;
    *) echo "unknown option: $arg" >&2; exit 2 ;;
  esac
done

SDK=${ANDROID_SDK:-/opt/android-sdk}
# A fixed build-tools version, so every build (and F-Droid's) uses the same tools.
BUILD_TOOLS=${BUILD_TOOLS:-37.0.0}
BT="$SDK/build-tools/$BUILD_TOOLS/"
PLATFORM="$SDK/platforms/android-36/android.jar"
ZXING=libs/core-3.5.3.jar
# Not kept in the repository: fetched from Maven Central and pinned by its SHA-256. The same file
# matches Maven Central's own core-3.5.3.jar.sha1 (ca1349214a356cd7958651b2d5a0e1f3811a9c4b).
ZXING_URL=https://repo1.maven.org/maven2/com/google/zxing/core/3.5.3/core-3.5.3.jar
ZXING_SHA256=8d8064c1636fdaef7189dd9055c7d59950a8940a12f2293956446ec3c109fd82
OUT=build
KEYSTORE=keys/debug.keystore

# The installed app's identity. AndroidManifest.xml keeps app.pfandcounter as the Java
# package; the published application id is io.github.passport0819.pfandcounter. A phone
# that already runs the app under another id keeps its data only if updates carry that
# same id, so such a build names it in app-id.local (one line, not part of the repository).
APP_ID=$(head -n 1 app-id.local 2>/dev/null | tr -d '[:space:]' || true)
APP_ID=${APP_ID:-io.github.passport0819.pfandcounter}
RENAME=(--rename-manifest-package "$APP_ID")

[ -d "$BT" ] || { echo "build-tools $BUILD_TOOLS not found under $SDK (set BUILD_TOOLS=…)" >&2; exit 1; }
[ -f "$PLATFORM" ] || { echo "missing $PLATFORM" >&2; exit 1; }
if [ ! -f "$ZXING" ]; then
  echo "== fetching ZXing from Maven Central"
  mkdir -p libs
  curl -fsSL -o "$ZXING.part" "$ZXING_URL" || { echo "download of $ZXING_URL failed" >&2; exit 1; }
  mv "$ZXING.part" "$ZXING"
fi
echo "$ZXING_SHA256  $ZXING" | sha256sum -c --status || {
  echo "$ZXING does not match the pinned SHA-256 — delete it and build again" >&2; exit 1; }

# A stable signing key. It must survive rebuilds — Android refuses to update an app
# whose signature changed.
if [ "$UNSIGNED" = 0 ] && [ ! -f "$KEYSTORE" ]; then
  echo "== creating signing key"
  mkdir -p keys
  keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android \
    -alias pfand -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Pfand Counter" >/dev/null
fi

rm -rf "$OUT"; mkdir -p "$OUT/gen" "$OUT/classes"

echo "== resources"
"$BT/aapt2" compile --dir res -o "$OUT/res.zip"
"$BT/aapt2" link \
  -o "$OUT/unsigned.apk" \
  -I "$PLATFORM" \
  --manifest AndroidManifest.xml \
  -R "$OUT/res.zip" \
  --java "$OUT/gen" \
  --min-sdk-version 34 \
  --target-sdk-version 36 \
  --auto-add-overlay \
  "${RENAME[@]}"

# src-local/ is optional: a local build may put an extra deposit source there
# (see src/app/pfandcounter/ExtraSource.java).
SOURCES=(src "$OUT/gen")
[ -d src-local ] && SOURCES+=(src-local)

echo "== java"
javac -nowarn -encoding UTF-8 -source 17 -target 17 \
  -classpath "$PLATFORM:$ZXING" -d "$OUT/classes" \
  $(find "${SOURCES[@]}" -name '*.java')

echo "== dex"
"$BT/d8" --lib "$PLATFORM" --min-api 34 --release --output "$OUT" \
  $(find "$OUT/classes" -name '*.class') "$ZXING"

echo "== package"
(cd "$OUT" && zip -q -j unsigned.apk classes.dex)
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
if [ "$UNSIGNED" = 1 ]; then
  APK="$OUT/pfand-unsigned.apk"
  mv "$OUT/aligned.apk" "$APK"
else
  APK="$OUT/pfand.apk"
  "$BT/apksigner" sign \
    --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
    --out "$APK" "$OUT/aligned.apk"
fi

rm -f "$OUT/unsigned.apk" "$OUT/aligned.apk" "$OUT/res.zip"
echo "== done: $APK"
ls -l "$APK"
