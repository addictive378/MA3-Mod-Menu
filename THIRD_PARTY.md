# Komponen pihak ketiga

## frida-inject

- Sumber: <https://github.com/frida/frida> (rilis resmi, mis. versi 17.x)
- Lisensi: wxWindows Library Licence (LGPL-style)
- Berkas: `ma3tool/vendor/frida-inject` (tidak disertakan di repo; ambil dengan
  `ma3tool/tools/get-frida-inject.sh`) dan disalin ke `assets/frida-inject`
  saat build sehingga ikut masuk ke APK rilis.

Binary ini **bukan** karya proyek ini dan tidak diubah. Hak cipta dan lisensi
sepenuhnya milik proyek Frida. Kode sumber Frida tersedia di repositori di atas,
sehingga pengguna dapat mengganti binary tersebut dengan versi lain.

## Aset game

Proyek ini **tidak** memuat APK, aset, atau data milik game Modern Age 3 /
Age of History 3. Semua kelas dan nama paket yang disebut hanya dipakai sebagai
referensi agar agent bisa memanggil API game yang sudah terpasang di perangkat
pengguna.
