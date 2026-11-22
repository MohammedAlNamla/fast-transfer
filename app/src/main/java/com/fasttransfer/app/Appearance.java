package com.fasttransfer.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

/** App-only light/dark choice. 0 = follow the phone, 1 = light, 2 = dark. */
final class Appearance {

    static final int AUTO = 0, LIGHT = 1, DARK = 2;

    private Appearance() { }

    static int get(Context c) {
        return prefs(c).getInt("theme", AUTO);
    }

    static void set(Context c, int mode) {
        prefs(c).edit().putInt("theme", mode).apply();
    }

    /** A context whose resources resolve light or dark colors as chosen. */
    static Context wrap(Context c) {
        int mode = get(c);
        if (mode == AUTO) return c;
        Configuration cfg = new Configuration(c.getResources().getConfiguration());
        cfg.uiMode = (cfg.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (mode == DARK ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        return c.createConfigurationContext(cfg);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }
}
