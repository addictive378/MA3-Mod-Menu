package com.ma3.overlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Service panel melayang (TYPE_APPLICATION_OVERLAY).
 *
 * Dua pekerja latar:
 *  - worker  : koneksi TCP ke agent (127.0.0.1:47321) + kirim/terima perintah.
 *  - watchdog: memantau proses game; bila agent mati / game restart, agent
 *              di-inject ulang otomatis lewat su + frida-inject.
 */
public class OverlayService extends Service {

    public static final String AGENT_PATH = "/data/local/tmp/ma3agent.js";
    private static final String PREF = "ma3";
    private static final String KEY_GAME = "game";
    public static final String INJECTOR_PATH = "/data/local/tmp/frida-inject";

    private WindowManager wm;
    private View panel;
    private WindowManager.LayoutParams params;
    private View mini;
    private WindowManager.LayoutParams miniParams;
    private TextView miniGold;
    private TextView miniDiam;
    private boolean minimized = false;

    private TextView statusView;
    private TextView goldValue;
    private TextView diamValue;
    private TextView statusDot;
    private EditText goldInput;
    private EditText diamInput;
    private TextView lockBtn;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<String>();
    private volatile boolean running = true;
    private volatile boolean locked = false;
    private volatile boolean connected = false;
    private volatile boolean injectedPending = false;
    private String lastGold = "?";
    private String lastCristal = "?";
    private String lastPid = "";
    private volatile String activePkg = Games.PREMIUM;
    private Thread worker;
    private Thread watchdog;

    private static final int INJECT_AFTER_S = 25;
    private static final int COLOR_ACCENT = Ui.ACCENT;
    private static final int COLOR_WARN = Ui.WARN;
    private static final int COLOR_ERR = Ui.ERR;
    private static final int COLOR_TEXT = Ui.TEXT;
    private static final int COLOR_MUTED = Ui.MUTED;

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        Root.init(this);
        pasangAgent(pilihanGame());
        new Thread(new Runnable() {
            public void run() {
                // frida-inject ikut di APK; salin sekali ke /data/local/tmp
                String err = Setup.pasangInjector(OverlayService.this);
                if (err != null) android.util.Log.w("MA3", "injector: " + err);
            }
        }).start();
        startAsForeground();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        buildPanel();

        worker = new Thread(new Runnable() { public void run() { workerLoop(); } });
        worker.setDaemon(true);
        worker.start();

