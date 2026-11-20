package com.bliss.headlessandroid;

import android.content.Context;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.lang.reflect.Method;

/**
 * Display-scoped input injection through the hidden {@code InputManager}
 * {@code injectInputEvent} API.
 *
 * <p>This runs inside the Shizuku user-service process, i.e. with shell
 * identity, which holds {@code android.permission.INJECT_EVENTS}.
 * {@code InputEvent.setDisplayId(int)} is what routes the event to a virtual
 * display instead of the phone's own screen, so a tap on tile 3 never lands on
 * the phone UI behind it.
 */
final class Inputs {
    private static final int INJECT_WAIT_FOR_FINISH = 2;

    private final Object manager;
    private final Method inject;
    private final Method setDisplayId;
    private final String unavailable;

    Inputs(Context context) {
        Object service = null;
        Method injectMethod = null;
        Method displayMethod = null;
        String problem = null;
        try {
            service = context.getSystemService(Context.INPUT_SERVICE);
            if (service == null) {
                throw new IllegalStateException("INPUT_SERVICE unavailable");
            }
            Method[] methods = service.getClass().getMethods();
            for (Method method : methods) {
                if ("injectInputEvent".equals(method.getName())
                        && method.getParameterTypes().length == 2) {
                    injectMethod = method;
                }
            }
            if (injectMethod == null) {
                throw new NoSuchMethodException("injectInputEvent(InputEvent,int)");
            }
            displayMethod = InputEvent.class.getMethod("setDisplayId", int.class);
        } catch (Throwable e) {
            problem = e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage());
        }
        this.manager = service;
        this.inject = injectMethod;
        this.setDisplayId = displayMethod;
        this.unavailable = problem;
    }

    boolean available() {
        return manager != null && inject != null && setDisplayId != null;
    }

    String reason() {
        return unavailable == null ? "" : unavailable;
    }

    boolean tap(int displayId, float x, float y) {
        long now = android.os.SystemClock.uptimeMillis();
        return send(displayId, motion(now, now, MotionEvent.ACTION_DOWN, x, y))
                & send(displayId, motion(now, now + 60, MotionEvent.ACTION_UP, x, y));
    }

    boolean longPress(int displayId, float x, float y, int durationMs) {
        int duration = Math.max(400, durationMs);
        long now = android.os.SystemClock.uptimeMillis();
        send(displayId, motion(now, now, MotionEvent.ACTION_DOWN, x, y));
        try {
            Thread.sleep(duration);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        return send(displayId, motion(now, now + duration, MotionEvent.ACTION_UP, x, y));
    }

    boolean swipe(int displayId, float x1, float y1, float x2, float y2, int durationMs) {
        int duration = Math.max(120, durationMs);
        int steps = Math.max(6, duration / 24);
        long down = android.os.SystemClock.uptimeMillis();
        send(displayId, motion(down, down, MotionEvent.ACTION_DOWN, x1, y1));
        for (int i = 1; i < steps; i++) {
            float progress = i / (float) steps;
            float x = x1 + (x2 - x1) * progress;
            float y = y1 + (y2 - y1) * progress;
            send(displayId, motion(down, down + Math.round(duration * progress),
                    MotionEvent.ACTION_MOVE, x, y));
            try {
                Thread.sleep(6);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        return send(displayId, motion(down, down + duration, MotionEvent.ACTION_UP, x2, y2));
    }

    boolean key(int displayId, int keyCode) {
        long now = android.os.SystemClock.uptimeMillis();
        return send(displayId, new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
                & send(displayId, new KeyEvent(now, now + 20, KeyEvent.ACTION_UP, keyCode, 0));
    }

    boolean text(int displayId, String value) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        KeyEvent[] events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
                .getEvents(value.toCharArray());
        if (events == null || events.length == 0) {
            return false;
        }
        boolean ok = true;
        for (KeyEvent event : events) {
            ok = send(displayId, event) & ok;
        }
        return ok;
    }

    private MotionEvent motion(long downTime, long eventTime, int action, float x, float y) {
        // Full 12-argument form: the short overloads mix int and float
        // parameters and the compiler picks the wrong one.
        MotionEvent event = MotionEvent.obtain(downTime, eventTime, action,
                Math.round(x), Math.round(y), 1f, 1f, 0, 1f, 1f, 0, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        return event;
    }

    private boolean send(int displayId, InputEvent event) {
        if (!available()) {
            return false;
        }
        try {
            setDisplayId.invoke(event, displayId);
            Object result = inject.invoke(manager, event, INJECT_WAIT_FOR_FINISH);
            return !(result instanceof Boolean) || (Boolean) result;
        } catch (Throwable e) {
            return false;
        }
    }
}