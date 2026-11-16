package com.bliss.headlessdroid;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * The privileged half of the app, kept inside the Shizuku user-service process.
 *
 * <p>Two things live here and cannot live anywhere else: starting a real app on
 * a display created by the app process ({@code am start --display <id>}), and
 * injecting input events addressed to one specific display instead of the phone
 * screen (see {@link Inputs}).
 */
final class Cmds {
    private Cmds() {
    }

    static final class Result {
        final int code;
        final String out;

        Result(int code, String out) {
            this.code = code;
            this.out = out == null ? "" : out;
        }

        boolean ok() {
            return code == 0;
        }
    }

    /** Runs a shell command and captures its combined output. */
    static Result run(String line) {
        try {
            Process process = new ProcessBuilder("/system/bin/sh", "-c", line)
                    .redirectErrorStream(true)
                    .start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String row;
                while ((row = reader.readLine()) != null) {
                    out.append(row).append('\n');
                }
            }
            boolean done = process.waitFor(25, TimeUnit.SECONDS);
            if (!done) {
                process.destroyForcibly();
                return new Result(-1, "timeout " + out.toString().trim());
            }
            return new Result(process.exitValue(), out.toString().trim());
        } catch (Throwable e) {
            return new Result(-1, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * Starts an installed app's main activity on the given virtual display.
     *
     * <p>{@code FLAG_ACTIVITY_NEW_TASK} plus {@code RESET_TASK_IF_NEEDED} gives
     * the app its own task on that display, so nine apps can share one display
     * without dragging each other's task along.
     */
    static Result startOnDisplay(String component, int displayId) {
        return run("am start --user 0 --display " + displayId
                + " -f 0x18000000 -W -n " + quote(component));
    }

    /** force-stop is the system-supported way to close a headless app. */
    static Result forceStop(String packageName) {
        return run("am force-stop --user 0 " + quote(packageName));
    }

    /** Brings the task of a display to the front, like a task switch. */
    static Result moveToFront(String component, int displayId) {
        return run("am stack list | head -n 1 >/dev/null; "
                + "am start --user 0 --display " + displayId
                + " -f 0x18000000 -W -n " + quote(component));
    }

    static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}