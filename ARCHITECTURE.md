# 🏗️ HighFPSFrameDumper — Architecture & Design

## Overview

This document describes the design and implementation details of HighFPSFrameDumper, a high-speed frame capture system for Android that dumps uncompressed TIFF frames at 240 fps.

---

## Design Goals

1. **Reliability**: Capture all 240 frames per second without drops
2. **Data Fidelity**: Save full RGB pixel data (no compression loss)
3. **Scalability**: Handle sustained 528 MB/s I/O throughput
4. **Simplicity**: Minimal UI (two buttons)
5. **Observability**: Detailed logging for frame tracking

---

## System Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     Android Framework                        │
│                                                               │
│  ┌──────────────────────────────────────────────────────┐   │
│  │                  Camera2 API                         │   │
│  │  (CameraManager, CameraDevice, CameraCaptureSession)│   │
│  └──────────────┬───────────────────────────────────────┘   │
│                 │ YUV420 frames @ 240fps                     │
│  ┌──────────────▼───────────────────────────────────────┐   │
│  │              ImageReader Surface                     │   │
│  │         (Buffers incoming frames)                   │   │
│  └──────────────┬───────────────────────────────────────┘   │
│                 │ onImageAvailable callback                  │
└────────────────┼──────────────────────────────────────────────┘
                 │
                 ▼
        ┌────────────────────┐
        │  FrameProcessor    │
        │ (ImageReader cb)   │
        │  Queue + Thread    │
        │    Pool (4x)       │
        └────────┬───────────┘
                 │
        ┌────────▼──────────┐
        │  BlockingQueue    │
        │  (60 frames max)   │
        │  ~250ms buffer     │
        └────────┬───────────┘
                 │
        ┌────────▼──────────────────────┐
        │  FrameEncoderTask (4 threads)  │
        │  (Parallel YUV→RGB conversion)  │
        └────────┬───────────────────────┘
                 │
        ┌────────▼──────────────────┐
        │  FrameSaver                │
        │  (TIFF encoding + file I/O)│
        └────────┬──────────────────┘
                 │
        ┌────────▼────────────────────────┐
        │  Storage (/Android/data/.../    │
        │   files/frames/)                 │
        │  frame_00001_<ts>.tiff           │
        │  frame_00002_<ts>.tiff           │
        │  ...                             │
        └─────────────────────────────────┘
```

---

## Core Components

### 1. **MainActivity**
**Responsibility**: UI, camera lifecycle, user interaction

```java
public class MainActivity extends AppCompatActivity {
    - onCreate()              : Inflate UI, setup button listeners
    - startCamera()           : Open back camera, query 240fps range
    - startRecording()        : Create capture session, set repeating request
    - stopRecording()         : Stop session, cleanup resources
    - onPermissionResult()    : Handle runtime permissions
    - onDestroy()             : Release camera, cleanup
}
```

**Key Methods**:
- `selectCamera()` → Query available cameras, prefer back
- `setupCaptureSession()` → Create session with ImageReader surface
- `setCaptureRequest()` → Force 240fps via `CONTROL_AE_TARGET_FPS_RANGE`

---

### 2. **FrameProcessor**
**Responsibility**: Async frame queuing and background encoding

```java
public class FrameProcessor implements ImageReader.OnImageAvailableListener {
    - onImageAvailable()      : Queue frame for async processing
    - startEncodingFrames()   : Launch thread pool, start workers
    - stopEncodingFrames()    : Wait for pending frames, shutdown
}
```

**Key Design Patterns**:
- **Producer-Consumer**: Camera thread → queue → encoder threads
- **Backpressure Handling**: Drop frames if queue full (log warning)
- **Thread Pool**: 4 threads for parallel encoding
- **Queue Capacity**: 60 frames = ~250ms buffer

---

### 3. **FrameSaver**
**Responsibility**: YUV→RGB conversion, TIFF encoding, disk I/O

```java
public class FrameSaver {
    - saveFrame(Image)        : Convert YUV, encode TIFF, write file
    - convertYUVToRGB()       : BT.601 color space conversion
    - writeTIFFFile()         : TIFF header + IFD + pixel data
}
```

**TIFF Format** (simplified):
```
Header (8 bytes)
├─ Byte order: "II" (little-endian)
├─ Magic: 0x002A (42 in little-endian)
└─ IFD offset: 0x00000008

