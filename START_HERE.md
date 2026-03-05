# 🎬 START HERE – How to Run HighFPSRecorder
## **Project Ready! ✅**
Your complete HighFPSRecorder Android project is ready to run.
📍 **Location**: `/home/fmea01/Downloads/PREMSAITEJA/HighFPSRecorder`
---
## **PICK ONE METHOD** (All 3 work!)
---
### **Method 1️⃣: Android Studio (Recommended)**
**Best for**: First-time users, visual learners
1. **Connect S25+**
   - Settings → Developer Options → USB Debugging ON
   - Connect via USB cable
2. **Open Project**
   - Open Android Studio
   - File → Open → `/home/fmea01/Downloads/PREMSAITEJA/HighFPSRecorder`
3. **Wait for Gradle**
   - Green checkmark appears (2-3 minutes)
4. **Click Run**
   - Green Run button (or Shift+F10)
   - Select S25+ if prompted
5. **Grant Permissions**
   - Tap "Allow" for Camera
   - Tap "Allow" for Microphone
6. **Done!** 🎉
App will show live camera preview with 2 buttons.
---
### **Method 2️⃣: Command Line (Fastest)**
**Best for**: Developers, automation
```bash
cd /home/fmea01/Downloads/PREMSAITEJA/HighFPSRecorder
./gradlew installDebug
adb shell am start -n com.example.highfps/.MainActivity
```
**View logs**:
```bash
adb logcat | grep HighFPS
```
---
### **Method 3️⃣: Build APK**
**Best for**: Testing, distribution
```bash
cd /home/fmea01/Downloads/PREMSAITEJA/HighFPSRecorder
./gradlew assembleDebug
```
APK location: `app/build/outputs/apk/debug/app-debug.apk`
Install & run:
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.example.highfps/.MainActivity
```
---
## **USING THE APP**
### **Screen Layout**
```
┌─────────────────────────┐
│  CAMERA PREVIEW         │
│  (live feed)            │
├─────────────────────────┤
│ [240FPS]  [CAM INFO]    │
└─────────────────────────┘
```
### **3 Steps to Record**
1. **Verify 240fps**: Tap "Camera Info"
   - Should show: `[240, 240]` FPS range
   - ✅ Means device supports 240fps
2. **Start**: Tap "START 240FPS RECORDING"
   - Toast shows save path
   - Button changes to "STOP RECORDING"
3. **Stop**: Tap "STOP RECORDING"
   - File saved to: `/Android/data/com.example.highfps/files/output_*.mp4`
---
## **VERIFY OUTPUT**
On your computer:
```bash
# Download video
adb pull /storage/emulated/0/Android/data/com.example.highfps/files/ ~/Downloads/
# Check FPS
ffprobe -v error -select_streams v:0 -show_entries stream=r_frame_rate ~/Downloads/output_*.mp4
# Should show: r_frame_rate=240/1 ✅
```
---
## **TROUBLESHOOTING**
| Issue | Fix |
|-------|-----|
| Device not found | `adb devices` → Enable USB Debug → Reconnect |
| Gradle fails | Check internet → File → Sync Now |
| Permission denied | Settings → Apps → HighFPSRecorder → Grant perms |
| Low FPS output | Tap "Camera Info" to check device max |
---
## **QUICK COMMANDS**
```bash
adb devices                    # List devices
./gradlew clean               # Clean build
./gradlew assembleDebug       # Build APK
./gradlew installDebug        # Install
adb logcat | grep HighFPS     # View logs
adb uninstall com.example.highfps  # Remove
```
---
## **BEFORE YOU START**
Make sure you have:
- ☑️ S25+ connected via USB
- ☑️ USB Debugging enabled
- ☑️ Android Studio installed (for Method 1)
---
## **THAT'S IT!**
Pick a method above and start recording.
**Questions?** Check `README.md` or `RUN_GUIDE.md` in the project folder.
---
**Status**: ✅ **READY TO RUN!** 🚀
