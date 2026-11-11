package com.ma3.overlay;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Pemeriksaan kesiapan dan pemasangan komponen.
 *
 * Yang dibutuhkan supaya panel bisa bekerja:
 *   1. akses root (helper ma3root.sh ATAU "su" langsung)
 *   2. frida-inject di /data/local/tmp  (sekarang ikut di dalam APK)
 *   3. izin "tampilkan di atas aplikasi lain"
 *   4. game Modern Age 3 terpasang
 *
 * Semua yang bisa diotomatiskan diotomatiskan di sini, jadi user cukup
 * memasang helper root sekali (kalau perangkatnya tidak mengizinkan su).
 */
public final class Setup {

    public static final String TMP = "/data/local/tmp";
    public static final String INJECTOR = TMP + "/frida-inject";
    public static final String AGENT = TMP + "/ma3agent.js";
    public static final String HELPER = "/data/adb/service.d/ma3root.sh";

    private Setup() { }

    /* ------------------------------------------------------------- aset */

    /** Salin satu aset APK ke folder privat aplikasi (bisa dibaca root). */
    public static File aset(Context c, String nama, String tujuan) {
        try {
            InputStream in = c.getAssets().open(nama);
            File out = new File(c.getFilesDir(), tujuan);
            FileOutputStream fo = new FileOutputStream(out);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
            fo.close();
            in.close();
            out.setReadable(true, false);
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** frida-inject sudah ada di /data/local/tmp? */
    public static boolean injectorAda() {
        String out = Root.run("ls -l " + INJECTOR).trim();
        return out.contains(INJECTOR) && !out.contains("No such file");
    }

    /** Salin frida-inject dari APK ke /data/local/tmp. null = sukses. */
    public static String pasangInjector(Context c) {
        if (injectorAda()) return null;
        File f = aset(c, "frida-inject", "frida-inject");
        if (f == null) {
            return "APK ini tidak memuat frida-inject. Ambil versi rilis dari GitHub.";
        }
        String out = Root.run("cp " + f.getAbsolutePath() + " " + INJECTOR
                + " && chmod 755 " + INJECTOR
                + " && chcon u:object_r:shell_data_file:s0 " + INJECTOR + " 2>/dev/null"
                + " && ls -l " + INJECTOR);
        if (out.startsWith("ERR") || !out.contains(INJECTOR)) return out;
        return null;
    }

    /* ------------------------------------------------------- helper root */

    /** Siapkan ma3root.sh di folder aplikasi supaya bisa disalin user. */
    public static File siapkanHelper(Context c) {
        return aset(c, "ma3root.sh", "ma3root.sh");
    }

    /**
     * Perintah satu baris untuk dipasang user lewat terminal root (Termux):
     * menyalin helper ke service.d, memberi izin, lalu menjalankannya.
     */
    public static String perintahHelper(Context c) {
        File f = siapkanHelper(c);
        String asal = f != null ? f.getAbsolutePath()
                : "/data/data/" + c.getPackageName() + "/files/ma3root.sh";
        return "su -c 'cp " + asal + " " + HELPER
                + " && chmod 755 " + HELPER
                + " && " + HELPER + "'";
    }
}
