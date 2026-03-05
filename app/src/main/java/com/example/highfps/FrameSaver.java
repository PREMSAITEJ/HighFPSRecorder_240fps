package com.example.highfps;

import android.graphics.ImageFormat;
import android.media.Image;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * FrameSaver: Encodes Image frames to TIFF format and saves to disk.
 *
 * TIFF (Tag Image File Format) preserves full pixel fidelity without compression.
 * Each frame is a standalone file with sequential numbering.
 */
public class FrameSaver {
    private static final String TAG = "FrameSaver";

    // TIFF constants
    private static final byte[] TIFF_LITTLE_ENDIAN = new byte[] {
        (byte) 0x49, (byte) 0x49  // "II" - Intel byte order
    };
    private static final byte[] TIFF_MAGIC = new byte[] {
        (byte) 0x2A, (byte) 0x00  // 42 in little-endian (TIFF magic number)
    };

    private final File framesFolder;
    private long sessionStartTime;
    private int frameCounter;

    public FrameSaver(File framesFolder) {
        this.framesFolder = framesFolder;
        this.frameCounter = 0;
        this.sessionStartTime = System.currentTimeMillis();
    }

    /**
     * Save a single frame as TIFF file.
     *
     * Filename format: frame_XXXXX_<timestamp>.tiff
     * where XXXXX is zero-padded frame number, timestamp is in milliseconds.
     */
    public synchronized boolean saveFrame(Image image) {
        if (image == null) {
            Log.w(TAG, "Received null image");
            return false;
        }

        try {
            frameCounter++;
            long timestamp = System.currentTimeMillis();
            String filename = String.format(Locale.US, "frame_%05d_%d.tiff", frameCounter, timestamp);
            File frameFile = new File(framesFolder, filename);

            // Convert Image (YUV420) to RGB
            byte[] rgbData = convertYUVToRGB(image);

            // Write TIFF file
            boolean success = writeTIFFFile(frameFile, rgbData, image.getWidth(), image.getHeight());

            if (success) {
                Log.d(TAG, "Saved frame " + frameCounter + ": " + filename);
            } else {
                Log.e(TAG, "Failed to write TIFF: " + filename);
            }

            return success;

        } catch (Exception e) {
            Log.e(TAG, "Error saving frame", e);
            return false;
        } finally {
            image.close();
        }
    }

    /**
     * Convert YUV420 (standard camera format) to RGB.
     * This is computationally expensive; consider GPU acceleration for production.
     */
    private byte[] convertYUVToRGB(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();

        if (image.getFormat() != ImageFormat.YUV_420_888) {
            throw new IllegalArgumentException("Expected YUV_420_888 format");
        }

        // Get YUV planes
        Image.Plane yPlane = image.getPlanes()[0];
        Image.Plane uPlane = image.getPlanes()[1];
        Image.Plane vPlane = image.getPlanes()[2];

        int ySize = yPlane.getBuffer().remaining();
        int uvSize = uPlane.getBuffer().remaining();

        byte[] yData = new byte[ySize];
        byte[] uData = new byte[uvSize];
        byte[] vData = new byte[uvSize];

        yPlane.getBuffer().get(yData);
        uPlane.getBuffer().get(uData);
        vPlane.getBuffer().get(vData);

        // Convert YUV to RGB (BT.601 standard)
        byte[] rgbData = new byte[width * height * 3];

        int pixelCount = width * height;
        for (int i = 0; i < pixelCount; i++) {
            int y = yData[i] & 0xFF;
            int u = uData[i / 4] & 0xFF;
            int v = vData[i / 4] & 0xFF;

            // BT.601 color space conversion
            int r = (int) (y + 1.402 * (v - 128));
            int g = (int) (y - 0.344136 * (u - 128) - 0.714136 * (v - 128));
            int b = (int) (y + 1.772 * (u - 128));

            r = Math.max(0, Math.min(255, r));
            g = Math.max(0, Math.min(255, g));
            b = Math.max(0, Math.min(255, b));

            rgbData[i * 3] = (byte) r;
            rgbData[i * 3 + 1] = (byte) g;
            rgbData[i * 3 + 2] = (byte) b;
        }

        return rgbData;
    }

    /**
     * Write RGB data as TIFF file (uncompressed).
     *
     * TIFF structure:
     * - Header (8 bytes)
     * - IFD (Image File Directory) with tags
     * - Pixel data
     */
    private boolean writeTIFFFile(File file, byte[] rgbData, int width, int height) {
        try (FileOutputStream fos = new FileOutputStream(file)) {

            // 1. TIFF Header
            fos.write(TIFF_LITTLE_ENDIAN);  // Byte order (II = little-endian)
            fos.write(TIFF_MAGIC);           // Magic number (42)
            fos.write(toBytes(8, 4));        // Offset to first IFD

            // 2. Image File Directory (IFD)
            // Simplified IFD with essential tags
            int pixelDataOffset = 8 + 2 + (12 * 10) + 4;  // Header + IFD size + pixel data offset

            // Number of directory entries
            fos.write(toBytes(10, 2));  // 10 tags

            // Tag entries (each 12 bytes: tag, type, count, value/offset)
            writeTag(fos, 0x0100, 3, 1, width);           // ImageWidth
            writeTag(fos, 0x0101, 3, 1, height);          // ImageLength
            writeTag(fos, 0x0102, 3, 3, pixelDataOffset); // BitsPerSample (3 samples = RGB)
            writeTag(fos, 0x0103, 3, 1, 1);               // Compression (1 = uncompressed)
            writeTag(fos, 0x0106, 3, 1, 2);               // PhotometricInterpretation (2 = RGB)
            writeTag(fos, 0x0111, 4, 1, pixelDataOffset); // StripOffsets (pixel data location)
            writeTag(fos, 0x0115, 3, 1, 3);               // SamplesPerPixel (3 for RGB)
            writeTag(fos, 0x0116, 3, 1, height);          // RowsPerStrip
            writeTag(fos, 0x0117, 4, 1, rgbData.length);  // StripByteCounts (image data size)
            writeTag(fos, 0x011A, 5, 1, pixelDataOffset + rgbData.length);  // XResolution

            // IFD terminator (next IFD offset = 0)
            fos.write(toBytes(0, 4));

            // 3. Pixel Data (RGB, uncompressed)
            fos.write(rgbData);

            return true;

        } catch (Exception e) {
            Log.e(TAG, "Failed to write TIFF file", e);
            return false;
        }
    }

    /**
     * Write TIFF tag (12 bytes).
     * Format: tag (2), type (2), count (4), value/offset (4)
     */
    private void writeTag(FileOutputStream fos, int tag, int type, int count, int value) throws Exception {
        fos.write(toBytes(tag, 2));
        fos.write(toBytes(type, 2));
        fos.write(toBytes(count, 4));
        fos.write(toBytes(value, 4));
    }

    /**
     * Convert integer to little-endian byte array.
     */
    private byte[] toBytes(int value, int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) ((value >> (i * 8)) & 0xFF);
        }
        return bytes;
    }

    /**
     * Get current frame counter.
     */
    public int getFrameCount() {
        return frameCounter;
    }

    /**
     * Get elapsed time since session start (milliseconds).
     */
    public long getElapsedTime() {
        return System.currentTimeMillis() - sessionStartTime;
    }

    /**
     * Reset session (for new recording).
     */
    public void resetSession() {
        frameCounter = 0;
        sessionStartTime = System.currentTimeMillis();
    }
}

