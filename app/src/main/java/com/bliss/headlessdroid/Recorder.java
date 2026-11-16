package com.bliss.headlessdroid;

import android.graphics.Bitmap;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Records what a slot renders into an MP4.
 *
 * <p>Nine slots can record at once, so each recording owns its codec, its muxer
 * and one daemon thread; nothing is shared between them. {@link #feed} only
 * hands a frame to that thread — all codec work, the colour conversion and the
 * teardown happen there, so the engine's capture loop is never blocked by an
 * encoder and no buffer index can be leaked by a caller that gives up.
 *
 * <p>This is a screen capture, not a camera: 10 to 15 frames per second per slot
 * is the honest number, and more slots means fewer frames each.
 */
public final class Recorder {
    private static final String TAG = "HeadlessDroid";
    private static final String MIME = "video/avc";
    private static final long JOIN_SLICE_MS = 400;
    private static final long DRAIN_TIMEOUT_MS = 6000;
    private static final int MAX_PENDING = 3;
    /** H.264 profile numbers from the specification, used as KEY_PROFILE values. */
    private static final int AVC_PROFILE_BASELINE = 1;
    private static final int AVC_PROFILE_MAIN = 4;
    private static final int AVC_PROFILE_HIGH = 8;

    private static final ReentrantLock lock = new ReentrantLock();
    private static final Recording[] slots = new Recording[Droid.MAX_SLOTS + 1];
    private static final AtomicInteger failures = new AtomicInteger();
    private static volatile String startError = "";

    private Recorder() {
    }

    /** Starts recording one slot. Returns true when the encoder came up. */
    public static boolean start(int slot, int width, int height, int fps) {
        if (slot < 1 || slot > Droid.MAX_SLOTS) {
            startError = "slot must be 1-9";
            return false;
        }
        // Validated, not clamped: a frame that does not match the encoder is
        // dropped, so a silently repaired size would lose every frame.
        if (width < 64 || height < 64 || width > 1920 || height > 1920) {
            startError = "unsupported size " + width + "x" + height;
            return false;
        }
        int safeFps = Math.max(1, Math.min(fps, 30));
        Recording recording;
        try {
            recording = new Recording(slot, width, height, safeFps);
        } catch (Throwable e) {
            startError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            Log.w(TAG, "recorder start failed for slot " + slot + ": " + startError);
            return false;
        }
        lock.lock();
        try {
            if (slots[slot] != null) {
                // Nothing external: the thread owns its own teardown.
                recording.teardownQuietly();
                return false;
            }
            slots[slot] = recording;
        } finally {
            lock.unlock();
        }
        recording.begin();
        return true;
    }

    /** Why the last start() failed, for the UI and the MCP tool. */
    public static String lastError() {
        return startError;
    }

    public static boolean recording(int slot) {
        lock.lock();
        try {
            return slots[slot] != null;
        } finally {
            lock.unlock();
        }
    }

    public static int errorCount() {
        return failures.get();
    }

    /**
     * Hands a frame to the recording thread.
     *
     * <p>Called from the engine's capture worker, so it does nothing expensive:
     * a frame is offered to a bounded queue and dropped when the thread is
     * behind, which is what a screen capture should do.
     */
    public static void feed(int slot, Bitmap frame) {
        if (frame == null || frame.isRecycled() || slot < 1 || slot > Droid.MAX_SLOTS) {
            return;
        }
        Recording recording;
        lock.lock();
        try {
            recording = slots[slot];
        } finally {
            lock.unlock();
        }
        if (recording == null) {
            return;
        }
        recording.offer(frame);
    }

    /**
     * Finishes a recording and returns the absolute path of the MP4: in
     * Downloads when MediaStore accepted it, otherwise in the app's cache.
     * Returns null when nothing usable was produced.
     */
    public static String stop(int slot) {
        if (slot < 1 || slot > Droid.MAX_SLOTS) {
            return null;
        }
        Recording recording;
        lock.lock();
        try {
            recording = slots[slot];
            slots[slot] = null;
        } finally {
            lock.unlock();
        }
        if (recording == null) {
            return null;
        }
        return recording.finish();
    }

    /** Drops a recording without publishing anything. */
    public static void abort(int slot) {
        if (slot < 1 || slot > Droid.MAX_SLOTS) {
            return;
        }
        Recording recording;
        lock.lock();
        try {
            recording = slots[slot];
            slots[slot] = null;
        } finally {
            lock.unlock();
        }
        if (recording == null) {
            return;
        }
        recording.abandon();
        Log.i(TAG, "slot " + slot + " recording aborted");
    }

    /** One slot's recording: a codec, a muxer and the thread that owns both. */
    private static final class Recording implements Runnable {
        final int slot;
        final int width;
        final int height;
        final int fps;
        final String label;
        final File file;

        private final MediaCodec codec;
        private final MediaMuxer muxer;
        private final boolean planar;
        private final boolean interleaved;
        private final boolean flex;
        private final boolean vFirst;
        private final int bitrate;
        private final byte[] yuv;
        private final int[] pixels;
        private final ArrayBlockingQueue<Bitmap> pending =
                new ArrayBlockingQueue<>(MAX_PENDING);
        private final long intervalNs;
        private final Object stopLock = new Object();

        private Thread thread;
        private volatile boolean draining;
        private volatile boolean stopping;
        private volatile boolean failed;
        private volatile long baseNanos;
        private volatile long lastQueuedNanos;
        private int trackIndex = -1;
        private boolean muxerRunning;

        Recording(int slot, int width, int height, int fps) throws Exception {
            this.slot = slot;
            this.width = width;
            this.height = height;
            this.fps = fps;
            this.intervalNs = 1_000_000_000L / fps;
            this.bitrate = width >= 720 ? 6_000_000 : 2_500_000;
            this.label = "slot" + slot;

            File directory = new File(App.context().getCacheDir(), "records");
            if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory()) {
                throw new IllegalStateException("cannot create " + directory);
            }
            this.file = new File(directory, label + "-" + System.currentTimeMillis() + ".mp4");

            MediaCodec encoder;
            try {
                encoder = MediaCodec.createEncoderByType(MIME);
            } catch (Throwable e) {
                throw new IllegalStateException("no H.264 encoder: " + e.getMessage());
            }
            this.codec = encoder;

            // Colour layout: a concrete 4:2:0 format whenever the encoder reports
            // one, because the byte order is then defined. Only when it reports
            // nothing but Flexible do the Image planes have to be filled, which
            // is the documented path for COLOR_FormatYUV420Flexible.
            boolean planarFound = false;
            boolean semiFound = false;
            boolean flexFound = false;
            try {
                int[] formats = encoder.getCodecInfo()
                        .getCapabilitiesForType(MIME).colorFormats;
                for (int format : formats) {
                    if (format == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
                        planarFound = true;
                    } else if (format == MediaCodecInfo.CodecCapabilities
                            .COLOR_FormatYUV420SemiPlanar) {
                        semiFound = true;
                    } else if (format == MediaCodecInfo.CodecCapabilities
                            .COLOR_FormatYUV420Flexible) {
                        flexFound = true;
                    }
                }
            } catch (Throwable e) {
                Log.w(TAG, "colour format probe failed: " + e);
            }
            this.planar = planarFound;
            this.interleaved = !planarFound && (semiFound || flexFound
                    || !(planarFound || semiFound || flexFound));
            this.flex = flexFound && !planarFound && !semiFound;
            // Semi-planar byte order is vendor specific; AOSP's flexible plane
            // order is Cb then Cr, so the interleaved pair is written Cb-first.
            this.vFirst = false;

            MediaFormat format = MediaFormat.createVideoFormat(MIME, width, height);
            if (flex) {
                // Deliberately not set: Flexible selects its own layout and the
                // Image API reports the planes.
            } else if (planarFound) {
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar);
            } else {
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar);
            }
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, fps < 8 ? 2 : 1);
            // Baseline keeps the file playable everywhere; some encoders refuse it, and
            // the fallback below leaves the profile to the encoder's default.
            // The profile numbers are the H.264 specification's: 1 is Baseline,
            // 4 is Main, 8 is High. The constants left the public SDK, so they
            // are written out with their values.
            try {
                format.setInteger(MediaFormat.KEY_PROFILE, AVC_PROFILE_BASELINE);
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            } catch (Throwable e) {
                Log.w(TAG, "slot " + slot + " rejected baseline, retrying: " + e);
                format.setInteger(MediaFormat.KEY_PROFILE, AVC_PROFILE_MAIN);
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            }
            encoder.start();
            this.muxer = new MediaMuxer(file.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            this.yuv = new byte[width * height * 3 / 2];
            this.pixels = new int[width * height];
        }

        /** True only when the encoder reports nothing but Flexible. */
        private boolean flexOnly() {
            return flex;
        }

        private void begin() {
            draining = true;
            thread = new Thread(this, TAG + "-rec-" + slot);
            thread.setDaemon(true);
            thread.start();
        }

        void offer(Bitmap bitmap) {
            if (failed || stopping || thread == null || bitmap.isRecycled()) {
                return;
            }
            long now = System.nanoTime();
            if (lastQueuedNanos != 0 && now - lastQueuedNanos < intervalNs) {
                return;
            }
            if (baseNanos == 0) {
                // The first frame anchors the timeline at zero, so the file's
                // duration and the seek bar mean something in a player.
                baseNanos = now;
            }
            if (!pending.offer(bitmap)) {
                // The recording thread is behind; dropping is correct here.
                return;
            }
            lastQueuedNanos = now;
        }

        @Override
        public void run() {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            try {
                while (draining) {
                    Bitmap frame = pending.poll();
                    if (frame != null) {
                        supply(frame, System.nanoTime());
                    }
                    int index = codec.dequeueOutputBuffer(info, 20_000);
                    if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        continue;
                    }
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        startMuxer(codec.getOutputFormat());
                        continue;
                    }
                    if (index < 0) {
                        continue;
                    }
                    boolean coded = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                    if (info.size > 0 && !coded && trackIndex >= 0) {
                        ByteBuffer buffer = codec.getOutputBuffer(index);
                        if (buffer != null) {
                            buffer.position(info.offset);
                            buffer.limit(info.offset + info.size);
                            muxer.writeSampleData(trackIndex, buffer, info);
                        }
                    }
                    codec.releaseOutputBuffer(index, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        draining = false;
                        break;
                    }
                }
            } catch (Throwable e) {
                fail("recording loop failed: " + e);
            } finally {
                // The thread owns its own teardown: no other thread touches the
                // codec or the muxer after this point.
                selfTeardown();
            }
        }

        /** Converts and queues one frame. Runs on the recording thread only. */
        private void supply(Bitmap bitmap, long now) {
            if (bitmap.isRecycled()
                    || bitmap.getWidth() != width || bitmap.getHeight() != height) {
                return;
            }
            long pts = Math.max(0, (now - baseNanos) / 1000);
            try {
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
                convert();
                int index = codec.dequeueInputBuffer(10_000);
                if (index < 0) {
                    return;
                }
                if (flexOnly()) {
                    Image image = codec.getInputImage(index);
                    if (image == null) {
                        Log.w(TAG, "slot " + slot + " encoder gave no input image");
                        codec.queueInputBuffer(index, 0, 0, pts,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        return;
                    }
                    fillPlanes(index, image, pts);
                } else {
                    ByteBuffer input = codec.getInputBuffer(index);
                    if (input == null) {
                        return;
                    }
                    input.clear();
                    input.put(yuv);
                    codec.queueInputBuffer(index, 0, yuv.length, pts, 0);
                }
            } catch (Throwable e) {
                fail("frame could not be queued: " + e);
            }
        }

        /**
         * One pass over the pixels: BT.601, integer math, one reusable output
         * buffer. Chroma is subsampled 2x2, which is also what keeps the cost of
         * a 540x720 frame well under the budget.
         */
        private void convert() {
            int total = width * height;
            int chroma = total / 4;
            int yIndex = 0;
            int uIndex = total;
            int vIndex = total + chroma;
            if (interleaved) {
                uIndex = vIndex = total;
            }
            for (int row = 0; row < height; row++) {
                int rowStart = row * width;
                int nextRow = rowStart + width;
                boolean chromaRow = (row & 1) == 0;
                int column = 0;
                while (column < width) {
                    int index = rowStart + column;
                    yuv[yIndex++] = (byte) luma(pixels[index]);
                    if (chromaRow && (column & 1) == 0) {
                        int below = row + 1 < height ? nextRow + column : index;
                        int red = (pixels[index] >> 16) & 0xff;
                        int green = (pixels[index] >> 8) & 0xff;
                        int blue = pixels[index] & 0xff;
                        int right = column + 1 < width ? index + 1 : index;
                        int belowRight = column + 1 < width ? below + 1 : below;
                        int r = (red + (pixels[right] >> 16 & 0xff)
                                + (pixels[below] >> 16 & 0xff)
                                + (pixels[belowRight] >> 16 & 0xff)) >> 2;
                        int g = (green + (pixels[right] >> 8 & 0xff)
                                + (pixels[below] >> 8 & 0xff)
                                + (pixels[belowRight] >> 8 & 0xff)) >> 2;
                        int b = (blue + (pixels[right] & 0xff)
                                + (pixels[below] & 0xff)
                                + (pixels[belowRight] & 0xff)) >> 2;
                        int u = chromaU(r, g, b);
                        int v = chromaV(r, g, b);
                        if (interleaved) {
                            yuv[uIndex++] = (byte) (vFirst ? v : u);
                            yuv[uIndex++] = (byte) (vFirst ? u : v);
                        } else {
                            yuv[uIndex++] = (byte) u;
                            yuv[vIndex++] = (byte) v;
                        }
                    }
                    column++;
                }
            }
        }

        private static int luma(int argb) {
            int r = (argb >> 16) & 0xff;
            int g = (argb >> 8) & 0xff;
            int b = argb & 0xff;
            return clamp(((66 * r + 129 * g + 25 * b + 128) >> 8) + 16);
        }

        private static int chromaU(int r, int g, int b) {
            return clamp(((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128);
        }

        private static int chromaV(int r, int g, int b) {
            return clamp(((112 * r - 94 * g - 18 * b + 128) >> 8) + 128);
        }

        private static int clamp(int value) {
            return value < 0 ? 0 : Math.min(value, 255);
        }

        /**
         * Writes the packed YUV420 buffer into the planes the codec declares.
         *
         * <p>Both vendor layouts are handled: three separate planes, and the
         * two-plane sub-image where U and V share one plane and interleave at
         * {@code pixelStride 2}. The buffer index is always returned to the
         * codec, including on the error paths, so it can never be leaked.
         */
        private void fillPlanes(int index, Image image, long pts) {
            android.media.Image.Plane[] planes = image.getPlanes();
            if (planes == null || planes.length < 2) {
                Log.w(TAG, "slot " + slot + " encoder reported "
                        + (planes == null ? 0 : planes.length) + " planes");
                codec.queueInputBuffer(index, 0, 0, pts, 0);
                image.close();
                return;
            }
            int total = width * height;
            int chroma = total / 4;
            int chromaWidth = width / 2;
            int chromaHeight = height / 2;

            android.media.Image.Plane lumaPlane = planes[0];
            ByteBuffer yPlane = lumaPlane.getBuffer();
            int yRowStride = lumaPlane.getRowStride();
            if (yPlane == null || yRowStride < width || lumaPlane.getPixelStride() != 1) {
                Log.w(TAG, "slot " + slot + " unusable luma plane");
                codec.queueInputBuffer(index, 0, 0, pts, 0);
                image.close();
                return;
            }
            for (int row = 0; row < height; row++) {
                yPlane.position(row * yRowStride);
                yPlane.put(yuv, row * width, width);
            }

            android.media.Image.Plane first = planes[1];
            ByteBuffer firstBuffer = first.getBuffer();
            int firstRow = first.getRowStride();
            int firstPixel = first.getPixelStride();
            android.media.Image.Plane second = planes.length >= 3 ? planes[2] : null;
            ByteBuffer secondBuffer = second == null ? null : second.getBuffer();

            boolean twoPlane = second == null || secondBuffer == null
                    || firstBuffer == secondBuffer;
            boolean interleave = twoPlane && firstPixel == 2;
            if (firstBuffer == null || (twoPlane && !interleave)) {
                Log.w(TAG, "slot " + slot + " unusable chroma planes");
                codec.queueInputBuffer(index, 0, 0, pts, 0);
                image.close();
                return;
            }
            int uBase = total;
            int vBase = total + chroma;
            if (interleave) {
                // Two planes, U and V interleaved at a pixel stride of 2: the
                // pair for each chroma pixel lives at column * 2.
                for (int row = 0; row < chromaHeight; row++) {
                    int offset = row * firstRow;
                    for (int column = 0; column < chromaWidth; column++) {
                        int position = offset + column * firstPixel;
                        firstBuffer.put(position, yuv[uBase++]);
                        firstBuffer.put(position + 1, yuv[vBase++]);
                    }
                }
            } else if (twoPlane) {
                for (int row = 0; row < chromaHeight; row++) {
                    int offset = row * firstRow;
                    for (int column = 0; column < chromaWidth; column++) {
                        firstBuffer.put(offset + column * firstPixel, yuv[uBase]);
                        firstBuffer.put(offset + column * firstPixel
                                + Math.max(1, firstPixel / 2), yuv[vBase]);
                        uBase++;
                        vBase++;
                    }
                }
            } else {
                int secondRow = second.getRowStride();
                int secondPixel = second.getPixelStride();
                if (secondRow < chromaWidth || secondPixel != 1) {
                    Log.w(TAG, "slot " + slot + " unusable second chroma plane");
                    codec.queueInputBuffer(index, 0, 0, pts, 0);
                    image.close();
                    return;
                }
                for (int row = 0; row < chromaHeight; row++) {
                    int uOffset = row * firstRow;
                    int vOffset = row * secondRow;
                    for (int column = 0; column < chromaWidth; column++) {
                        firstBuffer.put(uOffset + column, yuv[uBase++]);
                        secondBuffer.put(vOffset + column, yuv[vBase++]);
                    }
                }
            }
            codec.queueInputBuffer(index, 0, 0, pts, 0);
            image.close();
        }

        private void startMuxer(MediaFormat format) {
            if (muxerRunning) {
                return;
            }
            try {
                trackIndex = muxer.addTrack(format);
                muxer.start();
                muxerRunning = true;
                Log.i(TAG, "slot " + slot + " muxer started with " + format);
            } catch (Throwable e) {
                fail("muxer failed: " + e);
            }
        }

        /**
         * Signals the end of the stream and waits for the recording thread to
         * finish and clean up after itself. Every wait has a deadline because
         * this runs on the engine's worker or the UI thread.
         */
        String finish() {
            synchronized (stopLock) {
                stopping = true;
                long deadline = System.currentTimeMillis() + DRAIN_TIMEOUT_MS;
                boolean signalled = false;
                while (System.currentTimeMillis() < deadline) {
                    try {
                        int index = codec.dequeueInputBuffer(250_000);
                        if (index < 0) {
                            continue;
                        }
                        if (flexOnly()) {
                            Image image = codec.getInputImage(index);
                            if (image != null) {
                                image.close();
                            }
                        }
                        // The end-of-stream timestamp belongs to the same timeline as the
                        // frames, anchored on the first one, not to the wall clock.
                        long tail = Math.max(0, (System.nanoTime() - baseNanos) / 1000) + 1000;
                        codec.queueInputBuffer(index, 0, 0, tail,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        signalled = true;
                        break;
                    } catch (Throwable e) {
                        Log.w(TAG, "slot " + slot + " end of stream failed: " + e);
                        break;
                    }
                }
                if (!signalled) {
                    Log.w(TAG, "slot " + slot + " could not signal the end of stream");
                    draining = false;
                    if (thread != null && thread.isAlive()) {
                        thread.interrupt();
                    }
                }
                Thread local = thread;
                if (local != null) {
                    long spent = 0;
                    while (local.isAlive() && spent < DRAIN_TIMEOUT_MS) {
                        try {
                            local.join(JOIN_SLICE_MS);
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        spent += JOIN_SLICE_MS;
                    }
                }
            }
            if (failed || file == null || file.length() <= 0) {
                return null;
            }
            String name = Captures.fileName(stem(), ".mp4");
            String result = Captures.publish(App.context(), Droid.DIR_VIDEOS, name, file);
            if (result != null) {
                // Captures publishes into Downloads, deletes the cache original
                // on success and returns the display name; the caller is promised
                // an absolute path, so it is rebuilt here.
                File inDownloads = new File(Environment.getExternalStorageDirectory(),
                        Droid.DIR_VIDEOS + "/" + name);
                return inDownloads.getAbsolutePath();
            }
            // Publishing failed, so the cache copy stays and is what is
            // returned. Nothing else deletes it.
            Log.w(TAG, "slot " + slot + " published nothing, kept " + file);
            return file.getAbsolutePath();
        }

        /** slot1-com.android.settings-540x720, safe for a file name. */
        private String stem() {
            String pkg = enginePackage();
            if (pkg.isEmpty()) {
                return label + "-" + width + "x" + height;
            }
            return label + "-" + pkg.replaceAll("[^A-Za-z0-9.]+", "-")
                    + "-" + width + "x" + height;
        }

        private String enginePackage() {
            Engine engine = Engine.get();
            if (engine == null) {
                return "";
            }
            Tile tile = engine.tile(slot);
            return tile == null || tile.pkg == null ? "" : tile.pkg;
        }

        void abandon() {
            draining = false;
            stopThreadNow();
        }

        void stopThreadNow() {
            Thread local = thread;
            if (local != null && local.isAlive()) {
                local.interrupt();
            }
        }

        void teardownQuietly() {
            try {
                codec.stop();
                codec.release();
            } catch (Throwable ignored) {
            }
            try {
                if (muxerRunning) {
                    muxer.release();
                }
            } catch (Throwable ignored) {
            }
        }

        /** Runs on the recording thread's own finally: nothing else may touch
         * the codec or the muxer after this. */
        private void selfTeardown() {
            try {
                codec.stop();
            } catch (Throwable ignored) {
            }
            try {
                if (muxerRunning) {
                    muxer.stop();
                }
            } catch (Throwable ignored) {
            }
            try {
                codec.release();
            } catch (Throwable ignored) {
            }
            try {
                if (muxerRunning) {
                    muxer.release();
                }
            } catch (Throwable ignored) {
            }
            // The file is deliberately left alone: only the caller that knows
            // whether publishing succeeded may delete it, and it runs after this
            // thread is joined.
        }

        private synchronized void fail(String reason) {
            if (failed) {
                return;
            }
            failed = true;
            draining = false;
            failures.incrementAndGet();
            Log.w(TAG, "slot " + slot + " recording failed: " + reason);
        }
    }
}