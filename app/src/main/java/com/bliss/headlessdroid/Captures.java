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

    /**
     * Moves a finished local file into Downloads and deletes the copy.
     *
     * <p>Copied in chunks, not read into one array: a recording can outgrow a
     * byte[], and the moov atom lives at the end of an MP4, so a truncated copy
     * would produce a file that no player can open.
     */
    static String publish(Context context, String folder, String name, File file) {
        if (file == null || !file.isFile() || file.length() <= 0) {
            return null;
        }
        long total = file.length();
        if (Build.VERSION.SDK_INT >= 29) {
            Uri target = null;
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                values.put(MediaStore.MediaColumns.MIME_TYPE, mime(name));
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, folder);
                target = context.getContentResolver()
                        .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (target == null) {
                    return null;
                }
                try (FileInputStream in = new FileInputStream(file);
                     OutputStream out = context.getContentResolver().openOutputStream(target)) {
                    if (out == null) {
                        return null;
                    }
                    copy(in, out, total);
                }
                try {
                    file.delete();
                } catch (Throwable ignored) {
                }
                return name;
            } catch (Throwable e) {
                Log.w(Droid.TAG, "media store write failed: " + e);
                if (target != null) {
                    try {
                        context.getContentResolver().delete(target, null, null);
                    } catch (Throwable ignored) {
                    }
                }
                return null;
            }
        }
        File directory = new File(Environment.getExternalStorageDirectory(), folder);
        if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory()) {
            return null;
        }
        File destination = new File(directory, name);
        try (FileInputStream in = new FileInputStream(file);
             FileOutputStream out = new FileOutputStream(destination)) {
            copy(in, out, total);
            try {
                file.delete();
            } catch (Throwable ignored) {
            }
            return name;
        } catch (Throwable e) {
            Log.w(Droid.TAG, "legacy write failed: " + e);
            return null;
        }
    }

    private static void copy(FileInputStream in, OutputStream out, long total)
            throws java.io.IOException {
        byte[] buffer = new byte[256 * 1024];
        long written = 0;
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
            written += read;
            if (written > total) {
                throw new java.io.IOException("source changed while copying");
            }
        }
        out.flush();
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

    /**
     * Reads a published capture back, by file name and folder.
     *
     * <p>Used by the endpoint's {@code /files/<kind>/<name>} route, so a client
     * can pull the same bytes that were written to Downloads.
     */
    static byte[] read(Context context, String folder, String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        if (Build.VERSION.SDK_INT >= 29) {
            try (android.database.Cursor cursor = context.getContentResolver().query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.MediaColumns._ID},
                    MediaStore.MediaColumns.DISPLAY_NAME + " = ? AND "
                            + MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?",
                    new String[]{name, folder + "%"}, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    long id = cursor.getLong(0);
                    Uri uri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            String.valueOf(id));
                    try (java.io.InputStream in = context.getContentResolver()
                            .openInputStream(uri)) {
                        if (in == null) {
                            return null;
                        }
                        return readAll(in);
                    }
                }
            } catch (Throwable e) {
                Log.w(Droid.TAG, "read capture failed: " + e);
            }
            return null;
        }
        File directory = new File(Environment.getExternalStorageDirectory(), folder);
        if (!directory.isDirectory()) {
            return null;
        }
        File file = new File(directory, name);
        if (!file.isFile()) {
            return null;
        }
        try (java.io.InputStream in = new FileInputStream(file)) {
            return readAll(in);
        } catch (Throwable e) {
            return null;
        }
    }

    private static byte[] readAll(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[128 * 1024];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
            if (out.size() > MAX_CAPTURE_BYTES) {
                throw new java.io.IOException("capture is too large to serve");
            }
        }
        return out.toByteArray();
    }

    private static final int MAX_CAPTURE_BYTES = 96 * 1024 * 1024;

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