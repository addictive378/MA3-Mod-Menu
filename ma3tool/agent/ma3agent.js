/*
 * MA3 Agent -- berjalan DI DALAM proses game Modern Age 3
 * (com.oxiwyle.modernage3premium atau com.oxiwyle.modernage3), dimuat oleh
 * frida-inject (root).
 *
 * Kenapa JNI mentah: frida-java-bridge mem-patch ART, dan PairIP
 * (libpairipcore.so) mendeteksinya lalu mematikan proses. Agent ini tidak
 * menyentuh ART; semua akses Java lewat NativeFunction ke vtable JNIEnv.
 *
 * Kenapa server-nya loop blocking di badan skrip (bukan setInterval):
 * panggilan JNI dari callback timer di proses ini berakhir
 * "access violation accessing 0x0", sedangkan panggilan dari thread tempat
 * skrip dimuat aman. Jadi server + JNI jalan di thread itu, memakai recv
 * non-blocking dan usleep.
 *
 * Kenapa semua referensi Java di-resolve ulang tiap permintaan:
 * jclass/jobject yang disimpan lama menjadi basi begitu GC game memindahkan
 * objek (gejalanya: semua panggilan JNI sesudahnya fault permanen). Dengan
 * resolve ulang di dalam PushLocalFrame/PopLocalFrame, tiap permintaan selalu
 * memakai referensi segar dan state tidak pernah "teracuni".
 *
 * Protokol: satu perintah per baris, satu balasan per baris.
 *   PING                    -> PONG
 *   GET                     -> OK <gold> <cristal>
 *   SET <gold> <cristal>    -> OK <gold> <cristal>   ('-' = lewati)
 *   ADD <gold> <cristal>    -> OK <gold> <cristal>   ('-' = lewati)
 *   SAVE                    -> OK saved
 *   LOCK <gold> <cristal>   -> OK locked            (tempel ulang bila turun)
 *   UNLOCK                  -> OK unlocked
 *   DIAG                    -> OK ...               (tanpa JNI)
 *   QUIT                    -> OK bye
 */

var PORT_BASE = 47321;
var PORT_TRIES = 10;

/* ------------------------------------------------------------------ logging */

/* Aplikasi mengganti penanda ini dengan folder game yang sedang di-inject. */
var LOGPATH = "__MA3_LOG__";
var logFile = null;

function log(s) {
    try { send(String(s)); } catch (e) { }
    try {
        if (logFile === null) logFile = new File(LOGPATH, "a");
        logFile.write(String(s) + "\n");
        logFile.flush();
    } catch (e2) { }
}

/* ------------------------------------------------------------------ JNI setup */

function u8(s) { return Memory.allocUtf8String(s); }

var P = Process.pointerSize;

var getVMs = new NativeFunction(Module.findGlobalExportByName("JNI_GetCreatedJavaVMs"),
    'int', ['pointer', 'int', 'pointer']);
var vmsBuf = Memory.alloc(P), cntBuf = Memory.alloc(4);
getVMs(vmsBuf, 1, cntBuf);
var VM = vmsBuf.readPointer();
if (VM.isNull()) throw new Error("JavaVM tidak ditemukan");

var vmTable = VM.readPointer();
function vmFn(i, ret, args) { return new NativeFunction(vmTable.add(i * P).readPointer(), ret, args); }
var attachCurrent = vmFn(4, 'int', ['pointer', 'pointer', 'pointer']);

var envBuf = Memory.alloc(P);
var attachRc = attachCurrent(VM, envBuf, ptr(0));
if (attachRc !== 0) throw new Error("AttachCurrentThread gagal rc=" + attachRc);

var ENV = null, envTable = null;
var FindClass, ExceptionOccurred, ExceptionClear, PushLocalFrame, PopLocalFrame,
    NewObjectA, GetMethodID, CallObjectMethod3, CallObjectMethod4, CallVoidMethod5,
    GetStaticMethodID, CallStaticObjectMethod3, CallStaticObjectMethodZ,
    CallStaticVoidMethod4, NewStringUTF, GetStringUTFChars, ReleaseStringUTFChars;

function jf(i, ret, args) { return new NativeFunction(envTable.add(i * P).readPointer(), ret, args); }

