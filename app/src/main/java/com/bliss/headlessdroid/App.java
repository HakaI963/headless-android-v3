package com.bliss.headlessdroid;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Process;
import android.util.Log;

import java.lang.reflect.Field;

/**
 * Process split.
 *
 * <p>Two processes run from this app: the normal one, which draws the UI, owns
 * the virtual displays and serves MCP; and {@code com.bliss.headlessdroid:shell},
 * which Shizuku starts with shell identity to launch apps and inject input.
 * {@code onCreate} checks the uid so the shell process never builds a grid or
 * starts an HTTP server of its own.
 */
public class App extends Application {
    private static volatile Context context;
    private static volatile boolean privilegedProcess;

    @Override
    public void onCreate() {
        super.onCreate();
        context = getApplicationContext();
        int uid = Process.myUid();
        privilegedProcess = uid == 1000 || uid == Process.SHELL_UID || uid == Process.ROOT_UID;
        if (privilegedProcess) {
            Log.i(Droid.TAG, "shell process, uid=" + uid + ": engine stays off");
            return;
        }
        Engine.start(this);
        Shell.get(this).refresh();
        GridService.sync(this);
    }

    public static Context context() {
        return context;
    }

    public static boolean privileged() {
        return privilegedProcess;
    }

    /** Starts the foreground service, called when the first tile is added. */
    public static void startGrid() {
        Context app = context;
        if (app != null) {
            try {
                GridService.sync(app);
            } catch (Throwable e) {
                Log.w(Droid.TAG, "service start failed: " + e);
            }
        }
    }

    public static void stopGrid() {
        Context app = context;
        if (app == null) {
            return;
        }
        try {
            app.stopService(new Intent(app, GridService.class));
        } catch (Throwable ignored) {
        }
    }
}