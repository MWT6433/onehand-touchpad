#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_SDK_ROOT:-/opt/android-sdk}"
# 注意：必须用 build-tools 35+。34.0.0 的 d8 对含内部类的 class 必崩
BT="$SDK/build-tools/35.0.0"
AJ="$SDK/platforms/android-34/android.jar"
LIBS="$ROOT/libs"
CP="$AJ:$LIBS/shizuku-api.jar:$LIBS/shizuku-provider.jar:$LIBS/shizuku-aidl.jar"
OUT="$ROOT/build"
rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/gen"
cd "$ROOT"
"$BT/aapt2" compile --dir res -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$AJ" --manifest AndroidManifest.xml \
  "$OUT/res.zip" --java "$OUT/gen" --min-sdk-version 30 --target-sdk-version 34
for aidl_file in $(find src -name '*.aidl' | sort); do
  "$BT/aidl" -I src -o "$OUT/gen" "$aidl_file" || exit 1
done
javac -nowarn -Xlint:-options -source 11 -target 11 -classpath "$CP" -d "$OUT/classes" \
  $(find src "$OUT/gen" -name '*.java' | sort)
"$BT/d8" --release --min-api 30 --lib "$AJ" --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class' | sort) \
  "$LIBS/shizuku-api.jar" "$LIBS/shizuku-provider.jar" "$LIBS/shizuku-aidl.jar"
python3 - "$OUT" <<'PY'
import sys, os, shutil, zipfile
out = sys.argv[1]
apk = os.path.join(out, "base.apk"); tmp = os.path.join(out, "t.apk")
shutil.copyfile(apk, tmp)
with zipfile.ZipFile(tmp, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(os.path.join(out, "dex", "classes.dex"), "classes.dex")
os.replace(tmp, apk)
PY
"$BT/zipalign" -f 4 "$OUT/base.apk" "$OUT/aligned.apk"
KS="$ROOT/tools/debug.keystore"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias ohtp \
    -keyalg RSA -keysize 2048 -validity 3650 -dname "CN=OneHandTouchpad" -noprompt
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --v1-signing-enabled false --v2-signing-enabled true --out "$OUT/onehand.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify "$OUT/onehand.apk"
echo "BUILD_OK $OUT/onehand.apk"
