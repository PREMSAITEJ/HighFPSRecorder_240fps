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
import android.widget.SeekBar;
import android.widget.TextView;
import android.view.TextureView;
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
public class MainActivity extends AppCompatActivity implements TextureView.SurfaceTextureListener {
    private static final String TAG = "HighFPS-Raw";
    private static final int CAMERA_PERMISSION_REQUEST = 100;
    private static final String[] REQUIRED_PERMISSIONS = {Manifest.permission.CAMERA};

    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CameraCaptureSession previewSession;
    private ImageReader imageReader;
    private ImageReader previewReader;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private NativeFrameProcessor nativeProcessor;
    private boolean isRecording = false;

    // UI
    private Button btnStartStop;
    private TextView tvStatus;
    private TextView tvStats;
    private SeekBar focusSeekBar;
    private SeekBar brightnessSeekBar;
    private TextView focusValueText;
    private TextView brightnessValueText;
    private TextureView textureView;

    // Constants
    private static final int PREVIEW_WIDTH = 1920;
    private static final int PREVIEW_HEIGHT = 1080;
    private static final int TARGET_FPS = 240;
    private static final String OUTPUT_FORMAT = "grayscale_tiff";  // Raw frames, not video

    // Camera controls
    private float currentFocusDistance = 0.0f;
    private int currentBrightness = 50;
    private String currentCameraId = null;
    private CameraCharacteristics cameraCharacteristics = null;

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
        focusSeekBar = findViewById(R.id.focusSeekBar);
        brightnessSeekBar = findViewById(R.id.brightnessSeekBar);
        focusValueText = findViewById(R.id.focusValueText);
        brightnessValueText = findViewById(R.id.brightnessValueText);
        textureView = findViewById(R.id.textureView);
        textureView.setSurfaceTextureListener(this);

        btnStartStop.setOnClickListener(v -> {
            if (isRecording) {
                stopRawFrameCapture();
            } else {
                startRawFrameCapture();
            }
        });

