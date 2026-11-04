package com.yunluo.wb2api;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.util.Log;

import com.chaquo.python.Kwarg;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 网关进程的真正宿主：
 *   1. 首次启动把 assets/app 里的 Python 网关源码解压到私有目录；
 *   2. 用 Chaquopy 起一个内嵌 CPython 3.11；
 *   3. chdir 到代码目录、注入环境变量，然后 runpy 跑 wb_proxy.py。
 *
 * Python 和 Java 共进程，所以「停止」= os._exit，靠闹钟把界面重新拉起来做「重启」。
 */
public final class PyRunner {

    public static final String TAG = "WB2API";
    public static final int DEFAULT_PORT = 8888;

    private static final AtomicBoolean STARTING = new AtomicBoolean(false);
    private static volatile boolean running = false;
    private static volatile String phase = "未启动";
    private static volatile String error = "";
    private static volatile int port = DEFAULT_PORT;
    private static volatile boolean lanMode = false;
    /**
     * 上一次网关进程的「死因」。Integer.MIN_VALUE 表示还没死过。
     *
     * 进程被系统强杀时拿不到 JAVA 层的退出码（Chaquopy 是同进程，不是子进程），
     * 所以这里记录的是我们**自己能观测到**的那部分：正常 stopping、启动异常、
     * 或者被 os._exit 带走的码。取不到就不假装知道。
     */
    private static volatile int lastExitCode = Integer.MIN_VALUE;

    private PyRunner() {}

    public static boolean isRunning() {
        return running;
    }

    public static boolean isStarting() {
        return STARTING.get();
    }

    public static String phase() {
        return phase;
    }

    public static String error() {
        return error;
    }

    public static int port() {
        return port;
    }

    public static boolean lanMode() {
        return lanMode;
    }

    /** 上一次退出码；没死过返回 Integer.MIN_VALUE。 */
    public static int lastExitCode() {
        return lastExitCode;
    }

    /** 记录一个退出码（供服务层展示）。 */
    public static void noteExit(int code) {
        lastExitCode = code;
    }

    public static String baseUrl() {
        return "http://127.0.0.1:" + port + "/";
    }

    public static File codeDir(Context ctx) {
        return new File(ctx.getFilesDir(), "app");
    }

    public static File logFile(Context ctx) {
        return new File(new File(ctx.getFilesDir(), "logs"), "gateway.log");
    }

    public static File accountsDir(Context ctx) {
        return new File(codeDir(ctx), "accounts");
    }

    // ---------------------------------------------------------------- 启动