function setEnv(env) {
    ENV = env;
    envTable = env.readPointer();
    FindClass = jf(6, 'pointer', ['pointer', 'pointer']);
    ExceptionOccurred = jf(15, 'pointer', ['pointer']);
    ExceptionClear = jf(17, 'void', ['pointer']);
    PushLocalFrame = jf(19, 'int', ['pointer', 'int']);
    PopLocalFrame = jf(20, 'pointer', ['pointer', 'pointer']);
    NewObjectA = jf(30, 'pointer', ['pointer', 'pointer', 'pointer', 'pointer']);
    GetMethodID = jf(33, 'pointer', ['pointer', 'pointer', 'pointer', 'pointer']);
    CallObjectMethod3 = jf(34, 'pointer', ['pointer', 'pointer', 'pointer']);
    CallObjectMethod4 = jf(34, 'pointer', ['pointer', 'pointer', 'pointer', 'pointer']);
    CallVoidMethod5 = jf(61, 'void', ['pointer', 'pointer', 'pointer', 'pointer', 'pointer']);
    GetStaticMethodID = jf(113, 'pointer', ['pointer', 'pointer', 'pointer', 'pointer']);
    CallStaticObjectMethod3 = jf(114, 'pointer', ['pointer', 'pointer', 'pointer']);
    CallStaticObjectMethodZ = jf(114, 'pointer', ['pointer', 'pointer', 'pointer', 'pointer', 'int', 'pointer']);
    CallStaticVoidMethod4 = jf(141, 'void', ['pointer', 'pointer', 'pointer', 'pointer']);
    NewStringUTF = jf(167, 'pointer', ['pointer', 'pointer']);
    GetStringUTFChars = jf(169, 'pointer', ['pointer', 'pointer', 'pointer']);
    ReleaseStringUTFChars = jf(170, 'void', ['pointer', 'pointer', 'pointer']);
}

setEnv(envBuf.readPointer());

function pendingException(tag) {
    var e = ExceptionOccurred(ENV);
    if (e.isNull()) return false;
    ExceptionClear(ENV);
    log("EXC " + tag);
    return true;
}

function jstr(s) { return NewStringUTF(ENV, u8(s)); }

function readStr(js) {
    if (js === null || js.isNull()) return null;
    var p = GetStringUTFChars(ENV, js, ptr(0));
    if (p.isNull()) { pendingException("GetStringUTFChars"); return null; }
    var v = p.readUtf8String();
    ReleaseStringUTFChars(ENV, js, p);
    return v;
}

/* --------------------------------------------------------- resolve per permintaan */

/*
 * Semua referensi Java dibuat ulang di sini, di dalam local frame milik
 * pemanggil. Tidak ada yang disimpan antar permintaan, jadi GC game tidak
 * bisa membuat referensi kita basi.
 */
