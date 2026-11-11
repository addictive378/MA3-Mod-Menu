#!/usr/bin/env bash
# Build APK overlay tanpa Gradle: javac -> d8 -> aapt2 link -> zipalign -> apksigner.
set -euo pipefail

cd "$(dirname "$0")"

SDK="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
BT="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"
PLATFORM_JAR="$SDK/platforms/android-34/android.jar"
[ -f "$PLATFORM_JAR" ] || PLATFORM_JAR="$(ls -d "$SDK"/platforms/*/android.jar | sort -V | tail -1)"

OUT=build
CLASSES=$OUT/classes
DEX=$OUT/dex
KEYSTORE=$OUT/ma3.keystore
KS_PASS=ma3overlay
KS_ALIAS=ma3overlay

rm -rf "$CLASSES" "$DEX" "$OUT/unsigned.apk" "$OUT/aligned.apk"
mkdir -p "$CLASSES" "$DEX" assets

cp ../agent/ma3agent.js assets/ma3agent.js
cp ../root/ma3root.sh assets/ma3root.sh

# frida-inject ikut dibundel supaya user tidak perlu adb push.
# Ambil dari ../vendor/frida-inject atau $FRIDA_INJECT kalau ada.
if [ -n "${FRIDA_INJECT:-}" ] && [ -f "$FRIDA_INJECT" ]; then
    cp "$FRIDA_INJECT" assets/frida-inject
elif [ -f ../vendor/frida-inject ]; then
    cp ../vendor/frida-inject assets/frida-inject
fi
if [ -f assets/frida-inject ]; then
    echo "      frida-inject: $(du -h assets/frida-inject | cut -f1) (dibundel)"
else
    echo "      frida-inject: tidak ada (user harus push sendiri)"
fi

echo "[1/5] kompilasi java"
find src -name '*.java' > "$OUT/src.list"
javac -nowarn -source 8 -target 8 -bootclasspath "$PLATFORM_JAR" \
    -d "$CLASSES" @"$OUT/src.list" 2> "$OUT/javac.log" \
    || { cat "$OUT/javac.log"; echo "kompilasi gagal"; exit 1; }
echo "      $(find "$CLASSES" -name '*.class' | wc -l) class"

echo "[2/5] dex"
find "$CLASSES" -name '*.class' > "$OUT/class.list"
"$BT/d8" --min-api 26 --lib "$PLATFORM_JAR" --output "$DEX" @"$OUT/class.list" >/dev/null

echo "[3/5] resource + manifest + assets"
rm -rf "$OUT/res"
mkdir -p "$OUT/res"
"$BT/aapt2" compile --dir res -o "$OUT/res"
"$BT/aapt2" link -o "$OUT/unsigned.apk" -I "$PLATFORM_JAR" \
    --manifest AndroidManifest.xml -A assets \
    --min-sdk-version 26 --target-sdk-version 34 \
    $(find "$OUT/res" -name '*.flat')

echo "[4/5] paketkan dex"
( cd "$DEX" && zip -q -X "$OLDPWD/$OUT/unsigned.apk" classes.dex )
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "[5/5] tanda tangan"
if [ ! -f "$KEYSTORE" ]; then
    keytool -genkeypair -keystore "$KEYSTORE" -storepass "$KS_PASS" -keypass "$KS_PASS" \
        -alias "$KS_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=MA3 Overlay, O=local, C=ID" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
    --ks-key-alias "$KS_ALIAS" --out ma3overlay.apk "$OUT/aligned.apk"
"$BT/apksigner" verify --print-certs ma3overlay.apk | head -3

echo
echo "selesai: $(pwd)/ma3overlay.apk"
ls -la ma3overlay.apk
