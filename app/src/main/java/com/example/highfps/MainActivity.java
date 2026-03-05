package com.example.highfps;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "HighFPSRecorder";
    private static final int REQUEST_CODE_PERMISSIONS = 200;
    private static final int DESIRED_FPS = 240;

    private Button btnRecord;

    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private MediaRecorder mediaRecorder;
    private HandlerThread cameraThread;
    private Handler cameraHandler;

    private String activeCameraId;
    private Range<Integer> activeFpsRange;
    private File outputFile;
    private boolean isRecording;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnRecord = findViewById(R.id.btnRecord);
        btnRecord.setOnClickListener(v -> {
            if (isRecording) {
                stopRecording();
            } else {
                startCamera();
            }
        });

        Button btnInfo = findViewById(R.id.btnInfo);
        btnInfo.setOnClickListener(v -> showCameraInfo());
    }

    private void showCameraInfo() {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            String[] cameraIds = manager.getCameraIdList();
            if (cameraIds.length == 0) {
                Toast.makeText(this, "No cameras available", Toast.LENGTH_SHORT).show();
                return;
            }
            String cameraId = cameraIds[0];
            String info = CameraInfoFormatter.formatCameraInfo(manager, cameraId);
            Toast.makeText(this, info, Toast.LENGTH_LONG).show();
            Log.d(TAG, "Camera Info:\n" + info);
        } catch (Exception e) {
            Log.e(TAG, "Unable to get camera info", e);
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void startCamera() {
        if (!hasRequiredPermissions()) {
            ActivityCompat.requestPermissions(this, getRequiredPermissions(), REQUEST_CODE_PERMISSIONS);
            return;
        }

        startCameraThread();

        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            CameraSelection selection = selectCamera(manager);
            activeCameraId = selection.cameraId;
            activeFpsRange = selection.fpsRange;

            Log.d(TAG, "Using camera=" + activeCameraId + " with FPS range=" + activeFpsRange);

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            manager.openCamera(activeCameraId, stateCallback, cameraHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Unable to open camera", e);
            Toast.makeText(this, "Camera open failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            stopCameraThread();
        }
    }

    private CameraSelection selectCamera(CameraManager manager) throws CameraAccessException {
        String[] cameraIds = manager.getCameraIdList();
        String fallbackCameraId = cameraIds[0];

        for (String cameraId : cameraIds) {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            Integer lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (lensFacing != null && lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                fallbackCameraId = cameraId;
                break;
            }
        }

        CameraCharacteristics characteristics = manager.getCameraCharacteristics(fallbackCameraId);
        Range<Integer>[] fpsRanges =
                characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);

        List<int[]> ranges = new ArrayList<>();
        if (fpsRanges != null) {
            for (Range<Integer> range : fpsRanges) {
                ranges.add(new int[] {range.getLower(), range.getUpper()});
                Log.d(TAG, "Supported FPS range: " + range);
            }
        }

        int[] picked = FpsSelector.pickBestRange(ranges, DESIRED_FPS);
        Range<Integer> selectedRange = new Range<>(picked[0], picked[1]);
        return new CameraSelection(fallbackCameraId, selectedRange);
    }

    private void startRecording() {
        if (cameraDevice == null) {
            Toast.makeText(this, "Camera is not ready", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            setupMediaRecorder();
            Surface recorderSurface = mediaRecorder.getSurface();

            // Try high-speed session first
            if (supportsHighSpeedRecording()) {
                startHighSpeedRecording(recorderSurface);
            } else {
                startStandardRecording(recorderSurface);
            }
        } catch (Exception e) {
            Log.e(TAG, "Unable to start recording", e);
            Toast.makeText(this, "Start recording failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            safeReleaseRecorder();
        }
    }

    private boolean supportsHighSpeedRecording() {
        try {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(activeCameraId);
            int[] capabilities =
                    characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
            if (capabilities != null) {
                for (int cap : capabilities) {
                    if (cap == CameraCharacteristics
                            .REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO) {
                        Log.d(TAG, "Device supports high-speed video");
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to check high-speed capability", e);
        }
        return false;
    }

    private void startHighSpeedRecording(Surface recorderSurface) throws CameraAccessException {
        Log.d(TAG, "Starting high-speed recording session...");
        CaptureRequest.Builder builder =
                cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
        builder.addTarget(recorderSurface);
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, activeFpsRange);

        cameraDevice.createCaptureSession(
                Arrays.asList(recorderSurface),
                new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(@NonNull CameraCaptureSession session) {
                        captureSession = session;
                        try {
                            captureSession.setRepeatingRequest(builder.build(), null, cameraHandler);
                            mediaRecorder.start();
                            isRecording = true;
                            runOnUiThread(() -> {
                                btnRecord.setText(getString(R.string.stop_recording));
                                Toast.makeText(
                                        MainActivity.this,
                                        "Recording (High-Speed): " + outputFile.getAbsolutePath(),
                                        Toast.LENGTH_SHORT
                                ).show();
                            });
                        } catch (CameraAccessException e) {
                            Log.e(TAG, "Capture session failed", e);
                            safeReleaseRecorder();
                        }
                    }

                    @Override
                    public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                        Log.w(TAG, "High-speed session config failed, falling back to standard");
                        try {
                            startStandardRecording(recorderSurface);
                        } catch (Exception ex) {
                            Toast.makeText(MainActivity.this, "Session config failed", Toast.LENGTH_SHORT)
                                    .show();
                            safeReleaseRecorder();
                        }
                    }
                },
                cameraHandler
        );
    }

    private void startStandardRecording(Surface recorderSurface) throws CameraAccessException {
        Log.d(TAG, "Starting standard recording session...");
        CaptureRequest.Builder builder =
                cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
        builder.addTarget(recorderSurface);
        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, activeFpsRange);

        cameraDevice.createCaptureSession(
                Arrays.asList(recorderSurface),
                new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(@NonNull CameraCaptureSession session) {
                        captureSession = session;
                        try {
                            captureSession.setRepeatingRequest(builder.build(), null, cameraHandler);
                            mediaRecorder.start();
                            isRecording = true;
                            runOnUiThread(() -> {
                                btnRecord.setText(getString(R.string.stop_recording));
                                Toast.makeText(
                                        MainActivity.this,
                                        "Recording: " + outputFile.getAbsolutePath(),
                                        Toast.LENGTH_SHORT
                                ).show();
                            });
                        } catch (CameraAccessException e) {
                            Log.e(TAG, "Capture session failed", e);
                            safeReleaseRecorder();
                        }
                    }

                    @Override
                    public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                        Toast.makeText(MainActivity.this, "Session config failed", Toast.LENGTH_SHORT)
                                .show();
                        safeReleaseRecorder();
                    }
                },
                cameraHandler
        );
    }

    private void setupMediaRecorder() throws IOException {
        safeReleaseRecorder();

        mediaRecorder = new MediaRecorder();
        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        outputFile = new File(getExternalFilesDir(null), "output_" + timestamp + "_240fps.mp4");

        mediaRecorder.setOutputFile(outputFile.getAbsolutePath());
        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        mediaRecorder.setVideoSize(1920, 1080);
        mediaRecorder.setVideoFrameRate(DESIRED_FPS);
        mediaRecorder.setVideoEncodingBitRate(20_000_000);
        mediaRecorder.prepare();
    }

    private void stopRecording() {
        if (!isRecording) {
            return;
        }

        try {
            if (captureSession != null) {
                captureSession.stopRepeating();
                captureSession.abortCaptures();
            }
        } catch (CameraAccessException e) {
            Log.w(TAG, "Unable to stop repeating request", e);
        }

        try {
            mediaRecorder.stop();
        } catch (RuntimeException e) {
            Log.e(TAG, "Recorder stopped before receiving enough data", e);
        }

        isRecording = false;
        btnRecord.setText(getString(R.string.start_recording));
        Toast.makeText(this, "Saved to: " + outputFile.getAbsolutePath(), Toast.LENGTH_LONG).show();

        closeCameraObjects();
        safeReleaseRecorder();
        stopCameraThread();
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
        permissions.add(Manifest.permission.RECORD_AUDIO);
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
                Toast.makeText(this, "Permissions are required", Toast.LENGTH_LONG).show();
                return;
            }
        }
        startCamera();
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraDevice = camera;
            startRecording();
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
            Toast.makeText(MainActivity.this, "Camera error: " + error, Toast.LENGTH_SHORT).show();
        }
    };

    private void closeCameraObjects() {
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
    }

    private void safeReleaseRecorder() {
        if (mediaRecorder == null) {
            return;
        }
        try {
            mediaRecorder.reset();
        } catch (Exception ignored) {
            // No-op: reset can throw if recorder was never fully initialized.
        }
        mediaRecorder.release();
        mediaRecorder = null;
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

    @Override
    protected void onPause() {
        if (isRecording) {
            stopRecording();
        } else {
            closeCameraObjects();
            safeReleaseRecorder();
            stopCameraThread();
        }
        super.onPause();
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