function resolve() {
    var AT = FindClass(ENV, u8("android/app/ActivityThread"));
    if (AT.isNull()) { pendingException("FindClass ActivityThread"); return null; }
    var curApp = GetStaticMethodID(ENV, AT, u8("currentApplication"),
        u8("()Landroid/app/Application;"));
    var app = CallStaticObjectMethod3(ENV, AT, curApp);
    if (pendingException("currentApplication") || app.isNull()) return null;

    var Context = FindClass(ENV, u8("android/content/Context"));
    var getCL = GetMethodID(ENV, Context, u8("getClassLoader"), u8("()Ljava/lang/ClassLoader;"));
    var loader = CallObjectMethod3(ENV, app, getCL);
    if (pendingException("getClassLoader") || loader.isNull()) return null;

    var ClassCls = FindClass(ENV, u8("java/lang/Class"));
    var forName = GetStaticMethodID(ENV, ClassCls, u8("forName"),
        u8("(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;"));
    var nameStr = jstr("com.oxiwyle.modernage2.models.PlayerCountry");
    var PC = CallStaticObjectMethodZ(ENV, ClassCls, forName, nameStr, 0, loader);
    if (pendingException("forName PlayerCountry") || PC.isNull()) return null;

    var BD = FindClass(ENV, u8("java/math/BigDecimal"));
    if (BD.isNull()) { pendingException("FindClass BigDecimal"); return null; }

    var r = {
        PC: PC,
        BD: BD,
        r6: null,
        x91: null,
        getInstance: GetStaticMethodID(ENV, PC, u8("getInstance"),
            u8("()Lcom/oxiwyle/modernage2/models/PlayerCountry;")),
        getRes: GetMethodID(ENV, PC, u8("getResourcesByType"),
            u8("(Ljava/lang/String;)Ljava/math/BigDecimal;")),
        setRes: GetMethodID(ENV, PC, u8("setResourcesByType"),
            u8("(Ljava/lang/String;Ljava/math/BigDecimal;)V")),
        getFR: GetMethodID(ENV, PC, u8("getFossilResources"),
            u8("()Lcom/oxiwyle/modernage2/models/FossilResources;")),
        bdInit: GetMethodID(ENV, BD, u8("<init>"), u8("(Ljava/lang/String;)V")),
        toString: null,
        T0: null,
        xc: null
    };

    var ObjCls = FindClass(ENV, u8("java/lang/Object"));
    r.toString = GetMethodID(ENV, ObjCls, u8("toString"), u8("()Ljava/lang/String;"));

    var r6 = CallStaticObjectMethodZ(ENV, ClassCls, forName, jstr("r6"), 0, loader);
    if (!pendingException("forName r6") && !r6.isNull()) {
        r.r6 = r6;
        r.T0 = GetStaticMethodID(ENV, r6, u8("T0"),
            u8("(Lcom/oxiwyle/modernage2/models/FossilResources;)V"));
        if (pendingException("GetStaticMethodID T0")) r.T0 = null;
    }
    var x91 = CallStaticObjectMethodZ(ENV, ClassCls, forName, jstr("x91"), 0, loader);
    if (!pendingException("forName x91") && !x91.isNull()) {
        r.x91 = x91;
        r.xc = GetStaticMethodID(ENV, x91, u8("c"), u8("()V"));
        if (pendingException("GetStaticMethodID x91.c")) r.xc = null;
    }

    if (r.getInstance.isNull() || r.getRes.isNull() || r.setRes.isNull()) {
        pendingException("methodID inti");
        return null;
    }
    return r;
}

/* ------------------------------------------------------------------- baca/tulis */

function readRes(r, type) {
    var pc = CallStaticObjectMethod3(ENV, r.PC, r.getInstance);
    if (pendingException("getInstance") || pc.isNull()) return null;
    var js = jstr(type);
    var bd = CallObjectMethod4(ENV, pc, r.getRes, js);
    if (pendingException("getResourcesByType " + type) || bd.isNull()) return null;
    var s = CallObjectMethod3(ENV, bd, r.toString);
    if (pendingException("toString " + type) || s.isNull()) return null;
    return readStr(s);
}

function writeRes(r, type, text) {
    var pc = CallStaticObjectMethod3(ENV, r.PC, r.getInstance);
    if (pendingException("getInstance") || pc.isNull()) return "game belum dimuat";
    var numStr = jstr(text);
    JVAL.writePointer(numStr);
    var bd = NewObjectA(ENV, r.BD, r.bdInit, JVAL);
    if (pendingException("new BigDecimal(" + text + ")") || bd.isNull()) return "angka tidak valid: " + text;
    var typeStr = jstr(type);
    CallVoidMethod5(ENV, pc, r.setRes, typeStr, bd);
    if (pendingException("set " + type)) return "gagal set " + type;
    return null;
}

function flushDb(r) {
    var pc = CallStaticObjectMethod3(ENV, r.PC, r.getInstance);
    if (pendingException("getInstance") || pc.isNull()) return false;
    var fr = CallObjectMethod3(ENV, pc, r.getFR);
    if (pendingException("getFossilResources") || fr.isNull()) return false;
    if (r.T0 && !r.T0.isNull()) {
        CallStaticVoidMethod4(ENV, r.r6, r.T0, fr);
        if (pendingException("r6.T0")) return false;
    }
    if (r.xc && !r.xc.isNull()) {
        CallStaticVoidMethod4(ENV, r.x91, r.xc, ptr(0));
        if (pendingException("x91.c")) return false;
    }
    return true;
}

var JVAL = Memory.alloc(8);

/*
 * Jalankan fn(r) di dalam local frame. Bila panggilan JNI fault (misalnya
 * bertabrakan dengan GC), frame dibuang dan fn dicoba sekali lagi dengan
 * referensi yang benar-benar baru.
 */
var faultBeruntun = 0;

