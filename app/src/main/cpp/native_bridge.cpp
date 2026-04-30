#include <jni.h>
#include "frame_processor.h"
#include <android/log.h>
#include <memory>

#define LOG_TAG "HighFPS-JNI-Raw"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

/**
 * Create native raw frame processor
 * JNI: NativeFrameProcessor.nativeCreate()
 */
JNIEXPORT jlong JNICALL
Java_com_example_highfps_NativeFrameProcessor_nativeCreate(JNIEnv* env,
                                                           jobject obj,
                                                           jint width,
                                                           jint height,
                                                           jstring joutput_dir) {
    try {
        const char* output_dir = env->GetStringUTFChars(joutput_dir, nullptr);
        auto processor = std::make_unique<FrameProcessor>(width, height, output_dir);
        env->ReleaseStringUTFChars(joutput_dir, output_dir);

        LOGD("Raw frame processor created: %dx%d", width, height);
        return reinterpret_cast<jlong>(processor.release());
    } catch (const std::exception& e) {
        LOGE("Failed to create processor: %s", e.what());
        return 0;
    }
}

/**
 * Destroy native processor
 */
JNIEXPORT void JNICALL
Java_com_example_highfps_NativeFrameProcessor_nativeDestroy(JNIEnv* env,
                                                             jobject obj,
                                                             jlong handle) {
    if (!handle) return;
    try {
        auto processor = reinterpret_cast<FrameProcessor*>(handle);
        processor->stop_capturing();
        delete processor;
        LOGD("Processor destroyed");
    } catch (const std::exception& e) {
        LOGE("Destroy error: %s", e.what());
    }
}

/**
 * Start raw frame capture
 */
JNIEXPORT void JNICALL
Java_com_example_highfps_NativeFrameProcessor_nativeStartCapture(JNIEnv* env,
                                                                  jobject obj,
                                                                  jlong handle) {
    if (!handle) return;
    try {
        auto processor = reinterpret_cast<FrameProcessor*>(handle);
        processor->start_capturing();
        LOGD("Capture started");
    } catch (const std::exception& e) {
        LOGE("Start error: %s", e.what());
    }
}

/**
 * Stop raw frame capture
 */
JNIEXPORT void JNICALL
Java_com_example_highfps_NativeFrameProcessor_nativeStopCapture(JNIEnv* env,
                                                                 jobject obj,
                                                                 jlong handle) {
    if (!handle) return;
    try {
        auto processor = reinterpret_cast<FrameProcessor*>(handle);
        processor->stop_capturing();
        LOGD("Capture stopped");
    } catch (const std::exception& e) {
        LOGE("Stop error: %s", e.what());
    }
}

/**
 * Check if capturing
 */
JNIEXPORT jboolean JNICALL
Java_com_example_highfps_NativeFrameProcessor_nativeIsCapturing(JNIEnv* env,
                                                                 jobject obj,
                                                                 jlong handle) {
    if (!handle) return JNI_FALSE;
    auto processor = reinterpret_cast<FrameProcessor*>(handle);
    return processor->is_capturing() ? JNI_TRUE : JNI_FALSE;
}

/**
 * Process raw Y-plane frame from camera sensor
 * JNI: NativeFrameProcessor.nativeProcessFrame()
 */
JNIEXPORT void JNICALL
Java_com_example_highfps_NativeFrameProcessor_nativeProcessFrame(JNIEnv* env,
                                                                  jobject obj,
                                                                  jlong handle,
                                                                  jbyteArray jy_data,
                                                                  jint pixel_stride,
                                                                  jint row_pitch) {
    if (!handle || !jy_data) return;

    try {
        jbyte* y_data = env->GetByteArrayElements(jy_data, nullptr);
        int y_size = env->GetArrayLength(jy_data);

        auto processor = reinterpret_cast<FrameProcessor*>(handle);
        processor->process_raw_yplane(
            reinterpret_cast<const uint8_t*>(y_data),
            pixel_stride,
            row_pitch
        );

        env->ReleaseByteArrayElements(jy_data, y_data, JNI_ABORT);
    } catch (const std::exception& e) {
        LOGE("Process frame error: %s", e.what());
    }
}

/**
 * Get total frame count
 */
JNIEXPORT jlong JNICALL
Java_com_example_highfps_NativeFrameProcessor_nativeGetFrameCount(JNIEnv* env,
                                                                   jobject obj,
                                                                   jlong handle) {
    if (!handle) return 0;
    auto processor = reinterpret_cast<FrameProcessor*>(handle);
    return processor->get_frame_count();
}

/**
 * Get dropped frame count
 */
JNIEXPORT jlong JNICALL
Java_com_example_highfps_NativeFrameProcessor_nativeGetDroppedFrames(JNIEnv* env,
                                                                      jobject obj,
                                                                      jlong handle) {
    if (!handle) return 0;
    auto processor = reinterpret_cast<FrameProcessor*>(handle);
    return processor->get_dropped_frames();
}

/**
 * Get error count
 */
JNIEXPORT jlong JNICALL
Java_com_example_highfps_NativeFrameProcessor_nativeGetErrorCount(JNIEnv* env,
                                                                   jobject obj,
                                                                   jlong handle) {
    if (!handle) return 0;
    auto processor = reinterpret_cast<FrameProcessor*>(handle);
    return processor->get_errors();
}

/**
 * Get average processing time
 */
JNIEXPORT jdouble JNICALL
Java_com_example_highfps_NativeFrameProcessor_nativeGetAvgProcessingTime(JNIEnv* env,
                                                                          jobject obj,
                                                                          jlong handle) {
    if (!handle) return 0.0;
    auto processor = reinterpret_cast<FrameProcessor*>(handle);
    return processor->get_avg_processing_time_ms();
}

}  // extern "C"
