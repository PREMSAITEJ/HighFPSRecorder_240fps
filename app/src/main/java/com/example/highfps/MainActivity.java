package com.example.highfps;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;

/**
 * High-Speed 240 FPS Raw Frame Capture Activity
 * Captures individual frames directly from camera sensor (no video encoding)
 * Saves as 8-bit grayscale TIFF frames via NDK for PIV analysis
 */
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "HighFPS-Raw";
    private static final int CAMERA_PERMISSION_REQUEST = 100;
    private static final String[] REQUIRED_PERMISSIONS = {Manifest.permission.CAMERA};

    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private NativeFrameProcessor nativeProcessor;
    private boolean isRecording = false;

    // UI
    private Button btnStartStop;
    private TextView tvStatus;
    private TextView tvStats;

    // Constants
    private static final int PREVIEW_WIDTH = 1920;
    private static final int PREVIEW_HEIGHT = 1080;
    private static final int TARGET_FPS = 240;
    private static final String OUTPUT_FORMAT = "grayscale_tiff";  // Raw frames, not video

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initializeUI();
        cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
        cameraThread = new HandlerThread("CameraThread");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());

        if (hasAllPermissions()) {
            startCamera();
        } else {
            requestPermissions();
        }
    }

    private void initializeUI() {
        btnStartStop = findViewById(R.id.btn_start_stop);
        tvStatus = findViewById(R.id.tv_status);
        tvStats = findViewById(R.id.tv_stats);

        btnStartStop.setOnClickListener(v -> {
            if (isRecording) {
                stopRawFrameCapture();
            } else {
                startRawFrameCapture();
            }
        });
    }

    /**
     * Start RAW FRAME CAPTURE from camera sensor
     * - No MediaRecorder (no video encoding)
     * - Direct sensor frame access via ImageReader
     * - Native C++ processing for speed
     */
    private void startRawFrameCapture() {
        if (captureSession == null) {
            Log.e(TAG, "Capture session not ready");
            return;
        }

        try {
            // Create output directory
            File frameDir = new File(getExternalFilesDir(null), "raw_frames_" + 
                    new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()));
            if (!frameDir.mkdirs() && !frameDir.exists()) {
                Log.e(TAG, "Failed to create frame directory");
                return;
            }

            Log.d(TAG, "Starting RAW frame capture to: " + frameDir.getAbsolutePath());

            // Initialize native processor for raw frame handling
            nativeProcessor = new NativeFrameProcessor(
                    PREVIEW_WIDTH,
                    PREVIEW_HEIGHT,
                    frameDir.getAbsolutePath()
            );
            nativeProcessor.startCapture();

            // Create raw frame capture request
            CaptureRequest.Builder requestBuilder = 
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            requestBuilder.addTarget(imageReader.getSurface());

            // Force 240 FPS
            requestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, 
                    new Range<>(TARGET_FPS, TARGET_FPS));
            // Manual exposure for consistency
            requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF);
            requestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, 1_000_000L);  // 1ms
            requestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, 100);

            CaptureRequest request = requestBuilder.build();
            captureSession.setRepeatingRequest(request, null, cameraHandler);

            isRecording = true;
            updateUI("RAW CAPTURING @ " + TARGET_FPS + " FPS (Y-plane grayscale TIFF)");
            
        } catch (CameraAccessException e) {
            Log.e(TAG, "Camera access error: " + e.getMessage());
            updateUI("ERROR: " + e.getMessage());
        }
    }

    /**
     * Stop RAW frame capture
     */
    private void stopRawFrameCapture() {
        try {
            if (captureSession != null) {
                captureSession.stopRepeating();
            }

            if (nativeProcessor != null) {
                nativeProcessor.stopCapture();
                
                // Get statistics
                long frameCount = nativeProcessor.getFrameCount();
                long droppedFrames = nativeProcessor.getDroppedFrames();
                long errorCount = nativeProcessor.getErrorCount();
                double avgProcessingMs = nativeProcessor.getAvgProcessingTimeMs();

                String stats = String.format(
                        "Captured: %d frames\nDropped: %d\nErrors: %d\nAvg processing: %.2f ms",
                        frameCount, droppedFrames, errorCount, avgProcessingMs
                );
                Log.d(TAG, stats);
                updateUI("STOPPED\n\n" + stats);

                nativeProcessor.release();
                nativeProcessor = null;
            }

            isRecording = false;

        } catch (CameraAccessException e) {
            Log.e(TAG, "Error stopping capture: " + e.getMessage());
        }
    }

    /**
     * Start camera and setup ImageReader for raw frames
     */
    private void startCamera() {
        try {
            String cameraId = selectCamera();
            if (cameraId == null) {
                updateUI("ERROR: No camera found");
                return;
            }

            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
            
            // Check 240 FPS capability
            int[] availableFps = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            if (availableFps == null) {
                updateUI("ERROR: No FPS ranges available");
                return;
            }

            boolean supports240Fps = false;
            for (int i = 0; i < availableFps.length; i += 2) {
                if (availableFps[i + 1] >= TARGET_FPS) {
                    supports240Fps = true;
                    break;
                }
            }

            if (!supports240Fps) {
                updateUI("WARNING: Device may not support 240 FPS\nAttempting anyway...");
            }

            // Create ImageReader for raw frame capture (YUV420_888 format)
            imageReader = ImageReader.newInstance(
                    PREVIEW_WIDTH,
                    PREVIEW_HEIGHT,
                    android.graphics.ImageFormat.YUV_420_888,
                    8  // Max 8 frames in queue
            );
            imageReader.setOnImageAvailableListener(this::onRawFrameAvailable, cameraHandler);

            // Open camera
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) 
                    != PackageManager.PERMISSION_GRANTED) {
                return;
            }

            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    MainActivity.this.cameraDevice = camera;
                    createCaptureSession();
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    camera.close();
                    MainActivity.this.cameraDevice = null;
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    camera.close();
                    MainActivity.this.cameraDevice = null;
                    Log.e(TAG, "Camera error: " + error);
                }
            }, cameraHandler);

            updateUI("Camera opened. Ready to capture raw frames.");

        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to start camera: " + e.getMessage());
            updateUI("ERROR: " + e.getMessage());
        }
    }

    /**
     * Create capture session for raw frame streaming
     */
    private void createCaptureSession() {
        try {
            cameraDevice.createCaptureSession(
                    Arrays.asList(imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            MainActivity.this.captureSession = session;
                            updateUI("Capture session ready.\nPress START to begin raw frame capture.");
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.e(TAG, "Capture session configuration failed");
                            updateUI("ERROR: Failed to configure capture session");
                        }
                    },
                    cameraHandler
            );
        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to create capture session: " + e.getMessage());
        }
    }

    /**
     * Callback when raw frame is available from ImageReader
     * This is called directly from camera sensor at 240 FPS
     */
    private void onRawFrameAvailable(ImageReader reader) {
        Image image = reader.acquireLatestImage();
        if (image == null) return;

        try {
            // Extract Y-plane (luminance) directly
            Image.Plane yPlane = image.getPlanes()[0];
            byte[] yData = new byte[yPlane.getBuffer().remaining()];
            yPlane.getBuffer().get(yData);

            // Pass to native processor for immediate TIFF writing
            if (nativeProcessor != null && nativeProcessor.isCapturing()) {
                nativeProcessor.processRawFrame(yData, 
                        yPlane.getPixelStride(),
                        yPlane.getRowPitch());
            }
        } catch (Exception e) {
            Log.e(TAG, "Frame processing error: " + e.getMessage());
        } finally {
            image.close();
        }
    }

    /**
     * Select back camera, fallback to front
     */
    private String selectCamera() throws CameraAccessException {
        String[] cameraIds = cameraManager.getCameraIdList();
        for (String cameraId : cameraIds) {
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                return cameraId;
            }
        }
        // Fallback to first camera
        return cameraIds.length > 0 ? cameraIds[0] : null;
    }

    private void updateUI(String message) {
        runOnUiThread(() -> {
            tvStatus.setText(message);
            btnStartStop.setText(isRecording ? "STOP" : "START");
        });
    }

    private boolean hasAllPermissions() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) 
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, CAMERA_PERMISSION_REQUEST);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                          @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                updateUI("Camera permission denied");
            }
        }
    }

    @Override
    protected void onDestroy() {
        stopRawFrameCapture();
        if (cameraDevice != null) {
            cameraDevice.close();
        }
        if (imageReader != null) {
            imageReader.close();
        }
        if (cameraThread != null) {
            cameraThread.quitSafely();
        }
        super.onDestroy();
    }
}