        watchdog = new Thread(new Runnable() { public void run() { watchdogLoop(); } });
        watchdog.setDaemon(true);
        watchdog.start();
    }

    /**
     * Salin assets/ma3agent.js ke /data/local/tmp/ma3agent.js.
     *
     * Dilakukan setiap service mulai supaya agent di perangkat selalu sama
     * dengan versi yang ada di APK (tidak perlu tekan "Pasang agent" lagi).
     */
    private void pasangAgent(String pkg) {
        try {
            InputStream in = getAssets().open("ma3agent.js");
            BufferedReader rd = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = rd.readLine()) != null) sb.append(line).append('\n');
            rd.close();

            // agent menulis log ke folder data game yang di-inject
            String isi = sb.toString().replace("__MA3_LOG__",
                    "/data/user/0/" + pkg + "/files/ma3.log");

            File tmp = new File(getCacheDir(), "ma3agent.js");
            FileOutputStream fo = new FileOutputStream(tmp);
            fo.write(isi.getBytes("UTF-8"));
            fo.close();
            String out = Root.run("cp " + tmp.getAbsolutePath() + " " + AGENT_PATH
                    + " && chmod 644 " + AGENT_PATH
                    + " && chcon u:object_r:shell_data_file:s0 " + AGENT_PATH + " 2>/dev/null");
            if (out.startsWith("ERR")) {
                android.util.Log.w("MA3", "pasang agent: " + out);
            }
        } catch (Exception e) {
            android.util.Log.w("MA3", "pasang agent gagal: " + e);
        }
    }

    private void startAsForeground() {
        String ch = "ma3";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel c = new NotificationChannel(ch, "MA3 Overlay",
                    NotificationManager.IMPORTANCE_MIN);
            nm.createNotificationChannel(c);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, ch)
                : new Notification.Builder(this);
        b.setContentTitle("MA3 Mod Menu aktif")
                .setContentText("Panel uang & diamond melayang")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setOngoing(true);
        startForeground(7, b.build());
    }

    /* ------------------------------------------------------------------- UI */

    private void buildPanel() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(12), dp(14), dp(12));

        GradientDrawable bg = Ui.vertical(Ui.BG_TOP, Ui.BG_BOTTOM, 22, this);
        bg.setStroke(dp(1), 0x3346E0B0);
        root.setBackground(bg);

        /* ---------------------------------------------------------- header */
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView badge = Ui.label(this, "MA3", 9, Ui.ACCENT_INK, true);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(8), dp(4), dp(8), dp(4));
        badge.setBackground(Ui.solid(Ui.ACCENT, 9, this));
        badge.setLetterSpacing(0.08f);
        header.addView(badge);

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(9);
        header.addView(titleBox, tlp);

        TextView judul = Ui.label(this, "MA3 Mod Menu", 14, Ui.TEXT, true);
        judul.setLetterSpacing(0.01f);
        titleBox.addView(judul);

        TextView credit = Ui.label(this, "cracked by addictive378", 9, Ui.ACCENT, false);
        credit.setLetterSpacing(0.03f);
        titleBox.addView(credit);

        TextView kecilkan = Ui.label(this, "\u2212", 16, Ui.MUTED, false);
        kecilkan.setGravity(Gravity.CENTER);
        kecilkan.setPadding(dp(9), dp(3), dp(9), dp(5));
        kecilkan.setBackground(Ui.outline(Ui.CARD, 10, Ui.STROKE, 1, this));
        kecilkan.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { minimize(); }
        });
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        klp.rightMargin = dp(6);
        header.addView(kecilkan, klp);

        TextView tutup = Ui.label(this, "\u2715", 13, Ui.MUTED, false);
        tutup.setGravity(Gravity.CENTER);
        tutup.setPadding(dp(9), dp(5), dp(9), dp(5));
        tutup.setBackground(Ui.outline(Ui.CARD, 10, Ui.STROKE, 1, this));
        tutup.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { stopSelf(); }
        });
        header.addView(tutup);
        root.addView(header);

        /* ---------------------------------------------------------- status */
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setPadding(dp(10), dp(7), dp(10), dp(7));
        statusRow.setBackground(Ui.outline(Ui.CARD_SOFT, 12, Ui.STROKE, 1, this));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(11);
        root.addView(statusRow, slp);

        statusDot = Ui.label(this, "\u25CF", 8, Ui.WARN, false);
        statusRow.addView(statusDot);

        statusView = Ui.label(this, "menyiapkan...", 10, Ui.MUTED, false);
        LinearLayout.LayoutParams svlp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        svlp.leftMargin = dp(7);
        statusRow.addView(statusView, svlp);

        /* ------------------------------------------------------- nilai */
        LinearLayout cards = new LinearLayout(this);
        cards.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(10);
        root.addView(cards, clp);

        goldValue = makeCard(cards, "UANG", Ui.GOLD, true);
        diamValue = makeCard(cards, "DIAMOND", Ui.DIAMOND, false);

        /* ------------------------------------------------------ input */
        goldInput = makeInput(root, "nominal uang", 10);
        diamInput = makeInput(root, "nominal diamond", 7);

        /* ----------------------------------------------------- tombol */
        LinearLayout row1 = makeRow(root, 11);
        row1.addView(makeButton("SET", 0, new View.OnClickListener() {
            public void onClick(View v) { sendSet(false); }
        }), weightParams());
        row1.addView(makeButton("TAMBAH", 1, new View.OnClickListener() {
            public void onClick(View v) { sendSet(true); }
        }), weightParams());

        LinearLayout row2 = makeRow(root, 6);
        row2.addView(makeButton("SIMPAN", 2, new View.OnClickListener() {
            public void onClick(View v) { queue.offer("SAVE"); }
        }), weightParams());
        lockBtn = makeButton("KUNCI", 2, new View.OnClickListener() {
            public void onClick(View v) { toggleLock(); }
        });
        row2.addView(lockBtn, weightParams());

        LinearLayout row3 = makeRow(root, 4);
        row3.addView(makeButton("BUKA GAME", 3, new View.OnClickListener() {
            public void onClick(View v) {
                new Thread(new Runnable() {
                    public void run() {
                        lastPid = "";
                        String pkg = pilihanGame();
                        activePkg = pkg;
                        Root.run("am start -n " + Games.activity(pkg));
                    }
                }).start();
            }
        }), weightParams());

        /* ----------------------------------------------------- footer */
        View garis = new View(this);
        garis.setBackgroundColor(0x1AFFFFFF);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        glp.topMargin = dp(10);
        root.addView(garis, glp);

        TextView footer = Ui.label(this, "cracked by addictive378", 9, 0xFF7C93A1, false);
        footer.setGravity(Gravity.CENTER);
        footer.setLetterSpacing(0.06f);
        footer.setPadding(0, dp(7), 0, dp(1));
        root.addView(footer);

        panel = root;

        params = new WindowManager.LayoutParams(
                dp(318),
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = dp(16);
        params.y = dp(56);

        header.setOnTouchListener(new View.OnTouchListener() {
            private float dx, dy;
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        dx = e.getRawX() - params.x;
                        dy = e.getRawY() - params.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        params.x = (int) (e.getRawX() - dx);
                        params.y = (int) (e.getRawY() - dy);
                        clampNow(panel, params);
                        try { wm.updateViewLayout(panel, params); } catch (Exception ex) { }
                        return true;
                    default:
                        return false;
                }
            }
        });

        try {
            wm.addView(panel, params);
        } catch (Exception e) {
            toast("Gagal menampilkan overlay: " + e.getMessage());
            stopSelf();
        }
    }

    private int overlayType() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    /* ------------------------------------------------------------- minimize */

    /** Panel dikecilkan jadi pil kecil; state & isian tetap hidup. */
    private void minimize() {
        if (minimized) return;
        minimized = true;
        try { wm.removeView(panel); } catch (Exception e) { }
        if (mini == null) buildMini();
        miniParams.x = params.x;
        miniParams.y = params.y;
        try {
            wm.addView(mini, miniParams);
        } catch (Exception e) {
            minimized = false;
            try { wm.addView(panel, params); } catch (Exception ex) { }
            toast("Gagal mengecilkan panel: " + e.getMessage());
            return;
        }
        clamp(mini, miniParams);
        if (miniGold != null) miniGold.setText(pretty(lastGold));
        if (miniDiam != null) miniDiam.setText(pretty(lastCristal));
    }

    /** Kembali ke panel penuh di posisi pil tadi. */
    private void restore() {
        if (!minimized) return;
        minimized = false;
        try { wm.removeView(mini); } catch (Exception e) { }
        params.x = miniParams.x;
        params.y = miniParams.y;
        try {
            wm.addView(panel, params);
        } catch (Exception e) {
            toast("Gagal membuka panel: " + e.getMessage());
            return;
        }
        clamp(panel, params);
    }

    /** Jaga tampilan tetap di dalam layar (mis. setelah rotasi). */
    private void clamp(final View v, final WindowManager.LayoutParams lp) {
        v.post(new Runnable() {
            public void run() {
                int x = lp.x;
                int y = lp.y;
                clampNow(v, lp);
                if (x != lp.x || y != lp.y) {
                    try { wm.updateViewLayout(v, lp); } catch (Exception e) { }
                }
            }
        });
    }

    /** Batasi posisi supaya tidak keluar layar dan tidak menutupi status bar. */
    private void clampNow(View v, WindowManager.LayoutParams lp) {
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        int w = v.getWidth() > 0 ? v.getWidth() : dp(318);
        int h = v.getHeight() > 0 ? v.getHeight() : dp(80);
        int atas = dp(24);
        lp.x = Math.max(0, Math.min(lp.x, Math.max(0, sw - w)));
        lp.y = Math.max(atas, Math.min(lp.y, Math.max(atas, sh - h)));
    }

    /** Pil kecil: MA3 + nilai uang + nilai diamond; ketuk untuk membuka. */
    private void buildMini() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(9), dp(6), dp(11), dp(6));

        GradientDrawable bg = Ui.vertical(Ui.BG_TOP, Ui.BG_BOTTOM, 18, this);
        bg.setStroke(dp(1), 0x5546E0B0);
        box.setBackground(bg);

        TextView badge = Ui.label(this, "MA3", 8, Ui.ACCENT_INK, true);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(7), dp(3), dp(7), dp(3));
        badge.setBackground(Ui.solid(Ui.ACCENT, 8, this));
        badge.setLetterSpacing(0.08f);
        box.addView(badge);

        miniGold = Ui.label(this, "?", 12, Ui.GOLD, true);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        glp.leftMargin = dp(8);
        box.addView(miniGold, glp);

        TextView titik = Ui.label(this, "\u00B7", 11, Ui.FAINT, false);
        LinearLayout.LayoutParams tlp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp2.leftMargin = dp(7);
        box.addView(titik, tlp2);

        miniDiam = Ui.label(this, "?", 11, Ui.DIAMOND, false);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        dlp.leftMargin = dp(7);
        box.addView(miniDiam, dlp);

        TextView buka = Ui.label(this, "\u25B4", 9, Ui.MUTED, false);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.leftMargin = dp(8);
        box.addView(buka, blp);

        miniParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        miniParams.gravity = Gravity.TOP | Gravity.START;

        box.setOnTouchListener(new View.OnTouchListener() {
            private float dx, dy, sx, sy;
            private boolean geser;

            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        dx = e.getRawX() - miniParams.x;
                        dy = e.getRawY() - miniParams.y;
                        sx = e.getRawX();
                        sy = e.getRawY();
                        geser = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (Math.abs(e.getRawX() - sx) > dp(6)
                                || Math.abs(e.getRawY() - sy) > dp(6)) {
                            geser = true;
                        }
                        if (geser) {
                            miniParams.x = (int) (e.getRawX() - dx);
                            miniParams.y = (int) (e.getRawY() - dy);
                            clampNow(mini, miniParams);
                            try { wm.updateViewLayout(mini, miniParams); } catch (Exception ex) { }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!geser) restore();
                        return true;
                    default:
                        return false;
                }
            }
        });

        mini = box;
    }

    /** Kartu nilai (label kecil + angka besar). Mengembalikan TextView angkanya. */
    private TextView makeCard(LinearLayout parent, String judul, int warna, boolean kiri) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(11), dp(9), dp(11), dp(9));
        card.setBackground(Ui.outline(Ui.CARD_SOFT, 14, Ui.STROKE, 1, this));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        if (kiri) lp.rightMargin = dp(4); else lp.leftMargin = dp(4);
        parent.addView(card, lp);

        TextView l = Ui.label(this, judul, 8, Ui.MUTED, true);
        l.setLetterSpacing(0.16f);
        card.addView(l);

        TextView v = Ui.label(this, "?", 16, warna, true);
        v.setPadding(0, dp(2), 0, 0);
        card.addView(v);
        return v;
    }

    private EditText makeInput(LinearLayout parent, String hint, int topMargin) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextSize(12);
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(Ui.FAINT);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setSingleLine(true);
        e.setPadding(dp(11), dp(8), dp(11), dp(8));
        e.setBackground(Ui.outline(Ui.CARD_SOFT, 12, Ui.STROKE, 1, this));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topMargin);
        parent.addView(e, lp);
        return e;
    }

    private LinearLayout makeRow(LinearLayout parent, int topMargin) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topMargin);
        parent.addView(row, lp);
        return row;
    }

    /**
     * gaya: 0 = utama (isi aksen), 1 = garis aksen, 2 = netral, 3 = samar
     */
    private TextView makeButton(String text, int gaya, View.OnClickListener l) {
        TextView b = Ui.label(this, text, 10, Ui.TEXT, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(6), dp(9), dp(6), dp(9));
        b.setClickable(true);
        b.setFocusable(true);
        b.setLetterSpacing(0.04f);
        if (gaya == 0) {
            b.setBackground(Ui.solid(Ui.ACCENT, 12, this));
            b.setTextColor(Ui.ACCENT_INK);
        } else if (gaya == 1) {
            b.setBackground(Ui.outline(0x00000000, 12, Ui.ACCENT, 1, this));
            b.setTextColor(Ui.ACCENT);
        } else if (gaya == 2) {
            b.setBackground(Ui.outline(Ui.CARD, 12, Ui.STROKE, 1, this));
            b.setTextColor(Ui.TEXT);
        } else {
            b.setBackground(Ui.outline(0x00000000, 12, 0x00000000, 0, this));
            b.setTextColor(Ui.MUTED);
        }
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams weightParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(3), 0, dp(3), 0);
        return lp;
    }

    /* -------------------------------------------------------------- perintah */

    private void sendSet(boolean add) {
        String g = goldInput.getText().toString().trim();
        String d = diamInput.getText().toString().trim();
        if (g.isEmpty() && d.isEmpty()) {
            toast("Isi nominal dulu");
            return;
        }
        queue.offer((add ? "ADD " : "SET ") + (g.isEmpty() ? "-" : g)
                + " " + (d.isEmpty() ? "-" : d));
        if (locked) {
            queue.offer("LOCK " + (g.isEmpty() ? "-" : g) + " " + (d.isEmpty() ? "-" : d));
        }
    }

    private void toggleLock() {
        if (locked) {
            queue.offer("UNLOCK");
            locked = false;
            lockBtn.setText("KUNCI");
            toast("Kunci nilai dimatikan");
            return;
        }
        String g = goldInput.getText().toString().trim();
        String d = diamInput.getText().toString().trim();
        if (g.isEmpty() && d.isEmpty()) {
            if (!"?".equals(lastGold)) g = strip(lastGold);
            if (!"?".equals(lastCristal)) d = strip(lastCristal);
        }
        if (g.isEmpty() && d.isEmpty()) {
            toast("Isi nominal atau tunggu game siap");
            return;
        }
        queue.offer("LOCK " + (g.isEmpty() ? "-" : g) + " " + (d.isEmpty() ? "-" : d));
        locked = true;
        lockBtn.setText("BUKA KUNCI");
        toast("Nilai dijaga otomatis");
    }

    private String strip(String v) {
        int i = v.indexOf('.');
        return i > 0 ? v.substring(0, i) : v;
    }

    /* ---------------------------------------------------------------- worker */

    private void workerLoop() {
        Socket s = null;
        BufferedReader in = null;
        BufferedWriter out = null;

        while (running) {
            try {
                if (s == null) {
                    int port = Root.findAgentPort();
                    if (port < 0) {
                        setConnected(false);
                        try { Thread.sleep(1200); } catch (InterruptedException ie) { }
                        continue;
                    }
                    s = new Socket();
                    s.connect(new InetSocketAddress(Root.HOST, port), 1500);
                    s.setSoTimeout(6000);
                    in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
                    out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), "UTF-8"));
                    setConnected(true);
                }

                String cmd = queue.poll(2000, TimeUnit.MILLISECONDS);
                if (cmd == null) cmd = "GET";

                out.write(cmd);
                out.write("\n");
                out.flush();

                String resp = in.readLine();
                if (resp == null) throw new java.io.IOException("ditutup");
                handleReply(resp);
            } catch (Exception e) {
                setConnected(false);
                try { if (s != null) s.close(); } catch (Exception e2) { }
                s = null;
                in = null;
                out = null;
                try { Thread.sleep(1200); } catch (InterruptedException ie) { }
            }
        }
    }

    private void handleReply(String resp) {
        final String r = resp.trim();
        if (r.startsWith("WAIT")) {
            String sisa = r.length() > 4 ? r.substring(4).trim() : "";
            setStatus("menunggu game siap" + (sisa.isEmpty() ? "" : " (" + sisa + " dtk)"), COLOR_MUTED);
            return;
        }
        if (r.startsWith("ERR")) {
            setStatus(r, COLOR_ERR);
            return;
        }
        if (!r.startsWith("OK ") || r.startsWith("OK saved")
                || r.startsWith("OK locked") || r.startsWith("OK unlocked")) {
            return;
        }
        String[] p = r.split("\\s+");
        if (p.length < 3) return;
        lastGold = p[1];
        lastCristal = p[2];
        ui.post(new Runnable() {
            public void run() {
                goldValue.setText(pretty(lastGold));
                diamValue.setText(pretty(lastCristal));
                if (miniGold != null) miniGold.setText(pretty(lastGold));
                if (miniDiam != null) miniDiam.setText(pretty(lastCristal));
            }
        });
    }

    private String pretty(String v) {
        try {
            double d = Double.parseDouble(v);
            if (d >= 1_000_000_000d) return String.format("%.2f M", d / 1_000_000_000d);
            if (d >= 1_000_000d) return String.format("%.2f jt", d / 1_000_000d);
            if (d >= 1_000d) return String.format("%.1f rb", d / 1_000d);
            return String.format("%.0f", d);
        } catch (Exception e) {
            return v;
        }
    }

    /* -------------------------------------------------------------- watchdog */

    private void watchdogLoop() {
        int fails = 0;
        while (running) {
            try { Thread.sleep(3000); } catch (InterruptedException e) { break; }
            if (!running) break;

            if (Root.agentAlive()) {
                fails = 0;
                continue;
            }
            fails++;
            if (fails < 2) continue;   // beri kesempatan sesi lama menutup
            fails = 0;
            doInject(false);
        }
    }

    /**
     * Umur proses game (detik), atau -1 kalau tidak terbaca.
     *
     * Agent TIDAK boleh di-inject saat proses game masih muda: JNI dari sesi
     * yang dipasang terlalu awal selalu berakhir "access violation" dan sesi
     * itu rusak permanen. Di perangkat ini batas amannya ~18 detik.
     *
     * Perhitungannya dilakukan di sini (bukan lewat shell) karena helper
     * menjalankan `sh -c "$req"`, sehingga $(...) dan $22 di dalam perintah
     * akan diekspansi lebih dulu oleh shell pembungkus dan hasilnya salah.
     */
    private long gameAgeSec(String p) {
        try {
            String up = Root.run("cat /proc/uptime").trim();
            String st = Root.run("cat /proc/" + p + "/stat").trim();
            double uptime = Double.parseDouble(up.split("\\s+")[0]);
            int tutup = st.lastIndexOf(')');
            String[] f = st.substring(tutup + 2).trim().split("\\s+");
            long ticks = Long.parseLong(f[19]);   // field 22 = starttime
            return (long) (uptime - ticks / 100.0);
        } catch (Exception e) {
            return -1;
        }
    }

    private synchronized void doInject(boolean force) {
        if (injectedPending) return;
        injectedPending = true;
        try {
            String pkg = null;
            String pid = "";
            for (int i = 0; i < Games.SEMUA.length; i++) {
                pid = Root.run("pidof " + Games.SEMUA[i]).trim();
                if (!pid.isEmpty() && !pid.startsWith("ERR")) {
                    pkg = Games.SEMUA[i];
                    break;
                }
            }
            if (pkg == null) {
                setStatus("game belum berjalan \u2014 tekan BUKA GAME", COLOR_WARN);
                lastPid = "";
                return;
            }
            activePkg = pkg;
            simpanGame(pkg);
            pasangAgent(pkg);
            final String p = pid.split("\\s+")[0];

            long umur = gameAgeSec(p);
            if (umur >= 0 && umur < INJECT_AFTER_S) {
                setStatus("menunggu game siap (" + (INJECT_AFTER_S - umur) + " dtk)", COLOR_WARN);
                lastPid = "";
                return;
            }

            /*
             * Sesi agent punya umur terbatas (~40 detik) lalu JNI-nya fault
             * permanen. Karena itu sesi baru selalu dipasang otomatis: buang
             * injektor lama, lalu inject ulang. Proses game tidak terpengaruh.
             */
            if (!force && p.equals(lastPid)) {
                setStatus("sesi agent habis \u2014 pasang sesi baru", COLOR_WARN);
            }
            lastPid = p;
            String injErr = Setup.pasangInjector(this);
            if (injErr != null) {
                setStatus("frida-inject belum ada \u2014 " + injErr, COLOR_ERR);
                return;
            }

            Root.run("pkill -f frida-inject");
            try { Thread.sleep(400); } catch (InterruptedException e) { }
            setStatus("inject ke pid " + p + " ...", COLOR_WARN);

            Root.run("setsid " + INJECTOR_PATH + " -p " + p + " -s " + AGENT_PATH
                    + " > /data/local/tmp/ma3-agent.out 2>&1 < /dev/null &");

            for (int i = 0; i < 12; i++) {
                try { Thread.sleep(600); } catch (InterruptedException e) { break; }
                if (Root.agentAlive()) {
                    setStatus("agent aktif \u00B7 " + Games.nama(activePkg) + " (pid " + p + ")",
                            COLOR_ACCENT);
                    return;
                }
            }
            setStatus("inject gagal \u2014 tekan INJECT", COLOR_ERR);
        } finally {
            injectedPending = false;
        }
    }

    /** Paket yang dipakai: yang sedang jalan, atau pilihan terakhir, atau yang terpasang. */
    private String pilihanGame() {
        String jalan = Games.berjalan();
        if (jalan != null) {
            simpanGame(jalan);
            return jalan;
        }
        String pref = getSharedPreferences(PREF, MODE_PRIVATE).getString(KEY_GAME, null);
        if (pref != null && Games.terpasang(pref)) return pref;
        String ada = Games.terpasangSatu();
        return ada != null ? ada : (pref != null ? pref : Games.PREMIUM);
    }

    private void simpanGame(String pkg) {
        if (!pkg.equals(getSharedPreferences(PREF, MODE_PRIVATE).getString(KEY_GAME, null))) {
            getSharedPreferences(PREF, MODE_PRIVATE).edit().putString(KEY_GAME, pkg).commit();
        }
    }

    private void setConnected(final boolean c) {
        connected = c;
        ui.post(new Runnable() {
            public void run() {
                if (c) {
                    statusView.setText("terhubung ke agent \u00B7 " + Games.nama(activePkg));
                    statusView.setTextColor(COLOR_ACCENT);
                    if (statusDot != null) statusDot.setTextColor(COLOR_ACCENT);
                } else {
                    statusView.setText("agent belum terhubung");
                    statusView.setTextColor(COLOR_WARN);
                    if (statusDot != null) statusDot.setTextColor(COLOR_WARN);
                }
            }
        });
    }

    private void setStatus(final String text, final int color) {
        ui.post(new Runnable() {
            public void run() {
                if (connected && color == COLOR_WARN) return;   // jangan timpa status terhubung
                statusView.setText(text);
                statusView.setTextColor(color);
                if (statusDot != null) statusDot.setTextColor(color);
            }
        });
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private void toast(final String s) {
        ui.post(new Runnable() {
            public void run() { Toast.makeText(OverlayService.this, s, Toast.LENGTH_SHORT).show(); }
        });
    }

    @Override
    public void onDestroy() {
        running = false;
        if (worker != null) worker.interrupt();
        if (watchdog != null) watchdog.interrupt();
        if (wm != null) {
            if (panel != null) {
                try { wm.removeView(panel); } catch (Exception e) { }
            }
            if (mini != null) {
                try { wm.removeView(mini); } catch (Exception e) { }
            }
        }
        stopForeground(true);
        // panel ditutup -> agent tidak perlu jalan lagi; watchdog akan
        // memasangnya kembali saat panel ditampilkan lagi.
        final Thread matikan = new Thread(new Runnable() {
            public void run() { Root.run("pkill -f frida-inject"); }
        });
        matikan.setDaemon(true);
        matikan.start();
        super.onDestroy();
    }
}
