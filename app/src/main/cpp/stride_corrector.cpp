#include "stride_corrector.h"
#include <cstring>
#include <android/log.h>

#define LOG_TAG "HighFPS-NDK"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

void StrideCorrector::correct_stride(const uint8_t* src, int src_stride,
                                     uint8_t* dst, int dst_width,
                                     int dst_height) {
    if (!src || !dst) return;

    for (int y = 0; y < dst_height; ++y) {
        std::memcpy(&dst[y * dst_width],
                    &src[y * src_stride],
                    dst_width);
    }
}

int StrideCorrector::detect_stride(int width) {
    // Common alignments: 64-byte, 128-byte, or direct width
    int stride = width;

    // Round up to 128-byte boundary (common for modern Samsung sensors)
    int aligned = ((width + 127) / 128) * 128;
    stride = aligned;

    LOGD("Detected stride: %d for width %d", stride, width);
    return stride;
}
