package com.bliss.headlessdroid;

import android.graphics.Bitmap;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Records what a slot renders into an MP4.
 *
 * <p>Nine slots can record at once, so each recording owns its codec, its muxer
 * and one daemon thread; nothing is shared between them. Frames arrive from the
 * engine's capture loop as RGBA bitmaps, are converted to the colour layout the
 * encoder reports, and are queued at a paced presentation time so a slow capture
 * still yields a correctly timed file instead of a fast-forward one.
 *
 * <p>This is a screen capture, not a camera: 10 to 15 frames per second per slot
 * is the honest number, and more slots means fewer frames each.
 */
public final class Recorder {
    private static final String TAG = "HeadlessDroid.Rec";
    private static final String MIME = "video/avc";
    private static final long DRAIN_TIMEOUT_MS = 4000;
    private static final long JOIN_SLICE_MS = 400;

    private static final ReentrantLock lock = new ReentrantLock();
    private static final Recording[] slots = new Recording[Droid.MAX_SLOTS + 1];
    private static final AtomicInteger failures = new AtomicInteger();

    private Recorder() {
    }

    /** Starts recording one slot. Returns true when the encoder came up. */
    public static boolean start(int slot, int width, int height, int fps) {
        if (slot < 1 || slot > Droid.MAX_SLOTS) {
            return false;
        }
        int safeWidth = Math.max(64, Math.min(width, 1920));
        int safeHeight = Math.max(64, Math.min(height, 1920));
        int safeFps = Math.max(1, Math.min(fps, 30));

        lock.lock();
        try {
            if (slots[slot] != null) {
                return false;
            }
            Recording recording = new Recording(slot, safeWidth, safeHeight, safeFps);
            if (!recording.ready()) {
                recording.tearDown();
                return false;
            }
            slots[slot] = recording;
            recording.begin();
            Log.i(TAG, "slot " + slot + " recording " + safeWidth + "x" + safeHeight
                    + " @" + safeFps + "fps -> " + recording.file);
            return true;
        } catch (Throwable e) {
            Log.w(TAG, "start " + slot + " failed: " + e);
            return false;
        } finally {
            lock.unlock();
        }
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
     * Queues a frame if enough time has passed since the last one.
     *
     * <p>Called from the engine's single worker thread. It copies the bitmap's
     * pixels once and hands the codec work to the recording thread, so the
     * capture loop is never blocked by an encoder.
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
     * Returns null when there was nothing usable.
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
        String published = recording.finish();
        recording.tearDown();
        Log.i(TAG, "slot " + slot + " stopped -> " + published);
        return published;
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
        recording.tearDown();
        Log.i(TAG, "slot " + slot + " recording aborted");
    }

    /** One slot's recording: a codec, a muxer and the thread that drains them. */
    private static final class Recording implements Runnable {
        final int slot;
        final int width;
        final int height;
        final int fps;
        final File file;
        final String label;

        private final MediaCodec codec;
        private final MediaMuxer muxer;
        private final boolean planar;
        private final boolean flexible;
        private final boolean interleavedChroma;
        private final boolean vFirst;
        private final int bitrate;
        private final byte[] yuv;
        private final int[] pixels;

        private Thread thread;
        private volatile boolean draining;
        private volatile boolean failed;
        private volatile boolean eosQueued;
        private volatile boolean muxerRunning;
        private int trackIndex = -1;
        private long lastQueuedAt;
        private final long intervalNs;
        private final Object stopLock = new Object();

        Recording(int slot, int width, int height, int fps) throws Exception {
            this.slot = slot;
            this.width = width;
            this.height = height;
            this.fps = fps;
            this.intervalNs = 1_000_000_000L / fps;
            this.bitrate = width >= 720 ? 6_000_000 : width >= 540 ? 3_000_000 : 2_500_000;
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

            // Colour layout: use a concrete 4:2:0 format when the encoder reports
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
            this.planar = planarFound && !flexFound;
            this.flexible = flexFound && !planarFound && !semiFound;
            this.interleavedChroma = !planar && !flexible;
            // Semi-planar byte order is vendor specific; AOSP's flexible plane
            // order is Cb then Cr, so the interleaved pair is written Cb-first.
            this.vFirst = false;

            MediaFormat format = MediaFormat.createVideoFormat(MIME, width, height);
            if (flexible) {
                // Deliberately not set: Flexible selects its own layout and the
                // Image API reports the planes.
            } else if (planar) {
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar);
            } else {
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar);
            }
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();

