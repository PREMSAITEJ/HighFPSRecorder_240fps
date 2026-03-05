package com.example.highfps;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.util.Range;

public class CameraInfoFormatter {
    public static String formatCameraInfo(CameraManager manager, String cameraId) throws Exception {
        CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
        StringBuilder info = new StringBuilder();

        info.append("Camera ID: ").append(cameraId).append("\n");

        Integer lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
        if (lensFacing != null) {
            info.append("Facing: ")
                    .append(lensFacing == CameraCharacteristics.LENS_FACING_BACK ? "BACK" : "FRONT")
                    .append("\n");
        }

        int[] capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        if (capabilities != null) {
            info.append("Capabilities: ");
            for (int cap : capabilities) {
                if (cap == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO) {
                    info.append("HIGH_SPEED_VIDEO ");
                } else if (cap == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) {
                    info.append("MANUAL_SENSOR ");
                }
            }
            info.append("\n");
        }

        Range<Integer>[] fpsRanges =
                characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (fpsRanges != null) {
            info.append("FPS Ranges:\n");
            for (Range<Integer> range : fpsRanges) {
                info.append("  [").append(range.getLower()).append(", ").append(range.getUpper())
                        .append("]\n");
                if (range.getUpper() >= 240) {
                    info.append("    ✓ High-FPS capable\n");
                }
            }
        }

        return info.toString();
    }
}

