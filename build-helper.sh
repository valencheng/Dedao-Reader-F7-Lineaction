#!/bin/bash
# 构建剪贴板测试辅助 APK（测试完可 adb uninstall com.chengfei.cliptest）
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_SDK:-/Users/chengfei/Android/sdk}"
BT="$SDK/build-tools/35.0.0"
PLAT="$SDK/platforms/android-35/android.jar"
OUT=out
KS=keystore/clipsync.jks
mkdir -p "$OUT/classes" "$OUT/gen-helper"

"$BT/aapt2" link -o "$OUT/cliptest-base.apk" -I "$PLAT" \
  --manifest helper/AndroidManifest.xml \
  --min-sdk-version 29 --target-sdk-version 30 \
  --java "$OUT/gen-helper"

find helper/src "$OUT/gen-helper" -name '*.java' > "$OUT/helper-sources.txt"
javac -source 8 -target 8 -Xlint:-options -encoding UTF-8 \
  -bootclasspath "$PLAT" -classpath "$PLAT" \
  -d "$OUT/classes" @"$OUT/helper-sources.txt"

find "$OUT/classes" -name '*.class' > "$OUT/helper-classes.txt"
"$BT/d8" --release --min-api 29 --lib "$PLAT" --output "$OUT" @"$OUT/helper-classes.txt"

( cd "$OUT" && zip -q cliptest-base.apk classes.dex )
"$BT/zipalign" -f 4 "$OUT/cliptest-base.apk" "$OUT/cliptest-aligned.apk"
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:clipsync --ks-key-alias clipsync \
  --out "$OUT/cliptest.apk" "$OUT/cliptest-aligned.apk"
"$BT/apksigner" verify "$OUT/cliptest.apk"
echo "helper OK: $OUT/cliptest.apk"
