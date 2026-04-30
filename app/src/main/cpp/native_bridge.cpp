#include "native_bridge.h"
#include "frame_processor.h"
#include <jni.h>
#include <android/log.h>

#define LOG_TAG "HighFPS-NDK-JNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Global frame processor instance
static std::unique_ptr<FrameProcessor> g_frame_processor;

void NativeBridge::initialize_jni(JNIEnv* env) {
    LOGD("Native bridge initialized");
}

std::unique_ptr<FrameProcessor> NativeBridge::create_frame_processor(
    int width, int height, const char* output_dir) {
    return std::make_unique<FrameProcessor>(width, height, output_dir);
}

// JNI exports
extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_highfps_NativeFrameProcessor_nativeCreate(JNIEnv* env,
                                                           jobject obj,
                                                           jint width,
                                                           jint height,
                                                           jstring joutput_dir) {
    try {
        const char* output_dir = env->GetStringUTFChars(joutput_dir, nullptr);
        auto processor = NativeBridge::create_frame_processor(width, height, output_dir);
        env->ReleaseStringUTFChars(joutput_dir, output_dir);

        LOGD("Native FrameProcessor created: 0x%lx", (long)processor.get());
        return reinterpret_cast<jlong>(processor.release());
    } catch (const std::exception& e) {
        LOGE("Failed to create FrameProcessor: %s", e.what());
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_com_example_highfps_NativeFrameProcessor_nativeDestroy(JNIEnv* env,
                                                             jobject obj,
                                                             jlong handle) {
    if (!handle) return;

    try {
        auto processor = reinterpret_cast<FrameProcessor*>(handle);
        processor->stop_capturing();
        delete processor;
        LOGD("Native FrameProcessor destroyed");
    } catch (const std::exception& e) {
        LOGE("Failed to destroy FrameProcessor: %s", e.what());
    }
}

JNIEXPORT void JNICALL
Java_com_example_highfps_NativeFrameProcessor_nativeStartCapture(JNIEnv* env,
                                                                  jobject obj,
                                                                  jlong handle) {
    if (!handle) return;

    try {
        auto processor = reinterpret_cast<FrameProcessor*>(handle);
        processor->start_capturing();
    } catch (const std::exception& e) {
        LOGE("Failed to start capture: %s", e.what());
    }
}

JNIEXPORT void JNICALL
Java_com_example_highfps_NativeFrameProcessor_nativeStopCapture(JNIEnv* env,
                                                                 jobject obj,
                                                                 jlong handle) {
    if (!handle) return;

    try {
        auto processor = reinterpret_cast<FrameProcessor*>(handle);
        processor->stop_capturing();
    } catch (const std::exception& e) {
        LOGE("Failed to stop capture: %s", e.what());
    }
}

JNIEXPORT jlong JNICALL
Java_com_example_highfps_NativeFrameProcessor_nativeGetFrameCount(JNIEnv* env,
                                                                   jobject obj,
                                                                   jlong handle) {
    if (!handle) return 0;
    auto processor = reinterpret_cast<FrameProcessor*>(handle);
    return processor->get_frame_count();
}

JNIEXPORT jlong JNICALL
Java_com_example_highfps_NativeFrameProcessor_nativeGetDroppedFrames(JNIEnv* env,
                                                                      jobject obj,
                                                                      jlong handle) {
    if (!handle) return 0;
    auto processor = reinterpret_cast<FrameProcessor*>(handle);
    return processor->get_dropped_frames();
}

JNIEXPORT jlong JNICALL
Java_com_example_highfps_NativeFrameProcessor_nativeGetErrorCount(JNIEnv* env,
                                                                   jobject obj,
                                                                   jlong handle) {
    if (!handle) return 0;
    auto processor = reinterpret_cast<FrameProcessor*>(handle);
    return processor->get_errors();
}

JNIEXPORT jdouble JNICALL
Java_com_example_highfps_NativeFrameProcessor_nativeGetAvgProcessingTime(JNIEnv* env,
                                                                          jobject obj,
                                                                          jlong handle) {
    if (!handle) return 0.0;
    auto processor = reinterpret_cast<FrameProcessor*>(handle);
    return processor->get_avg_processing_time_ms();
}

JNIEXPORT jboolean JNICALL
Java_com_example_highfps_NativeFrameProcessor_nativeIsCapturing(JNIEnv* env,
                                                                 jobject obj,
                                                                 jlong handle) {
    if (!handle) return JNI_FALSE;
    auto processor = reinterpret_cast<FrameProcessor*>(handle);
    return processor->is_capturing() ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"
