#ifndef HIGHFPS_FRAME_BUFFER_H
#define HIGHFPS_FRAME_BUFFER_H

#include <cstdint>
#include <vector>
#include <queue>
#include <mutex>
#include <memory>

/**
 * Circular frame buffer for managing allocated memory
 * Pre-allocates buffers to avoid OOM crashes during capture
 * Thread-safe acquire/release pattern
 */
class FrameBuffer {
public:
    explicit FrameBuffer(size_t buffer_size, int num_buffers = 8);
    ~FrameBuffer();

    // Acquire buffer for writing
    uint8_t* acquire_buffer();

    // Release buffer back to pool
    void release_buffer(uint8_t* buffer);

    // Statistics
    int get_available_count() const;
    int get_total_count() const;

private:
    size_t buffer_size_;
    std::vector<std::vector<uint8_t>> buffers_;
    std::queue<uint8_t*> available_buffers_;
    std::mutex mutex_;
};

#endif // HIGHFPS_FRAME_BUFFER_H
