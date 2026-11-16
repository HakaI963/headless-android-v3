package com.bliss.headlessdroid;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes screenshots and recordings to the phone's Downloads folder.
 *
 * <p>MediaStore is used on Android 10+ so no storage permission is needed; the
 * files land in {@code Download/HeadlessDroid/shots} and
 * {@code Download/HeadlessDroid/videos} where the user and any tooling can pick
 * them up with a normal file manager or {@code adb pull}.
 */
final class Captures {
    private Captures() {
    }

    static final class Entry {
        public final String name;
        public final String relativePath;
        public final long size;
        public final long modified;

        Entry(String name, String relativePath, long size, long modified) {
            this.name = name;
            this.relativePath = relativePath;
            this.size = size;
            this.modified = modified;
        }
    }

    /** Publishes bytes to Downloads; returns the display name or null. */
    static String publish(Context context, String folder, String name, byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                values.put(MediaStore.MediaColumns.MIME_TYPE, mime(name));
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, folder);
                Uri target = context.getContentResolver()
                        .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (target == null) {
                    return null;
                }
                try (OutputStream out = context.getContentResolver().openOutputStream(target)) {
                    if (out == null) {
                        return null;
                    }
                    out.write(data);
                }
                return name;
            } catch (Throwable e) {
                Log.w(Droid.TAG, "media store write failed: " + e);
                return null;
            }
        }
        File directory = new File(Environment.getExternalStorageDirectory(), folder);
        if (!directory.exists() && !directory.mkdirs()) {
            return null;
        }
        File file = new File(directory, name);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(data);
            return name;
        } catch (Throwable e) {
            Log.w(Droid.TAG, "legacy write failed: " + e);
            return null;
        }
    }

    /** Moves a finished local file into Downloads and deletes the copy. */
    static String publish(Context context, String folder, String name, File file) {
        if (file == null || !file.isFile() || file.length() <= 0) {
            return null;
        }
        byte[] data;
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[(int) Math.min(file.length(), 64 * 1024 * 1024)];
            int offset = 0;
            while (offset < buffer.length) {
                int read = in.read(buffer, offset, buffer.length - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }
            data = offset == buffer.length ? buffer : java.util.Arrays.copyOf(buffer, offset);
        } catch (Throwable e) {
            Log.w(Droid.TAG, "read recording failed: " + e);
            return null;
        }
        String published = publish(context, folder, name, data);
        try {
            file.delete();
        } catch (Throwable ignored) {
        }
        return published;
    }

    static String mime(String name) {
        if (name.endsWith(".mp4")) {
            return "video/mp4";
        }
        if (name.endsWith(".png")) {
            return "image/png";
        }
        return "image/jpeg";
    }

    /** Lists what has been written so an AI client can list and pull files. */
    static List<Entry> list(Context context, String folder) {
        List<Entry> out = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 29) {
            Uri root = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            String[] columns = {MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.DATE_MODIFIED};
            try (android.database.Cursor cursor = context.getContentResolver().query(
                    root, columns,
                    MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?",
                    new String[]{folder + "%"}, null)) {
                if (cursor == null) {
                    return out;
                }
                while (cursor.moveToNext()) {
                    out.add(new Entry(cursor.getString(0), folder,
                            cursor.getLong(1), cursor.getLong(2)));
                }
            } catch (Throwable ignored) {
            }
        }
        out.sort((a, b) -> Long.compare(b.modified, a.modified));
        return out;
    }

    /** Builds a stable, readable file name such as {@code slot1-play-2026-11-16-1812.jpg}. */
    static String fileName(String prefix, String extension) {
        return new java.text.SimpleDateFormat(
                "'" + prefix + "-'yyyy-MM-dd-HHmmss'" + extension + "'",
                java.util.Locale.US).format(new java.util.Date());
    }
}