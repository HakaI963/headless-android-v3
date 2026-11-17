package com.bliss.headlessdroid;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Process split, and the app's safety net.
 *
 * <p>Two processes run from this app: the normal one, which draws the UI, owns
 * the virtual displays and serves MCP; and {@code com.bliss.headlessdroid:shell},
 * which Shizuku starts with shell identity. [onCreate] checks the uid so the
 * shell process never builds a grid or starts an HTTP server of its own.
 *
 * <p>Every failure also lands in a file the user can actually read, because an
 * app that dies silently on a phone with no cable attached gives nobody
 * anything to work with.
 */
public class App extends Application {
    private static final String CRASH_DIR = "Download/HeadlessDroid/crashes";

    private static volatile Context context;
    private static volatile boolean privileged;
    private static volatile String startupError = "";

    @Override
    public void onCreate() {
        super.onCreate();
        context = getApplicationContext();
        int uid = android.os.Process.myUid();
        privileged = uid == 1000 || uid == android.os.Process.SHELL_UID
                || uid == android.os.Process.ROOT_UID;
        if (privileged) {
            Log.i(Droid.TAG, "shell process, uid=" + uid + ": engine stays off");
            return;
        }
        installCrashHandler();
        try {
            Engine.start(this);
        } catch (Throwable e) {
            startupError = describe(e);
            Log.w(Droid.TAG, "engine did not start: " + startupError);
        }
        try {
            Shell.get(this).refresh();
        } catch (Throwable e) {
            Log.w(Droid.TAG, "shell bridge did not come up: " + e);
        }
        try {
            GridService.sync(this);
        } catch (Throwable e) {
            Log.w(Droid.TAG, "service sync failed: " + e);
        }
    }

    public static Context context() {
        return context;
    }

    public static boolean privileged() {
        return privileged;
    }

    /** The reason the engine did not come up, if it did not. */
    public static String startupError() {
        return startupError;
    }

    /**
     * Writes any uncaught exception to Downloads before the process dies, so
     * the failure can be read on the phone without adb.
     */
    private void installCrashHandler() {
        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            writeCrash(thread, error);
            if (previous != null) {
                previous.uncaughtException(thread, error);
            }
        });
    }

    private void writeCrash(Thread thread, Throwable error) {
        try {
            StringWriter trace = new StringWriter();
            error.printStackTrace(new PrintWriter(trace));
            String name = Captures.fileName("crash", ".txt");
            File directory = new File(android.os.Environment
                    .getExternalStorageDirectory(), CRASH_DIR);
            if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory()) {
                return;
            }
            File file = new File(directory, name);
            try (OutputStreamWriter writer = new OutputStreamWriter(
                    new FileOutputStream(file), "UTF-8")) {
                writer.write("Headless Droid crash\n");
                writer.write("thread: " + (thread == null ? "?" : thread.getName()) + "\n");
                writer.write(describe(error) + "\n\n");
                writer.write(trace.toString());
            }
            Log.i(Droid.TAG, "crash written to " + file.getAbsolutePath());
        } catch (Throwable ignored) {
        }
    }

    private static String describe(Throwable error) {
        if (error == null) {
            return "unknown error";
        }
        String message = error.getMessage();
        return error.getClass().getSimpleName()
                + (message == null || message.trim().isEmpty() ? "" : ": " + message);
    }
}