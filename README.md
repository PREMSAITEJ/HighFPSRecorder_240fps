# HighFPSRecorder

Android app (Java + Camera2 + MediaRecorder) to record high-FPS video, targeting `240fps` when the device camera supports it.

## Project Info

- App ID: `com.example.highfps`
- Min SDK: `29` (Android 10)
- Target SDK: `34`
- Build: Android Gradle Plugin `8.5.2`, Gradle `8.7`

## Features

- **Runtime Permissions**: Automatic camera and microphone permission handling.
- **Camera Selection**: Back camera preferred; fallback to front if unavailable.
- **FPS Range Discovery**: Queries device camera capabilities for supported frame rates.
- **Adaptive High-FPS Recording**:
  - Attempts exact `240,240` FPS when supported.
  - Falls back to highest available FPS range if 240 unavailable.
  - Supports high-speed constrained capture sessions (API 34+) for true high-FPS recording.
- **Live Preview**: TextureView-based camera preview while recording.
- **Camera Info Display**: Button to inspect detected camera capabilities and supported FPS ranges.
- **App-Specific Storage**: Saves output MP4 under `/Android/data/com.example.highfps/files/`.

## Important Device Note

Not every device supports true `240fps` with Camera2 + MediaRecorder at `1920x1080`.
- **S25+** and flagship devices typically support 240fps.
- If unsupported, camera/session configuration falls back to the highest available FPS range.
- The app auto-detects constrained high-speed video capability (API 34+) and uses it if available.

## Open in Android Studio

1. **Open Folder**: File → Open → select this project folder.
2. **Sync Gradle**: Wait for Gradle sync to complete.
3. **Connect Device**: USB debug mode enabled on S25+.
4. **Run App**: Click *Run* or press `Shift+F10`.
5. **Grant Permissions**: On first launch, approve camera/microphone access.
6. **Verify Camera**: Tap "Camera Info" to see detected capabilities (should list FPS ranges ≥240).
7. **Record**: Tap "Start 240fps Recording", then tap again to stop.

### Output Location

```
/storage/emulated/0/Android/data/com.example.highfps/files/output_YYYYMMDD_HHMMSS_240fps.mp4
```

## Verify FPS (optional)

Once transferred to a desktop, inspect the recorded file:

```bash
ffprobe -v error -select_streams v:0 -show_entries stream=r_frame_rate,avg_frame_rate -of default=noprint_wrappers=1:nokey=0 /path/to/output_file.mp4
```

Expected output (if true 240fps):

```
r_frame_rate=240/1
avg_frame_rate=240/1
```

## Quick Local Harness (no Android runtime)

Tests the FPS selection helper (`FpsSelector`) with plain Java (no Android SDK needed):

```bash
cd /home/fmea01/Downloads/PREMSAITEJA/HighFPSRecorder
mkdir -p out
javac -d out app/src/main/java/com/example/highfps/FpsSelector.java app/src/test/java/com/example/highfps/FpsSelectorHarness.java
java -cp out com.example.highfps.FpsSelectorHarness
```

Expected output:

```
Selected FPS range: [240, 240]
```

## Gradle Commands

```bash
cd /home/fmea01/Downloads/PREMSAITEJA/HighFPSRecorder

# List available tasks
./gradlew tasks

# Run unit tests
./gradlew test

# Build debug APK
./gradlew assembleDebug

# Build and install on device
./gradlew installDebug

# Clean build
./gradlew clean
```

## Main Source Files

### Activity & Core Logic
- `app/src/main/java/com/example/highfps/MainActivity.java` — Main app entry; camera setup, recording control, permissions.
- `app/src/main/java/com/example/highfps/FpsSelector.java` — FPS range selection utility (query-side logic).

### UI & Preview
- `app/src/main/java/com/example/highfps/CameraPreviewView.java` — TextureView-based camera preview.
- `app/src/main/java/com/example/highfps/PreviewCallback.java` — Interface for preview readiness signals.

### Utilities
- `app/src/main/java/com/example/highfps/CameraInfoFormatter.java` — Formats camera capabilities for display.

### Resources & Layout
- `app/src/main/res/layout/activity_main.xml` — Two-button UI: Start/Stop recording, Camera Info.
- `app/src/main/res/values/strings.xml` — Localized string labels.
- `app/src/main/AndroidManifest.xml` — App permissions, SDK levels, activity registration.

### Tests
- `app/src/test/java/com/example/highfps/FpsSelectorTest.java` — JUnit unit tests for FPS logic.
- `app/src/test/java/com/example/highfps/FpsSelectorHarness.java` — Standalone Java harness (no Android deps).

## Architecture

```
HighFPSRecorder/
├── gradle/
│   └── wrapper/
│       ├── gradle-wrapper.jar
│       └── gradle-wrapper.properties
├── app/
│   ├── src/main/
│   │   ├── java/com/example/highfps/
│   │   │   ├── MainActivity.java
│   │   │   ├── FpsSelector.java
│   │   │   ├── CameraPreviewView.java
│   │   │   ├── PreviewCallback.java
│   │   │   └── CameraInfoFormatter.java
│   │   ├── res/
│   │   │   ├── layout/activity_main.xml
│   │   │   ├── values/
│   │   │   │   ├── strings.xml
│   │   │   │   ├── colors.xml
│   │   │   │   └── themes.xml
│   │   │   └── xml/
│   │   │       ├── backup_rules.xml
│   │   │       └── data_extraction_rules.xml
│   │   └── AndroidManifest.xml
│   ├── src/test/java/com/example/highfps/
│   │   ├── FpsSelectorTest.java
│   │   └── FpsSelectorHarness.java
│   ├── build.gradle
│   └── proguard-rules.pro
├── settings.gradle
├── build.gradle
├── gradle.properties
├── gradlew
├── gradlew.bat
└── README.md
```

## Next Steps

1. **Build & Deploy**: Run `./gradlew assembleDebug` on S25+ or Android Studio to install.
2. **Test Recording**: Grant permissions, tap Camera Info to verify 240fps support, then record a short clip.
3. **Verify Output**: Use `ffprobe` (or Android File Manager) to confirm video properties.
4. **Optimize**: Adjust bitrate (`20_000_000` bps), resolution (`1920x1080`), or codec if needed.


