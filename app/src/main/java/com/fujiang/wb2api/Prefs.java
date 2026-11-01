package com.fujiang.wb2api;

import android.content.Context;
import android.content.SharedPreferences;

/** 极简偏好设置：端口、局域网、面板密码、悬浮按钮位置。 */
public final class Prefs {

    private static final String FILE = "wb2api";
    public static final String DEFAULT_PANEL_PWD = "admin";

    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public int port() {
        int p = sp.getInt("port", 8888);
        if (p < 1024 || p > 65535) p = 8888;
        return p;
    }

    public void setPort(int p) {
        // commit() 而不是 apply()：改完端口/局域网会立刻 os._exit 重启进程，
        // apply() 的异步落盘经常赶不上，重启后就变回旧值了（app3 的 bug）
        sp.edit().putInt("port", p).commit();
    }

    public boolean lan() {
        return sp.getBoolean("lan", false);
    }

    public void setLan(boolean v) {
        sp.edit().putBoolean("lan", v).commit();
    }

    /**
     * WB-THINK-LEVEL 全局默认思考强度。
     * off / low / high / max —— 新建对话用这个档。
     */
    public String thinkLevel() {
        return sp.getString("think_level", "high");
    }

    public void setThinkLevel(String v) {
        sp.edit().putString("think_level", v == null ? "high" : v).commit();
    }

    public boolean autoStart() {
        return sp.getBoolean("autostart", true);
    }

    public void setAutoStart(boolean v) {
        sp.edit().putBoolean("autostart", v).commit();
    }

    /** 面板密码：用于 ?pwd= 自动登录，用户不用每次手输。 */
    public String panelPwd() {
        String v = sp.getString("panel_pwd", DEFAULT_PANEL_PWD);
        return (v == null || v.isEmpty()) ? DEFAULT_PANEL_PWD : v;
    }

    public void setPanelPwd(String v) {
        sp.edit().putString("panel_pwd", v == null ? DEFAULT_PANEL_PWD : v).commit();
    }

    /** 悬浮按钮位置，-1 表示还没拖过。 */
    public float menuX() {
        return sp.getFloat("menu_x", -1f);
    }

    public float menuY() {
        return sp.getFloat("menu_y", -1f);
    }

    public void setMenuPos(float x, float y) {
        sp.edit().putFloat("menu_x", x).putFloat("menu_y", y).apply();  // 位置不着急，apply 够了
    }
}
