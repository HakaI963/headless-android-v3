package com.bliss.headlessdroid;

import android.app.Application;
import android.content.Context;
import android.util.Log;

public class App extends Application {
    private static volatile Context context;
    private static volatile boolean privileged;

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
        Engine.start(this);
        Shell.get(this).refresh();
        GridService.sync(this);
    }

    public static Context context() {
        return context;
    }

    public static boolean privileged() {
        return privileged;
    }
}
