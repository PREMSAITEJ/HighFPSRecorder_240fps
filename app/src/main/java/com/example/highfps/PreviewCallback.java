package com.example.highfps;

import android.graphics.SurfaceTexture;
import android.view.TextureView;

public interface PreviewCallback {
    void onPreviewReady(SurfaceTexture surfaceTexture);

    void onPreviewUnavailable();
}

