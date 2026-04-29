package com.example.highfps;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.view.Surface;
import android.view.TextureView;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "HighFPSRecorder";
    private static final int REQUEST_CODE_PERMISSIONS = 200;
    private static final int DESIRED_FPS = 240;
    private static final int CAPTURE_WIDTH = 1920;
    private static final int CAPTURE_HEIGHT = 1080;
    private static final int IMAGE_READER_BUFFER = 50;
    private static final int BUFFER_THRESHOLD = 40;

    private Button btnRecord;
    private Button btnStop;
    private TextureView textureView;
    private TextView tvFrameCount;

    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private FrameSaver frameSaver;

    private HandlerThread cameraThread;
    private HandlerThread frameThread;
    private Handler cameraHandler;
    private Handler frameHandler;
    private Handler uiHandler;

    private String activeCameraId;
    private Range<Integer> activeFpsRange;
    private volatile boolean isRecording;
    private boolean pendingStartRecording;
    private volatile int pendingFrameCount = 0;
    private volatile long droppedFrameCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnRecord = findViewById(R.id.btnRecord);
        btnStop = findViewById(R.id.btnStop);
        textureView = findViewById(R.id.textureView);
        tvFrameCount = findViewById(R.id.tvFrameCount);

        btnRecord.setOnClickListener(v -> startRecording());
        btnStop.setOnClickListener(v -> stopRecording());
        btnStop.setEnabled(false);

        uiHandler = new Handler(getMainLooper());

        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                startCameraPreviewIfReady();
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                closeCameraObjects();
                stopCameraThread();
                stopFrameThread();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
            }
        });

        if (textureView.isAvailable()) {
            startCameraPreviewIfReady();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (textureView.isAvailable()) {
            startCameraPreviewIfReady();
        }
    }

    private void startRecording() {
        if (isRecording) {
            return;
        }

        if (!hasRequiredPermissions()) {
            pendingStartRecording = true;
            ActivityCompat.requestPermissions(this, getRequiredPermissions(), REQUEST_CODE_PERMISSIONS);
            return;
        }

        if (cameraDevice == null) {
            pendingStartRecording = true;
            startCameraPreviewIfReady();
            return;
        }

        configureRecordingSession();
    }

    private void stopRecording() {
        if (!isRecording) {
            return;
        }

        isRecording = false;
        pendingStartRecording = false;
        stopFrameCountUpdates();
        setRecordingUiState(false);

        stopAndCloseCaptureSession();
        closeImageReader();

        int total = frameSaver != null ? frameSaver.getFrameCount() : 0;
        String message = "Recording stopped. Total frames dumped: " + total;
        if (droppedFrameCount > 0) {
            message += " (Dropped: " + droppedFrameCount + ")";
        }
        Log.i(TAG, message);
        showToastOnUiThread(message);
        if (frameSaver != null) {
            showToastOnUiThread("Saved to: " + frameSaver.getSessionPath());
        }
        frameSaver = null;

        if (cameraDevice != null) {
            startPreviewSession();
        }
    }

    private void startCameraPreviewIfReady() {
        if (!textureView.isAvailable()) {
            return;
        }

        if (!hasRequiredPermissions()) {
            ActivityCompat.requestPermissions(this, getRequiredPermissions(), REQUEST_CODE_PERMISSIONS);
            return;
        }

        if (cameraDevice != null) {
            if (pendingStartRecording) {
                configureRecordingSession();
            } else if (captureSession == null || !isRecording) {
                startPreviewSession();
            }
            return;
        }

        startCameraThread();
        startFrameThread();

        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            CameraSelection selection = selectCamera(manager);
            activeCameraId = selection.cameraId;
            activeFpsRange = selection.fpsRange;

            Log.i(TAG, "Selected camera=" + activeCameraId + ", fpsRange=" + activeFpsRange);

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            manager.openCamera(activeCameraId, cameraStateCallback, cameraHandler);
        } catch (Exception e) {
            Log.e(TAG, "Unable to open camera preview", e);
            Toast.makeText(this, "Camera open failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            closeCameraObjects();
            stopCameraThread();
            stopFrameThread();
        }
    }

    private CameraSelection selectCamera(CameraManager manager) throws CameraAccessException {
        String[] cameraIds = manager.getCameraIdList();
        if (cameraIds.length == 0) {
            throw new CameraAccessException(CameraAccessException.CAMERA_ERROR, "No camera found");
        }

        String selectedId = cameraIds[0];
        for (String cameraId : cameraIds) {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            Integer lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (lensFacing != null && lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                selectedId = cameraId;
                break;
            }
        }

        CameraCharacteristics characteristics = manager.getCameraCharacteristics(selectedId);
        Range<Integer>[] fpsRanges =
                characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);

        List<int[]> ranges = new ArrayList<>();
        if (fpsRanges != null) {
            for (Range<Integer> range : fpsRanges) {
                Log.d(TAG, "Supported FPS range: " + range);
                ranges.add(new int[]{range.getLower(), range.getUpper()});
            }
        }

        int[] picked = FpsSelector.pickBestRange(ranges, DESIRED_FPS);
        Range<Integer> selectedRange = new Range<>(picked[0], picked[1]);
        return new CameraSelection(selectedId, selectedRange);
    }

    private void startPreviewSession() {
        try {
            if (cameraDevice == null) {
                return;
            }

            Surface previewSurface = getPreviewSurface();
            if (previewSurface == null) {
                return;
            }

            closeImageReader();

            CaptureRequest.Builder builder =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(previewSurface);

            cameraDevice.createCaptureSession(
                    Arrays.asList(previewSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            captureSession = session;
                            try {
                                session.setRepeatingRequest(builder.build(), null, cameraHandler);
                                if (pendingStartRecording) {
                                    configureRecordingSession();
                                }
                            } catch (CameraAccessException e) {
                                Log.e(TAG, "Unable to start preview", e);
                                showToastOnUiThread("Preview start failed");
                                closeCameraObjects();
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            showToastOnUiThread("Preview session config failed");
                            closeCameraObjects();
                        }
                    },
                    cameraHandler
            );
        } catch (Exception e) {
            Log.e(TAG, "Unable to configure preview session", e);
            Toast.makeText(this, "Preview setup failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            closeCameraObjects();
        }
    }

    private void configureRecordingSession() {
        try {
            if (cameraDevice == null || frameHandler == null) {
                pendingStartRecording = true;
                return;
            }

            stopAndCloseCaptureSession();
            closeImageReader();
            imageReader = ImageReader.newInstance(
                    CAPTURE_WIDTH,
                    CAPTURE_HEIGHT,
                    ImageFormat.YUV_420_888,
                    IMAGE_READER_BUFFER
            );

            imageReader.setOnImageAvailableListener(reader -> {
                Image image;
                try {
                    image = reader.acquireNextImage();
                } catch (IllegalStateException e) {
                    Log.w(TAG, "ImageReader buffer overflow, dropping frame. Pending: " + pendingFrameCount);
                    droppedFrameCount++;
                    return;
                }

                if (image == null) {
                    return;
                }

                if (!isRecording || frameSaver == null || frameHandler == null) {
                    image.close();
                    return;
                }

                if (pendingFrameCount >= BUFFER_THRESHOLD) {
                    Log.w(TAG, "Frame queue near capacity (" + pendingFrameCount + "/" + IMAGE_READER_BUFFER + "), dropping frame");
                    image.close();
                    droppedFrameCount++;
                    return;
                }

                pendingFrameCount++;
                frameHandler.post(() -> {
                    try {
                        frameSaver.saveFrame(image);
                    } finally {
                        pendingFrameCount--;
                    }
                });
            }, cameraHandler);

            Surface previewSurface = getPreviewSurface();
            if (previewSurface == null) {
                return;
            }
            Surface readerSurface = imageReader.getSurface();

            CaptureRequest.Builder builder =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            builder.addTarget(previewSurface);
            builder.addTarget(readerSurface);
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, activeFpsRange);

            cameraDevice.createCaptureSession(
                    Arrays.asList(previewSurface, readerSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            captureSession = session;
                            try {
                                frameSaver = new FrameSaver(MainActivity.this);
                                pendingFrameCount = 0;
                                droppedFrameCount = 0;
                                session.setRepeatingRequest(builder.build(), null, cameraHandler);
                                isRecording = true;
                                pendingStartRecording = false;
                                setRecordingUiState(true);
                                showToastOnUiThread("Recording started at " + activeFpsRange);
                                startFrameCountUpdates();
                            } catch (CameraAccessException e) {
                                Log.e(TAG, "Unable to start repeating request", e);
                                showToastOnUiThread("Start capture failed");
                                closeCameraObjects();
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            showToastOnUiThread("Capture session config failed");
                            closeCameraObjects();
                        }
                    },
                    cameraHandler
            );
        } catch (Exception e) {
            Log.e(TAG, "Unable to configure frame capture", e);
            Toast.makeText(this, "Capture setup failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            closeCameraObjects();
        }
    }

    private Surface getPreviewSurface() {
        SurfaceTexture surfaceTexture = textureView.getSurfaceTexture();
        if (surfaceTexture == null) {
            Log.e(TAG, "SurfaceTexture not ready");
            return null;
        }
        surfaceTexture.setDefaultBufferSize(CAPTURE_WIDTH, CAPTURE_HEIGHT);
        return new Surface(surfaceTexture);
    }

    private final CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraDevice = camera;
            if (pendingStartRecording) {
                configureRecordingSession();
            } else {
                startPreviewSession();
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            camera.close();
            cameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            camera.close();
            cameraDevice = null;
            showToastOnUiThread("Camera error: " + error);
        }
    };

    private void setRecordingUiState(boolean recording) {
        runOnUiThread(() -> {
            btnRecord.setEnabled(!recording);
            btnStop.setEnabled(recording);
            tvFrameCount.setVisibility(recording ? TextView.VISIBLE : TextView.GONE);
            if (!recording) {
                tvFrameCount.setText("Frames: 0");
            }
        });
    }

    private void showToastOnUiThread(String message) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show());
    }

    private final Runnable frameCountUpdater = new Runnable() {
        @Override
        public void run() {
            if (isRecording && frameSaver != null) {
                int count = frameSaver.getFrameCount();
                tvFrameCount.setText("Frames: " + count);
                uiHandler.postDelayed(this, 100);
            }
        }
    };

    private void startFrameCountUpdates() {
        uiHandler.post(frameCountUpdater);
    }

    private void stopFrameCountUpdates() {
        uiHandler.removeCallbacks(frameCountUpdater);
    }

    private boolean hasRequiredPermissions() {
        for (String permission : getRequiredPermissions()) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private String[] getRequiredPermissions() {
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.CAMERA);
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        return permissions.toArray(new String[0]);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_CODE_PERMISSIONS) {
            return;
        }

        for (int grantResult : grantResults) {
            if (grantResult != PackageManager.PERMISSION_GRANTED) {
                pendingStartRecording = false;
                Toast.makeText(this, "Permissions are required", Toast.LENGTH_LONG).show();
                return;
            }
        }

        if (pendingStartRecording) {
            startRecording();
        } else {
            startCameraPreviewIfReady();
        }
    }

    private void closeCameraObjects() {
        stopAndCloseCaptureSession();
        closeImageReader();
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
    }

    private void stopAndCloseCaptureSession() {
        if (captureSession != null) {
            try {
                captureSession.stopRepeating();
            } catch (Exception e) {
                Log.w(TAG, "Unable to stop repeating request cleanly", e);
            }
            try {
                captureSession.abortCaptures();
            } catch (Exception e) {
                Log.w(TAG, "Unable to abort in-flight captures cleanly", e);
            }
            captureSession.close();
            captureSession = null;
        }
    }

    private void closeImageReader() {
        if (imageReader != null) {
            imageReader.setOnImageAvailableListener(null, null);
            imageReader.close();
            imageReader = null;
        }
    }

    private void startCameraThread() {
        if (cameraThread != null) {
            return;
        }
        cameraThread = new HandlerThread("CameraThread");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    private void stopCameraThread() {
        if (cameraThread == null) {
            return;
        }
        cameraThread.quitSafely();
        try {
            cameraThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        cameraThread = null;
        cameraHandler = null;
    }

    private void startFrameThread() {
        if (frameThread != null) {
            return;
        }
        frameThread = new HandlerThread("FrameThread");
        frameThread.start();
        frameHandler = new Handler(frameThread.getLooper());
    }

    private void stopFrameThread() {
        if (frameThread == null) {
            return;
        }
        frameThread.quitSafely();
        try {
            frameThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        frameThread = null;
        frameHandler = null;
    }

    @Override
    protected void onPause() {
        super.onPause();
        isRecording = false;
        pendingStartRecording = false;
        stopFrameCountUpdates();
        setRecordingUiState(false);
        frameSaver = null;
        closeCameraObjects();
        stopCameraThread();
        stopFrameThread();
    }

    private static class CameraSelection {
        final String cameraId;
        final Range<Integer> fpsRange;

        CameraSelection(String cameraId, Range<Integer> fpsRange) {
            this.cameraId = cameraId;
            this.fpsRange = fpsRange;
        }
    }
}
