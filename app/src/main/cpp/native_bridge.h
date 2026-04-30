#ifndef HIGHFPS_NATIVE_BRIDGE_H
#define HIGHFPS_NATIVE_BRIDGE_H

#include <jni.h>
#include <memory>

class FrameProcessor;

/**
 * JNI bridge between Java and native frame processing
 */
class NativeBridge {
public:
    static void initialize_jni(JNIEnv* env);
    static std::unique_ptr<FrameProcessor> create_frame_processor(
        int width, int height, const char* output_dir);
};

#endif // HIGHFPS_NATIVE_BRIDGE_H
