#!/usr/bin/env bash
# Unduh frida-inject (android arm64) dari rilis resmi Frida.
#
#   ./ma3tool/tools/get-frida-inject.sh [versi]
#
# Hasil: ma3tool/vendor/frida-inject  (dipakai build.sh, ikut masuk ke APK)
set -euo pipefail

VERSI="${1:-17.23.3}"
DIR="$(cd "$(dirname "$0")/.." && pwd)"
TUJUAN="$DIR/vendor/frida-inject"
URL="https://github.com/frida/frida/releases/download/$VERSI/frida-inject-$VERSI-android-arm64.so.xz"

mkdir -p "$DIR/vendor"
echo "mengunduh $URL"
if ! curl -fL --retry 3 -o /tmp/frida-inject.xz "$URL"; then
    echo
    echo "Gagal mengunduh. Cek daftar rilis di https://github.com/frida/frida/releases"
    echo "lalu jalankan ulang dengan versi yang tersedia, mis.:"
    echo "  $0 16.7.19"
    exit 1
fi

xz -df /tmp/frida-inject.xz -c > "$TUJUAN"
chmod 755 "$TUJUAN"
echo "selesai: $TUJUAN ($(du -h "$TUJUAN" | cut -f1))"
