package com.example.highfps;

import android.util.Log;

/**
 * Native frame processor for raw camera frames
 * Handles direct Y-plane (luminance) extraction and TIFF encoding
 * No video encoding - pure raw frame output at sensor frequency
 */
public class NativeFrameProcessor {
    static {
        System.loadLibrary("highfps_native");
    }

    private static final String TAG = "NativeFrameProcessor";
    private long nativeHandle = 0;

    /**
     * Create native frame processor for raw Y-plane frames
     * @param width Image width (1920 for 1080p)
     * @param height Image height (1080 for 1080p)
     * @param outputDir Directory to save raw TIFF frames
     */
    public NativeFrameProcessor(int width, int height, String outputDir) {
        nativeHandle = nativeCreate(width, height, outputDir);
        if (nativeHandle == 0) {
            throw new RuntimeException("Failed to create native frame processor");
        }
        Log.d(TAG, "Native processor created: " + width + "x" + height + " -> " + outputDir);
    }

    /**
     * Start raw frame capture
     */
    public void startCapture() {
        nativeStartCapture(nativeHandle);
        Log.d(TAG, "Capture started");
    }

    /**
     * Stop raw frame capture
     */
    public void stopCapture() {
        nativeStopCapture(nativeHandle);
        Log.d(TAG, "Capture stopped");
    }

    /**
     * Process raw Y-plane frame from camera sensor
     * Direct passthrough to native TIFF encoder
     * @param yPlaneData Y-plane (luminance) raw bytes from ImageReader
     * @param pixelStride Pixel stride (usually 1 for Y-plane)
     * @param rowPitch Row pitch/stride in bytes
     */
    public void processRawFrame(byte[] yPlaneData, int pixelStride, int rowPitch) {
        if (!isCapturing()) {
            return;
        }
        nativeProcessFrame(nativeHandle, yPlaneData, pixelStride, rowPitch);
    }

    /**
     * Check if currently capturing
     */
    public boolean isCapturing() {
        return nativeIsCapturing(nativeHandle);
    }

    /**
     * Get total frames captured (raw, not encoded)
     */
    public long getFrameCount() {
        return nativeGetFrameCount(nativeHandle);
    }

    /**
     * Get frames dropped due to buffer overflow
     */
    public long getDroppedFrames() {
        return nativeGetDroppedFrames(nativeHandle);
    }

    /**
     * Get encoding/TIFF write errors
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
     * Release native resources
     */
    public void release() {
        if (nativeHandle != 0) {
            nativeDestroy(nativeHandle);
            nativeHandle = 0;
            Log.d(TAG, "Native processor released");
        }
    }

    @Override
    protected void finalize() throws Throwable {
        release();
        super.finalize();
    }

    // ===== JNI Method Declarations =====
    
    private native long nativeCreate(int width, int height, String outputDir);
    private native void nativeDestroy(long handle);
    private native void nativeStartCapture(long handle);
    private native void nativeStopCapture(long handle);
    private native boolean nativeIsCapturing(long handle);
    private native void nativeProcessFrame(long handle, byte[] yPlaneData, 
                                          int pixelStride, int rowPitch);
    private native long nativeGetFrameCount(long handle);
    private native long nativeGetDroppedFrames(long handle);
    private native long nativeGetErrorCount(long handle);
    private native double nativeGetAvgProcessingTime(long handle);
}