            this.muxer = new MediaMuxer(file.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            this.yuv = new byte[width * height * 3 / 2];
            this.pixels = new int[width * height];
        }

        boolean ready() {
            return codec != null && muxer != null && file.getParentFile() != null;
        }

        void begin() {
            draining = true;
            thread = new Thread(this, TAG + "-slot" + slot);
            thread.setDaemon(true);
            thread.start();
        }

        void offer(Bitmap bitmap) {
            if (failed || eosQueued || thread == null || bitmap.isRecycled()) {
                return;
            }
            long now = System.nanoTime();
            if (lastQueuedAt != 0 && now - lastQueuedAt < intervalNs) {
                return;
            }
            if (bitmap.getWidth() != width || bitmap.getHeight() != height) {
                // The engine resized the slot preset mid-recording; a frame of
                // the wrong size cannot be fed to this encoder.
                return;
            }
            try {
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
                convert();
                lastQueuedAt = now;
                queue(now);
            } catch (Throwable e) {
                fail("frame conversion failed: " + e);
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
            if (interleavedChroma) {
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
                        if (interleavedChroma) {
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

        private void queue(long timestamp) {
            try {
                int index = codec.dequeueInputBuffer(10_000);
                if (index < 0) {
                    return;
                }
                long pts = timestamp / 1000;
                if (flexible) {
                    Image image = codec.getInputImage(index);
                    if (image == null) {
                        Log.w(TAG, "slot " + slot + " encoder gave no input image");
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
                fail("queue failed: " + e);
            }
        }

        /**
         * Writes the packed YUV420 buffer into the planes the codec declares.
         * Handles both packed chroma (pixelStride 1) and the interleaved pair
         * (pixelStride 2), which is the layout some vendors report.
         */
        private void fillPlanes(int index, Image image, long pts) {
            android.media.Image.Plane[] planes = image.getPlanes();
            if (planes == null || planes.length < 3) {
                Log.w(TAG, "slot " + slot + " encoder reported " + (planes == null
                        ? 0 : planes.length) + " planes");
                return;
            }
            int total = width * height;
            int chroma = total / 4;
            int chromaWidth = width / 2;
            int chromaHeight = height / 2;

            ByteBuffer yPlane = planes[0].getBuffer();
            int yRowStride = planes[0].getRowStride();
            int yPixelStride = planes[0].getPixelStride();
            if (yPlane == null || yRowStride < width || yPixelStride != 1) {
                Log.w(TAG, "slot " + slot + " unusable luma plane "
                        + yRowStride + "/" + yPixelStride);
                return;
            }
            for (int row = 0; row < height; row++) {
                yPlane.position(row * yRowStride);
                yPlane.put(yuv, row * width, width);
            }

            ByteBuffer cPlane = planes[1].getBuffer();
            ByteBuffer uOrV = planes[2].getBuffer();
            int rowStride = planes[1].getRowStride();
            int pixelStride = planes[1].getPixelStride();
            int secondRowStride = planes[2].getRowStride();
            int secondPixelStride = planes[2].getPixelStride();
            if (cPlane == null || uOrV == null || pixelStride != 1
                    || secondPixelStride != 1 || rowStride < chromaWidth
                    || secondRowStride < chromaWidth) {
                Log.w(TAG, "slot " + slot + " unusable chroma planes "
                        + pixelStride + "/" + secondPixelStride);
                return;
            }
            int uBase = total;
            int vBase = total + chroma;
            boolean uvShareBuffer = same(cPlane, uOrV, rowStride, secondRowStride);
            for (int row = 0; row < chromaHeight; row++) {
                int uOffset = row * rowStride;
                int vOffset = row * secondRowStride;
                for (int column = 0; column < chromaWidth; column++) {
                    cPlane.put(uOffset + column, yuv[uBase]);
                    if (uvShareBuffer) {
                        // The two planes share one buffer, so the pair is written
                        // interleaved even though both report stride 1.
                        uOrV.put(uOffset + column * 2 + 1, yuv[vBase]);
                    } else {
                        uOrV.put(vOffset + column, yuv[vBase]);
                    }
                    uBase++;
                    vBase++;
                }
            }
            // The size argument is ignored for Image based inputs; the index is
            // what matters, and it must be the one from dequeueInputBuffer.
            codec.queueInputBuffer(index, 0, 0, pts, 0);
            image.close();
        }

        private static boolean same(ByteBuffer a, ByteBuffer b, int rowA, int rowB) {
            return a != null && b != null && a == b && rowA == rowB;
        }

        @Override
        public void run() {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (draining) {
                try {
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
                    boolean key = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                    if (info.size > 0 && !coded && trackIndex >= 0) {
                        ByteBuffer buffer = codec.getOutputBuffer(index);
                        if (buffer != null) {
                            buffer.position(info.offset);
                            buffer.limit(info.offset + info.size);
                            muxer.writeSampleData(trackIndex, buffer, info);
                        }
                    } else if (info.size > 0 && !coded && !key) {
                        Log.w(TAG, "slot " + slot + " dropped a frame before the muxer started");
                    }
                    codec.releaseOutputBuffer(index, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        draining = false;
                        break;
                    }
                } catch (Throwable e) {
                    fail("drain failed: " + e);
                    draining = false;
                    break;
                }
            }
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

        private synchronized void fail(String reason) {
            if (failed) {
                return;
            }
            failed = true;
            draining = false;
            failures.incrementAndGet();
            Log.w(TAG, "slot " + slot + " recording failed: " + reason);
        }

        /**
         * Publishes the finished file. Every wait has a deadline because this
         * runs on the engine's worker thread, which drives the whole grid.
         */
        String finish() {
            synchronized (stopLock) {
                try {
                    if (!failed) {
                        eosQueued = true;
                        int index = codec.dequeueInputBuffer(500_000);
                        if (index >= 0) {
                            if (flexible) {
                                Image image = codec.getInputImage(index);
                                if (image != null) {
                                    image.close();
                                }
                            }
                            codec.queueInputBuffer(index, 0, 0, System.nanoTime() / 1000,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        }
                        Thread local = thread;
                        if (local != null) {
                            long spent = 0;
                            while (local.isAlive() && spent < DRAIN_TIMEOUT_MS) {
                                local.join(JOIN_SLICE_MS);
                                spent += JOIN_SLICE_MS;
                            }
                        }
                    }
                } catch (Throwable e) {
                    Log.w(TAG, "slot " + slot + " stop failed: " + e);
                } finally {
                    draining = false;
                    if (thread != null && thread.isAlive()) {
                        thread.interrupt();
                    }
                    try {
                        if (muxerRunning) {
                            muxer.stop();
                        }
                    } catch (Throwable e) {
                        Log.w(TAG, "slot " + slot + " muxer stop failed: " + e);
                    }
                }
            }
            if (failed || file == null || file.length() <= 0) {
                return null;
            }
            String name = Captures.fileName(label + "-" + width + "x" + height, ".mp4");
            String published = Captures.publish(App.context(), Droid.DIR_VIDEOS, name, file);
            return published != null ? published : file.getAbsolutePath();
        }

        void abandon() {
            draining = false;
            try {
                if (thread != null && thread.isAlive()) {
                    thread.interrupt();
                }
            } catch (Throwable ignored) {
            }
        }

        void tearDown() {
            try {
                codec.stop();
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
            try {
                if (file != null && file.isFile()) {
                    file.delete();
                }
            } catch (Throwable ignored) {
            }
        }
    }
}