#include "frame_buffer.h"
#include <android/log.h>
#include <stdexcept>

#define LOG_TAG "HighFPS-NDK"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

FrameBuffer::FrameBuffer(size_t buffer_size, int num_buffers)
    : buffer_size_(buffer_size) {
    LOGD("Allocating %d buffers of %zu bytes each", num_buffers, buffer_size);

    for (int i = 0; i < num_buffers; ++i) {
        try {
            std::vector<uint8_t> buffer(buffer_size);
            uint8_t* ptr = buffer.data();
            buffers_.push_back(std::move(buffer));
            available_buffers_.push(ptr);
        } catch (const std::bad_alloc& e) {
            LOGW("Failed to allocate buffer %d: %s", i, e.what());
            break;
        }
    }

    LOGD("FrameBuffer initialized with %zu buffers", buffers_.size());
}

FrameBuffer::~FrameBuffer() {
    buffers_.clear();
    LOGD("FrameBuffer destroyed");
}

uint8_t* FrameBuffer::acquire_buffer() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (available_buffers_.empty()) {
        LOGW("No available buffers");
        return nullptr;
    }
    uint8_t* buffer = available_buffers_.front();
    available_buffers_.pop();
    return buffer;
}

void FrameBuffer::release_buffer(uint8_t* buffer) {
    if (!buffer) return;
    std::lock_guard<std::mutex> lock(mutex_);
    available_buffers_.push(buffer);
}

int FrameBuffer::get_available_count() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return available_buffers_.size();
}

int FrameBuffer::get_total_count() const {
    return buffers_.size();
}
