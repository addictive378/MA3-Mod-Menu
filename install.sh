#!/usr/bin/env bash
# Pasang MA3 Mod Menu ke perangkat lewat adb.
#
#   ./install.sh [path-ke-apk]
#
# frida-inject ikut di dalam APK, jadi cukup sekali tekan "Pasang komponen"
# di aplikasi setelah helper root terpasang.
set -euo pipefail

APK="${1:-ma3tool/android/ma3overlay.apk}"
HELPER="ma3tool/root/ma3root.sh"

[ -f "$APK" ] || { echo "APK tidak ditemukan: $APK"; exit 1; }
[ -f "$HELPER" ] || { echo "Helper tidak ditemukan: $HELPER"; exit 1; }

echo "[1/3] install APK"
adb install -r "$APK"

echo "[2/3] salin helper root"
adb push "$HELPER" /data/local/tmp/ma3root.sh

echo "[3/3] pasang + jalankan helper"
adb shell su -c 'mkdir -p /data/adb/service.d && cp /data/local/tmp/ma3root.sh /data/adb/service.d/ma3root.sh && chmod 755 /data/adb/service.d/ma3root.sh && /data/adb/service.d/ma3root.sh'

echo
echo "Selesai. Buka aplikasi MA3 Mod Menu, tekan \"Periksa ulang\","
echo "lalu \"Pasang komponen\" dan \"Tampilkan panel\"."
