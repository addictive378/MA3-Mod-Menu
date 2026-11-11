#!/system/bin/sh
# MA3 root helper
# Dijalankan otomatis oleh KernelSU (service.d) sebagai root saat boot.
#
# Kenapa ada file ini:
#   Di perangkat ini binary "su" tidak terlihat di mount namespace aplikasi,
#   sehingga aplikasi overlay tidak bisa exec su sendiri. Helper ini menjadi
#   jembatan: aplikasi menulis perintah ke  <app files>/cmd , helper (root)
#   menjalankannya dan menulis hasilnya ke <app files>/res .
#
# Keamanan: hanya proses root dan aplikasi com.ma3.overlay sendiri yang bisa
# menulis ke direktori itu (direktori privat aplikasi, mode 700).

PKG_DIR=/data/user/0/com.ma3.overlay
APP_DIR=$PKG_DIR/files
LOG=/data/local/tmp/ma3root.log

# jalankan diri sendiri di latar belakang supaya KernelSU tidak menunggu
if [ "$MA3_DAEMON" != "1" ]; then
    MA3_DAEMON=1 setsid "$0" > /dev/null 2>&1 < /dev/null &
    exit 0
fi

# tunggu sampai paket aplikasi pernah dijalankan (direktori datanya ada)
i=0
while [ ! -d "$PKG_DIR" ] && [ $i -lt 600 ]; do
    sleep 1
    i=$((i + 1))
done

mkdir -p "$APP_DIR" 2>/dev/null
APP_UID=$(stat -c %u "$PKG_DIR" 2>/dev/null)
if [ -n "$APP_UID" ]; then
    chown "$APP_UID:$APP_UID" "$APP_DIR" 2>/dev/null
fi
chmod 700 "$APP_DIR" 2>/dev/null

echo "[$(date)] helper mulai (uid=$APP_UID)" >> "$LOG"

while true; do
    if [ -f "$APP_DIR/cmd" ]; then
        req=$(cat "$APP_DIR/cmd" 2>/dev/null)
        rm -f "$APP_DIR/cmd" 2>/dev/null
        if [ -n "$req" ]; then
            echo "[$(date)] cmd: $req" >> "$LOG"
            out=$(sh -c "$req" 2>&1)
            printf '%s\n' "$out" > "$APP_DIR/res.tmp" 2>/dev/null
            chmod 644 "$APP_DIR/res.tmp" 2>/dev/null
            mv "$APP_DIR/res.tmp" "$APP_DIR/res" 2>/dev/null
        fi
    fi
    sleep 0.35
done
