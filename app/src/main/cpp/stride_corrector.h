#ifndef HIGHFPS_STRIDE_CORRECTOR_H
#define HIGHFPS_STRIDE_CORRECTOR_H

#include <cstdint>

/**
 * Samsung sensor stride correction utility
 * Some Samsung cameras use memory-aligned strides (e.g., 2048 bytes for 1920-wide)
 * This utility skips padding bytes to prevent frame skewing
 */
class StrideCorrector {
public:
    /**
     * Correct stride from source buffer with alignment padding
     * to destination buffer with no padding
     */
    static void correct_stride(const uint8_t* src, int src_stride,
                               uint8_t* dst, int dst_width, int dst_height);

    /**
     * Detect actual stride from sensor (heuristic)
     */
    static int detect_stride(int width);
};

#endif // HIGHFPS_STRIDE_CORRECTOR_H
