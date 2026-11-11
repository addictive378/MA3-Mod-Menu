package com.ma3.overlay;

/**
 * Dua varian Modern Age 3 yang didukung.
 *
 * Isi APK-nya sama -- kelas tetap {@code com.oxiwyle.modernage2.*} dan
 * pengamannya tetap libpairipcore.so -- yang berbeda hanya nama paket. Jadi
 * agent JNI yang sama bisa dipakai untuk keduanya; yang perlu disesuaikan
 * hanya pidof, activity yang dibuka, dan folder log agent.
 */
public final class Games {

    public static final String PREMIUM = "com.oxiwyle.modernage3premium";
    public static final String BIASA = "com.oxiwyle.modernage3";

    /** Urutan pencarian: premium lebih dulu kalau keduanya terpasang. */
    public static final String[] SEMUA = { PREMIUM, BIASA };

    /** Activity peluncur sama persis di kedua varian. */
    public static final String ACTIVITY = "com.oxiwyle.modernage2.activities.StartActivity";

    private Games() { }

    public static String nama(String pkg) {
        return PREMIUM.equals(pkg) ? "MA3 Premium" : "MA3";
    }

    public static String activity(String pkg) {
        return pkg + "/" + ACTIVITY;
    }

    public static boolean terpasang(String pkg) {
        String out = Root.run("pm path " + pkg).trim();
        return out.contains("package:");
    }

    /** Paket yang prosesnya sedang berjalan, atau null. */
    public static String berjalan() {
        for (int i = 0; i < SEMUA.length; i++) {
            String pid = Root.run("pidof " + SEMUA[i]).trim();
            if (!pid.isEmpty() && !pid.startsWith("ERR")) return SEMUA[i];
        }
        return null;
    }

    /** Paket pertama yang terpasang, atau null. */
    public static String terpasangSatu() {
        for (int i = 0; i < SEMUA.length; i++) {
            if (terpasang(SEMUA[i])) return SEMUA[i];
        }
        return null;
    }
}
