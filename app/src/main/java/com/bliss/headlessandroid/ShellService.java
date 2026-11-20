package com.bliss.headlessandroid;

import android.content.Context;
import android.os.Binder;
import android.os.HandlerThread;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import org.json.JSONObject;

/**
 * Shizuku user service: the privileged half of Headless Android.
 *
 * <p>Shizuku starts this class in its own process with shell identity
 * ({@code uid 2000}), which is the only identity on a normal, unrooted phone
 * that may start an activity on another display and inject input events aimed
 * at one display. The display itself is created by the app process (see
 * {@link DisplayStack}); this service only performs the actions that need the
 * shell permission, then returns JSON over one Binder transaction.
 *
 * <p>Protocol: transaction 1 takes a command string and replies with a JSON
 * string; transaction 2 answers "pong" so the client can test liveness.
 */
public class ShellService extends Binder {
    private static final String TAG = "HeadlessAndroid.Shell";

    private static final int TX_EXEC = 1;
    private static final int TX_PING = 2;

    private volatile Context context;
    private volatile Inputs inputs;
    private volatile Automation automation;
    private volatile HandlerThread automationThread;

    /** Shizuku instantiates the service with the no-arg constructor. */
    public ShellService() {
        this(null);
    }

    /** Kept for Shizuku builds that hand the app's Application over. */
    public ShellService(Context context) {
        super();
        this.context = context;
    }

    private Context ctx() {
        Context local = context;
        if (local != null) {
            return local;
        }
        try {
            Class<?> thread = Class.forName("android.app.ActivityThread");
            local = (Context) thread.getMethod("currentApplication").invoke(null);
        } catch (Throwable ignored) {
        }
        if (local == null) {
            try {
                Class<?> thread = Class.forName("android.app.ActivityThread");
                local = (Context) thread.getMethod("systemMain").invoke(null);
            } catch (Throwable ignored) {
            }
        }
        context = local;
        return local;
    }

    private Inputs inputs() {
        Inputs local = inputs;
        if (local == null) {
            synchronized (this) {
                if (inputs == null) {
                    Context app = ctx();
                    if (app == null) {
                        Log.w(TAG, "no application context for input bridge");
                    } else {
                        inputs = new Inputs(app);
                    }
                }
                local = inputs;
            }
        }
        return local;
    }

    /**
     * UiAutomation callbacks need a real Looper, so the bridge owns a small
     * thread here in the shell process. It is created on first use only.
     */
    private Automation automation() throws Exception {
        Automation local = automation;
        if (local == null) {
            synchronized (this) {
                if (automation == null) {
                    HandlerThread thread = new HandlerThread("HeadlessAndroid-UiAutomation");
                    thread.setDaemon(true);
                    thread.start();
                    automationThread = thread;
                    automation = new Automation(thread.getLooper());
                }
                local = automation;
            }
        }
        return local;
    }

