# Analisis: MA3 Mod Menu — Uang & Diamond Modern Age 3 (Android, root)

Dokumen ini mencatat hasil, temuan teknis, dan batasan dari pembuatan overlay
untuk game **Modern Age 3** yang bisa membaca dan mengubah nilai
**uang (GOLD)** dan **diamond (CRISTAL)** langsung di perangkat yang sudah
di-root. Dua varian didukung:

| Varian | Paket |
|---|---|
| Premium | `com.oxiwyle.modernage3premium` |
| Biasa | `com.oxiwyle.modernage3` |

Status akhir: **berhasil**. Mengisi angka di panel lalu menekan SET / TAMBAH
langsung mengubah HUD game (lihat `.work/shots/s1s.png` … `s5s.png`).

Aplikasinya bernama **MA3 Mod Menu** dan seluruh tampilannya memakai tema gelap
bersama (`Ui.java`) dengan kredit **cracked by addictive378**.

---

## 1. Arsitektur

```
┌──────────────────────────┐        file channel        ┌─────────────────────┐
│ APK overlay (com.ma3.    │  files/cmd → files/res     │ ma3root.sh          │
│ overlay), tanpa root     │ ─────────────────────────► │ (root, KernelSU     │
│ - panel melayang         │                            │  service.d)         │
│ - worker TCP             │ ◄───────────────────────── │ - exec perintah     │
└───────────┬──────────────┘                            └──────────┬──────────┘
            │ TCP 127.0.0.1:47321..47330                          │ setsid
            │                                                     ▼
            │                                          /data/local/tmp/frida-inject
            │                                          -p <pid game> -s ma3agent.js
            ▼                                                     │
┌──────────────────────────────────────────────────────────────── ▼ ─────────┐
│ Proses game (uid u0_a381)                                                   │
│  ma3agent.js  ── JNI mentah (NativeFunction ke vtable JNIEnv) ──► PlayerCountry
│  server libc socket/bind/listen/accept4/recv/send, loop blocking            │
└─────────────────────────────────────────────────────────────────────────────┘
```

Kenapa berlapis seperti ini:

- **Aplikasi tidak bisa `su`.** Di perangkat ini binary `su` tidak terlihat di
  mount namespace aplikasi, jadi aplikasi memakai helper root yang sudah jalan
  sebagai daemon sejak boot (`/data/adb/service.d/ma3root.sh`). Aplikasi menulis
  perintah ke `files/cmd`, helper mengeksekusi dan menulis hasil ke `files/res`.
- **Root yang men-inject agent**, karena `frida-inject` harus jalan sebagai root
  agar bisa `ptrace` proses game.

---

## 2. Kenapa bukan LSPosed / Xposed / frida-java-bridge

Game memakai **PairIP** (`libpairipcore.so`). Yang terbukti di perangkat ini:

| Perlakuan | Akibat |
|---|---|
| Modul Xposed / LSPosed | proses game mati |
| `Java.perform(...)` (frida-java-bridge) | proses mati ~11 detik kemudian |
| `frida-java-bridge` apa pun yang mem-patch ART | proses mati |
| `frida-inject` + **JNI mentah** (NativeFunction ke vtable JNIEnv) | aman |

Jadi agent **tidak menyentuh ART sama sekali**: tidak ada hook, tidak ada
patching, tidak ada `Java.use`. Semua akses Java dilakukan dengan memanggil
pointer fungsi di vtable `JNIEnv`/`JavaVM` memakai `NativeFunction`.

Indeks vtable JNI yang dipakai:

```
JavaVM : AttachCurrentThread = 4
JNIEnv : FindClass 6, ExceptionOccurred 15, ExceptionClear 17,
         PushLocalFrame 19, PopLocalFrame 20,
         NewObjectA 30, GetMethodID 33, CallObjectMethod(A) 34,
         CallVoidMethod(A) 61, GetStaticMethodID 113,
         CallStaticObjectMethod(A) 114, CallStaticVoidMethod(A) 141,
         NewStringUTF 167, GetStringUTFChars 169, ReleaseStringUTFChars 170
```

---

## 3. Cara mengakses data game

Kelas game hanya bisa ditemukan lewat **class loader aplikasi**, bukan
`FindClass` biasa:

```
ActivityThread.currentApplication()      -> Application
  .getClassLoader()                      -> ClassLoader
Class.forName("com.oxiwyle.modernage2.models.PlayerCountry", false, loader)
```

Method yang dipakai:

