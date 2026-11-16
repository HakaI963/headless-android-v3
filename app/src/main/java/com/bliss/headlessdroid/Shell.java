package com.bliss.headlessdroid;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import org.json.JSONObject;

import rikka.shizuku.Shizuku;

/**
 * App-process client for {@link ShellService}.
 *
 * <p>Handles the two things that are annoying about the user-service protocol:
 * the permission request and the fact that the service may die when Shizuku
 * restarts. Every call re-binds when the binder is gone, and every call is
 * allowed to fail loudly so the UI can say "grant Shizuku" instead of quietly
 * doing nothing.
 */
final class Shell {
    static final String STATE_STARTING = "starting";
    static final String STATE_READY = "ready";
    static final String STATE_NO_SHIZUKU = "no shizuku";
    static final String STATE_NO_PERMISSION = "no permission";

    private static final int PERMISSION_CODE = 4471;

    private static volatile Shell instance;

    private final Context context;
    private final Shizuku.UserServiceArgs args;
    private volatile IBinder binder;
    private volatile String state = STATE_STARTING;
    private volatile JSONObject info;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            binder = service;
            refresh();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            binder = null;
            state = STATE_STARTING;
            notifyState();
        }
    };

    public interface Listener {
        void onShellState();
    }

    private final java.util.List<Listener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    private Shell(Context context) {
        this.context = context.getApplicationContext();
        this.args = new Shizuku.UserServiceArgs(
                new ComponentName(context, ShellService.class))
                .processNameSuffix("shell")
                .tag("droid")
                .version(1)
                .daemon(false)
                .debuggable(false);

        try {
            Shizuku.addBinderReceivedListenerSticky(this::refresh);
            Shizuku.addBinderDeadListener(() -> {
                binder = null;
                state = STATE_STARTING;
                notifyState();
            });
            Shizuku.addRequestPermissionResultListener((code, result) -> {
                if (result == PackageManager.PERMISSION_GRANTED) {
                    bind();
                }
                refresh();
            });
        } catch (Throwable e) {
            Log.w(Droid.TAG, "shizuku listener registration failed: " + e);
        }
    }

    public static Shell get(Context context) {
        Shell local = instance;
        if (local == null) {
            synchronized (Shell.class) {
                if (instance == null) {
                    instance = new Shell(context);
                }
                local = instance;
            }
        }
        return local;
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private void notifyState() {
        for (Listener listener : listeners) {
            try {
                listener.onShellState();
            } catch (Throwable ignored) {
            }
        }
    }

    public String state() {
        return state;
    }

    public boolean ready() {
        return STATE_READY.equals(state);
    }

    public JSONObject shellInfo() {
        return info;
    }

    /** Re-evaluates Shizuku availability and (re)binds the user service. */
    public void refresh() {
        try {
            if (!Shizuku.pingBinder()) {
                state = STATE_NO_SHIZUKU;
                binder = null;
                notifyState();
                return;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                state = STATE_NO_PERMISSION;
                notifyState();
                return;
            }
            bind();
        } catch (Throwable e) {
            state = STATE_NO_SHIZUKU;
            notifyState();
        }
    }

    public void bind() {
        try {
            if (!Shizuku.pingBinder()) {
                state = STATE_NO_SHIZUKU;
                notifyState();
                return;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                state = STATE_NO_PERMISSION;
                notifyState();
                return;
            }
            if (binder != null && binder.pingBinder()) {
                state = STATE_READY;
                notifyState();
                return;
            }
            binder = null;
            state = STATE_STARTING;
            Shizuku.bindUserService(args, connection);
            notifyState();
        } catch (Throwable e) {
            Log.w(Droid.TAG, "bind failed: " + e);
            state = STATE_STARTING;
            notifyState();
        }
    }

    /** Prompts the Shizuku permission sheet. */
    public void requestPermission() {
        try {
            Shizuku.requestPermission(PERMISSION_CODE);
        } catch (Throwable e) {
            Log.w(Droid.TAG, "requestPermission failed: " + e);
        }
    }

    public void unbind() {
        try {
            Shizuku.unbindUserService(args, connection, true);
        } catch (Throwable ignored) {
        }
        binder = null;
    }

    /** Sends one JSON command and returns the reply, or null when unavailable. */
    JSONObject exec(JSONObject command) {
        IBinder local = binder;
        if (local == null || !local.pingBinder()) {
            refresh();
            local = binder;
            if (local == null) {
                return null;
            }
        }
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        try {
            data.writeString(command.toString());
            local.transact(1, data, reply, 0);
            String raw = reply.readString();
            if (raw == null || raw.isEmpty()) {
                return null;
            }
            JSONObject out = new JSONObject(raw);
            if (out.has("uid")) {
                info = out;
            }
            return out;
        } catch (RemoteException e) {
            binder = null;
            state = STATE_STARTING;
            refresh();
            return null;
        } catch (Throwable e) {
            Log.w(Droid.TAG, "shell transaction failed: " + e);
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    boolean ping() {
        IBinder local = binder;
        if (local == null) {
            return false;
        }
        android.os.Parcel data = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        try {
            local.transact(2, data, reply, 0);
            return "pong".equals(reply.readString());
        } catch (Throwable e) {
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    void confirmReady() {
        JSONObject reply = command("info");
        if (reply != null && reply.optBoolean("ok")) {
            info = reply;
            state = STATE_READY;
        } else {
            state = STATE_STARTING;
            bind();
        }
        notifyState();
    }

    JSONObject command(String cmd) {
        try {
            return exec(new JSONObject().put("cmd", cmd));
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * Fallback that starts an app on a display without any privileged service,
     * using the public {@link ActivityOptions#setLaunchDisplayId}.
     *
     * <p>Some ROMs refuse this for foreign packages; the UI reports a clear
     * failure in that case and Shizuku remains the supported path.
     */
    static String startWithoutShell(Context context, Intent intent, int displayId) {
        try {
            ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchDisplayId(displayId);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            context.startActivity(intent, options.toBundle());
            return "ok";
        } catch (Throwable e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }
}