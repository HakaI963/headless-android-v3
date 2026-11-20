package com.bliss.headlessandroid;

import android.content.Context;
import android.content.SharedPreferences;

/** User preferences, namespaced under one file. */
public final class Prefs {
    private static final String FILE = "droid";

    private static final String KEY_PORT = "mcp_port";
    private static final String KEY_TOKEN = "mcp_token";
    private static final String KEY_COLUMNS = "grid_columns";
    private static final String KEY_SCREEN = "tile_screen";
    private static final String KEY_WAKE = "keep_awake";
    private static final String KEY_LAST = "last_packages";

    private static volatile SharedPreferences prefs;

    public static SharedPreferences get(Context context) {
        SharedPreferences local = prefs;
        if (local == null) {
            synchronized (Prefs.class) {
                if (prefs == null) {
                    prefs = context.getApplicationContext()
                            .getSharedPreferences(FILE, Context.MODE_PRIVATE);
                }
                local = prefs;
            }
        }
        return local;
    }

    public static int port(Context context) {
        int port = get(context).getInt(KEY_PORT, Droid.DEFAULT_PORT);
        return Droid.clamp(port, 1024, 65535, Droid.DEFAULT_PORT);
    }

    public static void port(Context context, int value) {
        get(context).edit().putInt(KEY_PORT, Droid.clamp(value, 1024, 65535, Droid.DEFAULT_PORT)).apply();
    }

    public static String token(Context context) {
        SharedPreferences prefs = get(context);
        String token = prefs.getString(KEY_TOKEN, null);
        if (token == null || token.trim().isEmpty()) {
            token = Droid.hex();
            prefs.edit().putString(KEY_TOKEN, token).apply();
        }
        return token.trim();
    }

    public static String newToken(Context context) {
        String token = Droid.hex();
        get(context).edit().putString(KEY_TOKEN, token).apply();
        return token;
    }

    public static int columns(Context context) {
        return Droid.clamp(get(context).getInt(KEY_COLUMNS, 3), 1, Droid.MAX_COLUMNS, 3);
    }

    public static void columns(Context context, int value) {
        get(context).edit().putInt(KEY_COLUMNS, Droid.clamp(value, 1, Droid.MAX_COLUMNS, 3)).apply();
    }

    public static int screen(Context context) {
        int index = Droid.clamp(get(context).getInt(KEY_SCREEN, Droid.DEFAULT_SCREEN_INDEX),
                1, Droid.SCREENS.length, Droid.DEFAULT_SCREEN_INDEX) - 1;
        return index;
    }

    public static void screen(Context context, int index) {
        get(context).edit().putInt(KEY_SCREEN, index + 1).apply();
    }

    public static boolean keepAwake(Context context) {
        return get(context).getBoolean(KEY_WAKE, true);
    }

    public static void keepAwake(Context context, boolean value) {
        get(context).edit().putBoolean(KEY_WAKE, value).apply();
    }

    /** Packages remembered so a restart can relaunch the same grid. */
    public static String lastPackages(Context context) {
        return get(context).getString(KEY_LAST, "");
    }

    public static void lastPackages(Context context, String value) {
        get(context).edit().putString(KEY_LAST, value == null ? "" : value).apply();
    }
}