| Tujuan | Method |
|---|---|
| instance | `PlayerCountry.getInstance()` (static) |
| baca | `getResourcesByType(String) : BigDecimal` |
| tulis | `setResourcesByType(String, BigDecimal) : void` |
| simpan | `getFossilResources()` lalu `r6.T0(FossilResources)` dan `x91.c()` |

Tipe resource: `"GOLD"` dan `"CRISTAL"` (perhatikan ejaannya, bukan "CRYSTAL").

---

## 4. Temuan selama debugging (ini bagian terpenting)

### 4.1 JNI dari callback timer tidak stabil
Memanggil JNI dari `setInterval`/`setTimeout` sering berakhir
`Error: access violation accessing 0x0`. Yang **stabil** adalah memanggil JNI
dari thread tempat skrip dimuat. Karena itu agent menjalankan seluruh server
sebagai **loop blocking di badan skrip** (recv non-blocking + `usleep(20000)`),
bukan di timer.

### 4.2 Inject terlalu dini = sesi rusak permanen
Kalau `frida-inject` dipasang saat proses game masih muda (< ~25 detik), semua
panggilan JNI sesudahnya fault selamanya (sudah diuji: menunggu 150 detik tidak
menolong). Karena itu:

- aplikasi menghitung umur proses game dari `/proc/uptime` dan
  `/proc/<pid>/stat` (field 22) dan baru inject bila umur ≥ **25 detik**
  (`INJECT_AFTER_S`). Batas ini dinaikkan dari 18 detik setelah cold start
  masih gagal attach dengan pesan
  `Unexpectedly timed out while waiting for stop from process`;
- selama menunggu, panel menampilkan `menunggu game siap (N dtk)`.

### 4.3 Sesi JNI punya umur terbatas
Sesi yang dipasang dengan benar tetap akhirnya fault permanen
(`access violation accessing 0x0`) setelah puluhan detik sampai beberapa menit.
Setelah itu **semua** panggilan JNI di sesi itu gagal, termasuk panggilan ke
vtable `JavaVM`. Dugaan terkuat: deteksi/penetralan PairIP terhadap thread asing.

Yang **tidak** menyembuhkan: attach ulang (`AttachCurrentThread` mengembalikan
JNIEnv yang sama), resolve ulang kelas, retry.

Yang **menyembuhkan**: **sesi baru**. `frida-inject` baru ke proses yang sama
langsung bisa JNI lagi, dan mematikan `frida-inject` lama **tidak** membunuh
game. Karena itu:

- agent menutup socket dan berhenti bila terjadi 2 fault beruntun
  (log: `AGENT_MATI`);
- aplikasi mendeteksi agent mati (PING gagal), membunuh injektor lama
  (`pkill -f frida-inject`), lalu inject sesi baru secara otomatis;
- port digilir `47321..47330` supaya sisa socket sesi lama tidak menghalangi.

Efek samping: saat pergantian sesi (± 6–10 detik) nilai di panel berhenti
diperbarui dan perintah yang sedang dikirim bisa hilang — cukup tekan lagi.

### 4.4 Jangan panggil `DetachCurrentThread`
`DetachCurrentThread` dari thread ini memicu **abort ART**
(`ThreadList::Unregister` → `Runtime::Abort`), dan karena abort-nya gagal
rekursif, proses hidup dalam keadaan rusak lalu SIGSEGV beberapa detik kemudian.

### 4.5 Referensi Java harus segar
`jclass`/`jobject` yang disimpan lama bisa basi setelah GC game memindahkan
objek. Karena itu setiap permintaan melakukan `PushLocalFrame` → resolve semua
referensi (class loader, kelas, method ID, instance) → kerja → `PopLocalFrame`.
Tidak ada referensi Java yang disimpan antar permintaan.

### 4.6 Jebakan di helper root
Helper menjalankan perintah dengan `sh -c "$req"` (tanda kutip ganda), sehingga
`$(...)` dan `$22` di dalam perintah **sudah diekspansi oleh shell pembungkus**
dan hasilnya salah. Karena itu perintah ke helper harus sederhana (`cat ...`,
`pidof ...`, `cp ...`), dan perhitungan dilakukan di sisi Java/agent.

### 4.7 Socket API Frida tidak jalan di sini
`Socket.listen()` milik Frida tidak pernah memanggil callback `accept` di
proses ini. Agent memakai syscall libc langsung:
`socket/bind/listen/accept4/recv/send/fcntl/close/usleep`.

---

## 5. Protokol agent

Satu perintah per baris, satu balasan per baris, lewat TCP `127.0.0.1:47321+`.

