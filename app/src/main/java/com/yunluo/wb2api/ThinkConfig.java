package com.yunluo.wb2api;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * 思考档位配置：files/app/wb_app/thinking.json
 *
 * 这个文件是唯一真源，网关侧的 wb_boot.py 每秒轮询它的 mtime，
 * 一改就 os.environ["WB_DEFAULT_REASONING_EFFORT"] 立即换档 ——
 * 上游是每个请求现读 os.environ，所以不用重启进程。
 * App 设置面板和浏览器调试台写的是同一个文件，两边永远一致。
 */
public final class ThinkConfig {

    public static final String[] MODES = {"off", "catalog", "high"};

    private ThinkConfig() {
    }

    public static File file(Context ctx) {
        return new File(PyRunner.codeDir(ctx), "wb_app/thinking.json");
    }

    /** 主开关：关掉就完全不干预，回到上游默认行为 */
    public static boolean enabled(Context ctx) {
        try {
            JSONObject o = new JSONObject(read(file(ctx)));
            return o.optBoolean("enabled", true);
        } catch (Throwable ignored) {
            return true;
        }
    }

    public static String mode(Context ctx) {
        try {
            JSONObject o = new JSONObject(read(file(ctx)));
            String m = o.optString("mode", "off").trim().toLowerCase();
            for (String v : MODES) {
                if (v.equals(m)) return v;
            }
        } catch (Throwable ignored) {
        }
        return "off";
    }

    public static boolean forceOff(Context ctx) {
        try {
            return new JSONObject(read(file(ctx))).optBoolean("force_off", false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void save(Context ctx, String mode, boolean forceOff) {
        save(ctx, mode, forceOff, true);
    }

    public static void save(Context ctx, String mode, boolean forceOff, boolean enabled) {
        String m = mode;
        boolean ok = false;
        for (String v : MODES) {
            if (v.equals(m)) ok = true;
        }
        if (!ok) m = "off";
        try {
            File f = file(ctx);
            File dir = f.getParentFile();
            if (dir != null) dir.mkdirs();
            JSONObject o = new JSONObject();
            o.put("enabled", enabled);
            o.put("mode", m);
            o.put("force_off", forceOff);
            o.put("updated_at", System.currentTimeMillis() / 1000L);
            File tmp = new File(f.getAbsolutePath() + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(o.toString().getBytes("UTF-8"));
                out.flush();
                out.getFD().sync();
            } finally {
                out.close();
            }
            if (!tmp.renameTo(f)) {
                // rename 失败就直写
                FileOutputStream direct = new FileOutputStream(f);
                try {
                    direct.write(o.toString().getBytes("UTF-8"));
                } finally {
                    direct.close();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static String read(File f) {
        if (!f.exists()) return "{}";
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (Throwable t) {
            return "{}";
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
