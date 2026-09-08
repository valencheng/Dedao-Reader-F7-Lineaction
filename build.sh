#!/bin/bash
# 极简构建：aapt2 + javac + d8 + zipalign + apksigner，不依赖 Gradle / 第三方库
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_SDK:-/Users/chengfei/Android/sdk}"
BT="$SDK/build-tools/35.0.0"
PLAT="$SDK/platforms/android-35/android.jar"
OUT=out
KS=keystore/clipsync.jks

for f in "$BT/aapt2" "$BT/d8" "$BT/zipalign" "$BT/apksigner" "$PLAT"; do
  [ -e "$f" ] || { echo "missing: $f"; exit 1; }
done

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$(dirname "$KS")"

echo "[1/5] resources + manifest -> apk & R.java"
"$BT/aapt2" compile --dir res -o "$OUT/res.zip"
ASSET_ARGS=""
if [ -d assets ]; then ASSET_ARGS="-A assets"; fi
"$BT/aapt2" link -o "$OUT/app-base.apk" -I "$PLAT" \
  --manifest AndroidManifest.xml -R "$OUT/res.zip" \
  --min-sdk-version 29 --target-sdk-version 30 \
  $ASSET_ARGS \
  --java "$OUT/gen"

echo "[2/5] javac"
find src "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -source 8 -target 8 -Xlint:-options -encoding UTF-8 \
  -bootclasspath "$PLAT" -classpath "$PLAT" \
  -d "$OUT/classes" @"$OUT/sources.txt"

echo "[3/5] d8 dex"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$BT/d8" --release --min-api 29 --lib "$PLAT" \
  --output "$OUT" @"$OUT/classes.txt"

echo "[4/5] package + align"
( cd "$OUT" && zip -q app-base.apk classes.dex )
"$BT/zipalign" -f 4 "$OUT/app-base.apk" "$OUT/app-aligned.apk"

echo "[5/5] sign"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -alias clipsync -keyalg RSA -keysize 2048 \
    -validity 10950 -storepass clipsync -keypass clipsync \
    -dname "CN=clipsync, OU=personal, O=chengfei" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:clipsync --ks-key-alias clipsync \
  --out "$OUT/clipsync.apk" "$OUT/app-aligned.apk"
"$BT/apksigner" verify "$OUT/clipsync.apk" && echo "verified OK"

ls -l "$OUT/clipsync.apk"
echo "done: $OUT/clipsync.apk"