function denganGame(fn) {
    var percobaan = 0;
    while (percobaan < 2) {
        percobaan++;
        PushLocalFrame(ENV, 32);
        var hasil, gagal = null;
        try {
            var r = resolve();
            if (r === null) hasil = "ERR game belum siap";
            else hasil = fn(r);
        } catch (e) {
            gagal = e;
        }
        try { PopLocalFrame(ENV, ptr(0)); } catch (e2) { }
        if (gagal === null) { faultBeruntun = 0; return hasil; }
        log("fault percobaan " + percobaan + ": " + gagal);
        // ENV bisa jadi tidak berlaku lagi (thread di-detach dari ART).
        // AttachCurrentThread pada thread yang sudah ter-attach aman: ia
        // mengembalikan JNIEnv yang sama.
        try {
            var rc = attachCurrent(VM, envBuf, ptr(0));
            if (rc === 0) {
                setEnv(envBuf.readPointer());
                log("attach ulang ok, env=" + ENV);
            } else {
                log("attach ulang gagal rc=" + rc);
            }
        } catch (e3) {
            log("attach ulang error " + e3);
        }
    }
    faultBeruntun++;
    return "ERR akses JNI gagal";
}

/* dipakai main(): sesi ini sudah tidak bisa dipakai lagi */

/* ------------------------------------------------------------------- protokol */

var lock = null;
var lastLockAt = 0;

function cmdGet(r) {
    var g = readRes(r, "GOLD");
    if (g === null) return "ERR game belum dimuat";
    var c = readRes(r, "CRISTAL");
    if (c === null) return "ERR game belum dimuat";
    return "OK " + g + " " + c;
}

function applyValues(r, goldText, crisText) {
    if (goldText !== "-") { var e1 = writeRes(r, "GOLD", goldText); if (e1) return "ERR " + e1; }
    if (crisText !== "-") { var e2 = writeRes(r, "CRISTAL", crisText); if (e2) return "ERR " + e2; }
    flushDb(r);
    return null;
}

function handle(line) {
    var parts = line.trim().split(/\s+/);
    var cmd = (parts[0] || "").toUpperCase();

    if (cmd === "PING") return "PONG";
    if (cmd === "QUIT") return "OK bye";
    if (cmd === "DIAG") {
        return "OK env=" + ENV + " siap=" + (lock === null ? "idle" : "lock");
    }
    if (cmd === "LOCK") {
        lock = {
            gold: (parts[1] || "-") === "-" ? null : parts[1],
            cristal: (parts[2] || "-") === "-" ? null : parts[2]
        };
        lastLockAt = 0;
        log("LOCK gold=" + lock.gold + " cristal=" + lock.cristal);
        return "OK locked";
    }
    if (cmd === "UNLOCK") { lock = null; return "OK unlocked"; }

    if (cmd === "GET") {
        return denganGame(function (r) { return cmdGet(r); });
    }

    if (cmd === "SET") {
        return denganGame(function (r) {
            var err = applyValues(r, parts[1] || "-", parts[2] || "-");
            return err ? err : cmdGet(r);
        });
    }

    if (cmd === "ADD") {
        return denganGame(function (r) {
            var gold = parts[1] || "-";
            var cris = parts[2] || "-";
            if (gold !== "-") {
                var curG = readRes(r, "GOLD");
                if (curG === null) return "ERR game belum dimuat";
                gold = (parseFloat(curG) + parseFloat(gold)).toFixed(4);
            }
            if (cris !== "-") {
                var curC = readRes(r, "CRISTAL");
                if (curC === null) return "ERR game belum dimuat";
                cris = (parseFloat(curC) + parseFloat(cris)).toFixed(4);
            }
            var e = applyValues(r, gold, cris);
            return e ? e : cmdGet(r);
        });
    }

    if (cmd === "SAVE") {
        return denganGame(function (r) { return flushDb(r) ? "OK saved" : "ERR gagal simpan"; });
    }

    return "ERR perintah tidak dikenal: " + cmd;
}

function lockTick() {
    if (lock === null) return;
    var now = Date.now();
    if (now - lastLockAt < 2000) return;
    lastLockAt = now;
    var l = lock;
    denganGame(function (r) {
        if (l.gold !== null) {
            var g = readRes(r, "GOLD");
            if (g !== null && parseFloat(g) < parseFloat(l.gold)) writeRes(r, "GOLD", l.gold);
        }
        if (l.cristal !== null) {
            var c = readRes(r, "CRISTAL");
            if (c !== null && parseFloat(c) < parseFloat(l.cristal)) writeRes(r, "CRISTAL", l.cristal);
        }
        return "OK";
    });
}

