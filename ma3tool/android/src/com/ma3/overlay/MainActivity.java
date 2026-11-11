package com.ma3.overlay;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;


/**
 * Layar peluncur MA3 Mod Menu.
 *
 * Sengaja hanya berisi dua tombol: tampilkan / sembunyikan panel. Sisanya
 * otomatis -- agent disalin dari assets setiap panel mulai, game dibuka dari
 * panel, dan agent dipasang sendiri oleh watchdog setelah umur game cukup.
 */
public class MainActivity extends Activity {

    public static final String AGENT_PATH = "/data/local/tmp/ma3agent.js";
    public static final String INJECTOR_PATH = "/data/local/tmp/frida-inject";

    private TextView status;
    private TextView statusDot;
    private TextView siapHelper;
    private TextView siapInjector;
    private TextView siapIzin;
    private TextView siapGame;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Root.init(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(Ui.vertical(Ui.BG_TOP, Ui.BG_BOTTOM, 0, this));
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 22);
        root.setPadding(pad, Ui.dp(this, 40), pad, Ui.dp(this, 28));
        scroll.addView(root);

        root.addView(buildHeader());
        root.addView(buildStatusCard());
        root.addView(buildKesiapan());
        root.addView(buildActions());
        root.addView(buildFooter());

        setContentView(scroll);

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }

        cekKesiapan();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // izin overlay bisa berubah dari Setelan, jadi periksa lagi
        cekKesiapan();
    }

    /* ------------------------------------------------------------- komponen */

    private View buildHeader() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 16));
        box.setBackground(Ui.outline(Ui.CARD, 20, Ui.STROKE, 1, this));

        LinearLayout baris = new LinearLayout(this);
        baris.setOrientation(LinearLayout.HORIZONTAL);
        baris.setGravity(Gravity.CENTER_VERTICAL);

        TextView badge = Ui.label(this, "MA3", 11, Ui.ACCENT_INK, true);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(Ui.dp(this, 10), Ui.dp(this, 5), Ui.dp(this, 10), Ui.dp(this, 5));
        badge.setBackground(Ui.solid(Ui.ACCENT, 11, this));
        badge.setLetterSpacing(0.08f);
        baris.addView(badge);

        LinearLayout teks = new LinearLayout(this);
        teks.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = Ui.dp(this, 12);
        baris.addView(teks, tlp);

        TextView judul = Ui.label(this, "MA3 Mod Menu", 22, Ui.TEXT, true);
        judul.setLetterSpacing(0.01f);
        teks.addView(judul);

        TextView credit = Ui.label(this, "cracked by addictive378", 11, Ui.ACCENT, false);
        credit.setLetterSpacing(0.03f);
        teks.addView(credit);

        box.addView(baris);

        TextView sub = Ui.label(this,
                "Panel uang & diamond untuk Modern Age 3\n"
                        + "root + agent JNI, tanpa LSPosed",
                12, Ui.MUTED, false);
        sub.setLineSpacing(Ui.dp(this, 3), 1f);
        sub.setPadding(0, Ui.dp(this, 14), 0, 0);
        box.addView(sub);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(this, 14);
        box.setLayoutParams(lp);
        return box;
    }

    private View buildStatusCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        card.setBackground(Ui.outline(Ui.CARD_SOFT, 14, Ui.STROKE, 1, this));

        statusDot = Ui.label(this, "\u25CF", 9, Ui.MUTED, false);
        card.addView(statusDot);

        status = Ui.label(this, "Memeriksa kesiapan...", 11, Ui.TEXT, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = Ui.dp(this, 9);
        card.addView(status, lp);

        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = Ui.dp(this, 16);
        card.setLayoutParams(clp);
        return card;
    }

    private View buildActions() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        box.addView(button("Tampilkan panel", 0, new Runnable() {
            public void run() {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        && !Settings.canDrawOverlays(MainActivity.this)) {
                    setStatus("Izinkan \"Tampilkan di atas aplikasi lain\" dulu.");
                    Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                    return;
                }
                startMyService(new Intent(MainActivity.this, OverlayService.class));
                runOnUiThread(new Runnable() {
                    public void run() { finish(); }
                });
            }
        }));

        box.addView(button("Sembunyikan panel", 3, new Runnable() {
            public void run() {
                stopService(new Intent(MainActivity.this, OverlayService.class));
                setStatus("Panel disembunyikan.");
            }
        }));

        return box;
    }

    private View buildFooter() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, Ui.dp(this, 26), 0, 0);

        View garis = new View(this);
        garis.setBackgroundColor(0x1AFFFFFF);
        box.addView(garis, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 1)));

        TextView note = Ui.label(this,
                "Cukup tampilkan panel sekali. Panel membuka game, memasang agent "
                        + "setelah game siap, dan memasang sesi baru sendiri bila perlu.",
                10, Ui.FAINT, false);
        note.setLineSpacing(Ui.dp(this, 2), 1f);
        note.setPadding(0, Ui.dp(this, 12), 0, 0);
        box.addView(note);

        TextView credit = Ui.label(this, "cracked by addictive378", 10, Ui.ACCENT, false);
        credit.setGravity(Gravity.CENTER);
        credit.setLetterSpacing(0.06f);
        credit.setPadding(0, Ui.dp(this, 14), 0, 0);
        box.addView(credit);
        return box;
    }

    /* --------------------------------------------------------------- tombol */

    /**
     * gaya: 0 = utama (isi aksen), 1 = garis aksen, 2 = netral, 3 = samar
     */
    private View button(String label, int gaya, final Runnable action) {
        TextView b = Ui.label(this, label, 12, Ui.TEXT, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(Ui.dp(this, 10), Ui.dp(this, 15), Ui.dp(this, 10), Ui.dp(this, 15));
        b.setClickable(true);
        b.setFocusable(true);
        b.setLetterSpacing(0.01f);
        if (gaya == 0) {
            b.setBackground(Ui.solid(Ui.ACCENT, 14, this));
            b.setTextColor(Ui.ACCENT_INK);
        } else if (gaya == 1) {
            b.setBackground(Ui.outline(0x00000000, 14, Ui.ACCENT, 1, this));
            b.setTextColor(Ui.ACCENT);
        } else if (gaya == 2) {
            b.setBackground(Ui.outline(Ui.CARD, 14, Ui.STROKE, 1, this));
            b.setTextColor(Ui.TEXT);
        } else {
            b.setBackground(Ui.outline(0x00000000, 14, 0x00000000, 0, this));
            b.setTextColor(Ui.MUTED);
        }
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                new Thread(new Runnable() {
                    public void run() { action.run(); }
                }).start();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(this, 10);
        b.setLayoutParams(lp);
        return b;
    }

    /* ------------------------------------------------------------- kesiapan */

    private View buildKesiapan() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));
        box.setBackground(Ui.outline(Ui.CARD, 16, Ui.STROKE, 1, this));

        TextView judul = Ui.label(this, "KESIAPAN", 9, Ui.MUTED, true);
        judul.setLetterSpacing(0.18f);
        judul.setPadding(0, 0, 0, Ui.dp(this, 6));
        box.addView(judul);

        siapHelper = baris(box, "Helper root");
        siapInjector = baris(box, "frida-inject");
        siapIzin = baris(box, "Izin overlay");
        siapGame = baris(box, "Game");

        LinearLayout aksi = new LinearLayout(this);
        aksi.setOrientation(LinearLayout.HORIZONTAL);
        aksi.setPadding(0, Ui.dp(this, 10), 0, 0);

        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        half.setMargins(Ui.dp(this, 2), 0, Ui.dp(this, 2), 0);
        aksi.addView(tombolKecil("Periksa ulang", 2, new Runnable() {
            public void run() {
                Root.reset();
                cekKesiapan();
            }
        }), half);

        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        half2.setMargins(Ui.dp(this, 2), 0, Ui.dp(this, 2), 0);
        aksi.addView(tombolKecil("Pasang komponen", 1, new Runnable() {
            public void run() { pasangKomponen(); }
        }), half2);

        box.addView(aksi);

        TextView salin = tombolKecil("Salin perintah helper root", 3, new Runnable() {
            public void run() { salinPerintah(); }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.topMargin = Ui.dp(this, 6);
        box.addView(salin, slp);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(this, 16);
        box.setLayoutParams(lp);
        return box;
    }

    /** Satu baris "label ..... status"; mengembalikan TextView statusnya. */
    private TextView baris(LinearLayout parent, String label) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(this, 5), 0, Ui.dp(this, 5));

        TextView dot = Ui.label(this, "\u25CF", 8, Ui.MUTED, false);
        row.addView(dot);

        TextView l = Ui.label(this, label, 11, Ui.TEXT, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = Ui.dp(this, 8);
        row.addView(l, lp);

        TextView v = Ui.label(this, "\u2026", 11, Ui.MUTED, false);
        row.addView(v);

        parent.addView(row);
        return v;
    }

    private void setBaris(final TextView v, final String teks, final int warna) {
        runOnUiThread(new Runnable() {
            public void run() {
                if (v == null) return;
                v.setText(teks);
                v.setTextColor(warna);
            }
        });
    }

    private TextView tombolKecil(String label, int gaya, final Runnable aksi) {
        TextView b = Ui.label(this, label, 11, Ui.TEXT, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(Ui.dp(this, 6), Ui.dp(this, 11), Ui.dp(this, 6), Ui.dp(this, 11));
        b.setClickable(true);
        b.setFocusable(true);
        if (gaya == 1) {
            b.setBackground(Ui.outline(0x00000000, 12, Ui.ACCENT, 1, this));
            b.setTextColor(Ui.ACCENT);
        } else if (gaya == 2) {
            b.setBackground(Ui.outline(Ui.CARD_SOFT, 12, Ui.STROKE, 1, this));
            b.setTextColor(Ui.TEXT);
        } else {
            b.setBackground(Ui.outline(0x00000000, 12, 0x00000000, 0, this));
            b.setTextColor(Ui.MUTED);
        }
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                new Thread(new Runnable() {
                    public void run() { aksi.run(); }
                }).start();
            }
        });
        return b;
    }

    private void cekKesiapan() {
        setBaris(siapHelper, "memeriksa...", Ui.MUTED);
        setBaris(siapInjector, "memeriksa...", Ui.MUTED);
        setBaris(siapIzin, "memeriksa...", Ui.MUTED);
        setBaris(siapGame, "memeriksa...", Ui.MUTED);
        new Thread(new Runnable() {
            public void run() {
                boolean root = Root.aktif();
                setBaris(siapHelper, root ? (Root.helperAktif() ? "aktif" : "aktif (su)")
                                : "belum",
                        root ? Ui.ACCENT : Ui.WARN);

                boolean inj = Setup.injectorAda();
                setBaris(siapInjector, inj ? "ada" : "belum — tekan Pasang komponen",
                        inj ? Ui.ACCENT : Ui.WARN);

                boolean izin = Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                        || Settings.canDrawOverlays(MainActivity.this);
                setBaris(siapIzin, izin ? "diberikan" : "belum — buka di Setelan",
                        izin ? Ui.ACCENT : Ui.WARN);

                final StringBuilder ada = new StringBuilder();
                for (int i = 0; i < Games.SEMUA.length; i++) {
                    if (Games.terpasang(Games.SEMUA[i])) {
                        if (ada.length() > 0) ada.append(" + ");
                        ada.append(Games.nama(Games.SEMUA[i]));
                    }
                }
                boolean game = ada.length() > 0;
                setBaris(siapGame, game ? ada.toString() : "tidak terpasang",
                        game ? Ui.ACCENT : Ui.WARN);

                if (root && inj && game) {
                    setStatus("Siap. Tekan \"Tampilkan panel\", lalu BUKA GAME.");
                } else if (!root) {
                    setStatus("Root belum aktif — pasang helper (tombol salin perintah).");
                } else if (!inj) {
                    setStatus("Komponen belum lengkap — tekan \"Pasang komponen\".");
                } else {
                    setStatus("Game Modern Age 3 belum terpasang.");
                }
            }
        }).start();
    }

    private void pasangKomponen() {
        setStatus("Memasang frida-inject ke /data/local/tmp ...");
        String err = Setup.pasangInjector(this);
        if (err != null) {
            setStatus("Gagal memasang komponen: " + err);
            return;
        }
        setStatus("Komponen terpasang. Panel siap dipakai.");
        cekKesiapan();
    }

    private void salinPerintah() {
        String cmd = Setup.perintahHelper(this);
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("perintah", cmd));
            setStatus("Perintah disalin. Tempel di Termux (root), lalu tekan Periksa ulang.");
        } catch (Exception e) {
            setStatus("Gagal menyalin: " + e);
        }
    }

    private void setStatus(final String text) {
        runOnUiThread(new Runnable() {
            public void run() {
                status.setText(text);
                if (statusDot != null) {
                    int warna = text.startsWith("Siap") ? Ui.ACCENT
                            : (text.startsWith("frida") || text.startsWith("ERR")
                               || text.startsWith("Izinkan") ? Ui.WARN : Ui.TEXT);
                    statusDot.setTextColor(warna);
                }
            }
        });
    }

    private void startMyService(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }
}