    public static void start(Context ctx, int wantedPort, boolean lan, String panelPwd) {
        if (running || !STARTING.compareAndSet(false, true)) {
            return;
        }
        final Context app = ctx.getApplicationContext();
        port = wantedPort;
        lanMode = lan;
        error = "";

        Thread t = new Thread(() -> {
            File log = logFile(app);
            try {
                phase = "解压运行时";
                ensureAssets(app);

                File code = codeDir(app);
                if (!new File(code, "wb_proxy.py").exists()) {
                    throw new IOException("wb_proxy.py 缺失：" + code.getAbsolutePath());
                }
                // 有手机壳自己的启动器就跑它：它负责思考档位、调试台，再去跑上游。
                // 没有（老包）就直接跑上游，行为不变。
                File boot = new File(code, "wb_boot.py");
                File entry = boot.exists() ? boot : new File(code, "wb_proxy.py");

                File logsDir = log.getParentFile();
                if (logsDir != null) logsDir.mkdirs();
                rotate(log);
                appendLog(log, "\n===== " + stamp() + " 启动 port=" + port + " lan=" + lanMode + " =====\n");

                phase = "初始化 Python 运行时";
                if (!Python.isStarted()) {
                    Python.start(new AndroidPlatform(app));
                }
                Python py = Python.getInstance();

                PyObject sys = py.getModule("sys");
                PyObject os = py.getModule("os");

                phase = "注入环境变量";
                PyObject environ = os.get("environ");
                putEnv(environ, "HOME", app.getFilesDir().getAbsolutePath());
                putEnv(environ, "TMPDIR", app.getCacheDir().getAbsolutePath());
                putEnv(environ, "TMP", app.getCacheDir().getAbsolutePath());
                putEnv(environ, "PYTHONUNBUFFERED", "1");
                putEnv(environ, "PYTHONIOENCODING", "utf-8");
                putEnv(environ, "PYTHONDONTWRITEBYTECODE", "1");
                putEnv(environ, "TZ", TimeZone.getDefault().getID());
                File cacert = new File(app.getFilesDir(), "chaquopy/cacert.pem");
                if (cacert.exists()) {
                    putEnv(environ, "SSL_CERT_FILE", cacert.getAbsolutePath());
                    putEnv(environ, "WB_CA_BUNDLE", cacert.getAbsolutePath());
                }
                os.callAttr("chdir", code.getAbsolutePath());
                // runpy 不会把脚本目录塞进 sys.path，同目录的 wb_* 兄弟模块得手动加
                sys.get("path").callAttr("insert", 0, code.getAbsolutePath());

                // stdout / stderr 全量落到日志文件，界面上直接 tail
                phase = "重定向日志";
                PyObject io = py.getModule("io");
                PyObject out = io.callAttr("open", log.getAbsolutePath(), "a",
                        new Kwarg("encoding", "utf-8"), new Kwarg("buffering", 1));
                sys.put("stdout", out);
                sys.put("stderr", out);

                phase = "加载网关";
                PyObject argv = sys.get("argv");
                argv.callAttr("clear");
                List<String> args = new ArrayList<>();
                args.add("wb_proxy.py");
                args.add("--host");
                // 局域网模式只改绑定地址，不传 --lan：--lan 会强制生成并校验 API Key，
                // 而这里要的是「同网段的设备打开网页直接就能用看板」，所以对它放行；
                // 面板本身仍然由面板密码把关。
                args.add(lanMode ? "0.0.0.0" : "127.0.0.1");
                args.add("--port");
                args.add(String.valueOf(port));
                // 面板密码由 App 统一下发：App 里的那份为准，网关侧同步写一次 PBKDF2
                // 摘要，这样 ?pwd= 自动登录永远不会因为密码漂移而失效。
                if (panelPwd != null && !panelPwd.isEmpty()) {
                    args.add("--panel-password");
                    args.add(panelPwd);
                }
                for (String a : args) argv.callAttr("append", a);

                running = true;
                phase = "运行中 :" + port;
                appendLog(log, "[app] python " + py.getModule("sys").get("version").toString().trim()
                        + " entry=" + entry.getName()
                        + " cwd=" + code.getAbsolutePath() + "\n");

                PyObject runpy = py.getModule("runpy");
                runpy.callAttr("run_path",
                        entry.getAbsolutePath(),
                        new Kwarg("run_name", "__main__"));

                // 正常返回 = 服务自己退出了
                running = false;
                phase = "已停止";
                appendLog(log, "[app] gateway exited normally\n");
            } catch (Throwable e) {
                running = false;
                phase = "启动失败";
                error = shortError(e);
                Log.e(TAG, "python 启动失败", e);
                appendLog(log, "[app] 启动失败: " + Log.getStackTraceString(e) + "\n");
            } finally {
                STARTING.set(false);
            }
        }, "wb2api-python");
        t.setDaemon(false);
        t.start();
    }

    private static void putEnv(PyObject environ, String k, String v) {
        environ.callAttr("__setitem__", k, v);
    }

    // ---------------------------------------------------------------- 停止 / 重启

