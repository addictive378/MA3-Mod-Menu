package com.ma3.overlay;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Jembatan ke root.
 *
 * Di perangkat ini binary "su" tidak tersedia di mount namespace aplikasi
 * (hanya di shell), jadi Runtime.exec("su") selalu gagal. Sebagai gantinya
 * dipakai helper root /data/adb/service.d/ma3root.sh yang dijalankan KernelSU
 * saat boot:
 *
 *   aplikasi tulis perintah  -> files/cmd
 *   helper (root) eksekusi   -> files/res
 */
public final class Root {

    public static final String HOST = "127.0.0.1";
    public static final int PORT_BASE = 47321;
    public static final int PORT_TRIES = 10;

    private static volatile int activePort = PORT_BASE;

    /* 0 = belum tahu, 1 = lewat helper ma3root.sh, 2 = lewat "su" langsung */
    private static volatile int mode = 0;

    private static File filesDir;
    private static final Object LOCK = new Object();

    private Root() { }

    public static void init(Context ctx) {
        filesDir = ctx.getFilesDir();
    }

    /**
     * Jalankan perintah sebagai root.
     *
     * Urutan: helper ma3root.sh (paling kompatibel, dipakai KernelSU di
     * perangkat ini) lalu "su" langsung (Magisk biasanya mengizinkan).
     */
    public static String run(String cmd) {
        synchronized (LOCK) {
            if (filesDir == null) return "ERR Root.init belum dipanggil";
            if (mode == 0) mode = pilihMode();

            if (mode == 2) {
                String su = viaSu(cmd);
                return su != null ? su : "ERR root tidak tersedia";
            }

            String out = viaHelper(cmd, 20000);
            if (out != null) return out;

            // helper hilang di tengah jalan -> coba su
            mode = 2;
            String su = viaSu(cmd);
            return su != null ? su
                    : "ERR root helper tidak merespon (ma3root.sh belum terpasang?)";
        }
    }

    /** Helper hidup? (cek cepat, tidak menunggu 20 detik) */
    public static boolean helperAktif() {
        synchronized (LOCK) {
            if (filesDir == null) return false;
            String out = viaHelper("echo ok", 4000);
            boolean ok = out != null && out.contains("ok");
            if (ok) mode = 1;
            return ok;
        }
    }

    /** "su" langsung bisa dipakai? */
    public static boolean suAktif() {
        synchronized (LOCK) {
            String out = viaSu("id");
            boolean ok = out != null && out.contains("uid=0");
            if (ok) mode = 2;
            return ok;
        }
    }

    /** Ada akses root lewat salah satu jalur? */
    public static boolean aktif() {
        return helperAktif() || suAktif();
    }

    /** Lupakan mode yang tersimpan (dipakai tombol "Periksa ulang"). */
    public static void reset() {
        synchronized (LOCK) { mode = 0; }
    }

    private static int pilihMode() {
        if (helperAktif()) return 1;
        if (suAktif()) return 2;
        return 2;   // dua-duanya belum ada: su dipakai supaya gagal cepat
    }

    private static String viaHelper(String cmd, long timeoutMs) {
        try {
            File cmdFile = new File(filesDir, "cmd");
            File resFile = new File(filesDir, "res");

            if (resFile.exists()) resFile.delete();
            if (cmdFile.exists()) cmdFile.delete();

            FileOutputStream fo = new FileOutputStream(cmdFile);
            fo.write(cmd.getBytes("UTF-8"));
            fo.close();

            long until = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < until) {
                if (resFile.exists()) {
                    String out = readAll(resFile);
                    resFile.delete();
                    return out == null ? "" : out.trim();
                }
                Thread.sleep(100);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String viaSu(String cmd) {
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
            final Process fp = p;
            final StringBuilder sb = new StringBuilder();
            Thread t = new Thread(new Runnable() {
                public void run() {
                    try {
                        BufferedReader r = new BufferedReader(
                                new InputStreamReader(fp.getInputStream(), "UTF-8"));
                        String line;
                        while ((line = r.readLine()) != null) sb.append(line).append('\n');
                    } catch (Exception e) { }
                }
            });
            t.setDaemon(true);
            t.start();

            long until = System.currentTimeMillis() + 15000;
            while (System.currentTimeMillis() < until) {
                try {
                    p.exitValue();
                    break;
                } catch (IllegalThreadStateException e) {
                    Thread.sleep(100);
                }
            }
            t.join(1000);
            return sb.toString().trim();
        } catch (Exception e) {
            return null;
        } finally {
            if (p != null) {
                try { p.destroy(); } catch (Exception e) { }
            }
        }
    }

    private static String readAll(File f) {
        try {
            FileInputStream in = new FileInputStream(f);
            BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            r.close();
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** Port agent yang terakhir ditemukan. */
    public static int port() { return activePort; }

    /** Cek apakah agent hidup di satu port (kirim PING, tunggu PONG). */
    private static boolean ping(int port) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(HOST, port), 600);
            s.setSoTimeout(1200);
            OutputStream out = s.getOutputStream();
            out.write("PING\n".getBytes("UTF-8"));
            out.flush();
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
            String line = r.readLine();
            return line != null && line.trim().equals("PONG");
        } catch (Exception e) {
            return false;
        } finally {
            try { s.close(); } catch (Exception e2) { }
        }
    }

    /**
     * Agent bisa pindah port kalau port lama masih dipegang sesi sebelumnya,
     * jadi cari di PORT_BASE..PORT_BASE+PORT_TRIES-1.
     */
    public static int findAgentPort() {
        for (int i = 0; i < PORT_TRIES; i++) {
            int p = PORT_BASE + i;
            if (ping(p)) {
                activePort = p;
                return p;
            }
        }
        return -1;
    }

    public static boolean agentAlive() { return findAgentPort() > 0; }
}
