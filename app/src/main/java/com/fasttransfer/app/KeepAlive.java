package com.fasttransfer.app;

import android.app.AppOpsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Process;
import android.provider.Settings;
import android.text.TextUtils;

/**
 * Keeps track of whether the transfer service can actually run.
 * On MIUI, swiping the app away kills the service, and without "autostart"
 * MIUI refuses to bring it back: it stays "on" in settings but does nothing.
 */
final class KeepAlive {

    enum State { OFF, STOPPED, RUNNING }

    /** MIUI's private app-op for autostart. */
    private static final int OP_MIUI_AUTOSTART = 10008;

    private KeepAlive() { }

    static State state(Context c) {
        if (!enabledInSettings(c)) return State.OFF;
        return UssdService.instance != null ? State.RUNNING : State.STOPPED;
    }

    static boolean enabledInSettings(Context c) {
        String enabled = Settings.Secure.getString(c.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) return false;
        String me = c.getPackageName() + "/" + UssdService.class.getName();
        String meShort = c.getPackageName() + "/." + UssdService.class.getSimpleName();
        for (String s : enabled.split(":")) {
            if (s.equalsIgnoreCase(me) || s.equalsIgnoreCase(meShort)) return true;
        }
        return false;
    }

    static boolean isMiui() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            String v = (String) sp.getMethod("get", String.class).invoke(null, "ro.miui.ui.version.name");
            return !TextUtils.isEmpty(v);
        } catch (Exception e) {
            return false;
        }
    }

    /** 1 = allowed, 0 = blocked, -1 = can't tell (or not MIUI). */
    static int autostart(Context c) {
        if (!isMiui()) return -1;
        try {
            AppOpsManager ops = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            Object mode = AppOpsManager.class
                    .getMethod("checkOpNoThrow", int.class, int.class, String.class)
                    .invoke(ops, OP_MIUI_AUTOSTART, Process.myUid(), c.getPackageName());
            return ((Integer) mode) == AppOpsManager.MODE_ALLOWED ? 1 : 0;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Opens this app's own page in accessibility settings (or the list). */
    static void openServicePage(Context c) {
        String comp = new ComponentName(c, UssdService.class).flattenToString();
        Intent i = new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                .putExtra(Intent.EXTRA_COMPONENT_NAME, comp)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!tryStart(c, i)) {
            Intent list = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .putExtra(":settings:fragment_args_key", comp)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            tryStart(c, list);
        }
    }

    /** MIUI autostart list, or the app's info page elsewhere. */
    static void openAutostart(Context c) {
        Intent i = new Intent().setComponent(new ComponentName("com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!tryStart(c, i)) openAppInfo(c);
    }

    /** App info: battery saver ("no restrictions") lives here on MIUI. */
    static void openAppInfo(Context c) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", c.getPackageName(), null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        tryStart(c, i);
    }

    private static boolean tryStart(Context c, Intent i) {
        try {
            c.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