IFD (Image File Directory)
├─ Tag count: 10 tags
├─ Tags (each 12 bytes):
│  ├─ 0x0100: ImageWidth (1920)
│  ├─ 0x0101: ImageLength (1080)
│  ├─ 0x0102: BitsPerSample (8, 8, 8 for RGB)
│  ├─ 0x0103: Compression (1 = no compression)
│  ├─ 0x0106: PhotometricInterpretation (2 = RGB)
│  ├─ 0x0111: StripOffsets (pixel data location)
│  ├─ 0x0115: SamplesPerPixel (3 for RGB)
│  ├─ 0x0116: RowsPerStrip (1080)
│  ├─ 0x0117: StripByteCounts (6,220,800 bytes)
│  └─ 0x011A: XResolution
└─ Next IFD: 0x00000000 (none)

Pixel Data (~6.2 MB per frame)
└─ RGB24 format: 1920 × 1080 × 3 bytes
```

---

### 4. **StorageManager**
**Responsibility**: Folder creation, space estimation, path management

```java
public class StorageManager {
    - initFramesDirectory()   : Create /frames/ folder
    - getAvailableStorageGB() : Check free space
    - estimateRecordingTime() : Calculate max record duration
    - getFrameCount()         : Count existing TIFF files
    - clearFrames()           : Delete all frames (with caution)
}
```

**Storage Path**:
```
/storage/emulated/0/Android/data/com.example.highfps/files/frames/
```

No runtime permissions needed (app-specific scoped storage).

---

## Threading Model

### **Thread Safety**

```
Main Thread (UI)
├─ Button clicks
├─ Start/Stop commands
└─ Status updates

Camera Thread (framework-managed)
├─ Frame acquisition
├─ onImageAvailable() callback
└─ Queues frame to BlockingQueue

Encoder Threads (4x, ThreadPoolExecutor)
├─ Dequeue frames
├─ YUV→RGB conversion
├─ TIFF encoding
└─ File I/O (synchronized by FrameSaver)

File I/O Thread (implicit in FileOutputStream)
└─ Disk writes (buffered)
```

**Synchronization**:
- `BlockingQueue<Image>` → thread-safe frame queueing
- `synchronized saveFrame()` → serializes disk writes
- `volatile` flags → memory visibility for recording state

### **Frame Flow Timeline**

```
t=0ms      Camera sensor captures frame #1 (YUV420)
t=0-1ms    onImageAvailable() → queue frame
           (if queue full, drop and log)

t=1-3ms    Encoder thread dequeues frame
           YUV→RGB conversion (BT.601)
           ~2ms per frame on S25+ CPU

t=3-4ms    FrameSaver.writeTIFFFile()
           ~1ms per frame (I/O latency)

t=4-5ms    Frame flushed to disk
           
t=4.17ms   Next frame arrives (240fps = 4.17ms gap)
           Process repeats...
```

### **Backpressure Handling**

If encoding can't keep up:
1. Frame arrives
2. Queue full (60 frames = 250ms)
3. Log warning: "Frame queue full, dropping frame"
4. Close image resource
5. Increment `droppedFrameCount`
6. Continue

**Result**: Sustained 240fps even under load (with occasional drops logged).

---

## Performance Analysis

### **Throughput Calculation**

```
Frames per second:  240
Bytes per frame:    1920 × 1080 × 3 = 6,220,800 bytes (~5.9 MB)
Throughput:         240 × 5.9 MB/s ≈ 1,418 MB/s

But with overhead:
- YUV→RGB conversion: ~2ms
- TIFF encoding: ~1ms
- Disk I/O: ~1ms
- Total per frame: ~4ms

Expected sustainable: 240fps × (4ms/4.17ms) ≈ 230fps

Reserve: ~4% margin for OS scheduling
```

### **Memory Usage**

```
Per frame:
├─ Camera buffer (YUV): 6.2 MB
├─ RGB buffer: 6.2 MB
├─ Queue (60 frames): ~372 MB
└─ Thread stack (4x): ~8 MB
   Total: ~392 MB + OS overhead ≈ 512 MB

S25+ available RAM: 12 GB → **Safe**
```

### **Storage Requirements**

```
1 second @ 240fps:
  240 frames × 5.9 MB/frame ≈ 1,418 MB (~1.4 GB)

10 seconds: ~14 GB
1 minute: ~85 GB  (requires 256GB+ device)

