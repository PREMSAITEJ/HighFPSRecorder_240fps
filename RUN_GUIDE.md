# HighFPSRecorder – Complete Run Guide
## 🚀 Three Ways to Run the App
---
## **Option 1: Android Studio (RECOMMENDED)**
### Prerequisites
- ✅ Android Studio installed
- ✅ S25+ connected via USB
- ✅ USB debugging enabled
### Steps
1. **Connect S25+**: Settings → Developer Options → USB Debugging ON
2. **Open Project**: File → Open → `/home/fmea01/Downloads/PREMSAITEJA/HighFPSRecorder`
3. **Sync Gradle**: Wait for build to complete (green checkmark)
4. **Run**: Click green Run button (Shift+F10)
5. **Grant Permissions**: Tap "Allow" for Camera and Microphone
6. **Test**: Tap "Camera Info" to verify 240fps support, then tap "Start 240fps Recording"
---
## **Option 2: Command Line (FAST)**
```bash
# Navigate to project
cd /home/fmea01/Downloads/PREMSAITEJA/HighFPSRecorder
# Build and install
./gradlew installDebug
# Launch app
adb shell am start -n com.example.highfps/.MainActivity
```
**View logs:**
```bash
adb logcat | grep HighFPS
```
---
## **Option 3: Build APK**
```bash
cd /home/fmea01/Downloads/PREMSAITEJA/HighFPSRecorder
./gradlew assembleDebug
```
**APK location:**
```
app/build/outputs/apk/debug/app-debug.apk
```
**Install manually:**
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```
---
## 📱 Using the App
### Initial Screen
```
┌──────────────────────────┐
│   [CAMERA PREVIEW]       │
│                          │
│ [START] [CAMERA INFO]    │
└──────────────────────────┘
```
### Check Device Capabilities
- Tap **"Camera Info"** button
- Should show FPS ranges (look for `[240, 240]`)
- ✅ Means S25+ supports 240fps
### Record Video
1. Tap **"Start 240fps Recording"**
   - Preview continues, recording in background
   - Toast shows save path
2. Tap **"Stop Recording"** to finish
   - File saved to: `/Android/data/com.example.highfps/files/output_*.mp4`
### Verify FPS (on Computer)
```bash
ffprobe -v error -select_streams v:0 \
  -show_entries stream=r_frame_rate \
  output_*.mp4
```
Expected: `avg_frame_rate=240/1`
---
## ✅ Troubleshooting
| Problem | Solution |
|---------|----------|
| Device not found | `adb devices` → Enable USB Debugging |
| Permission denied | Settings → Apps → HighFPSRecorder → Grant Camera/Mic |
| Gradle sync fails | Check internet → File → Sync Now |
| Low FPS in output | Check "Camera Info" for device max → close other camera apps |
| Camera not ready | Grant permissions → restart app |
---
## ⚡ Quick Commands
```bash
# Check connected devices
adb devices
# Build without installing
./gradlew assembleDebug
# Build and install
./gradlew installDebug
# Launch app
adb shell am start -n com.example.highfps/.MainActivity
# Pull video to desktop
adb pull /storage/emulated/0/Android/data/com.example.highfps/files/ ~/Downloads/
# Uninstall app
adb uninstall com.example.highfps
# View real-time logs
adb logcat | grep HighFPS
```
---
## 🎯 First Time Checklist
- [ ] S25+ connected via USB
- [ ] USB debugging enabled
- [ ] Android Studio opened
- [ ] Project folder opened
- [ ] Gradle sync complete
- [ ] App installed
- [ ] Permissions granted
- [ ] "Camera Info" shows FPS ranges
- [ ] Test recording created
- [ ] Video verified with ffprobe
---
**Status**: ✅ Ready to run! Pick Option 1, 2, or 3 above. 🚀