    public static void shutdown() {
        try {
            if (Python.isStarted()) {
                Python.getInstance().getModule("os").callAttr("_exit", 0);
            }
        } catch (Throwable e) {
            Log.w(TAG, "os._exit 失败", e);
        }
        try {
            android.os.Process.killProcess(android.os.Process.myPid());
        } catch (Throwable ignored) {
        }
    }

    /** 停掉 Python，再用闹钟把界面拉回来 —— 用户看到的就是「重启」。 */
    public static void restart(Context ctx) {
        try {
            Intent i = new Intent(ctx, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            PendingIntent pi = PendingIntent.getActivity(ctx, 0x2f11, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                // setAndAllowWhileIdle：Doze 里也能按时响，不用申请精确闹钟权限
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                        System.currentTimeMillis() + 1200, pi);
            }
        } catch (Throwable e) {
            Log.w(TAG, "重启闹钟设置失败", e);
        }
        shutdown();
    }

    // ---------------------------------------------------------------- 资产解压

    private static void ensureAssets(Context ctx) throws IOException {
        File root = codeDir(ctx);
        File stampFile = new File(root, ".asset_stamp");
        String want = BuildConfig.VERSION_NAME + "#" + BuildConfig.VERSION_CODE;
        String have = stampFile.exists() ? readAll(stampFile).trim() : "";
        if (want.equals(have) && new File(root, "wb_proxy.py").exists()) {
            return;
        }
        root.mkdirs();
        phase = "解压运行时（首次启动约 5-10 秒）";
        copyTree(ctx.getAssets(), "app", root);
        writeAll(stampFile, want);
    }

    private static void copyTree(AssetManager am, String path, File dest) throws IOException {
        String[] children = am.list(path);
        if (children == null || children.length == 0) {
            copyFile(am, path, dest);
            return;
        }
        if (!dest.exists() && !dest.mkdirs()) {
            throw new IOException("无法创建目录 " + dest);
        }
        for (String name : children) {
            // 用户数据目录永不覆盖
            if (name.equals("accounts") || name.equals("usage") || name.equals("logs")) continue;
            copyTree(am, path + "/" + name, new File(dest, name));
        }
    }

    private static void copyFile(AssetManager am, String path, File dest) throws IOException {
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (InputStream in = am.open(path); OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    // ---------------------------------------------------------------- 日志工具

    private static void rotate(File log) {
        if (log.exists() && log.length() > 2L * 1024 * 1024) {
            File old = new File(log.getParentFile(), "gateway.old.log");
            if (old.exists()) old.delete();
            log.renameTo(old);
        }
    }

    public static void appendLog(File log, String text) {
        try {
            File parent = log.getParentFile();
            if (parent != null) parent.mkdirs();
            try (FileWriter w = new FileWriter(log, true)) {
                w.write(text);
            }
        } catch (IOException ignored) {
        }
    }

    public static String tail(Context ctx, int lines) {
        File log = logFile(ctx);
        if (!log.exists()) return "（暂无日志）";
        List<String> ring = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader(log))) {
            String line;
            while ((line = r.readLine()) != null) {
                ring.add(line);
                if (ring.size() > lines) ring.remove(0);
            }
        } catch (IOException e) {
            return "（读取日志失败：" + e.getMessage() + "）";
        }
        StringBuilder sb = new StringBuilder();
        for (String l : ring) sb.append(l).append('\n');
        return sb.length() == 0 ? "（暂无日志）" : sb.toString();
    }

    private static String readAll(File f) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static void writeAll(File f, String text) throws IOException {
        try (FileWriter w = new FileWriter(f, false)) {
            w.write(text);
        }
    }

    private static String stamp() {
        return String.format(Locale.US, "%1$tF %1$tT", System.currentTimeMillis());
    }

    private static String shortError(Throwable e) {
        String msg = e.getMessage();
        if (msg == null || msg.trim().isEmpty()) msg = e.getClass().getSimpleName();
        if (msg.length() > 1200) msg = msg.substring(0, 1200) + " …";
        return msg;
    }
}