/* ------------------------------------------------------- kontrol via syscall */

function F(name, ret, args) {
    return new NativeFunction(Module.findGlobalExportByName(name), ret, args);
}
var c_socket = F("socket", 'int', ['int', 'int', 'int']);
var c_bind = F("bind", 'int', ['int', 'pointer', 'int']);
var c_listen = F("listen", 'int', ['int', 'int']);
var c_accept4 = F("accept4", 'int', ['int', 'pointer', 'pointer', 'int']);
var c_recv = F("recv", 'int', ['int', 'pointer', 'int', 'int']);
var c_send = F("send", 'int', ['int', 'pointer', 'int', 'int']);
var c_close = F("close", 'int', ['int']);
var c_fcntl = F("fcntl", 'int', ['int', 'int', 'int']);
var c_usleep = F("usleep", 'int', ['int']);

var F_SETFL = 4;
var O_NONBLOCK = 0x800;
var SOCK_NONBLOCK = 0x800;

var recvBuf = Memory.alloc(8192);
var klien = {};

function replyTo(fd, text) {
    var msg = text + "\n";
    var p = Memory.allocUtf8String(msg);
    var sent = 0;
    while (sent < msg.length) {
        var n = c_send(fd, p.add(sent), msg.length - sent, 0);
        if (n <= 0) break;
        sent += n;
    }
}

function layaniKlien(fd, buf) {
    var idx;
    while ((idx = buf.indexOf("\n")) >= 0) {
        var line = buf.substring(0, idx);
        buf = buf.substring(idx + 1);
        if (!line.trim()) continue;
        var reply;
        try { reply = handle(line); } catch (e) { reply = "ERR " + e; log("handler error " + e); }
        replyTo(fd, reply);
        if (line.trim().toUpperCase() === "QUIT") {
            c_close(fd);
            delete klien[fd];
            return null;
        }
    }
    return buf;
}

/* ---------------------------------------------------------------------- main */

(function main() {
    log("AGENT_MULAI pid=" + Process.id);

    var addr = Memory.alloc(16);
    addr.writeU16(2);                              // AF_INET
    addr.writeU32(0x0100007F, 4);                  // 127.0.0.1

    var fd = -1, dipakai = -1;
    for (var k = 0; k < PORT_TRIES; k++) {
        var port = PORT_BASE + k;
        addr.writeU16((((port & 0xff) << 8) | (port >> 8)) & 0xffff, 2);
        fd = c_socket(2, 1, 0);
        if (fd < 0) { log("socket gagal"); return; }
        c_fcntl(fd, F_SETFL, O_NONBLOCK);
        if (c_bind(fd, addr, 16) === 0 && c_listen(fd, 8) === 0) { dipakai = port; break; }
        c_close(fd);
        fd = -1;
    }
    if (dipakai < 0) { log("semua port " + PORT_BASE + "+ gagal"); return; }
    log("server siap di 127.0.0.1:" + dipakai);

    for (;;) {
        for (;;) {
            var c = c_accept4(fd, ptr(0), ptr(0), SOCK_NONBLOCK);
            if (c < 0) break;
            c_fcntl(c, F_SETFL, O_NONBLOCK);
            klien[c] = "";
        }
        for (var key in klien) {
            var kfd = parseInt(key, 10);
            var n = c_recv(kfd, recvBuf, 8192, 0);
            if (n === 0) { c_close(kfd); delete klien[key]; continue; }
            if (n < 0) continue;
            var buf = klien[key] + recvBuf.readUtf8String(n);
            var sisa = layaniKlien(kfd, buf);
            if (sisa === null) delete klien[key];
            else klien[key] = sisa;
        }
        lockTick();

        /*
         * Sesi JNI di proses ini punya umur terbatas (diduga deteksi PairIP):
         * setelah ~40 detik semua panggilan JNI fault permanen. Bila itu
         * terjadi, tutup socket supaya aplikasi tahu agent sudah mati dan
         * memasang sesi baru (frida-inject baru selalu sehat).
         */
        if (faultBeruntun >= 2) {
            log("AGENT_MATI: JNI tidak bisa dipakai lagi, socket ditutup");
            for (var kk in klien) { try { c_close(parseInt(kk, 10)); } catch (e) { } }
            try { c_close(fd); } catch (e2) { }
            return;
        }

        c_usleep(20000);
    }
})();