        // Focus slider listener
        focusSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && cameraCharacteristics != null) {
                    float minFocus = cameraCharacteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
                    currentFocusDistance = minFocus * (progress / 100.0f);
                    focusValueText.setText(String.format("%.2fm", currentFocusDistance));
                    applyFocusControl();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // Brightness slider listener
        brightnessSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    currentBrightness = progress;
                    brightnessValueText.setText(progress + "%");
                    applyBrightnessControl();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    /**
     * Apply focus control to camera
     */
    private void applyFocusControl() {
        if (captureSession == null && previewSession == null) return;

        try {
            CameraCaptureSession session = isRecording ? captureSession : previewSession;
            if (session == null) return;

            CaptureRequest.Builder requestBuilder = cameraDevice.createCaptureRequest(
                    isRecording ? CameraDevice.TEMPLATE_RECORD : CameraDevice.TEMPLATE_PREVIEW);
            requestBuilder.addTarget(isRecording ? imageReader.getSurface() : previewReader.getSurface());

            // Set focus mode to manual
            requestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF);
            requestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, currentFocusDistance);

            if (isRecording) {
                requestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, 
                        new Range<>(TARGET_FPS, TARGET_FPS));
            }

            CaptureRequest request = requestBuilder.build();
            session.setRepeatingRequest(request, null, cameraHandler);

        } catch (CameraAccessException e) {
            Log.e(TAG, "Error applying focus: " + e.getMessage());
        }
    }

    /**
     * Apply brightness control to camera
     */
    private void applyBrightnessControl() {
        if (captureSession == null && previewSession == null) return;

        try {
            CameraCaptureSession session = isRecording ? captureSession : previewSession;
            if (session == null) return;

            CaptureRequest.Builder requestBuilder = cameraDevice.createCaptureRequest(
                    isRecording ? CameraDevice.TEMPLATE_RECORD : CameraDevice.TEMPLATE_PREVIEW);
            requestBuilder.addTarget(isRecording ? imageReader.getSurface() : previewReader.getSurface());

            // Map brightness percentage to exposure time
            long exposureTime = mapBrightnessToExposure(currentBrightness);
            requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF);
            requestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTime);

            // Set ISO sensitivity
            int sensitivity = mapBrightnessToISO(currentBrightness);
            requestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, sensitivity);

            if (isRecording) {
                requestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, 
                        new Range<>(TARGET_FPS, TARGET_FPS));
            }

            CaptureRequest request = requestBuilder.build();
            session.setRepeatingRequest(request, null, cameraHandler);

        } catch (CameraAccessException e) {
            Log.e(TAG, "Error applying brightness: " + e.getMessage());
        }
    }

    /**
     * Map brightness percentage to exposure time
     */
    private long mapBrightnessToExposure(int brightness) {
        // Range from 100μs to 16ms based on brightness
        long minExposure = 100_000L;  // 100 microseconds
        long maxExposure = 16_000_000L;  // 16 milliseconds
        return minExposure + (maxExposure - minExposure) * brightness / 100;
    }

    /**
     * Map brightness percentage to ISO sensitivity
     */
    private int mapBrightnessToISO(int brightness) {
        // Range from 50 to 3200 ISO
        int minISO = 50;
        int maxISO = 3200;
        return minISO + (maxISO - minISO) * brightness / 100;
    }

    /**
     * Start RAW FRAME CAPTURE from camera sensor
     * - No MediaRecorder (no video encoding)
     * - Direct sensor frame access via ImageReader
     * - Native C++ processing for speed
     */
    private void startRawFrameCapture() {
        if (captureSession == null && previewSession == null) {
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

            // Apply current focus and brightness settings
            requestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF);
            requestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, currentFocusDistance);

            long exposureTime = mapBrightnessToExposure(currentBrightness);
            int sensitivity = mapBrightnessToISO(currentBrightness);

            requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF);
            requestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTime);
            requestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, sensitivity);

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

            // Resume preview
            if (previewSession != null) {
                startPreview();
            }

        } catch (CameraAccessException e) {
            Log.e(TAG, "Error stopping capture: " + e.getMessage());
        }
    }

    /**
     * Start camera and setup ImageReader for raw frames
     */
    private void startCamera() {
        try {
            currentCameraId = selectCamera();
            if (currentCameraId == null) {
                updateUI("ERROR: No camera found");
                return;
            }

            cameraCharacteristics = cameraManager.getCameraCharacteristics(currentCameraId);
            
            // Check 240 FPS capability
            int[] availableFps = cameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
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

            // Create ImageReader for preview
            previewReader = ImageReader.newInstance(
                    PREVIEW_WIDTH,
                    PREVIEW_HEIGHT,
                    android.graphics.ImageFormat.YUV_420_888,
                    2
            );
            previewReader.setOnImageAvailableListener(this::onPreviewFrameAvailable, cameraHandler);

            // Open camera
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) 
                    != PackageManager.PERMISSION_GRANTED) {
                return;
            }

            cameraManager.openCamera(currentCameraId, new CameraDevice.StateCallback() {
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
                    Arrays.asList(imageReader.getSurface(), previewReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            MainActivity.this.captureSession = session;
                            startPreview();
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
     * Start preview on TextureView
     */
    private void startPreview() {
        try {
            CaptureRequest.Builder previewBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewBuilder.addTarget(previewReader.getSurface());

            // Apply current settings
            previewBuilder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF);
            previewBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, currentFocusDistance);

            long exposureTime = mapBrightnessToExposure(currentBrightness);
            int sensitivity = mapBrightnessToISO(currentBrightness);

            previewBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF);
            previewBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTime);
            previewBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, sensitivity);

            CaptureRequest previewRequest = previewBuilder.build();
            captureSession.setRepeatingRequest(previewRequest, null, cameraHandler);
            updateUI("Capture session ready.\nPress START to begin raw frame capture.");

        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to start preview: " + e.getMessage());
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
     * Callback when preview frame is available
     */
    private void onPreviewFrameAvailable(ImageReader reader) {
        Image image = reader.acquireLatestImage();
        if (image != null) {
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
    public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
        Log.d(TAG, "SurfaceTexture available");
    }

    @Override
    public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {}

    @Override
    public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
        return false;
    }

    @Override
    public void onSurfaceTextureFrameAvailable(@NonNull SurfaceTexture surface) {}

    @Override
    protected void onDestroy() {
        stopRawFrameCapture();
        if (cameraDevice != null) {
            cameraDevice.close();
        }
        if (imageReader != null) {
            imageReader.close();
        }
        if (previewReader != null) {
            previewReader.close();
        }
        if (cameraThread != null) {
            cameraThread.quitSafely();
        }
        super.onDestroy();
    }
}