| Perintah | Balasan | Keterangan |
|---|---|---|
| `PING` | `PONG` | cek hidup (dipakai untuk memilih port) |
| `GET` | `OK <gold> <cristal>` | baca nilai sekarang |
| `SET <gold> <cristal>` | `OK <gold> <cristal>` | `-` = lewati |
| `ADD <gold> <cristal>` | `OK <gold> <cristal>` | tambah dari nilai sekarang |
| `SAVE` | `OK saved` | panggil jalur simpan game |
| `LOCK <gold> <cristal>` | `OK locked` | tempel ulang bila nilai turun di bawah |
| `UNLOCK` | `OK unlocked` | lepas kunci |
| `DIAG` | `OK ...` | info tanpa JNI |
| `QUIT` | `OK bye` | tutup koneksi |

Catatan: `LOCK` hanya **menaikkan** nilai bila turun di bawah angka kunci; ia
tidak menurunkan nilai yang lebih tinggi (pajak game tetap masuk).

---

## 6. Cara pakai

### Sekali saja (setup)

Sejak versi ini hampir semuanya dikerjakan aplikasi:

1. install APK (`adb install -r ma3overlay.apk` atau dari halaman Releases);
2. buka aplikasi → kartu **KESESIAPAN** menunjukkan apa yang kurang;
3. kalau **Helper root** belum aktif, tekan **Salin perintah helper root** lalu
   tempel di Termux (root):

   ```bash
   su -c 'cp /data/data/com.ma3.overlay/files/ma3root.sh /data/adb/service.d/ma3root.sh \
     && chmod 755 /data/adb/service.d/ma3root.sh && /data/adb/service.d/ma3root.sh'
   ```

   Perintah itu memasang helper sekaligus menjalankannya (tanpa reboot);
4. tekan **Pasang komponen** → `frida-inject` disalin dari `assets/frida-inject`
   ke `/data/local/tmp` (755, `shell_data_file`);
5. tekan **Tampilkan panel**.

Yang **tidak** perlu lagi dilakukan manual:

| Dulu | Sekarang |
|---|---|
| `adb push frida-inject` | dibundel di APK, disalin tombol **Pasang komponen** |
| `adb push ma3agent.js` | disalin aplikasi tiap service mulai |
| `adb push ma3root.sh` | disalin aplikasi ke folder privat, tinggal satu perintah |
| inject manual | watchdog otomatis setelah game ≥ 25 detik |

Untuk Magisk yang mengizinkan `su` langsung dari aplikasi, langkah helper bisa
dilewati sama sekali: `Root` mencoba helper lebih dulu, lalu jatuh ke
`su -c`. Pemeriksaan memakai timeout pendek (4 detik) supaya layar kesiapan
tidak menggantung.

`install.sh` di akar repo melakukan langkah adb yang setara untuk yang lebih
suka terminal.

### Dua varian game

Kedua varian isinya sama: kelas tetap `com.oxiwyle.modernage2.*` dan
pengamannya tetap `libpairipcore.so`; yang berbeda hanya nama paket. Karena
itu agent JNI yang sama dipakai untuk keduanya, dan aplikasi hanya perlu tahu
paket mana yang sedang dipakai untuk tiga hal:

| Keperluan | Contoh |
|---|---|
| cari proses | `pidof com.oxiwyle.modernage3premium` |
| buka game | `am start -n <paket>/com.oxiwyle.modernage2.activities.StartActivity` |
| folder log agent | `/data/user/0/<paket>/files/ma3.log` |

Tidak ada lagi pilihan game di layar utama -- panel memilih sendiri:

- Kalau salah satu game sedang berjalan, panel memakai game itu (premium
  diperiksa lebih dulu).
- Kalau tidak ada yang berjalan, **BUKA GAME** membuka paket yang terakhir
  dipakai, atau yang terpasang (premium didahulukan).
- Status panel menyebut game yang terhubung, mis. `terhubung ke agent · MA3`.
- Penanda `__MA3_LOG__` di dalam `ma3agent.js` diganti aplikasi dengan folder
  data game saat agent dipush, jadi log tetap masuk ke tempat yang benar.

### Pemakaian harian

1. Buka aplikasi **MA3 Mod Menu** → tekan **Tampilkan panel**.
2. Di panel: **BUKA GAME**. Panel akan menampilkan
   `menunggu game siap (N dtk)` sampai umur proses game cukup, lalu
   `terhubung ke agent` dan nilai uang/diamond muncul.
3. Isi kolom **Uang** dan **Diamond**, lalu:
   - **SET** – set nilai persis,
   - **TAMBAH** – tambahkan ke nilai sekarang,
   - **SIMPAN** – paksa game menyimpan,
   - **KUNCI** / **BUKA KUNCI** – jaga nilai agar tidak turun.

