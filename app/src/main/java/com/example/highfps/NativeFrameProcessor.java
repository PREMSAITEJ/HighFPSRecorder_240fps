package com.example.highfps;

import android.media.Image;

/**
 * JNI wrapper for native frame processor
 * Handles 240 FPS capture with native YUV→grayscale conversion and TIFF encoding
 */
public class NativeFrameProcessor {
    static {
        System.loadLibrary("highfps_native");
    }

    private long nativeHandle = 0;

    /**
     * Create native frame processor
     * @param width Image width in pixels
     * @param height Image height in pixels
     * @param outputDir Output directory for TIFF frames
     */
    public NativeFrameProcessor(int width, int height, String outputDir) {
        nativeHandle = nativeCreate(width, height, outputDir);
        if (nativeHandle == 0) {
            throw new RuntimeException("Failed to create native frame processor");
        }
    }

    /**
     * Start frame capture
     */
    public void startCapture() {
        nativeStartCapture(nativeHandle);
    }

    /**
     * Stop frame capture
     */
    public void stopCapture() {
        nativeStopCapture(nativeHandle);
    }

    /**
     * Check if currently capturing
     */
    public boolean isCapturing() {
        return nativeIsCapturing(nativeHandle);
    }

    /**
     * Get total frames captured
     */
    public long getFrameCount() {
        return nativeGetFrameCount(nativeHandle);
    }

    /**
     * Get frames dropped due to queue overflow
     */
    public long getDroppedFrames() {
        return nativeGetDroppedFrames(nativeHandle);
    }

    /**
     * Get number of encoding errors
     */
    public long getErrorCount() {
        return nativeGetErrorCount(nativeHandle);
    }

    /**
     * Get average frame processing time in milliseconds
     */
    public double getAvgProcessingTimeMs() {
        return nativeGetAvgProcessingTime(nativeHandle);
    }

    /**
     * Cleanup resources
     */
    public void release() {
        if (nativeHandle != 0) {
            nativeDestroy(nativeHandle);
            nativeHandle = 0;
        }
    }

    @Override
    protected void finalize() throws Throwable {
        release();
        super.finalize();
    }

    // Native methods
    private native long nativeCreate(int width, int height, String outputDir);
    private native void nativeDestroy(long handle);
    private native void nativeStartCapture(long handle);
    private native void nativeStopCapture(long handle);
    private native boolean nativeIsCapturing(long handle);
    private native long nativeGetFrameCount(long handle);
    private native long nativeGetDroppedFrames(long handle);
    private native long nativeGetErrorCount(long handle);
    private native double nativeGetAvgProcessingTime(long handle);
}
