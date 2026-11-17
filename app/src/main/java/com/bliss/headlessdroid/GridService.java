package com.bliss.headlessdroid;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * Keeps the grid alive while the UI is in the background.
 *
 * <p>Nine running apps and a listening MCP endpoint are the product, so this
 * service is not optional: without it the process can be frozen and the apps
 * inside the grid would stop rendering. The notification is quiet on purpose —
 * one line of status, no promotion.
 */
public class GridService extends Service {
    private static final String CHANNEL = "grid";
    private static final int NOTIFICATION_ID = 71;
    private static final String ACTION_STOP_ALL = "com.bliss.headlessdroid.STOP_ALL";

    /** Starts the service when apps run, stops it when the grid is empty. */
    public static void sync(Context context) {
        Engine engine = Engine.get();
        boolean running = engine != null && engine.count() > 0;
        if (running) {
            try {
                context.startService(new Intent(context, GridService.class));
            } catch (Throwable e) {
                Log.w(Droid.TAG, "start service failed: " + e);
            }
        } else {
            context.stopService(new Intent(context, GridService.class));
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureChannel(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP_ALL.equals(intent.getAction())) {
            Engine engine = Engine.get();
            if (engine != null) {
                engine.closeAll();
            }
            stopSelf();
            return START_NOT_STICKY;
        }
        Engine engine = Engine.get();
        if (engine == null || engine.count() == 0) {
            stopSelf();
            return START_NOT_STICKY;
        }
        Notification notification = build(this);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        refresh();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static void refresh() {
        Context context = App.context();
        if (context == null) {
            return;
        }
        NotificationManager manager = (NotificationManager) context
                .getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        try {
            manager.notify(NOTIFICATION_ID, build(context));
        } catch (Throwable ignored) {
        }
    }

    private static Notification build(Context context) {
        Engine engine = Engine.get();
        int running = engine == null ? 0 : engine.count();
        int recording = 0;
        if (engine != null) {
            for (Tile tile : engine.tiles()) {
                if (tile.recording) {
                    recording++;
                }
            }
        }
        String line = running + (running == 1 ? " app on the grid" : " apps on the grid")
                + (recording > 0 ? " · " + recording + " recording" : "");

        // Addressed by name, so this Java file never depends on the Kotlin UI.
        Intent open = new Intent("android.intent.action.MAIN");
        open.addCategory("android.intent.category.LAUNCHER");
        open.setPackage(Droid.PKG);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openIntent = PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopAll = new Intent(context, GridService.class);
        stopAll.setAction(ACTION_STOP_ALL);
        PendingIntent stopIntent = PendingIntent.getService(context, 1, stopAll,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            builder = new Notification.Builder(context, CHANNEL);
        } else {
            builder = new Notification.Builder(context);
        }
        builder.setSmallIcon(com.bliss.headlessdroid.R.drawable.ic_grid)
                .setContentTitle("Headless Droid")
                .setContentText(line)
                .setContentIntent(openIntent)
                .addAction(0, "Close all", stopIntent)
                .setOngoing(true)
                .setShowWhen(false);
        return builder.build();
    }

    private static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationManager manager = (NotificationManager) context
                .getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Grid status",
                NotificationManager.IMPORTANCE_MIN);
        channel.setShowBadge(false);
        channel.enableVibration(false);
        channel.setSound(null, null);
        try {
            manager.createNotificationChannel(channel);
        } catch (Throwable ignored) {
        }
    }
}