Semua langkah manual yang dulu ada sudah dihapus karena tidak diperlukan lagi:

| Dulu | Sekarang |
|---|---|
| Tombol **Pasang agent** | agent disalin dari `assets/ma3agent.js` otomatis tiap panel mulai |
| Tombol **Mulai agent (inject)** | watchdog memasang agent otomatis setelah umur game ≥ 25 dtk |
| Tombol **INJECT** di panel | sama, pemasangan ulang ditangani watchdog |
| Tombol **Hentikan agent** | agent dimatikan otomatis saat panel disembunyikan |

Panel juga punya tombol **−** (minimize) di samping **✕** (tutup):

- **−** mengecilkan panel jadi pil kecil `MA3  2,35 M · 2` yang bisa digeser
  dan tidak menutupi layar game. Nilai uang/diamond tetap diperbarui.
- Ketuk pil untuk membuka panel lagi di posisi pil tersebut.
- **✕** tetap menutup panel sekaligus mematikan agent.

Posisi panel maupun pil selalu dibatasi agar tidak keluar layar dan tidak
menutupi status bar.

Layar utama punya kartu **KESESIAPAN** berisi empat baris status:

| Baris | Isi |
|---|---|
| Helper root | `aktif` (helper) / `aktif (su)` / `belum` |
| frida-inject | `ada` / `belum — tekan Pasang komponen` |
| Izin overlay | `diberikan` / `belum — buka di Setelan` |
| Game | `MA3 Premium + MA3` / `tidak terpasang` |

Tombolnya: **Periksa ulang** (reset mode root lalu cek lagi), **Pasang
komponen** (salin `frida-inject` dari APK), dan **Salin perintah helper root**
(menyalin perintah `su -c ...` ke clipboard). Semua pemeriksaan berjalan di
thread latar supaya UI tidak macet, dan diulang otomatis di `onResume`
(mis. setelah izin overlay diberikan dari Setelan).

Di bawahnya baru dua tombol panel: **Tampilkan panel** dan
**Sembunyikan panel**.

---

## 7. Batasan / hal yang perlu diketahui

- **Butuh root** (KernelSU/Magisk) dan `frida-inject` di `/data/local/tmp`
  (disalin aplikasi dari dalam APK).
- **Sesi agent berumur pendek.** Aplikasi memasang sesi baru otomatis, tetapi
  saat pergantian ada jeda ± 6–10 detik dan perintah yang sedang dikirim bisa
  hilang.
- **Jangan inject saat game baru dibuka** (< 25 detik). Watchdog sudah menjaga
  ini; tidak ada lagi tombol inject manual.
- **Perubahan tidak selalu langsung terlihat di save game** kalau game belum
  menyimpan; pakai SIMPAN atau biarkan game auto-save.
- Angka uang bisa tetap naik karena pajak game — itu perilaku game, bukan bug.
- Kalau game di-update dan PairIP berubah, perilaku deteksi bisa ikut berubah.

---

## 8. Berkas penting

| Berkas | Isi |
|---|---|
| `ma3tool/agent/ma3agent.js` | agent JNI + server socket + protokol |
| `ma3tool/android/src/com/ma3/overlay/OverlayService.java` | panel, worker TCP, watchdog (umur game + inject ulang) |
| `ma3tool/android/src/com/ma3/overlay/Root.java` | jembatan helper root, pemilihan port agent |
| `ma3tool/android/src/com/ma3/overlay/MainActivity.java` | layar peluncur (2 tombol) |
| `ma3tool/android/src/com/ma3/overlay/Ui.java` | palet warna + helper tampilan (tema panel & layar utama) |
| `ma3tool/android/src/com/ma3/overlay/Games.java` | daftar paket yang didukung + deteksi game yang berjalan/terpasang |
| `ma3tool/android/build.sh` | build APK tanpa Gradle (javac → d8 → aapt2 → zipalign → apksigner) |
| `ma3tool/root/ma3root.sh` | helper root (service.d) |

Log:

- agent: `/data/user/0/com.oxiwyle.modernage3premium/files/ma3.log`
- helper: `/data/local/tmp/ma3root.log`
- injektor: `/data/local/tmp/ma3-agent.out`

---

## 9. Langkah berikutnya (opsional)

1. Menyelidiki penyebab pasti matinya sesi JNI (kalau bisa diatasi, tidak perlu
   re-inject berkala).
2. Memberi umpan balik status untuk SIMPAN/KUNCI di panel.
3. Menyimpan antrean perintah saat sesi berganti, lalu mengirim ulang otomatis.
4. Menambah nilai lain (mis. populasi, teknologi) dengan pola yang sama.