    @Override
    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        if (code != TX_EXEC && code != TX_PING) {
            return super.onTransact(code, data, reply, flags);
        }
        try {
            if (code == TX_PING) {
                reply.writeString("pong");
                return true;
            }
            String command = data.readString();
            reply.writeString(exec(command == null ? "" : command));
        } catch (Throwable e) {
            Log.e(TAG, "transaction " + code + " failed", e);
            try {
                reply.writeString("{\"ok\":false,\"error\":\""
                        + e.getClass().getSimpleName() + "\"}");
            } catch (Throwable ignored) {
            }
        }
        return true;
    }

    private String exec(String command) {
        JSONObject out = new JSONObject();
        try {
            JSONObject in = new JSONObject(command);
            String cmd = in.optString("cmd", "");
            Context app = ctx();
            switch (cmd) {
                case "info": {
                    out.put("ok", true);
                    out.put("uid", android.os.Process.myUid());
                    out.put("pid", android.os.Process.myPid());
                    out.put("shizuku_version", rikka.shizuku.Shizuku.getVersion());
                    out.put("input_available", inputsOk());
                    out.put("input_reason", inputsReason());
                    try {
                        JSONObject ui = automation().execute(new JSONObject().put("op", "status"));
                        out.put("ui_automation", ui);
                    } catch (Throwable e) {
                        out.put("ui_automation", new JSONObject()
                                .put("available", false).put("error", String.valueOf(e.getMessage())));
                    }
                    break;
                }
                case "ui": {
                    return automation().execute(in).toString();
                }
                case "start": {
                    String component = in.optString("component", "");
                    int display = in.optInt("display", -1);
                    if (component.isEmpty() || display < 0) {
                        throw new IllegalArgumentException("bad start command");
                    }
                    Cmds.Result result = Cmds.startOnDisplay(component, display);
                    out.put("ok", result.ok());
                    out.put("code", result.code);
                    out.put("out", result.out);
                    break;
                }
                case "stop": {
                    String pkg = in.optString("pkg", "");
                    Cmds.Result result = Cmds.forceStop(pkg);
                    out.put("ok", result.ok());
                    out.put("code", result.code);
                    out.put("out", result.out);
                    break;
                }
                case "tap": {
                    String reply = pointer("tap", in);
                    return reply;
                }
                case "long": {
                    String reply = longPress(in);
                    return reply;
                }
                case "swipe": {
                    String reply = swipe(in);
                    return reply;
                }
                case "key": {
                    int display = in.optInt("display", -1);
                    int key = in.optInt("code", -1);
                    Inputs bridge = inputs();
                    out.put("ok", bridge != null && bridge.key(display, key));
                    break;
                }
                case "text": {
                    int display = in.optInt("display", -1);
                    Inputs bridge = inputs();
                    out.put("ok", bridge != null && bridge.text(display, in.optString("text", "")));
                    break;
                }
                case "wake": {
                    // A display that slept with the phone screen wakes up when
                    // its task is brought back to the front.
                    String component = in.optString("component", "");
                    int display = in.optInt("display", -1);
                    Cmds.Result result = Cmds.startOnDisplay(component, display);
                    out.put("ok", result.ok());
                    break;
                }
                default:
                    throw new IllegalArgumentException("unknown command " + cmd);
            }
        } catch (Throwable e) {
            try {
                out = new JSONObject();
                out.put("ok", false);
                out.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            } catch (Throwable ignored) {
            }
        }
        return out.toString();
    }

    private String pointer(String what, JSONObject in) throws Exception {
        Inputs bridge = inputs();
        if (bridge == null) {
            return "{\"ok\":false,\"error\":\"input bridge unavailable\"}";
        }
        int display = in.optInt("display", -1);
        float x = (float) in.optDouble("x", 0);
        float y = (float) in.optDouble("y", 0);
        boolean ok = bridge.tap(display, x, y);
        return new JSONObject().put("ok", ok).toString();
    }

    private String longPress(JSONObject in) throws Exception {
        Inputs bridge = inputs();
        if (bridge == null) {
            return "{\"ok\":false,\"error\":\"input bridge unavailable\"}";
        }
        int display = in.optInt("display", -1);
        float x = (float) in.optDouble("x", 0);
        float y = (float) in.optDouble("y", 0);
        int duration = in.optInt("dur", 500);
        boolean ok = bridge.longPress(display, x, y, duration);
        return new JSONObject().put("ok", ok).toString();
    }

    private String swipe(JSONObject in) throws Exception {
        Inputs bridge = inputs();
        if (bridge == null) {
            return "{\"ok\":false,\"error\":\"input bridge unavailable\"}";
        }
        int display = in.optInt("display", -1);
        float x1 = (float) in.optDouble("x1", 0);
        float y1 = (float) in.optDouble("y1", 0);
        float x2 = (float) in.optDouble("x2", 0);
        float y2 = (float) in.optDouble("y2", 0);
        int duration = in.optInt("dur", 300);
        boolean ok = bridge.swipe(display, x1, y1, x2, y2, duration);
        return new JSONObject().put("ok", ok).toString();
    }

    private boolean inputsOk() {
        Inputs bridge = inputs();
        return bridge != null && bridge.available();
    }

    private String inputsReason() {
        Inputs bridge = inputs();
        return bridge == null ? "not initialised" : bridge.reason();
    }

    @Override
    protected void dump(java.io.FileDescriptor fd, java.io.PrintWriter fout, String[] args) {
        fout.println("Headless Android shell service, uid=" + android.os.Process.myUid());
    }
}