S25+ 512GB: ~6 minutes max sustained recording
```

---

## Error Handling & Logging

### **Logging Strategy**

| Level | Event | Example |
|-------|-------|---------|
| **DEBUG** | Frame saves | "Saved frame 1234: frame_01234_1678008300450.tiff" |
| **INFO** | Session events | "Recording started / stopped" |
| **WARNING** | Dropped frames | "Frame queue full, dropping frame" |
| **ERROR** | Failures | "Failed to write TIFF" |

### **Frame Loss Detection**

```java
// Check for gaps in frame numbers
for (i = 1; i < frameCount; i++) {
    if (frameNum[i] - frameNum[i-1] != 1) {
        Log.w("FRAME_GAP", "Frame " + i + " missing");
    }
}

// Check for timestamp regularity
for (i = 1; i < frameCount; i++) {
    gap = timestamp[i] - timestamp[i-1];
    if (gap > 5ms) {  // Expected: 4.17ms
        Log.w("TIMESTAMP_GAP", "Large gap: " + gap + "ms");
    }
}
```

---

## Future Optimizations

### **1. GPU-Accelerated YUV→RGB**
```glsl
// GLSL compute shader for parallel conversion
// Expected: 10x faster than CPU YUV→RGB
```

### **2. Selective Compression**
```
Option A: TIFF with LZ77 compression
  Compression ratio: 3:1 → 2 MB/frame
  Encode time: +3ms → total 7ms/frame (still safe)

Option B: RAW Bayer (no conversion needed)
  Size: ~2 MB/frame (1920×1080 × 1 byte)
  Encode time: <1ms
```

### **3. Network Streaming**
```
Real-time HTTP multipart upload to cloud:
  Framerate → network bitrate (5 Mbps upload = ~6fps max)
  OR batch upload after recording stops
```

### **4. Hardware Encoder (MediaCodec)**
```java
MediaCodec encoder = MediaCodec.createEncoderByType("video/avc");
// H.264 @ 240fps (if device supports)
// Throughput: 10-50 MB/s with entropy coding
```

---

## Testing & Validation

### **Unit Tests**
```java
FrameSaverTest.java
├─ testYUVToRGBConversion()     : Verify color space
├─ testTIFFHeaderFormat()        : TIFF structure
└─ testFilenameFormat()          : Sequential naming

StorageManagerTest.java
├─ testDirectoryCreation()       : Folder setup
└─ testSpaceEstimation()         : Capacity calc
```

### **Integration Tests**
```java
FrameCaptureTest.java (on device)
├─ testCameraOpens()             : Camera2 API
├─ test10FrameCapture()          : E2E pipeline
├─ testFrameSequence()           : No gaps
└─ testStorageCleanup()          : Teardown
```

### **Manual Verification**
```bash
# Post-recording checklist
1. Frame count: ls -1 frames/*.tiff | wc -l
2. File sizes: ls -lah frames/ | awk '{print $5}' | sort | uniq -c
3. Timestamp gaps: python3 verify_frames.py
4. TIFF validity: file frames/frame_*.tiff
5. Pixel data: ffprobe -v error frames/frame_00001.tiff
```

---

## Dependencies & External Libraries

### **Current**
- **Android Framework**: Camera2 API (built-in)
- **Standard Libraries**: Java NIO, concurrent, logging

### **Optional (Future)**
- **LibTIFF Binding**: For advanced TIFF codec
- **OpenGL ES**: GPU YUV→RGB conversion
- **FFmpeg JNI**: Multi-codec support

---

## Security Considerations

1. **Storage Permissions**: App-specific folder (no privacy risks)
2. **Camera Access**: Declared in manifest, user-approved
3. **File Integrity**: TIFF format verified on read
4. **Memory Safety**: No native code (pure Java/Android APIs)

---

## References

- Android Camera2: https://developer.android.com/reference/android/hardware/camera2
- ImageReader: https://developer.android.com/reference/android/media/ImageReader
- TIFF Spec: http://partners.adobe.com/public/developer/en/tiff/TIFF6.pdf
- YUV Color Space: https://en.wikipedia.org/wiki/YCbCr#JPEG_conversion
- Scoped Storage: https://developer.android.com/about/versions/11/privacy/storage

---

**Last Updated**: March 5, 2026  
**Author**: Prem Sai Teja  
**Status**: ✅ Finalized

