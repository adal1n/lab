package com.mtool.app;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class FridaTool {
    private static final String TARGET = "Milkchoco";
    private static final String RESIDENT_HOST = "127.0.0.1";
    private static final int RESIDENT_PORT = 39613;
    private static final String RESIDENT_TOKEN = "mtool-zx91";
    private static final String BINARY_NAME = "mtool_inj";
    private static final String ASSET_BINARY_ARM64 = "mtool_inj";
    private static final String ASSET_BINARY_X64 = "mtool_inj_x64.gz";
    private static final String REALM_EMULATED = "--realm=emulated";
    private static final String ASSET_SCRIPT = "resident.js";
    private static final long INJECT_TIMEOUT_MS = 60000;
    private static final long INJECT_WAIT_MS = 90000;
    private static final long EXEC_TIMEOUT_MS = 15000;
    private static final long SOFT_TIMEOUT_MS = 10000;
    private static final long HARD_TIMEOUT_MS = 60000;
    private static final long RESIDENT_RETRY_MS = 8000;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor();

    private static File binDir;
    private static volatile boolean prepared = false;
    private static volatile boolean prepareFailed = false;
    private static volatile String prepareFailReason = "";

    // ---- resident session state ----
    private static final Object RES_LOCK = new Object();
    private static volatile boolean residentAlive = false;
    private static volatile boolean injectInProgress = false;
    private static volatile boolean shuttingDown = false;
    private static volatile int consecutiveFails = 0;
    private static volatile long lastResidentFailAt = 0;
    private static volatile long injectedPid = -1;
    private static volatile long lastKnownPid = 0;
    // seed with time so seq never repeats across app restarts (script dedupes by seq)
    private static final AtomicLong seqGen = new AtomicLong(System.currentTimeMillis() % 100000000L);
    private static final Map<Long, Pending> pending = new ConcurrentHashMap<>();

    private static Socket resSocket;
    private static OutputStream resOut;
    private static Thread readerThread;

    public interface Callback {
        void onResult(boolean ok, String message);
    }

    // AutoHeal などの resident 発信デバッグログ監視
    public interface Monitor {
        void onLog(String message);
    }

    private static volatile Monitor monitor;

    public static void setMonitor(Monitor m) {
        monitor = m;
    }

    private static void notifyMonitor(String body) {
        Monitor m = monitor;
        if (m != null) {
            try { m.onLog(body); } catch (Throwable ignored) {}
        }
    }

    private static final class Pending {
        final Callback cb;
        volatile boolean done;
        ScheduledFuture<?> timeoutFuture;

        Pending(Callback cb) {
            this.cb = cb;
        }
    }

    private FridaTool() {
    }

    private static boolean deviceX86() {
        String[] abis = android.os.Build.SUPPORTED_ABIS;
        return abis != null && abis.length > 0 && abis[0] != null && abis[0].contains("x86");
    }

    private static String binaryAssetName() {
        return deviceX86() ? ASSET_BINARY_X64 : ASSET_BINARY_ARM64;
    }

    private static final Object LOG_LOCK = new Object();

    private static void lg(String m) {
        Log.d("mtool-frida", m);
        synchronized (LOG_LOCK) {
            try {
                if (binDir == null) return;
                java.io.File f = new java.io.File(binDir, "debug.log");
                if (f.length() > 512 * 1024) f.delete();
                java.io.FileWriter fw = new java.io.FileWriter(f, true);
                String ts = new java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS").format(new java.util.Date());
                fw.write(ts + " " + m + "\n");
                fw.close();
            } catch (Throwable ignored) {}
        }
    }

    public static void init(Context context) {
        if (prepared || prepareFailed || context == null) return;
        synchronized (FridaTool.class) {
            if (prepared || prepareFailed) return;
            try {
                File base = new File(context.getFilesDir(), "frida");
                if (!base.exists()) base.mkdirs();
                binDir = base;   // ログ用に早めに設定
                String abis = "";
                try {
                    for (String a : android.os.Build.SUPPORTED_ABIS) abis += a + ",";
                } catch (Throwable ignored) {}
                lg("init: abis=[" + abis + "] x86=" + deviceX86() + " asset=" + binaryAssetName());
                extractBinary(context, base);
                extractScript(context, base);
                prepared = true;
                lg("init: OK");
            } catch (Throwable t) {
                prepareFailed = true;
                prepareFailReason = "" + t;
                android.util.Log.e("mtool-frida", "init FAILED: " + prepareFailReason);
                lg("init FAILED: " + prepareFailReason);
            }
        }
    }

    private static void extractBinary(Context context, File base) throws Exception {
        File out = new File(base, BINARY_NAME);
        String assetName = binaryAssetName();
        boolean isGz = assetName.endsWith(".gz");
        String expectedHash = assetMd5(context, assetName);
        File stamp = new File(base, BINARY_NAME + ".md5");
        boolean needExtract = !out.exists() || !out.canExecute()
                || !(stamp.exists() && expectedHash.equals(new String(java.nio.file.Files.readAllBytes(stamp.toPath()), StandardCharsets.UTF_8).trim()));
        if (!needExtract) return;

        File tmp = new File(base, BINARY_NAME + ".tmp");
        InputStream in = null;
        OutputStream os = null;
        try {
            in = context.getAssets().open(assetName);
            if (isGz) in = new java.util.zip.GZIPInputStream(in);
            os = new FileOutputStream(tmp);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            os.flush();
        } finally {
            if (in != null) in.close();
            if (os != null) os.close();
        }
        if (!tmp.renameTo(out)) {
            out.delete();
            if (!tmp.renameTo(out)) throw new Exception("failed to place " + BINARY_NAME);
        }
        if (!out.setExecutable(true, false)) throw new Exception("chmod failed for " + BINARY_NAME);
        java.nio.file.Files.write(stamp.toPath(), expectedHash.getBytes(StandardCharsets.UTF_8));
    }

    private static String assetMd5(Context context, String name) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
        InputStream in = context.getAssets().open(name);
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        } finally {
            in.close();
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static void extractScript(Context context, File base) throws Exception {
        File out = new File(base, ASSET_SCRIPT);
        String expectedHash = assetMd5(context, ASSET_SCRIPT);
        File stamp = new File(base, ASSET_SCRIPT + ".md5");
        if (out.exists() && stamp.exists()
                && expectedHash.equals(new String(java.nio.file.Files.readAllBytes(stamp.toPath()), StandardCharsets.UTF_8).trim())) {
            return;
        }

        InputStream in = null;
        OutputStream os = null;
        File tmp = new File(base, ASSET_SCRIPT + ".tmp");
        try {
            in = context.getAssets().open(ASSET_SCRIPT);
            os = new FileOutputStream(tmp);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            os.flush();
        } finally {
            if (in != null) in.close();
            if (os != null) os.close();
        }
        if (!tmp.renameTo(out)) {
            out.delete();
            if (!tmp.renameTo(out)) throw new Exception("failed to place " + ASSET_SCRIPT);
        }
        java.nio.file.Files.write(stamp.toPath(), expectedHash.getBytes(StandardCharsets.UTF_8));
    }

    private static volatile long lastEnsureAt = 0;

    /** Called by FloatingMenuService when the game process attaches. */
    public static void onGameAttached(long pid) {
        if (!prepared || prepareFailed || pid <= 0) return;
        lastKnownPid = pid;
        EXECUTOR.execute(new Runnable() {
            @Override public void run() {
                // ゲームのロードが完了 (ネイティブ側でベース検出) するまで待つ。
                // 早すぎる注入はアンチチート初期化と競合し、以後使えなくなることがある。
                long deadline = System.currentTimeMillis() + 90000;
                while (System.currentTimeMillis() < deadline && !shuttingDown) {
                    if (MemOps.isBaseValid()) break;
                    if (lastKnownPid != pid) return; // 別インスタンスへ交代
                    try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
                }
                if (lastKnownPid != pid) return;
                ensureResident(pid);
            }
        });
    }

    /** attach ポーリングから定期呼び出し: 未接続なら再確立を試みる (スロットル内) */
    public static void tick(long pid) {
        if (!prepared || prepareFailed || pid <= 0) return;
        if (pid != lastKnownPid) { lastKnownPid = pid; }
        if (residentAlive || injectInProgress || shuttingDown) return;
        long now = System.currentTimeMillis();
        if (now - lastEnsureAt < 15000) return;
        lastKnownPid = pid;
        EXECUTOR.execute(new Runnable() {
            @Override public void run() {
                ensureResident(pid);
            }
        });
    }

    /** Called when the game process detaches/dies. */
    public static void onGameDetached() {
        closeConnection();
        residentAlive = false;
        injectedPid = -1;
        failAllPending("MSG:GAME_DETACHED");
    }

    /** Called when Mtool service is destroyed. Resident agent dies with the game. */
    public static void shutdown() {
        shuttingDown = true;
        closeConnection();
        residentAlive = false;
        failAllPending("MSG:SERVICE_STOPPED");
    }

    public static void run(final String action, final String arg, final Callback cb) {
        EXECUTOR.execute(new Runnable() {
            @Override public void run() {
                invoke(action, arg, cb);
            }
        });
    }

    private static void invoke(String action, String arg, Callback cb) {
        if (prepareFailed || binDir == null) {
            deliver(cb, false, "MSG:FRIDA_FAILED:-1(init:" + prepareFailReason + ")");
            return;
        }
        String safeArg = arg == null ? null : arg.replace('\n', ' ').replace('\r', ' ');
        if (residentAlive) {
            execResident(action, safeArg, cb);
            return;
        }
        long pid = lastKnownPid;
        boolean retryAllowed = consecutiveFails == 0
                || (System.currentTimeMillis() - lastResidentFailAt) >= RESIDENT_RETRY_MS;
        if (pid > 0 && retryAllowed && ensureResident(pid)) {
            execResident(action, safeArg, cb);
            return;
        }
        Result r = legacyExecute(action, safeArg);
        deliver(cb, r.ok, r.message);
    }

    // ------------------------------------------------------------------
    // Resident session management
    // ------------------------------------------------------------------

    private static void closeConnection() {
        if (resSocket != null) {
            try { resSocket.close(); } catch (Throwable ignored) {}
            resSocket = null;
            resOut = null;
        }
    }

    private static boolean tcpConnect(long timeoutMs) {
        try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(RESIDENT_HOST, RESIDENT_PORT), (int) timeoutMs);
            s.setSoTimeout((int) Math.max(timeoutMs, 2500));
            s.setTcpNoDelay(true);
            lg("tcp: connected, awaiting hello");
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            OutputStream w = s.getOutputStream();
            w.write((RESIDENT_TOKEN + "\n").getBytes(StandardCharsets.UTF_8));
            w.flush();
            String hello = r.readLine();
            lg("tcp: hello=<"+hello+">");
            if (hello == null || !hello.trim().equals("hello")) {
                try { s.close(); } catch (Throwable ignored) {}
                return false;
            }
            s.setSoTimeout(0);
            closeConnection();
            resSocket = s;
            resOut = w;
            startReader(r);
            return true;
        } catch (Throwable t) {
            lg("tcp: err " + t);
            return false;
        }
    }

    private static boolean ensureResident(long pid) {
        lastEnsureAt = System.currentTimeMillis();
        boolean ok = ensureResidentInner(pid);
        if (ok) {
            consecutiveFails = 0;
            lastResidentFailAt = 0;
        } else {
            consecutiveFails++;
            lastResidentFailAt = System.currentTimeMillis();
        }
        return ok;
    }

    private static boolean ensureResidentInner(long pid) {
        synchronized (RES_LOCK) {
            if (residentAlive) return true;
            if (injectInProgress) return false;
            injectInProgress = true;
        }
        boolean justInjected = false;
        try {
            if (shuttingDown) return false;
            lg("ensure: need inject pid="+pid);            if (injectedPid != pid) {
                String binPath = new File(binDir, BINARY_NAME).getAbsolutePath();
                String jsPath = new File(binDir, ASSET_SCRIPT).getAbsolutePath();
                String cmd = binPath + " -n " + TARGET + " -s " + jsPath + " --runtime=qjs"
                    + (deviceX86() ? " " + REALM_EMULATED : "");
                ProcessBuilder pb = new ProcessBuilder("su", "-c", cmd);
                pb.redirectErrorStream(true);
                Process p = pb.start();
                // drain child stdout in background so it can't block on a full pipe
                final InputStream es = p.getInputStream();
                Thread drainer = new Thread(new Runnable() {
                    @Override public void run() {
                        try {
                            byte[] buf = new byte[4096];
                            while (es.read(buf) > 0) {}
                        } catch (Throwable ignored) {}
                    }
                }, "mtool-inj-drain");
                drainer.setDaemon(true);
                drainer.start();
                justInjected = true;
            }
            long deadline = System.currentTimeMillis() + (justInjected ? INJECT_WAIT_MS : 3000);
            boolean connectedNow = false;
            while (System.currentTimeMillis() < deadline) {
                if (shuttingDown) return false;
                if (tcpConnect(800)) {
                    lg("ensure: CONNECTED");
                    connectedNow = true;
                    residentAlive = true;
                    long sb = MemOps.getSelfBase();
                    if (sb > 0) {
                        long sseq = seqGen.incrementAndGet();
                        writeLine(sseq + " setbase 0x" + Long.toHexString(sb));
                        lg("setbase sent: " + Long.toHexString(sb));
                    }
                    break;
                }
                try { Thread.sleep(400); } catch (InterruptedException e) { break; }
            }
            if (justInjected) {
                // The eternalized agent lives inside the game independently;
                // the injector helper may linger after -e, so force-clean it.
                su("kill -9 $(pidof " + BINARY_NAME + ") 2>/dev/null", 8000);
                if (connectedNow) injectedPid = pid;
            }
            return connectedNow;
        } catch (Throwable t) {
            return false;
        } finally {
            injectInProgress = false;
        }
    }

    private static void execResident(String action, String arg, Callback cb) {
        // コマンド前に最新の自ベースを通知 (ネイティブは毎tick再読込のため常に新鮮)
        long sb = MemOps.getSelfBase();
        if (sb > 0) {
            long bseq = seqGen.incrementAndGet();
            writeLine(bseq + " setbase 0x" + Long.toHexString(sb));
        }
        String body = action + (arg != null && !arg.isEmpty() ? " " + arg : "");
        long s = seqGen.incrementAndGet();
        Pending p = new Pending(cb);
        pending.put(s, p);
        p.timeoutFuture = WATCHDOG.schedule(new Runnable() {
            @Override public void run() {
                Pending q = pending.remove(s);
                if (q != null) {
                    closeConnection();
                    residentAlive = false;
                    deliver(q.cb, false, "MSG:FRIDA_TIMEOUT");
                }
            }
        }, EXEC_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        if (!writeLine(s + " " + body)) {
            pending.remove(s);
            closeConnection();
            residentAlive = false;
            deliver(cb, false, "MSG:FRIDA_FAILED:-3");
        }
    }

    private static boolean writeLine(String line) {
        try {
            if (resOut == null) { lg("write: resOut null"); return false; }
            lg("write: <" + line + ">");
            resOut.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            resOut.flush();
            return true;
        } catch (IOException e) {
            lg("write err: " + e);
            return false;
        }
    }

    private static void handleLine(String line) {
        if (line == null || line.isEmpty()) return;
        lg("recv: <" + line + ">");
        int sp = line.indexOf(' ');
        if (sp <= 0) return;
        long s;
        try {
            s = Long.parseLong(line.substring(0, sp));
        } catch (NumberFormatException e) {
            return;
        }
        String body = line.substring(sp + 1).trim();
        // AutoHeal のデバッグログ: 99998 seq は応答待ちなし。ログキャットに出す。
        if (s == 99998L) {
            lg("AUTOHEAL: " + body);
            notifyMonitor(body);
            return;
        }
        Pending p = pending.remove(s);
        if (p != null) {
            lg("resp: <" + line + ">");
            boolean ok = body.endsWith("_OK") || body.endsWith("_DONE")
                    || body.startsWith("MSG:COSTUMES_ALL_UNLOCKED");
            p.done = true;
            if (p.timeoutFuture != null) p.timeoutFuture.cancel(false);
            deliver(p.cb, ok, body.startsWith("MSG:") ? body : "MSG:" + body);
        }
    }

    private static void failAllPending(String msg) {
        for (Long key : pending.keySet().toArray(new Long[0])) {
            Pending p = pending.remove(key);
            if (p != null) {
                p.done = true;
                if (p.timeoutFuture != null) p.timeoutFuture.cancel(false);
                deliver(p.cb, false, msg);
            }
        }
    }

    private static void deliver(final Callback cb, final boolean ok, final String message) {
        if (cb == null) return;
        WATCHDOG.schedule(new Runnable() {
            @Override public void run() {
                try {
                    cb.onResult(ok, message);
                } catch (Throwable ignored) {}
            }
        }, 0, TimeUnit.MILLISECONDS);
    }

    // ------------------------------------------------------------------
    // Response reader thread (persistent socket)
    // ------------------------------------------------------------------

    private static void startReader(final BufferedReader r) {
        if (readerThread != null && readerThread.isAlive()) {
            // previous reader should have died with its socket; give it a moment
            try { readerThread.join(300); } catch (InterruptedException ignored) {}
        }
        readerThread = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String line;
                    while ((line = r.readLine()) != null) {
                        handleLine(line.trim());
                    }
                    lg("reader: EOF");
                } catch (Throwable t) {
                    lg("reader: err " + t);
                }
                // connection lost
                lg("reader: dead at " + System.currentTimeMillis());
                if (resSocket != null && !resSocket.isClosed()) {
                    try { resSocket.close(); } catch (Throwable ignored) {}
                }
                residentAlive = false;
            }
        }, "mtool-res-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    // ------------------------------------------------------------------
    // su helpers
    // ------------------------------------------------------------------

    private static boolean su(String shellCmd, long timeoutMs) {
        try {
            final Process p = new ProcessBuilder("su", "-c", shellCmd).start();
            final AtomicBoolean done = new AtomicBoolean(false);
            WATCHDOG.schedule(new Runnable() {
                @Override public void run() {
                    if (done.compareAndSet(false, true)) {
                        try { p.destroy(); } catch (Throwable ignored) {}
                    }
                }
            }, timeoutMs, TimeUnit.MILLISECONDS);
            InputStream is = p.getInputStream();
            byte[] buf = new byte[4096];
            while (is.read(buf) > 0) { /* drain */ }
            int code = p.waitFor();
            done.set(true);
            return code == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Legacy one-shot execution (fallback when resident session fails)
    // ------------------------------------------------------------------

    private static final class Result {
        final boolean ok;
        final String message;

        Result(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }

        static Result fail(int code) {
            return new Result(false, "MSG:FRIDA_FAILED:" + code);
        }
    }

    private static Result legacyExecute(String action, String arg) {
        try {
            File scriptFile = new File(binDir, "action.js");
            FileOutputStream fos = new FileOutputStream(scriptFile);
            fos.write(payloadFor(action, arg).getBytes(StandardCharsets.UTF_8));
            fos.flush();
            fos.close();

            String cmd = new File(binDir, BINARY_NAME).getAbsolutePath()
                    + " -n " + TARGET + " -s " + scriptFile.getAbsolutePath() + " --runtime=qjs"
                    + (deviceX86() ? " " + REALM_EMULATED : "");
            ProcessBuilder pb = new ProcessBuilder("su", "-c", cmd);
            pb.redirectErrorStream(true);
            final Process p = pb.start();

            final AtomicBoolean finished = new AtomicBoolean(false);
            final AtomicBoolean killScheduled = new AtomicBoolean(false);
            final AtomicBoolean killed = new AtomicBoolean(false);

            WATCHDOG.schedule(new Runnable() {
                @Override public void run() {
                    if (!finished.get() && killed.compareAndSet(false, true)) doKill();
                }
            }, SOFT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            WATCHDOG.schedule(new Runnable() {
                @Override public void run() {
                    if (!finished.get()) {
                        try { p.destroy(); } catch (Throwable ignored) {}
                    }
                }
            }, HARD_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            StringBuilder output = new StringBuilder();
            String msg = null;
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
                String t = line.trim();
                if (t.startsWith("MSG:")) {
                    msg = t;
                    if (killScheduled.compareAndSet(false, true)) {
                        WATCHDOG.schedule(new Runnable() {
                            @Override public void run() {
                                if (killed.compareAndSet(false, true)) doKill();
                            }
                        }, 500, TimeUnit.MILLISECONDS);
                    }
                }
            }
            int code = p.waitFor();
            finished.set(true);
            try { p.destroy(); } catch (Throwable ignored) {}

            if (msg == null) {
                String trimmed = output.toString().trim();
                if (!trimmed.isEmpty()) {
                    String[] lines = trimmed.split("\n");
                    msg = lines[lines.length - 1].trim();
                }
            }
            boolean ok = msg != null && (msg.endsWith("_OK") || msg.endsWith("_DONE"));
            if (msg == null) return Result.fail(code);
            return new Result(ok, msg);
        } catch (Throwable t) {
            String m = t.getMessage();
            if (m == null || m.isEmpty()) m = t.toString();
            return new Result(false, m.startsWith("MSG:") ? m : "MSG:FRIDA_FAILED:-2");
        }
    }

    private static void doKill() {
        try {
            Process k = new ProcessBuilder("su", "-c",
                    "kill -TERM $(pidof " + BINARY_NAME + ") 2>/dev/null").start();
            k.waitFor();
        } catch (Throwable ignored) {}
    }

    private static String payloadFor(String action, String arg) {
        if ("enemyKick".equals(action)) return ENEMY_KICK_JS.replace("__SKIP_SLOTS__", skipSlots(arg));
        if ("allKick".equals(action)) return ALL_KICK_JS.replace("__SKIP_SLOTS__", skipSlots(arg));
        if ("esco".equals(action)) return ESCO_JS;
        if ("botKick".equals(action)) return BOT_KICK_JS;
        if ("matchReset".equals(action)) return MATCH_RESET_JS;
        if ("nickChange".equals(action)) {
            String name = arg == null ? "" : arg;
            return NICK_CHANGE_JS.replace("__NICK__", jsEscape(name));
        }
        return "";
    }

    private static String skipSlots(String arg) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        if (arg != null && !arg.isEmpty()) {
            for (String part : arg.split(",")) {
                String t = part.trim();
                if (t.isEmpty()) continue;
                try {
                    int slot = Integer.parseInt(t);
                    if (!first) sb.append(',');
                    sb.append(slot);
                    first = false;
                } catch (NumberFormatException ignored) {}
            }
        }
        sb.append(']');
        return sb.toString();
    }

    private static String jsEscape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '\'': sb.append("\\'"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    private static final String ENEMY_KICK_JS = """
            (function(){
            var lib = Process.findModuleByName('libMyGame.so');
            if (!lib) { console.log('MSG:MODULE_NOT_FOUND'); return; }
            var getCamUser = lib.findExportByName('_ZN5Cloud10CameraData24GetCameraUserInformationEv');
            if (!getCamUser) { console.log('MSG:EXPORT_NOT_FOUND:GetCameraUserInformation'); return; }
            var fnGetUser = new NativeFunction(getCamUser, 'pointer', []);
            var myPtr = fnGetUser();
            if (!myPtr || myPtr.isNull()) { console.log('MSG:SELF_INFO_NULL'); return; }
            var mySlot = myPtr.add(0xC8).readU8();
            var kickAddr = lib.findExportByName('_ZN16SystemPacketSend18FMatchKickUserSlotEh');
            if (!kickAddr) { console.log('MSG:EXPORT_NOT_FOUND:FMatchKickUserSlot'); return; }
            var kick = new NativeFunction(kickAddr, 'void', ['uint8']);
            var myTeam = mySlot % 2;
            var skipSlots = __SKIP_SLOTS__;
            for (var i = 0; i <= 100; i++) {
                if (i % 2 !== myTeam) {
                    if (skipSlots.indexOf(i) >= 0) continue;
                    try { kick(i); } catch(e) {}
                }
            }
            console.log('MSG:ENEMY_KICK_OK');
            })();
            """;

    private static final String ALL_KICK_JS = """
            (function(){
            var lib = Process.findModuleByName('libMyGame.so');
            if (!lib) { console.log('MSG:MODULE_NOT_FOUND'); return; }
            var getCamUser = lib.findExportByName('_ZN5Cloud10CameraData24GetCameraUserInformationEv');
            if (!getCamUser) { console.log('MSG:EXPORT_NOT_FOUND:GetCameraUserInformation'); return; }
            var fnGetUser = new NativeFunction(getCamUser, 'pointer', []);
            var myPtr = fnGetUser();
            if (!myPtr || myPtr.isNull()) { console.log('MSG:SELF_INFO_NULL'); return; }
            var mySlot = myPtr.add(0xC8).readU8();
            var kickAddr = lib.findExportByName('_ZN16SystemPacketSend18FMatchKickUserSlotEh');
            if (!kickAddr) { console.log('MSG:EXPORT_NOT_FOUND:FMatchKickUserSlot'); return; }
            var kick = new NativeFunction(kickAddr, 'void', ['uint8']);
            var skipSlots = __SKIP_SLOTS__;
            for (var i = 0; i <= 100; i++) {
                if (i !== mySlot) {
                    if (skipSlots.indexOf(i) >= 0) continue;
                    try { kick(i); } catch(e) {}
                }
            }
            console.log('MSG:ALL_KICK_OK');
            })();
            """;

    private static final String ESCO_JS = """
            (function(){
            var lib = Process.findModuleByName('libMyGame.so');
            if (!lib) { console.log('MSG:MODULE_NOT_FOUND'); return; }
            var getCamUser = lib.findExportByName('_ZN5Cloud10CameraData24GetCameraUserInformationEv');
            if (!getCamUser) { console.log('MSG:EXPORT_NOT_FOUND:GetCameraUserInformation'); return; }
            var fnGetUser = new NativeFunction(getCamUser, 'pointer', []);
            var userInfoPtr = fnGetUser();
            if (!userInfoPtr || userInfoPtr.isNull()) { console.log('MSG:SELF_INFO_NULL'); return; }
            var hitAddr = lib.findExportByName('_ZN16SystemPacketSend8HitSnailERK9UserInforhRKN7cocos2d4Vec3Esf');
            var healAddr = lib.findExportByName('_ZN16SystemPacketSend9HealSnailERK9UserInforhRKN7cocos2d4Vec3Esf');
            if (hitAddr) {
                var vec3Hit = Memory.alloc(12);
                vec3Hit.writeFloat(0.74249267578125);
                vec3Hit.add(4).writeFloat(3.1789627075195313);
                vec3Hit.add(8).writeFloat(-0.34249114990234375);
                var fnHit = new NativeFunction(hitAddr, 'void', ['pointer', 'uint8', 'pointer', 'int16', 'float']);
                fnHit(userInfoPtr, 1, vec3Hit, 18688, 0);
            }
            if (healAddr) {
                var vec3Heal = Memory.alloc(12);
                vec3Heal.writeFloat(-2.5789337158203125);
                vec3Heal.add(4).writeFloat(0.06841754913330078);
                vec3Heal.add(8).writeFloat(-1.5102081298828125);
                var fnHeal = new NativeFunction(healAddr, 'void', ['pointer', 'uint8', 'pointer', 'int16', 'float']);
                fnHeal(userInfoPtr, 12, vec3Heal, -18688, 0);
            }
            if (!hitAddr && !healAddr) { console.log('MSG:EXPORT_NOT_FOUND:HitSnail'); return; }
            console.log('MSG:ESCO_DONE');
            })();
            """;

    private static final String BOT_KICK_JS = """
            (function(){
            var lib = Process.findModuleByName('libMyGame.so');
            if (!lib) { console.log('MSG:MODULE_NOT_FOUND'); return; }
            var clearAddr = lib.findExportByName('_ZN16SystemPacketSend15FMatchClearSlotEh');
            if (!clearAddr) { console.log('MSG:EXPORT_NOT_FOUND:FMatchClearSlot'); return; }
            var clearFunc = new NativeFunction(clearAddr, 'void', ['uint8']);
            for (var i = 0; i <= 100; i++) {
                try { clearFunc(i); } catch(e) {}
            }
            console.log('MSG:BOT_KICK_OK');
            })();
            """;

    private static final String MATCH_RESET_JS = """
            (function(){
            var lib=Process.findModuleByName('libMyGame.so');
            if(!lib){console.log('MSG:MODULE_NOT_FOUND');return;}
            var addr=lib.findExportByName('_ZN16SystemPacketSend11FMatchStartEv');
            if(addr){
                var fn=new NativeFunction(addr,'void',[]);
                fn();
                console.log('MSG:MATCH_RESET_OK');
            }else{console.log('MSG:EXPORT_NOT_FOUND:FMatchStart');}
            })();
            """;

    private static final String NICK_CHANGE_JS = """
            (function(){
            var lib=Process.findModuleByName('libMyGame.so');
            if(!lib){console.log('MSG:MODULE_NOT_FOUND');return;}
            var addr=lib.findExportByName('_ZN16SystemPacketSend14ChangeNicknameEhPKch');
            if(addr){
                var name = '__NICK__';
                var strPtr = Memory.allocUtf8String(name);
                var fn=new NativeFunction(addr,'void',['uint8','pointer','uint8']);
                fn(1, strPtr, 0);
                console.log('MSG:NICK_CHANGE_OK');
            }else{console.log('MSG:EXPORT_NOT_FOUND:ChangeNickname');}
            })();
            """;
}